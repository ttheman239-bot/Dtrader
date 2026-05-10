package com.dtrader.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.dtrader.app.ui.DtraderApp
import com.dtrader.app.ui.theme.DtraderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            DtraderTheme {
                DtraderApp()
            }
        }
    }
}
