/** The following file is slightly modified and taken from: https://github.com/LagradOst/CloudStream-3/blob/4d6050219083d675ba9c7088b59a9492fcaa32c7/app/src/main/java/com/lagradost/cloudstream3/animeproviders/AnimePaheProvider.kt
 * It is published under the following license:
 *
MIT License

Copyright (c) 2021 Osten

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
 *
 */

package eu.kanade.tachiyomi.animeextension.en.animepahe.kwik

import android.app.Application
import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit

data class KwikContent(val cookies: String, val html: String, val finalUrl: String)

class KwikExtractor(
    private val client: OkHttpClient,
) {
    private val kwikParamsRegex = Regex("""\("(\w+)",\d+,"(\w+)",(\d+),(\d+),\d+\)""")
    private val kwikDUrl = Regex("action=\"([^\"]+)\"")
    private val kwikDToken = Regex("value=\"([^\"]+)\"")

    private val kwikClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(client.connectTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(client.readTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(client.writeTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .build()
    }

    fun getHlsStreamUrl(kwikUrl: String, referer: String): String {
        val eContent = client.newCall(GET(kwikUrl, Headers.headersOf("referer", referer)))
            .execute().asJsoup()
        val script = eContent.selectFirst("script:containsData(eval\\(function)")!!.data().substringAfterLast("eval(function(")
        val unpacked = JsUnpacker.unpackAndCombine("eval(function($script")!!
        return unpacked.substringAfter("const source=\\'").substringBefore("\\';")
    }

    fun getStreamUrlFromKwik(context: Application, paheUrl: String): String {
        val noRedirectClient = kwikClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        // Get Kwik URL
        val kwikUrl = noRedirectClient.newCall(GET("$paheUrl/i")).execute().use { response ->
            val location = response.header("location")
                ?: throw KwikException.ExtractionException("Pahe redirect failed: No location header found.")
            "https://" + location.substringAfterLast("https://")
        }

        var (fContentCookies, fContentString, fContentUrl) = fetchKwikHtml(context, kwikUrl)
        var cloudFlareBypassResult: CloudFlareBypassResult? = null

        // Extract JS Parameters
        val match = kwikParamsRegex.find(fContentString)
            ?: throw KwikException.ExtractionException("Could not find decryption parameters in Kwik HTML.")

        val (fullString, key, v1, v2) = match.destructured
        val decrypted = decrypt(fullString, key, v1.toIntOrNull() ?: 0, v2.toIntOrNull() ?: 0)

        val uri = kwikDUrl.find(decrypted)?.groupValues?.get(1)
            ?: throw KwikException.ExtractionException("Failed to decrypt stream URI.")
        val tok = kwikDToken.find(decrypted)?.groupValues?.get(1)
            ?: throw KwikException.ExtractionException("Failed to decrypt stream Token.")

        // Extraction Loop
        var kwikLocation: String? = null
        var code = 419
        var tries = 0
        val tryLimit = 5

        while (code != 302 && tries < tryLimit) {
            val headersBuilder = Headers.Builder()
                .add("referer", fContentUrl)
                .add("cookie", fContentCookies)

            cloudFlareBypassResult?.let { headersBuilder.add("User-Agent", it.userAgent) }

            noRedirectClient.newCall(
                POST(uri, headersBuilder.build(), FormBody.Builder().add("_token", tok).build()),
            ).execute().use { response ->
                code = response.code
                kwikLocation = response.header("location")
            }

            // Cloudflare/Session Timeout Handling
            if ((code == 403 || code == 419) && cloudFlareBypassResult == null) {
                cloudFlareBypassResult = CloudflareBypass(context).getCookies(kwikUrl)
                    ?: throw KwikException.CloudflareBlockedException("Cloudflare bypass failed to return result.")

                fContentCookies = "$fContentCookies; ${cloudFlareBypassResult.cookies}"
                tries = 0 // Reset tries after successful bypass
            }
            tries++
        }

        return kwikLocation ?: throw KwikException.ExtractionException("Failed to extract stream URI after $tries attempts.")
    }

    private fun fetchKwikHtml(context: Application, kwikUrl: String): KwikContent {
        val initialResponse = kwikClient.newCall(
            GET(kwikUrl, Headers.headersOf("referer", "https://kwik.cx/")),
        ).execute()

        val (html, cookies, finalUrl) = initialResponse.use { resp ->
            Triple(resp.body.string(), resp.extractCookies(), resp.request.url.toString())
        }

        if (html.contains("eval(function(")) {
            return KwikContent(cookies, html, finalUrl)
        }

        // Try Cloudflare Bypass if context has value
        context.let { ctx ->
            val cfResult = CloudflareBypass(ctx).getCookies(kwikUrl)
                ?: throw KwikException.CloudflareBlockedException("Bypass returned null result.")

            val bypassHeaders = Headers.Builder()
                .add("referer", "https://kwik.cx/")
                .add("cookie", cfResult.cookies)
                .add("User-Agent", cfResult.userAgent)
                .build()

            kwikClient.newCall(GET(kwikUrl, bypassHeaders)).execute().use { resp ->
                val bypassHtml = resp.body.string()
                val bypassCookies = resp.extractCookies()

                if (bypassHtml.contains("eval(function(")) {
                    return KwikContent("$bypassCookies; ${cfResult.cookies}", bypassHtml, resp.request.url.toString())
                }
            }
        }

        throw KwikException.CloudflareBlockedException("Cloudflare challenge not solved.")
    }

    private fun Response.extractCookies(): String {
        return headers("set-cookie").joinToString("; ") { it.substringBefore(";") }
    }

    private fun decrypt(fullString: String, key: String, v1: Int, v2: Int): String {
        val keyIndexMap = key.withIndex().associate { it.value to it.index }
        val sb = StringBuilder()
        var i = 0
        val toFind = key[v2]

        while (i < fullString.length) {
            val nextIndex = fullString.indexOf(toFind, i)
            val decodedCharStr = buildString {
                for (j in i until nextIndex) {
                    append(keyIndexMap[fullString[j]] ?: -1)
                }
            }

            i = nextIndex + 1
            val decodedChar = (decodedCharStr.toInt(v2) - v1).toChar()
            sb.append(decodedChar)
        }

        return sb.toString()
    }
}
