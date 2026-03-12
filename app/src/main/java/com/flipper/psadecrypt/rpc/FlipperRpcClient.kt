package com.flipper.psadecrypt.rpc

import android.util.Log
import com.flipper.psadecrypt.FlipperBleClient
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Protobuf RPC client for Flipper Zero.
 *
 * Sits on top of [FlipperBleClient] and provides request/response dispatch
 * with command ID matching, streaming response support, and overflow throttling.
 */
class FlipperRpcClient(
    private val bleClient: FlipperBleClient,
    private val scope: CoroutineScope
) : FlipperBleClient.RpcListener {

    companion object {
        private const val TAG = "FlipperRPC"
        private const val OVERFLOW_POLL_MS = 50L
    }

    private val commandIdCounter = AtomicInteger(1)
    private val accumulator = ByteAccumulator()

    /**
     * Listener for unsolicited screen frame notifications (field 22, commandId=0).
     * Called on the parse thread — implementations should not block.
     */
    var screenFrameListener: ((ProtobufCodec.ScreenFrameData) -> Unit)? = null

    /**
     * Pending response handlers: commandId -> channel that receives response chunks.
     * For streaming responses (has_next=true), multiple items are sent.
     * The channel is closed when has_next=false or on error.
     */
    private val pendingRequests = ConcurrentHashMap<Int, Channel<ProtobufCodec.MainResponse>>()

    /** Job that continuously parses accumulated BLE data into protobuf messages */
    private var parseJob: Job? = null

    fun start() {
        bleClient.rpcListener = this
        parseJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val msg = accumulator.tryReadMessage()
                if (msg != null) {
                    try {
                        val response = ProtobufCodec.parseMainResponse(msg)
                        dispatchResponse(response)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse protobuf response", e)
                    }
                } else {
                    delay(5) // small yield when no complete message available
                }
            }
        }
        Log.d(TAG, "RPC client started")
    }

    fun stop() {
        parseJob?.cancel()
        parseJob = null
        bleClient.rpcListener = null
        accumulator.clear()
        // Close all pending request channels
        for ((id, channel) in pendingRequests) {
            channel.close(CancellationException("RPC client stopped"))
        }
        pendingRequests.clear()
        Log.d(TAG, "RPC client stopped")
    }

    // --- FlipperBleClient.RpcListener ---

    override fun onRpcDataReceived(data: ByteArray) {
        accumulator.append(data)
    }

    override fun onOverflowUpdate(remainingBuffer: Int) {
        // Handled inline during sendWithOverflowControl
    }

    // --- Request/Response ---

    private fun nextCommandId(): Int {
        var id: Int
        do {
            id = commandIdCounter.getAndUpdate { if (it == Int.MAX_VALUE) 1 else it + 1 }
        } while (pendingRequests.containsKey(id))
        return id
    }

    private fun dispatchResponse(response: ProtobufCodec.MainResponse) {
        // Screen frame notifications arrive with commandId=0 and content field 22
        if (response.contentFieldNumber == 22) {
            try {
                val frame = ProtobufCodec.parseScreenFrame(response.contentBytes)
                screenFrameListener?.invoke(frame)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse screen frame", e)
            }
            return
        }

        val channel = pendingRequests[response.commandId]
        if (channel == null) {
            if (response.commandId != 0) {
                Log.w(TAG, "Response for unknown command_id=${response.commandId}")
            }
            return
        }
        channel.trySend(response)
        if (!response.hasNext) {
            pendingRequests.remove(response.commandId)
            channel.close()
        }
    }

    /**
     * Send a pre-built varint-delimited message, respecting overflow control.
     * Waits until the Flipper's buffer has enough room, then sends and locally
     * decrements the buffer (fe63 notifications set the absolute value).
     */
    private suspend fun sendWithOverflowControl(data: ByteArray) {
        // Wait until buffer has room for this message
        var waited = 0
        while (bleClient.rpcBufferRemaining < data.size && waited < 30_000) {
            delay(OVERFLOW_POLL_MS)
            waited += OVERFLOW_POLL_MS.toInt()
        }
        if (waited >= 30_000) {
            Log.w(TAG, "Overflow wait timeout, sending anyway (buffer=${bleClient.rpcBufferRemaining}, need=${data.size})")
        }
        bleClient.sendRpc(data)
        // Locally track buffer consumption; fe63 notifications correct the absolute value
        bleClient.consumeBuffer(data.size)
    }

    /**
     * Send a single request and collect all response chunks (handles streaming via has_next).
     * Returns the list of all response messages for this command ID.
     */
    suspend fun request(data: ByteArray, commandId: Int): Result<List<ProtobufCodec.MainResponse>> {
        val channel = Channel<ProtobufCodec.MainResponse>(Channel.UNLIMITED)
        pendingRequests[commandId] = channel

        return try {
            sendWithOverflowControl(data)

            val responses = mutableListOf<ProtobufCodec.MainResponse>()
            for (response in channel) {
                if (!response.isOk) {
                    pendingRequests.remove(commandId)
                    return Result.failure(FlipperRpcException(response.commandStatus, response.statusName))
                }
                responses.add(response)
            }
            Result.success(responses)
        } catch (e: CancellationException) {
            pendingRequests.remove(commandId)
            throw e
        } catch (e: Exception) {
            pendingRequests.remove(commandId)
            Result.failure(e)
        }
    }

    /**
     * Send a single request and wait for a single response (non-streaming).
     */
    suspend fun requestOnce(data: ByteArray, commandId: Int): Result<ProtobufCodec.MainResponse> {
        return request(data, commandId).map { it.first() }
    }

    /**
     * Send a stream of write chunks for file upload.
     * All chunks share the same commandId; only the last has has_next=false.
     * Response comes after the final chunk.
     */
    suspend fun sendWriteStream(
        commandId: Int,
        path: String,
        fileData: ByteArray,
        chunkSize: Int = 512
    ): Result<ProtobufCodec.MainResponse> {
        val channel = Channel<ProtobufCodec.MainResponse>(Channel.UNLIMITED)
        pendingRequests[commandId] = channel

        return try {
            var offset = 0
            while (offset < fileData.size) {
                val end = (offset + chunkSize).coerceAtMost(fileData.size)
                val chunk = fileData.copyOfRange(offset, end)
                val hasNext = end < fileData.size
                val msg = ProtobufCodec.buildWriteRequest(commandId, path, chunk, hasNext)
                sendWithOverflowControl(msg)
                offset = end
            }

            // If file is empty, send one empty write with has_next=false
            if (fileData.isEmpty()) {
                val msg = ProtobufCodec.buildWriteRequest(commandId, path, ByteArray(0), false)
                sendWithOverflowControl(msg)
            }

            // Wait for the final response
            val response = channel.receive()
            pendingRequests.remove(commandId)
            channel.close()

            if (!response.isOk) {
                Result.failure(FlipperRpcException(response.commandStatus, response.statusName))
            } else {
                Result.success(response)
            }
        } catch (e: CancellationException) {
            pendingRequests.remove(commandId)
            throw e
        } catch (e: Exception) {
            pendingRequests.remove(commandId)
            Result.failure(e)
        }
    }

    /**
     * Send a request without waiting for a response (fire-and-forget).
     * Used for input events where we don't need to track the response.
     */
    suspend fun sendFireAndForget(data: ByteArray) {
        sendWithOverflowControl(data)
    }

    fun allocateCommandId(): Int = nextCommandId()
}

class FlipperRpcException(val statusCode: Int, val statusName: String) :
    Exception("Flipper RPC error: $statusName (code=$statusCode)")
