package com.airtouch.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class RgbaRowsTest {

    @Test
    fun dropsRowPadding() {
        val width = 3
        val height = 2
        val rowStride = 16 // 12 bytes of pixels + 4 bytes of padding
        val src = ByteBuffer.allocate(rowStride * (height - 1) + width * 4)
        for (y in 0 until height) {
            for (i in 0 until width * 4) src.put(y * rowStride + i, (10 * y + i).toByte())
            if (y < height - 1) for (p in width * 4 until rowStride) src.put(y * rowStride + p, PAD)
        }
        val dst = ByteBuffer.allocate(width * height * 4)

        RgbaRows.pack(src, rowStride, width, height, dst)

        val expected = ByteArray(width * height * 4) { i -> (10 * (i / 12) + i % 12).toByte() }
        assertEquals(expected.size, dst.remaining())
        assertArrayEquals(expected, ByteArray(dst.remaining()).also { dst.get(it) })
    }

    @Test
    fun leavesSourcePositionUntouched() {
        val src = ByteBuffer.allocate(32)
        RgbaRows.pack(src, rowStride = 16, width = 4, height = 2, dst = ByteBuffer.allocate(32))
        assertEquals(0, src.position())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsStrideShorterThanARow() {
        RgbaRows.pack(ByteBuffer.allocate(64), rowStride = 8, width = 4, height = 2, dst = ByteBuffer.allocate(64))
    }

    private companion object {
        const val PAD: Byte = -1
    }
}
