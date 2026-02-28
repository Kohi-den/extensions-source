package eu.kanade.tachiyomi.animeextension.en.hanime

import android.content.SharedPreferences
import eu.kanade.tachiyomi.animesource.model.Video

object VideoSorter {
    fun sortVideos(videos: List<Video>, preferences: SharedPreferences): List<Video> {
        val quality = preferences.getString(Hanime.PREF_QUALITY_KEY, Hanime.PREF_QUALITY_DEFAULT)!!
        return videos.sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }
}
