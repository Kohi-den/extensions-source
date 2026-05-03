package eu.kanade.tachiyomi.animeextension.en.hanime

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

// ---------------------------------------------------------------------------
// WASM Binary Extractor — Fetches and decodes the inline WASM from vendor.js
// ---------------------------------------------------------------------------
// hanime.tv embeds its signature WASM as a base64-encoded string inside the
// vendor.js bundle. This object discovers the vendor.js URL from the homepage,
// fetches it, scans for base64 blobs, and returns the one that decodes to a
// valid WASM binary (identified by the magic number \\0asm).
//
// Design: "Parse, Don't Validate" — each base64 candidate is decoded at the
// boundary and checked against the WASM magic number. Only a confirmed match
// is returned; everything else is discarded. Callers receive a trusted byte
// array that is guaranteed to be a valid WASM module header.
// ---------------------------------------------------------------------------

/**
 * Extracts the WASM binary from hanime.tv's vendor.js JavaScript bundle.
 *
 * The WASM binary is NOT fetched from a URL — it is base64-encoded inline
 * in the vendor.js file and decoded at runtime by the site's JavaScript
 * via `WebAssembly.compile(Uint8Array.from(atob("…")))` or similar patterns.
 *
 * This object replicates that extraction in Kotlin: it fetches the homepage
 * to discover the current vendor.js URL, downloads the JS bundle, scans for
 * base64 strings, and returns the one whose decoded bytes start with the
 * WASM magic number (`\0asm`).
 */
object HanimeWasmBinary {

    /** The WASM magic number: `\0asm` (0x00 0x61 0x73 0x6D). */
    private val WASM_MAGIC = byteArrayOf(0x00, 0x61, 0x73, 0x6D)

    /** hanime.tv homepage — used to discover the vendor.js script URL. */
    private const val HANIME_HOME = "https://hanime.tv"

    /** Maximum number of retry attempts for fetching the WASM binary. */
    private const val MAX_FETCH_RETRIES = 2

    /** Log tag for debug output. */
    private const val TAG = "HanimeWasmBinary"

    /**
     * Fetch and extract the WASM binary from hanime.tv's vendor.js bundle.
     *
     * The process is:
     * 1. Fetch the hanime.tv homepage HTML.
     * 2. Extract the vendor.js URL from `<script>` tags.
     * 3. Fetch the vendor.js content.
     * 4. Scan for base64 strings and return the one that decodes to a WASM binary.
     *
     * Retries up to [MAX_FETCH_RETRIES] times on failure, with an increasing
     * delay between attempts to allow transient network issues to resolve.
     *
     * @param client OkHttp client to use for HTTP requests.
     * @return The raw WASM binary bytes.
     * @throws WasmExtractionException if the binary cannot be extracted after all retries.
     */
    suspend fun fetchWasmBinary(client: OkHttpClient): ByteArray {
        var lastException: Exception? = null
        repeat(MAX_FETCH_RETRIES) { attempt ->
            val attemptNum = attempt + 1
            Log.d(TAG, "fetchWasmBinary: attempt $attemptNum of $MAX_FETCH_RETRIES")
            try {
                return withContext(Dispatchers.IO) {
                    Log.d(TAG, "fetchWasmBinary: fetching homepage $HANIME_HOME")
                    val html = fetchPage(client, HANIME_HOME)
                    val vendorJsUrl = extractVendorJsUrl(html)
                        ?: throw WasmExtractionException("Could not find vendor.js URL in hanime.tv HTML")
                    Log.d(TAG, "fetchWasmBinary: resolved vendor.js URL: $vendorJsUrl")
                    val vendorJs = fetchPage(client, vendorJsUrl)
                    extractWasmFromVendorJs(vendorJs)
                }
            } catch (e: Exception) {
                lastException = e
                Log.e(TAG, "fetchWasmBinary: attempt $attemptNum failed — ${e.javaClass.simpleName}: ${e.message}")
                if (attempt < MAX_FETCH_RETRIES - 1) {
                    val delayMs = 1000L * (attempt + 1)
                    Log.d(TAG, "fetchWasmBinary: retrying after ${delayMs}ms delay")
                    // Brief delay before retry to allow transient failures to resolve
                    delay(delayMs)
                }
            }
        }
        Log.e(TAG, "fetchWasmBinary: all $MAX_FETCH_RETRIES attempts exhausted, lastException: ${lastException?.message}")
        throw WasmExtractionException("Failed to fetch WASM binary after $MAX_FETCH_RETRIES attempts: ${lastException?.message}", lastException)
    }

