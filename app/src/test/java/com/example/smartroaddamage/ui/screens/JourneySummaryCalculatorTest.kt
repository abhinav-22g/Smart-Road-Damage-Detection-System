package com.example.smartroaddamage.ui.screens

import com.example.smartroaddamage.detector.AnomalyResult
import org.junit.Assert.assertEquals
import org.junit.Test

class JourneySummaryCalculatorTest {

    @Test
    fun `summarize returns labeled breakdown and severity total`() {
        val anomalies = listOf(
            anomaly(label = "Pothole", confidence = 92f, isFused = true),
            anomaly(label = "Pothole", confidence = 70f),
            anomaly(label = "Speedbreaker", confidence = 88f),
            anomaly(label = "Bump", confidence = 84f, hasGps = false)
        )

        val summary = JourneySummaryCalculator.summarize(anomalies)

        assertEquals(4, summary.totalAnomalies)
        assertEquals(2, summary.potholeCount)
        assertEquals(1, summary.speedbreakerCount)
        assertEquals(1, summary.bumpCount)
        assertEquals(1, summary.fusedCount)
        assertEquals(11, summary.totalSeverity)
        assertEquals(3, summary.uploadEligibleCount)
        assertEquals(1, summary.skippedCount)
    }

    private fun anomaly(
        label: String,
        confidence: Float,
        isFused: Boolean = false,
        hasGps: Boolean = true
    ): AnomalyResult {
        return AnomalyResult(
            label = label,
            isAnomaly = label != "Normal",
            confidence = confidence,
            anomalyProbability = confidence / 100f,
            allProbabilities = floatArrayOf(),
            impactLat = if (hasGps) 12.0 else null,
            impactLng = if (hasGps) 77.0 else null,
            impactEspMs = 100L,
            androidEpochMs = 200L,
            laneSide = null,
            isFused = isFused
        )
    }
}
