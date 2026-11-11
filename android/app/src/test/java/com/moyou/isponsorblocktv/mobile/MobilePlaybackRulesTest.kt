package com.moyou.isponsorblocktv.mobile

import com.moyou.isponsorblocktv.domain.SkipSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MobilePlaybackRulesTest {
    @Test fun acceptsWatchShortsLiveAndShortLinks() {
        val id = "dQw4w9WgXcQ"
        assertEquals(id, MobilePlaybackRules.videoId("https://m.youtube.com/watch?v=$id&t=2"))
        assertEquals(id, MobilePlaybackRules.videoId("https://www.youtube.com/shorts/$id"))
        assertEquals(id, MobilePlaybackRules.videoId("https://youtube.com/live/$id"))
        assertEquals(id, MobilePlaybackRules.videoId("https://youtu.be/$id"))
    }

    @Test fun rejectsUntrustedAndInvalidUrls() {
        assertNull(MobilePlaybackRules.videoId("https://youtube.com.evil.invalid/watch?v=dQw4w9WgXcQ"))
        assertNull(MobilePlaybackRules.videoId("http://youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(MobilePlaybackRules.videoId("https://youtube.com/results?search_query=music"))
        assertNull(MobilePlaybackRules.videoId("https://m.youtube.com/watch?v=short"))
        assertNull(MobilePlaybackRules.videoId("not a URL"))
    }

    @Test fun skipsOnlyWithinLivePlayingVideoSegments() {
        val segment = SkipSegment(10.0, 17.0, listOf("id"))
        val segments = listOf(segment)
        assertEquals(segment, MobilePlaybackRules.segmentAt(segments, 11.0, true, false))
        assertNull(MobilePlaybackRules.segmentAt(segments, 9.0, true, false))
        assertNull(MobilePlaybackRules.segmentAt(segments, 17.0, true, false))
        assertNull(MobilePlaybackRules.segmentAt(segments, 11.0, false, false))
        assertNull(MobilePlaybackRules.segmentAt(segments, 11.0, true, true))
        assertNull(MobilePlaybackRules.segmentAt(segments, Double.NaN, true, false))
    }
}
