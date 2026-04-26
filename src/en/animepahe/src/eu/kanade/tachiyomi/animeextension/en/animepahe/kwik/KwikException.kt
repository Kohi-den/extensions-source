package eu.kanade.tachiyomi.animeextension.en.animepahe.kwik

sealed class KwikException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class ExtractionException(message: String, cause: Throwable? = null) : KwikException(message, cause)
    class CloudflareBlockedException(message: String) : KwikException(message)
}
