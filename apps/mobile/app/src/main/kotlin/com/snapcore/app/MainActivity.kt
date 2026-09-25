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
    // Écran demandé par une notification touchée (app fermée ou déjà ouverte).
    private var launchTarget by mutableStateOf<LaunchTarget?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        launchTarget = Notifications.launchTargetOf(intent)
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
        Notifications.launchTargetOf(intent)?.let { launchTarget = it }
    }
}
