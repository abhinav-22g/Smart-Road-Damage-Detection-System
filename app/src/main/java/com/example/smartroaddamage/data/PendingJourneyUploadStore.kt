package com.example.smartroaddamage.data

import android.content.Context
import com.example.smartroaddamage.detector.AnomalyResult
import org.json.JSONArray
import org.json.JSONObject

data class QueuedJourneyUpload(
    val journeyId: String,
    val reporterUid: String,
    val createdAtEpochMs: Long,
    val anomalies: List<QueuedAnomaly>
)

data class QueuedAnomaly(
    val label: String,
    val confidence: Float,
    val anomalyProbability: Float,
    val impactLat: Double?,
    val impactLng: Double?,
    val impactEspMs: Long,
    val androidEpochMs: Long,
    val laneSide: String?,
    val isFused: Boolean,
    val visionPixelRatio: Float
) {
    fun toAnomalyResult(): AnomalyResult = AnomalyResult(
        label = label,
        isAnomaly = label != "Normal",
        confidence = confidence,
        anomalyProbability = anomalyProbability,
        allProbabilities = floatArrayOf(),
        impactLat = impactLat,
        impactLng = impactLng,
        impactEspMs = impactEspMs,
        androidEpochMs = androidEpochMs,
        laneSide = laneSide,
        isFused = isFused,
        visionPixelRatio = visionPixelRatio
    )

    internal fun toPersistedMap(): Map<String, Any?> = linkedMapOf(
        "label" to label,
        "confidence" to confidence.toDouble(),
        "anomalyProbability" to anomalyProbability.toDouble(),
        "impactLat" to impactLat,
        "impactLng" to impactLng,
        "impactEspMs" to impactEspMs,
        "androidEpochMs" to androidEpochMs,
        "laneSide" to laneSide,
        "isFused" to isFused,
        "visionPixelRatio" to visionPixelRatio.toDouble()
    )

    fun toJson(): JSONObject = JSONObject().apply {
        toPersistedMap().forEach { (key, value) -> put(key, value) }
    }

    companion object {
        fun from(result: AnomalyResult): QueuedAnomaly = QueuedAnomaly(
            label = result.label,
            confidence = result.confidence,
            anomalyProbability = result.anomalyProbability,
            impactLat = result.impactLat,
            impactLng = result.impactLng,
            impactEspMs = result.impactEspMs,
            androidEpochMs = result.androidEpochMs,
            laneSide = result.laneSide,
            isFused = result.isFused,
            visionPixelRatio = result.visionPixelRatio
        )

        internal fun fromPersistedMap(map: Map<String, Any?>): QueuedAnomaly = QueuedAnomaly(
            label = map["label"] as? String ?: "Normal",
            confidence = (map["confidence"] as? Number)?.toFloat() ?: 0f,
            anomalyProbability = (map["anomalyProbability"] as? Number)?.toFloat() ?: 0f,
            impactLat = (map["impactLat"] as? Number)?.toDouble(),
            impactLng = (map["impactLng"] as? Number)?.toDouble(),
            impactEspMs = (map["impactEspMs"] as? Number)?.toLong() ?: 0L,
            androidEpochMs = (map["androidEpochMs"] as? Number)?.toLong() ?: 0L,
            laneSide = (map["laneSide"] as? String)?.takeIf { it.isNotBlank() },
            isFused = map["isFused"] as? Boolean ?: false,
            visionPixelRatio = (map["visionPixelRatio"] as? Number)?.toFloat() ?: 0f
        )

        fun fromJson(json: JSONObject): QueuedAnomaly = QueuedAnomaly(
            label = json.optString("label", "Normal"),
            confidence = json.optDouble("confidence", 0.0).toFloat(),
            anomalyProbability = json.optDouble("anomalyProbability", 0.0).toFloat(),
            impactLat = if (json.isNull("impactLat")) null else json.optDouble("impactLat"),
            impactLng = if (json.isNull("impactLng")) null else json.optDouble("impactLng"),
            impactEspMs = json.optLong("impactEspMs", 0L),
            androidEpochMs = json.optLong("androidEpochMs", 0L),
            laneSide = json.optString("laneSide").takeIf { it.isNotBlank() },
            isFused = json.optBoolean("isFused", false),
            visionPixelRatio = json.optDouble("visionPixelRatio", 0.0).toFloat()
        )
    }
}

class PendingJourneyUploadStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun upsertJourneyUpload(
        journeyId: String,
        reporterUid: String,
        anomalies: List<AnomalyResult>
    ) {
        if (anomalies.isEmpty()) return

        val uploads = loadMutableList().filterNot { it.journeyId == journeyId }.toMutableList()
        uploads += QueuedJourneyUpload(
            journeyId = journeyId,
            reporterUid = reporterUid,
            createdAtEpochMs = System.currentTimeMillis(),
            anomalies = anomalies.map { QueuedAnomaly.from(it) }
        )
        save(uploads)
    }

    @Synchronized
    fun removeJourneyUpload(journeyId: String) {
        val uploads = loadMutableList().filterNot { it.journeyId == journeyId }
        save(uploads)
    }

    @Synchronized
    fun listQueuedJourneys(): List<QueuedJourneyUpload> =
        loadMutableList().sortedBy { it.createdAtEpochMs }

    @Synchronized
    fun hasQueuedJourneys(): Boolean = loadMutableList().isNotEmpty()

    private fun loadMutableList(): MutableList<QueuedJourneyUpload> {
        val raw = prefs.getString(KEY_QUEUE_JSON, null)?.takeIf { it.isNotBlank() } ?: return mutableListOf()
        return try {
            val array = JSONArray(raw)
            MutableList(array.length()) { index ->
                val item = array.getJSONObject(index)
                val anomaliesArray = item.optJSONArray("anomalies") ?: JSONArray()
                val anomalies = MutableList(anomaliesArray.length()) { anomalyIndex ->
                    QueuedAnomaly.fromJson(anomaliesArray.getJSONObject(anomalyIndex))
                }
                QueuedJourneyUpload(
                    journeyId = item.optString("journeyId"),
                    reporterUid = item.optString("reporterUid"),
                    createdAtEpochMs = item.optLong("createdAtEpochMs", 0L),
                    anomalies = anomalies
                )
            }.toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun save(uploads: List<QueuedJourneyUpload>) {
        val array = JSONArray()
        uploads.forEach { upload ->
            val anomalies = JSONArray()
            upload.anomalies.forEach { anomalies.put(it.toJson()) }
            array.put(
                JSONObject()
                    .put("journeyId", upload.journeyId)
                    .put("reporterUid", upload.reporterUid)
                    .put("createdAtEpochMs", upload.createdAtEpochMs)
                    .put("anomalies", anomalies)
            )
        }
        prefs.edit().putString(KEY_QUEUE_JSON, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "pending_hazard_uploads"
        private const val KEY_QUEUE_JSON = "queued_journeys_json"
    }
}
