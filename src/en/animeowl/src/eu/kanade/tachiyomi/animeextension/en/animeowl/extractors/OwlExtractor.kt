package eu.kanade.tachiyomi.animeextension.en.animeowl.extractors

import eu.kanade.tachiyomi.animeextension.en.animeowl.Link
import eu.kanade.tachiyomi.animeextension.en.animeowl.OwlServers
import eu.kanade.tachiyomi.animeextension.en.animeowl.Stream
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.synchrony.Deobfuscator
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient

class OwlExtractor(private val client: OkHttpClient, private val baseUrl: String) {

    private val playlistUtils by lazy { PlaylistUtils(client) }

    private val noRedirectClient by lazy {
        client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    suspend fun extractOwlVideo(link: Link): List<Video> {
        val dataSrc = client.newCall(GET(link.url)).execute()
            .asJsoup()
            .select("button#hot-anime-tab")
            .attr("data-source")
        val epJS = dataSrc.substringAfterLast("/")
            .let {
                client.newCall(GET("$baseUrl/players/$it.v2.js")).execute().body.string()
            }
            .let(Deobfuscator::deobfuscateScript)
            ?: throw Exception("Unable to get clean JS")

        val jwt = findFirstJwt(epJS) ?: throw Exception("Unable to get jwt")

        val videoList = mutableListOf<Video>()
        val servers = client.newCall(GET("$baseUrl$dataSrc")).execute()
            .parseAs<OwlServers>()
        coroutineScope {
            val lufDeferred = async {
                servers.luffy?.let { luffy ->
                    luffy.forEach { stream ->
                        noRedirectClient.newCall(GET("${stream.url}$jwt")).execute()
                            .use { it.headers["Location"] }?.let {
                                videoList.add(
                                    Video(it, "${link.lang} Luffy:${stream.resolution}", it),
                                )
                            }
                    }
                }
            }
            val kaiDeferred = async {
                servers.kaido?.let { kaido ->
                    kaido.forEach { stream ->
                        videoList.addAll(
                            getHLS("${stream.url}$jwt", "Kaido", link.lang),
                        )
                    }
                }
            }
            val zorDeferred = async {
                servers.zoro?.let { zoro ->
                    zoro.forEach { stream ->
                        videoList.addAll(
                            getHLS("${stream.url}$jwt", "Boa", link.lang),
                        )
                    }
                }
            }

            awaitAll(lufDeferred, kaiDeferred, zorDeferred)
        }
        return videoList
    }

    private fun getHLS(url: String, server: String, lang: String): List<Video> {
        return client.newCall(GET(url)).execute().let { it ->
            if (it.isSuccessful) {
                it.parseAs<Stream>().url.let {
                    playlistUtils.extractFromHls(it, videoNameGen = { qty -> "$lang $server:$qty" })
                }
            } else {
                emptyList()
            }
        }
    }

    private fun findFirstJwt(text: String): String? {
        val jwtPattern = Regex("['\"]([A-Za-z0-9-_]+\\.[A-Za-z0-9-_]+\\.[A-Za-z0-9-_]+)['\"]")
        return jwtPattern.find(text)?.groupValues?.get(1)
    }
}
