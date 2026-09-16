package com.example.smartroaddamage.data

/**
 * HazardReadRepository
 * ════════════════════════════════════════════════════════════════════
 * Read-side contract for canonical clustered road hazards.
 *
 * The UI and route-analysis layers ONLY talk to this interface, never
 * to raw Firestore collections directly.  This makes it trivial to
 * swap [MockHazardReadRepository] ↔ [FirestoreHazardReadRepository]
 * without touching a single Compose file.
 *
 * Implementations
 * ───────────────
 *  [MockHazardReadRepository]      → deterministic seed data for UI dev / tests
 *  [FirestoreHazardReadRepository] → live canonical `potholes` collection
 */
interface HazardReadRepository {

    /**
     * Fetch all canonical clustered hazards whose centroid falls
     * inside [box].  Returns an empty list on network failure so that
     * route analysis can still run (just with zero hazards).
     */
    suspend fun fetchHazardsInBox(box: BoundingBox): List<PotholePoint>
}
