// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.export

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * P1-02 (Patch G): Keystore-backed AES-256-GCM vault for the auto-backup passphrase.
 *
 * Blueprint 15 / Audit P1-02:
 * Prevents storing the user's auto-backup passphrase in cleartext in SQLite or SharedPreferences.
 * The passphrase is encrypted with an AndroidKeyStore hardware-backed key before hitting the disk.
 */
object BackupPassphraseVault {
    private const val ALIAS = "mine_backup_passphrase_wrap"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val FILE_NAME = "wrapped_backup_passphrase.bin"

    private fun ensureKeystoreKey(keyStore: KeyStore) {
        if (!keyStore.containsAlias(ALIAS)) {
            val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGen.init(spec)
            keyGen.generateKey()
        }
    }

    fun savePassphrase(context: Context, passphrase: String) {
        val file = File(context.filesDir, FILE_NAME)
        if (passphrase.isBlank()) {
            file.delete()
            return
        }

        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        ensureKeystoreKey(keyStore)
        val secretKey = keyStore.getKey(ALIAS, null) as SecretKey

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(passphrase.toByteArray(Charsets.UTF_8))

        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        java.io.FileOutputStream(tmp).use { fos ->
            fos.write(byteArrayOf(iv.size.toByte()))
            fos.write(iv)
            fos.write(encrypted)
            fos.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    fun getPassphrase(context: Context): String? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null

        return try {
            val bytes = file.readBytes()
            if (bytes.isEmpty()) return null
            val ivSize = bytes[0].toInt()
            if (bytes.size < 1 + ivSize) return null
            val iv = bytes.copyOfRange(1, 1 + ivSize)
            val ciphertext = bytes.copyOfRange(1 + ivSize, bytes.size)

            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            val secretKey = keyStore.getKey(ALIAS, null) as? SecretKey ?: return null

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            val decrypted = cipher.doFinal(ciphertext)
            String(decrypted, Charsets.UTF_8)
        } catch (t: Throwable) {
            android.util.Log.w("BackupPassphraseVault", "Failed to unwrap backup passphrase: ${t.message}")
            null
        }
    }

    fun clearPassphrase(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
    }
}
