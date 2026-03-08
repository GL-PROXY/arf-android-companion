package com.flipper.psadecrypt.rpc

import java.io.ByteArrayOutputStream

/**
 * Thread-safe byte accumulator for reassembling BLE notification chunks
 * into complete varint-delimited protobuf messages.
 *
 * BLE notifications can split a protobuf message across multiple packets,
 * or pack multiple messages into one packet. This class handles both cases.
 */
class ByteAccumulator {
    private val buffer = ByteArrayOutputStream(4096)
    private val lock = Any()

    /** Append received BLE chunk to the buffer */
    fun append(data: ByteArray) = synchronized(lock) {
        buffer.write(data)
    }

    /**
     * Try to read one complete varint-delimited message from the buffer.
     * Returns the raw protobuf bytes (without the varint prefix), or null
     * if not enough data is available yet.
     */
    fun tryReadMessage(): ByteArray? = synchronized(lock) {
        val bytes = buffer.toByteArray()
        if (bytes.isEmpty()) return null

        // Try to read varint32 length prefix
        var varintLen = 0
        var shift = 0
        var pos = 0
        while (pos < bytes.size) {
            val b = bytes[pos].toInt() and 0xFF
            varintLen = varintLen or ((b and 0x7F) shl shift)
            pos++
            if (b and 0x80 == 0) {
                // Varint complete — check if we have enough data for the message
                if (pos + varintLen <= bytes.size) {
                    val message = bytes.copyOfRange(pos, pos + varintLen)
                    // Remove consumed bytes from buffer
                    buffer.reset()
                    if (pos + varintLen < bytes.size) {
                        buffer.write(bytes, pos + varintLen, bytes.size - (pos + varintLen))
                    }
                    return message
                }
                // Not enough data yet — message is incomplete
                return null
            }
            shift += 7
            if (shift > 35) {
                // Invalid varint (too many bytes) — discard buffer to recover
                buffer.reset()
                return null
            }
        }
        // Varint itself is incomplete — need more data
        return null
    }

    /** Discard all buffered data */
    fun clear() = synchronized(lock) {
        buffer.reset()
    }

    /** Current buffered byte count */
    val size: Int get() = synchronized(lock) { buffer.size() }
}
