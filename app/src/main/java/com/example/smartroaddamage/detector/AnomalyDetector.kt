package com.example.smartroaddamage.detector

/*
 * AnomalyDetector.kt
 * ══════════════════════════════════════════════════════════════════════
 * Complete Kotlin integration for the MS-CAN anomaly detection pipeline.
 *
 * WHAT THIS FILE DOES:
 *  1. Parses all 4 ESP32 packet types: I (infer), C (cal), G (GPS), SYNC
 *  2. Applies StandardScaler in Kotlin (mean/std from notebook Cell 14)
 *  3. Produces [1, 8, 20] input tensor for the new 3-class model (InferenceEngine.kt)
 *  4. Runs the PyTorch Mobile model — anomaly classification
 *  5. GPS interpolation: finds real impact lat/lng using timestamps
 *  6. Clock sync: aligns ESP32 millis() to Android System epoch
 *
 * DEPENDENCIES (build.gradle):
 *  implementation 'org.pytorch:pytorch_android_lite:1.13.1'
 *  implementation 'org.pytorch:pytorch_android_torchvision_lite:1.13.1'
 *
 * ASSETS (src/main/assets/):
 *  anomaly_detector.ptl   ← from Anomaly2.ipynb (April 2026 model)
 *
 * USAGE:
 *  val detector = AnomalyDetector(context)
 *  detector.onResult = { result -> /* update UI */ }
 *  // In your BT receive loop:
 *  detector.parseLine(rawBtLine)
 * ══════════════════════════════════════════════════════════════════════
 */

import android.content.Context
import android.location.Location
import android.util.Log

// ─────────────────────────────────────────────────────────────────────
// Data classes
// ─────────────────────────────────────────────────────────────────────

/** One 20Hz IMU sample from the ESP32 INFER packet. */
data class ImuSample(
    val espMs: Long,          // ESP32 millis() from "I,<ms>,..." packet
    val ax: Float,
    val ay: Float,
    val az: Float,
    val gx: Float,
    val gy: Float,
    val gz: Float
)

/** One GPS fix from the ESP32 G packet. */
data class GpsFix(
    val espMs: Long,          // ESP32 millis() at GPS update moment
    val lat: Double,
    val lng: Double,
    val sats: Int
)

data class PhoneFix(
    val androidEpochMs: Long,
    val lat: Double,
    val lng: Double,
    val accuracyM: Float
)

/** Result emitted after each successful inference. */
data class AnomalyResult(
    val label: String,              // "Normal", "Bump", "Pothole", "Speedbreaker"
    val isAnomaly: Boolean,
    val confidence: Float,          // 0–100%
    val anomalyProbability: Float,  // max(pPothole, pSpeedbreaker) — NOT 1-P(Normal)
    val allProbabilities: FloatArray, // [Normal, Bump(always 0=rule-derived), Pothole, Speedbreaker]
    val impactLat: Double?,         // interpolated GPS lat at impact moment
    val impactLng: Double?,         // interpolated GPS lng at impact moment
    val impactEspMs: Long,          // ESP32 ms of peak-acceleration sample
    val androidEpochMs: Long,       // wall-clock epoch of impact (after sync)
    val laneSide: String? = null,   // heuristic LEFT / RIGHT / CENTER when confidence is high
    val isFused: Boolean = false,
    val visionPixelRatio: Float = 0f
)

// ─────────────────────────────────────────────────────────────────────
// Main class
// ─────────────────────────────────────────────────────────────────────

class AnomalyDetector(private val context: Context) {

    // ── Callback — set this before parsing any lines ──────────────
    var onResult: ((AnomalyResult) -> Unit)? = null
    var onCalPacket: ((Map<String, String>) -> Unit)? = null   // for Device Monitor UI
    var onCalibDone: (() -> Unit)? = null
    var onSynced: (() -> Unit)? = null

    // ── Model constants from notebook Cell 14 printout ────────────
    // (Moved to InferenceEngine.kt)

    // ── Clock sync state ─────────────────────────────────────────
    // ESP32 sends "SYNC:<androidEpochMs>", we record the ESP ms in SYNCED reply.
    // Conversion: androidEpoch = syncAndroidMs + (pktEspMs - syncEsp32Ms)
    private var syncEsp32Ms: Long   = 0
    private var syncAndroidMs: Long = 0
    private var isSynced: Boolean   = false

