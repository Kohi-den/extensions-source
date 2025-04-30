package eu.kanade.tachiyomi.lib.savefileextractor

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class SavefileExtractor(
    private val client: OkHttpClient,
    private val preferences: SharedPreferences,
) {

    private val playlistUtils by lazy { PlaylistUtils(client) }

    fun videosFromUrl(url: String, prefix: String = "Savefile - ", headers: Headers? = null): List<Video> {
        val httpUrl = url.toHttpUrl()
        val videoHeaders = (headers?.newBuilder() ?: Headers.Builder())
            .set("Referer", url)
            .set("Origin", "https://${httpUrl.host}")
            .build()

        val doc = client.newCall(GET(url, videoHeaders)).execute().asJsoup()
        val js = doc.selectFirst("script:containsData(m3u8)")!!.data()
        val masterUrl = js.takeIf(String::isNotBlank)
            ?.substringAfter("{file:\"", "")
            ?.substringBefore("\"}", "")
            ?.takeIf(String::isNotBlank)
            ?: return emptyList()

        val videoList = playlistUtils.extractFromHls(
            masterUrl,
            referer = "https://${httpUrl.host}/",
            videoNameGen = { "$prefix$it" },
        )

        val subPref = preferences.getString(PREF_SUBTITLE_KEY, PREF_SUBTITLE_DEFAULT).orEmpty()
        return videoList.map {
            Video(
                url = it.url,
                quality = it.quality,
                videoUrl = it.videoUrl,
                audioTracks = it.audioTracks,
                subtitleTracks = it.subtitleTracks.filter { tracks -> tracks.lang.contains(subPref, true) }
            )
        }
    }

    companion object {
        fun addSubtitlePref(screen: PreferenceScreen) {
            EditTextPreference(screen.context).apply {
                key = PREF_SUBTITLE_KEY
                title = "Savefile subtitle preference"
                summary = "Leave blank to use all subs"
                setDefaultValue(PREF_SUBTITLE_DEFAULT)
            }.also(screen::addPreference)
        }

        private const val PREF_SUBTITLE_KEY = "pref_savefile_sub_lang_key"
        private const val PREF_SUBTITLE_DEFAULT = "eng"
    }
}
