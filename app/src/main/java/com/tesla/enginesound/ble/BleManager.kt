package com.tesla.enginesound.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    companion object {
        // Standard BLE UART Service UUIDs
        val UART_SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val UART_TX_CHAR_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // Write
        val UART_RX_CHAR_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // Notify

        // Device name prefixes to look for
        val ELM327_NAME_PREFIXES = listOf("ELM327", "VLINK", "OBD2", "OBD-II", "OBD", "BLE", "LINKV")

        // Scan settings
        const val SCAN_TIMEOUT_MS = 10_000L
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val OPERATION_TIMEOUT_MS = 3_000L
        const val MAX_RECONNECT_ATTEMPTS = 3
        const val RECONNECT_DELAY_MS = 2_000L
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

    // Scanned devices
    private val _scannedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val scannedDevices: StateFlow<List<BluetoothDevice>> = _scannedDevices.asStateFlow()

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
    // SCANNING
    // ─────────────────────────────────────────────────────────────

    fun startScan() {
        if (_state.value !is BleState.Disconnected && _state.value !is BleState.Error) return
        if (isScanning.getAndSet(true)) return

        _state.value = BleState.Scanning
        _scannedDevices.value = emptyList()

        val filters = ELM327_NAME_PREFIXES.flatMap { prefix ->
            listOf(
                ScanFilter.Builder().setDeviceName(prefix).build()
            )
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner?.startScan(filters, settings, scanCallback)
            scope.launch {
                delay(SCAN_TIMEOUT_MS)
                if (isScanning.getAndSet(false)) {
                    scanner?.stopScan(scanCallback)
                    if (_state.value == BleState.Scanning) {
                        _state.value = BleState.Disconnected
                    }
                }
            }
        } catch (e: Exception) {
            isScanning.set(false)
            _state.value = BleState.Error("Scan failed: ${e.message}", e)
        }
    }

    fun stopScan() {
        if (!isScanning.getAndSet(false)) return
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Exception) { }
        if (_state.value == BleState.Scanning) {
            _state.value = BleState.Disconnected
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val current = _scannedDevices.value.toMutableList()
            if (current.none { it.address == device.address }) {
                current.add(device)
                _scannedDevices.value = current
            }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning.set(false)
            _state.value = BleState.Error("Scan failed with error code: $errorCode")
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

        withContext(Dispatchers.IO) {
            try {
                bluetoothGatt?.close()
                bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } catch (e: Exception) {
                _state.value = BleState.Error("Connection failed: ${e.message}", e)
                return@withContext
            }

            // Wait for connection or timeout
            val connected = awaitCondition(timeoutMs = CONNECT_TIMEOUT_MS) {
                _state.value is BleState.Connected || _state.value is BleState.Ready || _state.value is BleState.Error
            }

            if (!connected) {
                _state.value = BleState.Error("Connection timed out")
                bluetoothGatt?.disconnect()
            }
        }
    }

    fun disconnect() {
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
            _state.value = BleState.Error("Max reconnection attempts reached")
            return
        }
        reconnectAttempts++

        scope.launch {
            delay(RECONNECT_DELAY_MS)
            isReconnecting.set(false)
            lastConnectedDevice?.let { device ->
                connectInternal(device)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // GATT CALLBACK
    // ─────────────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _state.value = BleState.Connected(gatt.device)
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
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
                val service = gatt.getService(UART_SERVICE_UUID)
                if (service != null) {
                    txCharacteristic = service.getCharacteristic(UART_TX_CHAR_UUID)
                    rxCharacteristic = service.getCharacteristic(UART_RX_CHAR_UUID)

                    // Enable notifications for RX characteristic
                    rxCharacteristic?.let { char ->
                        gatt.setCharacteristicNotification(char, true)
                        val descriptor = char.getDescriptor(
                            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                        )
                        descriptor?.let {
                            it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(it)
                        }
                    }

                    _state.value = BleState.Ready
                } else {
                    _state.value = BleState.Error("UART service not found on device")
                }
            } else {
                _state.value = BleState.Error("Service discovery failed with status: $status")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == UART_RX_CHAR_UUID) {
                val data = characteristic.value ?: return
                _rxFlow.tryEmit(data)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            // Acknowledge for pending request matching
            pendingRequests.entries.find {
                characteristic.uuid == UART_TX_CHAR_UUID
            }?.let { (_, job) ->
                job.cancel()
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            // Notifications enabled
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == UART_RX_CHAR_UUID) {
                _rxFlow.tryEmit(value)
            }
        }

        override fun onError(errorCode: Int) {
            _state.value = BleState.Error("BLE error: $errorCode")
        }
    }

    // ─────────────────────────────────────────────────────────────
    // WRITE / READ
    // ─────────────────────────────────────────────────────────────

    /**
     * Write bytes to ELM327 and wait for response.
     * Thread-safe request/response pattern.
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
        return gatt.writeCharacteristic(txChar)
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

    fun release() {
        disconnect()
        scope.cancel()
    }
}