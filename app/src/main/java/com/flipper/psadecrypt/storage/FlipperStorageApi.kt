package com.flipper.psadecrypt.storage

import com.flipper.psadecrypt.rpc.FlipperRpcClient
import com.flipper.psadecrypt.rpc.ProtobufCodec
import java.io.File
import java.io.FileOutputStream

/**
 * High-level storage API for Flipper Zero filesystem operations.
 * Wraps [FlipperRpcClient] and [ProtobufCodec] to provide simple suspend functions.
 */
class FlipperStorageApi(private val rpc: FlipperRpcClient) {

    /**
     * List files and directories at the given path on Flipper.
     */
    suspend fun list(path: String): Result<List<FlipperFile>> {
        val cmdId = rpc.allocateCommandId()
        val request = ProtobufCodec.buildListRequest(cmdId, path)
        return rpc.request(request, cmdId).map { responses ->
            responses.flatMap { response ->
                if (response.contentFieldNumber == 8) { // storage_list_response
                    ProtobufCodec.parseListResponse(response.contentBytes).map { entry ->
                        FlipperFile(
                            name = entry.name,
                            isDirectory = entry.isDirectory,
                            size = entry.size
                        )
                    }
                } else emptyList()
            }
        }
    }

    /**
     * Download a file from Flipper to a local file on Android.
     * @param flipperPath full path on Flipper (e.g., "/ext/myfile.txt")
     * @param localFile destination file on Android
     * @param progress optional callback (bytesDownloaded, totalSize). totalSize may be -1 if unknown.
     */
    suspend fun download(
        flipperPath: String,
        localFile: File,
        progress: ((Long, Long) -> Unit)? = null
    ): Result<Unit> {
        // First stat to get total size
        val totalSize = stat(flipperPath).getOrNull()?.size ?: -1L

        val cmdId = rpc.allocateCommandId()
        val request = ProtobufCodec.buildReadRequest(cmdId, flipperPath)

        return rpc.request(request, cmdId).map { responses ->
            FileOutputStream(localFile).use { fos ->
                var downloaded = 0L
                for (response in responses) {
                    if (response.contentFieldNumber == 10) { // storage_read_response
                        val chunk = ProtobufCodec.parseReadResponse(response.contentBytes)
                        if (chunk.isNotEmpty()) {
                            fos.write(chunk)
                            downloaded += chunk.size
                            progress?.invoke(downloaded, totalSize)
                        }
                    }
                }
            }
        }
    }

    /**
     * Upload a local file from Android to Flipper.
     * @param localFile source file on Android
     * @param flipperPath destination path on Flipper (e.g., "/ext/myfile.txt")
     * @param progress optional callback (bytesUploaded, totalSize)
     */
    suspend fun upload(
        localFile: File,
        flipperPath: String,
        progress: ((Long, Long) -> Unit)? = null
    ): Result<Unit> {
        val fileData = localFile.readBytes()
        val totalSize = fileData.size.toLong()
        val cmdId = rpc.allocateCommandId()

        // Track progress through chunk sending
        progress?.invoke(0L, totalSize)

        return rpc.sendWriteStream(cmdId, flipperPath, fileData).map {
            progress?.invoke(totalSize, totalSize)
        }
    }

    /**
     * Delete a file or directory on Flipper.
     */
    suspend fun delete(path: String, recursive: Boolean = true): Result<Unit> {
        val cmdId = rpc.allocateCommandId()
        val request = ProtobufCodec.buildDeleteRequest(cmdId, path, recursive)
        return rpc.requestOnce(request, cmdId).map { }
    }

    /**
     * Create a directory on Flipper.
     */
    suspend fun mkdir(path: String): Result<Unit> {
        val cmdId = rpc.allocateCommandId()
        val request = ProtobufCodec.buildMkdirRequest(cmdId, path)
        return rpc.requestOnce(request, cmdId).map { }
    }

    /**
     * Stat a file or directory on Flipper.
     */
    suspend fun stat(path: String): Result<FlipperFile> {
        val cmdId = rpc.allocateCommandId()
        val request = ProtobufCodec.buildStatRequest(cmdId, path)
        return rpc.requestOnce(request, cmdId).map { response ->
            if (response.contentFieldNumber == 25) { // storage_stat_response
                val entry = ProtobufCodec.parseStatResponse(response.contentBytes)
                FlipperFile(entry.name, entry.isDirectory, entry.size)
            } else {
                FlipperFile("", false, 0)
            }
        }
    }
}
