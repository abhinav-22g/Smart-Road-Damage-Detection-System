package com.example.smartroaddamage.detector

import android.content.Context
import android.util.Log
import org.pytorch.IValue
import org.pytorch.LiteModuleLoader
import org.pytorch.Module
import org.pytorch.Tensor
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Core PyTorch inference engine for road-anomaly detection.
 *
 * Model contract (new model — anomaly_detector.ptl, April 2026):
 *  - Input  : [1, 8, 20]  channel-major tensor (batch=1, channels=8, timesteps=20)
 *  - Output : 3 raw logits — index 0=Normal, 1=Pothole, 2=Speedbreaker
 *  - Bump   : derived by post-processing threshold logic (NOT a raw model class)
 *
 * Feature order (8 channels, in this exact sequence):
 *  0  Accel_X
 *  1  Accel_Y
 *  2  Accel_Z   (positive at rest ≈ +9.8 m/s²)
 *  3  Gyro_X
 *  4  Gyro_Y
 *  5  Gyro_Z
 *  6  accel_mag = sqrt(ax²+ay²+az²)   computed from RAW values before normalisation
 *  7  jerk      = |accel_mag[t] - accel_mag[t-1]|, jerk[0] = 0
 *
 * Parsing and mount-offset correction are done upstream in SmartRoadClassicManager
 * (IO dispatcher). This class does pure arithmetic only.
 */
class InferenceEngine(context: Context) {

    private val model: Module by lazy {
        val versionCode = context.packageManager
            .getPackageInfo(context.packageName, 0).longVersionCode
        val file = File(context.filesDir, "anomaly_detector_v${versionCode}.ptl")
        if (!file.exists()) {
            context.assets.open("anomaly_detector.ptl").use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
        }
        LiteModuleLoader.load(file.absolutePath)
    }

    // ── New model constants ────────────────────────────────────────────────────
    // Source: MODEL_ANDROID_INTEGRATION_GUIDE.md §5, verified against Anomaly2.ipynb output

    /** Must be 20. Any other value is incorrect for this model. */
    private val WINDOW_SIZE = 20

    /** Must be 8: ax, ay, az, gx, gy, gz, accel_mag, jerk. */
    private val NUM_CHANNELS = 8

    /**
     * StandardScaler means printed from Anomaly2.ipynb.
     * Note: SCALER_MEAN[2] is +10.55 because the model was trained with az positive-at-rest.
     */
    private val SCALER_MEAN = floatArrayOf(
        -0.449963f,   // Accel_X
         1.585829f,   // Accel_Y
        10.553956f,   // Accel_Z  (positive-at-rest convention)
        -0.015712f,   // Gyro_X
         0.014350f,   // Gyro_Y
         0.007273f,   // Gyro_Z
        11.196947f,   // accel_mag
         2.654355f    // jerk
    )

    private val SCALER_STD = floatArrayOf(
        1.205472f,    // Accel_X
        2.857317f,    // Accel_Y
        3.145069f,    // Accel_Z
        0.231811f,    // Gyro_X
        0.150908f,    // Gyro_Y
        0.148451f,    // Gyro_Z
        2.870641f,    // accel_mag
        3.354940f     // jerk
    )

    /**
     * ANOMALY_THRESH: gate for "is this anomalous at all?" applied to max(pPothole, pSpeedbreaker).
     * BUMP_CONF     : minimum subtype confidence needed to label as Pothole or Speedbreaker.
     *                 Falls below this → "Bump" (uncertain anomaly).
     */
    private val ANOMALY_THRESH = 0.83f
    private val BUMP_CONF      = 0.60f

    // Model outputs 3 logits. Final app facing labels include Bump (rule-based).
    private val MODEL_CLASS_NAMES  = arrayOf("Normal", "Pothole", "Speedbreaker")
    private val FINAL_CLASS_NAMES  = arrayOf("Normal", "Bump",    "Pothole", "Speedbreaker")

    // Reusable flat buffer for the [1, 8, 20] tensor and its shape descriptor.
    private val scaledBuffer = FloatArray(NUM_CHANNELS * WINDOW_SIZE)   // 8 * 20 = 160
    private val tensorShape  = longArrayOf(1L, NUM_CHANNELS.toLong(), WINDOW_SIZE.toLong())

