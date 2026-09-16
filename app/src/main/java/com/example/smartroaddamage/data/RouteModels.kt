package com.example.smartroaddamage.data

import com.mappls.sdk.maps.geometry.LatLng

/**
 * A canonical clustered hazard point — sourced either from
 * [FirestoreHazardReadRepository] (live) or [MockHazardReadRepository] (test).
 *
 * The optional fields are populated only when backend clustering has enriched
 * the document; UI code must treat them as nullable/default-safe.
 */
data class PotholePoint(
    val id: String,
    val lat: Double,
    val lng: Double,
    val severity: Int,
    val confidence: Float,
    // ── Cluster-enriched fields (populated once backend worker is live) ──────
    val reportCount: Int = 1,
    val dominantLabel: String = "Pothole",
    val hazardScore: Float = (severity * confidence),
    val laneSide: String? = null
)

/** A decoded route from Mappls Direction REST API. */
data class MapplsRoute(
    val index: Int,
    val durationMin: Int,
    val distanceKm: Float,
    val polyline: List<LatLng>  // decoded from polyline6 geometry
)

/** Analysis result after cross-referencing a route against Firestore potholes. */
data class RouteAnalysis(
    val route: MapplsRoute,
    val potholes: List<PotholePoint>,  // only those within 50m of this route
    val potholeCount: Int,
    val severityScore: Int,   // clustered route risk score derived from hazardScore/reportCount
    val hazardScore: Float,
    val totalReportCount: Int,
    val primaryHazardLabel: String,
    val confirmedHazardCount: Int,
    val riskLabel: String,    // CLEAR / MODERATE / HIGH RISK / EXTREME
    val riskColor: Long       // hex as Long e.g. 0xFF4ADE80
) {
    /** Rank score: lower is safer. Clusters confirmed by many riders weigh more. */
    val rankScore: Float get() = severityScore + confirmedHazardCount * 4f + potholeCount * 2f
}

/** Snap-to-road adjusted pothole: actual position on the road surface. */
data class SnappedPothole(
    val original: PotholePoint,
    val snappedLat: Double,
    val snappedLng: Double,
    val distanceToRouteM: Double  // metres from route polyline
)
