package com.example.smartroaddamage.detector

/**
 * Lightweight fusion layer between the headless vision lane and the existing IMU lane.
 *
 * Vision hits are queued briefly, then matched against the next IMU anomaly inside the
 * configured window. IMU anomalies still pass through even when no vision confirmation exists.
 */
class SensorFusionManager(
    private val nowMs: () -> Long = System::currentTimeMillis
) {

    data class FusedEvent(
        val label: String,
        val imuConfidence: Float,
        val visionPixelRatio: Float,
        val visionLaneSide: String?,
        val isFused: Boolean,
        val lat: Double?,
        val lng: Double?,
        val timestampMs: Long
    )

    private data class PendingVisionHit(
        val pixelRatio: Float,
        val horizontalCenter: Float?,
        val frameRotationDegrees: Int,
        val timestampMs: Long
    )

    private val pendingVisionHits = ArrayDeque<PendingVisionHit>()

    @Synchronized
    fun recordVisionHit(
        pixelRatio: Float,
        horizontalCenter: Float?,
        frameRotationDegrees: Int,
        timestampMs: Long = nowMs()
    ) {
        pendingVisionHits.addLast(
            PendingVisionHit(
                pixelRatio = pixelRatio,
                horizontalCenter = horizontalCenter,
                frameRotationDegrees = frameRotationDegrees,
                timestampMs = timestampMs
            )
        )
        pruneOldVisionHits(timestampMs)
    }

    @Synchronized
    fun fuse(result: AnomalyResult): FusedEvent? {
        if (!result.isAnomaly) return null

        val eventTimestamp = result.androidEpochMs.takeIf { it > 0L } ?: nowMs()
        pruneOldVisionHits(eventTimestamp)

        val matchingVision = pendingVisionHits.lastOrNull { it.timestampMs <= eventTimestamp }
        if (matchingVision != null) {
            pendingVisionHits.remove(matchingVision)
        }

        val visionLaneSide = if (result.label == "Pothole") {
            matchingVision?.horizontalCenter?.let(::laneSideForHorizontalCenter)
        } else {
            null
        }

        return FusedEvent(
            label = result.label,
            imuConfidence = result.confidence,
            visionPixelRatio = matchingVision?.pixelRatio ?: 0f,
            visionLaneSide = visionLaneSide,
            isFused = matchingVision != null,
            lat = result.impactLat,
            lng = result.impactLng,
            timestampMs = eventTimestamp
        )
    }

    @Synchronized
    fun reset() {
        pendingVisionHits.clear()
    }

    private fun pruneOldVisionHits(referenceTimeMs: Long) {
        val cutoff = referenceTimeMs - FUSION_WINDOW_MS
        while (
            pendingVisionHits.isNotEmpty() &&
            pendingVisionHits.first().timestampMs < cutoff
        ) {
            pendingVisionHits.removeFirst()
        }
    }

    private fun laneSideForHorizontalCenter(horizontalCenter: Float): String {
        return when {
            horizontalCenter < 0.35f -> "LEFT"
            horizontalCenter < 0.65f -> "CENTER"
            else -> "RIGHT"
        }
    }

    companion object {
        const val FUSION_WINDOW_MS = 1_500L
    }
}
