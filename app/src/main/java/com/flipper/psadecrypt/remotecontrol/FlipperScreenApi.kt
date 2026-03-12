package com.flipper.psadecrypt.remotecontrol

import com.flipper.psadecrypt.rpc.FlipperRpcClient
import com.flipper.psadecrypt.rpc.ProtobufCodec

/**
 * High-level API for Flipper screen streaming and input.
 *
 * Screen frames arrive as unsolicited notifications (field 22) and are
 * dispatched via [FlipperRpcClient.screenFrameListener].
 * This class handles start/stop stream and sending button inputs.
 */
class FlipperScreenApi(private val rpc: FlipperRpcClient) {

    companion object {
        // InputKey enum values (from gui.proto)
        const val KEY_UP = 0
        const val KEY_DOWN = 1
        const val KEY_RIGHT = 2
        const val KEY_LEFT = 3
        const val KEY_OK = 4
        const val KEY_BACK = 5

        // InputType enum values (from gui.proto)
        const val TYPE_PRESS = 0
        const val TYPE_RELEASE = 1
        const val TYPE_SHORT = 2
        const val TYPE_LONG = 3
        const val TYPE_REPEAT = 4
    }

    /**
     * Start screen streaming. After this call, screen frames will arrive
     * via [FlipperRpcClient.screenFrameListener].
     */
    suspend fun startStream(): Result<Unit> {
        val cmdId = rpc.allocateCommandId()
        val request = ProtobufCodec.buildStartScreenStreamRequest(cmdId)
        rpc.sendFireAndForget(request)
        return Result.success(Unit)
    }

    /**
     * Stop screen streaming.
     */
    suspend fun stopStream(): Result<Unit> {
        val cmdId = rpc.allocateCommandId()
        val request = ProtobufCodec.buildStopScreenStreamRequest(cmdId)
        rpc.sendFireAndForget(request)
        return Result.success(Unit)
    }

    /**
     * Send a short button press (PRESS -> SHORT -> RELEASE).
     */
    suspend fun pressButton(key: Int) {
        sendInput(key, TYPE_PRESS)
        sendInput(key, TYPE_SHORT)
        sendInput(key, TYPE_RELEASE)
    }

    /**
     * Send a long button press (PRESS -> LONG -> RELEASE).
     */
    suspend fun longPressButton(key: Int) {
        sendInput(key, TYPE_PRESS)
        sendInput(key, TYPE_LONG)
        sendInput(key, TYPE_RELEASE)
    }

    /**
     * Send a single input event.
     */
    suspend fun sendInput(key: Int, type: Int) {
        val cmdId = rpc.allocateCommandId()
        val request = ProtobufCodec.buildSendInputEventRequest(cmdId, key, type)
        rpc.sendFireAndForget(request)
    }
}