    // ── Inference ─────────────────────────────────────────────────────────────

    /**
     * Run one inference cycle on a 20-sample IMU window.
     *
     * @param window  Exactly 20 consecutive [ImuSample] values (corrected by mount calibration).
     * @return        [AnomalyResult] with label, probabilities, and peak-impact timestamp.
     * @throws IllegalArgumentException if window size != 20.
     * @throws IllegalStateException    if NaN/Infinity detected in tensor or logit count != 3.
     */
    fun infer(window: List<ImuSample>): AnomalyResult {
        // ── Safeguard 1: window size ───────────────────────────────────────────
        require(window.size == WINDOW_SIZE) {
            "InferenceEngine: window must be $WINDOW_SIZE samples, got ${window.size}"
        }

        // ── Step 1: Derive accel_mag and jerk from RAW values (pre-normalisation) ──
        // Build an intermediate [20][8] row-store, then normalise and repack as [1,8,20].
        val rows = Array(WINDOW_SIZE) { FloatArray(NUM_CHANNELS) }
        var prevMag = 0f

        for (t in 0 until WINDOW_SIZE) {
            val s = window[t]

            // accel_mag from raw corrected accelerometer values
            val mag = sqrt(s.ax * s.ax + s.ay * s.ay + s.az * s.az)
            // jerk: first timestep = 0, subsequent = |mag[t] - mag[t-1]|
            val jerk = if (t == 0) 0f else abs(mag - prevMag)
            prevMag = mag

            rows[t][0] = s.ax
            rows[t][1] = s.ay
            rows[t][2] = s.az
            rows[t][3] = s.gx
            rows[t][4] = s.gy
            rows[t][5] = s.gz
            rows[t][6] = mag
            rows[t][7] = jerk
        }

        // ── Step 2: Normalise all 8 features (after derivation) ───────────────
        for (t in 0 until WINDOW_SIZE) {
            for (c in 0 until NUM_CHANNELS) {
                rows[t][c] = (rows[t][c] - SCALER_MEAN[c]) / SCALER_STD[c]
            }
        }

        // ── Step 3: Pack into channel-major flat buffer → [1, 8, 20] ──────────
        // Outer loop over channels, inner loop over time — matching PyTorch contiguous layout.
        var idx = 0
        for (c in 0 until NUM_CHANNELS) {
            for (t in 0 until WINDOW_SIZE) {
                scaledBuffer[idx++] = rows[t][c]
            }
        }

        // ── Safeguard 2: NaN / Infinity in tensor ─────────────────────────────
        for (i in scaledBuffer.indices) {
            check(!scaledBuffer[i].isNaN() && !scaledBuffer[i].isInfinite()) {
                "InferenceEngine: NaN/Infinity in scaledBuffer at position $i"
            }
        }

        // ── Step 4: Run TorchScript model ─────────────────────────────────────
        val tensor = Tensor.fromBlob(scaledBuffer, tensorShape)
        val logits = model.forward(IValue.from(tensor)).toTensor().dataAsFloatArray

        // ── Safeguard 3: output count must be 3 ───────────────────────────────
        check(logits.size == 3) {
            "InferenceEngine: expected 3 logits from model, got ${logits.size}"
        }

        // ── Step 5: Softmax over 3 logits ────────────────────────────────────
        val maxL   = logits.max()
        val expVals = FloatArray(3) { i -> exp((logits[i] - maxL).toDouble()).toFloat() }
        val expSum  = expVals.sum()
        val probs   = FloatArray(3) { i -> expVals[i] / expSum }

        val pNormal      = probs[0]
        val pPothole     = probs[1]
        val pSpeedbreaker = probs[2]

        // ── Step 6: Rule-based final label decoding ───────────────────────────
        //  pAnomaly  = max(pPothole, pSpeedbreaker)
        //  bestIdx   = 0 if pPothole >= pSpeedbreaker, else 1  (index into anomaly subtypes)
        //  ANOMALY_THRESH gate → if below, emit Normal
        //  BUMP_CONF sub-gate  → if above ANOMALY_THRESH but below BUMP_CONF, emit Bump
        //  Otherwise           → Pothole or Speedbreaker
        val pAnomaly  = maxOf(pPothole, pSpeedbreaker)
        val bestIdx   = if (pPothole >= pSpeedbreaker) 0 else 1   // 0=Pothole, 1=Speedbreaker

        val finalLabel = when {
            pAnomaly <= ANOMALY_THRESH                       -> "Normal"
            pAnomaly >= BUMP_CONF && bestIdx == 0            -> "Pothole"
            pAnomaly >= BUMP_CONF && bestIdx == 1            -> "Speedbreaker"
            else                                             -> "Bump"
        }

        // Final index for consumers that need a numeric code:
        //  0=Normal, 1=Bump, 2=Pothole, 3=Speedbreaker
        val finalIndex = FINAL_CLASS_NAMES.indexOf(finalLabel)
        val isAnomaly  = finalLabel != "Normal"

        // Log raw probabilities for bring-up debugging
        Log.d(
            TAG,
            "Infer: pNormal=%.3f pPothole=%.3f pSpb=%.3f | pAnomaly=%.3f → $finalLabel"
                .format(pNormal, pPothole, pSpeedbreaker, pAnomaly)
        )

        // ── Step 7: Find peak-impact sample (max total acceleration) ──────────
        val peakSample = window.maxByOrNull {
            it.ax * it.ax + it.ay * it.ay + it.az * it.az
        }!!

        // Build an extended allProbabilities array in FINAL_CLASS_NAMES order
        // [pNormal, pBump≈0, pPothole, pSpeedbreaker] — pBump is not a raw model output.
        // We store 0f for Bump to preserve the 4-element array contract used by AnomalyDetector.
        val allProbs = floatArrayOf(pNormal, 0f, pPothole, pSpeedbreaker)
        val laneSide = inferLaneSide(finalLabel, pAnomaly, peakSample, window)

        return AnomalyResult(
            label            = finalLabel,
            isAnomaly        = isAnomaly,
            confidence       = pAnomaly * 100f,                // best anomaly subtype confidence
            anomalyProbability = pAnomaly,
            allProbabilities = allProbs,                       // [Normal, Bump(0), Pothole, Speedbreaker]
            impactEspMs      = peakSample.espMs,
            impactLat        = null,    // delegated to AnomalyDetector.enrich()
            impactLng        = null,
            androidEpochMs   = 0L,      // delegated to AnomalyDetector.enrich()
            laneSide         = laneSide
        )
    }

