package dev.readthat.client

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * AES-GCM session storage backed by non-exportable Android Keystore keys.
 *
 * The mature Android app shipped [LEGACY_PREFERENCES] and [LEGACY_KEY_ALIAS] before the KMP
 * client existed. Keeping a bounded compatibility window here lets the shared client become the
 * production host without signing users out. New values are mirrored to the legacy envelope so a
 * rollback build can still authenticate; logout and account reset clear both stores.
 */
class AndroidSecureSessionStore internal constructor(
    private val current: AndroidSessionEnvelopeStore,
    private val legacy: AndroidSessionEnvelopeStore,
    private val json: Json = Json { encodeDefaults = true },
) : SecureSessionStore {
    constructor(context: Context) : this(
        current = KeystoreSessionEnvelopeStore(
            context.applicationContext,
            CURRENT_PREFERENCES,
            CURRENT_KEY_ALIAS,
        ),
        legacy = KeystoreSessionEnvelopeStore(
            context.applicationContext,
            LEGACY_PREFERENCES,
            LEGACY_KEY_ALIAS,
        ),
    )

    override suspend fun readSession(): StoredSession? {
        decode(current)?.let { return it }
        val migrated = decode(legacy) ?: return null
        // Authentication can continue even if a storage-pressure failure prevents the migration
        // write. The next restore will retry from the still-encrypted legacy envelope.
        runCatching { current.writeSessionEnvelope(encode(current, migrated)) }
        migrateBookmarkIfNeeded()
        return migrated
    }

    override suspend fun writeSession(session: StoredSession) {
        current.writeSessionEnvelope(encode(current, session))
        // A compatibility mirror makes a rollback safe. The primary write is authoritative;
        // inability to update an obsolete alias must not fail a successful token refresh.
        runCatching { legacy.writeSessionEnvelope(encode(legacy, session)) }
    }

    override suspend fun clearSession() {
        current.clearSessionEnvelope()
        runCatching { legacy.clearSessionEnvelope() }
    }

    override suspend fun readBookmark(): String? {
        current.readBookmark()?.let { return it }
        return legacy.readBookmark()?.also { bookmark ->
            runCatching { current.writeBookmark(bookmark) }
        }
    }

    override suspend fun writeBookmark(value: String) {
        current.writeBookmark(value)
        runCatching { legacy.writeBookmark(value) }
    }

    override suspend fun clearBookmark() {
        current.clearBookmark()
        runCatching { legacy.clearBookmark() }
    }

    private fun decode(store: AndroidSessionEnvelopeStore): StoredSession? {
        val encoded = store.readSessionEnvelope() ?: return null
        return runCatching {
            json.decodeFromString<StoredSession>(store.decrypt(encoded))
        }.getOrElse {
            // A malformed or no-longer-decryptable envelope is not retried forever. If this is
            // the current store, readSession still gets a chance to recover the legacy session.
            runCatching { store.clearSessionEnvelope() }
            null
        }
    }

    private fun encode(store: AndroidSessionEnvelopeStore, session: StoredSession): String =
        store.encrypt(json.encodeToString(session))

    private fun migrateBookmarkIfNeeded() {
        if (current.readBookmark() != null) return
        legacy.readBookmark()?.let { bookmark -> runCatching { current.writeBookmark(bookmark) } }
    }

    private companion object {
        const val CURRENT_PREFERENCES = "shared_backend_session"
        const val CURRENT_KEY_ALIAS = "readthat_shared_session_v1"
        const val LEGACY_PREFERENCES = "backend_session"
        const val LEGACY_KEY_ALIAS = "sdui_backend_session_v1"
    }
}

/** Injectable encrypted-envelope boundary; Android host tests use an in-memory implementation. */
internal interface AndroidSessionEnvelopeStore {
    fun readSessionEnvelope(): String?
    fun writeSessionEnvelope(value: String)
    fun clearSessionEnvelope()
    fun readBookmark(): String?
    fun writeBookmark(value: String)
    fun clearBookmark()
    fun encrypt(cleartext: String): String
    fun decrypt(encoded: String): String
}

private class KeystoreSessionEnvelopeStore(
    context: Context,
    preferencesName: String,
    private val keyAlias: String,
) : AndroidSessionEnvelopeStore {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override fun readSessionEnvelope(): String? = preferences.getString(SESSION, null)

    override fun writeSessionEnvelope(value: String) {
        preferences.edit().putString(SESSION, value).apply()
    }

    override fun clearSessionEnvelope() {
        preferences.edit().remove(SESSION).apply()
    }

    override fun readBookmark(): String? = preferences.getString(BOOKMARK, null)

    override fun writeBookmark(value: String) {
        preferences.edit().putString(BOOKMARK, value).apply()
    }

    override fun clearBookmark() {
        preferences.edit().remove(BOOKMARK).apply()
    }

    override fun encrypt(cleartext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(cleartext.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    override fun decrypt(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, IV_BYTES)))
        return String(cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size)), StandardCharsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val SESSION = "session"
        const val BOOKMARK = "d1_bookmark"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
