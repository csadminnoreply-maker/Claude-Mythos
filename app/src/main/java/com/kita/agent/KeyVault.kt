package com.kita.agent

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Enkripsi API key dengan kunci AES yang tersimpan di Android Keystore (tidak bisa diekstrak). */
internal object KeyVault {
    private const val ALIAS = "cmythos_key"
    private const val PREFIX = "enc:"

    private fun secret(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return g.generateKey()
    }

    fun isEncrypted(s: String) = s.startsWith(PREFIX)

    fun enc(plain: String): String {
        if (plain.isEmpty() || isEncrypted(plain)) return plain
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, secret())
            val ct = c.doFinal(plain.toByteArray())
            PREFIX + Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
        } catch (_: Exception) {
            plain
        }
    }

    fun dec(stored: String): String {
        if (!isEncrypted(stored)) return stored
        return try {
            val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12))
        } catch (_: Exception) {
            ""
        }
    }

    fun mask(k: String): String =
        if (k.length <= 8) "••••" else k.take(3) + "…" + k.takeLast(4)
}
