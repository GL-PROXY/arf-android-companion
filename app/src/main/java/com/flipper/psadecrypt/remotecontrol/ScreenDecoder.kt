package com.flipper.psadecrypt.remotecontrol

import android.graphics.Bitmap
import android.graphics.Color
import com.flipper.psadecrypt.rpc.ProtobufCodec

/**
 * Decodes Flipper screen frame data (128x64, 1bpp vertical byte packing) into an Android Bitmap.
 *
 * Pixel layout: each byte covers 8 vertical pixels.
 * Byte index for pixel (x, y) = (y / 8) * 128 + x
 * Bit position within byte = y % 8
 * Bit set = pixel ON (black on Flipper's orange LCD).
 */
class ScreenDecoder {

    companion object {
        const val SCREEN_WIDTH = 128
        const val SCREEN_HEIGHT = 64
        private const val EXPECTED_DATA_SIZE = SCREEN_WIDTH * SCREEN_HEIGHT / 8 // 1024 bytes

        // Flipper LCD colors
        private const val COLOR_PIXEL_ON = Color.BLACK
        private const val COLOR_PIXEL_OFF = 0xFFFF8C29.toInt() // orange
    }

    // Reuse bitmap to avoid GC pressure at ~30fps
    private val bitmap = Bitmap.createBitmap(SCREEN_WIDTH, SCREEN_HEIGHT, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(SCREEN_WIDTH * SCREEN_HEIGHT)

    /**
     * Decode a screen frame into a Bitmap.
     * Returns null if the data is invalid (wrong size).
     */
    fun decode(frame: ProtobufCodec.ScreenFrameData): Bitmap? {
        val data = frame.data
        if (data.size < EXPECTED_DATA_SIZE) return null

        when (frame.orientation) {
            0 -> decodeHorizontal(data, false)
            1 -> decodeHorizontal(data, true)
            2 -> decodeVertical(data, false)
            3 -> decodeVertical(data, true)
            else -> decodeHorizontal(data, false)
        }

        bitmap.setPixels(pixels, 0, SCREEN_WIDTH, 0, 0, SCREEN_WIDTH, SCREEN_HEIGHT)
        return bitmap
    }

    private fun decodeHorizontal(data: ByteArray, flip: Boolean) {
        for (y in 0 until SCREEN_HEIGHT) {
            for (x in 0 until SCREEN_WIDTH) {
                val srcX = if (flip) SCREEN_WIDTH - 1 - x else x
                val srcY = if (flip) SCREEN_HEIGHT - 1 - y else y
                val byteIndex = (srcY / 8) * SCREEN_WIDTH + srcX
                val bitIndex = srcY % 8
                val isSet = (data[byteIndex].toInt() ushr bitIndex) and 1 == 1
                pixels[y * SCREEN_WIDTH + x] = if (isSet) COLOR_PIXEL_ON else COLOR_PIXEL_OFF
            }
        }
    }

    private fun decodeVertical(data: ByteArray, flip: Boolean) {
        for (y in 0 until SCREEN_HEIGHT) {
            for (x in 0 until SCREEN_WIDTH) {
                val srcX: Int
                val srcY: Int
                if (flip) {
                    srcX = y
                    srcY = SCREEN_WIDTH - 1 - x
                } else {
                    srcX = SCREEN_HEIGHT - 1 - y
                    srcY = x
                }
                if (srcX < 0 || srcX >= SCREEN_WIDTH || srcY < 0 || srcY >= SCREEN_HEIGHT) {
                    pixels[y * SCREEN_WIDTH + x] = COLOR_PIXEL_OFF
                    continue
                }
                val byteIndex = (srcY / 8) * SCREEN_WIDTH + srcX
                if (byteIndex >= data.size) {
                    pixels[y * SCREEN_WIDTH + x] = COLOR_PIXEL_OFF
                    continue
                }
                val bitIndex = srcY % 8
                val isSet = (data[byteIndex].toInt() ushr bitIndex) and 1 == 1
                pixels[y * SCREEN_WIDTH + x] = if (isSet) COLOR_PIXEL_ON else COLOR_PIXEL_OFF
            }
        }
    }
}
