package com.example.smartroaddamage.data

import com.example.smartroaddamage.detector.AnomalyResult

internal fun deriveAnomalySeverity(anomaly: AnomalyResult): Int = when (anomaly.label) {
    "Pothole" -> if (anomaly.confidence > 80f) 5 else 3
    "Speedbreaker" -> 2
    "Bump" -> 1
    else -> 1
}
