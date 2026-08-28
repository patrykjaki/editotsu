package ani.dantotsu.connections.mal

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object MALKeystore {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "MAL_ENCRYPTION_KEY"
    private const val AES_GCM_NOPADDING = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128
    const val ENVELOPE_PREFIX = "v1:"

    interface KeyProvider {
        fun getSecretKey(): SecretKey
    }

    class AndroidKeyStoreProvider : KeyProvider {
        override fun getSecretKey(): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE
                )
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
            }
            val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry
            return entry.secretKey
        }
    }

    class SoftwareKeyProvider(private val secretKey: SecretKey = generateSoftwareKey()) : KeyProvider {
        companion object {
            fun generateSoftwareKey(): SecretKey {
                val bytes = ByteArray(32)
                SecureRandom().nextBytes(bytes)
                return SecretKeySpec(bytes, "AES")
            }
        }
        override fun getSecretKey(): SecretKey = secretKey
    }

    internal var keyProvider: KeyProvider = AndroidKeyStoreProvider()

    internal fun resetForTests() {
        keyProvider = AndroidKeyStoreProvider()
    }

    /**
     * Encrypts plaintext using AES-256-GCM with versioned envelope.
     * Fail-closed: Never returns plaintext on error. Returns Result.success or Result.failure.
     */
    fun encrypt(plaintext: String): Result<String> {
        return try {
            val key = keyProvider.getSecretKey()
            val cipher = Cipher.getInstance(AES_GCM_NOPADDING)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
            val base64 = Base64.getUrlEncoder().withoutPadding().encodeToString(combined)
            Result.success("$ENVELOPE_PREFIX$base64")
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /**
     * Decrypts versioned ciphertext envelope ("v1:<base64(iv || ciphertext)>").
     * Fail-closed: Never returns raw payload or unauthenticated plaintext on failure.
     */
    fun decrypt(encryptedEnvelope: String?): Result<String> {
        if (encryptedEnvelope.isNullOrBlank()) {
            return Result.failure(IllegalArgumentException("Encrypted envelope is null or blank"))
        }
        if (!encryptedEnvelope.startsWith(ENVELOPE_PREFIX)) {
            return Result.failure(SecurityException("Invalid or untrusted ciphertext envelope"))
        }
        return try {
            val rawBase64 = encryptedEnvelope.removePrefix(ENVELOPE_PREFIX)
            val combined = Base64.getUrlDecoder().decode(rawBase64)
            if (combined.size <= GCM_IV_LENGTH) {
                return Result.failure(SecurityException("Ciphertext payload too short"))
            }
            val iv = ByteArray(GCM_IV_LENGTH)
            val ciphertext = ByteArray(combined.size - GCM_IV_LENGTH)
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH)
            System.arraycopy(combined, GCM_IV_LENGTH, ciphertext, 0, ciphertext.size)

            val key = keyProvider.getSecretKey()
            val cipher = Cipher.getInstance(AES_GCM_NOPADDING)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            val decryptedBytes = cipher.doFinal(ciphertext)
            Result.success(String(decryptedBytes, Charsets.UTF_8))
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }
}
