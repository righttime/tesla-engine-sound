package com.tesla.enginesound.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tesla.enginesound.ble.BleManager
import com.tesla.enginesound.ble.Elm327Protocol
import com.tesla.enginesound.tesla.TeslaVehicleState
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class BleDevice(
    val name: String,
    val address: String,
    val rssi: Int = 0
)

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Scanning : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(val device: BleDevice) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

enum class EnginePreset(val displayName: String, val cylinders: Int) {
    I4("I4", 4), V6("V6", 6), V8("V8", 8), V12("V12", 12)
}

class EngineViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "EngineViewModel"
    }

    private val bleManager = BleManager(application)
    private val elm327 = Elm327Protocol(bleManager)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<BleDevice>> = _discoveredDevices.asStateFlow()

    private val _connectedDevice = MutableStateFlow<BleDevice?>(null)
    val connectedDevice: StateFlow<BleDevice?> = _connectedDevice.asStateFlow()

    val connectionLog: StateFlow<List<String>> = bleManager.connectionLog
    val initProgress: StateFlow<String> = elm327.initProgress

    val pairedDevices: List<BleDevice>
        get() = bleManager.bondedDevices.map { BleDevice(it.name, it.device.address, it.rssi) }

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _vehicleState = MutableStateFlow(TeslaVehicleState.EMPTY)
    val vehicleState: StateFlow<TeslaVehicleState> = _vehicleState.asStateFlow()

    private val _engineRpm = MutableStateFlow(800f)
    val engineRpm: StateFlow<Float> = _engineRpm.asStateFlow()

    private val _engineThrottle = MutableStateFlow(0f)
    val engineThrottle: StateFlow<Float> = _engineThrottle.asStateFlow()

    private val _enginePreset = MutableStateFlow(EnginePreset.V8)
    val enginePreset: StateFlow<EnginePreset> = _enginePreset.asStateFlow()

    private val _cabinFilterEnabled = MutableStateFlow(false)
    val cabinFilterEnabled: StateFlow<Boolean> = _cabinFilterEnabled.asStateFlow()

    private val _volume = MutableStateFlow(0.7f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    init {
        // Observe BLE state changes
        viewModelScope.launch {
            bleManager.state.collect { state ->
                when (state) {
                    is BleManager.BleState.Disconnected -> {
                        _connectionState.value = ConnectionState.Disconnected
                        _isConnected.value = false
                    }
                    is BleManager.BleState.Scanning -> {
                        _connectionState.value = ConnectionState.Scanning
                    }
                    is BleManager.BleState.Connecting -> {
                        _connectionState.value = ConnectionState.Connecting
                    }
                    is BleManager.BleState.Connected -> {
                        _connectionState.value = ConnectionState.Connected(
                            _connectedDevice.value ?: BleDevice("Unknown", "")
                        )
                    }
                    is BleManager.BleState.Ready -> {
                        _connectionState.value = ConnectionState.Connected(
                            _connectedDevice.value ?: BleDevice("Unknown", "")
                        )
                        _isConnected.value = true
                        initElm327()
                    }
                    is BleManager.BleState.Error -> {
                        _connectionState.value = ConnectionState.Error(state.message)
                    }
                }
            }
        }

        // Observe scanned devices
        viewModelScope.launch {
            bleManager.scannedDevices.collect { devices ->
                _discoveredDevices.value = devices.map {
                    BleDevice(it.name, it.device.address, it.rssi)
                }
            }
        }

        // Observe CAN frames
        viewModelScope.launch {
            elm327.canFrames.collect { (id, data) ->
                val frame = elm327.parseTeslaFrame(id, data)
                if (frame != null) {
                    updateFromParsedFrame(frame)
                }
            }
        }
    }

    fun startScan() {
        bleManager.startScan()
    }

    fun stopScan() {
        bleManager.stopScan()
    }

    fun connect(device: BleDevice) {
        _connectionState.value = ConnectionState.Connecting
        _connectedDevice.value = device

        viewModelScope.launch {
            val scannedDevice = bleManager.scannedDevices.value.find { it.device.address == device.address }
            if (scannedDevice != null) {
                bleManager.connect(scannedDevice.device)
            } else {
                _connectionState.value = ConnectionState.Error("Device not found in scan results")
            }
        }
    }

    fun disconnect() {
        try {
            elm327.stopMonitoring()
            elm327.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping ELM327", e)
        }
        bleManager.disconnect()
        _connectedDevice.value = null
        _isConnected.value = false
        _connectionState.value = ConnectionState.Disconnected
        _engineRpm.value = 800f
        _engineThrottle.value = 0f
    }

    private fun initElm327() {
        viewModelScope.launch {
            try {
                val success = elm327.initSequence()
                if (success) {
                    elm327.startMonitoring()
                }
            } catch (e: Exception) {
                Log.e(TAG, "ELM327 init failed", e)
            }
        }
    }

    private fun updateFromParsedFrame(frame: Elm327Protocol.ParsedFrame) {
        val values = frame.values
        val state = _vehicleState.value

        val newState = when (frame.id) {
            0x257 -> state.copy(speedKmh = values["speedKmh"] as? Float ?: state.speedKmh)
            0x118 -> {
                _engineThrottle.update { values["acceleratorPedal"] as? Float ?: 0f }
                state
            }
            0x292 -> state.copy(socPercent = values["socPercent"] as? Float ?: state.socPercent)
            0x132 -> state.copy(
                batteryVoltage = values["batteryVoltage"] as? Float ?: state.batteryVoltage,
                batteryCurrent = values["batteryCurrent"] as? Float ?: state.batteryCurrent
            )
            0x212 -> state.copy(batteryTempC = values["batteryTempC"] as? Float ?: state.batteryTempC)
            0x154 -> state.copy(rearTorqueNm = values["rearTorqueNm"] as? Float ?: state.rearTorqueNm)
            0x1D4 -> state.copy(frontTorqueNm = values["frontTorqueNm"] as? Float ?: state.frontTorqueNm)
            0x266 -> state.copy(rearPowerKw = values["rearPowerKw"] as? Float ?: state.rearPowerKw)
            0x2E5 -> state.copy(frontPowerKw = values["frontPowerKw"] as? Float ?: state.frontPowerKw)
            else -> state
        }

        _vehicleState.value = newState

        // Simulate engine RPM based on speed for testing
        if (newState.speedKmh > 0) {
            _engineRpm.value = 800f + newState.speedKmh * 30f
        }
    }

    fun updateVehicleState(state: TeslaVehicleState) {
        _vehicleState.value = state
    }

    fun setEnginePreset(preset: EnginePreset) {
        _enginePreset.value = preset
    }

    fun toggleCabinFilter() {
        _cabinFilterEnabled.value = !_cabinFilterEnabled.value
    }

    fun setVolume(volume: Float) {
        _volume.value = volume.coerceIn(0f, 1f)
    }

    override fun onCleared() {
        super.onCleared()
        try {
            elm327.release()
            bleManager.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing resources", e)
        }
    }
}
