package com.tesla.enginesound

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tesla.enginesound.ui.viewmodel.EngineViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel: EngineViewModel = viewModel()
            val connectionState by viewModel.connectionState.collectAsState()
            val discoveredDevices by viewModel.discoveredDevices.collectAsState()

            val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
            } else {
                arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_FINE_LOCATION)
            }
            var hasPerm by remember { mutableStateOf(false) }
            val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> hasPerm = r.values.all { it } }

            val paired = remember(hasPerm) { if (hasPerm) viewModel.pairedDevices else emptyList() }

            Column(
                modifier = Modifier.fillMaxSize().background(Color(0xFF121212)).padding(16.dp).verticalScroll(rememberScrollState())
            ) {
                Text("EV Engine", color = Color.White, fontSize = 24.sp)
                Spacer(Modifier.height(8.dp))
                Text("State: ${connectionState::class.simpleName}", color = Color(0xFF00E676), fontSize = 14.sp)
                Spacer(Modifier.height(16.dp))

                if (!hasPerm) {
                    Button(onClick = { permLauncher.launch(perms) }) { Text("Grant Permissions") }
                } else {
                    Button(onClick = { viewModel.startScan() }) { Text("SCAN") }
                }

                if (hasPerm && paired.isNotEmpty()) {
                    Spacer(Modifier.height(24.dp))
                    Text("Paired Devices", color = Color(0xFF4FC3F7), fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    paired.forEach { device ->
                        Text(
                            "${device.name} (${device.address})", color = Color.White, fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Color(0xFF2C2C2C)).padding(12.dp).clickable { viewModel.connect(device) }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("Discovered: ${discoveredDevices.size}", color = Color(0xFF00E676), fontSize = 14.sp)
                discoveredDevices.forEach { device ->
                    Text("${device.name.ifEmpty { "?" }} (${device.address})", color = Color.White, fontSize = 13.sp)
                }
            }
        }
    }
}
