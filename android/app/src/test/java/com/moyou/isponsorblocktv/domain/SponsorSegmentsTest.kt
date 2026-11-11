package com.moyou.isponsorblocktv.domain

import org.junit.Assert.*
import org.junit.Test

class SponsorSegmentsTest {
    @Test fun mergesOverlappingSegmentsAndRetainsTrackingIds() {
        val list = SponsorSegments.merge(listOf(
            RawSegment(10.0, 15.0, "a"),
            RawSegment(14.0, 20.0, "b"),
            RawSegment(20.3, 23.0, "c"),
            RawSegment(60.0, 65.0, "d"),
        ))
        assertEquals(2, list.size)
        assertEquals(23.0, list.first().end, 0.0001)
        assertEquals(listOf("a", "b", "c"), list.first().uuids)
    }

    @Test fun rejectsBadAndShortSegments() {
        val list = SponsorSegments.merge(listOf(
            RawSegment(5.0, 5.5, "short"),
            RawSegment(10.0, 5.0, "backward"),
            RawSegment(Double.NaN, 20.0, "invalid"),
        ))
        assertTrue(list.isEmpty())
    }

    @Test fun skipsAtBeginningAndSchedulesUpcomingSegments() {
        val segments = listOf(
            SkipSegment(0.0, 12.0, listOf("a")),
            SkipSegment(45.0, 65.0, listOf("b")),
        )
        assertEquals(0.0, SponsorSegments.next(segments, 0.5)!!.waitSeconds, 0.0001)
        assertEquals(34.5, SponsorSegments.next(segments, 10.0, elapsedSeconds = 0.5)!!.waitSeconds, 0.0001)
        assertNull(SponsorSegments.next(segments, 65.0))
    }

    @Test fun neverSeeksBackwardsAfterSlowNetwork() {
        val s = listOf(SkipSegment(10.0, 15.0, listOf("a")), SkipSegment(35.0, 40.0, listOf("b")))
        assertEquals("b", SponsorSegments.next(s, 0.0, elapsedSeconds = 20.0)!!.segment.uuids.single())
        assertNull(SponsorSegments.next(s, 0.0, elapsedSeconds = 50.0))
    }

    @Test fun appliesSpeedAndOffset() {
        val s = listOf(SkipSegment(20.0, 30.0, listOf("x")))
        assertEquals(4.8, SponsorSegments.next(s, 10.0, speed=2.0, offsetMillis=200)!!.waitSeconds, 0.001)
    }
}
