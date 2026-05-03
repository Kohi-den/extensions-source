package eu.kanade.tachiyomi.animeextension.en.hanime

import android.annotation.SuppressLint
import android.app.Application
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// ---------------------------------------------------------------------------
// WebView-based signature extraction for hanime.tv
// ---------------------------------------------------------------------------
// Loads hanime.tv in a headless WebView, waits for the WASM signature
// generator to initialise, and extracts `window.ssignature` and
// `window.stime` via [WebView.evaluateJavascript]. If the WASM module
// has not yet produced a signature, the 'e' DOM event is dispatched to
// trigger the generation pipeline, and extraction is retried until a
// timeout is reached.
//
// Thread safety:
// - [signatureMutex] serializes [getSignature] calls so only one WebView
//   is active at a time, preventing the "Already resumed" crash that
//   occurred when two concurrent calls each created a WebView whose poll
//   loop tried to resume the same continuation.
// - [resumed] AtomicBoolean guards all resume paths (poll success, poll
//   timeout, SSL error, page error) so only the FIRST path to invoke
//   [resumeOrDestroy] wins — even if two paths race.
// - [cachedSignature] avoids redundant WebView loads when the signature
//   is still within its TTL.
// ---------------------------------------------------------------------------

/**
 * Extracts hanime.tv request signatures by loading the site in a WebView
 * and reading the `window.ssignature` / `window.stime` values that the
 * client-side WASM binary produces.
 *
 * Flow:
 * 1. Create a [WebView] on the main thread via [Handler].
 * 2. Navigate to `https://hanime.tv`.
 * 3. On [WebViewClient.onPageFinished], wait for WASM initialisation.
 * 4. Poll `window.ssignature` / `window.stime` via [evaluateJavascript].
 * 5. If not yet available, dispatch the `'e'` event to trigger WASM
 *   signature generation.
 * 6. Deliver the [Signature] through the [JavascriptInterface] callback.
 * 7. Destroy the WebView on the main thread.
 */
class WebViewSignatureProvider : SignatureProvider {

    override val name: String = "WebView"

    private val context: Application by injectLazy()
    private val handler = Handler(Looper.getMainLooper())

    /** Serializes [getSignature] calls so only one WebView is active at a time. */
    private val signatureMutex = Mutex()

    /** Active WebView reference for [close] cleanup. */
    @Volatile
    private var activeWebView: WebView? = null

    /** Cached signature to avoid redundant WebView loads within the TTL. */
    @Volatile
    private var cachedSignature: Signature? = null

