package com.flipper.psadecrypt

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.*

/**
 * BLE GATT client for Flipper Zero serial service.
 * Uses custom data characteristics (fe65 RX, fe66 TX) for PSA BF offload,
 * separate from the RPC channel (fe62/fe61).
 */
@SuppressLint("MissingPermission")
class FlipperBleClient(private val context: Context) {
    companion object {
        private const val TAG = "FlipperBLE"

        // Flipper serial service UUID (0xFE60)
        // STM32WB stores UUIDs in little-endian byte order — reverse the firmware byte arrays
        val SERIAL_SERVICE_UUID: UUID = UUID.fromString("8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000")

        // Custom data characteristics for PSA offload
        val CUSTOM_DATA_RX_UUID: UUID = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e65fe0000") // fe65 — write to Flipper
        val CUSTOM_DATA_TX_UUID: UUID = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e66fe0000") // fe66 — notifications from Flipper

        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onDataReceived(data: ByteArray)
        fun onLog(msg: String) {}
    }

    var listener: Listener? = null
    private var gatt: BluetoothGatt? = null
    private var customRxChar: BluetoothGattCharacteristic? = null // fe65: we write to this
    private var customTxChar: BluetoothGattCharacteristic? = null // fe66: notifications from Flipper
    private val handler = Handler(Looper.getMainLooper())

    val isConnected: Boolean
        get() = gatt != null && customRxChar != null

    private fun log(msg: String) {
        Log.d(TAG, msg)
        handler.post { listener?.onLog(msg) }
    }

    fun connect(device: BluetoothDevice) {
        log("Connecting to ${device.name} (${device.address})")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun refreshGattCache(gatt: BluetoothGatt) {
        try {
            val method = gatt.javaClass.getMethod("refresh")
            val result = method.invoke(gatt) as Boolean
            log("GATT cache refresh: $result")
        } catch (e: Exception) {
            log("GATT cache refresh failed: ${e.message}")
        }
    }

    fun disconnect() {
        val g = gatt ?: return
        gatt = null
        customRxChar = null
        customTxChar = null
        g.disconnect()
        g.close()
        handler.post { listener?.onDisconnected() }
    }

    /** Send data to Flipper via custom data RX characteristic (fe65) */
    fun send(data: ByteArray): Boolean {
        val char = customRxChar ?: return false
        val g = gatt ?: return false
        char.value = data
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val ok = g.writeCharacteristic(char)
        log("Send ${data.size} bytes → fe65: $ok")
        return ok
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            log("Connection state: status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("Connected, clearing GATT cache before discovery")
                refreshGattCache(gatt)
                // Small delay to let cache clear take effect before discovery
                Thread.sleep(200)
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Disconnected (status=$status)")
                this@FlipperBleClient.gatt = null
                customRxChar = null
                customTxChar = null
                handler.post { listener?.onDisconnected() }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Service discovery failed: $status")
                return
            }

            // Log all discovered services for debugging
            for (svc in gatt.services) {
                log("  Service: ${svc.uuid}")
                for (ch in svc.characteristics) {
                    val descCount = ch.descriptors.size
                    log("    Char: ${ch.uuid} props=0x${Integer.toHexString(ch.properties)} descs=$descCount")
                }
            }

            val service = gatt.getService(SERIAL_SERVICE_UUID)
            if (service == null) {
                log("ERROR: Serial service not found (expected $SERIAL_SERVICE_UUID)")
                disconnect()
                return
            }

            log("Found serial service")

            customRxChar = service.getCharacteristic(CUSTOM_DATA_RX_UUID)
            customTxChar = service.getCharacteristic(CUSTOM_DATA_TX_UUID)

            if (customRxChar == null) {
                log("ERROR: Custom data RX (fe65) not found")
            }
            if (customTxChar == null) {
                log("ERROR: Custom data TX (fe66) not found")
            }

            // Enable notifications on custom TX char (fe66) to receive BF requests from Flipper
            customTxChar?.let { tx ->
                val localOk = gatt.setCharacteristicNotification(tx, true)
                log("setCharacteristicNotification(fe66): $localOk")

                val desc = tx.getDescriptor(CCCD_UUID)
                if (desc != null) {
                    desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    val writeOk = gatt.writeDescriptor(desc)
                    log("writeDescriptor(CCCD): $writeOk")
                } else {
                    log("WARNING: CCCD descriptor not found on fe66, notifications may not work")
                    // Still report connected — notifications might work without CCCD on some stacks
                    handler.post { listener?.onConnected() }
                }
            } ?: run {
                // No TX char, still report connected for write-only mode
                log("No fe66, connecting in write-only mode")
                handler.post { listener?.onConnected() }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            log("onDescriptorWrite: uuid=${descriptor.uuid} status=$status (${if (status == BluetoothGatt.GATT_SUCCESS) "OK" else "FAIL"})")
            if (descriptor.uuid == CCCD_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    log("Notifications enabled on fe66 — ready")
                } else {
                    log("WARNING: CCCD write failed (status=$status), notifications may not work")
                }
                // Report connected after CCCD write completes (success or fail)
                handler.post { listener?.onConnected() }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == CUSTOM_DATA_TX_UUID) {
                val data = characteristic.value
                if (data != null && data.isNotEmpty()) {
                    log("Notification received: ${data.size} bytes, type=0x${String.format("%02X", data[0])}")
                    handler.post { listener?.onDataReceived(data) }
                }
            }
        }
    }
}