    // ── Buffers ───────────────────────────────────────────────────
    // GPS history: last 5 fixes with ESP timestamps for interpolation
    private val gpsHistory = java.util.concurrent.ConcurrentLinkedDeque<GpsFix>()
    private val GPS_HISTORY_SIZE = 8
    private var lastCalSeedEspMs: Long = Long.MIN_VALUE
    private var smoothedFix: GpsFix? = null
    private val phoneHistory = java.util.concurrent.ConcurrentLinkedDeque<PhoneFix>()
    private val PHONE_HISTORY_SIZE = 8
    private var latestFusedLatLng: Pair<Double, Double>? = null
    private var isStationary: Boolean = true

    // ── PyTorch Mobile model ──────────────────────────────────────
    // (Moved to InferenceEngine.kt)

    // ─────────────────────────────────────────────────────────────
    // Public entry point — call this for every line received over BT
    // ─────────────────────────────────────────────────────────────

    fun parseLine(raw: String) {
        val line = raw.trim()
        when {
            line.startsWith("C,")       -> handleCalPacket(line)
            line.startsWith("G,")       -> handleGpsPacket(line)
            line.startsWith("SYNCED,")  -> handleSyncedReply(line)
            line == "CALIB_DONE"        -> onCalibDone?.invoke()
            line.startsWith("H:")       -> { /* header line — ignore or log */ }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Clock sync — call this immediately on BT connect
    // Returns the string to send to the ESP32
    // ─────────────────────────────────────────────────────────────

    fun buildSyncCommand(): String {
        val nowMs = System.currentTimeMillis()
        return "SYNC:$nowMs\n"
    }

    fun updatePhoneLocation(location: Location) {
        val fix = PhoneFix(
            androidEpochMs = location.time.takeIf { it > 0L } ?: System.currentTimeMillis(),
            lat = location.latitude,
            lng = location.longitude,
            accuracyM = location.accuracy.takeIf { it > 0f } ?: 999f
        )
        phoneHistory.addLast(fix)
        while (phoneHistory.size > PHONE_HISTORY_SIZE) {
            phoneHistory.pollFirst()
        }
    }

    fun updateMotionState(stationary: Boolean) {
        isStationary = stationary
    }

    fun currentMapLocation(): Pair<Double?, Double?> {
        latestFusedLatLng?.let { return Pair(it.first, it.second) }
        gpsHistory.peekLast()?.let { return Pair(it.lat, it.lng) }
        return Pair(null, null)
    }

    // ─────────────────────────────────────────────────────────────
    // Packet handlers
    // ─────────────────────────────────────────────────────────────

    /**
     * G,<ms>,<lat>,<lng>,<sats>
     * Store GPS fix with its ESP timestamp.
     * Android uses last 5 of these to interpolate impact location.
     */
    private fun handleGpsPacket(line: String) {
        val p = line.split(",")
        if (p.size != 5) return
        val fix = GpsFix(
            espMs = p[1].toLongOrNull() ?: return,
            lat   = p[2].toDoubleOrNull() ?: return,
            lng   = p[3].toDoubleOrNull() ?: return,
            sats  = p[4].toIntOrNull() ?: 0
        )
        recordGpsFix(fix, allowSmoothing = true)
    }

    /**
     * C,<ms>,<lat>,<lng>,<roll>,<pitch>,<Ax>,<Ay>,<Az>,
     *   <Gx>,<Gy>,<Gz>,<sats>,<hdop>,<snr>,<temp>,<volts>,<batpct>,<sdused>,<sdtot>
     * Parsed and forwarded to UI callback — not used in ML path.
     */
    private fun handleCalPacket(line: String) {
        val p = line.trim().split(",")
        if (p.size != 20) return
        val espMs = p[1].toLongOrNull()
        val lat = p[2].toDoubleOrNull()
        val lng = p[3].toDoubleOrNull()
        val sats = p[12].toIntOrNull() ?: 0
        if (
            espMs != null &&
            lat != null &&
            lng != null &&
            lat != 0.0 &&
            lng != 0.0 &&
            sats > 0 &&
            espMs - lastCalSeedEspMs >= 500L
        ) {
            lastCalSeedEspMs = espMs
            recordGpsFix(
                GpsFix(
                    espMs = espMs,
                    lat = lat,
                    lng = lng,
                    sats = sats
                ),
                allowSmoothing = true
            )
        }
        val keys = listOf("ms","lat","lng","roll","pitch","ax","ay","az",
                          "gx","gy","gz","sats","hdop","snr","temp",
                          "volts","batpct","sdused","sdtot","mode")
        // Build map for UI (skip prefix "C" at index 0)
        val map = keys.zip(p.drop(1)).toMap().toMutableMap()
        map["mode"] = "CAL"
        onCalPacket?.invoke(map)
    }

    /** SYNCED,<espMs>,<androidEpochMs> — reply from ESP32 after SYNC */
    private fun handleSyncedReply(line: String) {
        val p = line.split(",")
        if (p.size != 3) return
        syncEsp32Ms   = p[1].toLongOrNull() ?: return
        syncAndroidMs = p[2].toLongOrNull() ?: return
        isSynced      = true
        Log.i(TAG, "Clock synced: esp=$syncEsp32Ms android=$syncAndroidMs")
        onSynced?.invoke()
    }

    /**
     * Called by ViewModel when inference yields an anomaly.
     * Maps the core ML result onto physical clock and world coordinates.
     */
    fun enrich(raw: AnomalyResult): AnomalyResult {
        val (impactLat, impactLng) = interpolateGps(raw.impactEspMs)
        val androidEpoch = if (isSynced) {
            syncAndroidMs + (raw.impactEspMs - syncEsp32Ms)
        } else {
            System.currentTimeMillis() // fallback
        }
        
        return raw.copy(
            impactLat = impactLat,
            impactLng = impactLng,
            androidEpochMs = androidEpoch
        )
    }

    // ─────────────────────────────────────────────────────────────
    // GPS linear interpolation
    // ─────────────────────────────────────────────────────────────

    /**
     * Given an ESP32 millisecond timestamp, find the two surrounding
     * GPS fixes and linearly interpolate the lat/lng.
     *
     * Cases:
     *  - Impact between two known fixes → interpolate (best case, ~2–4m error)
     *  - Impact before first fix   → return first fix (only initial seconds)
     *  - Impact after last fix     → return last fix (GPS update imminent)
     *  - No GPS history at all     → return null (user will see null on map)
     *
     * At 60 km/h, linear interpolation error vs true curved path ≈ 0.1m
     * over a 1s GPS interval — negligible vs GPS hardware accuracy (~3m).
     */
    private fun interpolateGps(impactEspMs: Long): Pair<Double?, Double?> {
        val fixes = gpsHistory.toList().sortedBy { it.espMs }
        if (fixes.isEmpty()) return Pair(null, null)
        if (fixes.size == 1) return Pair(fixes.first().lat, fixes.first().lng)

        // Find the fix just before and just after impact
        var before: GpsFix? = null
        var after: GpsFix?  = null

        for (fix in fixes) {
            if (fix.espMs <= impactEspMs) {
                if (before == null || fix.espMs > before.espMs) before = fix
            } else {
                if (after == null || fix.espMs < after.espMs) after = fix
            }
        }

        return when {
            before != null && after != null -> {
                // Linear interpolation
                val alpha = (impactEspMs - before.espMs).toDouble() /
                            (after.espMs - before.espMs).toDouble()
                val lat = before.lat + alpha * (after.lat - before.lat)
                val lng = before.lng + alpha * (after.lng - before.lng)
                fuseForImpact(impactEspMs, lat, lng)
            }
            before != null -> {
                val pair = extrapolateForward(fixes, before, impactEspMs)
                if (pair.first != null && pair.second != null) {
                    fuseForImpact(impactEspMs, pair.first!!, pair.second!!)
                } else {
                    pair
                }
            }
            after  != null -> fuseForImpact(impactEspMs, after.lat, after.lng)
            else           -> Pair(null, null)
        }
    }

    private fun recordGpsFix(fix: GpsFix, allowSmoothing: Boolean) {
        val accepted = if (allowSmoothing) smoothFix(fix) else fix
        val fused = fuseSensorWithPhone(accepted)
        gpsHistory.addLast(accepted)
        while (gpsHistory.size > GPS_HISTORY_SIZE) {
            gpsHistory.pollFirst()
        }
        latestFusedLatLng = Pair(fused.lat, fused.lng)
        Log.d(
            TAG,
            "GPS fix: ${fused.lat},${fused.lng} sats=${accepted.sats} @${accepted.espMs}ms " +
                "(raw=${fix.lat},${fix.lng})"
        )
    }

    private fun smoothFix(raw: GpsFix): GpsFix {
        val previous = smoothedFix ?: run {
            smoothedFix = raw
            return raw
        }

        val dtMs = (raw.espMs - previous.espMs).coerceAtLeast(1L)
        val distanceMeters = approxDistanceMeters(previous.lat, previous.lng, raw.lat, raw.lng)
        val speedMps = distanceMeters / (dtMs / 1000.0)

        val alpha = when {
            isStationary && distanceMeters > 6.0 -> 0.02
            speedMps > 35.0 && raw.sats < 10 -> 0.03
            distanceMeters < 1.5 -> if (isStationary) 0.05 else 0.10
            distanceMeters < 4.0 -> if (isStationary) 0.10 else 0.18
            distanceMeters < 10.0 -> if (isStationary) 0.14 else 0.28
            else -> if (isStationary) 0.08 else 0.35
        }

        val smoothed = GpsFix(
            espMs = raw.espMs,
            lat = previous.lat + alpha * (raw.lat - previous.lat),
            lng = previous.lng + alpha * (raw.lng - previous.lng),
            sats = raw.sats
        )
        smoothedFix = if (dtMs > 10_000L) raw else smoothed
        return smoothedFix!!
    }

    private fun extrapolateForward(
        fixes: List<GpsFix>,
        lastFix: GpsFix,
        impactEspMs: Long
    ): Pair<Double?, Double?> {
        val previous = fixes.lastOrNull { it.espMs < lastFix.espMs } ?: return Pair(lastFix.lat, lastFix.lng)
        val gapMs = lastFix.espMs - previous.espMs
        val forwardMs = impactEspMs - lastFix.espMs
        if (gapMs <= 0L || forwardMs <= 0L || forwardMs > 2_000L) {
            return Pair(lastFix.lat, lastFix.lng)
        }

        val alpha = forwardMs.toDouble() / gapMs.toDouble()
        val lat = lastFix.lat + alpha * (lastFix.lat - previous.lat)
        val lng = lastFix.lng + alpha * (lastFix.lng - previous.lng)
        return Pair(lat, lng)
    }

    private fun fuseSensorWithPhone(sensor: GpsFix): GpsFix {
        val sensorEpochMs = toAndroidEpoch(sensor.espMs) ?: return sensor
        val phone = nearestPhoneFix(sensorEpochMs) ?: return sensor

        val phoneWeightBase = when {
            phone.accuracyM <= 10f -> 0.12
            phone.accuracyM <= 20f -> 0.08
            phone.accuracyM <= 35f -> 0.05
            else -> 0.02
        }
        val phoneWeight = if (isStationary) {
            (phoneWeightBase + 0.03).coerceAtMost(0.15)
        } else {
            phoneWeightBase
        }
        val sensorWeight = 1.0 - phoneWeight

        return sensor.copy(
            lat = sensor.lat * sensorWeight + phone.lat * phoneWeight,
            lng = sensor.lng * sensorWeight + phone.lng * phoneWeight
        )
    }

    private fun fuseForImpact(
        impactEspMs: Long,
        sensorLat: Double,
        sensorLng: Double
    ): Pair<Double?, Double?> {
        val sensorEpochMs = toAndroidEpoch(impactEspMs) ?: return Pair(sensorLat, sensorLng)
        val phone = nearestPhoneFix(sensorEpochMs) ?: return Pair(sensorLat, sensorLng)
        val phoneWeight = when {
            isStationary && phone.accuracyM <= 20f -> 0.12
            phone.accuracyM <= 10f -> 0.08
            phone.accuracyM <= 20f -> 0.05
            else -> 0.02
        }
        val sensorWeight = 1.0 - phoneWeight
        return Pair(
            sensorLat * sensorWeight + phone.lat * phoneWeight,
            sensorLng * sensorWeight + phone.lng * phoneWeight
        )
    }

    private fun nearestPhoneFix(androidEpochMs: Long): PhoneFix? {
        return phoneHistory.minByOrNull { kotlin.math.abs(it.androidEpochMs - androidEpochMs) }
            ?.takeIf { kotlin.math.abs(it.androidEpochMs - androidEpochMs) <= 10_000L }
    }

    private fun toAndroidEpoch(espMs: Long): Long? {
        return if (isSynced) syncAndroidMs + (espMs - syncEsp32Ms) else null
    }

    private fun approxDistanceMeters(
        lat1: Double,
        lng1: Double,
        lat2: Double,
        lng2: Double
    ): Double {
        val metersPerDegreeLat = 111_320.0
        val metersPerDegreeLng = 111_320.0 * kotlin.math.cos(Math.toRadians((lat1 + lat2) / 2.0))
        val dLat = (lat2 - lat1) * metersPerDegreeLat
        val dLng = (lng2 - lng1) * metersPerDegreeLng
        return kotlin.math.sqrt(dLat * dLat + dLng * dLng)
    }

    // ─────────────────────────────────────────────────────────────
    // Utility
    // ─────────────────────────────────────────────────────────────

    // (Removed assetToFile and destroy from AnomalyDetector since InferenceEngine manages the model.)
    // ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "AnomalyDetector"
    }
}
