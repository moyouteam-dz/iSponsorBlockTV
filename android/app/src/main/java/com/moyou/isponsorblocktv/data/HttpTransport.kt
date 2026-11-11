package com.moyou.isponsorblocktv.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import com.moyou.isponsorblocktv.domain.LoungeFrameParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** HTTPS only. Interactions with YouTube Lounge and SponsorBlock use no embedded webview. */
@Singleton
class HttpTransport @Inject constructor(@IoDispatcher private val io: CoroutineDispatcher) {
    data class Response(val code: Int, val body: String)

    private fun params(data: Map<String, String>): String = data.entries.joinToString("&") { (k, v) ->
        "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" 
    }

    private fun connection(url: String, method: String, timeout: Int): HttpURLConnection {
        require(url.startsWith("https://")) { "HTTPS is required" }
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = timeout
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "iSponsorBlockTV-Android/0.1")
            setRequestProperty("Accept", "application/json, */*")
        }
    }

    private fun addParams(url: String, data: Map<String, String>) =
        if (data.isEmpty()) url else url + (if (url.contains('?')) "&" else "?") + params(data)

    suspend fun postForm(url: String, data: Map<String, String>, query: Map<String, String> = emptyMap()): Response =
        withContext(io) {
            val conn = connection(addParams(url, query), "POST", 25_000)
            try {
                val bytes = params(data).toByteArray(Charsets.UTF_8)
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                conn.outputStream.use { it.write(bytes) }
                readResponse(conn)
            } finally { conn.disconnect() }
        }

    suspend fun get(url: String, query: Map<String, String> = emptyMap()): Response = withContext(io) {
        val conn = connection(addParams(url, query), "GET", 25_000)
        try { readResponse(conn) } finally { conn.disconnect() }
    }

    /** The Lounge bind stream consists of ASCII length + newline + a UTF-8 JSON array. */
    suspend fun stream(
        url: String, query: Map<String, String>,
        onConnection: (HttpURLConnection?) -> Unit,
        onFrame: suspend (String) -> Unit,
    ) = withContext(io) {
        val conn = connection(addParams(url, query), "GET", 75_000)
        onConnection(conn)
        try {
            if (conn.responseCode !in 200..299) throw IOException("YouTube stream HTTP ${conn.responseCode}")
            conn.inputStream.buffered().use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val frame = LoungeFrameParser.readFrame(input) ?: break
                    onFrame(frame)
                }
            }
        } finally {
            onConnection(null)
            conn.disconnect()
        }
    }

    private fun readResponse(conn: HttpURLConnection): Response {
        val code = conn.responseCode
        val input = if (code >= 400) conn.errorStream else conn.inputStream
        val bytes = input?.use { it.readBytesLimited(4_000_000) } ?: ByteArray(0)
        return Response(code, bytes.toString(Charsets.UTF_8))
    }

    // Frame parsing is in the pure Kotlin domain for deterministic tests.

}

private fun InputStream.readBytesLimited(max: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val block = ByteArray(8_192)
    while (true) {
        val size = read(block)
        if (size == -1) return output.toByteArray()
        if (output.size() + size > max) throw IOException("HTTP response too large")
        output.write(block, 0, size)
    }
}
