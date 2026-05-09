package com.tesla.enginesound.ble

import com.tesla.enginesound.tesla.TeslaVehicleState

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
