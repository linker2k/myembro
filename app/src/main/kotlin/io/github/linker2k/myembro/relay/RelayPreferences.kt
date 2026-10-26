// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.relay

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class RelayConfig(val address: String = "", val key: String = "", val enabled: Boolean = false)

/**
 * "AI connection (experimental)" settings, inside the app only. The APP_KEY is AES-GCM encrypted with an
 * Android Keystore key that never leaves the device. The file is not in BackupPreferences (backup ZIP) and
 * is excluded from Android backup and device transfer (res/xml/backup_rules.xml, data_extraction_rules.xml).
 */
class RelayPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun read() = RelayConfig(prefs.getString(ADDRESS, "").orEmpty(),
        prefs.getString(KEY, null)?.let { runCatching { decrypt(it) }.getOrNull() }.orEmpty(), prefs.getBoolean(ENABLED, false))

    fun write(config: RelayConfig) {
        prefs.edit().putString(ADDRESS, config.address.trim())
            .putString(KEY, config.key.trim().takeIf { it.isNotEmpty() }?.let(::encrypt))
            .putBoolean(ENABLED, config.enabled).apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_BYTES)) }
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES))
    }

    companion object {
        /** Not in BackupPreferences.names; excluded by name in the backup rule files. */
        const val FILE = "relay_connection"
        private const val ADDRESS = "address"
        private const val KEY = "app_key"
        private const val ENABLED = "enabled"
        private const val ALIAS = "myembro_relay_app_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
    }
}
