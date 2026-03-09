package eu.kanade.tachiyomi.animeextension.de.aniworldsplit

import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.multisrc.aniworldtheme.AniWorldTheme
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import uy.kohesive.injekt.api.get

class AniWorldSplit : AniWorldTheme("AniWorld (Split Seasons)") {

    override val id: Long = 8286900189409315837

    // ===== SEARCH =====
    override fun searchAnimeParse(response: Response): AnimesPage {
        val body = response.body.string()
        val results = json.decodeFromString<JsonArray>(body)
        val animes = results
            .mapNotNull { animeFromSearch(it.jsonObject) }
            .flatten()
        return AnimesPage(animes, false)
    }

    fun animeFromSearch(obj: JsonObject): List<SAnime>? {
        val name = obj["name"]?.jsonPrimitive?.content ?: return null
        val link = obj["link"]?.jsonPrimitive?.content ?: return null
        val thumbnailUrl = obj["cover"]
            ?.jsonPrimitive
            ?.content
            ?.replace("150x225", "220x330")
            ?.let { cover -> "$baseUrl$cover" }
        val description = obj["description"]?.jsonPrimitive?.content

        val page = client.newCall(GET("$baseUrl/anime/stream/$link")).execute().asJsoup()
        val animes = mutableListOf<SAnime>()
        val seasonElements = page.select("#stream > ul:nth-child(1) > li > a")

        seasonElements.forEach { element ->
            val seasonUrl = element.attr("abs:href")

            if (seasonUrl.contains("/filme")) {
                val moviesHtml = client.newCall(GET(seasonUrl)).execute().asJsoup()
                val movieElements = moviesHtml.select("table.seasonEpisodesList tbody tr")
                movieElements.forEach { movieElement ->
                    val movieAnime =
                        SAnime.create().apply {
                            val movieTitle = movieElement.select("td.seasonEpisodeTitle a span").text()
                            title = "$name - $movieTitle"
                            url = movieElement.selectFirst("td.seasonEpisodeTitle a")!!.attr("href").substringAfter(baseUrl)
                            thumbnail_url = thumbnailUrl
                            this.description = description
                            status = SAnime.COMPLETED
                        }
                    animes.add(movieAnime)
                }
            } else {
                val seasonAnime =
                    SAnime.create().apply {
                        val seasonNum = element.attr("href").substringAfter("staffel-").substringBeforeLast("/")
                        title = "$name - Staffel $seasonNum"
                        url = seasonUrl.substringAfter(baseUrl)
                        thumbnail_url = thumbnailUrl
                        this.description = description
                        status = SAnime.UNKNOWN
                    }
                animes.add(seasonAnime)
            }
        }
        return animes
    }

    // ===== EPISODE =====
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        val url = response.request.url.toString()

        if (url.contains("/filme")) {
            val episode = SEpisode.create().apply {
                name = document.select(".hosterSiteTitle h2 small").text()
                episode_number = 1f
                this.url = url
            }
            episodeList.add(episode)
        } else {
            val episodeElements = document.select("table.seasonEpisodesList tbody tr")
            episodeElements.forEach { element ->
                val episode = SEpisode.create().apply {
                    val num = element.attr("data-episode-season-id")
                    name = "$num. " + element.select("td.seasonEpisodeTitle a span").text()
                    episode_number = element.select("td meta").attr("content").toFloat()
                    this.url = element.selectFirst("td.seasonEpisodeTitle a")!!.attr("href")
                }
                episodeList.add(episode)
            }
        }
        return episodeList.reversed()
    }
}
