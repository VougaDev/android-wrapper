package com.vougadev.shell.auth

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import com.vougadev.shell.ShellConfig
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals a secret for the disk and opens it again; null when it cannot be opened (another key, a damaged value). */
interface Sealer {
    fun seal(plain: String): String

    fun open(sealed: String): String?
}

/** Where sealed values are kept: SharedPreferences on the phone, a map in the tests. */
interface Values {
    fun get(key: String): String?

    fun put(key: String, value: String?)
}

/**
 * THE APP'S REFRESH TOKEN, SEALED WITH A KEY OF THE ANDROID KEYSTORE. the account service rotates it: every refresh answers a new one
 * and the old one is spent, so [save] is called with each answer and the new token replaces the old at once.
 *
 * The access token is never written: it lives a few minutes, is traded at once for the site's session (`/auth/app-session/`)
 * and a lost session refreshes anyway. A sealed value that no longer opens (the key went with a restore to another
 * phone, or the value is damaged) is dropped: the person signs in again, nothing is guessed.
 */
class TokenStore(private val values: Values, private val sealer: Sealer) {

    @Synchronized
    fun refreshToken(): String? {
        val sealed = values.get(KEY_REFRESH) ?: return null
        val plain = sealer.open(sealed)
        if (plain.isNullOrEmpty()) values.put(KEY_REFRESH, null)
        return plain?.takeIf { it.isNotEmpty() }
    }

    /** A new (rotated) refresh token; a blank one keeps the current token, as OAuth allows a refresh to answer none. */
    @Synchronized
    fun save(refreshToken: String?) {
        if (refreshToken.isNullOrBlank()) return
        values.put(KEY_REFRESH, sealer.seal(refreshToken))
    }

    /**
     * THE SCOPES THE KEPT TOKEN'S GRANT WAS MADE WITH: a refresh keeps them, so a build asking for more must sign in
     * again. A token kept before this was written came from a build asking the shell's defaults.
     */
    @Synchronized
    fun grantedScopes(): String = values.get(KEY_SCOPES) ?: ShellConfig.DEFAULT_SCOPES

    @Synchronized
    fun saveGrantedScopes(scopes: String) = values.put(KEY_SCOPES, scopes)

    @Synchronized
    fun clear() {
        values.put(KEY_REFRESH, null)
        values.put(KEY_SCOPES, null)
    }

    /** A token is kept (not opened: cheap enough for the main thread; one that no longer opens fails its renewal). */
    val signedIn: Boolean get() = values.get(KEY_REFRESH) != null

    private companion object {
        const val KEY_REFRESH = "refresh_token"
        const val KEY_SCOPES = "granted_scopes"
    }
}

/** [Values] over SharedPreferences. */
class PrefsValues(private val prefs: SharedPreferences) : Values {
    override fun get(key: String): String? = prefs.getString(key, null)

    // commit, not apply: a rotated refresh token must be on disk before the old one is gone from memory
    override fun put(key: String, value: String?) = prefs.edit(commit = true) {
        if (value == null) remove(key) else putString(key, value)
    }
}

/**
 * AES-256-GCM with a key that never leaves the Android Keystore. The sealed text is `base64(iv):base64(ciphertext)`.
 */
class KeystoreSealer(private val alias: String) : Sealer {

    override fun seal(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return b64(cipher.iv) + ":" + b64(body)
    }

    override fun open(sealed: String): String? {
        return try {
            val (iv, body) = sealed.split(':', limit = 2).let { Base64.decode(it[0], Base64.NO_WRAP) to Base64.decode(it[1], Base64.NO_WRAP) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, existingKey() ?: return null, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun existingKey(): SecretKey? {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    private fun key(): SecretKey = existingKey() ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
        init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        generateKey()
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
