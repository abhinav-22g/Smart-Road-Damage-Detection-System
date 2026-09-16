package com.example.smartroaddamage.ui.screens

import com.example.smartroaddamage.data.deriveAnomalySeverity
import com.example.smartroaddamage.detector.AnomalyResult

data class JourneySummary(
    val totalAnomalies: Int,
    val potholeCount: Int,
    val speedbreakerCount: Int,
    val bumpCount: Int,
    val fusedCount: Int,
    val totalSeverity: Int,
    val uploadEligibleCount: Int,
    val skippedCount: Int
)

object JourneySummaryCalculator {
    fun summarize(anomalies: List<AnomalyResult>): JourneySummary {
        val potholeCount = anomalies.count { it.label == "Pothole" }
        val speedbreakerCount = anomalies.count { it.label == "Speedbreaker" }
        val bumpCount = anomalies.count { it.label == "Bump" }
        val fusedCount = anomalies.count { it.isFused }
        val uploadEligibleCount = anomalies.count { it.impactLat != null && it.impactLng != null }

        return JourneySummary(
            totalAnomalies = anomalies.size,
            potholeCount = potholeCount,
            speedbreakerCount = speedbreakerCount,
            bumpCount = bumpCount,
            fusedCount = fusedCount,
            totalSeverity = anomalies.sumOf(::deriveAnomalySeverity),
            uploadEligibleCount = uploadEligibleCount,
            skippedCount = anomalies.size - uploadEligibleCount
        )
    }
}
