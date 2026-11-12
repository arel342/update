package com.nodelix.coreos.core

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.nodelix.coreos.ui.theme.NodelixTheme

/**
 * Single-activity host extending [FragmentActivity] so AndroidX [androidx.biometric.BiometricPrompt]
 * can authorize hardware-backed Android Keystore ECDSA signatures natively.
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.parseColor("#101720"))
        )
        super.onCreate(savedInstanceState)

        val appContainer = (application as App).container
        appContainer.networkMonitor.startMonitoring()

        setContent {
            rememberNodelixAppState(appContainer)
            NodelixTheme {
                NodelixRootApp(container = appContainer)
            }
        }
    }
}
