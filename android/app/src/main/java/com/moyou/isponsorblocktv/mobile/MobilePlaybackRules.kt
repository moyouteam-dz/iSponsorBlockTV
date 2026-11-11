package com.moyou.isponsorblocktv.mobile

import com.moyou.isponsorblocktv.domain.SkipSegment
import java.net.URI

/** Pure Kotlin URL validation and SponsorBlock playback safety rules. */
object MobilePlaybackRules {
    private val videoIdPattern = Regex("^[A-Za-z0-9_-]{11}$")

    fun videoId(url: String): String? {
        val uri = try { URI(url) } catch (_: Exception) { return null }
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        val host = uri.host?.lowercase() ?: return null
        val path = uri.path.orEmpty()
        val id = when {
            host == "youtu.be" -> path.trim('/').substringBefore('/')
            host == "youtube.com" || host.endsWith(".youtube.com") -> when {
                path == "/watch" -> uri.rawQuery.orEmpty().split('&')
                    .firstOrNull { it.startsWith("v=") }?.substringAfter("v=")
                path.startsWith("/shorts/") -> path.removePrefix("/shorts/").substringBefore('/')
                path.startsWith("/live/") -> path.removePrefix("/live/").substringBefore('/')
                else -> null
            }
            else -> null
        }
        return id?.takeIf { videoIdPattern.matches(it) }
    }

    fun segmentAt(segments: List<SkipSegment>, seconds: Double,
                  playing: Boolean, advertisement: Boolean): SkipSegment? {
        if (!playing || advertisement || !seconds.isFinite() || seconds < 0.0) return null
        return segments.firstOrNull { s ->
            s.start.isFinite() && s.end.isFinite() &&
                seconds >= s.start && seconds < s.end - 0.3
        }
    }
