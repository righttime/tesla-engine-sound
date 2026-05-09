package com.tesla.enginesound.tesla

import kotlin.math.pow

/**
 * Parser for Tesla Model 3/Y CAN bus frames.
 *
 * Tesla vehicles communicate over CAN bus with an 11-bit ID at 500kbps.
 * This parser extracts specific signals from raw 8-byte CAN data frames.
 *
 * ## CAN Bit Layout
 *
 * CAN frames are 8 bytes (64 bits). Bits are numbered 0–63, with bit 0 being
 * the MSB of the first byte (byte 0). Signal extraction must handle
 * cross-byte boundaries (e.g., a 12-bit signal starting at bit 12 spans
 * bytes[1] and bytes[2]).
 *
 * ## Signal Endianness
 *
 * Tesla uses **Motorola (big-endian)** bit ordering for multi-byte signals.
 * In Motorola format, bit 0 is the MSB of the first byte, and signal bits
 * are packed from MSB → LSB within each byte, then continue to the next byte.
 *
 * Example: A 12-bit signal at startBit=12 occupies bits 12–23:
 *   - byte[1] bits 4–7 (4 bits) + bits 0–7 (8 bits) from byte[2]
 *   - In Motorola: (bytes[1] & 0xF0) << 4 | bytes[2]
 *
 * ## CAN IDs Handled
 *
 * | CAN ID  | Signal                          | Length | Signed | Factor | Offset |
 * |---------|----------------------------------|--------|--------|--------|--------|
 * | 0x257   | Speed                            | 12-bit | No     | 0.08   | -40    |
 * | 0x118   | Accelerator Pedal                 | 8-bit  | No     | 0.4    | 0      |
 * | 0x118   | Brake Pedal                      | 2-bit  | No     | 1      | 0      |
 * | 0x2E5   | Front Motor Power                | 11-bit | Yes    | 0.5    | 0      |
 * | 0x266   | Rear Motor Power                 | 11-bit | Yes    | 0.5    | 0      |
 * | 0x1D4   | Front Motor Torque               | custom | Yes    | 0.25   | 0      |
 * | 0x154   | Rear Motor Torque                 | custom | Yes    | 0.25   | 0      |
 * | 0x132   | Battery Voltage                  | 16-bit | No     | 0.01   | 0      |
 * | 0x132   | Battery Current                  | 16-bit | Yes    | 0.1    | -1000  |
 * | 0x292   | State of Charge                  | 10-bit | No     | 0.1    | 0      |
 * | 0x3B6   | Odometer                         | 32-bit | No     | 0.001  | 0      |
 * | 0x212   | Battery Temperature              | 8-bit  | No     | 0.5    | -40    |
 * | 0x321   | Outside Ambient Temperature       | 8-bit  | No     | 0.5    | -40    |
 *
 * @see TeslaVehicleState
 */
class TeslaCanParser {

    companion object {
        /** Number of bytes in a standard CAN data frame */
        const val CAN_DATA_LENGTH = 8

        // CAN IDs handled by this parser
        const val CAN_ID_SPEED           = 0x257
        const val CAN_ID_PEDAL          = 0x118
        const val CAN_ID_FRONT_POWER     = 0x2E5
        const val CAN_ID_REAR_POWER      = 0x266
        const val CAN_ID_FRONT_TORQUE    = 0x1D4
        const val CAN_ID_REAR_TORQUE     = 0x154
        const val CAN_ID_BATTERY         = 0x132
        const val CAN_ID_SOC             = 0x292
        const val CAN_ID_ODOMETER        = 0x3B6
        const val CAN_ID_BATTERY_TEMP    = 0x212
        const val CAN_ID_OUTSIDE_TEMP    = 0x321

        /** Set of all CAN IDs this parser handles, for fast lookup */
        private val SUPPORTED_IDS = setOf(
            CAN_ID_SPEED, CAN_ID_PEDAL, CAN_ID_FRONT_POWER, CAN_ID_REAR_POWER,
            CAN_ID_FRONT_TORQUE, CAN_ID_REAR_TORQUE, CAN_ID_BATTERY,
            CAN_ID_SOC, CAN_ID_ODOMETER, CAN_ID_BATTERY_TEMP, CAN_ID_OUTSIDE_TEMP
        )
    }

