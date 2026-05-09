package eu.kanade.tachiyomi.animeextension.en.hstream

import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * URL and string parsing utilities for Hstream extension.
 * Extracted for testability — pure functions with no Android dependencies
 * beyond OkHttp's [toHttpUrl] in [normalizeHref].
 */
object HstreamUtils {

    /** Matches a trailing `-{digits}` at the end of a string, capturing the prefix and the number. */
    val REGEX_TRAILING_EP_NUM = Regex("^(.+)-(\\d+)/?$")

    /** Matches a trailing ` - {digits}` suffix in display text (e.g., alt text). */
    val REGEX_EPISODE_SUFFIX = Regex("\\s*-\\s*\\d+$")

    /**
     * Convert an episode URL path to a series URL path by stripping the trailing -{epNum}.
     * e.g., "/hentai/slug-name-1" → "/hentai/slug-name"
     */
    fun String.toSeriesUrl(): String = REGEX_TRAILING_EP_NUM.replace(this) { it.groupValues[1] }

    /**
     * Convert an episode slug to a series slug by stripping the trailing -{epNum}.
     * e.g., "slug-name-1" → "slug-name"
     */
    fun String.toSeriesSlug(): String = REGEX_TRAILING_EP_NUM.replace(this) { it.groupValues[1] }

    /**
     * Extract the episode number from an episode URL path.
     * e.g., "/hentai/slug-name-1" → "1"
     * Returns null if the URL doesn't end with a trailing -{digits}.
     */
    fun String.extractEpisodeNumber(): String? = REGEX_TRAILING_EP_NUM.find(this)?.groupValues?.get(2)

    /**
     * Strip a trailing episode suffix from a display title.
     * e.g., "Series Name - 1" → "Series Name"
     * e.g., "Series Name-3" → "Series Name"
     */
    fun String.stripEpisodeSuffix(): String = REGEX_EPISODE_SUFFIX.replace(this, "")

    /**
     * Normalize an href attribute to a relative path.
     * If the href is an absolute URL, extract just the encoded path.
     * If it's already a relative path, return it as-is.
     */
    fun String.normalizeHref(): String = if (startsWith("http")) {
        try {
            val result = toHttpUrl().encodedPath
            HstreamLogger.debug("normalizeHref", "'$this' -> '$result'")
            result
        } catch (e: Exception) {
            HstreamLogger.error("normalizeHref", "FAILED for '$this'", e)
            this // fallback: return raw href
        }
    } else {
        this
    }
}
