package com.vougadev.shell.auth

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

/**
 * SIGN IN WITH GOOGLE, NATIVELY: Google refuses its sign-in inside a WebView, so the account service's page asks the
 * app (`VougaDevNative.nativeSignIn({provider: "google", nonce})`) and gets Google's ID token for its own client
 * ([com.vougadev.shell.ShellConfig.googleServerClientId]) with the account's address and name, which it checks and signs the
 * person in with.
 */
object GoogleSignIn {

    sealed interface Result {
        data class Signed(val idToken: String, val email: String?, val name: String?) : Result
        data object Cancelled : Result
        data object Failed : Result
    }

    suspend fun signIn(activity: Activity, serverClientId: String, nonce: String? = null): Result {
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(serverClientId)
            .setFilterByAuthorizedAccounts(false)
            // the account service's page's one-time nonce: a token caught elsewhere is refused there
            .setNonce(nonce)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            val credential = CredentialManager.create(activity).getCredential(activity, request).credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val google = GoogleIdTokenCredential.createFrom(credential.data)
                Result.Signed(google.idToken, google.id, google.displayName)
            } else {
                Result.Failed
            }
        } catch (_: GetCredentialCancellationException) {
            Result.Cancelled
        } catch (_: GetCredentialException) {
            Result.Failed
        } catch (_: GoogleIdTokenParsingException) {
            Result.Failed
        }
    }
}
