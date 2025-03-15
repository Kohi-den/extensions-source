package eu.kanade.tachiyomi.animeextension.de.aniworld

object AWConstants {
    const val NAME_DOOD = "Doodstream"
    const val NAME_VOE = "VOE"
    const val NAME_VIZ = "Vidoza"
    const val NAME_FLMN = "Filemoon"
    const val NAME_LULU = "Luluvdo"
    const val NAME_VIDM = "Vidmoly"

    const val URL_DOOD = "https://dood"
    const val URL_VOE = "https://voe"
    const val URL_VIZ = "https://vidoza"
    const val URL_FLMN = "https://filemoon"
    const val URL_LULU = "https://luluvdo"
    const val URL_VIDM = "https://vidmoly"

    val HOSTER_NAMES = arrayOf(NAME_VOE, NAME_DOOD, NAME_VIZ, NAME_FLMN, NAME_LULU, NAME_VIDM)
    val HOSTER_URLS = arrayOf(URL_VOE, URL_DOOD, URL_VIZ, URL_FLMN, URL_LULU, URL_VIDM)

    const val KEY_GER_DUB = 1
    const val KEY_ENG_SUB = 2
    const val KEY_GER_SUB = 3

    const val LANG_GER_SUB = "Deutscher Sub"
    const val LANG_GER_DUB = "Deutscher Dub"
    const val LANG_ENG_SUB = "Englischer Sub"

    val LANGS = arrayOf(LANG_GER_SUB, LANG_GER_DUB, LANG_ENG_SUB)

    const val PREFERRED_HOSTER = "preferred_hoster"
    const val PREFERRED_LANG = "preferred_lang"
    const val HOSTER_SELECTION = "hoster_selection"
    
}
