package com.example.smartroaddamage.utils

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Small, lifecycle-safe TTS wrapper used for route hazard warnings.
 *
 * The engine is created lazily on the first request and repeated calls are debounced so the
 * driver does not get spammed while approaching the same cluster of hazards.
 */
class TtsManager(context: Context) {

    private val appContext = context.applicationContext

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var isInitializing = false
    private var lastSpokenMs = 0L
    private var pendingUtterance: String? = null

    fun speak(hazardType: String, distanceMeters: Int) {
        val now = System.currentTimeMillis()
        if (now - lastSpokenMs < DEBOUNCE_MS) return

        val clampedDistance = distanceMeters.coerceAtLeast(1)
        val utterance = "Caution. $hazardType detected ahead. $clampedDistance metres."

        if (!ensureReady(utterance)) return

        lastSpokenMs = now
        speakInternal(utterance, now)
    }

    fun destroy() {
        pendingUtterance = null
        isInitializing = false
        isReady = false
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun ensureReady(utterance: String): Boolean {
        if (isReady && tts != null) return true

        pendingUtterance = utterance
        if (tts != null || isInitializing) return false

        isInitializing = true
        tts = TextToSpeech(appContext) { status ->
            isInitializing = false
            isReady = status == TextToSpeech.SUCCESS
            if (!isReady) {
                Log.w(TAG, "TTS initialization failed with status=$status")
            } else {
                val languageResult = tts?.setLanguage(Locale.US) ?: TextToSpeech.LANG_NOT_SUPPORTED
                if (
                    languageResult == TextToSpeech.LANG_MISSING_DATA ||
                    languageResult == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    Log.w(TAG, "TTS language Locale.US is unavailable")
                }

                pendingUtterance?.let { queued ->
                    val timestamp = System.currentTimeMillis()
                    lastSpokenMs = timestamp
                    speakInternal(queued, timestamp)
                    pendingUtterance = null
                }
            }
        }
        return false
    }

    private fun speakInternal(utterance: String, timestampMs: Long) {
        tts?.speak(
            utterance,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "hazard_$timestampMs"
        )
        Log.d(TAG, utterance)
    }

    companion object {
        private const val TAG = "TtsManager"
        private const val DEBOUNCE_MS = 10_000L
    }
}
