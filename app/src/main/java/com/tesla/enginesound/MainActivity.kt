package com.tesla.enginesound

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tesla.enginesound.ui.viewmodel.ConnectionState
import com.tesla.enginesound.ui.viewmodel.EngineViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel: EngineViewModel = viewModel()
            val connectionState by viewModel.connectionState.collectAsState()
            val discoveredDevices by viewModel.discoveredDevices.collectAsState()
            val vehicleState by viewModel.vehicleState.collectAsState()
            val engineRpm by viewModel.engineRpm.collectAsState()
            val engineThrottle by viewModel.engineThrottle.collectAsState()
            val isConnected = connectionState is ConnectionState.Connected

            val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
            } else {
                arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_FINE_LOCATION)
            }
            var hasPerm by remember { mutableStateOf(false) }
            val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> hasPerm = r.values.all { it } }
            val paired = remember(hasPerm) { if (hasPerm) viewModel.pairedDevices else emptyList() }

            Column(
                modifier = Modifier.fillMaxSize().background(Color(0xFF121212)).verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("EV Engine", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (isConnected) Color(0xFF00E676) else Color(0xFFFF1744)))
                }

                // RPM Gauge
                Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                    val animRpm by animateFloatAsState(engineRpm.coerceIn(0f, 7000f), tween(300))
                    Canvas(Modifier.fillMaxSize()) {
                        val cx = size.width / 2; val cy = size.height * 0.85f; val r = size.width / 2 - 24f
                        val greenEnd = 180f * (3000f / 7000f)
                        val yellowEnd = greenEnd + 180f * (2000f / 7000f)
                        val redEnd = yellowEnd + 180f * (2000f / 7000f)
                        drawArc(color = Color(0xFF2C2C2C), startAngle = 180f, sweepAngle = 180f, useCenter = false, topLeft = Offset(cx-r, cy-r), size = Size(r*2, r*2), style = Stroke(18f))
                        drawArc(color = Color(0xFF00E676), startAngle = 180f, sweepAngle = greenEnd, useCenter = false, topLeft = Offset(cx-r, cy-r), size = Size(r*2, r*2), style = Stroke(14f))
                        drawArc(color = Color(0xFFFFEA00), startAngle = 180f + greenEnd, sweepAngle = yellowEnd - greenEnd, useCenter = false, topLeft = Offset(cx-r, cy-r), size = Size(r*2, r*2), style = Stroke(14f))
                        drawArc(color = Color(0xFFFF1744), startAngle = 180f + yellowEnd, sweepAngle = redEnd - yellowEnd, useCenter = false, topLeft = Offset(cx-r, cy-r), size = Size(r*2, r*2), style = Stroke(14f))
                        val na = Math.toRadians(180 + 180.0 * animRpm / 7000f); val nl = r - 20f
                        drawLine(Color.White, Offset(cx, cy), Offset(cx + nl*kotlin.math.cos(na).toFloat(), cy + nl*kotlin.math.sin(na).toFloat()), strokeWidth = 4f, cap = StrokeCap.Round)
                        drawCircle(Color(0xFF121212), 12f, Offset(cx, cy))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${engineRpm.toInt()}", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                        Text("RPM", color = Color(0xFF6B6B6B), fontSize = 11.sp)
                    }
                }

                // Speed
                Text("${vehicleState.speedKmh.toInt()} km/h", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))

                // Data row
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Text("SOC ${vehicleState.socPercent.toInt()}%", color = Color(0xFF00E676), fontSize = 14.sp)
                    Text("${vehicleState.batteryTempC.toInt()}°C", color = Color(0xFF4FC3F7), fontSize = 14.sp)
                    Text(String.format("%.1fV", vehicleState.batteryVoltage), color = Color(0xFF4FC3F7), fontSize = 14.sp)
                    Text("Throttle ${engineThrottle.toInt()}%", color = Color(0xFFFFEA00), fontSize = 14.sp)
                }

                HorizontalDivider(Modifier.padding(vertical = 12.dp).fillMaxWidth().padding(horizontal = 16.dp), color = Color(0xFF2C2C2C))

                // BLE Section
                Text("State: ${connectionState::class.simpleName}", color = Color(0xFF00E676), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(8.dp))

                if (!hasPerm) {
                    Button(onClick = { permLauncher.launch(perms) }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Grant Permissions") }
                } else {
                    Button(onClick = { viewModel.startScan() }, enabled = connectionState::class.simpleName != "Scanning", modifier = Modifier.padding(horizontal = 16.dp)) { Text("SCAN") }
                }

                if (hasPerm && paired.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Paired Devices", color = Color(0xFF4FC3F7), fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
                    paired.forEach { device ->
                        Text("${device.name} (${device.address})", color = Color.White, fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2C2C2C)).padding(12.dp).clickable { viewModel.connect(device) })
                    }
                }

                if (discoveredDevices.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Discovered (${discoveredDevices.size})", color = Color(0xFF00E676), fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
                    discoveredDevices.forEach { device ->
                        Text("${device.name.ifEmpty { "?" }} (${device.address})", color = Color.White, fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2C2C2C)).padding(12.dp).clickable { viewModel.connect(device) })
                    }
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}
