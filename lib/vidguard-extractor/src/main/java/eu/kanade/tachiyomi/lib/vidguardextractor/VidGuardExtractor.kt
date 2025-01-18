package eu.kanade.tachiyomi.lib.vidguardextractor

import android.util.Base64
import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import uy.kohesive.injekt.injectLazy

class VidGuardExtractor(private val client: OkHttpClient) {

    private val playlistUtils by lazy { PlaylistUtils(client) }

    private val json: Json by injectLazy()

    fun videosFromUrl(url: String, prefix: String) = videosFromUrl(url) { "${prefix}VidGuard:$it" }

    fun videosFromUrl(url: String, videoNameGen: (String) -> String = { quality -> "VidGuard:$quality" }): List<Video> {
        val res = client.newCall(GET(url)).execute().asJsoup()
        val scriptData = res.selectFirst("script:containsData(eval)")?.data() ?: return emptyList()

        val jsonStr2 = json.decodeFromString<SvgObject>(runJS2(scriptData))
        val playlistUrl = sigDecode(jsonStr2.stream)

        return playlistUtils.extractFromHls(playlistUrl, videoNameGen = videoNameGen)
    }

    private fun sigDecode(url: String): String {
        val sig = url.split("sig=")[1].split("&")[0]
        val t = sig.chunked(2)
            .joinToString("") { (Integer.parseInt(it, 16) xor 2).toChar().toString() }
            .let {
                val padding = when (it.length % 4) {
                    2 -> "=="
                    3 -> "="
                    else -> ""
                }
                String(Base64.decode((it + padding).toByteArray(Charsets.UTF_8), Base64.DEFAULT))
            }
            .dropLast(5)
            .reversed()
            .toCharArray()
            .apply {
                for (i in indices step 2) {
                    if (i + 1 < size) {
                        this[i] = this[i + 1].also { this[i + 1] = this[i] }
                    }
                }
            }
            .concatToString()
            .dropLast(5)
        return url.replace(sig, t)
    }

    private fun runJS2(hideMyHtmlContent: String): String {
        var result = ""
        val r = Runnable {
            val rhino = Context.enter()
            rhino.initSafeStandardObjects()
            rhino.optimizationLevel = -1
            val scope: Scriptable = rhino.initSafeStandardObjects()
            scope.put("window", scope, scope)
            try {
                rhino.evaluateString(
                    scope,
                    hideMyHtmlContent,
                    "JavaScript",
                    1,
                    null,
                )
                val svgObject = scope.get("svg", scope)
                result = if (svgObject is NativeObject) {
                    NativeJSON.stringify(Context.getCurrentContext(), scope, svgObject, null, null)
                        .toString()
                } else {
                    Context.toString(svgObject)
                }
            } catch (e: Exception) {
                Log.e("Error", "JavaScript execution error: ${e.message}")
            } finally {
                Context.exit()
            }
        }
        val t = Thread(ThreadGroup("A"), r, "thread_rhino", 8 * 1024 * 1024) // Increase stack size to 8MB
        t.start()
        t.join()
        t.interrupt()
        return result
    }
}

@Serializable
data class SvgObject(
    val stream: String,
    val hash: String,
)
