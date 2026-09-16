package com.example.smartroaddamage.data

import android.util.Log
import com.mappls.sdk.geojson.Point
import com.mappls.sdk.maps.geometry.LatLng
import com.mappls.sdk.services.api.OnResponseCallback
import com.mappls.sdk.services.api.autosuggest.MapplsAutoSuggest
import com.mappls.sdk.services.api.autosuggest.MapplsAutosuggestManager
import com.mappls.sdk.services.api.autosuggest.model.AutoSuggestAtlasResponse
import com.mappls.sdk.services.api.autosuggest.model.ELocation
import com.mappls.sdk.services.api.directions.DirectionsCriteria
import com.mappls.sdk.services.api.directions.MapplsDirectionManager
import com.mappls.sdk.services.api.directions.MapplsDirections
import com.mappls.sdk.services.api.directions.models.DirectionsResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.*

/**
 * RouteRepository — Industry-standard data layer using Mappls SDK native APIs.
 *
 * Auth is handled automatically by the SDK via atlasClientId + atlasClientSecret
 * set in SmartRoadApplication. No manual HTTP or OAuth calls.
 *
 * Pipeline:
 *  1. MapplsAutosuggestManager  → resolve destination text → LatLng
 *  2. MapplsDirectionManager    → fetch candidate routes (RESOURCE_ROUTE, free-tier)
 *  3. HazardReadRepository      → fetch canonical clustered hazards in route bbox
 *  4. Haversine distance match  → hazards within 50m of each route polyline
 *  5. Score + rank              → safest route first
 *
 * Hazard data source is injected via [HazardReadRepository]:
 *  - [MockHazardReadRepository]      for UI development / offline testing
 *  - [FirestoreHazardReadRepository] for live production reads
 *
 * This object NEVER writes to Firestore.  All uploads go through
 * [FirestoreHazardWriteRepository].
 */
