package com.tesla.enginesound.tesla

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Maps live Tesla vehicle state (speed, pedal, power) to a virtual engine RPM
 * and throttle position for driving a synthesized engine sound.
 *
 * ## Design Goals
 *
 * - **Idle RPM**: Always maintain at least 800 RPM so the engine sound never
 *   disappears at a stoplight.
 * - **Speed-proportional base**: Motor sound (and thus engine simulation) rises
 *   with vehicle speed up to ~6000 RPM at 180 km/h.
 * - **Throttle multiplier**: Pressing the accelerator pedal amplifies the RPM
 *   target, giving a responsive, "revving" feel without needing exact torque curves.
 * - **Power boost**: High motor power output (+100 kW+) adds a bonus RPM to
 *   simulate aggressive acceleration sound.
 * - **Brake damping**: When brakes are applied, RPM collapses quickly to near
 *   idle (simulating deceleration), but clamps to 800 RPM minimum.
 * - **Smoothing**: RPM changes are exponentially smoothed (EMA) to prevent
 *   audible discontinuities from frame-rate jitter or transient spikes.
 * - **Throttle output**: Passed through directly from accelerator pedal (0–1).
 *
 * @param smoothingFactor EMA coefficient for RPM smoothing (0–1, higher = faster response).
 *                        Default 0.05 means each update moves 5% toward target RPM.
 */
class RpmMapper(
    private val smoothingFactor: Float = 0.05f
) {

    /**
     * Input for the engine sound synthesizer.
     *
     * @param rpm Virtual engine RPM (800–6800)
     * @param throttle Throttle position 0.0 (closed) – 1.0 (full)
     * @param isBraking true when the brake pedal is pressed
     */
    data class EngineInput(
        val rpm: Float,
        val throttle: Float,
        val isBraking: Boolean
    )

    /** Persisted smoothed RPM between calls to [mapToEngineInput]. */
    @Volatile
    private var smoothRpm: Float = BASE_IDLE_RPM

    /**
     * Convert a live TeslaVehicleState into an EngineInput for the sound engine.
     *
     * This method is stateless except for [smoothRpm], making it safe to call
     * from a BLE callback thread or audio thread concurrently.
     *
     * ## RPM Mapping Algorithm
     *
     * ```
     * 1. baseRpm   = 800 + (speedKmh / 180) × 5200   // 800 RPM @ 0 km/h → 6000 RPM @ 180 km/h
     * 2. throttleFactor = 0.3 + 0.7 × (pedal / 100) // 0.3–1.0 range, amplifies motor response
     * 3. targetRpm = 800 + (baseRpm − 800) × throttleFactor
     * 4. powerBoost = min(|frontPower| + |rearPower| / 200, 1) × 1000   // 0–1000 RPM bonus
     * 5. finalRpm  = targetRpm + powerBoost
     * 6. if braking → finalRpm = max(800, finalRpm × 0.3)
     * 7. finalRpm  = clamp(finalRpm, 800, 6800)
     * 8. smoothRpm += 0.05 × (finalRpm − smoothRpm)   // EMA smoothing
     * 9. throttle  = pedal / 100.0
     * ```
     *
     * @param state Current TeslaVehicleState (typically from the latest CAN frame)
     * @return EngineInput with mapped rpm, throttle, and braking flag
     */
    @Synchronized
    fun mapToEngineInput(state: TeslaVehicleState): EngineInput {
        // ── 1. Base RPM from speed ───────────────────────────────────────────
        // At 0 km/h idle at 800 RPM; at 180 km/h reach ~6000 RPM.
        val baseRpm = BASE_IDLE_RPM + (state.speedKmh / 180f) * (MAX_RPM - BASE_IDLE_RPM)

        // ── 2. Throttle factor ─────────────────────────────────────────────
        // Even at 0% pedal we hear some motor sound (0.3×base); full pedal uses full base.
        val throttleFactor = 0.3f + 0.7f * (state.acceleratorPedal / 100f)
        val targetRpm = BASE_IDLE_RPM + (baseRpm - BASE_IDLE_RPM) * throttleFactor

        // ── 3. Power boost ─────────────────────────────────────────────────
        // High motor power = aggressive acceleration → raise RPM for more urgency.
        // At 200 kW total combined power the boost saturates at +1000 RPM.
        val totalPower = abs(state.frontPowerKw) + abs(state.rearPowerKw)
        val powerBoost = min(totalPower / 200f, 1f) * 1000f
        val finalRpm = targetRpm + powerBoost

        // ── 4. Brake damping ────────────────────────────────────────────────
        // When braking, collapse RPM toward idle but don't let it disappear.
        val brakedRpm = if (state.brakePedalOn) {
            max(BASE_IDLE_RPM, finalRpm * BRAKE_DAMPING)
        } else {
            finalRpm
        }

        // ── 5. Clamp ───────────────────────────────────────────────────────
        val clampedRpm = brakedRpm.coerceIn(BASE_IDLE_RPM, MAX_RPM)

        // ── 6. Exponential smoothing ───────────────────────────────────────
        // EMA avoids jarring RPM jumps when state updates arrive at irregular intervals.
        smoothRpm += smoothingFactor * (clampedRpm - smoothRpm)

        // ── 7. Throttle pass-through ───────────────────────────────────────
        val throttle = (state.acceleratorPedal / 100f).coerceIn(0f, 1f)

        return EngineInput(
            rpm = smoothRpm,
            throttle = throttle,
            isBraking = state.brakePedalOn
        )
    }

    /**
     * Reset smoothed RPM back to idle.
     * Call this when the audio engine is paused or vehicle goes to sleep.
     */
    @Synchronized
    fun reset() {
        smoothRpm = BASE_IDLE_RPM
    }

    companion object {
        /** Minimum RPM even at full stop (engine doesn't "stall") */
        const val BASE_IDLE_RPM = 800f

        /** Maximum RPM at redline / top speed */
        const val MAX_RPM = 6800f

        /** RPM multiplier when brake is pressed (collapses to 30% of target) */
        const val BRAKE_DAMPING = 0.3f
    }
}
