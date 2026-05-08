package com.tesla.enginesound.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tesla.enginesound.ui.components.DataCard
import com.tesla.enginesound.ui.theme.RpmGreen
import com.tesla.enginesound.ui.theme.RpmRed
import com.tesla.enginesound.ui.theme.TeslaDark
import com.tesla.enginesound.ui.theme.TeslaGray
import com.tesla.enginesound.ui.theme.TeslaGrayLight
import com.tesla.enginesound.ui.theme.TeslaRed
import com.tesla.enginesound.ui.theme.TeslaWhite
import com.tesla.enginesound.ui.viewmodel.EnginePreset
import com.tesla.enginesound.ui.viewmodel.EngineViewModel
import com.tesla.enginesound.ui.viewmodel.TeslaVehicleState

@Composable
fun DashboardScreen(
    viewModel: EngineViewModel,
    vehicleState: TeslaVehicleState,
    engineRpm: Float,
    engineThrottle: Float,
    isConnected: Boolean
) {
    val enginePreset by viewModel.enginePreset.collectAsState()
    val cabinFilterEnabled by viewModel.cabinFilterEnabled.collectAsState()
    val volume by viewModel.volume.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TeslaDark)
            .padding(16.dp)
    ) {
        // Top bar
        TopBar(isConnected = isConnected)

        Spacer(modifier = Modifier.height(16.dp))

        // Center RPM Gauge
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            RpmGaugeLarge(rpm = engineRpm)
        }

        // Speed display
        Text(
            text = "${vehicleState.speed}",
            color = TeslaWhite,
            fontSize = 72.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Text(
            text = "km/h",
            color = TeslaGrayLight,
            fontSize = 18.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Left/Right panels
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left panel - Battery info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BatterySocDisplay(soc = vehicleState.batterySoc)
                DataCard(
                    title = "Battery Temp",
                    value = "${vehicleState.batteryTemp}°C",
                    unit = "",
                    modifier = Modifier.fillMaxWidth()
                )
                DataCard(
                    title = "Voltage",
                    value = "%.1f".format(vehicleState.batteryVoltage),
                    unit = "V",
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Right panel - Throttle/Motor info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ThrottleBar(throttle = engineThrottle)
                DataCard(
                    title = "Motor Power",
                    value = "%.0f".format(vehicleState.motorPower),
                    unit = "kW",
                    modifier = Modifier.fillMaxWidth()
                )
                DataCard(
                    title = "Torque",
                    value = "%.0f".format(vehicleState.torque),
                    unit = "Nm",
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Bottom bar
        BottomBar(
            enginePreset = enginePreset,
            onPresetSelected = { viewModel.setEnginePreset(it) },
            cabinFilterEnabled = cabinFilterEnabled,
            onCabinFilterToggle = { viewModel.toggleCabinFilter() },
            volume = volume,
            onVolumeChange = { viewModel.setVolume(it) }
        )
    }
}

@Composable
private fun TopBar(isConnected: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Tesla Engine Sound",
            color = TeslaWhite,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (isConnected) "BLE" else "Disconnected",
                color = TeslaGrayLight,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (isConnected) RpmGreen else RpmRed)
            )
        }
    }
}

