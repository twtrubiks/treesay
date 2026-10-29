package com.twtrubiks.treesay

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Claude API key 的保管處。用 Android Keystore 裡的 AES 金鑰加密後，放在 App 私有的 SharedPreferences。
 * Keystore 的金鑰拿不出這支手機，加密後的內容就算被抄走也解不開。
 * key 不打包進 APK、不寫進 repo；`allowBackup="false"`，也不會跟著備份到雲端。
 */
class ApiKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences("api_key", Context.MODE_PRIVATE)

    /** 沒設定過、或解不開（例如 Keystore 的金鑰被系統清掉）都回 null，請使用者重新輸入。 */
    fun get(): String? {
        val stored = prefs.getString(PREF_ENCRYPTED, null) ?: return null
        return try {
            decrypt(stored)
        } catch (e: GeneralSecurityException) {
            clear()
            null
        } catch (e: IllegalArgumentException) {
            clear()
            null
        }
    }

    fun save(apiKey: String) = prefs.edit { putString(PREF_ENCRYPTED, encrypt(apiKey)) }

    fun clear() = prefs.edit { remove(PREF_ENCRYPTED) }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    // 存的是 IV（12 bytes）接著密文，整串 Base64
    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()))
    }

    private fun decrypt(stored: String): String {
        val bytes = Base64.getDecoder().decode(stored)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
        }
        return String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE))
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "treesay_api_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val PREF_ENCRYPTED = "encrypted"
    }
}
