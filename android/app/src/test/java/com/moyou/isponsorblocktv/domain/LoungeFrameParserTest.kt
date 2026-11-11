package com.moyou.isponsorblocktv.domain

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class LoungeFrameParserTest {
    @Test fun readsMultipleFrames() {
        val data = "3\n[1]\n3\n[2]".toByteArray()
        val input = ByteArrayInputStream(data)
        assertEquals("[1]", LoungeFrameParser.readFrame(input))
        assertEquals("[2]", LoungeFrameParser.readFrame(input))
        assertNull(LoungeFrameParser.readFrame(input))
    }

    @Test(expected = IOException::class)
    fun rejectsOversizedFrame() {
        LoungeFrameParser.readFrame(ByteArrayInputStream("9999999\n".toByteArray()))
    }

    @Test(expected = IOException::class)
    fun rejectsTruncatedFrame() {
        LoungeFrameParser.readFrame(ByteArrayInputStream("10\n[]".toByteArray()))
    }
}
