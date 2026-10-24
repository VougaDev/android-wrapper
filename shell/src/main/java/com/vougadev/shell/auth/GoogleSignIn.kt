package com.vougadev.shell.auth

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

/**
 * SIGN IN WITH GOOGLE, NATIVELY: Google refuses its sign-in inside a WebView, so the account service's page asks the
 * app (`VougaDevNative.nativeSignIn({provider: "google"})`) and gets Google's ID token for its own client
 * ([com.vougadev.shell.ShellConfig.googleServerClientId]), which it checks and signs the person in with.
 */
object GoogleSignIn {

    /** The ID token, or null when the person cancelled or the phone has no Google account to offer. */
    suspend fun idToken(activity: Activity, serverClientId: String): String? {
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(serverClientId)
            .setFilterByAuthorizedAccounts(false)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            val credential = CredentialManager.create(activity).getCredential(activity, request).credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                GoogleIdTokenCredential.createFrom(credential.data).idToken
            } else {
                null
            }
        } catch (_: GetCredentialException) {
            null
        } catch (_: GoogleIdTokenParsingException) {
            null
        }
    }
}
