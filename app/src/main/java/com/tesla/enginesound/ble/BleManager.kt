package com.tesla.enginesound.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"

        // Multiple UART Service UUIDs to try (HM-10, Nordic, DF Robot, Generic)
        val UART_SERVICE_UUIDS = listOf(
            UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"), // Nordic UART
            UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB"), // HM-10 style
            UUID.fromString("0000DFB0-0000-1000-8000-00805F9B34FB"), // DF Robot
            UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB"), // Generic
            UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB"), // HM-10 TX (fallback)
        )

        // Known ELM327 device name keywords (for highlighting, not filtering)
        val DEVICE_NAME_KEYWORDS = listOf(
            "elm", "obd", "vlink", "诊断", "ble", "cc41", "hm-10", "jdty",
            "bolutek", "konnwei", "v1.5", "v2.1", "obd2", "obd-ii", "linkv"
        )

        // Original prefixes for backwards compatibility
        val ELM327_NAME_PREFIXES = listOf("ELM327", "VLINK", "OBD2", "OBD-II", "OBD", "BLE", "LINKV")

        // Scan settings
        const val SCAN_TIMEOUT_MS = 12_000L
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val OPERATION_TIMEOUT_MS = 5_000L
        const val MAX_RECONNECT_ATTEMPTS = 3
        const val RECONNECT_DELAY_MS = 2_000L

        // Characteristic descriptors
        private val CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    sealed class BleState {
        data object Disconnected : BleState()
        data object Scanning : BleState()
        data class Connecting(val device: BluetoothDevice) : BleState()
        data class Connected(val device: BluetoothDevice) : BleState()
        data object Ready : BleState()
        data class Error(val message: String, val exception: Throwable? = null) : BleState()
    }

    // BLE State as StateFlow
    private val _state = MutableStateFlow<BleState>(BleState.Disconnected)
    val state: StateFlow<BleState> = _state.asStateFlow()

    // Connection log for UI
    private val _connectionLog = MutableStateFlow<List<String>>(emptyList())
    val connectionLog: StateFlow<List<String>> = _connectionLog.asStateFlow()

    // Scanned devices with RSSI
    data class ScannedDevice(val device: BluetoothDevice, val rssi: Int, val name: String)
    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scannedDevices: StateFlow<List<ScannedDevice>> = _scannedDevices.asStateFlow()

    // Bonded (already paired) devices
    val bondedDevices: List<ScannedDevice>
        get() = bluetoothAdapter?.bondedDevices?.map {
            ScannedDevice(it, -1, it.name ?: "Unknown")
        }?.filter { it.name.isNotEmpty() } ?: emptyList()

    // RX data queue (notifications from ELM327)
    private val _rxQueue = ConcurrentLinkedQueue<ByteArray>()
    private val _rxFlow = MutableSharedFlow<ByteArray>(replay = 0, extraBufferCapacity = 64)
    val rxFlow: SharedFlow<ByteArray> = _rxFlow.asSharedFlow()

    // Internals
    private val bluetoothManager: BluetoothManager? = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val scanner: BluetoothLeScanner? get() = bluetoothAdapter?.bluetoothLeScanner
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var bluetoothGatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null

    private val isScanning = AtomicBoolean(false)
    private val isReconnecting = AtomicBoolean(false)
    private var reconnectAttempts = 0
    private var lastConnectedDevice: BluetoothDevice? = null

    // Pending responses for request/response pattern
    private val pendingRequests = ConcurrentHashMap<Int, Job>()

    val isConnected: Boolean
        get() = _state.value is BleState.Connected || _state.value is BleState.Ready

    val isReady: Boolean
        get() = _state.value is BleState.Ready

    // ─────────────────────────────────────────────────────────────
    // LOGGING HELPERS
    // ─────────────────────────────────────────────────────────────

    private fun log(msg: String) {
        Log.d(TAG, msg)
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        _connectionLog.value = (_connectionLog.value + "[$timestamp] $msg").takeLast(100)
    }

    // ─────────────────────────────────────────────────────────────
    // SCANNING
    // ─────────────────────────────────────────────────────────────

    fun startScan() {
        if (_state.value !is BleState.Disconnected && _state.value !is BleState.Error) {
            log("startScan called but state is ${_state.value}")
            return
        }
        if (isScanning.getAndSet(true)) {
            log("Already scanning")
            return
        }

        _state.value = BleState.Scanning
        _scannedDevices.value = emptyList()
        log("Starting BLE scan - showing ALL devices")

        // Scan WITHOUT filters - show everything so user can find their adapter
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        try {
            scanner?.startScan(null, settings, scanCallback)
            log("Scan started - looking for all BLE devices")

            scope.launch {
                delay(SCAN_TIMEOUT_MS)
                if (isScanning.getAndSet(false)) {
                    try {
                        scanner?.stopScan(scanCallback)
                        log("Scan timeout - stopped")
                    } catch (_: Exception) { }
                    if (_state.value == BleState.Scanning) {
                        _state.value = BleState.Disconnected
                    }
                }
            }
        } catch (e: Exception) {
            isScanning.set(false)
            log("Scan failed: ${e.message}")
            _state.value = BleState.Error("Scan failed: ${e.message}", e)
        }
    }

    fun stopScan() {
        if (!isScanning.getAndSet(false)) return
        try {
            scanner?.stopScan(scanCallback)
            log("Scan stopped by user")
        } catch (_: Exception) { }
        if (_state.value == BleState.Scanning) {
            _state.value = BleState.Disconnected
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: "Unknown"
            val rssi = result.rssi

            log("Device found: $name (${device.address}) RSSI=$rssi")

            val current = _scannedDevices.value.toMutableList()
            // Update if exists, otherwise add
            val existingIndex = current.indexOfFirst { it.device.address == device.address }
            val scannedDevice = ScannedDevice(device, rssi, name)
            if (existingIndex >= 0) {
                current[existingIndex] = scannedDevice
            } else {
                current.add(scannedDevice)
            }
            _scannedDevices.value = current
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning.set(false)
            val msg = "Scan failed with error code: $errorCode"
            log(msg)
            _state.value = BleState.Error(msg)
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            log("Batch results: ${results.size} devices")
            for (result in results) {
                onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // CONNECTION
    // ─────────────────────────────────────────────────────────────

    fun connect(device: BluetoothDevice) {
        scope.launch {
            connectInternal(device)
        }
    }

    private suspend fun connectInternal(device: BluetoothDevice) {
        stopScan()
        lastConnectedDevice = device
        reconnectAttempts = 0

        _state.value = BleState.Connecting(device)
        log("Connecting to ${device.name ?: "Unknown"} (${device.address})...")

        withContext(Dispatchers.IO) {
            try {
                bluetoothGatt?.close()
                bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                log("GATT connection initiated")
            } catch (e: Exception) {
                log("Connection failed: ${e.message}")
                _state.value = BleState.Error("Connection failed: ${e.message}", e)
                return@withContext
            }

            // Wait for connection or timeout
            val connected = awaitCondition(timeoutMs = CONNECT_TIMEOUT_MS) {
                _state.value is BleState.Connected || _state.value is BleState.Ready || _state.value is BleState.Error
            }

            if (!connected) {
                log("Connection timed out")
                _state.value = BleState.Error("Connection timed out")
                bluetoothGatt?.disconnect()
            }
        }
    }

    fun disconnect() {
        log("Disconnect requested")
        reconnectAttempts = MAX_RECONNECT_ATTEMPTS // Prevent auto-reconnect
        isReconnecting.set(false)
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (_: Exception) { }
        bluetoothGatt = null
        txCharacteristic = null
        rxCharacteristic = null
        _state.value = BleState.Disconnected
        pendingRequests.values.forEach { it.cancel() }
        pendingRequests.clear()
        _rxQueue.clear()
    }

    private fun attemptReconnect() {
        if (isReconnecting.getAndSet(true)) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            isReconnecting.set(false)
            log("Max reconnect attempts reached")
            _state.value = BleState.Error("Max reconnection attempts reached")
            return
        }
        reconnectAttempts++

        scope.launch {
            delay(RECONNECT_DELAY_MS)
            isReconnecting.set(false)
            lastConnectedDevice?.let { device ->
                log("Attempting reconnect #${reconnectAttempts}")
                connectInternal(device)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // GATT CALLBACK
    // ─────────────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            log("GATT state change: status=$status, newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _state.value = BleState.Connected(gatt.device)
                    log("Connected! Discovering services...")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("Disconnected")
                    if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS && !isReconnecting.get()) {
                        attemptReconnect()
                    } else if (_state.value !is BleState.Disconnected) {
                        _state.value = BleState.Disconnected
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("Services discovered - found ${gatt.services.size} services")

                // Log all services for debugging
                for (service in gatt.services) {
                    log("  Service: ${service.uuid}")
                    for (char in service.characteristics) {
                        val props = buildString {
                            if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE > 0) append("WRITE ")
                            if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE > 0) append("WRITE_NR ")
                            if (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY > 0) append("NOTIFY ")
                            if (char.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE > 0) append("INDICATE ")
                            if (char.properties and BluetoothGattCharacteristic.PROPERTY_READ > 0) append("READ ")
                        }
                        log("    Char: ${char.uuid} [$props]")
                    }
                }

                // Try to find UART service from our list
                var found = false
                for (uartUuid in UART_SERVICE_UUIDS) {
                    val service = gatt.getService(uartUuid)
                    if (service != null) {
                        log("Found UART service: $uartUuid")
                        findUartCharacteristics(gatt, service)
                        found = true
                        break
                    }
                }

                // If not found, try discovering ALL services and find any WRITE+NOTIFY pair
                if (!found) {
                    log("No known UART service found, searching for WRITE+NOTIFY pair...")
                    if (findWriteNotifyPair(gatt)) {
                        found = true
                    }
                }

                if (found) {
                    _state.value = BleState.Ready
                    log("UART characteristics found - READY!")
                } else {
                    _state.value = BleState.Error("No UART service found on device")
                    log("ERROR: No UART service with required characteristics")
                }
            } else {
                log("Service discovery failed with status: $status")
                _state.value = BleState.Error("Service discovery failed with status: $status")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("TX Write succeeded: ${characteristic.uuid}")
            } else {
                log("TX Write failed: status=$status")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val data = characteristic.value ?: return
            val text = data.decodeToString().replace("\r", "<CR>").replace("\n", "<LF>")
            log("RX notification (${data.size} bytes): $text")
            _rxFlow.tryEmit(data)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: android.bluetooth.BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("Descriptor write succeeded for ${descriptor.uuid}")
            } else {
                log("Descriptor write failed: status=$status")
            }
        }
    }

    private fun findUartCharacteristics(gatt: BluetoothGatt, service: BluetoothGattService) {
        // Find TX (write) and RX (notify) characteristics
        for (char in service.characteristics) {
            val props = char.properties
            if (props and BluetoothGattCharacteristic.PROPERTY_WRITE > 0 ||
                props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE > 0) {
                txCharacteristic = char
                log("TX characteristic found: ${char.uuid}")
            }
            if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY > 0 ||
                props and BluetoothGattCharacteristic.PROPERTY_INDICATE > 0) {
                rxCharacteristic = char
                log("RX characteristic found: ${char.uuid}")
                // Enable notifications
                gatt.setCharacteristicNotification(char, true)
                val descriptor = char.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
                descriptor?.let {
                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(it)
                }
            }
        }
    }

    private fun findWriteNotifyPair(gatt: BluetoothGatt): Boolean {
        // Search all services for any characteristic pair that supports WRITE and NOTIFY
        for (service in gatt.services) {
            var writeChar: BluetoothGattCharacteristic? = null
            var notifyChar: BluetoothGattCharacteristic? = null

            for (char in service.characteristics) {
                val props = char.properties
                if (props and BluetoothGattCharacteristic.PROPERTY_WRITE > 0 ||
                    props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE > 0) {
                    writeChar = char
                }
                if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY > 0 ||
                    props and BluetoothGattCharacteristic.PROPERTY_INDICATE > 0) {
                    notifyChar = char
                }
            }

            if (writeChar != null && notifyChar != null) {
                txCharacteristic = writeChar
                rxCharacteristic = notifyChar
                log("WRITE+NOTIFY pair found in service ${service.uuid}: TX=${writeChar.uuid}, RX=${notifyChar.uuid}")

                // Enable notifications for RX
                gatt.setCharacteristicNotification(notifyChar, true)
                val descriptor = notifyChar.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
                descriptor?.let {
                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(it)
                }
                return true
            }
        }
        return false
    }

    // ─────────────────────────────────────────────────────────────
    // WRITE / READ
    // ─────────────────────────────────────────────────────────────

    /**
     * Send command and accumulate response until '>' prompt or timeout.
     * This handles multi-chunk BLE responses.
     */
    suspend fun sendCommandWithResponse(cmd: ByteArray, timeoutMs: Long = OPERATION_TIMEOUT_MS): String {
        return withContext(Dispatchers.IO) {
            val txChar = txCharacteristic ?: return@withContext ""
            val gatt = bluetoothGatt ?: return@withContext ""

            val sb = StringBuilder()
            val startTime = System.currentTimeMillis()

            // Start collecting responses
            val job = scope.launch {
                observeRx().collect { chunk ->
                    val text = chunk.decodeToString()
                    sb.append(text)
                    if (text.contains(">") || System.currentTimeMillis() - startTime > timeoutMs) {
                        return@collect
                    }
                }
            }

            // Write command
            txChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            txChar.setValue(cmd)
            val writeOk = gatt.writeCharacteristic(txChar)
            log("TX: ${cmd.decodeToString().replace("\r", "<CR>")} writeOk=$writeOk")

            if (!writeOk) {
                job.cancel()
                return@withContext ""
            }

            // Wait for prompt or timeout
            delay(timeoutMs)
            job.cancel()

            val response = sb.toString()
            log("RX: ${response.replace("\r", "<CR>").replace("\n", "<LF>")}")
            response
        }
    }

    /**
     * Write bytes to ELM327 and wait for response (single chunk - legacy).
     * Prefer sendCommandWithResponse for multi-chunk support.
     */
    suspend fun writeRead(data: ByteArray, timeoutMs: Long = OPERATION_TIMEOUT_MS): ByteArray? {
        return withContext(Dispatchers.IO) {
            val txChar = txCharacteristic ?: return@withContext null
            val gatt = bluetoothGatt ?: return@withContext null

            var result: ByteArray? = null
            val latch = java.util.concurrent.CountDownLatch(1)

            // Start listening before writing
            val job = scope.launch {
                _rxFlow.filter { it.isNotEmpty() }.collect {
                    result = it
                    latch.countDown()
                }
            }

            // Write command
            txChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            txChar.setValue(data)
            val writeOk = gatt.writeCharacteristic(txChar)

            if (!writeOk) {
                job.cancel()
                return@withContext null
            }

            // Wait for response with timeout
            latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            job.cancel()

            result
        }
    }

    /**
     * Write bytes without waiting (fire-and-forget, e.g., for continuous monitoring).
     */
    fun writeNoResponse(data: ByteArray): Boolean {
        val txChar = txCharacteristic ?: return false
        val gatt = bluetoothGatt ?: return false

        txChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        txChar.setValue(data)
        val ok = gatt.writeCharacteristic(txChar)
        log("TX (no response): ${data.decodeToString().replace("\r", "<CR>")} ok=$ok")
        return ok
    }

    /**
     * Collect all incoming data as a Flow (for continuous monitoring).
     */
    fun observeRx(): SharedFlow<ByteArray> = rxFlow

    // ─────────────────────────────────────────────────────────────
    // UTILITY
    // ─────────────────────────────────────────────────────────────

    private suspend fun awaitCondition(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val start = System.currentTimeMillis()
        while (!condition()) {
            if (System.currentTimeMillis() - start > timeoutMs) return false
            delay(50)
        }
        return true
    }

    fun hasBluetooth(): Boolean = bluetoothAdapter != null

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    fun getDeviceName(device: BluetoothDevice): String = device.name ?: "Unknown"

    fun clearConnectionLog() {
        _connectionLog.value = emptyList()
    }

    fun release() {
        disconnect()
        scope.cancel()
    }
}