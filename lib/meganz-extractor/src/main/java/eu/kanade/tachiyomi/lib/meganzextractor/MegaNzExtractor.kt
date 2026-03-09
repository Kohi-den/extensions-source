package eu.kanade.tachiyomi.lib.meganzextractor

import android.util.Base64
import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

class MegaNzExtractor(
    private val client: OkHttpClient,
    private val json: Json,
) {
    companion object {
        private const val TAG = "MegaNzExtractor"
        private const val API_URL = "https://g.api.mega.co.nz/cs"

        @Volatile
        private var proxyServer: MegaProxyServer? = null

        @Synchronized
        private fun getOrCreateProxy(): MegaProxyServer {
            proxyServer?.let { if (it.isAlive) return it }
            val server = MegaProxyServer()
            server.start()
            proxyServer = server
            return server
        }
    }

    fun videosFromUrl(
        url: String,
        prefix: String = "MEGA ",
    ): List<Video> {
        val (fileId, keyStr) = parseUrl(url) ?: return emptyList()

        val keyBytes = decodeKey(keyStr) ?: return emptyList()
        val (aesKey, iv) = deriveKeyAndIv(keyBytes)

        val fileInfo = getFileInfo(fileId) ?: return emptyList()
        val downloadUrl = fileInfo.first
        val fileSize = fileInfo.third
        val fileName = decryptAttributes(fileInfo.second, aesKey)

        val proxy = getOrCreateProxy()
        val token = proxy.registerAndStartDownload(downloadUrl, aesKey, iv, client, fileSize)

        val proxyUrl = "http://127.0.0.1:${proxy.listeningPort}/mega?token=$token"
        val quality =
            if (fileName?.contains("1080", ignoreCase = true) == true) {
                "1080p"
            } else if (fileName?.contains("720", ignoreCase = true) == true) {
                "720p"
            } else {
                "Video"
            }

        return listOf(Video(videoTitle = "$prefix$quality", videoUrl = proxyUrl))
    }

    private fun parseUrl(url: String): Pair<String, String>? {
        val regex = Regex("""mega\.nz/(?:embed|file)/([^#]+)#(.+)""")
        val legacyRegex = Regex("""mega\.nz/#!([^!]+)!(.+)""")

        regex.find(url)?.let {
            return Pair(it.groupValues[1], it.groupValues[2])
        }
        legacyRegex.find(url)?.let {
            return Pair(it.groupValues[1], it.groupValues[2])
        }
        return null
    }

    private fun decodeKey(keyStr: String): ByteArray? =
        try {
            Base64.decode(keyStr, Base64.URL_SAFE or Base64.NO_PADDING)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode key: ${e.message}")
            null
        }

    private fun deriveKeyAndIv(keyBytes: ByteArray): Pair<ByteArray, ByteArray> {
        val k = IntArray(8)
        val buf = ByteBuffer.wrap(keyBytes).order(ByteOrder.BIG_ENDIAN)
        for (i in 0 until minOf(8, keyBytes.size / 4)) {
            k[i] = buf.getInt()
        }

        val aesKeyInts = intArrayOf(k[0] xor k[4], k[1] xor k[5], k[2] xor k[6], k[3] xor k[7])
        val aesKey = ByteArray(16)
        ByteBuffer.wrap(aesKey).order(ByteOrder.BIG_ENDIAN).apply {
            aesKeyInts.forEach { putInt(it) }
        }

        val iv = ByteArray(16)
        ByteBuffer.wrap(iv).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(k[4])
            putInt(k[5])
        }

        return Pair(aesKey, iv)
    }

    private fun getFileInfo(fileId: String): Triple<String, String, Long>? {
        return try {
            val requestBody = """[{"a":"g","g":1,"ssl":2,"p":"$fileId"}]"""
            val request =
                Request
                    .Builder()
                    .url(API_URL)
                    .post(requestBody.toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()

            val response = client.newCall(request).execute()
            val body = response.body.string()
            val jsonResponse = json.decodeFromString<JsonArray>(body)
            val obj = jsonResponse[0].jsonObject

            if (obj.containsKey("e")) {
                Log.e(TAG, "MEGA API error: ${obj["e"]?.jsonPrimitive?.int}")
                return null
            }

            val downloadUrl = obj["g"]?.jsonPrimitive?.content ?: return null
            val fileSize = obj["s"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            val encAttributes = obj["at"]?.jsonPrimitive?.content ?: ""
            Triple(downloadUrl, encAttributes, fileSize)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get file info: ${e.message}")
            null
        }
    }

    private fun decryptAttributes(
        encAttr: String,
        aesKey: ByteArray,
    ): String? =
        try {
            val attrBytes = Base64.decode(encAttr, Base64.URL_SAFE or Base64.NO_PADDING)
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(ByteArray(16)))
            val decrypted = cipher.doFinal(attrBytes)
            val str = String(decrypted, Charsets.UTF_8)
            val nameRegex = Regex(""""n"\s*:\s*"([^"]+)"""")
            nameRegex.find(str)?.groupValues?.get(1)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt attributes: ${e.message}")
            null
        }

    class MegaProxyServer : NanoHTTPD(0) {
        init {
            java.util.logging.Logger
                .getLogger(NanoHTTPD::class.java.name)
                .level = java.util.logging.Level.OFF
        }

        class CachedDownload(
            val tempFile: File,
            val totalSize: Long,
        ) {
            @Volatile
            var downloadedBytes: Long = 0

            @Volatile
            var isComplete: Boolean = false

            @Volatile
            var error: String? = null
        }

        private val downloads = ConcurrentHashMap<String, CachedDownload>()
        private var tokenCounter = 0

        @Synchronized
        fun registerAndStartDownload(
            downloadUrl: String,
            aesKey: ByteArray,
            iv: ByteArray,
            client: OkHttpClient,
            fileSize: Long,
        ): String {
            val token = "mega_${++tokenCounter}"
            val tempFile = File.createTempFile("mega_", ".mp4")
            tempFile.deleteOnExit()
            val cached = CachedDownload(tempFile, fileSize)
            downloads[token] = cached

            thread(isDaemon = true, name = "mega-dl-$token") {
                downloadAndDecrypt(downloadUrl, aesKey, iv, client, cached)
            }

            return token
        }

        private fun downloadAndDecrypt(
            downloadUrl: String,
            aesKey: ByteArray,
            iv: ByteArray,
            client: OkHttpClient,
            cached: CachedDownload,
        ) {
            var response: okhttp3.Response? = null
            try {
                val request = Request.Builder().url(downloadUrl).build()
                response = client.newCall(request).execute()
                val input =
                    response.body?.byteStream()
                        ?: run {
                            cached.error = "Empty body"
                            return
                        }

                val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))

                FileOutputStream(cached.tempFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        val decrypted = cipher.update(buffer, 0, bytesRead)
                        if (decrypted != null && decrypted.isNotEmpty()) {
                            output.write(decrypted)
                            cached.downloadedBytes += decrypted.size
                        }
                    }
                    cipher.doFinal()?.let { remaining ->
                        if (remaining.isNotEmpty()) {
                            output.write(remaining)
                            cached.downloadedBytes += remaining.size
                        }
                    }
                    output.flush()
                }
                cached.isComplete = true
                Log.d(TAG, "Download complete: ${cached.downloadedBytes} bytes")
            } catch (e: Exception) {
                cached.error = e.message
                Log.e(TAG, "Download failed: ${e.message}")
            } finally {
                response?.close()
            }
        }

        override fun serve(session: IHTTPSession): Response {
            if (!session.uri.startsWith("/mega")) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
            }

            val token = session.parameters["token"]?.firstOrNull()
            val cached =
                token?.let { downloads[it] }
                    ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid token")

            cached.error?.let {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Download error: $it")
            }

            return try {
                serveFromFile(cached, session)
            } catch (e: Exception) {
                Log.e(TAG, "Proxy serve error: ${e.message}")
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
            }
        }

        private fun serveFromFile(
            cached: CachedDownload,
            session: IHTTPSession,
        ): Response {
            val rangeHeader = session.headers["range"]
            val totalSize = cached.totalSize

            val requestedStart =
                rangeHeader?.let {
                    Regex("""bytes=(\d+)-""")
                        .find(it)
                        ?.groupValues
                        ?.get(1)
                        ?.toLongOrNull()
                } ?: 0L
            val requestedEnd =
                rangeHeader?.let {
                    Regex("""bytes=\d+-(\d+)""")
                        .find(it)
                        ?.groupValues
                        ?.get(1)
                        ?.toLongOrNull()
                } ?: (totalSize - 1)

            val endByte = minOf(requestedEnd, totalSize - 1)
            val contentLength = endByte - requestedStart + 1

            val waitTarget = minOf(requestedStart + minOf(contentLength, 256 * 1024L), totalSize)
            val maxWait = 60_000L
            val startTime = System.currentTimeMillis()
            while (cached.downloadedBytes < waitTarget && !cached.isComplete && cached.error == null) {
                if (System.currentTimeMillis() - startTime > maxWait) {
                    return newFixedLengthResponse(
                        Response.Status.INTERNAL_ERROR,
                        MIME_PLAINTEXT,
                        "Timeout waiting for data",
                    )
                }
                Thread.sleep(100)
            }
            cached.error?.let {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Download error: $it")
            }

            val fileStream = TempFileInputStream(cached.tempFile, cached, requestedStart, endByte)

            return if (rangeHeader != null) {
                val response =
                    newFixedLengthResponse(
                        Response.Status.PARTIAL_CONTENT,
                        "video/mp4",
                        fileStream,
                        contentLength,
                    )
                response.addHeader("Content-Range", "bytes $requestedStart-$endByte/$totalSize")
                response.addHeader("Accept-Ranges", "bytes")
                response
            } else {
                val response =
                    newFixedLengthResponse(
                        Response.Status.OK,
                        "video/mp4",
                        fileStream,
                        totalSize,
                    )
                response.addHeader("Accept-Ranges", "bytes")
                response
            }
        }
    }

    private class TempFileInputStream(
        file: File,
        private val cached: MegaProxyServer.CachedDownload,
        startByte: Long,
        private val endByte: Long,
    ) : InputStream() {
        private val raf = RandomAccessFile(file, "r")
        private var position = startByte

        init {
            raf.seek(startByte)
        }

        override fun read(): Int {
            if (position > endByte) return -1
            waitForData(position + 1)
            if (cached.error != null) return -1
            position++
            return raf.read()
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (position > endByte) return -1
            val maxRead = minOf(len.toLong(), endByte - position + 1).toInt()
            if (maxRead <= 0) return -1
            waitForData(position + 1)
            if (cached.error != null) return -1

            val available = minOf(maxRead.toLong(), cached.downloadedBytes - position).toInt()
            if (available <= 0) {
                if (cached.isComplete) return -1
                waitForData(position + 1)
                val nowAvailable = minOf(maxRead.toLong(), cached.downloadedBytes - position).toInt()
                if (nowAvailable <= 0) return -1
                val bytesRead = raf.read(b, off, nowAvailable)
                if (bytesRead > 0) position += bytesRead
                return bytesRead
            }

            val bytesRead = raf.read(b, off, available)
            if (bytesRead > 0) position += bytesRead
            return bytesRead
        }

        private fun waitForData(needed: Long) {
            val maxWait = 30_000L
            val start = System.currentTimeMillis()
            while (cached.downloadedBytes < needed && !cached.isComplete && cached.error == null) {
                if (System.currentTimeMillis() - start > maxWait) return
                Thread.sleep(50)
            }
        }

        override fun close() {
            try {
                raf.close()
            } catch (_: Exception) {
            }
        }
    }
}
