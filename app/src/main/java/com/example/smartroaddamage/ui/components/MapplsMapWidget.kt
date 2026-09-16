package com.example.smartroaddamage.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.smartroaddamage.data.PotholePoint
import com.example.smartroaddamage.utils.TtsManager
import com.mappls.sdk.maps.MapView
import com.mappls.sdk.maps.MapplsMap
import com.mappls.sdk.maps.MapplsMapOptions
import com.mappls.sdk.maps.OnMapReadyCallback
import com.mappls.sdk.maps.Style
import com.mappls.sdk.maps.camera.CameraUpdateFactory
import com.mappls.sdk.maps.geometry.LatLng
import com.mappls.sdk.maps.annotations.MarkerOptions
import com.mappls.sdk.maps.annotations.PolylineOptions
import com.mappls.sdk.maps.location.LocationComponentActivationOptions
import com.mappls.sdk.maps.location.modes.CameraMode
import com.mappls.sdk.maps.location.modes.RenderMode

/**
 * Mappls map widget.
 *
 * In DEVICE_MONITOR / idle mode: shows a clean map centered on GPS.
 * In DRIVING mode: draws the selected route polyline with RED danger segments
 *   where the matched Firestore potholes lie, and drops a marker for each one.
 *
 * @param lat           GPS latitude from ESP32 (0.0 = no fix)
 * @param lng           GPS longitude from ESP32 (0.0 = no fix)
 * @param routePoints   Decoded polyline from the selected MapplsRoute (null = no route yet)
 * @param potholePoints Potholes that matched this route from Firestore (null = none)
 */
@Composable
fun MapplsMapWidget(
    lat: Double = 0.0,
    lng: Double = 0.0,
    routePoints: List<LatLng>? = null,
    potholePoints: List<PotholePoint>? = null,
    ttsManager: TtsManager? = null,
    recenterTrigger: Int = 0,
    modifier: Modifier = Modifier
) {
    val context        = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted -> hasLocationPermission = isGranted }

    LaunchedEffect(Unit) {
        if (!hasLocationPermission) permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    val mapView = remember {
        MapView(context, MapplsMapOptions.createFromAttributes(context, null).textureMode(true))
            .apply { onCreate(Bundle()) }
    }

    var mapRef: MapplsMap? by remember { mutableStateOf(null) }
    var mapReady by remember { mutableStateOf(false) }

    // ── Camera follows ESP32 GPS ─────────────────────────────────────────────
    val hasValidFix = lat != 0.0 && lng != 0.0
    LaunchedEffect(lat, lng) {
        val map = mapRef ?: return@LaunchedEffect
        if (!mapReady || !hasValidFix) return@LaunchedEffect
        
        // Only hijack the camera to follow GPS if we aren't previewing a route
        if (routePoints == null) {
            map.animateCamera(CameraUpdateFactory.newLatLng(LatLng(lat, lng)))
        }
        
        map.locationComponent.forceLocationUpdate(
            Location("esp32_sensor").apply {
                latitude  = lat; longitude = lng; accuracy = 1f
            }
        )
    }

    LaunchedEffect(lat, lng, potholePoints, ttsManager) {
        if (!hasValidFix || potholePoints.isNullOrEmpty() || ttsManager == null) return@LaunchedEffect

        val closestHazard = potholePoints.minByOrNull { pothole ->
            haversineM(lat, lng, pothole.lat, pothole.lng)
        } ?: return@LaunchedEffect

        val distanceMeters = haversineM(lat, lng, closestHazard.lat, closestHazard.lng)
        if (distanceMeters <= HAZARD_TTS_DISTANCE_M) {
            ttsManager.speak(
                hazardType = closestHazard.dominantLabel,
                distanceMeters = distanceMeters.toInt()
            )
        }
    }

    // ── Re-draw route when routePoints or potholePoints change ───────────────
    LaunchedEffect(routePoints, potholePoints, mapReady) {
        val map = mapRef ?: return@LaunchedEffect
        if (!mapReady) return@LaunchedEffect
        if (!routePoints.isNullOrEmpty()) {
            drawRouteWithPotholes(map, routePoints, potholePoints ?: emptyList())
            
            // Pan camera to the true starting origin of the generated route
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(routePoints.first(), 14.5))
        }
    }

    LaunchedEffect(recenterTrigger, lat, lng, mapReady) {
        val map = mapRef ?: return@LaunchedEffect
        if (!mapReady || !hasValidFix || recenterTrigger == 0) return@LaunchedEffect
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), 17.0))
    }

    // ── Lifecycle bridge ─────────────────────────────────────────────────────
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START   -> mapView.onStart()
                Lifecycle.Event.ON_RESUME  -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE   -> mapView.onPause()
                Lifecycle.Event.ON_STOP    -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ── AndroidView factory ───────────────────────────────────────────────────
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory  = { mapView },
        update   = { view ->
            if (!mapReady) {
                view.getMapAsync(object : OnMapReadyCallback {
                    override fun onMapReady(map: MapplsMap) {
                        val styles      = map.getMapplsAvailableStyles()
                        val styleName   = styles.firstOrNull {
                            it.name.contains("satellite", ignoreCase = true)
                        }?.name ?: "standard_day"

                        map.setMapplsStyle(styleName, object : Style.OnStyleLoaded {
                            override fun onStyleLoaded(style: Style) {
                                // Initial camera
                                if (!routePoints.isNullOrEmpty()) {
                                    // Let LaunchedEffect handle panning to the route smoothly
                                } else if (hasValidFix) {
                                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), 17.0))
                                } else {
                                    map.moveCamera(CameraUpdateFactory.zoomTo(15.0))
                                }

                                // Blue phone location dot (no camera takeover)
                                if (hasLocationPermission) enableLocationDot(context, style, map)

                                mapRef   = map
                                mapReady = true
                            }
                        })
                    }
                    override fun onMapError(p0: Int, p1: String?) {
                        android.util.Log.e("MapplsMapWidget", "Map error $p0: $p1")
                    }
                })
            }
        }
    )
}

