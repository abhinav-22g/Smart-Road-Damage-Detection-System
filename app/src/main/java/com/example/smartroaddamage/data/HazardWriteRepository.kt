package com.example.smartroaddamage.data

import com.example.smartroaddamage.detector.AnomalyResult

/**
 * HazardWriteRepository
 * ════════════════════════════════════════════════════════════════════
 * Write-side contract for raw rider anomaly submissions and pilot
 * contribution statistics.
 *
 * Architecture rules
 * ──────────────────
 * - Implementations write ONLY to `pothole_reports`.
 * - They NEVER write to `potholes` (canonical serving collection).
 * - Cluster creation / merging is a backend responsibility.
 * - Stats queries read from `pothole_reports` by `reporterUid`.
 *
 * See [FirestoreHazardWriteRepository] for the production implementation.
 */
interface HazardWriteRepository {

    /**
     * Upload all anomalies detected during one journey as raw reports.
     *
     * @param journeyId   Stable ID for the session.  Used to idempotently
     *                    prevent double-submission on retry.
     * @param reporterUid Firebase Auth UID of the current pilot.
     * @param anomalies   Enriched anomaly results from [AnomalyDetector].
     * @return [UploadResult] summarising success/partial-failure.
     */
    suspend fun uploadJourneyReports(
        journeyId: String,
        reporterUid: String,
        anomalies: List<AnomalyResult>
    ): UploadResult

    /**
     * Load contribution statistics for one pilot from raw reports.
     * Always reads from `pothole_reports`, not from `potholes`.
     */
    suspend fun loadPilotStats(reporterUid: String): PilotStats
}

/** Outcome of a journey report upload. */
data class UploadResult(
    val uploadedCount: Int,
    val skippedCount: Int,       // anomalies without valid GPS coordinates
    val addedSeverityTotal: Int,
    val error: String? = null    // non-null if batch partially/fully failed
)

/** Pilot contribution statistics from raw `pothole_reports`. */
data class PilotStats(
    val totalReports: Int   = 0,
    val totalSeverity: Int  = 0,
    val confirmedCount: Int = 0  // reports where ingestStatus == "processed"
)