    /**
     * Extract a scalar signal from CAN data bytes using Motorola (big-endian) bit ordering.
     *
     * This function handles cross-byte boundaries correctly. In Motorola format,
     * bits are numbered from the MSB (bit 0 = MSB of byte 0) across bytes.
     * A signal starting at bit 12 with length 12 spans bits 12–23, which crosses
     * from byte[1] into byte[2].
     *
     * ## Algorithm
     *
     * 1. Determine start byte index and bit offset within that byte.
     * 2. Collect the raw bits by reading each byte from startByte to endByte,
     *    extracting the relevant bit range from each and shifting into result.
     * 3. Handle sign extension for signed signals by shifting left to align sign bit.
     * 4. Apply factor and offset to get physical units.
     *
     * @param bytes Raw 8-byte CAN data frame
     * @param startBit Bit index where signal starts (0–63)
     * @param length Number of bits in signal (1–32)
     * @param signed If true, interpret as two's complement signed integer
     * @param factor Multiply raw value by this to get physical units
     * @param offset Add this offset after scaling
     * @return The decoded signal as a Float in physical units
     */
    fun extractSignal(
        bytes: ByteArray,
        startBit: Int,
        length: Int,
        signed: Boolean,
        factor: Float,
        offset: Float
    ): Float {
        if (bytes.size < CAN_DATA_LENGTH) return 0f

        val rawValue = extractRawBits(bytes, startBit, length)

        // Sign-extend if signed
        val value = if (signed) {
            val shift = 32 - length
            ((rawValue shl shift) shr shift).toInt() // arithmetic shift for sign extension
        } else {
            rawValue
        }

        return value * factor + offset
    }

    /**
     * Extract raw unsigned bits from CAN data using Motorola byte order.
     *
     * ## Motorola Bit Ordering
     *
     * Each byte has bits numbered 0–7, where bit 0 is the MSB.
     * A 12-bit signal at startBit=12 spans bits 12–23:
     *   - byte[1] contains bits 8–15 (our signal starts at bit 12 = 4th bit of byte[1])
     *   - byte[2] contains bits 16–23
     *   From byte[1] we need bits 4–7 (4 bits): (bytes[1] >> 4)
     *   From byte[2] we need bits 0–7 (8 bits): bytes[2]
     *   Combined: ((bytes[1] >> 4) << 8) | bytes[2]
     */
    private fun extractRawBits(bytes: ByteArray, startBit: Int, length: Int): Int {
        val startByte = startBit / 8           // Byte index where signal starts
        val endByte = (startBit + length - 1) / 8  // Byte index where signal ends
        var result = 0
        var bitsAccumulated = 0

        for (byteIdx in startByte..endByte) {
            val byte = bytes[byteIdx].toInt() and 0xFF

            // Bit position within this byte where our signal starts (MSB = bit 0)
            val localStartBit = if (byteIdx == startByte) startBit % 8 else 0
            // How many bits we can take from this byte
            val bitsAvailable = 8 - localStartBit
            val bitsToTake = minOf(bitsAvailable, length - bitsAccumulated)

            // Mask and shift to extract the bits we need
            val mask = (0xFF ushr localStartBit) and (0xFF shl (8 - bitsToTake))
            val extractedBits = (byte and mask) ushr (8 - localStartBit - bitsToTake)

            result = (result shl bitsToTake) or extractedBits
            bitsAccumulated += bitsToTake
        }

        return result
    }

    /**
     * Parse a single CAN frame and update the vehicle state.
     *
     * This method is thread-safe — it produces a new [TeslaVehicleState] copy
     * with only the relevant fields updated, leaving all others unchanged.
     *
     * @param canId The 11-bit CAN arbitration ID
     * @param data Raw 8-byte CAN data payload
     * @return Updated TeslaVehicleState with new values from this frame,
     *         or null if canId is not handled by this parser
     */
    fun parseFrame(canId: Int, data: ByteArray): TeslaVehicleState? {
        if (data.size < CAN_DATA_LENGTH) return null
        if (canId !in SUPPORTED_IDS) return null

        return when (canId) {
            CAN_ID_SPEED -> parseSpeed(data)
            CAN_ID_PEDAL -> parsePedals(data)
            CAN_ID_FRONT_POWER -> parseFrontPower(data)
            CAN_ID_REAR_POWER -> parseRearPower(data)
            CAN_ID_FRONT_TORQUE -> parseFrontTorque(data)
            CAN_ID_REAR_TORQUE -> parseRearTorque(data)
            CAN_ID_BATTERY -> parseBattery(data)
            CAN_ID_SOC -> parseSoc(data)
            CAN_ID_ODOMETER -> parseOdometer(data)
            CAN_ID_BATTERY_TEMP -> parseBatteryTemp(data)
            CAN_ID_OUTSIDE_TEMP -> parseOutsideTemp(data)
            else -> null
        }
    }

    // ─── Individual CAN ID parsers ───────────────────────────────────────────

