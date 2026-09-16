package com.example.smartroaddamage.data

import android.util.Log
import com.example.smartroaddamage.detector.AnomalyResult
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * FirestoreHazardWriteRepository
 * ════════════════════════════════════════════════════════════════════
 * Writes raw rider anomaly submissions to Firestore `pothole_reports`.
 *
 * ARCHITECTURE RULE
 * ─────────────────
 * This repository NEVER writes to the canonical `potholes` collection.
 * The backend clustering worker (Firebase Cloud Function) is the only
 * process allowed to write there.
 *
 * Idempotency
 * ───────────
 * Each document ID is derived deterministically from:
 *
 *   "<journeyId>_<impactEspMs>_<label>"
 *
 * This means a retry after a timeout will overwrite the same document
 * rather than create a duplicate, making uploads safe to retry.
 *
 * `pothole_reports` document schema
 * ───────────────────────────────────
 *  reportId        String   — document ID (deterministic)
 *  journeyId       String
 *  reporterUid     String
 *  label           String   — "Pothole" / "Speedbreaker" / "Bump"
 *  severity        Int      — 1–5 derived from label + confidence
 *  modelConfidence Float    — 0.0–1.0
 *  rawLat          Double
 *  rawLng          Double
 *  snappedLat      null     — filled by backend after Snap-to-Road
 *  snappedLng      null
 *  impactEspMs     Long     — ESP32 millis at anomaly peak
 *  androidEpochMs  Long     — wall-clock epoch after clock sync
 *  visionPixelRatio Float?  — optional vision mask support strength
 *  modelVersion    String   — "ptl_2026_04"
 *  ingestStatus    String   — "pending" → "processed" | "failed" (backend)
 *  clusterId       null     — written by backend after merge
 *  createdAt       Timestamp
 */
class FirestoreHazardWriteRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) : HazardWriteRepository {

    companion object {
        private const val TAG               = "FSHazardWriteRepo"
        private const val RAW_COLLECTION    = "pothole_reports"
        private const val MODEL_VERSION     = "ptl_2026_04"
        private const val MAX_BATCH_REPORTS = 450
        private const val CELL_SIZE_METRES  = 5.0
    }

    // ─────────────────────────────────────────────────────────────────
    // Upload journey reports → pothole_reports
    // ─────────────────────────────────────────

    override suspend fun uploadJourneyReports(
        journeyId: String,
        reporterUid: String,
        anomalies: List<AnomalyResult>
    ): UploadResult {

        val eligible = anomalies.filter { it.impactLat != null && it.impactLng != null }
        val skipped  = anomalies.size - eligible.size

        if (eligible.isEmpty()) {
            Log.w(TAG, "No GPS-tagged anomalies to upload for journey $journeyId")
            return UploadResult(0, skipped, 0)
        }

        var uploadedCount = 0
        var uploadedSeverity = 0

        return try {
            eligible.chunked(MAX_BATCH_REPORTS).forEachIndexed { chunkIndex, chunk ->
                val batch = db.batch()
                var chunkSeverity = 0

                chunk.forEach { anomaly ->
                    val severity = deriveAnomalySeverity(anomaly)
                    val lat = anomaly.impactLat!!
                    val lng = anomaly.impactLng!!

                    // Deterministic ID prevents duplicate uploads on retry
                    val reportId = "${journeyId}_${anomaly.impactEspMs}_${anomaly.label}"
                    val docRef = db.collection(RAW_COLLECTION).document(reportId)

                    val payload = hashMapOf(
                        "reportId"        to reportId,
                        "journeyId"       to journeyId,
                        "reporterUid"     to reporterUid,
                        "label"           to anomaly.label,
                        "severity"        to severity,
                        "modelConfidence" to (anomaly.confidence / 100f),
                        "rawLat"          to lat,
                        "rawLng"          to lng,
                        "snappedLat"      to null,                // filled by backend
                        "snappedLng"      to null,
                        "impactEspMs"     to anomaly.impactEspMs,
                        "androidEpochMs"  to anomaly.androidEpochMs,
                        "laneSide"        to anomaly.laneSide,
                        "isFused"         to anomaly.isFused,
                        "visionPixelRatio" to anomaly.visionPixelRatio,
                        "modelVersion"    to MODEL_VERSION,
                        "cellKey"         to computeCellKey(lat, lng),
                        "ingestStatus"    to "pending",           // backend will set "processed"
                        "clusterId"       to null,                // backend will set after merge
                        "createdAt"       to FieldValue.serverTimestamp()
                    )

                    batch.set(docRef, payload)
                    chunkSeverity += severity
                }

                batch.commit().await()
                uploadedCount += chunk.size
                uploadedSeverity += chunkSeverity
                Log.d(
                    TAG,
                    "Committed upload chunk ${chunkIndex + 1}/${(eligible.size + MAX_BATCH_REPORTS - 1) / MAX_BATCH_REPORTS}"
                )
            }

            Log.i(TAG, "Uploaded $uploadedCount raw reports for journey $journeyId")
            UploadResult(
                uploadedCount      = uploadedCount,
                skippedCount       = skipped,
                addedSeverityTotal = uploadedSeverity
            )

        } catch (e: Exception) {
            Log.e(TAG, "Batch upload failed for journey $journeyId", e)
            UploadResult(
                uploadedCount      = uploadedCount,
                skippedCount       = skipped + (eligible.size - uploadedCount),
                addedSeverityTotal = uploadedSeverity,
                error              = e.message
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Load pilot stats → from pothole_reports (NOT from potholes)
    // ─────────────────────────────────────────

    override suspend fun loadPilotStats(reporterUid: String): PilotStats {
        return try {
            val snapshot = db.collection(RAW_COLLECTION)
                .whereEqualTo("reporterUid", reporterUid)
                .get()
                .await()

            val total     = snapshot.size()
            val severity  = snapshot.documents.sumOf { (it.getLong("severity") ?: 1L).toInt() }
            val confirmed = snapshot.documents.count {
                it.getString("ingestStatus") == "processed"
            }

            PilotStats(
                totalReports   = total,
                totalSeverity  = severity,
                confirmedCount = confirmed
            ).also { Log.d(TAG, "Pilot $reporterUid stats: $it") }

        } catch (e: Exception) {
            Log.e(TAG, "loadPilotStats failed for $reporterUid", e)
            PilotStats() // safe empty default
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Severity derivation (consistent with prior CockpitScreen logic)
    // ─────────────────────────────────────────

    private fun computeCellKey(lat: Double, lng: Double): String {
        val latDegrees = CELL_SIZE_METRES / 111_320.0
        val lngDegrees = CELL_SIZE_METRES /
            (111_320.0 * kotlin.math.cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        val latCell = kotlin.math.floor(lat / latDegrees).toLong()
        val lngCell = kotlin.math.floor(lng / lngDegrees).toLong()
        return "$latCell:$lngCell"
    }
}
