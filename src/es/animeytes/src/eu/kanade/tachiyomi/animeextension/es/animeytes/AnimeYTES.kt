package eu.kanade.tachiyomi.animeextension.es.animeytes

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.burstcloudextractor.BurstCloudExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.sendvidextractor.SendvidExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimeYTES : AnimeStream(
    "es",
    "AnimeYT.es",
    "https://animeyt.es",
) {
    override val preferences: SharedPreferences by lazy { Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000) }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Amazon"
        private val SERVER_LIST = arrayOf(
            "YourUpload",
            "SendVid",
            "BurstCloud",
            "StreamTape",
            "Filemoon",
            "Okru",
        )
    }

    override val animeListUrl = "$baseUrl/tv"

    // ============================ Video Links =============================
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val sendvidExtractor by lazy { SendvidExtractor(client, headers) }
    private val youruploadExtractor by lazy { YourUploadExtractor(client) }
    private val burstcloudExtractor by lazy { BurstCloudExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    override fun getVideoList(url: String, name: String): List<Video> {
        return when (name) {
            "OK" -> okruExtractor.videosFromUrl(url)
            "Stream" -> streamtapeExtractor.videosFromUrl(url)
            "Send" -> sendvidExtractor.videosFromUrl(url)
            "Your" -> youruploadExtractor.videoFromUrl(url, headers)
            "Alpha" -> burstcloudExtractor.videoFromUrl(url, headers)
            "Moon" -> filemoonExtractor.videosFromUrl(url)
            else -> universalExtractor.videosFromUrl(url, headers)
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }
}
