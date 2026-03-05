package eu.kanade.tachiyomi.animeextension.pt.animesotaku.dto

import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Serializable
data class SearchResponseDto(val data: SearchDataDto, val success: Boolean)

@Serializable
data class SearchDataDto(
    val html: String,
    @SerialName("max_pages")
    val maxPages: Int,
    @SerialName("current_page")
    val currentPage: Int,
)

@Serializable
data class EpisodeResponseDto(val data: EpisodeDataDto, val success: Boolean)

@Serializable
data class EpisodeDataDto(
    val episodes: List<EpisodeItemDto>,
    @SerialName("max_episodes_page")
    val maxEpisodesPage: Int,
)

@Serializable
data class EpisodeItemDto(
    val number: String,
    val title: String,
    val released: String,
    val url: String,
    @SerialName("meta_number")
    val metaNumber: String,
) {
    fun toSEpisode() = SEpisode.create().apply {
        name = number
        episode_number = metaNumber.toFloatOrNull() ?: 1F
        url = "/watch/${this@EpisodeItemDto.url.substringAfter("/watch/")}"
        date_upload = parseReleasedDate(released)
    }

    private fun parseReleasedDate(released: String): Long {
        return try {
            // Verifica se é apenas números (formato de dias desde 01/01/1900)
            if (released.all { it.isDigit() }) {
                val days = released.toLong()
                val calendar = Calendar.getInstance()
                calendar.set(1900, Calendar.JANUARY, 1, 0, 0, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                calendar.add(Calendar.DAY_OF_YEAR, days.toInt())
                calendar.timeInMillis
            } else {
                // Formato DD/MM/YYYY
                val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                val date = formatter.parse(released)
                date?.time ?: 0L
            }
        } catch (e: Exception) {
            0L
        }
    }
}

@Serializable
data class TaxonomyDto(val taxonomy: String = "", val terms: List<String> = emptyList())

@Serializable
data class SingleDto(
    val paged: String,
    @SerialName("meta_key")
    val key: String?,
    val order: String,
    val orderBy: String,
    val season: String?,
    val year: String?,
)
