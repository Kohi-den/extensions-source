package eu.kanade.tachiyomi.animeextension.en.hanime

import android.annotation.SuppressLint

// ---------------------------------------------------------------------------
// Base64 Helper — Platform-aware Base64 delegation
// ---------------------------------------------------------------------------
// android.util.Base64 is unavailable in JVM unit tests. This thin wrapper
// delegates to the Android implementation in production and java.util.Base64
// in test environments, keeping the contract identical: decode a Base64
// string to raw bytes using DEFAULT (standard) encoding.
//
// Design: "Parse, Don't Validate" — the caller receives decoded bytes or an
// exception. No silent fallbacks. The platform switch is a single top-level
// function, so every consumer stays agnostic.
// ---------------------------------------------------------------------------

/**
 * Decodes a Base64-encoded string into raw bytes.
 *
 * Uses `android.util.Base64` on Android and `java.util.Base64` on the
 * JVM (unit tests). The behaviour matches `Base64.DEFAULT` on Android,
 * which is standard Base64 with line-break tolerance.
 *
 * @param input The Base64-encoded string.
 * @return The decoded byte array.
 * @throws IllegalArgumentException if [input] is not valid Base64.
 */
fun decodeBase64(input: String): ByteArray = Base64Provider.decode(input)

/**
 * Encodes a byte array into a Base64 string.
 *
 * Uses `android.util.Base64` on Android and `java.util.Base64` on the
 * JVM (unit tests). The behaviour matches `Base64.DEFAULT` on Android,
 * which includes line breaks every 76 characters.
 *
 * @param input The bytes to encode.
 * @return The Base64-encoded string.
 */
fun encodeBase64(input: ByteArray): String = Base64Provider.encode(input)

/**
 * Strategy interface for platform-specific Base64 codecs.
 *
 * Production code sets [Base64Provider.instance] to [AndroidBase64]
 * at class-load time. Test code can replace it with [JvmBase64]
 * before any decode/encode calls occur.
 */
internal object Base64Provider {

    var instance: Base64Codec = AndroidBase64

    fun decode(input: String): ByteArray = instance.decode(input)

    fun encode(input: ByteArray): String = instance.encode(input)
}

/** Abstraction over platform-specific Base64 implementations. */
internal interface Base64Codec {
    fun decode(input: String): ByteArray
    fun encode(input: ByteArray): String
}

/** Android implementation using android.util.Base64. */
private object AndroidBase64 : Base64Codec {
    override fun decode(input: String): ByteArray = android.util.Base64.decode(input, android.util.Base64.DEFAULT)

    override fun encode(input: ByteArray): String = android.util.Base64.encodeToString(input, android.util.Base64.DEFAULT)
}

/** JVM implementation using java.util.Base64 (available since API 26 / Java 8).
 *
 * This implementation is **only** used in JVM unit tests where Java 8+ is
 * guaranteed. It is never instantiated on Android devices; production code
 * uses [AndroidBase64] instead. The @SuppressLint("NewApi") annotation
 * suppresses lint's API-level check because this code path is unreachable
 * on Android runtimes below API 26.
 */
@SuppressLint("NewApi")
object JvmBase64 : Base64Codec {
    override fun decode(input: String): ByteArray = java.util.Base64.getMimeDecoder().decode(input)

    override fun encode(input: ByteArray): String = java.util.Base64.getMimeEncoder().encodeToString(input)
}
