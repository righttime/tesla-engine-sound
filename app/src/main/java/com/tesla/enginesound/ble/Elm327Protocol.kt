package com.tesla.enginesound.ble

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ELM327 AT Command Protocol Handler
 *
 * Handles the ELM327 OBD2-to-BLE adapter communication protocol.
 * The ELM327 is a standard OBD2-to-BLE/USB bridge that speaks
 * a simple ASCII command/response protocol over BLE UART.
 *
 * Reference: ELM327 AT Command Set
 */
class Elm327Protocol(private val bleManager: BleManager) {

    companion object {
        private const val TAG = "Elm327"

        // ELM327 AT Commands
        const val CMD_RESET = "AT Z"
        const val CMD_ECHO_OFF = "AT E0"
        const val CMD_ECHO_ON = "AT E1"
        const val CMD_PROTOCOL_AUTO = "AT SP 6"
        const val CMD_HEADERS_ON = "AT H1"
        const val CMD_HEADERS_OFF = "AT H0"
        const val CMD_MONITOR_ALL = "AT MA"
        const val CMD_MONITOR_CAN = "AT CR" // CAN receive only
        const val CMD_STANDARD_FORMAT = "AT D0"
        const val CMD_SET_PROTOCOL = "AT SP"
        const val CMD_VERSION = "AT I"
        const val CMD_DESCRIPTION = "AT @1"
        const val CMD_CLOSE = "AT PC" // Close protocol
        const val CMD_OPEN = "AT OP" // Open protocol
        const val CMD_READY = "AT WS" // Warm start

        // Response terminators
        const val ELM_PROMPT = ">"
        const val ELM_SEARCHING = "SEARCHING..."
        const val ELM_NODATA = "NODATA"
        const val ELM_OK = "OK"
        const val ELM_ERROR = "ERROR"
        const val ELM_UNABLE = "UNABLE TO CONNECT"

        // Timeouts
        private const val DEFAULT_TIMEOUT_MS = 3000L
        private const val RESET_TIMEOUT_MS = 5000L // Reset takes longer
        private const val CMD_TERMINATOR = "\r"
    }

    // Protocol state
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _initProgress = MutableStateFlow<String>("")
    val initProgress: StateFlow<String> = _initProgress.asStateFlow()

    private val _monitoringActive = MutableStateFlow(false)
    val monitoringActive: StateFlow<Boolean> = _monitoringActive.asStateFlow()

    // Raw CAN frame stream (for continuous monitoring)
    private val _canFrames = MutableSharedFlow<Pair<Int, ByteArray>>(replay = 0, extraBufferCapacity = 128)
    val canFrames: SharedFlow<Pair<Int, ByteArray>> = _canFrames.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitoringJob: Job? = null

    // ─────────────────────────────────────────────────────────────
    // LOGGING
    // ─────────────────────────────────────────────────────────────

    private fun log(msg: String) {
        Log.d(TAG, msg)
    }

    // ─────────────────────────────────────────────────────────────
    // INITIALIZATION
    // ─────────────────────────────────────────────────────────────

    /**
     * Initialize ELM327 with standard init sequence:
     * 1. AT Z - Reset (takes up to 5s)
     * 2. AT E0 - Echo off
     * 3. AT SP 6 - Set protocol to Auto (CAN)
     * 4. AT H1 - Headers on (to see source/dest addresses)
     *
     * Returns true if initialization succeeded.
     */
    suspend fun initSequence(timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        if (!bleManager.isConnected) {
            log("initSequence called but not connected")
            return false
        }

        try {
            _initProgress.value = "Resetting ELM327..."

            // AT Z - Reset (this can take up to 5 seconds)
            log("Sending: AT Z (reset)")
            if (!sendCommand(CMD_RESET, RESET_TIMEOUT_MS)) {
                log("AT Z failed")
                _initProgress.value = "Reset failed"
                return false
            }
            delay(200)

            _initProgress.value = "Setting echo off..."
            // AT E0 - Echo off
            log("Sending: AT E0 (echo off)")
            if (!sendCommand(CMD_ECHO_OFF, timeoutMs)) {
                log("AT E0 failed")
                _initProgress.value = "Echo off failed"
                return false
            }
            delay(100)

            _initProgress.value = "Setting protocol to CAN..."
            // AT SP 6 - Set protocol to CAN auto
            log("Sending: AT SP 6 (protocol CAN auto)")
            if (!sendCommand(CMD_PROTOCOL_AUTO, timeoutMs)) {
                log("AT SP 6 failed")
                _initProgress.value = "Protocol set failed"
                return false
            }
            delay(100)

            _initProgress.value = "Enabling headers..."
            // AT H1 - Headers on
            log("Sending: AT H1 (headers on)")
            if (!sendCommand(CMD_HEADERS_ON, timeoutMs)) {
                log("AT H1 failed")
                _initProgress.value = "Headers failed"
                return false
            }
            delay(100)

            _initProgress.value = "Ready!"
            _isInitialized.value = true
            log("ELM327 initialization complete!")
            return true
        } catch (e: Exception) {
            log("Init sequence exception: ${e.message}")
            _initProgress.value = "Error: ${e.message}"
            _isInitialized.value = false
            return false
        }
    }

