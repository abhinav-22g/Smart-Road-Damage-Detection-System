package com.example.smartroaddamage.detector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorFusionManagerTest {

    private var fakeNowMs = 0L

    private val manager = SensorFusionManager(nowMs = { fakeNowMs })

    @Test
    fun `fuses imu anomaly with recent vision hit`() {
        fakeNowMs = 1_000L
        manager.recordVisionHit(
            pixelRatio = 0.12f,
            horizontalCenter = 0.20f,
            frameRotationDegrees = 0,
            timestampMs = fakeNowMs
        )

        fakeNowMs = 2_000L
        val fused = manager.fuse(anomalyAt(timestampMs = fakeNowMs))

        assertNotNull(fused)
        assertTrue(fused!!.isFused)
        assertEquals(0.12f, fused.visionPixelRatio, 0.0001f)
        assertEquals("LEFT", fused.visionLaneSide)
    }

    @Test
    fun `does not fuse when vision hit is outside window`() {
        fakeNowMs = 1_000L
        manager.recordVisionHit(
            pixelRatio = 0.12f,
            horizontalCenter = 0.20f,
            frameRotationDegrees = 0,
            timestampMs = fakeNowMs
        )

        fakeNowMs = 3_000L
        val fused = manager.fuse(anomalyAt(timestampMs = fakeNowMs))

        assertNotNull(fused)
        assertFalse(fused!!.isFused)
        assertEquals(0f, fused.visionPixelRatio, 0.0001f)
        assertEquals(null, fused.visionLaneSide)
    }

    @Test
    fun `uses most recent matching vision hit`() {
        fakeNowMs = 1_000L
        manager.recordVisionHit(
            pixelRatio = 0.10f,
            horizontalCenter = 0.10f,
            frameRotationDegrees = 0,
            timestampMs = fakeNowMs
        )
        manager.recordVisionHit(
            pixelRatio = 0.22f,
            horizontalCenter = 0.80f,
            frameRotationDegrees = 0,
            timestampMs = 1_200L
        )

        fakeNowMs = 1_300L
        val fused = manager.fuse(anomalyAt(timestampMs = fakeNowMs))

        assertNotNull(fused)
        assertEquals(0.22f, fused!!.visionPixelRatio, 0.0001f)
        assertEquals("RIGHT", fused.visionLaneSide)
    }

    @Test
    fun `does not override non pothole lane side`() {
        fakeNowMs = 1_000L
        manager.recordVisionHit(
            pixelRatio = 0.14f,
            horizontalCenter = 0.52f,
            frameRotationDegrees = 0,
            timestampMs = fakeNowMs
        )

        fakeNowMs = 1_100L
        val fused = manager.fuse(
            anomalyAt(timestampMs = fakeNowMs).copy(label = "Speedbreaker")
        )

        assertNotNull(fused)
        assertTrue(fused!!.isFused)
        assertEquals(null, fused.visionLaneSide)
    }

    private fun anomalyAt(timestampMs: Long): AnomalyResult {
        return AnomalyResult(
            label = "Pothole",
            isAnomaly = true,
            confidence = 96f,
            anomalyProbability = 0.96f,
            allProbabilities = floatArrayOf(0.04f, 0f, 0.96f, 0f),
            impactLat = 12.0,
            impactLng = 77.0,
            impactEspMs = 250L,
            androidEpochMs = timestampMs,
            laneSide = null
        )
    }
}
