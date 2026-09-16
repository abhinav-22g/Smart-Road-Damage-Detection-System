package com.example.smartroaddamage.ble

import android.util.Log
import com.example.smartroaddamage.models.CPacket

private const val TAG = "CPacketParser"

/**
 * Parse a C-packet (calibration mode) from ESP32
 *
 * Format: C,Time(ms),Lat,Lng,Roll,Pitch,Ax,Ay,Az,Gx,Gy,Gz,Sats,HDOP,SNR,Temp,Volts,BatPct,SDUsed,SDTot
 * Expected fields: 20 total — [0]=C prefix, [1..19]=19 data fields
 */
fun parseCPacket(line: String): CPacket? {
    return try {
        // .trim() defends against any stray \r or whitespace that survives the read loop
        val parts = line.trim().split(",")
        
        // C + 19 data fields = 20 parts total (indices 0–19)
        if (parts.size != 20) {
            Log.w(TAG, "Invalid C-packet field count: ${parts.size} (expected 20) in: '$line'")
            return null
        }
        
        // Prefix must be "C"
        if (parts[0] != "C") {
            Log.w(TAG, "Invalid C-packet prefix: '${parts[0]}' (expected 'C') in: '$line'")
            return null
        }
        
        // Parse each field with safety checks
        val espMs = parts[1].toLongOrNull() ?: run {
            Log.w(TAG, "Failed to parse espMs: '${parts[1]}'")
            return null
        }
        
        val lat = parts[2].toDoubleOrNull() ?: run {
            Log.w(TAG, "Failed to parse lat: '${parts[2]}'")
            return null
        }
        
        val lng = parts[3].toDoubleOrNull() ?: run {
            Log.w(TAG, "Failed to parse lng: '${parts[3]}'")
            return null
        }
        
        val roll = parts[4].toFloatOrNull() ?: run {
            Log.w(TAG, "Failed to parse roll: '${parts[4]}'")
            return null
        }
        
        val pitch = parts[5].toFloatOrNull() ?: run {
            Log.w(TAG, "Failed to parse pitch: '${parts[5]}'")
            return null
        }
        
        val ax = parts[6].toFloatOrNull() ?: run {
            Log.w(TAG, "Failed to parse ax: '${parts[6]}'")
            return null
        }
        
        val ay = parts[7].toFloatOrNull() ?: run {
            Log.w(TAG, "Failed to parse ay: '${parts[7]}'")
            return null
        }
        
        val az = parts[8].toFloatOrNull() ?: run {
            Log.w(TAG, "Failed to parse az: '${parts[8]}'")
            return null
        }
        
        val gx = parts[9].toFloatOrNull() ?: run {
            Log.w(TAG, "Failed to parse gx: '${parts[9]}'")
            return null
        }
        
        val gy = parts[10].toFloatOrNull() ?: run {
            Log.w(TAG, "Failed to parse gy: '${parts[10]}'")
            return null
        }
        
        val gz = parts[11].toFloatOrNull() ?: run {
            Log.w(TAG, "Failed to parse gz: '${parts[11]}'")
            return null
        }
        
        val sats = parts[12].toIntOrNull() ?: 0
        
        val hdop = parts[13].toFloatOrNull() ?: 99.9f
        
        val snr = parts[14].toFloatOrNull() ?: 0f
        
        val tempC = parts[15].toFloatOrNull() ?: 0f
        
        val volts = parts[16].toFloatOrNull() ?: 0f
        
        val batPct = parts[17].toFloatOrNull() ?: 0f
        
        val sdUsedGb = parts[18].toFloatOrNull() ?: 0f
        
        val sdTotGb = parts[19].toFloatOrNull() ?: 0f
        
        CPacket(
            espMs = espMs,
            lat = lat,
            lng = lng,
            roll = roll,
            pitch = pitch,
            ax = ax,
            ay = ay,
            az = az,
            gx = gx,
            gy = gy,
            gz = gz,
            sats = sats,
            hdop = hdop,
            snr = snr,
            tempC = tempC,
            volts = volts,
            batPct = batPct,
            sdUsedGb = sdUsedGb,
            sdTotGb = sdTotGb
        )
    } catch (e: Exception) {
        Log.e(TAG, "Exception parsing C-packet: '$line'", e)
        null
    }
}
