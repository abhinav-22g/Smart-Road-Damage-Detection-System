package com.example.smartroaddamage.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat

class PhoneLocationTracker(private val context: Context) {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private var listener: LocationListener? = null

    fun hasPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    fun start(onLocation: (Location) -> Unit) {
        if (!hasPermission()) return
        val manager = locationManager ?: return
        stop()

        val newListener = LocationListener { location ->
            if (location.latitude == 0.0 && location.longitude == 0.0) return@LocationListener
            onLocation(location)
        }
        listener = newListener

        val providers = buildList {
            if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
            if (manager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) add(LocationManager.PASSIVE_PROVIDER)
        }.distinct()

        providers.forEach { provider ->
            manager.requestLocationUpdates(
                provider,
                1000L,
                0f,
                newListener,
                Looper.getMainLooper()
            )
        }

        providers.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }?.let(onLocation)
    }

    fun stop() {
        val manager = locationManager ?: return
        val currentListener = listener ?: return
        runCatching { manager.removeUpdates(currentListener) }
        listener = null
    }
}
