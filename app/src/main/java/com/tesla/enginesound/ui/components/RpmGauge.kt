package com.tesla.enginesound.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tesla.enginesound.ui.theme.RpmGreen
import com.tesla.enginesound.ui.theme.RpmRed
import com.tesla.enginesound.ui.theme.RpmYellow
import com.tesla.enginesound.ui.theme.TeslaDark
import com.tesla.enginesound.ui.theme.TeslaGray
import com.tesla.enginesound.ui.theme.TeslaGrayLight
import com.tesla.enginesound.ui.theme.TeslaWhite
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun RpmGauge(
    rpm: Float,
    modifier: Modifier = Modifier,
    maxRpm: Float = 7000f,
    minRpm: Float = 0f
) {
    val animatedRpm by animateFloatAsState(
        targetValue = rpm.coerceIn(minRpm, maxRpm),
        animationSpec = tween(durationMillis = 300),
        label = "rpm_animation"
    )

    Box(
        modifier = modifier.aspectRatio(1.6f),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            drawGaugeBackground()
            drawArcWithTickMarks(maxRpm, minRpm)
            drawNeedle(animatedRpm, maxRpm)
            drawNeedleGlow(animatedRpm, maxRpm)
        }

        // RPM text overlay
        Text(
            text = "${animatedRpm.toInt()}",
            color = TeslaWhite,
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

private fun DrawScope.drawGaugeBackground() {
    // Background arc
    drawArc(
        color = TeslaGray,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = false,
        style = Stroke(width = 30f, cap = StrokeCap.Round)
    )
}

private fun DrawScope.drawArcWithTickMarks(maxRpm: Float, minRpm: Float) {
    val startAngle = 180f
    val sweepAngle = 180f
    val radius = size.width / 2 - 40f
    val center = Offset(size.width / 2, size.height / 2)

    // Color zones
    val greenEnd = 180f + (sweepAngle * (3000f / maxRpm))
    val yellowEnd = 180f + (sweepAngle * (5000f / maxRpm))

    // Green zone (0-3000)
    drawArc(
        color = RpmGreen,
        startAngle = startAngle,
        sweepAngle = greenEnd - startAngle,
        useCenter = false,
        style = Stroke(width = 24f, cap = StrokeCap.Butt)
    )

    // Yellow zone (3000-5000)
    drawArc(
        color = RpmYellow,
        startAngle = greenEnd,
        sweepAngle = yellowEnd - greenEnd,
        useCenter = false,
        style = Stroke(width = 24f, cap = StrokeCap.Butt)
    )

    // Red zone (5000-7000)
    drawArc(
        color = RpmRed,
        startAngle = yellowEnd,
        sweepAngle = (startAngle + sweepAngle) - yellowEnd,
        useCenter = false,
        style = Stroke(width = 24f, cap = StrokeCap.Butt)
    )

    // Tick marks
    for (i in 0..7) {
        val tickValue = (maxRpm / 7 * i)
        val tickAngle = Math.toRadians((180 + (180 * tickValue / maxRpm)).toDouble())
        val innerRadius = radius - 35f
        val outerRadius = radius + 10f

        val startX = center.x + (innerRadius * cos(tickAngle)).toFloat()
        val startY = center.y + (innerRadius * sin(tickAngle)).toFloat()
        val endX = center.x + (outerRadius * cos(tickAngle)).toFloat()
        val endY = center.y + (outerRadius * sin(tickAngle)).toFloat()

        drawLine(
            color = TeslaWhite,
            start = Offset(startX, startY),
            end = Offset(endX, endY),
            strokeWidth = 4f,
            cap = StrokeCap.Round
        )
    }
}

private fun DrawScope.drawNeedle(rpm: Float, maxRpm: Float) {
    val radius = size.width / 2 - 40f
    val center = Offset(size.width / 2, size.height / 2)
    val normalizedRpm = (rpm / maxRpm).coerceIn(0f, 1f)
    val needleAngle = 180 + (180 * normalizedRpm)
    val angleRad = Math.toRadians(needleAngle.toDouble())

    val needleLength = radius - 10f
    val needleTip = Offset(
        center.x + (needleLength * cos(angleRad)).toFloat(),
        center.y + (needleLength * sin(angleRad)).toFloat()
    )

    // Needle line
    drawLine(
        color = Color.White,
        start = center,
        end = needleTip,
        strokeWidth = 6f,
        cap = StrokeCap.Round
    )

    // Center circle
    drawCircle(
        color = TeslaDark,
        radius = 20f,
        center = center
    )
    drawCircle(
        color = Color.White,
        radius = 16f,
        center = center
    )
    drawCircle(
        color = RpmRed,
        radius = 10f,
        center = center
    )
}

private fun DrawScope.drawNeedleGlow(rpm: Float, maxRpm: Float) {
    if (rpm < 5000f) return

    val radius = size.width / 2 - 40f
    val center = Offset(size.width / 2, size.height / 2)
    val normalizedRpm = (rpm / maxRpm).coerceIn(0f, 1f)
    val needleAngle = 180 + (180 * normalizedRpm)
    val angleRad = Math.toRadians(needleAngle.toDouble())
    val needleLength = radius - 10f

    val needleTip = Offset(
        center.x + (needleLength * cos(angleRad)).toFloat(),
        center.y + (needleLength * sin(angleRad)).toFloat()
    )

    // Glow effect
    drawLine(
        color = RpmRed.copy(alpha = 0.4f),
        start = center,
        end = needleTip,
        strokeWidth = 20f,
        cap = StrokeCap.Round
    )
    drawLine(
        color = RpmRed.copy(alpha = 0.2f),
        start = center,
        end = needleTip,
        strokeWidth = 35f,
        cap = StrokeCap.Round
    )
}
