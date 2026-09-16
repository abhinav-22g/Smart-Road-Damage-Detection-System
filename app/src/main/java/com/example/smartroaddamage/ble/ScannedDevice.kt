package com.example.smartroaddamage.ble

data class ScannedDevice(
    val name: String?,
    val address: String,
    val rssi: Int
)
