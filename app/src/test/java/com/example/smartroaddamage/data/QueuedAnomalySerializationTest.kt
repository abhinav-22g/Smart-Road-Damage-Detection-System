package com.example.smartroaddamage.data

import com.example.smartroaddamage.detector.AnomalyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueuedAnomalySerializationTest {

    @Test
    fun `queued anomaly json preserves fusion fields`() {
        val source = QueuedAnomaly.from(
            AnomalyResult(
                label = "Pothole",
                isAnomaly = true,
                confidence = 91f,
                anomalyProbability = 0.91f,
                allProbabilities = floatArrayOf(),
                impactLat = 12.0,
                impactLng = 77.0,
                impactEspMs = 321L,
                androidEpochMs = 654L,
                laneSide = "LEFT",
                isFused = true,
                visionPixelRatio = 0.27f
            )
        )

        val restored = QueuedAnomaly.fromPersistedMap(source.toPersistedMap())
        val anomaly = restored.toAnomalyResult()

        assertEquals("LEFT", restored.laneSide)
        assertTrue(restored.isFused)
        assertEquals(0.27f, restored.visionPixelRatio, 0.0001f)
        assertTrue(anomaly.isFused)
        assertEquals(0.27f, anomaly.visionPixelRatio, 0.0001f)
    }
}
