package com.tesla.enginesound.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tesla.enginesound.ui.theme.TeslaDark
import com.tesla.enginesound.ui.theme.TeslaGray
import com.tesla.enginesound.ui.theme.TeslaGrayLight
import com.tesla.enginesound.ui.theme.TeslaRed
import com.tesla.enginesound.ui.theme.TeslaWhite
import com.tesla.enginesound.ui.viewmodel.ConnectionState
import com.tesla.enginesound.ui.viewmodel.EngineViewModel

@Composable
fun ConnectionScreen(
    viewModel: EngineViewModel,
    onDeviceSelected: () -> Unit
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val connectedDevice by viewModel.connectedDevice.collectAsState()

    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            viewModel.startScan()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TeslaDark)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Tesla Engine Sound",
            color = TeslaWhite,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 48.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "BLE Connection",
            color = TeslaGrayLight,
            fontSize = 16.sp
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Connection status indicator
        ConnectionStatusBadge(
            state = connectionState,
            deviceName = connectedDevice?.name
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Scan button
        when (connectionState) {
            is ConnectionState.Scanning -> {
                CircularProgressIndicator(
                    color = TeslaRed,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Scanning for ELM327 devices...",
                    color = TeslaWhite,
                    fontSize = 16.sp
                )
            }
            is ConnectionState.Connected -> {
                connectedDevice?.let { device ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = TeslaGray)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Connected Device",
                                color = TeslaGrayLight,
                                fontSize = 12.sp
                            )
                            Text(
                                text = device.name,
                                color = TeslaWhite,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = device.address,
                                color = TeslaGrayLight,
                                fontSize = 14.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = { viewModel.disconnect() },
                        colors = ButtonDefaults.buttonColors(containerColor = TeslaRed),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "DISCONNECT",
                            color = TeslaWhite,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onDeviceSelected,
                        colors = ButtonDefaults.buttonColors(containerColor = TeslaRed),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "GO TO DASHBOARD",
                            color = TeslaWhite,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            else -> {
                Button(
                    onClick = { permissionLauncher.launch(permissions) },
                    colors = ButtonDefaults.buttonColors(containerColor = TeslaRed),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = "SCAN",
                        color = TeslaWhite,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Device list
        if (discoveredDevices.isNotEmpty() && connectionState !is ConnectionState.Connected) {
            Text(
                text = "Available Devices",
                color = TeslaGrayLight,
                fontSize = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(discoveredDevices) { device ->
                    DeviceItem(
                        name = device.name,
                        address = device.address,
                        rssi = device.rssi,
                        onClick = {
                            if (connectionState !is ConnectionState.Connecting) {
                                viewModel.connect(device)
                            }
                        },
                        isConnecting = connectionState is ConnectionState.Connecting
                    )
                }
            }
        }

        // Permission note
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "Required: BLUETOOTH_SCAN, BLUETOOTH_CONNECT, ACCESS_FINE_LOCATION",
            color = TeslaGrayLight,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun ConnectionStatusBadge(
    state: ConnectionState,
    deviceName: String?
) {
    val (text, color) = when (state) {
        is ConnectionState.Disconnected -> "Disconnected" to Color(0xFFFF1744)
        is ConnectionState.Scanning -> "Scanning..." to Color(0xFFFFEA00)
        is ConnectionState.Connecting -> "Connecting..." to Color(0xFFFFEA00)
        is ConnectionState.Connected -> "Connected" to Color(0xFF00E676)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(TeslaGray)
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            color = TeslaWhite,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun DeviceItem(
    name: String,
    address: String,
    rssi: Int,
    onClick: () -> Unit,
    isConnecting: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isConnecting) { onClick() },
        colors = CardDefaults.cardColors(containerColor = TeslaGray),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name.ifEmpty { "Unknown Device" },
                    color = TeslaWhite,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = address,
                    color = TeslaGrayLight,
                    fontSize = 12.sp
                )
            }
            RssiIndicator(rssi = rssi)
        }
    }
}

@Composable
private fun RssiIndicator(rssi: Int) {
    val bars = when {
        rssi >= -50 -> 4
        rssi >= -60 -> 3
        rssi >= -70 -> 2
        else -> 1
    }
    val color = when (bars) {
        4 -> Color(0xFF00E676)
        3 -> Color(0xFF00E676)
        2 -> Color(0xFFFFEA00)
        else -> Color(0xFFFF1744)
    }

    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(4) { index ->
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height((12 + index * 6).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (index < bars) color else TeslaGrayLight)
            )
        }
    }
}
