package eu.kanade.tachiyomi.animeextension.en.hanime

import java.security.MessageDigest

/**
 * Signature provider that computes the hanime.tv signature natively via
 * direct SHA-256 hashing, replacing WASM execution and WebView extraction.
 *
 * ## Algorithm
 *
 * The hanime.tv API signature is computed as:
 * ```
 * SHA256("${timestamp},Xkdi29,https://hanime.tv,mn2,${timestamp}")
 * ```
 *
 * Where:
 * - `timestamp` = `System.currentTimeMillis() / 1000L` (Unix seconds)
 * - `Xkdi29` = static salt embedded in the site's JavaScript
 * - `https://hanime.tv` = the origin/origin parameter
 * - `mn2` = static salt for the signature version
 *
 * The resulting 32-byte hash is formatted as a 64-character lowercase
 * hexadecimal string and sent as the `x-signature` header alongside
 * the timestamp in the `x-time` header.
 *
 * ## Why this exists
 *
 * The original WASM binary (emscripten-compiled) computes this same hash,
 * but the Chicory WASM runtime stubs several JS environment functions
 * (`crypto.getRandomValues`, `performance.now`, etc.), causing the
 * WASM code to produce incorrect signatures. This provider bypasses
 * WASM entirely by computing the SHA-256 directly in the JVM.
 *
 * ## Thread safety
 *
 * This provider is thread-safe. [MessageDigest] is created fresh per
 * [getSignature] call, so no mutable shared state exists.
 */
open class NativeSignatureProvider : SignatureProvider {

    companion object {
        /** First salt embedded in the hanime.tv signature algorithm. */
        private const val SALT_1 = "Xkdi29"

        /** The origin value used in the signature input. */
        private const val ORIGIN = "https://hanime.tv"

        /** Second salt embedded in the hanime.tv signature algorithm. */
        private const val SALT_2 = "mn2"
    }

    override val name: String = "native"

    /**
     * Timestamp source — returns current Unix time in seconds.
     * Overridable in tests to pin a fixed timestamp for known-answer verification.
     */
    protected open val timestampProvider: () -> Long = { System.currentTimeMillis() / 1000L }

    /**
     * Compute a fresh signature by hashing the current timestamp.
     *
     * The input format is: `{timestamp},{SALT_1},{ORIGIN},{SALT_2},{timestamp}`
     * The hash is SHA-256, formatted as 64 lowercase hex characters.
     */
    override suspend fun getSignature(): Signature {
        val timestamp = timestampProvider()
        val input = "$timestamp,$SALT_1,$ORIGIN,$SALT_2,$timestamp"
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        val hex = hashBytes.joinToString("") { "%02x".format(it) }
        return Signature(signature = hex, time = timestamp.toString())
    }

    /** No resources to release — this is a no-op. */
    override fun close() {}
}
