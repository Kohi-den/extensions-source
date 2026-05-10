package eu.kanade.tachiyomi.animeextension.en.av1encodes

import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.model.Video

internal const val PREF_QUALITY_KEY = "preferred_quality"
internal const val PREF_QUALITY_DEFAULT = "1920 x 1080"
internal val QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
internal val QUALITY_VALUES = arrayOf("1920 x 1080", "1280 x 720", "854 x 480", "640 x 360")

internal const val PREF_LINK_TYPE_KEY = "preferred_link_type"
internal const val PREF_LINK_TYPE_DEFAULT = "Stream"
internal val LINK_TYPE_ENTRIES = arrayOf("Watch", "Stream", "Download", "Torrent")

internal const val PREF_SHOW_TORRENT_KEY = "show_torrent"
internal const val PREF_SHOW_TORRENT_DEFAULT = true

internal fun buildPreferenceScreen(screen: PreferenceScreen, preferences: SharedPreferences) {
    // ── Preferred Resolution ──────────────────────────────────────────────────
    ListPreference(screen.context).apply {
        key = PREF_QUALITY_KEY
        title = "Preferred Resolution"
        summary = "%s\n\nIf a season shows no episodes, try a lower resolution."
        entries = QUALITY_ENTRIES
        entryValues = QUALITY_VALUES
        setDefaultValue(PREF_QUALITY_DEFAULT)
        setOnPreferenceChangeListener { _, newValue ->
            preferences.edit().putString(PREF_QUALITY_KEY, newValue as String).apply()
            true
        }
    }.also(screen::addPreference)

    // ── Preferred Link Type ───────────────────────────────────────────────────
    ListPreference(screen.context).apply {
        key = PREF_LINK_TYPE_KEY
        title = "Preferred Link Type"
        summary = "%s — this link type will appear first in the video list."
        entries = LINK_TYPE_ENTRIES
        entryValues = LINK_TYPE_ENTRIES
        setDefaultValue(PREF_LINK_TYPE_DEFAULT)
        setOnPreferenceChangeListener { _, newValue ->
            preferences.edit().putString(PREF_LINK_TYPE_KEY, newValue as String).apply()
            true
        }
    }.also(screen::addPreference)

    // ── Show Torrent Link ─────────────────────────────────────────────────────
    SwitchPreferenceCompat(screen.context).apply {
        key = PREF_SHOW_TORRENT_KEY
        title = "Show Torrent Link"
        summary = "Include the torrent link as a video option."
        setDefaultValue(PREF_SHOW_TORRENT_DEFAULT)
        setOnPreferenceChangeListener { _, newValue ->
            preferences.edit().putBoolean(PREF_SHOW_TORRENT_KEY, newValue as Boolean).apply()
            true
        }
    }.also(screen::addPreference)
}

internal fun List<Video>.sortByPreferredQuality(preferences: SharedPreferences): List<Video> {
    val q = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
    val linkType = preferences.getString(PREF_LINK_TYPE_KEY, PREF_LINK_TYPE_DEFAULT)!!
    return sortedWith(
        compareByDescending<Video> { it.quality.contains(linkType, ignoreCase = true) }
            .thenByDescending { it.quality.contains(q, ignoreCase = true) }
            .thenByDescending { it.quality.replace("p", "").toIntOrNull() ?: 0 },
    )
}
