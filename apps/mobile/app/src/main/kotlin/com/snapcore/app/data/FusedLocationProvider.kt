package com.snapcore.app.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.snapcore.app.domain.GeoPoint
import com.snapcore.app.domain.LocationProvider
import kotlinx.coroutines.tasks.await

/**
 * Position via le Fused Location Provider des Google Play Services (ADR-031). Permission « pendant
 * l'utilisation de l'app » uniquement : précise si accordée, sinon approximative.
 */
class FusedLocationProvider(private val context: Context) : LocationProvider {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    override fun hasPermission() = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    @SuppressLint("MissingPermission") // vérifiée par hasPermission() juste avant
    override suspend fun current(): GeoPoint? {
        if (!hasPermission()) return null
        val priority = if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        val cancellation = CancellationTokenSource()
        return try {
            val location = client.getCurrentLocation(priority, cancellation.token).await() ?: client.lastLocation.await()
            location?.let { GeoPoint(it.latitude, it.longitude) }
        } finally {
            cancellation.cancel()
        }
    }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
