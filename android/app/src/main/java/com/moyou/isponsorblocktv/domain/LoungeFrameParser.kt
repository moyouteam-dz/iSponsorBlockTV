package com.moyou.isponsorblocktv.domain

import java.io.IOException
import java.io.InputStream

/** Decodes YouTube's length-prefixed bind stream without trusting remote frame lengths. */
object LoungeFrameParser {
    fun readFrame(input: InputStream): String? {
        val number = StringBuilder()
        while (true) {
            val next = input.read()
            if (next == -1) return if (number.isEmpty()) null else throw IOException("Incomplete frame")
            if (next == 10) {
                if (number.isEmpty()) continue
                break
            }
            if (next == 13) continue
            if (next !in 48..57 || number.length >= 8) throw IOException("Bad Lounge frame length")
            number.append(next.toChar())
        }
        val length = number.toString().toIntOrNull() ?: throw IOException("Invalid Lounge length")
        if (length <= 0 || length > 4_000_000) throw IOException("Oversized Lounge frame")
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(bytes, offset, length - offset)
            if (count <= 0) throw IOException("Truncated Lounge frame")
            offset += count
        }
        return bytes.toString(Charsets.UTF_8)
    }
}
