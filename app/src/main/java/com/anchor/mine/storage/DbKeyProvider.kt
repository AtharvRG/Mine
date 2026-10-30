// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class DbKeyProvider(private val context: Context) {

    companion object {
        private const val ALIAS = "mine_db_key_wrap"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val FILE_NAME = "wrapped_db.key"
    }

    /**
     * Returns the 256-bit raw key for SQLCipher.
     * Generates and wraps a new one if it doesn't exist.
     */
    fun getOrGenerateKey(): ByteArray {
        val keyFile = File(context.filesDir, FILE_NAME)
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

        ensureKeystoreKey(keyStore)

        if (!keyFile.exists()) {
            val rawKey = ByteArray(32).apply { SecureRandom().nextBytes(this) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val secretKey = keyStore.getKey(ALIAS, null) as SecretKey
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)

            val iv = cipher.iv
            val encrypted = cipher.doFinal(rawKey)

            // Atomic write using a temporary file and sync
            val tmpFile = File(context.filesDir, "$FILE_NAME.tmp")
            java.io.FileOutputStream(tmpFile).use { fos ->
                fos.write(byteArrayOf(iv.size.toByte()))
                fos.write(iv)
                fos.write(encrypted)
                fos.fd.sync() // Ensure it hits the disk
            }
            if (!tmpFile.renameTo(keyFile)) {
                throw IllegalStateException("Failed to commit database key to disk")
            }
            return rawKey
        }

        // Unwrap existing
        val bytes = keyFile.readBytes()
        val ivSize = bytes[0].toInt()
        val iv = bytes.copyOfRange(1, 1 + ivSize)
        val encrypted = bytes.copyOfRange(1 + ivSize, bytes.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = keyStore.getKey(ALIAS, null) as SecretKey
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))

        return cipher.doFinal(encrypted)
    }

    private fun ensureKeystoreKey(keyStore: KeyStore) {
        if (!keyStore.containsAlias(ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            keyGenerator.init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    // Deliberately NOT setting setUserAuthenticationRequired(true) per D-030
                    .build()
            )
            keyGenerator.generateKey()
        }
    }
}