    /**
     * CAN ID 0x257: Vehicle Speed
     *
     * Bit 12, 12-bit unsigned, factor=0.08, offset=-40.
     * Range: -40 × 0.08 + (-40) = -43.2 to (255 × 0.08 - 40) = -20 to ~180 km/h
     *
     * Formula: speed = ((bytes[1] & 0x0F) << 8 | bytes[2]) * 0.08 - 40
     */
    private fun parseSpeed(data: ByteArray): TeslaVehicleState {
        val raw = ((data[1].toInt() and 0x0F) shl 8) or (data[2].toInt() and 0xFF)
        val speed = raw * 0.08f - 40f
        return TeslaVehicleState(speedKmh = speed)
    }

    /**
     * CAN ID 0x118: Accelerator and Brake Pedal
     *
     * Accelerator: bit 32, 8-bit unsigned, factor=0.4, offset=0
     *   - occupies bits 32–39: byte[4] (all 8 bits)
     *
     * Brake pedal: bit 19, 2-bit unsigned, factor=1, offset=0
     *   - occupies bits 19–20: byte[2] bits 3–4 (2 bits)
     *   - Value 0 = not pressed, 1 = pressed, 2+ = fault
     */
    private fun parsePedals(data: ByteArray): TeslaVehicleState {
        // Accelerator: byte index 4, all 8 bits
        val accelPedal = (data[4].toInt() and 0xFF) * 0.4f

        // Brake: byte index 2, bits 3–4 (mask 0x18, then shift right 3)
        val brakeRaw = (data[2].toInt() and 0x18) shr 3
        val brakePedalOn = brakeRaw >= 1

        return TeslaVehicleState(
            acceleratorPedal = accelPedal,
            brakePedalOn = brakePedalOn
        )
    }

    /**
     * CAN ID 0x2E5: Front Motor Power
     *
     * Bit 16, 11-bit signed, factor=0.5, offset=0.
     * Occupies bits 16–26 across bytes[2] and bytes[3].
     *
     * Signed 11-bit range: -1024 to +1023
     * Power range: -512 to +511.5 kW
     */
    private fun parseFrontPower(data: ByteArray): TeslaVehicleState {
        // Bits 16–23: bytes[2], bits 0–7
        // Bits 24–26: bytes[3] bits 0–2 (mask 0x07)
        val raw = ((data[2].toInt() and 0xFF) or ((data[3].toInt() and 0x07) shl 8))
        val power = signExtend11(raw) * 0.5f
        return TeslaVehicleState(frontPowerKw = power)
    }

    /**
     * CAN ID 0x266: Rear Motor Power
     *
     * Bit 16, 11-bit signed, factor=0.5, offset=0.
     * Same format as front power.
     */
    private fun parseRearPower(data: ByteArray): TeslaVehicleState {
        val raw = ((data[2].toInt() and 0xFF) or ((data[3].toInt() and 0x07) shl 8))
        val power = signExtend11(raw) * 0.5f
        return TeslaVehicleState(rearPowerKw = power)
    }

    /**
     * Sign-extend an 11-bit signed value.
     *
     * For signed signals, if bit 10 (MSB of 11-bit field) is set,
     * the value is negative and must be sign-extended to full int.
     */
    private fun signExtend11(value: Int): Int {
        val masked = value and 0x7FF
        return if ((masked and 0x400) != 0) masked or -2048 else masked
    }

    /**
     * CAN ID 0x1D4: Front Motor Torque
     *
     * Custom formula: (bytes[5] + ((bytes[6] & 0x1F) << 8) - (512 * (bytes[6] & 0x10))) * 0.25
     *
     * bytes[5] = low byte of torque (8 bits)
     * bytes[6] bits 0–4 = high byte of torque (5 bits)
     * bytes[6] bit 4 = sign bit: if set, subtract 512 from raw value before scaling
     *
     * Effective range: approximately -511 to +511 Nm
     */
    private fun parseFrontTorque(data: ByteArray): TeslaVehicleState {
        val raw = data[5].toInt() and 0xFF
        val highByte = data[6].toInt() and 0x1F
        val signBit = (data[6].toInt() and 0x10) != 0
        val value = raw + (highByte shl 8) - if (signBit) 512 else 0
        val torque = value * 0.25f
        return TeslaVehicleState(frontTorqueNm = torque)
    }

    /**
     * CAN ID 0x154: Rear Motor Torque
     *
     * Same formula as front torque (0x1D4).
     */
    private fun parseRearTorque(data: ByteArray): TeslaVehicleState {
        val raw = data[5].toInt() and 0xFF
        val highByte = data[6].toInt() and 0x1F
        val signBit = (data[6].toInt() and 0x10) != 0
        val value = raw + (highByte shl 8) - if (signBit) 512 else 0
        val torque = value * 0.25f
        return TeslaVehicleState(rearTorqueNm = torque)
    }

