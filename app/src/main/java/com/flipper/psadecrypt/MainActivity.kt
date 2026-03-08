package com.flipper.psadecrypt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.flipper.psadecrypt.filemanager.FileManagerFragment
import com.flipper.psadecrypt.rpc.FlipperRpcClient
import com.flipper.psadecrypt.storage.FlipperStorageApi
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity(), FlipperBleClient.Listener {
    companion object {
        private const val TAG = "Companion"
    }

    lateinit var bleClient: FlipperBleClient
        private set
    private val handler = Handler(Looper.getMainLooper())

    // RPC client & storage API (initialized on BLE connect if RPC chars available)
    private var rpcClient: FlipperRpcClient? = null
    var storageApi: FlipperStorageApi? = null
        private set
    private var rpcScope: CoroutineScope? = null

    // UI — main
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navView: NavigationView
    private lateinit var bleStatusText: TextView
    private lateinit var bleButton: Button
    private lateinit var deviceSpinner: Spinner
    private lateinit var logToggle: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var logText: TextView

    // BLE scan
    private val foundDevices = mutableListOf<BluetoothDevice>()
    private val deviceNames = mutableListOf<String>()
    private lateinit var deviceAdapter: ArrayAdapter<String>

    private var logExpanded = false
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // Current fragment
    private var psaFragment: PsaDecryptFragment? = null
    private var fileManagerFragment: FileManagerFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Toolbar
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        // Drawer
        drawerLayout = findViewById(R.id.drawer_layout)
        navView = findViewById(R.id.nav_view)

        val toggle = ActionBarDrawerToggle(
            this, drawerLayout, toolbar,
            R.string.nav_open, R.string.nav_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_psa_decrypt -> showPsaDecrypt()
                R.id.nav_file_manager -> showFileManager()
                R.id.nav_about -> showAbout()
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        // BLE status bar
        bleStatusText = findViewById(R.id.ble_status_text)
        bleButton = findViewById(R.id.ble_button)
        deviceSpinner = findViewById(R.id.device_spinner)

        deviceAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, deviceNames)
        deviceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        deviceSpinner.adapter = deviceAdapter

        bleButton.setOnClickListener { requestPermissionsAndScan() }

        // Shared log
        logToggle = findViewById(R.id.log_toggle)
        logScroll = findViewById(R.id.log_scroll)
        logText = findViewById(R.id.log_text)

        logToggle.setOnClickListener {
            logExpanded = !logExpanded
            logScroll.visibility = if (logExpanded) View.VISIBLE else View.GONE
            logToggle.text = if (logExpanded) "▼ Log" else "▶ Log"
        }

        // BLE client
        bleClient = FlipperBleClient(this)
        bleClient.listener = this

        // Default fragment
        if (savedInstanceState == null) {
            showPsaDecrypt()
            navView.setCheckedItem(R.id.nav_psa_decrypt)
        }

        val cpuCount = Runtime.getRuntime().availableProcessors()
        appendLog("App started, $cpuCount CPU cores, SDK ${Build.VERSION.SDK_INT}")
    }

    // --- Fragment navigation ---

    private fun showPsaDecrypt() {
        fileManagerFragment = null
        val frag = PsaDecryptFragment()
        psaFragment = frag
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, frag)
            .commit()
        supportActionBar?.title = "PSA Decrypt"
    }

    private fun showFileManager() {
        psaFragment = null
        val frag = FileManagerFragment()
        fileManagerFragment = frag
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, frag)
            .commit()
        supportActionBar?.title = "File Manager"
    }

    private fun showAbout() {
        psaFragment = null
        fileManagerFragment = null
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, AboutFragment())
            .commit()
        supportActionBar?.title = "About"
    }

    // --- RPC lifecycle ---

    private fun startRpcClient() {
        if (!bleClient.isRpcAvailable) {
            appendLog("RPC characteristics not available, file manager won't work")
            return
        }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val client = FlipperRpcClient(bleClient, scope)
        client.start()
        rpcClient = client
        rpcScope = scope
        storageApi = FlipperStorageApi(client)
        appendLog("RPC client started (MTU=${bleClient.negotiatedMtu}, buffer=${bleClient.rpcBufferRemaining})")

        // Notify file manager fragment
        fileManagerFragment?.onConnectionChanged(true)
    }

    private fun stopRpcClient() {
        rpcClient?.stop()
        rpcClient = null
        storageApi = null
        rpcScope?.cancel()
        rpcScope = null
        appendLog("RPC client stopped")

        // Notify file manager fragment
        fileManagerFragment?.onConnectionChanged(false)
    }

    // --- Shared log ---

    fun appendLog(msg: String) {
        val ts = timeFmt.format(Date())
        val line = "[$ts] $msg\n"
        Log.d(TAG, msg)
        runOnUiThread {
            logText.append(line)
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // --- BLE data bridge ---

    fun sendBleData(data: ByteArray): Boolean {
        if (!bleClient.isConnected) return false
        return bleClient.send(data)
    }

    // --- BLE Permissions ---

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        appendLog("Permission results: $results")
        if (results.values.all { it }) startBleScan()
        else {
            bleStatusText.text = "BLE permissions denied"
            appendLog("BLE permissions denied")
        }
    }

    private fun requestPermissionsAndScan() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        appendLog("Permissions needed: $needed")
        if (needed.isEmpty()) startBleScan()
        else permLauncher.launch(needed.toTypedArray())
    }

    // --- BLE Scanning ---

    private fun startBleScan() {
        val btManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = btManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            bleStatusText.text = "Bluetooth not available"
            appendLog("Bluetooth adapter null or disabled")
            return
        }

        foundDevices.clear()
        deviceNames.clear()
        deviceAdapter.notifyDataSetChanged()

        val bonded = adapter.bondedDevices ?: emptySet()
        appendLog("Bonded devices: ${bonded.size}")
        for (device in bonded) {
            val name = device.name ?: "unknown"
            appendLog("  Bonded: $name (${device.address})")
            if (name.startsWith("Flipper")) {
                foundDevices.add(device)
                deviceNames.add("$name (${device.address}) [bonded]")
            }
        }
        deviceAdapter.notifyDataSetChanged()
        deviceSpinner.visibility = View.VISIBLE

        bleStatusText.text = "Scanning... (${foundDevices.size} bonded)"
        appendLog("Starting BLE scan...")

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            bleStatusText.text = "BLE scanner not available"
            appendLog("bluetoothLeScanner is null")
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (e: Exception) {
            appendLog("startScan failed: ${e.message}")
            bleStatusText.text = "Scan failed: ${e.message}"
            return
        }

        handler.postDelayed({
            try {
                scanner.stopScan(scanCallback)
            } catch (e: Exception) {
                appendLog("stopScan failed: ${e.message}")
            }
            appendLog("Scan complete, ${foundDevices.size} device(s) found")
            if (foundDevices.isNotEmpty()) {
                bleStatusText.text = "Found ${foundDevices.size} device(s)"
                bleButton.text = "Connect"
                bleButton.setOnClickListener { connectToSelected() }
            } else {
                bleStatusText.text = "No Flipper found"
            }
        }, 10000)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name
            val addr = device.address

            if (name != null) {
                appendLog("Scan: $name ($addr) rssi=${result.rssi}")
            }

            if (name != null && name.startsWith("Flipper") && !foundDevices.any { it.address == addr }) {
                foundDevices.add(device)
                deviceNames.add("$name ($addr)")
                runOnUiThread {
                    deviceAdapter.notifyDataSetChanged()
                    bleStatusText.text = "Found ${foundDevices.size} device(s), scanning..."
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            appendLog("Scan failed with error code: $errorCode")
            runOnUiThread { bleStatusText.text = "Scan failed (error $errorCode)" }
        }
    }

    private fun connectToSelected() {
        val idx = deviceSpinner.selectedItemPosition
        if (idx < 0 || idx >= foundDevices.size) {
            bleStatusText.text = "No device selected"
            return
        }
        val device = foundDevices[idx]
        appendLog("Connecting to ${device.name} (${device.address})...")
        bleStatusText.text = "Connecting..."
        bleClient.connect(device)
    }

    // --- FlipperBleClient.Listener ---

    override fun onLog(msg: String) {
        appendLog("[BLE] $msg")
    }

    override fun onConnected() {
        appendLog("Connected to Flipper")
        bleStatusText.text = "Connected"
        bleButton.text = "Disconnect"
        bleButton.setOnClickListener { bleClient.disconnect() }
        deviceSpinner.visibility = View.GONE
        BleKeepAliveService.start(this)

        // Start RPC client for file manager
        startRpcClient()
    }

    override fun onDisconnected() {
        appendLog("Disconnected from Flipper")
        bleStatusText.text = "Disconnected"
        psaFragment?.bfExecutor?.cancel()
        bleButton.text = "Scan BLE"
        bleButton.setOnClickListener { requestPermissionsAndScan() }
        deviceSpinner.visibility = View.GONE
        BleKeepAliveService.stop(this)

        // Stop RPC client
        stopRpcClient()
    }

    override fun onDataReceived(data: ByteArray) {
        appendLog("BLE data received: ${data.size} bytes, type=0x${String.format("%02X", data[0])}")
        psaFragment?.handleBleData(data)
    }

    // --- Lifecycle ---

    @Deprecated("Use onBackPressedDispatcher")
    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        psaFragment?.bfExecutor?.cancel()
        stopRpcClient()
        bleClient.disconnect()
        BleKeepAliveService.stop(this)
    }
}
