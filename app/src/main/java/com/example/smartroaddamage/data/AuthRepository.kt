package com.example.smartroaddamage.data

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.example.smartroaddamage.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await

private const val TAG = "AuthRepository"

/**
 * AuthRepository — Production-grade Firebase/Google authentication engine.
 *
 * Uses the 2026 Credential Manager API (androidx.credentials 1.5.0).
 *
 * Robust credential handling:
 * Play Services sometimes wraps the Google ID token inside a [CustomCredential]
 * rather than returning a [GoogleIdTokenCredential] directly. This version
 * explicitly checks the type string constant and calls [GoogleIdTokenCredential.createFrom]
 * to unpack it — more reliable across all Play Services versions.
 *
 * SHA-1 Reminder: If the bottom-sheet closes without signing in, verify your
 * debug SHA-1 is listed under com.example.smartroaddamage in Firebase Console
 * → Project Settings → General → Your Apps.
 */
class AuthRepository(private val context: Context) {
    private val auth = FirebaseAuth.getInstance()
    private val credentialManager = CredentialManager.create(context)

    suspend fun signInWithGoogle(): Result<FirebaseUser> {
        return try {
            // 1. Configure the Google ID option with the Web Client ID (client_type: 3)
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(context.getString(R.string.default_web_client_id))
                // Note: setAutoSelectEnabled(true) can cause silent failures on some devices;
                // keeping it false ensures the account picker always appears.
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            // 2. Invoke the system Credential Manager bottom-sheet
            val result = credentialManager.getCredential(context, request)
            val credential = result.credential

            // 3. Robust type check — Play Services may wrap the token as a CustomCredential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                // Unpack the token using the library's factory method
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val firebaseCredential =
                    GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
                val authResult = auth.signInWithCredential(firebaseCredential).await()
                Log.d(TAG, "Firebase sign-in success: uid=${authResult.user?.uid}")
                Result.success(authResult.user!!)
            } else {
                // Log the actual type so you can diagnose in Logcat → filter "AuthRepository"
                Log.e(TAG, "Unexpected credential type: ${credential.type}")
                Result.failure(
                    Exception("Handshake failed: received '${credential.type}' instead of Google ID token.")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "signInWithGoogle exception", e)
            Result.failure(e)
        }
    }

    fun getCurrentUser(): FirebaseUser? = auth.currentUser
}
