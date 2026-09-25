package com.minashin1120.voxcribe.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * APIキーの暗号化保存（Web版の Fernet 暗号化保存に相当）。
 * 鍵は Android Keystore の AES-GCM 鍵で、端末外へ持ち出せない。
 */
class SecretStore(context: Context) {
    private val sp = context.getSharedPreferences("voxcribe_secrets", Context.MODE_PRIVATE)

    enum class KeyType(val prefKey: String) { GEMINI("gemini"), XAI("xai"), OPENAI("openai") }

    fun has(type: KeyType): Boolean {
        val value = get(type)
        return if (type == KeyType.XAI) isPlausibleXaiApiKey(value) else !value.isNullOrEmpty()
    }

    fun get(type: KeyType): String? {
        val stored = sp.getString(type.prefKey, null) ?: return null
        return try {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, 12)
            val ct = raw.copyOfRange(12, raw.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun put(type: KeyType, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val out = cipher.iv + ct
        sp.edit { putString(type.prefKey, Base64.encodeToString(out, Base64.NO_WRAP)) }
    }

    fun clearAll() = sp.edit { clear() }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    companion object {
        fun isPlausibleXaiApiKey(value: String?): Boolean {
            val normalized = value?.trim().orEmpty()
            return normalized.length > 4 && normalized.startsWith("xai-")
        }

        private const val ALIAS = "voxcribe_api_keys"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
