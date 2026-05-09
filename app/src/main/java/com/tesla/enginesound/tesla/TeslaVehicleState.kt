package com.tesla.enginesound.tesla

import com.tesla.enginesound.ble.PartialUpdate

/**
 * Represents the current state of a Tesla vehicle, parsed from CAN bus frames.
 *
 * @param speedKmh Vehicle speed in km/h
 * @param acceleratorPedal Accelerator pedal position (0–100%)
 * @param brakePedalOn true if brake pedal is pressed
 * @param frontPowerKw Front motor power in kW (signed)
 * @param rearPowerKw Rear motor power in kW (signed)
 * @param frontTorqueNm Front motor torque in Nm
 * @param rearTorqueNm Rear motor torque in Nm
 * @param batteryVoltage Battery voltage in V
 * @param batteryCurrent Battery current in A (positive = charging)
 * @param socPercent State of charge (0–100%)
 * @param odometerKm Odometer reading in km
 * @param batteryTempC Battery temperature in °C
 * @param outsideTempC Outside ambient temperature in °C
 * @param timestamp Monotonic timestamp when this frame was received
 */
data class TeslaVehicleState(
    val speedKmh: Float = 0f,
    val acceleratorPedal: Float = 0f,
    val brakePedalOn: Boolean = false,
    val frontPowerKw: Float = 0f,
    val rearPowerKw: Float = 0f,
    val frontTorqueNm: Float = 0f,
    val rearTorqueNm: Float = 0f,
    val batteryVoltage: Float = 0f,
    val batteryCurrent: Float = 0f,
    val socPercent: Float = 0f,
    val odometerKm: Float = 0f,
    val batteryTempC: Float = 0f,
    val outsideTempC: Float = 0f,
    val timestamp: Long = System.currentTimeMillis()
) {
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
