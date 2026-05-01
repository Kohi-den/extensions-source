package eu.kanade.tachiyomi.animeextension.en.hanime

import android.util.Log

// ─────────────────────────────────────────────────────────────────────────────
// Signature data model
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Represents a single hanime.tv API signature bundle.
 *
 * @property signature 64-character hex string sent as the `x-signature` header.
 * @property time       Unix timestamp string sent as the `x-time` header.
 * @property createdAt  Epoch-millis timestamp of when this signature was obtained,
 *                       used to determine expiry.
 */
data class Signature(
    val signature: String,
    val time: String,
    val createdAt: Long = System.currentTimeMillis(),
) {
    /**
     * Returns `true` when this signature is older than [ttlMs] milliseconds.
     */
    fun isExpired(ttlMs: Long): Boolean = System.currentTimeMillis() - createdAt > ttlMs

    /**
     * Validates that this signature has the expected format:
     * - `signature` is exactly 64 lowercase hexadecimal characters
     * - `time` is a valid Unix timestamp within a reasonable window
     *
     * @param maxAgeMs Maximum age in milliseconds for the timestamp to be considered valid.
     * @throws SignatureException if validation fails.
     */
    fun validate(maxAgeMs: Long = 5 * 60 * 1000L) {
        if (!signature.matches(SIGNATURE_PATTERN)) {
            Log.e("SignatureProvider", "validate FAILED: signature format invalid (length=${signature.length})")
            throw SignatureException("Invalid signature format: expected 64 lowercase hex chars, got length=${signature.length}")
        }

        val timeValue = time.toLongOrNull()
        if (timeValue == null) {
            Log.e("SignatureProvider", "validate FAILED: timestamp '$time' is not a valid number")
            throw SignatureException("Invalid timestamp: '$time' is not a valid number")
        }

        val now = System.currentTimeMillis() / 1000L
        val ageMs = (now - timeValue) * 1000L

        if (ageMs > maxAgeMs || ageMs < -CLOCK_SKEW_TOLERANCE_MS) {
            Log.e("SignatureProvider", "validate FAILED: timestamp too far from current time (ageMs=$ageMs, maxAgeMs=$maxAgeMs)")
            throw SignatureException("Timestamp $timeValue is too far from current time (age=${ageMs}ms, maxAge=${maxAgeMs}ms)")
        }
    }

    companion object {
        /** Regex pattern for validating signature format: exactly 64 lowercase hex characters. */
        private val SIGNATURE_PATTERN = Regex("^[0-9a-f]{64}$")

        /** Tolerance for clock skew between client and server (60 seconds). */
        private const val CLOCK_SKEW_TOLERANCE_MS = 60_000L
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Signature provider interface
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Supplies fresh [Signature] instances for authenticating hanime.tv API requests.
 *
 * Implementations may involve heavy work (e.g. WebView load, WASM execution),
 * so callers should avoid calling on the main thread.
 */
interface SignatureProvider {

    /**
     * Obtain a fresh signature.  May involve heavy work such as loading a
     * WebView or executing WASM — avoid calling on the main thread.
     */
    suspend fun getSignature(): Signature

    /** Human-readable label for this provider (useful in logs / preferences). */
    val name: String

    /** Release any held resources (WebView, memory, etc.). Default is a no-op. */
    fun close() {}
}

// ─────────────────────────────────────────────────────────────────────────────
// Signature headers helper
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Builds the set of HTTP headers that hanime.tv requires alongside every
 * authenticated API request.
 */
object SignatureHeaders {

    /**
     * Construct the full header map from a [Signature].
     *
     * Includes the two dynamic signature headers (`x-signature`, `x-time`)
     * plus the static headers that the server expects.
     */
    fun build(signature: Signature): Map<String, String> {
        Log.d("SignatureProvider", "Building signature headers (length=${signature.signature.length})")
        return mapOf(
            "x-signature" to signature.signature,
            "x-time" to signature.time,
            "x-signature-version" to "web2",
            "x-session-token" to "",
            "x-user-license" to "",
            "x-csrf-token" to "",
            "x-license" to "",
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Domain-specific exception
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Thrown when signature extraction fails or times out.
 *
 * @param message Human-readable description of the failure.
 * @param cause The underlying exception, if any.
 */
class SignatureException(message: String, cause: Throwable? = null) : Exception(message, cause)
