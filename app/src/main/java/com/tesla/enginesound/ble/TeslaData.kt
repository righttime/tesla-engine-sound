package com.tesla.enginesound.ble

import kotlin.math.abs

/**
 * Tesla vehicle state data class.
 * Contains real-time telemetry parsed from Tesla CAN bus via ELM327.
 *
 * CAN ID Reference (11-bit standard OBD2 PIDs on Tesla Model 3/Y):
 * - 0x118: Accelerator pedal position (%) + Brake switch
 * - 0x132: Battery pack voltage (V) + Current (A)
 * - 0x154: Rear motor torque (Nm)
 * - 0x1D4: Front motor torque (Nm)
 * - 0x212: Battery module temperatures (°C)
 * - 0x257: Vehicle speed (km/h)
 * - 0x266: Rear motor power (kW)
 * - 0x2E5: Front motor power (kW)
 * - 0x292: State of Charge (%)
 * - 0x321: Ambient temperature (°C)
 * - 0x3B6: Odometer (km)
 *
 * These IDs are derived from open-source Tesla research (tesla-candecode, etc.)
 * and may vary by firmware version. Values should be validated against
 * reasonable physical ranges before use.
 */
data class TeslaVehicleState(
    val speedKmh: Float = 0f,           // 0x257 - km/h
    val acceleratorPedal: Float = 0f,    // 0x118 - 0-100%
    val brakePedalOn: Boolean = false,   // 0x118 - boolean
    val frontPowerKw: Float = 0f,        // 0x2E5 - kilowatts
    val rearPowerKw: Float = 0f,         // 0x266 - kilowatts
    val frontTorqueNm: Float = 0f,       // 0x1D4 - Newton-meters
    val rearTorqueNm: Float = 0f,        // 0x154 - Newton-meters
    val batteryVoltage: Float = 0f,      // 0x132 - Volts
    val batteryCurrent: Float = 0f,       // 0x132 - Amps
    val socPercent: Float = 0f,          // 0x292 - 0-100%
    val odometerKm: Float = 0f,          // 0x3B6 - kilometers
    val batteryTempC: Float = 0f,        // 0x212 - Celsius
    val outsideTempC: Float = 0f,        // 0x321 - Celsius
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Total motor power in kilowatts
     */
    val totalPowerKw: Float
        get() = frontPowerKw + rearPowerKw

    /**
     * Total motor torque in Newton-meters
     */
    val totalTorqueNm: Float
        get() = frontTorqueNm + rearTorqueNm

    /**
     * Estimated acceleration G-force based on motor torque.
     * Uses approximate Tesla Model 3/Y curb weight.
     * Not precision - useful for relative "sportiness" indication.
     */
    val estimatedGForce: Float
        get() {
            // F = m * a, G = a / 9.81
            // Tesla Model 3/Y curb weight ~1800-2000kg depending on variant
            val massKg = 1900f
            val forceN = totalTorqueNm / 0.3f // approx wheel radius 0.3m
            return (forceN / massKg) / 9.81f
        }

    /**
     * Whether the vehicle is currently moving
     */
    val isMoving: Boolean
        get() = speedKmh > 0.5f

    /**
     * Whether the vehicle is charging (based on negative current while plugged)
     * Note: This is inferred from current flow direction, not a direct signal
     */
    val isCharging: Boolean
        get() = batteryCurrent < -1f // Negative current = charging

    companion object {
        val EMPTY = TeslaVehicleState()

        /**
         * Merge an incoming state update into the current state.
         * Only non-default values from [update] are applied.
         */
        fun merge(current: TeslaVehicleState, update: PartialUpdate): TeslaVehicleState {
            return current.copy(
                speedKmh = update.speedKmh ?: current.speedKmh,
                acceleratorPedal = update.acceleratorPedal ?: current.acceleratorPedal,
                brakePedalOn = update.brakePedalOn ?: current.brakePedalOn,
                frontPowerKw = update.frontPowerKw ?: current.frontPowerKw,
                rearPowerKw = update.rearPowerKw ?: current.rearPowerKw,
                frontTorqueNm = update.frontTorqueNm ?: current.frontTorqueNm,
                rearTorqueNm = update.rearTorqueNm ?: current.rearTorqueNm,
                batteryVoltage = update.batteryVoltage ?: current.batteryVoltage,
                batteryCurrent = update.batteryCurrent ?: current.batteryCurrent,
                socPercent = update.socPercent ?: current.socPercent,
                odometerKm = update.odometerKm ?: current.odometerKm,
                batteryTempC = update.batteryTempC ?: current.batteryTempC,
                outsideTempC = update.outsideTempC ?: current.outsideTempC,
                timestamp = System.currentTimeMillis()
            )
        }
    }
}

/**
 * Partial update for incremental state changes.
 * Only fields that are present (non-null) will be updated.
 */
data class PartialUpdate(
    val speedKmh: Float? = null,
    val acceleratorPedal: Float? = null,
    val brakePedalOn: Boolean? = null,
    val frontPowerKw: Float? = null,
    val rearPowerKw: Float? = null,
    val frontTorqueNm: Float? = null,
    val rearTorqueNm: Float? = null,
    val batteryVoltage: Float? = null,
    val batteryCurrent: Float? = null,
    val socPercent: Float? = null,
    val odometerKm: Float? = null,
    val batteryTempC: Float? = null,
    val outsideTempC: Float? = null
) {
    fun toVehicleState(): TeslaVehicleState = TeslaVehicleState.merge(TeslaVehicleState.EMPTY, this)
}

/**
 * Converts parsed ELM327 protocol frame data into a PartialUpdate.
 */
fun Elm327Protocol.ParsedFrame.toPartialUpdate(): PartialUpdate? {
    return when (this.id) {
        0x118 -> {
            val accelerator = values["acceleratorPedal"] as? Float
            val brake = values["brakePedalOn"] as? Boolean
            if (accelerator != null || brake != null) {
                PartialUpdate(acceleratorPedal = accelerator, brakePedalOn = brake)
            } else null
        }
        0x132 -> {
            val voltage = values["batteryVoltage"] as? Float
            val current = values["batteryCurrent"] as? Float
            if (voltage != null || current != null) {
                PartialUpdate(batteryVoltage = voltage, batteryCurrent = current)
            } else null
        }
        0x154 -> {
            val torque = values["rearTorqueNm"] as? Float
            torque?.let { PartialUpdate(rearTorqueNm = it) }
        }
        0x1D4 -> {
            val torque = values["frontTorqueNm"] as? Float
            torque?.let { PartialUpdate(frontTorqueNm = it) }
        }
        0x212 -> {
            val temp = values["batteryTempC"] as? Float
            temp?.let { PartialUpdate(batteryTempC = it) }
        }
        0x257 -> {
            val speed = values["speedKmh"] as? Float
            speed?.let { PartialUpdate(speedKmh = it) }
        }
        0x266 -> {
            val power = values["rearPowerKw"] as? Float
            power?.let { PartialUpdate(rearPowerKw = it) }
        }
        0x2E5 -> {
            val power = values["frontPowerKw"] as? Float
            power?.let { PartialUpdate(frontPowerKw = it) }
        }
        0x292 -> {
            val soc = values["socPercent"] as? Float
            soc?.let { PartialUpdate(socPercent = it.coerceIn(0f, 100f)) }
        }
        0x321 -> {
            val temp = values["outsideTempC"] as? Float
            temp?.let { PartialUpdate(outsideTempC = it) }
        }
        0x3B6 -> {
            val odo = values["odometerKm"] as? Float
            odo?.let { PartialUpdate(odometerKm = it) }
        }
        else -> null
    }
}