    override suspend fun getSignature(): Signature = withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
        signatureMutex.withLock {
            // Fast path: return cached signature if still valid
            cachedSignature?.let { cached ->
                if (!cached.isExpired(SIGNATURE_CACHE_TTL_MS)) {
                    Log.d(TAG, "getSignature: returning cached signature (age=${System.currentTimeMillis() - cached.createdAt}ms)")
                    return@withLock cached
                }
                Log.d(TAG, "getSignature: cached signature expired, generating new one")
            }

            Log.d(TAG, "getSignature: entry -- timeout = ${TOTAL_TIMEOUT_MS}ms")
            suspendCancellableCoroutine<Signature> { continuation ->
                var webView: WebView? = null

                handler.post {
                    try {
                        Log.d(TAG, "getSignature: creating WebView on main thread")
                        val wv = createWebView()
                        webView = wv
                        activeWebView = wv
                        configureWebView(wv, continuation)
                        Log.d(TAG, "getSignature: loading URL https://hanime.tv")
                        wv.loadUrl("https://hanime.tv")
                    } catch (e: Exception) {
                        Log.e(TAG, "getSignature: failed to create WebView -- ${e.javaClass.simpleName}: ${e.message}")
                        continuation.resumeWithException(
                            SignatureException("Failed to create WebView: ${e.message}", e),
                        )
                    }
                }

                continuation.invokeOnCancellation {
                    handler.post {
                        webView?.destroy()
                        if (activeWebView === webView) activeWebView = null
                    }
                }
            }.also { signature ->
                cachedSignature = signature
                Log.d(TAG, "getSignature: signature cached (length=${signature.signature.length})")
            }
        }
    } ?: throw SignatureException("WebView signature extraction timed out after ${TOTAL_TIMEOUT_MS}ms")

    override fun close() {
        cachedSignature = null
        val wv = activeWebView
        Log.d(TAG, "close: called -- activeWebView present = ${wv != null}")
        if (wv != null) {
            handler.post {
                wv.destroy()
                if (activeWebView === wv) activeWebView = null
            }
        }
    }

    /**
     * Creates a new [WebView] with JavaScript and DOM storage enabled.
     *
     * The caller is responsible for setting a [WebViewClient] and loading
     * a URL -- this method only configures the [WebSettings].
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val wv = WebView(context)
        Log.d(TAG, "createWebView: WebView instance created")

        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = USER_AGENT
        }
        Log.d(TAG, "createWebView: JS enabled, UA = ${USER_AGENT.take(30)}...")

        return wv
    }

    /**
     * Attaches a [WebViewClient] and [JavascriptInterface] to [webView],
     * then starts the signature extraction pipeline once the page loads.
     */
    private fun configureWebView(
        webView: WebView,
        continuation: CancellableContinuation<Signature>,
    ) {
        val jsInterface = SignatureJsInterface()
        webView.addJavascriptInterface(jsInterface, JS_INTERFACE_NAME)
        Log.d(TAG, "configureWebView: JS interface '$JS_INTERFACE_NAME' added")

        // AtomicBoolean guard: only the FIRST code path to call
        // compareAndSet(false, true) wins — all others are skipped.
        // This prevents the "Already resumed" IllegalStateException
        // even when multiple paths race (e.g. poll finds a signature
        // AND onPageFinished fires again due to a redirect).
        val resumed = AtomicBoolean(false)

        fun isResumable() = !continuation.isCancelled && resumed.compareAndSet(false, true)

        webView.webViewClient = object : WebViewClient() {

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "configureWebView: onPageFinished -- url: $url")

                // Only schedule the first poll if we haven't already resumed.
                // onPageFinished fires multiple times (e.g. redirects:
                // https://hanime.tv -> https://hanime.tv/home), and each
                // firing would otherwise schedule a new poll chain.
                if (resumed.get() || continuation.isCancelled) return

                Log.d(TAG, "configureWebView: scheduling first poll after ${WASM_INIT_DELAY_MS}ms WASM init delay")
                handler.postDelayed(
                    { pollForSignature(webView, jsInterface, continuation, resumed) },
                    WASM_INIT_DELAY_MS,
                )
            }

            override fun onReceivedSslError(
                view: WebView?,
                sslHandler: SslErrorHandler?,
                error: SslError?,
            ) {
                Log.e(TAG, "configureWebView: SSL error -- ${error?.toString() ?: "unknown"}")
                sslHandler?.cancel()
                if (isResumable()) {
                    resumeOrDestroy(webView, continuation, resumed) {
                        continuation.resumeWithException(
                            SignatureException("SSL error loading hanime.tv: ${error?.toString() ?: "unknown"}"),
                        )
                    }
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                Log.e(TAG, "configureWebView: page load error -- ${error?.description ?: "unknown"} (errorCode=${error?.errorCode}), isForMainFrame=${request?.isForMainFrame}")
                // Only fast-fail for main frame requests -- subresource errors are non-fatal
                if (request?.isForMainFrame == true && isResumable()) {
                    resumeOrDestroy(webView, continuation, resumed) {
                        continuation.resumeWithException(
                            SignatureException(
                                "Page load failed: ${error?.description ?: "unknown error"} " +
                                    "(errorCode=${error?.errorCode})",
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * Repeatedly checks whether `window.ssignature` and `window.stime`
     * have been set by the WASM module. If the values are not yet
     * available, dispatches the `'e'` event to trigger signature
     * generation and retries after a short delay.
     */
    private fun pollForSignature(
        webView: WebView,
        jsInterface: SignatureJsInterface,
        continuation: CancellableContinuation<Signature>,
        resumed: AtomicBoolean,
    ) {
        val deadline = System.currentTimeMillis() + SIGNATURE_POLL_TIMEOUT_MS
        Log.d(TAG, "pollForSignature: starting -- deadline in ${SIGNATURE_POLL_TIMEOUT_MS}ms")

        fun poll() {
            if (continuation.isCancelled || resumed.get()) return
            val now = System.currentTimeMillis()
            if (now > deadline) {
                Log.w(TAG, "pollForSignature: timeout reached -- ${SIGNATURE_POLL_TIMEOUT_MS}ms elapsed, no signature")
                resumeOrDestroy(webView, continuation, resumed) {
                    continuation.resumeWithException(
                        SignatureException("Signature not available after ${SIGNATURE_POLL_TIMEOUT_MS}ms of polling"),
                    )
                }
                return
            }

            // Check result from any PREVIOUS evaluateJavascript call first.
            // evaluateJavascript is asynchronous -- the JS callback fires on the
            // next main-thread Looper iteration, so the result of the current
            // call won't be available until after poll() returns.
            val result = jsInterface.getResult()
            if (result != null) {
                Log.d(TAG, "pollForSignature: result obtained from jsInterface -- signature length=${result.signature.length}")
                resumeOrDestroy(webView, continuation, resumed) {
                    continuation.resume(result)
                }
                return
            }

            // No result yet -- execute the polling script and schedule a
            // follow-up check after POLL_INTERVAL_MS. The script's JS callback
            // will have fired by then.
            Log.d(TAG, "pollForSignature: no result yet -- calling evaluateJavascript (time remaining: ${deadline - now}ms)")
            webView.evaluateJavascript(POLL_SCRIPT, null)
            handler.postDelayed({ poll() }, POLL_INTERVAL_MS)
        }

        poll()
    }

    /**
     * Executes [action] to resume the continuation, then destroys the
     * WebView on the main thread to release resources.
     *
     * Uses [resumed] [AtomicBoolean] to guarantee that only the FIRST
     * caller wins — subsequent callers are silently skipped. This prevents
     * the "Already resumed" [IllegalStateException] when multiple code
     * paths race (e.g. poll success vs. SSL error).
     */
    private fun resumeOrDestroy(
        webView: WebView,
        continuation: CancellableContinuation<Signature>,
        resumed: AtomicBoolean,
        action: () -> Unit,
    ) {
        if (!resumed.compareAndSet(false, true)) {
            Log.w(TAG, "resumeOrDestroy: already resumed, skipping")
            return
        }
        action()
        handler.post {
            webView.destroy()
            if (activeWebView === webView) activeWebView = null
        }
    }

    /**
     * Receives signature values from the JavaScript environment via
     * [WebView.addJavascriptInterface].
     *
     * The JS polling script calls [onSignatureReady] when both
     * `window.ssignature` and `window.stime` are available, and
     * [onSignatureNotReady] when they are not yet set.
     */
    inner class SignatureJsInterface {

        @Volatile
        private var signatureResult: Signature? = null

        /** Called from JS when both `window.ssignature` and `window.stime` are set. */
        @JavascriptInterface
        fun onSignatureReady(signature: String, time: String) {
            Log.d(TAG, "SignatureJsInterface: onSignatureReady -- signature length=${signature.length}, time=$time")
            signatureResult = Signature(signature, time)
        }

        /** Called from JS when the signature values are not yet available. */
        @JavascriptInterface
        fun onSignatureNotReady() {
            Log.d(TAG, "SignatureJsInterface: onSignatureNotReady -- WASM signature not yet available")
            // No-op -- the poll loop will retry after POLL_INTERVAL_MS
        }

        /** Returns the captured signature, or `null` if not yet available. */
        fun getResult(): Signature? = signatureResult
    }

    companion object {
        private const val TAG = "WebViewSigProvider"

        /** Maximum time to wait for the page to finish loading. */
        private const val PAGE_LOAD_TIMEOUT_MS = 30_000L

        /** Maximum time to poll for the signature after the page loads. */
        private const val SIGNATURE_POLL_TIMEOUT_MS = 15_000L

        /** Interval between signature availability checks. */
        private const val POLL_INTERVAL_MS = 500L

        /** Delay after onPageFinished before the first poll -- gives WASM time to initialise. */
        private const val WASM_INIT_DELAY_MS = 2_000L

        /** Combined timeout for the entire operation. */
        private const val TOTAL_TIMEOUT_MS = PAGE_LOAD_TIMEOUT_MS + SIGNATURE_POLL_TIMEOUT_MS

        /** Time-to-live for cached signatures before a fresh WebView load is required. */
        private const val SIGNATURE_CACHE_TTL_MS = 120_000L // 2 minutes

        /** Name exposed to JavaScript via `addJavascriptInterface`. */
        private const val JS_INTERFACE_NAME = "AndroidInterface"

        /**
         * User agent string mimicking a recent Chrome on Android device.
         * Must be mobile-class so hanime.tv serves the correct WASM payload.
         */
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

        /**
         * JavaScript executed on each poll iteration.
         *
         * 1. If `window.ssignature` and `window.stime` are already set,
         *    delivers them to [SignatureJsInterface.onSignatureReady].
         * 2. If the WASM exports object exists but no signature yet,
         *    dispatches the `'e'` event to trigger generation, then
         *    polls again after a 1-second delay.
         * 3. If WASM has not loaded at all, signals
         *    [SignatureJsInterface.onSignatureNotReady] so the Kotlin
         *    side can retry.
         */
        private val POLL_SCRIPT = """
(function() {
  if (window.ssignature && window.stime) {
    __JS_INTERFACE__.onSignatureReady(
      window.ssignature,
      window.stime.toString()
    );
  } else if (window.wasmExports) {
    window.dispatchEvent(new Event('e'));
    setTimeout(function() {
      if (window.ssignature && window.stime) {
        __JS_INTERFACE__.onSignatureReady(
          window.ssignature,
          window.stime.toString()
        );
      }
    }, 1000);
  } else {
    __JS_INTERFACE__.onSignatureNotReady();
  }
})();
        """.trimIndent().replace("__JS_INTERFACE__", JS_INTERFACE_NAME)
    }
}
