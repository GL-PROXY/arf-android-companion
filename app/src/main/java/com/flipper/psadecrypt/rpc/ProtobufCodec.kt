package com.flipper.psadecrypt.rpc

import java.io.ByteArrayOutputStream

/**
 * Minimal hand-written protobuf encoder/decoder for Flipper RPC protocol.
 *
 * Only encodes/decodes the subset of messages needed for storage operations.
 * Wire format reference: https://protobuf.dev/programming-guides/encoding/
 *
 * Flipper protobuf field numbers (from assets/protobuf/flipper.proto):
 *   1: command_id (uint32)
 *   2: command_status (enum/uint32)
 *   3: has_next (bool)
 *
 * Storage oneof content fields in Main:
 *    7: storage_list_request
 *    8: storage_list_response
 *    9: storage_read_request
 *   10: storage_read_response
 *   11: storage_write_request
 *   12: storage_delete_request
 *   13: storage_mkdir_request
 *   14: storage_md5sum_request
 *   15: storage_md5sum_response
 *   24: storage_stat_request
 *   25: storage_stat_response
 *   28: storage_info_request
 *   29: storage_info_response
 *   30: storage_rename_request
 */
object ProtobufCodec {

    // --- Varint encoding/decoding ---

    fun encodeVarint(value: Int): ByteArray {
        val out = ByteArrayOutputStream(5)
        var v = value
        while (v and 0x7F.inv() != 0) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v and 0x7F)
        return out.toByteArray()
    }

    fun encodeVarintLong(value: Long): ByteArray {
        val out = ByteArrayOutputStream(10)
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write((v and 0x7F).toInt())
        return out.toByteArray()
    }

    // --- Low-level protobuf field writing ---

    /** Write a varint field (wire type 0) */
    fun writeVarintField(out: ByteArrayOutputStream, fieldNumber: Int, value: Int) {
        if (value == 0) return // default value, omit
        val tag = (fieldNumber shl 3) or 0 // wire type 0
        out.write(encodeVarint(tag))
        out.write(encodeVarint(value))
    }

    /** Write a bool field (wire type 0, varint 0/1) */
    fun writeBoolField(out: ByteArrayOutputStream, fieldNumber: Int, value: Boolean) {
        if (!value) return // default is false, omit
        val tag = (fieldNumber shl 3) or 0
        out.write(encodeVarint(tag))
        out.write(1)
    }

    /** Write a length-delimited field (wire type 2): string or bytes or embedded message */
    fun writeLenDelimField(out: ByteArrayOutputStream, fieldNumber: Int, data: ByteArray) {
        if (data.isEmpty()) return
        val tag = (fieldNumber shl 3) or 2 // wire type 2
        out.write(encodeVarint(tag))
        out.write(encodeVarint(data.size))
        out.write(data)
    }

    /** Write a string field */
    fun writeStringField(out: ByteArrayOutputStream, fieldNumber: Int, value: String) {
        if (value.isEmpty()) return
        writeLenDelimField(out, fieldNumber, value.toByteArray(Charsets.UTF_8))
    }

    /** Write a uint64 field */
    fun writeUint64Field(out: ByteArrayOutputStream, fieldNumber: Int, value: Long) {
        if (value == 0L) return
        val tag = (fieldNumber shl 3) or 0
        out.write(encodeVarint(tag))
        out.write(encodeVarintLong(value))
    }

    // --- Message builders ---

    /**
     * Build a complete varint-delimited Main message for sending to Flipper.
     * @param commandId unique request ID
     * @param hasNext whether more messages follow (for streaming writes)
     * @param contentFieldNumber the oneof field number (e.g., 7 for storage_list_request)
     * @param contentBytes the serialized inner message
     */
    fun buildMainMessage(
        commandId: Int,
        hasNext: Boolean = false,
        contentFieldNumber: Int,
        contentBytes: ByteArray
    ): ByteArray {
        val mainBody = ByteArrayOutputStream(contentBytes.size + 20)
        writeVarintField(mainBody, 1, commandId)     // command_id
        writeBoolField(mainBody, 3, hasNext)          // has_next
        writeLenDelimField(mainBody, contentFieldNumber, contentBytes) // oneof content
        val body = mainBody.toByteArray()

        // Wrap with varint length prefix
        val out = ByteArrayOutputStream(body.size + 5)
        out.write(encodeVarint(body.size))
        out.write(body)
        return out.toByteArray()
    }

    // --- Storage request builders ---

    /**
     * Storage ListRequest:
     *   field 1: path (string)
     *   field 2: include_md5 (bool)
     *   field 3: filter_max_size (uint32)
     */
    fun buildListRequest(commandId: Int, path: String): ByteArray {
        val inner = ByteArrayOutputStream(path.length + 20)
        writeStringField(inner, 1, path)
        // include_md5 = false (default, omitted)
        writeVarintField(inner, 3, 10 * 1024 * 1024) // filter_max_size = 10MiB
        return buildMainMessage(commandId, contentFieldNumber = 7, contentBytes = inner.toByteArray())
    }

    /**
     * Storage ReadRequest:
     *   field 1: path (string)
     */
    fun buildReadRequest(commandId: Int, path: String): ByteArray {
        val inner = ByteArrayOutputStream(path.length + 10)
        writeStringField(inner, 1, path)
        return buildMainMessage(commandId, contentFieldNumber = 9, contentBytes = inner.toByteArray())
    }

    /**
     * Storage WriteRequest:
     *   field 1: path (string)
     *   field 2: file (embedded File message)
     *
     * File message:
     *   field 1: type (enum: FILE=0, DIR=1)
     *   field 2: name (string)
     *   field 3: size (uint32)
     *   field 4: data (bytes)
     */
    fun buildWriteRequest(commandId: Int, path: String, data: ByteArray, hasNext: Boolean): ByteArray {
        // Build inner File message (just the data field)
        val fileMsg = ByteArrayOutputStream(data.size + 10)
        writeLenDelimField(fileMsg, 4, data) // data field
        val fileBytes = fileMsg.toByteArray()

        // Build WriteRequest
        val inner = ByteArrayOutputStream(path.length + fileBytes.size + 20)
        writeStringField(inner, 1, path)
        writeLenDelimField(inner, 2, fileBytes) // file field

        return buildMainMessage(commandId, hasNext = hasNext, contentFieldNumber = 11, contentBytes = inner.toByteArray())
    }

    /**
     * Storage DeleteRequest:
     *   field 1: path (string)
     *   field 2: recursive (bool)
     */
    fun buildDeleteRequest(commandId: Int, path: String, recursive: Boolean): ByteArray {
        val inner = ByteArrayOutputStream(path.length + 10)
        writeStringField(inner, 1, path)
        writeBoolField(inner, 2, recursive)
        return buildMainMessage(commandId, contentFieldNumber = 12, contentBytes = inner.toByteArray())
    }

    /**
     * Storage MkdirRequest:
     *   field 1: path (string)
     */
    fun buildMkdirRequest(commandId: Int, path: String): ByteArray {
        val inner = ByteArrayOutputStream(path.length + 10)
        writeStringField(inner, 1, path)
        return buildMainMessage(commandId, contentFieldNumber = 13, contentBytes = inner.toByteArray())
    }

    /**
     * Storage StatRequest:
     *   field 1: path (string)
     */
    fun buildStatRequest(commandId: Int, path: String): ByteArray {
        val inner = ByteArrayOutputStream(path.length + 10)
        writeStringField(inner, 1, path)
        return buildMainMessage(commandId, contentFieldNumber = 24, contentBytes = inner.toByteArray())
    }

    // --- Response parsing ---

    /**
     * Parsed Main response from Flipper.
     */
    data class MainResponse(
        val commandId: Int = 0,
        val commandStatus: Int = 0, // 0 = OK
        val hasNext: Boolean = false,
        val contentFieldNumber: Int = 0,
        val contentBytes: ByteArray = ByteArray(0)
    ) {
        val isOk: Boolean get() = commandStatus == 0
        val statusName: String get() = when (commandStatus) {
            0 -> "OK"
            1 -> "ERROR"
            2 -> "ERROR_DECODE"
            3 -> "ERROR_NOT_IMPLEMENTED"
            4 -> "ERROR_BUSY"
            5 -> "ERROR_STORAGE_NOT_READY"
            6 -> "ERROR_STORAGE_EXIST"
            7 -> "ERROR_STORAGE_NOT_EXIST"
            8 -> "ERROR_STORAGE_INVALID_PARAMETER"
            9 -> "ERROR_STORAGE_DENIED"
            10 -> "ERROR_STORAGE_INVALID_NAME"
            11 -> "ERROR_STORAGE_INTERNAL"
            12 -> "ERROR_STORAGE_NOT_IMPLEMENTED"
            13 -> "ERROR_STORAGE_ALREADY_OPEN"
            14 -> "ERROR_CONTINUOUS_COMMAND_INTERRUPTED"
            15 -> "ERROR_INVALID_PARAMETERS"
            16 -> "ERROR_APP_CANT_START"
            17 -> "ERROR_APP_SYSTEM_LOCKED"
            18 -> "ERROR_STORAGE_DIR_NOT_EMPTY"
            19 -> "ERROR_VIRTUAL_DISPLAY_ALREADY_STARTED"
            20 -> "ERROR_VIRTUAL_DISPLAY_NOT_STARTED"
            21 -> "ERROR_APP_NOT_RUNNING"
            22 -> "ERROR_APP_CMD_ERROR"
            else -> "UNKNOWN($commandStatus)"
        }
    }

    /**
     * Parse a raw protobuf Main message (without length prefix — that's already stripped by ByteAccumulator).
     */
    fun parseMainResponse(data: ByteArray): MainResponse {
        var commandId = 0
        var commandStatus = 0
        var hasNext = false
        var contentFieldNumber = 0
        var contentBytes = ByteArray(0)

        var pos = 0
        while (pos < data.size) {
            val tagResult = readVarint(data, pos)
            val tag = tagResult.first
            pos = tagResult.second

            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07

            when (wireType) {
                0 -> { // varint
                    val valueResult = readVarint(data, pos)
                    pos = valueResult.second
                    when (fieldNumber) {
                        1 -> commandId = valueResult.first
                        2 -> commandStatus = valueResult.first
                        3 -> hasNext = valueResult.first != 0
                    }
                }
                2 -> { // length-delimited
                    val lenResult = readVarint(data, pos)
                    val len = lenResult.first
                    pos = lenResult.second
                    val fieldData = data.copyOfRange(pos, (pos + len).coerceAtMost(data.size))
                    pos += len

                    // Oneof content fields (storage responses: 8,10,15,25,29; and others)
                    if (fieldNumber >= 4) {
                        contentFieldNumber = fieldNumber
                        contentBytes = fieldData
                    }
                }
                1 -> pos += 8 // 64-bit, skip
                5 -> pos += 4 // 32-bit, skip
                else -> break // unknown wire type, stop
            }
        }

        return MainResponse(commandId, commandStatus, hasNext, contentFieldNumber, contentBytes)
    }

    /**
     * Parsed storage File entry from a ListResponse.
     */
    data class FileEntry(
        val type: Int = 0, // 0 = FILE, 1 = DIR
        val name: String = "",
        val size: Long = 0
    ) {
        val isDirectory: Boolean get() = type == 1
    }

    /**
     * Parse a ListResponse message body.
     * ListResponse:
     *   field 1: file (repeated embedded File message)
     */
    fun parseListResponse(data: ByteArray): List<FileEntry> {
        val files = mutableListOf<FileEntry>()
        var pos = 0
        while (pos < data.size) {
            val tagResult = readVarint(data, pos)
            val tag = tagResult.first
            pos = tagResult.second
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07

            if (wireType == 2 && fieldNumber == 1) {
                val lenResult = readVarint(data, pos)
                pos = lenResult.second
                val fileData = data.copyOfRange(pos, (pos + lenResult.first).coerceAtMost(data.size))
                pos += lenResult.first
                files.add(parseFileEntry(fileData))
            } else {
                pos = skipField(data, pos, wireType)
            }
        }
        return files
    }

    private fun parseFileEntry(data: ByteArray): FileEntry {
        var type = 0
        var name = ""
        var size = 0L
        var pos = 0
        while (pos < data.size) {
            val tagResult = readVarint(data, pos)
            val tag = tagResult.first
            pos = tagResult.second
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07

            when {
                wireType == 0 && fieldNumber == 1 -> { // type
                    val r = readVarint(data, pos); type = r.first; pos = r.second
                }
                wireType == 2 && fieldNumber == 2 -> { // name
                    val r = readVarint(data, pos); pos = r.second
                    name = String(data, pos, r.first, Charsets.UTF_8); pos += r.first
                }
                wireType == 0 && fieldNumber == 3 -> { // size
                    val r = readVarintLong(data, pos); size = r.first; pos = r.second
                }
                else -> pos = skipField(data, pos, wireType)
            }
        }
        return FileEntry(type, name, size)
    }

    /**
     * Parse a ReadResponse message body.
     * ReadResponse:
     *   field 1: file (embedded File message with data in field 4)
     */
    fun parseReadResponse(data: ByteArray): ByteArray {
        var pos = 0
        while (pos < data.size) {
            val tagResult = readVarint(data, pos)
            val tag = tagResult.first
            pos = tagResult.second
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07

            if (wireType == 2 && fieldNumber == 1) {
                val lenResult = readVarint(data, pos)
                pos = lenResult.second
                val fileData = data.copyOfRange(pos, (pos + lenResult.first).coerceAtMost(data.size))
                return extractFileData(fileData)
            } else {
                pos = skipField(data, pos, wireType)
            }
        }
        return ByteArray(0)
    }

    /** Extract data_ (field 4) from a File message */
    private fun extractFileData(data: ByteArray): ByteArray {
        var pos = 0
        while (pos < data.size) {
            val tagResult = readVarint(data, pos)
            val tag = tagResult.first
            pos = tagResult.second
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07

            if (wireType == 2 && fieldNumber == 4) {
                val lenResult = readVarint(data, pos)
                pos = lenResult.second
                return data.copyOfRange(pos, (pos + lenResult.first).coerceAtMost(data.size))
            } else {
                pos = skipField(data, pos, wireType)
            }
        }
        return ByteArray(0)
    }

    /**
     * Parse a StatResponse message body.
     * StatResponse:
     *   field 1: file (embedded File message)
     */
    fun parseStatResponse(data: ByteArray): FileEntry {
        var pos = 0
        while (pos < data.size) {
            val tagResult = readVarint(data, pos)
            val tag = tagResult.first
            pos = tagResult.second
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07

            if (wireType == 2 && fieldNumber == 1) {
                val lenResult = readVarint(data, pos)
                pos = lenResult.second
                val fileData = data.copyOfRange(pos, (pos + lenResult.first).coerceAtMost(data.size))
                return parseFileEntry(fileData)
            } else {
                pos = skipField(data, pos, wireType)
            }
        }
        return FileEntry()
    }

    // --- Internal helpers ---

    private fun readVarint(data: ByteArray, startPos: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var pos = startPos
        while (pos < data.size) {
            val b = data[pos].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            pos++
            if (b and 0x80 == 0) return result to pos
            shift += 7
            if (shift > 35) break
        }
        return result to pos
    }

    private fun readVarintLong(data: ByteArray, startPos: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var pos = startPos
        while (pos < data.size) {
            val b = data[pos].toLong() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            pos++
            if (b and 0x80 == 0L) return result to pos
            shift += 7
            if (shift > 63) break
        }
        return result to pos
    }

    private fun skipField(data: ByteArray, pos: Int, wireType: Int): Int {
        return when (wireType) {
            0 -> { // varint
                var p = pos
                while (p < data.size && data[p].toInt() and 0x80 != 0) p++
                p + 1 // skip final byte
            }
            1 -> pos + 8 // 64-bit
            2 -> { // length-delimited
                val r = readVarint(data, pos)
                r.second + r.first
            }
            5 -> pos + 4 // 32-bit
            else -> data.size // unknown, skip to end
        }
    }
}