    /**
     * Extract the WASM binary from a vendor.js string.
     *
     * Scans for base64-encoded strings (minimum 100 characters to filter noise),
     * decodes each candidate, and checks whether the decoded bytes start with the
     * WASM magic number (`\0asm`). Returns the first match.
     *
     * Recognised JavaScript patterns:
     * - `WebAssembly.compile(Uint8Array.from(atob("AGFzbQE…")))`
     * - `new Uint8Array(Base64.decode("AGFzbQE…"))`
     * - Any large quoted base64 string that decodes to valid WASM.
     *
     * @param vendorJs The full text content of the vendor.js bundle.
     * @return The decoded WASM binary bytes.
     * @throws WasmExtractionException if no WASM binary is found.
     */
    fun extractWasmFromVendorJs(vendorJs: String): ByteArray {
        Log.d(TAG, "extractWasmFromVendorJs: vendor.js content length = ${vendorJs.length} chars")

        val base64Pattern = Regex("""["']([A-Za-z0-9+/=]{100,})["']""")

        val matches = base64Pattern.findAll(vendorJs).toList()
        Log.d(TAG, "extractWasmFromVendorJs: found ${matches.size} base64 candidate(s) in vendor.js")

        if (matches.isEmpty()) {
            throw WasmExtractionException("No base64 strings found in vendor.js")
        }

        for ((index, match) in matches.withIndex()) {
            val base64Str = match.groupValues[1]
            Log.d(TAG, "extractWasmFromVendorJs: candidate #${index + 1} length = ${base64Str.length} chars")

            val decoded = try {
                decodeBase64(base64Str)
            } catch (e: Exception) {
                Log.d(TAG, "extractWasmFromVendorJs: candidate #${index + 1} decode failed — ${e.javaClass.simpleName}: ${e.message}")
                // Not valid base64 — skip
                continue
            }

            val hasWasmMagic = decoded.size >= WASM_MAGIC.size &&
                decoded.sliceArray(0 until WASM_MAGIC.size).contentEquals(WASM_MAGIC)
            Log.d(TAG, "extractWasmFromVendorJs: candidate #${index + 1} decoded to ${decoded.size} bytes, WASM magic match = $hasWasmMagic")

            if (hasWasmMagic) {
                Log.d(TAG, "extractWasmFromVendorJs: valid WASM binary found — ${decoded.size} bytes")
                return decoded
            }
        }

        Log.w(TAG, "extractWasmFromVendorJs: all ${matches.size} base64 candidates exhausted, none matched WASM magic")
        throw WasmExtractionException("Could not find WASM binary in vendor.js")
    }

    /**
     * Extract the vendor.js URL from the hanime.tv HTML.
     *
     * Looks for `<script src="…vendor….js">` tags and returns the matching URL.
     * Relative paths are resolved against `https://hanime.tv`.
     *
     * @param html The hanime.tv homepage HTML.
     * @return The fully-qualified vendor.js URL, or `null` if not found.
     */
    fun extractVendorJsUrl(html: String): String? {
        Log.d(TAG, "extractVendorJsUrl: HTML content length = ${html.length} chars")

        // Primary pattern: look for vendor.js in script src attributes
        val primaryPattern = Regex("""src=["']([^"']*vendor[^"']*\.js)["']""")
        val primaryMatch = primaryPattern.find(html)
        if (primaryMatch != null) {
            val path = primaryMatch.groupValues[1]
            val resolved = resolveUrl(path)
            Log.d(TAG, "extractVendorJsUrl: primary pattern matched path: $path, resolved: $resolved")
            return resolved
        }
        Log.d(TAG, "extractVendorJsUrl: primary vendor.js pattern found no match, trying fallback")

        // Fallback pattern: any JS bundle that might contain the WASM binary
        // Sites sometimes rename bundles — look for large app/build bundles
        val fallbackPattern = Regex("""src=["']([^"']*\d{8,}[^"']*\.js)["']""")
        val fallbackMatches = fallbackPattern.findAll(html).toList()
        Log.d(TAG, "extractVendorJsUrl: fallback pattern found ${fallbackMatches.size} match(es)")
        for (match in fallbackMatches) {
            val path = match.groupValues[1]
            val resolved = resolveUrl(path) ?: continue
            Log.d(TAG, "extractVendorJsUrl: trying fallback URL: $resolved")
            return resolved
        }

        Log.w(TAG, "extractVendorJsUrl: no vendor.js URL found (both primary and fallback patterns exhausted)")
        return null
    }

    private fun resolveUrl(path: String): String? = if (path.startsWith("http")) {
        path
    } else {
        try {
            HANIME_HOME.toHttpUrl().resolve(path)?.toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeBase64(base64Str: String): ByteArray {
        return android.util.Base64.decode(base64Str, android.util.Base64.DEFAULT)
    }

    /**
     * Fetch a page's text content via HTTP GET.
     *
     * @param client OkHttp client to use.
     * @param url The URL to fetch.
     * @return The response body as a string.
     * @throws WasmExtractionException on HTTP failure or empty body.
     */
    private fun fetchPage(client: OkHttpClient, url: String): String {
        Log.d(TAG, "fetchPage: requesting URL: $url")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36")
            .header("Accept", "text/html,application/javascript,*/*")
            .build()

        val timeoutClient = client.newBuilder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val response = timeoutClient.newCall(request).execute()

        return response.use {
            Log.d(TAG, "fetchPage: HTTP ${it.code} for $url")
            if (!it.isSuccessful) {
                Log.e(TAG, "fetchPage: HTTP ${it.code} fetching $url — request failed")
                throw WasmExtractionException("HTTP ${it.code} fetching $url")
            }
            val body = it.body.string()
            if (body.isBlank()) throw WasmExtractionException("Empty vendor.js body for $url")
            Log.d(TAG, "fetchPage: response body length = ${body.length} chars for $url")
            body
        }
    }

    /**
     * Thrown when the WASM binary cannot be extracted from the vendor.js bundle.
     */
    class WasmExtractionException(message: String, cause: Throwable? = null) : Exception(message, cause)
}
