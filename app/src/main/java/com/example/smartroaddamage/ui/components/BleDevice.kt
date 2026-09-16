package com.example.smartroaddamage.ui.components

/**
 * BleDevice — shared data class representing a discovered BLE peripheral.
 * Lives in the `components` package so both HardwareBridgeScreen and
 * TacticalRadarModule can reference it without a circular dependency.
 */
data class BleDevice(
    val name: String,
    val address: String,
    val rssi: Int
)
