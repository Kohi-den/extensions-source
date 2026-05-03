package eu.kanade.tachiyomi.animeextension.en.hstream

import eu.kanade.tachiyomi.animeextension.en.hstream.HstreamUtils.extractEpisodeNumber
import eu.kanade.tachiyomi.animeextension.en.hstream.HstreamUtils.normalizeHref
import eu.kanade.tachiyomi.animeextension.en.hstream.HstreamUtils.stripEpisodeSuffix
import eu.kanade.tachiyomi.animeextension.en.hstream.HstreamUtils.toSeriesSlug
import eu.kanade.tachiyomi.animeextension.en.hstream.HstreamUtils.toSeriesUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HstreamUtilsTest {

    // ========================== toSeriesUrl() ==========================

    @Test
    fun `toSeriesUrl strips trailing episode number from URL`() {
        assertEquals("/hentai/slug-name", "/hentai/slug-name-1".toSeriesUrl())
    }

    @Test
    fun `toSeriesUrl handles multi-episode URLs`() {
        assertEquals("/hentai/slug-name", "/hentai/slug-name-12".toSeriesUrl())
    }

    @Test
    fun `toSeriesUrl preserves numbers in series title`() {
        // "4" is part of the series name, "1" is the episode number
        assertEquals(
            "/hentai/jk-to-inkou-kyoushi-4-feat-ero-giin-sensei",
            "/hentai/jk-to-inkou-kyoushi-4-feat-ero-giin-sensei-1".toSeriesUrl(),
        )
    }

    @Test
    fun `toSeriesUrl returns unchanged URL when no trailing episode number`() {
        // A series URL (no trailing -N) should be returned as-is
        assertEquals("/hentai/slug-name", "/hentai/slug-name".toSeriesUrl())
    }

    @Test
    fun `toSeriesUrl handles single-character slug`() {
        assertEquals("/hentai/a", "/hentai/a-1".toSeriesUrl())
    }

    // ========================== toSeriesSlug() ==========================

    @Test
    fun `toSeriesSlug strips trailing episode number from slug`() {
        assertEquals("slug-name", "slug-name-1".toSeriesSlug())
    }

    @Test
    fun `toSeriesSlug preserves numbers in slug`() {
        assertEquals(
            "jk-to-inkou-kyoushi-4-feat-ero-giin-sensei",
            "jk-to-inkou-kyoushi-4-feat-ero-giin-sensei-1".toSeriesSlug(),
        )
    }

    @Test
    fun `toSeriesSlug handles slug without episode number`() {
        assertEquals("slug-name", "slug-name".toSeriesSlug())
    }

    // ======================== extractEpisodeNumber() ========================

    @Test
    fun `extractEpisodeNumber returns trailing number from URL`() {
        assertEquals("1", "/hentai/slug-name-1".extractEpisodeNumber())
    }

    @Test
    fun `extractEpisodeNumber returns multi-digit episode number`() {
        assertEquals("12", "/hentai/slug-name-12".extractEpisodeNumber())
    }

    @Test
    fun `extractEpisodeNumber extracts correct number from series with title numbers`() {
        assertEquals("1", "/hentai/jk-to-inkou-kyoushi-4-feat-ero-giin-sensei-1".extractEpisodeNumber())
    }

    @Test
    fun `extractEpisodeNumber returns null for series URL without episode number`() {
        assertNull("/hentai/slug-name".extractEpisodeNumber())
    }

    // This was the original bug — groupValues[1] vs groupValues[2]
    @Test
    fun `extractEpisodeNumber returns the number not the prefix`() {
        // The regex has groupValues[1]=prefix and groupValues[2]=number
        // We MUST get groupValues[2], not the full URL prefix
        val result = "/hentai/jk-to-inkou-kyoushi-4-feat-ero-giin-sensei-1".extractEpisodeNumber()
        assertEquals("1", result)
        // This would fail if we used groupValues[1] (which was the bug):
        // result would be "/hentai/jk-to-inkou-kyoushi-4-feat-ero-giin-sensei"
    }

    // ======================== stripEpisodeSuffix() ========================

    @Test
    fun `stripEpisodeSuffix removes trailing dash and number from title`() {
        assertEquals("Series Name", "Series Name - 1".stripEpisodeSuffix())
    }

    @Test
    fun `stripEpisodeSuffix handles no spaces around dash`() {
        assertEquals("Series Name", "Series Name-1".stripEpisodeSuffix())
    }

    @Test
    fun `stripEpisodeSuffix handles extra spaces`() {
        assertEquals("Series Name", "Series Name - 3".stripEpisodeSuffix())
    }

    @Test
    fun `stripEpisodeSuffix preserves title with no episode suffix`() {
        assertEquals("Series Name", "Series Name".stripEpisodeSuffix())
    }

    @Test
    fun `stripEpisodeSuffix preserves numbers within the title`() {
        // "4" is part of the title, "1" is the episode suffix
        assertEquals(
            "JK to Inkou Kyoushi 4 feat. Ero Giin-sensei",
            "JK to Inkou Kyoushi 4 feat. Ero Giin-sensei - 1".stripEpisodeSuffix(),
        )
    }

    @Test
    fun `stripEpisodeSuffix handles multi-digit episode numbers`() {
        assertEquals("Series Name", "Series Name - 12".stripEpisodeSuffix())
    }

    // ========================== normalizeHref() ==========================

    @Test
    fun `normalizeHref extracts path from absolute URL`() {
        assertEquals("/hentai/slug-name-1", "https://hstream.moe/hentai/slug-name-1".normalizeHref())
    }

    @Test
    fun `normalizeHref preserves relative path`() {
        assertEquals("/hentai/slug-name-1", "/hentai/slug-name-1".normalizeHref())
    }

    @Test
    fun `normalizeHref handles http scheme`() {
        assertEquals("/hentai/slug-name-1", "http://hstream.moe/hentai/slug-name-1".normalizeHref())
    }

    @Test
    fun `normalizeHref handles different domains`() {
        assertEquals("/hentai/slug-name-1", "https://other-domain.com/hentai/slug-name-1".normalizeHref())
    }

    @Test
    fun `normalizeHref extracts path from full series URL`() {
        assertEquals("/hentai/slug-name", "https://hstream.moe/hentai/slug-name".normalizeHref())
    }

    @Test
    fun `normalizeHref handles series URL without episode number`() {
        assertEquals(
            "/hentai/jk-to-inkou-kyoushi-4-feat-ero-giin-sensei",
            "https://hstream.moe/hentai/jk-to-inkou-kyoushi-4-feat-ero-giin-sensei".normalizeHref(),
        )
    }
}
