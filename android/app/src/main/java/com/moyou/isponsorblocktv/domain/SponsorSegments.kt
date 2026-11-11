package com.moyou.isponsorblocktv.domain

import kotlin.math.max

/** Times are seconds, not milliseconds. No Android dependencies, so core logic is unit-testable. */
data class RawSegment(val start: Double, val end: Double, val uuid: String)
data class SkipSegment(val start: Double, val end: Double, val uuids: List<String>)

data class SkipDecision(val segment: SkipSegment, val waitSeconds: Double)

object SponsorSegments {
    /** Merge overlapping/adjacent SponsorBlock segments, then apply minimum length. */
    fun merge(raw: List<RawSegment>, minSeconds: Double = 1.0): List<SkipSegment> {
        if (raw.isEmpty()) return emptyList()
        val sorted = raw.asSequence()
            .filter { it.start.isFinite() && it.end.isFinite() && it.end > it.start }
            .sortedWith(compareBy<RawSegment> { it.start }.thenBy { it.end })
            .toList()
        if (sorted.isEmpty()) return emptyList()
        val merged = mutableListOf<SkipSegment>()
        var start = sorted.first().start
        var end = sorted.first().end
        var uuids = linkedSetOf(sorted.first().uuid)
        for (item in sorted.drop(1)) {
            if (item.start - end < 1.0) {
                end = max(end, item.end)
                uuids.add(item.uuid)
            } else {
                if (end - start > minSeconds) merged.add(SkipSegment(start, end, uuids.toList()))
                start = item.start
                end = item.end
                uuids = linkedSetOf(item.uuid)
            }
        }
        if (end - start > minSeconds) merged.add(SkipSegment(start, end, uuids.toList()))
        return merged
    }

    /** Match the upstream early-video behavior: skip an already-started segment only near 0s. */
    fun next(segments: List<SkipSegment>, positionSeconds: Double, speed: Double = 1.0,
             elapsedSeconds: Double = 0.0, offsetMillis: Int = 0): SkipDecision? {
        val safeSpeed = speed.coerceAtLeast(0.25)
        val pos = positionSeconds.coerceAtLeast(0.0)
        val current = pos + elapsedSeconds.coerceAtLeast(0.0) * safeSpeed
        // Never seek backwards if the API responded after a segment ended.
        val segment = segments.firstOrNull { s ->
            s.end > current && (s.start > pos || (pos < 1.0 && s.start <= pos && pos < s.end))
        } ?: return null
        val from = if (pos < 1.0 && segment.start <= pos) pos else segment.start
        val wait = (from - current) / safeSpeed - offsetMillis / 1000.0
        return SkipDecision(segment, wait.coerceAtLeast(0.0))
    }
}
