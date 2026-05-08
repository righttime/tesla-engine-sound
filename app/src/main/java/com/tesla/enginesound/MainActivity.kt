package com.tesla.enginesound

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.tesla.enginesound.ui.theme.TeslaEngineSoundTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TeslaEngineSoundTheme {
                // TODO: Navigation will be added by UI subagent
            }
        }
    }
}
