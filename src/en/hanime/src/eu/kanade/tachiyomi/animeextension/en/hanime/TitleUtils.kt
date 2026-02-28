package eu.kanade.tachiyomi.animeextension.en.hanime

object TitleUtils {
    fun isNumber(num: String) = num.toIntOrNull() != null

    fun getTitle(title: String): String {
        return if (title.contains(" Ep ")) {
            title.split(" Ep ")[0].trim()
        } else {
            if (isNumber(title.trim().split(" ").last())) {
                val split = title.trim().split(" ")
                split.slice(0..split.size - 2).joinToString(" ").trim()
            } else {
                title.trim()
            }
        }
    }
}
