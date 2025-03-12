package eu.kanade.tachiyomi.animeextension.en.zoro

// Domain Preferences
const val PREF_CUSTOM_DOMAIN_KEY = "pref_custom_domain_key"
const val PREF_DOMAIN_KEY = "pref_domain_key"
const val PREF_DOMAIN_DEFAULT = "https://hianime.to"

// Quality Preferences
const val PREF_QUALITY_KEY = "pref_quality"
const val PREF_QUALITY_TITLE = "Video Quality"
val PREF_QUALITY_ENTRIES = arrayOf("360p", "480p", "720p", "1080p")
const val PREF_QUALITY_DEFAULT = "1080p"

// Size Sorting Preferences
const val PREF_SIZE_SORT_KEY = "pref_size_sort"
const val PREF_SIZE_SORT_TITLE = "Sort by File Size"
val PREF_SIZE_SORT_ENTRIES = arrayOf("Smallest", "Largest")
val PREF_SIZE_SORT_VALUES = arrayOf("smallest", "largest")
const val PREF_SIZE_SORT_DEFAULT = "smallest"
const val PREF_SIZE_SORT_SUMMARY = "Sort videos by file size"

// Domain Setting UI Text
const val PREF_DOMAIN_TITLE = "Custom Domain"
const val PREF_DOMAIN_DIALOG_TITLE = "Enter custom domain"