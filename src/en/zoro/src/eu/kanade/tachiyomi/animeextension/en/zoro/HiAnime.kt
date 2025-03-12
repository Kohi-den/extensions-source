package eu.kanade.tachiyomi.animeextension.en.zoro

import android.content.SharedPreferences
import androidx.preference.*
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.megacloudextractor.MegaCloudExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.multisrc.zorotheme.ZoroTheme
import eu.kanade.tachiyomi.network.GET
import okhttp3.Request
import org.jsoup.nodes.Element

class HiAnime : ZoroTheme(
    "en",
    "HiAnime",
    PREF_DOMAIN_DEFAULT,
    hosterNames = listOf(
        "HD-1",
        "HD-2",
        "StreamTape",
    ),
) {
    override val id = 6706411382606718900L

    override val ajaxRoute = "/v2"

    // Dynamic baseUrl logic with custom domain support
    override val baseUrl by lazy {
        val customDomain = preferences.getString(PREF_CUSTOM_DOMAIN_KEY, null)
        if (customDomain.isNullOrBlank()) {
            preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!
        } else {
            customDomain
        }
    }

    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val megaCloudExtractor by lazy { MegaCloudExtractor(client, headers, preferences) }

    override fun latestUpdatesRequest(page: Int): Request = 
        GET("$baseUrl/recently-updated?page=$page", docHeaders)

    override fun popularAnimeFromElement(element: Element): SAnime {
        return super.popularAnimeFromElement(element).apply {
            url = url.substringBefore("?")
        }
    }

    override fun extractVideo(server: VideoData): List<Video> {
        return when (server.name) {
            "StreamTape" -> {
                streamtapeExtractor.videoFromUrl(server.link, "Streamtape - ${server.type}")
                    ?.let(::listOf)
                    ?: emptyList()
            }
            "HD-1", "HD-2" -> megaCloudExtractor.getVideosFromUrl(server.link, server.type, server.name)
            else -> emptyList()
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // Video Quality Preference
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        // File Size Sorting Preference
        ListPreference(screen.context).apply {
            key = PREF_SIZE_SORT_KEY
            title = PREF_SIZE_SORT_TITLE
            entries = PREF_SIZE_SORT_ENTRIES
            entryValues = PREF_SIZE_SORT_VALUES
            setDefaultValue(PREF_SIZE_SORT_DEFAULT)
            summary = PREF_SIZE_SORT_SUMMARY

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        // Custom Domain Preference
        EditTextPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = PREF_DOMAIN_TITLE
            dialogTitle = PREF_DOMAIN_DIALOG_TITLE
            setDefaultValue(PREF_DOMAIN_DEFAULT)
            summary = getDomainPrefSummary()

            setOnPreferenceChangeListener { _, newValue ->
                runCatching {
                    val value = (newValue as String).ifEmpty { PREF_DOMAIN_DEFAULT }
                    preferences.edit().putString(key, value).commit().also {
                        summary = getDomainPrefSummary()
                    }
                }.getOrDefault(false)
            }
        }.also(screen::addPreference)
    }

    // Utility function to display the current domain in preferences
    private fun getDomainPrefSummary(): String {
        val domain = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT) ?: PREF_DOMAIN_DEFAULT
        return "Current domain: $domain"
    }
}
