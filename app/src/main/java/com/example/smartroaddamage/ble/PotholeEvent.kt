package com.example.smartroaddamage.ble

/**
 * Represents a single road hazard event detected by the ESP32 hardware.
 * This is the core data contract between the BLE layer and the UI/backend layers.
 */
data class PotholeEvent(
    val timestamp: Long,
    val lat: Double,
    val lng: Double,
    val magnitude: Float,
    val eventType: String = "POTHOLE"
)