    /**
     * CAN ID 0x132: Battery Voltage and Current
     *
     * Voltage: bytes[0:2] (16-bit unsigned), divided by 100 → 0–655.35V
     * Current: bytes[2:4] (16-bit signed two's complement), formula: 1000 - raw/10 → -3276.8 to +3276.8A
     *
     * Current encoding: raw value of 10000 = 0A
     * Positive raw → discharging, Negative raw → charging
     */
    private fun parseBattery(data: ByteArray): TeslaVehicleState {
        val voltage = ((data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)) / 100f
        val rawCurrent = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        // Java/JS Int16 handling: negative values wrap around
        val signedCurrent = if (rawCurrent > 0x7FFF) rawCurrent - 0x10000 else rawCurrent
        val current = 1000f - signedCurrent / 10f
        return TeslaVehicleState(batteryVoltage = voltage, batteryCurrent = current)
    }

    /**
     * CAN ID 0x292: State of Charge (SOC)
     *
     * Bits 0–9 (10-bit): bytes[0] (8 bits) + bytes[1] bits 0–1 (2 bits)
     * Formula: (bytes[0] + ((bytes[1] & 0x3) << 8)) / 10.0
     * Range: 0–102.3%
     */
    private fun parseSoc(data: ByteArray): TeslaVehicleState {
        val raw = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0x03) shl 8)
        val soc = raw / 10f
        return TeslaVehicleState(socPercent = soc)
    }

    /**
     * CAN ID 0x3B6: Odometer
     *
     * 32-bit unsigned little-endian (standard x86 byte order, not Motorola!)
     * Formula: (bytes[0] + (bytes[1] << 8) + (bytes[2] << 16) + (bytes[3] << 24)) / 1000.0
     * Range: 0–4,294,967.295 km
     */
    private fun parseOdometer(data: ByteArray): TeslaVehicleState {
        val raw = (data[0].toInt() and 0xFF) or
                  ((data[1].toInt() and 0xFF) shl 8) or
                  ((data[2].toInt() and 0xFF) shl 16) or
                  ((data[3].toInt() and 0xFF) shl 24)
        val odometer = raw / 1000f
        return TeslaVehicleState(odometerKm = odometer)
    }

    /**
     * CAN ID 0x212: Battery Temperature
     *
     * Byte index 7 (last byte of frame), 8-bit unsigned.
     * Formula: (bytes[7] / 2.0) - 40.0
     * Range: -40 to 87.5°C
     */
    private fun parseBatteryTemp(data: ByteArray): TeslaVehicleState {
        val temp = (data[7].toInt() and 0xFF) / 2f - 40f
        return TeslaVehicleState(batteryTempC = temp)
    }

    /**
     * CAN ID 0x321: Outside Ambient Temperature
     *
     * Byte index 3, 8-bit unsigned.
     * Formula: (bytes[3] * 0.5) - 40
     * Range: -40 to 87.5°C
     */
    private fun parseOutsideTemp(data: ByteArray): TeslaVehicleState {
        val temp = (data[3].toInt() and 0xFF) * 0.5f - 40f
        return TeslaVehicleState(outsideTempC = temp)
    }

    /**
     * Parse a hex string CAN frame into (canId, data bytes).
     *
     * Accepts hex strings in the format used by Tesla CAN bus loggers:
     *   "252D8CA8F7DEC438510" → (0x252, [0x8C, 0xA8, 0xF7, 0xDE, ...])
     *
     * The first 3 hex characters are the CAN ID (11-bit, padded to 12 bits),
     * followed by 16 hex characters for the 8 data bytes.
     *
     * @param hexLine Continuous hex string (29 characters expected)
     * @return Pair of (canId, dataBytes) or null if parsing fails
     */
    fun parseHexFrame(hexLine: String): Pair<Int, ByteArray>? {
        val clean = hexLine.replace(Regex("[^0-9A-Fa-f]"), "")
        if (clean.length < 11) return null // need at least ID (3 chars) + some data

        return try {
            // CAN ID: first 3 hex chars (11-bit ID, sometimes written as 4 chars with leading 0)
            val canIdStr = if (clean.length == 11) clean.substring(0, 3) else clean.substring(0, 4)
            val canId = canIdStr.toInt(16)

            // Data: 16 hex chars = 8 bytes
            val dataHex = clean.substring(canIdStr.length, canIdStr.length + 16)
            val dataBytes = ByteArray(CAN_DATA_LENGTH)
            for (i in 0 until CAN_DATA_LENGTH) {
                dataBytes[i] = dataHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }

            Pair(canId, dataBytes)
        } catch (e: Exception) {
            null
        }
    }
}
