package dev.hushyari.llm

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import timber.log.Timber
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure API key storage using Android Keystore with AES-256-GCM encryption.
 * 🧠 Roubao mechanic: Each provider's key is encrypted with a unique IV and
 * the master key is hardware-backed via AndroidKeyStore.
 */
@Singleton
class ApiKeyManager @Inject constructor() {

    companion object {
        private const val KEYSTORE_ALIAS = "hushyari_master_key"
        private const val KEY_ALGORITHM = KeyProperties.KEY_ALGORITHM_AES
        private const val BLOCK_MODE = KeyProperties.BLOCK_MODE_GCM
        private const val PADDING = KeyProperties.ENCRYPTION_PADDING_NONE
        private const val TRANSFORMATION = "$KEY_ALGORITHM/$BLOCK_MODE/$PADDING"
        private const val GCM_TAG_LENGTH = 128
        private const val MMKV_PREFIX = "api_key_"
    }

    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply {
        load(null)
    }

    private val prefs = com.tencent.mmkv.MMKV.defaultMMKV()

    private fun getOrCreateMasterKey(): SecretKey {
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            val entry = keyStore.getEntry(KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry
            return entry.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(KEY_ALGORITHM, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(BLOCK_MODE)
            .setEncryptionPaddings(PADDING)
            .setKeySize(256)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    /**
     * Encrypt and persist an API key for the given provider.
     */
    fun storeApiKey(provider: String, key: String) {
        try {
            val masterKey = getOrCreateMasterKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, masterKey)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(key.toByteArray(Charsets.UTF_8))

            val encoded = Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
            prefs.encode("$MMKV_PREFIX$provider", encoded)
            Timber.d("Stored API key for provider: $provider")
        } catch (e: Exception) {
            Timber.e(e, "Failed to store API key for $provider")
        }
    }

    /**
     * Retrieve and decrypt the API key for the given provider.
     */
    fun getApiKey(provider: String): String? {
        return try {
            val encoded = prefs.decodeString("$MMKV_PREFIX$provider") ?: return null
            val data = Base64.decode(encoded, Base64.NO_WRAP)

            val iv = data.copyOfRange(0, 12)
            val encrypted = data.copyOfRange(12, data.size)

            val masterKey = getOrCreateMasterKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, masterKey, spec)

            val decrypted = cipher.doFinal(encrypted)
            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            Timber.e(e, "Failed to retrieve API key for $provider")
            null
        }
    }

    /**
     * Remove the stored API key for a provider.
     */
    fun deleteApiKey(provider: String) {
        prefs.remove("$MMKV_PREFIX$provider")
        Timber.d("Deleted API key for provider: $provider")
    }

    /**
     * Return list of all providers for which an API key is stored.
     */
    fun getAllProviders(): List<String> {
        val providers = mutableListOf<String>()
        val allKeys = prefs.allKeys() ?: emptyArray()
        for (key in allKeys) {
            if (key.startsWith(MMKV_PREFIX)) {
                providers.add(key.removePrefix(MMKV_PREFIX))
            }
        }
        return providers
    }

    /**
     * Remove all stored keys.
     */
    fun deleteAll() {
        val providers = getAllProviders()
        providers.forEach { deleteApiKey(it) }
    }
}
