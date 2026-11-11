package com.moyou.isponsorblocktv.data

import com.moyou.isponsorblocktv.domain.LoungeFrameParser
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Minimal native port of the YouTube Lounge command/event protocol used by pyytlounge. */
class LoungeClient @Inject constructor(private val http: HttpTransport) {
    private val base = "https://www.youtube.com/api/lounge"
    private val commandMutex = Mutex()
    private var loungeToken: String = ""
    private var screenId: String = ""
    private var sid: String = ""
    private var gsession: String = ""
    private var lastEventId: String = "0"
    private var offset = 1
    private val clientName = "iSponsorBlockTV-Android"
    @Volatile private var streamConnection: HttpURLConnection? = null

    val connected: Boolean get() = sid.isNotBlank() && gsession.isNotBlank()

    fun close() {
        streamConnection?.disconnect()
        streamConnection = null
        sid = ""
        gsession = ""
    }

    suspend fun refreshAuth(id: String) {
        require(id.isNotBlank()) { "Screen id missing" }
        screenId = id
        val result = http.postForm("$base/pairing/get_lounge_token_batch", mapOf("screen_ids" to id))
        if (result.code != 200) throw IOException("YouTube authorization HTTP ${result.code}")
        val array = JSONObject(result.body).optJSONArray("screens")
        val item = array?.optJSONObject(0) ?: throw IOException("Device authorization unavailable")
        loungeToken = item.optString("loungeToken")
        if (loungeToken.isBlank()) throw IOException("YouTube returned an empty Lounge token")
    }

    suspend fun isAvailable(): Boolean {
        if (loungeToken.isBlank()) return false
        val r = http.postForm("$base/pairing/get_screen_availability", mapOf("lounge_token" to loungeToken))
        if (r.code != 200) return false
        return JSONObject(r.body).optJSONArray("screens")?.optJSONObject(0)
            ?.optString("status") == "online"
    }

    suspend fun connect(): Boolean {
        if (loungeToken.isBlank()) return false
        val bytes = MessageDigest.getInstance("MD5").digest("iSponsorBlockTV$screenId".toByteArray())
        val deviceId = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        val body = mapOf(
            "id" to deviceId,
            "mdx-version" to "3",
            "TYPE" to "xmlhttp",
            "theme" to "cl",
            "sessionSource" to "MDX_SESSION_SOURCE_UNKNOWN",
            "RID" to "1",
            "CVER" to "1",
            "capabilities" to "que,dsdtr,atp,vsp",
            "ui" to "false",
            "app" to "ytios-phone-20.15.1",
            "pairing_type" to "manual",
            "VER" to "8",
            "loungeIdToken" to loungeToken,
            "device" to "REMOTE_CONTROL",
            "name" to clientName,
        )
        sid = ""
        gsession = ""
        lastEventId = "0"
        val response = http.postForm("$base/bc/bind", body)
        if (response.code == 401) {
            loungeToken = ""
            return false
        }
        if (response.code != 200) throw IOException("YouTube Lounge HTTP ${response.code}")
        ByteArrayInputStream(response.body.toByteArray(Charsets.UTF_8)).use { input ->
            while (true) {
                val frame = LoungeFrameParser.readFrame(input) ?: break
                parseEvents(frame) { _, _ -> }
            }
        }
        offset = 1
        return connected
    }

    suspend fun subscribe(onEvent: suspend (name: String, data: JSONObject) -> Unit) {
        if (!connected) throw IOException("Lounge is disconnected")
        val params = commonParams() + mapOf("RID" to "rpc", "CI" to "0", "TYPE" to "xmlhttp")
        http.stream("$base/bc/bind", params,
            onConnection = { streamConnection = it },
            onFrame = { parseEvents(it, onEvent) },
        )
    }

    private suspend fun parseEvents(frame: String, onEvent: suspend (String, JSONObject) -> Unit) {
        val events = JSONArray(frame)
        for (i in 0 until events.length()) {
            val event = events.optJSONArray(i) ?: continue
            if (event.length() < 2) continue
            lastEventId = event.optString(0, lastEventId)
            val payload = event.optJSONArray(1) ?: continue
            if (payload.length() == 0) continue
            when (val type = payload.optString(0)) {
                "c" -> sid = payload.optString(1)
                "S" -> gsession = payload.optString(1)
                "loungeScreenDisconnected" -> {
                    sid = ""
                    gsession = ""
                    onEvent(type, payload.optJSONObject(1) ?: JSONObject())
                }
                else -> onEvent(type, payload.optJSONObject(1) ?: JSONObject())
            }
        }
    }

    private fun commonParams() = mapOf(
        "name" to clientName,
        "loungeIdToken" to loungeToken,
        "SID" to sid,
        "AID" to lastEventId,
        "gsessionid" to gsession,
        "device" to "REMOTE_CONTROL",
        "app" to "ytios-phone-20.15.1",
        "VER" to "8",
        "v" to "2",
    )

    private suspend fun command(name: String, data: Map<String, String> = emptyMap()): Boolean =
        commandMutex.withLock {
            if (!connected) return@withLock false
            val body = linkedMapOf("count" to "1", "ofs" to offset.toString(), "req0__sc" to name)
            for ((key, value) in data) body["req0_$key"] = value
            offset += 1
            val response = http.postForm("$base/bc/bind", body, commonParams() + mapOf("RID" to offset.toString()))
            if (response.code == 400 || response.code == 410 || response.code == 401) {
                close()
                return@withLock false
            }
            if (response.code !in 200..299) throw IOException("Lounge command $name HTTP ${response.code}")
            true
        }

    suspend fun seekTo(seconds: Double): Boolean = command("seekTo", mapOf("newTime" to seconds.toString()))
    suspend fun skipAd(): Boolean = command("skipAd")
    suspend fun mute(muted: Boolean, volume: String): Boolean = command(
        "setVolume", mapOf("volume" to volume, "muted" to if (muted) "true" else "false")
    )
    suspend fun setAutoPlay(enabled: Boolean): Boolean = command(
        "setAutoplayMode", mapOf("autoplayMode" to if (enabled) "ENABLED" else "DISABLED")
    )
    suspend fun getNowPlaying(): Boolean = command("getNowPlaying")
}
