package com.example.smartroaddamage.models

/**
 * CPacket — One calibration-mode (20-field) sensor snapshot from ESP32
 *
 * Format: C,Time(ms),Lat,Lng,Roll,Pitch,Ax,Ay,Az,Gx,Gy,Gz,Sats,HDOP,SNR,Temp,Volts,BatPct,SDUsed,SDTot
 *
 * Example:
 *   C,75828,17.488697,78.535126,-0.18,-0.00,0.01,-0.00,9.77,-0.0064,0.0154,0.0037,10,0.92,0,33.8,7.4,78.0,14,32
 */
data class CPacket(
    val espMs: Long,            // ESP32 milliseconds since boot
    val lat: Double,            // GPS latitude
    val lng: Double,            // GPS longitude
    val roll: Float,            // Roll angle (degrees)
    val pitch: Float,           // Pitch angle (degrees)
    val ax: Float,              // Acceleration X (m/s²)
    val ay: Float,              // Acceleration Y (m/s²)
    val az: Float,              // Acceleration Z (m/s²)
    val gx: Float,              // Gyroscope X (rad/s)
    val gy: Float,              // Gyroscope Y (rad/s)
    val gz: Float,              // Gyroscope Z (rad/s)
    val sats: Int,              // Satellite count
    val hdop: Float,            // Horizontal dilution of precision
    val snr: Float,             // Signal-to-noise ratio
    val tempC: Float,           // Temperature (°C)
    val volts: Float,           // Voltage (V)
    val batPct: Float,          // Battery percentage (0–100)
    val sdUsedGb: Float,        // SD card used (GB)
    val sdTotGb: Float          // SD card total (GB)
) {
    /**
     * Convert to map for UI callbacks (matches AnomalyDetector.onCalPacket signature)
     */
    fun toMap(): Map<String, String> = mapOf(
        "ms" to espMs.toString(),
        "lat" to lat.toString(),
        "lng" to lng.toString(),
        "roll" to roll.toString(),
        "pitch" to pitch.toString(),
        "ax" to ax.toString(),
        "ay" to ay.toString(),
        "az" to az.toString(),
        "gx" to gx.toString(),
        "gy" to gy.toString(),
        "gz" to gz.toString(),
        "sats" to sats.toString(),
        "hdop" to hdop.toString(),
        "snr" to snr.toString(),
        "temp" to tempC.toString(),
        "volts" to volts.toString(),
        "batpct" to batPct.toString(),
        "sdused" to sdUsedGb.toString(),
        "sdtot" to sdTotGb.toString(),
        "mode" to "CAL"
    )
}