@Composable
private fun RpmGaugeLarge(rpm: Float) {
    val animatedRpm by androidx.compose.animation.core.animateFloatAsState(
        targetValue = rpm.coerceIn(0f, 7000f),
        animationSpec = androidx.compose.animation.core.tween(300)
    )

    Box(
        modifier = Modifier.size(width = 280.dp, height = 160.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerX = size.width / 2
            val centerY = size.height * 0.85f
            val radius = size.width / 2 - 20f

            // Draw arc background
            drawArc(
                color = TeslaGray,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(centerX - radius, centerY - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = 20f, cap = StrokeCap.Round)
            )

            // Green zone
            drawArc(
                color = RpmGreen,
                startAngle = 180f,
                sweepAngle = 180f * (3000f / 7000f),
                useCenter = false,
                topLeft = Offset(centerX - radius, centerY - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = 16f, cap = StrokeCap.Round)
            )

            // Yellow zone
            drawArc(
                color = androidx.compose.ui.graphics.Color(0xFFFFEA00),
                startAngle = 180f + (180f * (3000f / 7000f)),
                sweepAngle = 180f * (2000f / 7000f),
                useCenter = false,
                topLeft = Offset(centerX - radius, centerY - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = 16f, cap = StrokeCap.Round)
            )

            // Red zone
            drawArc(
                color = RpmRed,
                startAngle = 180f + (180f * (5000f / 7000f)),
                sweepAngle = 180f * (2000f / 7000f),
                useCenter = false,
                topLeft = Offset(centerX - radius, centerY - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = 16f, cap = StrokeCap.Round)
            )

            // Tick marks
            for (i in 0..7) {
                val angle = Math.toRadians((180 + (180.0 * i / 7)).toDouble())
                val innerRadius = radius - 25f
                val outerRadius = radius + 5f
                drawLine(
                    color = TeslaWhite,
                    start = Offset(
                        centerX + (innerRadius * kotlin.math.cos(angle)).toFloat(),
                        centerY + (innerRadius * kotlin.math.sin(angle)).toFloat()
                    ),
                    end = Offset(
                        centerX + (outerRadius * kotlin.math.cos(angle)).toFloat(),
                        centerY + (outerRadius * kotlin.math.sin(angle)).toFloat()
                    ),
                    strokeWidth = 3f,
                    cap = StrokeCap.Round
                )
            }

            // Needle
            val needleAngle = 180 + (180.0 * animatedRpm / 7000f)
            val needleRad = Math.toRadians(needleAngle)
            val needleLength = radius - 25f
            val needleTip = Offset(
                centerX + (needleLength * kotlin.math.cos(needleRad)).toFloat(),
                centerY + (needleLength * kotlin.math.sin(needleRad)).toFloat()
            )

            // Glow at high RPM
            if (animatedRpm > 5000f) {
                drawLine(
                    color = RpmRed.copy(alpha = 0.3f),
                    start = Offset(centerX, centerY),
                    end = needleTip,
                    strokeWidth = 25f,
                    cap = StrokeCap.Round
                )
            }

            drawLine(
                color = TeslaWhite,
                start = Offset(centerX, centerY),
                end = needleTip,
                strokeWidth = 5f,
                cap = StrokeCap.Round
            )

            // Center cap
            drawCircle(color = TeslaDark, radius = 18f, center = Offset(centerX, centerY))
            drawCircle(color = TeslaWhite, radius = 14f, center = Offset(centerX, centerY))
            drawCircle(color = RpmRed, radius = 8f, center = Offset(centerX, centerY))
        }

        // RPM text
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "${animatedRpm.toInt()}",
                color = TeslaWhite,
                fontSize = 42.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "x1000 RPM",
                color = TeslaGrayLight,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun BatterySocDisplay(soc: Int) {
    val animatedSoc by androidx.compose.animation.core.animateFloatAsState(
        targetValue = soc.toFloat(),
        animationSpec = androidx.compose.animation.core.tween(300)
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(TeslaGray),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(80.dp)) {
            drawArc(
                color = TeslaDark,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                style = Stroke(width = 10f, cap = StrokeCap.Round)
            )
            drawArc(
                brush = Brush.sweepGradient(
                    colors = listOf(RpmGreen, RpmGreen, RpmYellow, RpmRed)
                ),
                startAngle = 135f,
                sweepAngle = 270f * (animatedSoc / 100f),
                useCenter = false,
                style = Stroke(width = 10f, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "${soc}%",
                color = TeslaWhite,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "SOC",
                color = TeslaGrayLight,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun ThrottleBar(throttle: Float) {
    val animatedThrottle by androidx.compose.animation.core.animateFloatAsState(
        targetValue = throttle,
        animationSpec = androidx.compose.animation.core.tween(100)
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(TeslaGray)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.Bottom
        ) {
            repeat(10) { index ->
                val threshold = (index + 1) * 10f
                val fillHeight = if (animatedThrottle >= threshold) {
                    1f
                } else if (animatedThrottle > (index * 10f)) {
                    (animatedThrottle - index * 10f) / 10f
                } else 0f

                val color = when {
                    threshold <= 30f -> RpmGreen
                    threshold <= 60f -> RpmGreen
                    threshold <= 80f -> androidx.compose.ui.graphics.Color(0xFFFFEA00)
                    else -> RpmRed
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(fillHeight.coerceIn(0f, 1f))
                        .padding(horizontal = 2.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(color.copy(alpha = 0.3f + 0.7f * fillHeight))
                )
            }
        }
        Text(
            text = "Throttle: ${throttle.toInt()}%",
            color = TeslaWhite,
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

@Composable
private fun BottomBar(
    enginePreset: EnginePreset,
    onPresetSelected: (EnginePreset) -> Unit,
    cabinFilterEnabled: Boolean,
    onCabinFilterToggle: () -> Unit,
    volume: Float,
    onVolumeChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(TeslaGray)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Engine preset selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            EnginePreset.entries.forEach { preset ->
                Button(
                    onClick = { onPresetSelected(preset) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (enginePreset == preset) TeslaRed else TeslaDark,
                        contentColor = if (enginePreset == preset) TeslaWhite else TeslaGrayLight
                    ),
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = preset.displayName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Cabin filter + Volume
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "Cabin Filter",
                    color = TeslaGrayLight,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = cabinFilterEnabled,
                    onCheckedChange = { onCabinFilterToggle() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = TeslaWhite,
                        checkedTrackColor = TeslaRed,
                        uncheckedThumbColor = TeslaGrayLight,
                        uncheckedTrackColor = TeslaDark
                    )
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Text(text = "Vol", color = TeslaGrayLight, fontSize = 14.sp)
                Slider(
                    value = volume,
                    onValueChange = onVolumeChange,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = TeslaRed,
                        activeTrackColor = TeslaRed,
                        inactiveTrackColor = TeslaDark
                    )
                )
                Text(
                    text = "${(volume * 100).toInt()}%",
                    color = TeslaWhite,
                    fontSize = 14.sp,
                    modifier = Modifier.width(40.dp)
                )
            }
        }
    }
}
