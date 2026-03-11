package eu.kanade.tachiyomi.animeextension.es.azanimex

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

class Azanimex : ParsedAnimeHttpSource() {

    override val name = "az-animex"

    override val baseUrl = "https://www.az-animex.com"

    override val lang = "es"

    override val supportsLatest = false

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page > 1) "$baseUrl/?query-22-page=$page" else baseUrl
        return GET(url)
    }
    override fun popularAnimeSelector(): String = "li.wp-block-post"

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.selectFirst("h2.wp-block-post-title a")?.attr("href") ?: "")
            title =
                element.selectFirst("h2.wp-block-post-title a")?.text()?.substringBefore("[") ?: ""
            thumbnail_url =
                element.selectFirst("figure.wp-block-post-featured-image img")?.attr("data-src")

            val genres = mutableListOf<String>()
            element.select("div[class*=taxonomy-genero] a").forEach { genres.add(it.text()) }
            element.select("div[class*=taxonomy-tipo] a")
                .forEach { genres.add("Tipo: ${it.text()}") }
            genre = genres.joinToString(", ")
        }
    }

    override fun popularAnimeNextPageSelector(): String = "a.page-numbers:not(.prev):not(.next)"

    // ============================== Episodes ==============================
    override fun episodeListSelector() = throw Exception("Not used")

    override fun episodeFromElement(element: Element): SEpisode {
        throw Exception("Not used")
    }

    private fun getPathFromUrl(url: String): String {
        val cleanUrl = url.replace("https://", "").replace("http://", "")
        return cleanUrl.substringAfter("/").replace("//", "/").replaceFirst("es/", "")
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()

        val mainUrl = document.select("a.su-button[href*='filemoon']").firstOrNull()
            ?.attr("href") ?: return emptyList()

        val folderDocument = client.newCall(GET(mainUrl)).execute().asJsoup()

        return folderDocument.select(".folder-share li").map {
            val title = it.select(".name h4").text().substringAfter("] ").substringBeforeLast(" [")
            SEpisode.create().apply {
                name = title
                episode_number = parseEpisodeNumber(title)
                url = it.select(".action a").attr("abs:href")
            }
        }.reversed()
    }

    private fun parseEpisodeNumber(filename: String): Float {
        val regex = Regex("""-(\d+)\s*\[""")
        return regex.find(filename)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
    }

    // =========================== Anime Details ===========================
    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            val infoMap = document.select("span.post-info").associate { span ->
                val label = span.text().trim()
                val value = span.nextSibling()?.toString()?.trim() ?: ""
                label to value
            }

            title = Parser.unescapeEntities(document.select("span.post-info:contains(Título) + br").first()?.previousSibling()?.toString()?.trim().orEmpty(), false)
            description = document.select("div.su-spoiler-content").first()?.text()?.trim() ?: ""
            genre = infoMap["Géneros"]?.substringBefore(".")
            author = infoMap["Estudio"]
            status = when (infoMap["Episodios"]?.substringAfter("de ")?.trim()) {
                infoMap["Episodios"]?.substringBefore(" de")?.trim() -> SAnime.COMPLETED
                else -> SAnime.ONGOING
            }

            // Información adicional para la descripción
            val additionalInfo = buildString {
                appendLine("\n\nInformación:")
                if (!infoMap["Año"].isNullOrBlank()) appendLine("• Año: ${infoMap["Año"]}")
                if (!infoMap["Episodios"].isNullOrBlank()) appendLine("• Episodios: ${infoMap["Episodios"]}")
                if (!infoMap["Duración"].isNullOrBlank()) appendLine("• Duración: ${infoMap["Duración"]}")
                if (!infoMap["Fansub"].isNullOrBlank()) appendLine("• Fansub: ${infoMap["Fansub"]}")
                if (!infoMap["Versión"].isNullOrBlank()) appendLine("• Versión: ${infoMap["Versión"]}")
                if (!infoMap["Resolución"].isNullOrBlank()) appendLine("• Resolución: ${infoMap["Resolución"]}")
                if (!infoMap["Formato"].isNullOrBlank()) appendLine("• Formato: ${infoMap["Formato"]}")
            }

            description += additionalInfo
        }
    }

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()

        val urlElement = element.selectFirst("h2.wp-block-post-title a")
        anime.setUrlWithoutDomain(urlElement?.attr("href") ?: "")

        anime.title = urlElement?.text() ?: "Título desconocido"

        anime.thumbnail_url = element.selectFirst("figure.gs-hover-scale-img img")?.attr("src") ?: ""
        Log.d("Azanimex", "URL de la imagen: ${anime.thumbnail_url}")

        val genres = element.select("div.taxonomy-tipo a, div.taxonomy-version a").joinToString { it.text() }
        anime.genre = genres.ifEmpty { "Desconocido" }

        return anime
    }

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = if (page > 1) {
            "$baseUrl/page/$page/?s=$query"
        } else {
            "$baseUrl/?s=$query"
        }

        return GET(url, headers)
    }

    // ============================ Video URLS =============================

    override fun videoListSelector() = throw Exception("Not used")

    override fun videoFromElement(element: Element) = throw Exception("Not used")
    override fun videoUrlParse(document: Document): String {
        throw Exception("Not used")
    }

    private val filemoonExtractor by lazy { FilemoonExtractor(client) }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val videoUrl = episode.url
        return filemoonExtractor.videosFromUrl(videoUrl, prefix = "Filemoon:")
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        throw UnsupportedOperationException()
    }

    override fun latestUpdatesSelector(): String {
        throw UnsupportedOperationException()
    }

    override fun latestUpdatesFromElement(element: Element): SAnime {
        throw UnsupportedOperationException()
    }

    override fun latestUpdatesNextPageSelector(): String? {
        throw UnsupportedOperationException()
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}