    /**
     * Conservative first-pass lane-side inference.
     *
     * This intentionally prefers null over a bad label. Speedbreakers are
     * treated as centre hits; potholes/bumps only get LEFT/RIGHT when the
     * lateral gyro spike clearly dominates the local window.
     */
    private fun inferLaneSide(
        label: String,
        anomalyProbability: Float,
        peakSample: ImuSample,
        window: List<ImuSample>
    ): String? {
        if (anomalyProbability < BUMP_CONF) return null
        if (label == "Speedbreaker") return "CENTER"
        if (label == "Normal") return null

        val avgAbsGy = window.map { abs(it.gy) }.average().toFloat()
        val avgAbsGx = window.map { abs(it.gx) }.average().toFloat()
        val lateralSpike = peakSample.gy
        val lateralDominates = abs(lateralSpike) > maxOf(0.55f, avgAbsGy * 1.8f) &&
            abs(lateralSpike) > avgAbsGx * 1.2f

        return when {
            !lateralDominates -> null
            lateralSpike > 0f -> "LEFT"
            else -> "RIGHT"
        }
    }

    /** Trigger lazy model loading on a background thread so first inference is faster. */
    fun warmup() { model }

    fun destroy() = try { model.destroy() } catch (_: Exception) {}

    companion object {
        private const val TAG = "InferenceEngine"

        // ── Exposed for unit tests ─────────────────────────────────────────────
        const val WINDOW_SIZE_CONST  = 20
        const val NUM_CHANNELS_CONST = 8

        val SCALER_MEAN_TEST = floatArrayOf(
            -0.449963f, 1.585829f, 10.553956f,
            -0.015712f, 0.014350f,  0.007273f,
            11.196947f, 2.654355f
        )
        val SCALER_STD_TEST = floatArrayOf(
            1.205472f, 2.857317f, 3.145069f,
            0.231811f, 0.150908f, 0.148451f,
            2.870641f, 3.354940f
        )
    }
}
