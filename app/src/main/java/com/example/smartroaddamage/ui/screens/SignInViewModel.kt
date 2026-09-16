package com.example.smartroaddamage.ui.screens

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.smartroaddamage.data.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * UI state for the Sign-In screen.
 *
 * @param isAuthenticating  True while the Firebase handshake is in-flight.
 * @param isAuthenticated   True once the user is confirmed signed-in.
 * @param errorMessage      Non-null when authentication failed, null otherwise.
 */
data class SignInUiState(
    val isAuthenticating: Boolean = false,
    val isAuthenticated: Boolean = false,
    val errorMessage: String? = null
)

/**
 * SignInViewModel — Orchestrates the production Google/Firebase authentication flow.
 *
 * Replaces the former delay(2000) mock with a real AuthRepository call.
 * State is exposed as a StateFlow so the Composable can collect reactively.
 *
 * Dependency Injection: Provided via [SignInViewModelFactory] which is constructed
 * in the hosting Composable using [AuthRepository(context)].
 */
class SignInViewModel(private val authRepository: AuthRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(SignInUiState())
    val uiState: StateFlow<SignInUiState> = _uiState

    fun onPilotLoginClicked() {
        viewModelScope.launch {
            // 1. Trigger the loading / scanning overlay
            _uiState.value = SignInUiState(isAuthenticating = true)

            // 2. Execute real Firebase Credential Manager handshake
            val result = authRepository.signInWithGoogle()

            // 3. Reflect cloud response in UI state
            result.fold(
                onSuccess = {
                    _uiState.value = SignInUiState(isAuthenticated = true)
                },
                onFailure = { error ->
                    _uiState.value = SignInUiState(
                        isAuthenticating = false,
                        errorMessage = error.localizedMessage ?: "Pilot authentication failed."
                    )
                }
            )
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
}

/**
 * Factory for injecting [AuthRepository] into [SignInViewModel].
 * Used from the Composable: viewModel(factory = SignInViewModelFactory(context))
 */
class SignInViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return SignInViewModel(AuthRepository(context)) as T
    }
}
