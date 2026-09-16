package com.example.smartroaddamage.data

import android.util.Log

/**
 * MockHazardReadRepository
 * ════════════════════════════════════════════════════════════════════
 * Returns a fixed set of pre-shaped canonical cluster documents that
 * already look exactly like what [FirestoreHazardReadRepository] would
 * return after the backend clustering worker has run.
 *
 * Why this matters
 * ────────────────
 * Mock data must imitate the **final clustered shape**, not the raw
 * report shape.  If you seed with one document per rider report, the
 * UI and scoring logic will be trained on wrong semantics.
 *
 * Every PotholePoint here mirrors the `potholes` Firestore schema:
 *   lat / lng          → canonical centroid (snapped or weighted avg)
 *   severity           → maxSeverity from merged cluster
 *   confidence         → avgConfidence of cluster
 *   reportCount        → how many raw rider reports fed this cluster
 *   dominantLabel      → modal label across all merged reports
 *   hazardScore        → severity * confidence * log(1 + reportCount)
 *
 * Replace or extend this list freely during UI development.
 * When [FirestoreHazardReadRepository] is live, simply swap the
 * injection site — zero UI changes required.
 */
object MockHazardReadRepository : HazardReadRepository {

    private const val TAG = "MockHazardReadRepo"

    /**
     * Representative hazard clusters around Hyderabad (Jubilee Hills /
     * Banjara Hills corridor — high-traffic, pothole-prone stretch).
     *
     * Each entry is pre-clustered: reportCount > 1 means multiple riders
     * independently confirmed the same hazard.
     */
    private val SEED_CLUSTERS: List<PotholePoint> = listOf(

        // ── Jubilee Hills Road No. 36 ──────────────────────────────────
        PotholePoint(
            id             = "mock_cluster_jh_01",
            lat            = 17.43215,
            lng            = 78.40612,
            severity       = 5,
            confidence     = 0.93f,
            reportCount    = 14,
            dominantLabel  = "Pothole",
            hazardScore    = 5 * 0.93f * Math.log1p(14.0).toFloat()
        ),
        PotholePoint(
            id             = "mock_cluster_jh_02",
            lat            = 17.43108,
            lng            = 78.40891,
            severity       = 3,
            confidence     = 0.77f,
            reportCount    = 6,
            dominantLabel  = "Bump",
            hazardScore    = 3 * 0.77f * Math.log1p(6.0).toFloat()
        ),

        // ── Banjara Hills Road No. 12 ─────────────────────────────────
        PotholePoint(
            id             = "mock_cluster_bh_01",
            lat            = 17.41502,
            lng            = 78.44831,
            severity       = 4,
            confidence     = 0.88f,
            reportCount    = 9,
            dominantLabel  = "Pothole",
            hazardScore    = 4 * 0.88f * Math.log1p(9.0).toFloat()
        ),
        PotholePoint(
            id             = "mock_cluster_bh_02",
            lat            = 17.41789,
            lng            = 78.44220,
            severity       = 2,
            confidence     = 0.72f,
            reportCount    = 3,
            dominantLabel  = "Speedbreaker",
            hazardScore    = 2 * 0.72f * Math.log1p(3.0).toFloat()
        ),

        // ── Mehdipatnam Junction ──────────────────────────────────────
        PotholePoint(
            id             = "mock_cluster_mp_01",
            lat            = 17.39487,
            lng            = 78.43920,
            severity       = 5,
            confidence     = 0.95f,
            reportCount    = 22,
            dominantLabel  = "Pothole",
            hazardScore    = 5 * 0.95f * Math.log1p(22.0).toFloat()
        ),
        PotholePoint(
            id             = "mock_cluster_mp_02",
            lat            = 17.39312,
            lng            = 78.44133,
            severity       = 3,
            confidence     = 0.81f,
            reportCount    = 7,
            dominantLabel  = "Bump",
            hazardScore    = 3 * 0.81f * Math.log1p(7.0).toFloat()
        ),

        // ── Tolichowki – Attapur road ─────────────────────────────────
        PotholePoint(
            id             = "mock_cluster_tc_01",
            lat            = 17.38620,
            lng            = 78.42110,
            severity       = 4,
            confidence     = 0.86f,
            reportCount    = 11,
            dominantLabel  = "Pothole",
            hazardScore    = 4 * 0.86f * Math.log1p(11.0).toFloat()
        ),

        // ── Gachibowli flyover approach ───────────────────────────────
        PotholePoint(
            id             = "mock_cluster_gc_01",
            lat            = 17.44021,
            lng            = 78.34812,
            severity       = 2,
            confidence     = 0.69f,
            reportCount    = 2,
            dominantLabel  = "Speedbreaker",
            hazardScore    = 2 * 0.69f * Math.log1p(2.0).toFloat()
        ),
        PotholePoint(
            id             = "mock_cluster_gc_02",
            lat            = 17.44280,
            lng            = 78.34620,
            severity       = 5,
            confidence     = 0.91f,
            reportCount    = 18,
            dominantLabel  = "Pothole",
            hazardScore    = 5 * 0.91f * Math.log1p(18.0).toFloat()
        ),

        // ── HITEC City inner roads ─────────────────────────────────────
        PotholePoint(
            id             = "mock_cluster_hc_01",
            lat            = 17.44723,
            lng            = 78.37840,
            severity       = 3,
            confidence     = 0.80f,
            reportCount    = 5,
            dominantLabel  = "Bump",
            hazardScore    = 3 * 0.80f * Math.log1p(5.0).toFloat()
        )
    )

    override suspend fun fetchHazardsInBox(box: BoundingBox): List<PotholePoint> {
        val result = SEED_CLUSTERS.filter { p ->
            p.lat in box.minLat..box.maxLat && p.lng in box.minLng..box.maxLng
        }
        Log.d(TAG, "Mock: ${result.size} clusters in box $box")
        return result
    }
}