    // ─────────────────────────────────────────────────────────────
    // MONITORING
    // ─────────────────────────────────────────────────────────────

    /**
     * Start continuous CAN frame monitoring.
     * This runs in the background and emits parsed CAN frames via canFrames Flow.
     *
     * The ELM327 will stream CAN frames in format like:
     *   "252D8CA8F7DEC438510" (18 chars = 9 bytes with header)
     *   First 3 chars = CAN arbitration ID (0x252)
     *   Remaining = 8 bytes of data
     */
    fun startMonitoring() {
        if (_monitoringActive.value) {
            log("Already monitoring")
            return
        }

        monitoringJob = scope.launch {
            _monitoringActive.value = true
            log("Starting CAN monitoring...")

            // Send AT MA command to start monitor all
            val sent = bleManager.writeNoResponse(buildCommand(CMD_MONITOR_ALL))
            if (!sent) {
                log("Failed to start monitoring")
                _monitoringActive.value = false
                return@launch
            }

            log("Monitoring started, collecting frames...")

            // Collect and parse incoming data
            bleManager.observeRx()
                .buffer(16) // Batch incoming bytes
                .collect { data ->
                    parseAndEmit(data)
                }
        }
    }

    fun stopMonitoring() {
        log("Stopping monitoring")
        monitoringJob?.cancel()
        monitoringJob = null
        _monitoringActive.value = false
        // Send ESC to stop current operation
        scope.launch {
            bleManager.writeNoResponse(byteArrayOf(0x1B)) // ESC
        }
    }

    // ─────────────────────────────────────────────────────────────
    // COMMAND EXECUTION
    // ─────────────────────────────────────────────────────────────

    /**
     * Send an AT command and wait for response.
     * @return true if we got a valid response (not an error)
     */
    suspend fun sendCommand(cmd: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        val response = sendCommandRaw(cmd, timeoutMs)
        return response != null && !containsError(response)
    }

