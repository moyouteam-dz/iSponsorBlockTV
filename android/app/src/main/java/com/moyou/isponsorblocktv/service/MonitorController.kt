package com.moyou.isponsorblocktv.service

import android.os.SystemClock
import com.moyou.isponsorblocktv.data.IoDispatcher
import com.moyou.isponsorblocktv.data.LoungeClient
import com.moyou.isponsorblocktv.data.SettingsRepository
import com.moyou.isponsorblocktv.data.SponsorBlockRepository
import com.moyou.isponsorblocktv.domain.LinkStatus
import com.moyou.isponsorblocktv.domain.MonitorConfig
import com.moyou.isponsorblocktv.domain.MonitorStatus
import com.moyou.isponsorblocktv.domain.SponsorSegments
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

@Singleton
class MonitorController @Inject constructor(
    private val lounge: LoungeClient,
    private val sponsorBlock: SponsorBlockRepository,
    private val settings: SettingsRepository,
    @IoDispatcher io: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + io)
    private val mutableStatus = MutableStateFlow(MonitorStatus())
    val status: StateFlow<MonitorStatus> = mutableStatus
    private var monitorJob: Job? = null
    private var shutdownJob: Job? = null
    @Volatile private var stopping = false
    private var pendingSkip: Job? = null
    private var videoId = ""
    private var position = 0.0
    private var playbackSpeed = 1.0
    private var playing = false
    private var volume = "100"
    private var mutedByUs = false

    @Synchronized
    fun start() {
        if (monitorJob?.isActive == true) return
        val config = settings.settings.value
        if (!config.isPaired) {
            mutableStatus.value = MonitorStatus(LinkStatus.ERROR, "يرجى ربط التلفاز أولًا")
            return
        }
        videoId = ""
        playing = false
        mutableStatus.value = MonitorStatus(LinkStatus.CONNECTING, "جارٍ الاتصال بالتلفاز…")
        val cleanup = shutdownJob
        monitorJob = scope.launch {
            cleanup?.join() // Prevent a previous stop from closing the next session.
            stopping = false
            monitor(config.screenId)
        }
    }

    @Synchronized
    fun stop() {
        if (shutdownJob?.isActive == true) return
        stopping = true
        val current = monitorJob
        monitorJob = null
        pendingSkip?.cancel()
        pendingSkip = null
        shutdownJob = scope.launch {
            // Unmute *before* disconnecting. Never block the Service main thread.
            if (mutedByUs && lounge.connected) {
                try { withTimeoutOrNull(2_500) { lounge.mute(false, volume) } }
                catch (_: Exception) { /* best effort */ }
            }
            mutedByUs = false
            current?.cancelAndJoin()
            lounge.close()
            mutableStatus.value = MonitorStatus(LinkStatus.STOPPED, "الخدمة متوقفة", skippedSegments = mutableStatus.value.skippedSegments)
        }
        mutableStatus.value = MonitorStatus(LinkStatus.STOPPED, "الخدمة متوقفة", skippedSegments = mutableStatus.value.skippedSegments)
    }

    private suspend fun monitor(screenId: String) {
        try {
            while (currentCoroutineContext().isActive) {
                try {
                    mutableStatus.update { it.copy(status = LinkStatus.CONNECTING, detail = "مصادقة YouTube…") }
                    lounge.refreshAuth(screenId)
                    if (!lounge.isAvailable()) {
                        mutableStatus.update { it.copy(status = LinkStatus.RETRYING, detail = "افتح تطبيق YouTube على التلفاز") }
                        delay(10_000)
                        continue
                    }
                    if (!lounge.connect()) {
                        mutableStatus.update { it.copy(status = LinkStatus.RETRYING, detail = "تعذّر بدء جلسة التلفاز، إعادة المحاولة…") }
                        delay(10_000)
                        continue
                    }
                    mutableStatus.update { it.copy(status = LinkStatus.CONNECTED, detail = "متصل بالتلفاز") }
                    // Request an explicit playback update in case video is already playing.
                    try { lounge.getNowPlaying() } catch (_: Exception) { }
                    lounge.subscribe { type, data -> handleEvent(type, data) }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    mutableStatus.update { it.copy(status = LinkStatus.RETRYING, detail = "انقطع الاتصال، ستتم إعادة المحاولة") }
                } finally {
                    pendingSkip?.cancel()
                    pendingSkip = null
                    lounge.close()
                }
                delay(8_000)
            }
        } finally {
            pendingSkip?.cancel()
            lounge.close()
        }
    }

    private suspend fun handleEvent(type: String, data: JSONObject) {
        if (stopping) return
        val config = settings.settings.value
        when (type) {
            "nowPlaying" -> {
                val newId = data.optString("videoId")
                if (newId.isNotBlank() && newId != videoId) {
                    videoId = newId
                    position = 0.0
                    playbackSpeed = 1.0
                }
                position = number(data, "currentTime", position)
                playing = data.optString("state", "0") == "1"
                mutableStatus.update { it.copy(currentVideoId = videoId, detail = "متصل بالتلفاز") }
                scheduleSkip(config)
                if (playing) releaseMuteIfNeeded()
            }
            "onStateChange" -> {
                playing = data.optString("state") == "1"
                position = number(data, "currentTime", position)
                scheduleSkip(config)
                if (playing) releaseMuteIfNeeded()
            }
            "onPlaybackSpeedChanged" -> {
                playbackSpeed = number(data, "playbackSpeed", 1.0).coerceIn(0.25, 4.0)
                scheduleSkip(config)
                try { lounge.getNowPlaying() } catch (_: Exception) { }
            }
            "onVolumeChanged" -> {
                volume = data.optString("volume", volume)
                if (data.optString("muted") == "false") mutedByUs = false
            }
            "onAdStateChange", "adPlaying" -> handleAd(data, config)
            "autoplayUpNext" -> {
                val nextVideo = data.optString("videoId")
                if (nextVideo.isNotBlank()) CoroutineScope(currentCoroutineContext()).launch {
                    try { sponsorBlock.segments(nextVideo, settings.settings.value.categories, settings.settings.value.minSkipSeconds) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { }
                }
            }
            "onAutoplayModeChanged" -> {
                if (config.autoPlay) lounge.setAutoPlay(true)
            }
            "loungeScreenDisconnected" -> throw IOException("TV disconnected")
            "loungeStatus" -> {
                val raw = data.optString("devices", "[]")
                val devices = org.json.JSONArray(raw)
                for (i in 0 until devices.length()) {
                    val device = devices.optJSONObject(i) ?: continue
                    if (device.optString("type") == "LOUNGE_SCREEN") {
                        val details = try { JSONObject(device.optString("deviceInfo", "{}")) }
                            catch (_: Exception) { JSONObject() }
                        if (details.optString("clientName") == "TVHTML5_FOR_KIDS") {
                            throw IOException("YouTube Kids profile is not supported")
                        }
                    }
                }
            }
        }
    }

    private fun number(data: JSONObject, key: String, default: Double): Double =
        data.optString(key).toDoubleOrNull()?.takeIf { it.isFinite() } ?: default

    private fun scheduleSkip(config: MonitorConfig) {
        pendingSkip?.cancel()
        pendingSkip = null
        if (!playing || videoId.isBlank() || config.categories.isEmpty()) return
        val currentVideoId = videoId
        val currentPosition = position
        val currentSpeed = playbackSpeed
        val eventTime = SystemClock.elapsedRealtime()
        pendingSkip = scope.launch {
            try {
                val segments = sponsorBlock.segments(currentVideoId, config.categories, config.minSkipSeconds)
                var anchorPosition = currentPosition
                var anchorTime = eventTime
                while (isActive && lounge.connected && segments.isNotEmpty()) {
                    val elapsed = (SystemClock.elapsedRealtime() - anchorTime) / 1000.0
                    val decision = SponsorSegments.next(segments, anchorPosition, currentSpeed, elapsed, config.offsetMillis)
                        ?: break
                    delay((decision.waitSeconds * 1000).toLong())
                    ensureActive()
                    if (!lounge.connected || videoId != currentVideoId || !playing) break
                    val projectedPosition = anchorPosition +
                        (SystemClock.elapsedRealtime() - anchorTime) / 1000.0 * currentSpeed
                    if (projectedPosition >= decision.segment.end) {
                        anchorPosition = decision.segment.end
                        anchorTime = SystemClock.elapsedRealtime()
                        continue
                    }
                    if (lounge.seekTo(decision.segment.end)) {
                        mutableStatus.update { it.copy(skippedSegments = it.skippedSegments + 1, detail = "تم تخطي مقطع") }
                        if (config.trackSkips) launch {
                            sponsorBlock.markViewed(decision.segment.uuids)
                        }
                    }
                    anchorPosition = decision.segment.end
                    anchorTime = SystemClock.elapsedRealtime()
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // A failed SponsorBlock/YouTube API request must never crash the foreground service.
            }
        }
    }

    private suspend fun handleAd(data: JSONObject, config: MonitorConfig) {
        val adState = data.optString("adState")
        val ended = (adState == "0" || adState == "1082") && data.optString("currentTime") != "0"
        val skipAllowed = data.optString("isSkipEnabled") == "true"
        when {
            ended -> releaseMuteIfNeeded()
            config.skipAds && skipAllowed -> {
                lounge.skipAd()
                releaseMuteIfNeeded()
            }
            config.muteAds && !mutedByUs -> {
                if (lounge.mute(true, volume)) {
                    mutedByUs = true
                    mutableStatus.update { it.copy(mutedByApp = true) }
                }
            }
        }
    }

    private suspend fun releaseMuteIfNeeded() {
        if (!mutedByUs) return
        if (lounge.mute(false, volume)) {
            mutedByUs = false
            mutableStatus.update { it.copy(mutedByApp = false) }
        }
    }
}