// ────────────────────────────────────────────────────────────────────────────
// Route drawing — real polyline from Mappls, RED danger at potholes
// ────────────────────────────────────────────────────────────────────────────

private fun drawRouteWithPotholes(
    map: MapplsMap,
    route: List<LatLng>,
    potholes: List<PotholePoint>
) {
    // Build a set of route segment indices that are "near" a pothole
    val potholeLatLngs = potholes.map { LatLng(it.lat, it.lng) }

    // Drop existing route annotations (markers + polylines added by us)
    // Note: Mappls SDK does not have removeAll cleanly, so we track and clear
    try { map.clear() } catch (e: Exception) { /* no-op if map is still loading */ }

    // Draw route segment by segment
    for (i in 1 until route.size) {
        val p1 = route[i - 1]
        val p2 = route[i]

        // Check if either endpoint of this segment is within 60m of any pothole
        val isDanger = potholeLatLngs.any { ph ->
            minOf(haversineM(p1.latitude, p1.longitude, ph.latitude, ph.longitude),
                  haversineM(p2.latitude, p2.longitude, ph.latitude, ph.longitude)) <= 60.0
        }

        val color = if (isDanger) android.graphics.Color.RED
                    else android.graphics.Color.parseColor("#38BDF8")
        val width = if (isDanger) 7f else 4f

        map.addPolyline(
            PolylineOptions().add(p1, p2).color(color).width(width)
        )
    }

    // Drop a marker for every matched pothole
    potholes.forEach { p ->
        try {
            map.addMarker(
                MarkerOptions()
                    .position(LatLng(p.lat, p.lng))
                    .title("${p.dominantLabel} - Severity ${p.severity}")
                    .snippet(
                        "Confidence: ${"%.0f".format(p.confidence * 100)}% - " +
                            "Reports: ${p.reportCount} - Hazard: ${"%.1f".format(p.hazardScore)}"
                    )
            )
        } catch (e: Exception) { /* skip bad coords */ }
    }

    android.util.Log.d("MapplsMapWidget",
        "Drew ${route.size - 1} segments, ${potholes.size} pothole markers")
}

private fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val R    = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a    = Math.sin(dLat / 2).let { it * it } +
               Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
               Math.sin(dLng / 2).let { it * it }
    return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}

private const val HAZARD_TTS_DISTANCE_M = 60.0

// ────────────────────────────────────────────────────────────────────────────
// Blue location dot — phone position, no camera hijack
// ────────────────────────────────────────────────────────────────────────────

@Suppress("MissingPermission")
private fun enableLocationDot(context: Context, style: Style, map: MapplsMap) {
    try {
        map.locationComponent.apply {
            activateLocationComponent(
                LocationComponentActivationOptions.builder(context, style).build()
            )
            isLocationComponentEnabled = true
            cameraMode = CameraMode.NONE
            renderMode = RenderMode.COMPASS
        }
    } catch (e: Exception) {
        android.util.Log.e("MapplsMapWidget", "enableLocationDot failed: ${e.message}")
    }
}
