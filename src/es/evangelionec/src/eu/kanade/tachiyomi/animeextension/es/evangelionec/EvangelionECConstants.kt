package eu.kanade.tachiyomi.animeextension.es.evangelionec

object EvangelionECConstants {
    const val ITEMS_PER_PAGE = 20

    const val PREF_QUALITY_KEY = "preferred_quality"
    const val PREF_QUALITY_DEFAULT = "1080"
    val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

    const val PREF_SERVER_KEY = "preferred_server"
    const val PREF_SERVER_DEFAULT = "StreamTape"

    const val PREF_SPLIT_SEASONS_KEY = "split_seasons"
    const val PREF_SPLIT_SEASONS_DEFAULT = true

    val SERVER_LIST =
        arrayOf(
            "StreamTape",
            "Filemoon",
            "StreamWish",
            "Voe",
            "Okru",
            "Doodstream",
            "Mp4Upload",
            "Uqload",
            "YourUpload",
            "VidHide",
            "MegaUp",
            "MEGA",
        )

    val RESOLUTION_REGEX = Regex("""(\d+)p""")

    val LABEL_FILTER_REGEX =
        Regex(
            """^(\d{4}|\d+\.\d+|Ep .*|FHD|HD|HQ|4K|BD|Sub|Dub|Anime.*|[A-Z]|""" +
                """Lunes|Martes|Miércoles|Jueves|Viernes|Sábado|Domingo|""" +
                """(Invierno|Primavera|Verano|Otoño)-\d{4})$""",
        )

    val EXCLUDED_LABELS = setOf("En Emisión", "Finalizado", "Pausado", "Próximamente", "0-9")

    data class ServerPattern(
        val keywords: List<String>,
        val name: String,
    )

    val SERVER_PATTERNS =
        listOf(
            ServerPattern(listOf("streamtape"), "StreamTape"),
            ServerPattern(listOf("filemoon"), "Filemoon"),
            ServerPattern(listOf("streamwish", "wish"), "StreamWish"),
            ServerPattern(listOf("voe"), "Voe"),
            ServerPattern(listOf("okru", "ok.ru"), "Okru"),
            ServerPattern(listOf("dood"), "Doodstream"),
            ServerPattern(listOf("mp4upload"), "Mp4Upload"),
            ServerPattern(listOf("uqload"), "Uqload"),
            ServerPattern(listOf("yourupload"), "YourUpload"),
            ServerPattern(listOf("vidhide"), "VidHide"),
            ServerPattern(listOf("megaup"), "MegaUp"),
            ServerPattern(listOf("mega.nz", "mega.co.nz"), "MEGA"),
        )
}
