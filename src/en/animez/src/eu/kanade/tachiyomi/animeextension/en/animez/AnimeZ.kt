// This code takes inspiration from Phisher98's AnimeZ

package eu.kanade.tachiyomi.animeextension.en.animez

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class AnimeZ : AnimeHttpSource() {

    override val name = "Animez"
    override val baseUrl = "https://animeyy.com"
    override val lang = "en"
    override val supportsLatest = true

    // ============================= Popular =============================
    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/?act=searchadvance&f[min_num_chapter]=1&f[status]=In%20process&f[sortby]=top-manga&&pageNum=$page")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animeList = doc.select("article").map { animeFromElement(it) }
        val hasNextPage = doc.select("a.page-link:contains(Next)").isNotEmpty()
        return AnimesPage(animeList, hasNextPage)
    }

    // ============================= Latest =============================
    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/?act=searchadvance&f[min_num_chapter]=1&f[status]=In%20process&f[sortby]=lastest-manga&&pageNum=$page")

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animeList = doc.select("article").map { animeFromElement(it) }
        val hasNextPage = doc.select("a.page-link:contains(Next)").isNotEmpty()
        return AnimesPage(animeList, hasNextPage)
    }

    // ============================= Search =============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/?act=searchadvance&pageNum=$page&f[keyword]=$query"
        return GET(url)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animeList = doc.select("article").map { animeFromElement(it) }
        val hasNextPage = doc.select("a.page-link:contains(Next)").isNotEmpty()
        return AnimesPage(animeList, hasNextPage)
    }

    // ============================= Details =============================
    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.select("article.TPost.Single h2").text().trim()
            thumbnail_url = doc.select("meta[property=og:image]").attr("content")
            description = doc.select("meta[property=og:description]").attr("content")
            genre = doc.select("div.mvici-left > ul > li:nth-child(4) a").joinToString { it.text() }
        }
    }

    // ============================= Episodes =============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val episodes = mutableListOf<SEpisode>()

        val malid = doc.selectFirst("h2.SubTitle")?.attr("data-manga") ?: return episodes

        val regex = Regex("""load_list_chapter\((\d+)\)""")
        val lastPageNum = doc.select("#nav_list_chapter_id_detail a.page-link")
            .mapNotNull { regex.find(it.attr("onclick"))?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 1

        val ajaxHeaders = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.5993.90 Safari/537.36")
            .add("Referer", response.request.url.toString())
            .build()

        // Pagination
        for (page in 1..lastPageNum) {
            val ajaxUrl = "$baseUrl/?act=ajax&code=load_list_chapter&manga_id=$malid&page_num=$page&chap_id=0&keyword="
            val jsonResponse = try {
                client.newCall(GET(ajaxUrl, headers = ajaxHeaders)).execute().body?.string() ?: continue
            } catch (e: Exception) {
                continue
            }

            val listChapHtml = try {
                JSONObject(jsonResponse).getString("list_chap")
            } catch (e: Exception) {
                continue
            }

            val pageDoc = Jsoup.parse(listChapHtml)
            pageDoc.select("li.wp-manga-chapter a").forEach { element ->
                val href = element.attr("href").trim()
                val name = element.text().trim()
                val epNum = Regex("""\d+""").find(name)?.value?.toFloatOrNull() ?: 0f

                val episode = SEpisode.create().apply {
                    setUrlWithoutDomain(href)
                    this.name = name
                    episode_number = epNum
                }
                episodes += episode
            }
        }

        return episodes.sortedByDescending { it.episode_number }
    }

    // ============================= Video =============================
    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val token = doc.select("iframe").attr("src").substringAfter("/embed/")

        val headers = Headers.Builder()
            .add("Referer", baseUrl)
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.5993.90 Safari/537.36")
            .build()

        return doc.select("#list_sv a").map { el ->
            val host = el.attr("data-link")
            val m3u8 = "$host/anime/$token"
            Video(
                url = m3u8,
                quality = "Animez",
                videoUrl = m3u8,
                headers = headers,
            )
        }
    }

    // ============================= Helpers =============================
    private fun animeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            title = element.selectFirst("h2")?.text().orEmpty()
            setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
            thumbnail_url = element.select("img").last()?.getImageAttr()
        }
    }

    private fun Element.getImageAttr(): String {
        return when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            else -> attr("abs:src")
        }
    }
}
