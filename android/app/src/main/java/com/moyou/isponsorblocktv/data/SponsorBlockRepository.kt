package com.moyou.isponsorblocktv.data

import com.moyou.isponsorblocktv.domain.RawSegment
import com.moyou.isponsorblocktv.domain.SkipSegment
import com.moyou.isponsorblocktv.domain.SponsorSegments
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

@Singleton
class SponsorBlockRepository @Inject constructor(private val http: HttpTransport) {
    private data class CacheEntry(val timestamp: Long, val segments: List<RawSegment>)
    private data class FetchResult(val segments: List<RawSegment>, val cacheable: Boolean)
    private val cache = LinkedHashMap<String, CacheEntry>()
    private val url = "https://sponsor.ajay.app/api/"

    suspend fun segments(videoId: String, categories: Set<String>, minimum: Int): List<SkipSegment> {
        if (categories.isEmpty() || videoId.isBlank()) return emptyList()
        val key = videoId + ":" + categories.sorted().joinToString(",")
        val now = System.currentTimeMillis()
        val cached = synchronized(cache) { cache[key]?.takeIf { now - it.timestamp < 5 * 60 * 1000 } }
        val raw = cached?.segments ?: fetch(videoId, categories).let { result ->
            if (result.cacheable) {
                synchronized(cache) {
                    cache[key] = CacheEntry(now, result.segments)
                    while (cache.size > 60) cache.remove(cache.keys.first())
                }
            }
            result.segments
        }
        return SponsorSegments.merge(raw, minimum.toDouble())
    }

    private suspend fun fetch(videoId: String, categories: Set<String>): FetchResult {
        val prefix = MessageDigest.getInstance("SHA-256")
            .digest(videoId.toByteArray(Charsets.UTF_8))
            .take(2).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        // URL uses repeated category[] keys as in upstream's aiohttp list parameters.
        val categoryArgs = categories.sorted().joinToString("&") { "category=${java.net.URLEncoder.encode(it, "UTF-8")}" }
        val fullUrl = "${url}skipSegments/$prefix?actionType=skip&service=youtube&$categoryArgs"
        val response = http.get(fullUrl)
        if (response.code == 404) return FetchResult(emptyList(), cacheable = true)
        if (response.code != 200) throw java.io.IOException("SponsorBlock HTTP ${response.code}")
        // Malformed/transient responses must be retried, not cached as empty results.
        val choices = try { JSONArray(response.body) }
            catch (error: JSONException) { throw java.io.IOException("Invalid SponsorBlock JSON", error) }
        for (index in 0 until choices.length()) {
            val candidate = choices.optJSONObject(index) ?: continue
            if (candidate.optString("videoID") != videoId) continue
            val found = candidate.optJSONArray("segments") ?: return FetchResult(emptyList(), cacheable = false)
            val result = mutableListOf<RawSegment>()
            for (i in 0 until found.length()) {
                val part = found.optJSONObject(i) ?: continue
                val interval = part.optJSONArray("segment") ?: continue
                if (interval.length() < 2) continue
                result += RawSegment(interval.optDouble(0), interval.optDouble(1), part.optString("UUID"))
            }
            return FetchResult(result, cacheable = true)
        }
        // Another video can share the four-character hash bucket.
        return FetchResult(emptyList(), cacheable = false)
    }

    suspend fun markViewed(uuids: List<String>) {
        for (uuid in uuids) {
            if (uuid.isBlank()) continue
            try {
                http.postForm("${url}viewedVideoSponsorTime/?UUID=${java.net.URLEncoder.encode(uuid, "UTF-8")}", emptyMap())
            } catch (_: Exception) { /* Skip reporting is best effort, never block playback. */ }
        }
    }
}
