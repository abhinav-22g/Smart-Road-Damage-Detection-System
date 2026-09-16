package com.example.smartroaddamage.ble

import android.util.Log
import com.example.smartroaddamage.detector.ImuSample

private const val TAG = "IPacketParser"

/**
 * IPacketParser.kt — parses the raw string into ImuSample, nothing else.
 * 
 * Extracts I-packet strings to an ImuSample data structure without any
 * offset application. Offsets are subtracted by SmartRoadClassicManager securely.
 */
object IPacketParser {
    fun parse(line: String): ImuSample? {
        try {
            val parts = line.split(",")
            if (parts.size != 8) {
                Log.w(TAG, "Bad I-packet: expected 8 fields, got ${parts.size} -> '$line'")
                return null
            }

            val espMs = parts[1].toLongOrNull() ?: run {
                Log.w(TAG, "Bad I-packet espMs '${parts[1]}' -> '$line'")
                return null
            }
            val ax = parts[2].toFloatOrNull() ?: run {
                Log.w(TAG, "Bad I-packet ax '${parts[2]}' -> '$line'")
                return null
            }
            val ay = parts[3].toFloatOrNull() ?: run {
                Log.w(TAG, "Bad I-packet ay '${parts[3]}' -> '$line'")
                return null
            }
            val az = parts[4].toFloatOrNull() ?: run {
                Log.w(TAG, "Bad I-packet az '${parts[4]}' -> '$line'")
                return null
            }
            val gx = parts[5].toFloatOrNull() ?: run {
                Log.w(TAG, "Bad I-packet gx '${parts[5]}' -> '$line'")
                return null
            }
            val gy = parts[6].toFloatOrNull() ?: run {
                Log.w(TAG, "Bad I-packet gy '${parts[6]}' -> '$line'")
                return null
            }
            val gz = parts[7].toFloatOrNull() ?: run {
                Log.w(TAG, "Bad I-packet gz '${parts[7]}' -> '$line'")
                return null
            }

            return ImuSample(
                espMs = espMs,
                ax = ax,
                ay = ay,
                az = az,
                gx = gx,
                gy = gy,
                gz = gz
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception parsing I-packet: '$line'", e)
            return null
        }
    }
}
