package com.example.smartroaddamage.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * PreferencesManager — Handles app preferences using SharedPreferences.
 *
 * Manages:
 * - Onboarding completion status (coroutine Flow API + sync helpers for MainActivity)
 */
class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "app_preferences",
        Context.MODE_PRIVATE
    )

    private val _isOnboardingCompleted = MutableStateFlow(
        prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
    )

    /** Flow that emits true if onboarding has been completed. */
    val isOnboardingCompleted: Flow<Boolean> = _isOnboardingCompleted.asStateFlow()

    /** Coroutine-safe write — use from ViewModels / coroutines. */
    suspend fun setOnboardingCompleted(completed: Boolean) {
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, completed).apply()
        _isOnboardingCompleted.value = completed
    }

    // ── Synchronous helpers for MainActivity (pre-composition reads) ──────────

    /**
     * Synchronous read — used by MainActivity.onCreate() to determine the
     * start screen before the first Compose composition, avoiding a blank
     * flash or coroutine race condition on the splash transition.
     */
    fun isOnboardingCompletedSync(): Boolean =
        prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)

    /**
     * Synchronous write — called from the Onboarding onComplete lambda
     * (which runs on the Main thread inside a Composable callback).
     * SharedPreferences.apply() is non-blocking so this is safe on Main.
     */
    fun setOnboardingCompletedSync() {
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, true).apply()
        _isOnboardingCompleted.value = true
    }

    companion object {
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
    }
}
