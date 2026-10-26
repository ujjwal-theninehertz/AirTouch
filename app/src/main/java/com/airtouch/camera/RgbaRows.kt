package com.airtouch.camera

import java.nio.ByteBuffer

/** Removes row padding from an RGBA image so it can be copied into a Bitmap in one call. */
internal object RgbaRows {

    const val BYTES_PER_PIXEL = 4

    /** Copies [height] rows of [width] pixels from [src], whose rows start every [rowStride] bytes, into [dst]. */
    fun pack(src: ByteBuffer, rowStride: Int, width: Int, height: Int, dst: ByteBuffer) {
        val rowBytes = width * BYTES_PER_PIXEL
        require(rowStride >= rowBytes) { "rowStride $rowStride is shorter than a row of $rowBytes bytes" }
        require(dst.capacity() >= rowBytes * height) { "dst holds ${dst.capacity()} bytes, needs ${rowBytes * height}" }
        val row = src.duplicate()
        dst.clear()
        for (y in 0 until height) {
            val start = y * rowStride
            row.clear()
            row.position(start)
            row.limit(start + rowBytes)
            dst.put(row)
        }
        dst.flip()
    }
}
