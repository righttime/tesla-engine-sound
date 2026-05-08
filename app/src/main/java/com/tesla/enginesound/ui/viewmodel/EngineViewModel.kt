package com.tesla.enginesound.ui.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// Placeholder data classes - would be replaced with actual BLE/Parser implementations
data class BleDevice(
    val name: String,
    val address: String,
    val rssi: Int = 0
)

data class TeslaVehicleState(
    val batterySoc: Int = 0,
    val batteryTemp: Int = 0,
    val batteryVoltage: Float = 0f,
    val speed: Int = 0,
    val motorPower: Float = 0f,
    val torque: Float = 0f
)

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Scanning : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(val device: BleDevice) : ConnectionState()
}

enum class EnginePreset(val displayName: String, val cylinders: Int) {
    I4("I4", 4),
    V6("V6", 6),
    V8("V8", 8),
    V12("V12", 12)
}

class EngineViewModel : ViewModel() {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<BleDevice>> = _discoveredDevices.asStateFlow()

    private val _connectedDevice = MutableStateFlow<BleDevice?>(null)
    val connectedDevice: StateFlow<BleDevice?> = _connectedDevice.asStateFlow()

    private val _vehicleState = MutableStateFlow(TeslaVehicleState())
    val vehicleState: StateFlow<TeslaVehicleState> = _vehicleState.asStateFlow()

    private val _engineRpm = MutableStateFlow(0f)
    val engineRpm: StateFlow<Float> = _engineRpm.asStateFlow()

    private val _engineThrottle = MutableStateFlow(0f)
    val engineThrottle: StateFlow<Float> = _engineThrottle.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _enginePreset = MutableStateFlow(EnginePreset.V8)
    val enginePreset: StateFlow<EnginePreset> = _enginePreset.asStateFlow()

    private val _cabinFilterEnabled = MutableStateFlow(false)
    val cabinFilterEnabled: StateFlow<Boolean> = _cabinFilterEnabled.asStateFlow()

    private val _volume = MutableStateFlow(0.7f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    fun startScan() {
        _connectionState.value = ConnectionState.Scanning
        _discoveredDevices.update { emptyList() }
        // TODO: Initiate BLE scan - placeholder for actual BLE implementation
        // In real implementation, this would call BLEManager.startScan()
    }

    fun connect(device: BleDevice) {
        _connectionState.value = ConnectionState.Connecting
        // TODO: Connect to BLE device - placeholder for actual BLE connection
        // In real implementation, this would call BLEManager.connect(device.address)
    }

    fun disconnect() {
        _connectionState.value = ConnectionState.Disconnected
        _isConnected.update { false }
        _connectedDevice.update { null }
        _engineRpm.update { 0f }
        _engineThrottle.update { 0f }
        // TODO: Disconnect BLE device
    }

    fun setEnginePreset(preset: EnginePreset) {
        _enginePreset.update { preset }
        // TODO: Update engine sound based on preset
    }

    fun toggleCabinFilter() {
        _cabinFilterEnabled.update { !it }
    }

    fun setVolume(volume: Float) {
        _volume.update { volume.coerceIn(0f, 1f) }
    }

    fun updateVehicleState(state: TeslaVehicleState) {
        _vehicleState.update { state }
    }

    fun updateRpm(rpm: Float) {
        _engineRpm.update { rpm.coerceIn(0f, 7000f) }
    }

    fun updateThrottle(throttle: Float) {
        _engineThrottle.update { throttle.coerceIn(0f, 100f) }
    }

    // Called when CAN frame is received from BLE
    fun onCanFrameReceived(frame: ByteArray) {
        // TODO: Parse CAN frame and extract RPM/throttle values
        // In real implementation, this would call CanParser.parse(frame)
        // Placeholder - simulate RPM based on speed
        val speed = _vehicleState.value.speed
        if (speed > 0) {
            val simulatedRpm = (speed * 40f).coerceAtMost(7000f)
            _engineRpm.update { simulatedRpm }
            _engineThrottle.update { (simulatedRpm / 7000f * 100f) }
        }
    }

    fun onDeviceConnected(device: BleDevice) {
        _connectedDevice.update { device }
        _isConnected.update { true }
        _connectionState.value = ConnectionState.Connected(device)
    }

    fun onDeviceDisconnected() {
        _connectedDevice.update { null }
        _isConnected.update { false }
        _connectionState.value = ConnectionState.Disconnected
    }

    fun onDevicesFound(devices: List<BleDevice>) {
        _discoveredDevices.update { devices }
    }
}
