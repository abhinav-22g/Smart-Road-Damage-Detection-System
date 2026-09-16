package com.example.smartroaddamage.data

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await

/**
 * Reads canonical clustered hazard documents from the `potholes` collection.
 *
 * We keep the indexed server-side filters on `isActive` + `lat`, then apply the
 * `lng` bounding-box check client-side.
 */
class FirestoreHazardReadRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) : HazardReadRepository {

    companion object {
        private const val TAG = "FSHazardReadRepo"
        private const val COLLECTION = "potholes"
    }

    override suspend fun fetchHazardsInBox(box: BoundingBox): List<PotholePoint> {
        return try {
            val snapshot = db.collection(COLLECTION)
                .whereEqualTo("isActive", true)
                .whereGreaterThanOrEqualTo("lat", box.minLat)
                .whereLessThanOrEqualTo("lat", box.maxLat)
                .get(Source.SERVER)
                .await()

            val lngFilteredDocs = snapshot.documents.filter { doc ->
                val lng = doc.getDouble("lng") ?: return@filter false
                lng in box.minLng..box.maxLng
            }

            lngFilteredDocs.mapNotNull { doc ->
                val lat = doc.getDouble("lat") ?: return@mapNotNull null
                val lng = doc.getDouble("lng") ?: return@mapNotNull null
                if (lat == 0.0 && lng == 0.0) return@mapNotNull null

                val severity = (
                    doc.getLong("maxSeverity")
                        ?: doc.getLong("severity")
                        ?: 1L
                    ).toInt()
                val confidence = (
                    doc.getDouble("avgConfidence")
                        ?: doc.getDouble("confidence")
                        ?: 0.8
                    ).toFloat()
                val reportCount = (
                    doc.getLong("reportCount")
                        ?: inferReportCountFromTags(doc.get("tags") as? List<*>)
                        ?: 1L
                    ).toInt()
                val dominantLabel = normalizeHazardLabel(
                    doc.getString("dominantLabel")
                        ?: doc.getString("type")
                        ?: "Pothole"
                )
                val hazardScore = (
                    doc.getDouble("hazardScore")?.toFloat()
                        ?: (severity * confidence)
                    )

                PotholePoint(
                    id = doc.getString("id") ?: doc.id,
                    lat = lat,
                    lng = lng,
                    severity = severity,
                    confidence = confidence,
                    reportCount = reportCount,
                    dominantLabel = dominantLabel,
                    hazardScore = hazardScore,
                    laneSide = doc.getString("dominantLaneSide") ?: doc.getString("laneSide")
                )
            }.also {
                Log.d(
                    TAG,
                    "Fetched ${snapshot.size()} active docs by lat, ${lngFilteredDocs.size} survived lng filter, ${it.size} mapped hazards for box $box"
                )
            }
        } catch (e: FirebaseFirestoreException) {
            Log.e(TAG, "fetchHazardsInBox firestore failure code=${e.code} box=$box", e)
            throw IllegalStateException(
                "Hazard fetch failed (${e.code.name}). Firestore query could not complete."
            )
        } catch (e: Exception) {
            Log.e(TAG, "fetchHazardsInBox unexpected failure box=$box", e)
            throw IllegalStateException("Hazard fetch failed. See logcat for details.")
        }
    }

    private fun inferReportCountFromTags(tags: List<*>?): Long? {
        val detectionTag = tags
            ?.filterIsInstance<String>()
            ?.firstOrNull { it.startsWith("detections:") }
            ?: return null

        return detectionTag.substringAfter(':').toLongOrNull()
    }

    private fun normalizeHazardLabel(rawLabel: String): String {
        return when (rawLabel.trim().lowercase()) {
            "speedbreaker" -> "Speedbreaker"
            "bump" -> "Bump"
            "pothole" -> "Pothole"
            else -> rawLabel.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase() else it.toString()
            }
        }
    }
}
