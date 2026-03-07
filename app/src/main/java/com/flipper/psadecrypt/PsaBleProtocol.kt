package com.flipper.psadecrypt

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary protocol for PSA BF offload over BLE serial.
 *
 * Message format: [MSG_TYPE:1][PAYLOAD]
 *
 * BF_REQUEST (Flipper → Android):
 *   [0x01][bf_type:1][w0:4][w1:4] = 10 bytes
 *
 * BF_PROGRESS (Android → Flipper):
 *   [0x02][keys_tested:4][keys_per_sec:4] = 9 bytes
 *
 * BF_RESULT (Android → Flipper):
 *   [0x03][success:1][counter:4][dec_v0:4][dec_v1:4][elapsed_ms:4] = 18 bytes
 *
 * BF_CANCEL (Flipper → Android):
 *   [0x04] = 1 byte
 */
object PsaBleProtocol {
    const val MSG_BF_REQUEST: Byte = 0x01
    const val MSG_BF_PROGRESS: Byte = 0x02
    const val MSG_BF_RESULT: Byte = 0x03
    const val MSG_BF_CANCEL: Byte = 0x04

    // BF1 and BF2 ranges (constants matching Flipper firmware)
    const val BF1_START = 0x23000000.toInt()
    const val BF1_END = 0x24000000.toInt()
    const val BF2_START = 0xF3000000.toInt()
    const val BF2_END = 0xF4000000.toInt()

    data class BfRequest(val bfType: Int, val w0: Int, val w1: Int)

    fun parseBfRequest(data: ByteArray): BfRequest? {
        if (data.size < 10 || data[0] != MSG_BF_REQUEST) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buf.get() // skip type
        val bfType = buf.get().toInt() and 0xFF
        val w0 = buf.getInt()
        val w1 = buf.getInt()
        return BfRequest(bfType, w0, w1)
    }

    fun encodeProgress(keysTested: Int, keysPerSec: Int): ByteArray {
        val buf = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MSG_BF_PROGRESS)
        buf.putInt(keysTested)
        buf.putInt(keysPerSec)
        return buf.array()
    }

    fun encodeResult(result: BfResult): ByteArray {
        val buf = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MSG_BF_RESULT)
        buf.put(if (result.found) 1.toByte() else 0.toByte())
        buf.putInt(result.counter)
        buf.putInt(result.decV0)
        buf.putInt(result.decV1)
        buf.putInt(result.elapsedMs.toInt())
        return buf.array()
    }

    fun isCancelMessage(data: ByteArray): Boolean {
        return data.isNotEmpty() && data[0] == MSG_BF_CANCEL
    }
}
