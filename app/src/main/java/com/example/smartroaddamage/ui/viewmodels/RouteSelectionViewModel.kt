package com.example.smartroaddamage.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartroaddamage.data.HazardReadRepositories
import com.example.smartroaddamage.data.RouteAnalysis
import com.example.smartroaddamage.data.RouteRepository
import com.mappls.sdk.services.api.autosuggest.model.ELocation
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class RouteSelectionState {
    object Idle    : RouteSelectionState()
    object Loading : RouteSelectionState()
    data class Ready(val analyses: List<RouteAnalysis>) : RouteSelectionState()
    data class Error(val message: String)               : RouteSelectionState()
}

class RouteSelectionViewModel : ViewModel() {

    /**
     * Production default is the live canonical Firestore hazard collection.
     * For isolated UI bring-up, swap HazardReadRepositories.active back to
     * MockHazardReadRepository in one place.
     */
    private val routeRepository = RouteRepository(
        hazardReadRepository = HazardReadRepositories.active
    )

    private val _state       = MutableStateFlow<RouteSelectionState>(RouteSelectionState.Idle)
    val state: StateFlow<RouteSelectionState> = _state.asStateFlow()

    // Live autosuggest suggestions for the destination text field
    private val _suggestions = MutableStateFlow<List<ELocation>>(emptyList())
    val suggestions: StateFlow<List<ELocation>> = _suggestions.asStateFlow()

    // Live autosuggest suggestions for the origin text field
    private val _originSuggestions = MutableStateFlow<List<ELocation>>(emptyList())
    val originSuggestions: StateFlow<List<ELocation>> = _originSuggestions.asStateFlow()

    private var suggestJob: Job? = null
    private var originSuggestJob: Job? = null

    // ── Destination autosuggest — debounced 600ms, errors swallowed gracefully ─────────
    fun onDestinationTyped(query: String, userLat: Double, userLng: Double) {
        suggestJob?.cancel()
        if (query.length < 4) { _suggestions.value = emptyList(); return }
        suggestJob = viewModelScope.launch {
            delay(600)
            try {
                _suggestions.value = routeRepository.fetchSuggestions(query, userLat, userLng)
            } catch (e: Exception) {
                android.util.Log.e("RouteVM", "Suggest error: ${e.message}")
                _suggestions.value = emptyList()
            }
        }
    }

    // ── Origin autosuggest — debounced 600ms ─────────────────────────────────
    fun onOriginTyped(query: String) {
        originSuggestJob?.cancel()
        if (query.length < 4) { _originSuggestions.value = emptyList(); return }
        originSuggestJob = viewModelScope.launch {
            delay(600)
            try {
                _originSuggestions.value = routeRepository.fetchSuggestions(query, null, null)
            } catch (e: Exception) {
                android.util.Log.e("RouteVM", "Origin suggest error: ${e.message}")
                _originSuggestions.value = emptyList()
            }
        }
    }

    fun clearSuggestions()       { _suggestions.value = emptyList() }
    fun clearOriginSuggestions() { _originSuggestions.value = emptyList() }

    // ── Main route scan ───────────────────────────────────────────────────────
    fun searchRoutes(
        originText: String,
        originLat: Double?,
        originLng: Double?,
        originPin: String?,
        destination: String,
        destLat: Double?,
        destLng: Double?,
        destPin: String?,
        currentLat: Double,
        currentLng: Double
    ) {
        if (destination.isBlank()) return
        viewModelScope.launch {
            _state.value = RouteSelectionState.Loading
            _suggestions.value = emptyList()
            try {
                val results = routeRepository.analyseRoutes(
                    originText  = originText,
                    originLat   = originLat,
                    originLng   = originLng,
                    originPin   = originPin,
                    destination = destination,
                    destLat     = destLat,
                    destLng     = destLng,
                    destPin     = destPin,
                    currentLat  = currentLat,
                    currentLng  = currentLng
                )
                _state.value = if (results.isEmpty())
                    RouteSelectionState.Error("No routes found. Try a different destination.")
                else
                    RouteSelectionState.Ready(results)
            } catch (e: Exception) {
                _state.value = RouteSelectionState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun reset() { _state.value = RouteSelectionState.Idle; _suggestions.value = emptyList() }
}
