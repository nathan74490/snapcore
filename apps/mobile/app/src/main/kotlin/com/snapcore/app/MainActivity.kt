package com.snapcore.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.snapcore.app.domain.LaunchTarget
import com.snapcore.app.push.Notifications
import com.snapcore.app.ui.SnapCoreNavHost
import com.snapcore.app.ui.theme.SnapCoreTheme

class MainActivity : ComponentActivity() {
    // Écran demandé par une notification touchée ou par le retour du paiement (app fermée ou déjà ouverte).
    private var launchTarget by mutableStateOf<LaunchTarget?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        launchTarget = launchTargetOf(intent)
        setContent {
            SnapCoreTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SnapCoreNavHost(launchTarget = launchTarget, onLaunchTargetHandled = { launchTarget = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        launchTargetOf(intent)?.let { launchTarget = it }
    }

    // snapcore://snapcoreplus : page de succès du paiement RevenueCat (ADR-025).
    private fun launchTargetOf(intent: Intent?): LaunchTarget? =
        if (intent?.data?.scheme == "snapcore" && intent.data?.host == "snapcoreplus") LaunchTarget.SNAPCORE_PLUS
        else Notifications.launchTargetOf(intent)
}
