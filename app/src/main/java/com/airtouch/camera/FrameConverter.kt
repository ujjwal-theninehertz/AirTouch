package com.airtouch.camera

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/**
 * Copies RGBA camera frames into one reused Bitmap. In steady state it allocates nothing per frame;
 * [allocations] counts the buffers it had to create, which only grows when the resolution changes.
 */
internal class FrameConverter {

    private var frame: Frame? = null
    private var packed: ByteBuffer? = null
    private var lastTimestampMs = Long.MIN_VALUE

    var allocations = 0
        private set

    fun convert(image: ImageProxy, isMirrored: Boolean): Frame {
        val width = image.width
        val height = image.height
        val target = frame?.takeIf { it.bitmap.width == width && it.bitmap.height == height }
            ?: Frame(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)).also {
                frame = it
                allocations++
            }

        val plane = image.planes[0]
        val pixels = plane.buffer
        pixels.rewind()
        if (plane.pixelStride == RgbaRows.BYTES_PER_PIXEL && plane.rowStride == width * RgbaRows.BYTES_PER_PIXEL) {
            target.bitmap.copyPixelsFromBuffer(pixels)
        } else {
            val size = width * height * RgbaRows.BYTES_PER_PIXEL
            val dst = packed?.takeIf { it.capacity() == size }
                ?: ByteBuffer.allocateDirect(size).also {
                    packed = it
                    allocations++
                }
            RgbaRows.pack(pixels, plane.rowStride, width, height, dst)
            target.bitmap.copyPixelsFromBuffer(dst)
        }

        // MediaPipe's live-stream mode rejects timestamps that do not increase.
        val timestampMs = maxOf(image.imageInfo.timestamp / NANOS_PER_MILLI, lastTimestampMs + 1)
        lastTimestampMs = timestampMs
        target.timestampMs = timestampMs
        target.rotationDegrees = image.imageInfo.rotationDegrees
        target.isMirrored = isMirrored
        return target
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
