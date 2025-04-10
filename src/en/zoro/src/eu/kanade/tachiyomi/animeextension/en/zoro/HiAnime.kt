package eu.kanade.tachiyomi.animeextension.en.zoro

import android.app.Application
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.megacloudextractor.MegaCloudExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.multisrc.zorotheme.ZoroTheme
import eu.kanade.tachiyomi.network.GET
import okhttp3.Request
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class HiAnime : ConfigurableAnimeSource, ZoroTheme(
    lang = "en",
    name = "HiAnime",
    baseUrl = preferences.getString(PREF_BASE_URL_KEY, DEFAULT_BASE_URL)!!,
    hosterNames = listOf("HD-1", "HD-2", "StreamTape"),
) {

    override val id = 6706411382606718900L

    override val ajaxRoute = "/v2"

    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val megaCloudExtractor by lazy { MegaCloudExtractor(client, headers, preferences) }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/recently-updated?page=$page", docHeaders)
    }

    override fun popularAnimeFromElement(element: Element): SAnime {
        return super.popularAnimeFromElement(element).apply {
            url = url.substringBefore("?")
        }
    }

    override fun extractVideo(server: VideoData): List<Video> {
        return when (server.name) {
            "StreamTape" -> {
                streamtapeExtractor.videoFromUrl(server.link, "Streamtape - ${server.type}")
                    ?.let(::listOf) ?: emptyList()
            }
            "HD-1", "HD-2" -> {
                megaCloudExtractor.getVideosFromUrl(server.link, server.type, server.name)
            }
            else -> emptyList()
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val baseUrlPref = EditTextPreference(screen.context).apply {
            key = PREF_BASE_URL_KEY
            title = "Custom domain"
            summary = "Set a custom domain to override the default ($DEFAULT_BASE_URL)"
            dialogTitle = "Custom domain"
            setDefaultValue(DEFAULT_BASE_URL)
            setOnPreferenceChangeListener { _, newValue ->
                Toast.makeText(
                    screen.context,
                    "Restart Aniyomi to apply changes.",
                    Toast.LENGTH_LONG
                ).show()
                true // Save the preference
            }
        }

        screen.addPreference(baseUrlPref)
    }

    companion object {
        private const val PREF_BASE_URL_KEY = "pref_base_url"
        private const val DEFAULT_BASE_URL = "https://hianime.to"

        private val preferences: SharedPreferences by lazy {
            Injekt.get<Application>().getSharedPreferences("hianime", 0)
        }
    }
}