class RouteRepository(
    private val hazardReadRepository: HazardReadRepository = HazardReadRepositories.active
) {

    companion object {
        private const val TAG = "RouteRepository"
        private const val POTHOLE_BUFFER_METRES = 50.0
    }
    // Cache to prevent duplicate autosuggest calls during the same session
    private val suggestCache = mutableMapOf<String, List<ELocation>>()

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC ENTRY POINT
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun analyseRoutes(
        originText: String,
        originLat: Double?,
        originLng: Double?,
        originPin: String?,
        destination: String,
        destLat: Double?,
        destLng: Double?,
        destPin: String?,
        currentLat: Double,
        currentLng: Double
    ): List<RouteAnalysis> = withContext(Dispatchers.IO) {

        // 1. Resolve Origin
        val finalOriginLat: Double?
        val finalOriginLng: Double?
        val finalOriginPin: String?

        if (originText.isBlank()) {
            if (currentLat == 0.0) throw IllegalArgumentException("Waiting for GPS...")
            finalOriginLat = currentLat
            finalOriginLng = currentLng
            finalOriginPin = null
        } else if (originLat != null && originLng != null) {
            finalOriginLat = originLat
            finalOriginLng = originLng
            finalOriginPin = null
        } else if (!originPin.isNullOrBlank()) {
            finalOriginLat = null
            finalOriginLng = null
            finalOriginPin = originPin
        } else {
            val (latLng, pin) = resolveDestination(originText, currentLat, currentLng)
            if (latLng == null && pin == null) throw IllegalArgumentException(
                "Could not resolve origin: '$originText'"
            )
            finalOriginLat = latLng?.latitude
            finalOriginLng = latLng?.longitude
            finalOriginPin = pin
        }

        // 2. Resolve Destination
        val finalDestLat: Double?
        val finalDestLng: Double?
        val finalDestPin: String?

        if (destLat != null && destLng != null) {
            finalDestLat = destLat
            finalDestLng = destLng
            finalDestPin = null
        } else if (!destPin.isNullOrBlank()) {
            finalDestLat = null
            finalDestLng = null
            finalDestPin = destPin
        } else {
            val (latLng, pin) = resolveDestination(destination, currentLat, currentLng)
            if (latLng == null && pin == null) throw IllegalArgumentException(
                "Search limit reached for today. Enter destination as coordinates: lat,lng  (e.g. 17.4399,78.4983)"
            )
            finalDestLat = latLng?.latitude
            finalDestLng = latLng?.longitude
            finalDestPin = pin
        }

        val routes = fetchRoutesSdk(
            finalOriginLat, finalOriginLng, finalOriginPin,
            finalDestLat, finalDestLng, finalDestPin
        )

        if (routes.isEmpty()) throw RuntimeException(
            "No routes found. Ensure Routing API is enabled in Mappls console."
        )

        val box      = BoundingBox.from(routes.flatMap { it.polyline }, paddingDeg = 0.005)
        val potholes = hazardReadRepository.fetchHazardsInBox(box)
        Log.d(TAG, "HazardRepo: ${potholes.size} canonical clusters in bounding box")

        routes.map { matchAndScore(it, potholes) }.sortedBy { it.rankScore }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 1: Resolve destination → LatLng via Autosuggest SDK
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun resolveDestination(
        query: String,
        nearLat: Double,
        nearLng: Double
    ): Pair<LatLng?, String?> {  // returns (latLng, mapplsPin) — one will be non-null

        // Handle raw coordinate input like "17.48, 78.53"
        val coordRegex = Regex("""^(-?\d+\.?\d*),\s*(-?\d+\.?\d*)$""")
        coordRegex.matchEntire(query.trim())?.let { m ->
            val lat = m.groupValues[1].toDoubleOrNull() ?: return Pair(null, null)
            val lng = m.groupValues[2].toDoubleOrNull() ?: return Pair(null, null)
            return Pair(LatLng(lat, lng), null)
        }

        return try {
            val suggestions = fetchSuggestions(query, nearLat, nearLng)
            val first = suggestions.firstOrNull() ?: return Pair(null, null)
            
            // Try coordinates first
            val lat = first.latitude
            val lng = first.longitude
            if (lat != null && lng != null) {
                Log.d(TAG, "Resolved '$query' → coords $lat,$lng (${first.placeName})")
                return Pair(LatLng(lat, lng), null)
            }

            // Fall back to mapplsPin — pass directly to directions builder
            val pin = first.mapplsPin
            if (!pin.isNullOrBlank()) {
                Log.d(TAG, "Resolved '$query' → mapplsPin=$pin (${first.placeName})")
                return Pair(null, pin)
            }

            Log.e(TAG, "ELocation has neither coords nor mapplsPin: ${first.placeName}")
            Pair(null, null)
        } catch (e: Exception) {
            Log.e(TAG, "resolveDestination failed: ${e.message}")
            Pair(null, null)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Autosuggest — public so ViewModel can call for live dropdown
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun fetchSuggestions(
        query: String,
        nearLat: Double?,
        nearLng: Double?
    ): List<ELocation> = suspendCancellableCoroutine { cont ->
        if (query.length < 3) { cont.resume(emptyList()); return@suspendCancellableCoroutine }

        // Return cached result if available
        val cacheKey = query.trim().lowercase()
        suggestCache[cacheKey]?.let {
            Log.d(TAG, "Autosuggest cache hit for '$query'")
            cont.resume(it)
            return@suspendCancellableCoroutine
        }

        val builder = MapplsAutoSuggest.builder().query(query.trim())
        if (nearLat != null && nearLng != null && nearLat != 0.0 && nearLng != 0.0) {
            builder.setLocation(nearLat, nearLng)
        }

        val manager = MapplsAutosuggestManager.newInstance(builder.build())
        cont.invokeOnCancellation { manager.cancel() }

        manager.call(object : OnResponseCallback<AutoSuggestAtlasResponse> {
            override fun onSuccess(response: AutoSuggestAtlasResponse?) {
                val locs = response?.suggestedLocations ?: emptyList()
                if (suggestCache.size > 50) suggestCache.clear() // Prevent memory leak
                suggestCache[cacheKey] = locs
                Log.d(TAG, "Autosuggest '$query' → ${locs.size} results (cached)")
                cont.resume(locs)
            }
            override fun onError(code: Int, message: String?) {
                Log.e(TAG, "Autosuggest error $code: $message")
                if (code == 403) {
                    // Limit hit — resume with empty instead of crashing the app
                    cont.resume(emptyList())
                } else {
                    cont.resumeWithException(
                        RuntimeException("Search API error $code: ${message ?: "unknown"}. Check Mappls console → APIs & Services → Autosuggest/Atlas is enabled.")
                    )
                }
            }
        })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 2: Directions via MapplsDirectionManager
    //   - RESOURCE_ROUTE      : free tier compatible
    //   - alternatives(true)  : request up to 3 routes
    //   - GEOMETRY_POLYLINE6  : Mappls precision (1e-6)
    //   - origin/destination  : pass doubles directly (no Point class needed)
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun fetchRoutesSdk(
        originLat: Double?, originLng: Double?, originPin: String?,
        destLat: Double?, destLng: Double?, destPin: String?
    ): List<MapplsRoute> = suspendCancellableCoroutine { cont ->

        val builder = MapplsDirections.builder()
            .resource(DirectionsCriteria.RESOURCE_ROUTE)
            .profile(DirectionsCriteria.PROFILE_DRIVING)
            .alternatives(true)
            .steps(true)
            .overview(DirectionsCriteria.OVERVIEW_FULL)
            .geometries(DirectionsCriteria.GEOMETRY_POLYLINE6)

        // Origin: coords if available, mapplsPin otherwise
        when {
            originLat != null && originLng != null ->
                builder.origin(Point.fromLngLat(originLng, originLat))
            !originPin.isNullOrBlank() ->
                builder.origin(originPin)
            else -> { cont.resumeWithException(RuntimeException("No origin")); return@suspendCancellableCoroutine }
        }

        // Destination: coords if available, mapplsPin otherwise
        when {
            destLat != null && destLng != null ->
                builder.destination(Point.fromLngLat(destLng, destLat))
            !destPin.isNullOrBlank() ->
                builder.destination(destPin)  // ✅ SDK accepts 6-char mapplsPin directly
            else -> { cont.resumeWithException(RuntimeException("No destination")); return@suspendCancellableCoroutine }
        }

        val manager = MapplsDirectionManager.newInstance(builder.build())
        cont.invokeOnCancellation { manager.cancel() }

        manager.call(object : OnResponseCallback<DirectionsResponse> {
            override fun onSuccess(response: DirectionsResponse?) {
                if (response == null) { cont.resume(emptyList()); return }
                
                val routes = (response.routes() ?: emptyList()).mapIndexedNotNull { idx, r ->
                    val leg      = r.legs()?.firstOrNull() ?: return@mapIndexedNotNull null
                    val geometry = r.geometry()             ?: return@mapIndexedNotNull null
                    val decoded  = decodePolyline6(geometry)
                    if (decoded.size < 2)                     return@mapIndexedNotNull null
                    
                    MapplsRoute(
                        index       = idx,
                        durationMin = ((leg.duration() ?: 0.0) / 60.0).toInt(),
                        distanceKm  = "%.1f".format((leg.distance() ?: 0.0) / 1000.0).toFloat(),
                        polyline    = decoded
                    )
                }
                Log.d(TAG, "Direction SDK → ${routes.size} routes")
                cont.resume(routes)
            }
            
            override fun onError(code: Int, message: String?) {
                Log.e(TAG, "Direction error $code: $message")
                cont.resumeWithException(
                    RuntimeException("Route error $code: ${message ?: "unknown"}. Enable Routing API in Mappls console.")
                )
            }
        })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Decode Mappls polyline6 (precision 1e-6)
    // ─────────────────────────────────────────────────────────────────────────

    fun decodePolyline6(encoded: String): List<LatLng> {
        val poly = mutableListOf<LatLng>()
        var index = 0; var lat = 0; var lng = 0
        while (index < encoded.length) {
            var b: Int; var shift = 0; var result = 0
            do { b = encoded[index++].code - 63; result = result or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            shift = 0; result = 0
            do { b = encoded[index++].code - 63; result = result or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20)
            lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            poly.add(LatLng(lat / 1e6, lng / 1e6))
        }
        return poly
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 4+5: Haversine match + severity scoring
    // ─────────────────────────────────────────────────────────────────────────

    private fun matchAndScore(route: MapplsRoute, allPotholes: List<PotholePoint>): RouteAnalysis {
        val onRoute = allPotholes.filter { p ->
            minDistToPolyM(p.lat, p.lng, route.polyline) <= POTHOLE_BUFFER_METRES
        }
        val count = onRoute.size
        val totalHazardScore = onRoute.sumOf { it.hazardScore.toDouble() }.toFloat()
        val totalReportCount = onRoute.sumOf { it.reportCount }
        val confirmedHazardCount = onRoute.count { it.reportCount >= 3 }
        val primaryHazardLabel = onRoute
            .maxByOrNull { it.hazardScore * it.reportCount.coerceAtLeast(1) }
            ?.dominantLabel
            ?: "CLEAR"

        val severityScore = when {
            count == 0 -> 0
            else -> (
                totalHazardScore * 4.5f +
                    totalReportCount * 0.85f +
                    confirmedHazardCount * 5f +
                    count * 2f
                ).roundToInt()
        }

        val (label, color) = when {
            count == 0          -> "CLEAR"      to 0xFF4ADE80L
            severityScore < 25  -> "MODERATE"   to 0xFFFACC15L
            severityScore < 70  -> "HIGH RISK"  to 0xFFFB923CL
            else                -> "EXTREME"    to 0xFFEF4444L
        }
        return RouteAnalysis(
            route = route,
            potholes = onRoute,
            potholeCount = count,
            severityScore = severityScore,
            hazardScore = totalHazardScore,
            totalReportCount = totalReportCount,
            primaryHazardLabel = primaryHazardLabel,
            confirmedHazardCount = confirmedHazardCount,
            riskLabel = label,
            riskColor = color
        )
    }

    private fun minDistToPolyM(lat: Double, lng: Double, poly: List<LatLng>): Double {
        var min = Double.MAX_VALUE
        for (i in 1 until poly.size) {
            val d = segDistM(lat, lng, poly[i-1].latitude, poly[i-1].longitude, poly[i].latitude, poly[i].longitude)
            if (d < min) min = d
        }
        return min
    }

    private fun segDistM(pLat: Double, pLng: Double, aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val ab = (bLat - aLat).pow(2.0) + (bLng - aLng).pow(2.0)
        if (ab == 0.0) return haversineM(pLat, pLng, aLat, aLng)
        val t = (((pLat - aLat) * (bLat - aLat) + (pLng - aLng) * (bLng - aLng)) / ab).coerceIn(0.0, 1.0)
        return haversineM(pLat, pLng, aLat + t * (bLat - aLat), aLng + t * (bLng - aLng))
    }

    private fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R    = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a    = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2.0)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}

data class BoundingBox(
    val minLat: Double, val maxLat: Double,
    val minLng: Double, val maxLng: Double
) {
    companion object {
        fun from(points: List<LatLng>, paddingDeg: Double = 0.0) = BoundingBox(
            minLat = points.minOf { it.latitude }  - paddingDeg,
            maxLat = points.maxOf { it.latitude }  + paddingDeg,
            minLng = points.minOf { it.longitude } - paddingDeg,
            maxLng = points.maxOf { it.longitude } + paddingDeg
        )
    }
}