    /**
     * Send an AT command and return raw response string.
     * Uses the new accumulation method to collect multi-chunk responses.
     */
    suspend fun sendCommandRaw(cmd: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String? {
        val data = buildCommand(cmd)
        val response = bleManager.sendCommandWithResponse(data, timeoutMs)
        return response?.normalizeResponse()
    }

    /**
     * Execute a raw OBD query (e.g., "01 0D 1" for vehicle speed) and return response.
     */
    suspend fun queryObd(mode: Int, pid: Int, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ByteArray? {
        val cmd = String.format("%02X %02X 1", mode, pid) // Mode 01 = show data, pid
        val data = buildCommand(cmd)
        val response = bleManager.writeRead(data, timeoutMs)
        return response?.normalizeResponseBytes()
    }

    // ─────────────────────────────────────────────────────────────
    // CAN FRAME PARSING
    // ─────────────────────────────────────────────────────────────

    /**
     * Parse an ELM327 CAN frame response line.
     * Input example: "252D8CA8F7DEC438510"
     *   - First 3 chars (or up to 3 bytes in hex) = arbitration ID
     *   - Remaining chars = data bytes (padded to 8 bytes = 16 chars)
     *
     * With headers ON, the ELM327 format is:
     *   "IIIDDDDDDDDDDDDDDD" where III = ID (1-3 bytes), DD = data (up to 8 bytes)
     *   The format varies based on protocol. For 11-bit CAN (standard OBD):
     *   - ID is 3 hex chars (11-bit)
     *   - Data is 16 hex chars (8 bytes)
     *
     * @return Pair(arbitrationId, dataBytes) or null if invalid
     */
    fun parseCanFrame(line: String): Pair<Int, ByteArray>? {
        val hex = line.replace("\\s".toRegex(), "").uppercase()

        // Skip prompts and non-frame responses
        if (hex.isEmpty() || hex == ">" || hex.startsWith("SEARCHING") ||
            hex.startsWith("NODATA") || hex.startsWith("OK") ||
            hex.startsWith("ERROR") || hex.startsWith("UNABLE")) {
            return null
        }

        // Handle multi-frame responses (length byte followed by data)
        // For now, we handle single frame CAN messages

        // Standard CAN message from ELM327 with headers:
        // 11-bit CAN ID (3 hex chars) + up to 8 data bytes (16 hex chars)
        // Minimum valid frame: 3 + 2 = 5 chars (ID + 1 data byte)
        if (hex.length < 5) return null

        // Parse arbitration ID (first 3 characters = 11-bit CAN ID)
        // In "252D8CA8F7DEC438510", "252" is the CAN ID
        val idStr = hex.substring(0, minOf(3, hex.length))
        val arbitrationId = idStr.toIntOrNull(16) ?: return null

        // Parse data bytes (remaining characters, padded to even length)
        val dataHex = hex.substring(minOf(3, hex.length))
        val paddedDataHex = if (dataHex.length % 2 != 0) dataHex + "0" else dataHex

        val dataBytes = ByteArray(paddedDataHex.length / 2) { i ->
            val byteStr = paddedDataHex.substring(i * 2, i * 2 + 2)
            byteStr.toIntOrNull(16)?.toByte() ?: return null
        }

        return Pair(arbitrationId, dataBytes)
    }

    /**
     * Parse a ByteArray containing CAN frame data (from BLE notification).
     * Handles multi-line responses and strips echo.
     */
    private fun parseAndEmit(data: ByteArray) {
        val text = data.decodeToString()
        val lines = text.lines()

        for (line in lines) {
            // Skip echo (command echo back before response)
            val strippedLine = stripEcho(line)
            if (strippedLine.isEmpty() || strippedLine == ">") continue

            parseCanFrame(strippedLine)?.let { (id, bytes) ->
                scope.launch {
                    _canFrames.emit(Pair(id, bytes))
                }
            }
        }
    }

    /**
     * Strip echo from response.
     * When echo is on (or during init before echo is disabled),
     * the command is echoed back before the response.
     */
    private fun stripEcho(line: String): String {
        // If line contains \r\r\n it's likely echo + response
        // Just return the last meaningful part
        val trimmed = line.trim()
        if (trimmed.contains("\r") || trimmed.contains("\n")) {
            val parts = trimmed.split("\r", "\n").filter { it.isNotEmpty() && it != ">" }
            return parts.lastOrNull()?.trim() ?: ""
        }
        return trimmed
    }

    // ─────────────────────────────────────────────────────────────
    // CAN ID -> TESLA DATA MAPPING
    // ─────────────────────────────────────────────────────────────

    /**
     * Map CAN arbitration ID to parsed data field.
     * These are known Tesla CAN IDs from reverse engineering:
     *
     * 0x118 - Accelerator/Brake pedal position
     * 0x132 - Battery voltage/current
     * 0x154 - Rear motor torque
     * 0x157 - Steering angle
     * 0x166 - Motor/inverter status
     * 0x1D4 - Front motor torque
     * 0x212 - Battery temperature
     * 0x257 - Vehicle speed
     * 0x266 - Rear motor power
     * 0x292 - SOC (State of Charge)
     * 0x321 - Ambient/outside temperature
     * 0x3B6 - Odometer
     * 0x2E5 - Front motor power
     */
    fun parseTeslaFrame(id: Int, data: ByteArray): ParsedFrame? {
        if (data.size < 2) return null

        return when (id) {
            0x118 -> parsePedalFrame(data)
            0x132 -> parseBatteryFrame(data)
            0x154 -> parseRearTorqueFrame(data)
            0x1D4 -> parseFrontTorqueFrame(data)
            0x212 -> parseBatteryTempFrame(data)
            0x257 -> parseSpeedFrame(data)
            0x266 -> parseRearPowerFrame(data)
            0x2E5 -> parseFrontPowerFrame(data)
            0x292 -> parseSocFrame(data)
            0x321 -> parseOutsideTempFrame(data)
            0x3B6 -> parseOdometerFrame(data)
            else -> null
        }
    }

    // Parsed frame data class
    data class ParsedFrame(
        val id: Int,
        val values: Map<String, Any>
    )

    private fun parsePedalFrame(data: ByteArray): ParsedFrame? {
        // 0x118: Byte 0 = accelerator pedal (0-100%), Byte 1 bit 0 = brake on
        val accelerator = data[0].toFloat() // 0-100%
        val brakeOn = (data[1].toInt() and 0x01) != 0
        return ParsedFrame(0x118, mapOf(
            "acceleratorPedal" to accelerator,
            "brakePedalOn" to brakeOn
        ))
    }

    private fun parseBatteryFrame(data: ByteArray): ParsedFrame? {
        // 0x132: Big-endian shorts for voltage and current
        if (data.size < 4) return null
        val voltage = ByteBuffer.wrap(data, 0, 2).order(ByteOrder.BIG_ENDIAN).short.toFloat() / 10f
        val current = ByteBuffer.wrap(data, 2, 2).order(ByteOrder.BIG_ENDIAN).short.toFloat() / 10f
        return ParsedFrame(0x132, mapOf(
            "batteryVoltage" to voltage,
            "batteryCurrent" to current
        ))
    }

    private fun parseRearTorqueFrame(data: ByteArray): ParsedFrame? {
        // 0x154: Rear motor torque in Nm (big-endian short)
        if (data.size < 2) return null
        val torque = ByteBuffer.wrap(data, 0, 2).order(ByteOrder.BIG_ENDIAN).short.toFloat()
        return ParsedFrame(0x154, mapOf("rearTorqueNm" to torque))
    }

    private fun parseFrontTorqueFrame(data: ByteArray): ParsedFrame? {
        // 0x1D4: Front motor torque in Nm (big-endian short)
        if (data.size < 2) return null
        val torque = ByteBuffer.wrap(data, 0, 2).order(ByteOrder.BIG_ENDIAN).short.toFloat()
        return ParsedFrame(0x1D4, mapOf("frontTorqueNm" to torque))
    }

    private fun parseBatteryTempFrame(data: ByteArray): ParsedFrame? {
        // 0x212: Battery temperature (offset by 40 to get Celsius)
        val tempRaw = data[0].toInt() and 0xFF
        val tempC = tempRaw - 40f
        return ParsedFrame(0x212, mapOf("batteryTempC" to tempC))
    }

    private fun parseSpeedFrame(data: ByteArray): ParsedFrame? {
        // 0x257: Vehicle speed (km/h, big-endian short)
        if (data.size < 2) return null
        val speed = ByteBuffer.wrap(data, 0, 2).order(ByteOrder.BIG_ENDIAN).short.toFloat()
        return ParsedFrame(0x257, mapOf("speedKmh" to speed))
    }

    private fun parseRearPowerFrame(data: ByteArray): ParsedFrame? {
        // 0x266: Rear motor power (kW, big-endian short)
        if (data.size < 2) return null
        val power = ByteBuffer.wrap(data, 0, 2).order(ByteOrder.BIG_ENDIAN).short.toFloat()
        return ParsedFrame(0x266, mapOf("rearPowerKw" to power))
    }

    private fun parseFrontPowerFrame(data: ByteArray): ParsedFrame? {
        // 0x2E5: Front motor power (kW, big-endian short)
        if (data.size < 2) return null
        val power = ByteBuffer.wrap(data, 0, 2).order(ByteOrder.BIG_ENDIAN).short.toFloat()
        return ParsedFrame(0x2E5, mapOf("frontPowerKw" to power))
    }

    private fun parseSocFrame(data: ByteArray): ParsedFrame? {
        // 0x292: State of Charge (percentage, 0-100)
        val soc = data[0].toFloat()
        return ParsedFrame(0x292, mapOf("socPercent" to soc))
    }

    private fun parseOutsideTempFrame(data: ByteArray): ParsedFrame? {
        // 0x321: Outside temperature (offset by 40 to get Celsius, signed)
        if (data.size < 1) return null
        val tempRaw = data[0].toInt()
        val tempC = if (tempRaw > 127) tempRaw - 256 - 40 else tempRaw - 40
        return ParsedFrame(0x321, mapOf("outsideTempC" to tempC.toFloat()))
    }

    private fun parseOdometerFrame(data: ByteArray): ParsedFrame? {
        // 0x3B6: Odometer (km, big-endian 3-byte integer)
        if (data.size < 3) return null
        val odo = ByteBuffer.wrap(data, 0, 3).order(ByteOrder.BIG_ENDIAN).int.toFloat()
        return ParsedFrame(0x3B6, mapOf("odometerKm" to odo))
    }

    // ─────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────

    private fun buildCommand(cmd: String): ByteArray {
        val full = cmd + CMD_TERMINATOR
        return full.toByteArray()
    }

    private fun containsError(response: String): Boolean {
        val upper = response.uppercase()
        return upper.contains("ERROR") || upper.contains("UNABLE") ||
               (upper.contains("?") && !upper.contains("OK"))
    }

    private fun String.normalizeResponse(): String {
        // Remove ELM327 prompts, whitespace, and normalize
        return this
            .replace(">", "")
            .replace("\r", "")
            .replace("\n", "")
            .replace(" ", "")
            .trim()
    }

    private fun ByteArray.normalizeResponseBytes(): ByteArray {
        // Strip prompt '>' and whitespace
        val baos = ByteArrayOutputStream()
        for (b in this) {
            if (b.toInt() == 0x3E) continue // '>' prompt
            if (b.toInt() == 0x0D || b.toInt() == 0x0A) continue // CR/LF
            if (b == 0x20.toByte()) continue // space
            baos.write(b.toInt())
        }
        return baos.toByteArray()
    }

    fun release() {
        stopMonitoring()
        scope.cancel()
    }
}