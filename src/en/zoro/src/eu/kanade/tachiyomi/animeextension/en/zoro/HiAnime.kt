package eu.kanade.tachiyomi.animeextension.en.zoro

import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
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
    "https://hianimez.to",
    hosterNames = listOf(
        "HD-1",
        "HD-2",
        "StreamTape",
    ),
) {
    override val id = 6706411382606718900L

    override val ajaxRoute = "/v2"

    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }

    private val megaCloudExtractor by lazy { MegaCloudExtractor(client, headers, preferences) }

    override val baseUrl: String
        get() = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT) ?: PREF_DOMAIN_DEFAULT

    override fun latestUpdatesRequest(page: Int): Request = GET(
        "$baseUrl/recently-updated?page=$page",
        docHeaders,
    )

    override fun popularAnimeFromElement(element: Element): SAnime {
        return super.popularAnimeFromElement(element).apply {
            url = url.substringBefore("?")
        }
    }

    override fun extractVideo(server: VideoData): List<Video> {
        return when (server.name) {
            "StreamTape" -> {
                streamtapeExtractor.videoFromUrl(
                    server.link,
                    "Streamtape - ${server.type}",
                )?.let(::listOf) ?: emptyList()
            }

            "HD-1", "HD-2" -> megaCloudExtractor.getVideosFromUrl(
                server.link,
                server.type,
                server.name,
            )

            else -> emptyList()
        }
    }

    // Added the setupPreferenceScreen method here
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen)
        screen.addPreference(
            ListPreference(screen.context).apply {
                key = PREF_DOMAIN_KEY
                title = "Preferred domain"
                entries = arrayOf("hianimez.to", "hianime.to", "hianimez.is", "hianime.nz", "hianime.pe")
                entryValues = arrayOf("https://hianimez.to", "https://hianime.to", "https://hianimez.is", "https://hianime.nz", "https://hianime.pe")
                setDefaultValue(PREF_DOMAIN_DEFAULT)
                summary = "%s"

                setOnPreferenceChangeListener { _, newValue ->
                    val selected = newValue as String
                    val index = findIndexOfValue(selected)
                    val entry = entryValues[index] as String
                    Toast.makeText(
                        screen.context,
                        "Restart Aniyomi to apply changes",
                        Toast.LENGTH_LONG,
                    ).show()
                    preferences.edit().putString(key, entry).commit()
                }
            },
        )
    }

    companion object {
        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private const val PREF_DOMAIN_DEFAULT = "https://hianimez.to"
    }
}
