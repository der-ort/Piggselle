/*
 * Copyright 2026 Daniel (der-ort)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.assistant.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

private const val TAG = "PGSecureStore"
private const val KEY_ALIAS = "piggselle_data_key_v1"
private const val FORMAT_VERSION: Byte = 1
private const val IV_LENGTH = 12
private const val TAG_LENGTH_BITS = 128

/**
 * Small encrypted file store for everything personal: memories, personas, private terms and the
 * network log.
 *
 * Files are AES-256-GCM encrypted with a key that lives in the Android Keystore (hardware-backed on
 * the Pixel 9 Pro's TEE) and never leaves it. Layout: `[version:1][iv:12][ciphertext+tag]`. Writes
 * go to a temp file first and are renamed into place, so a crash cannot leave half a file behind.
 */
object SecureStore {
  val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  private val lock = Any()

  fun dir(context: Context): File =
    File(context.applicationContext.noBackupFilesDir, "piggselle").apply { mkdirs() }

  fun file(context: Context, name: String): File = File(dir(context), name)

  private fun key(): SecretKey {
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let {
      return it
    }
    val generator =
      KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    generator.init(
      KeyGenParameterSpec.Builder(
          KEY_ALIAS,
          KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build()
    )
    return generator.generateKey()
  }

  fun writeBytes(file: File, plaintext: ByteArray) {
    synchronized(lock) {
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.ENCRYPT_MODE, key())
      val iv = cipher.iv
      require(iv.size == IV_LENGTH) { "Unexpected IV length ${iv.size}" }
      val ciphertext = cipher.doFinal(plaintext)
      val tmp = File(file.parentFile, "${file.name}.tmp")
      tmp.outputStream().use { out ->
        out.write(byteArrayOf(FORMAT_VERSION))
        out.write(iv)
        out.write(ciphertext)
        out.fd.sync()
      }
      if (!tmp.renameTo(file)) {
        file.delete()
        if (!tmp.renameTo(file)) error("Could not move ${tmp.name} into place")
      }
    }
  }

  fun readBytes(file: File): ByteArray? {
    synchronized(lock) {
      if (!file.exists()) return null
      val data = file.readBytes()
      if (data.size <= 1 + IV_LENGTH || data[0] != FORMAT_VERSION) {
        Log.w(TAG, "Ignoring unreadable store file ${file.name}")
        return null
      }
      val iv = data.copyOfRange(1, 1 + IV_LENGTH)
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
      return cipher.doFinal(data, 1 + IV_LENGTH, data.size - 1 - IV_LENGTH)
    }
  }

  fun <T> read(context: Context, name: String, serializer: KSerializer<T>): T? {
    return try {
      readBytes(file(context, name))?.let { json.decodeFromString(serializer, it.decodeToString()) }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to read $name", e)
      null
    }
  }

  fun <T> write(context: Context, name: String, serializer: KSerializer<T>, value: T) {
    try {
      writeBytes(file(context, name), json.encodeToString(serializer, value).encodeToByteArray())
    } catch (e: Exception) {
      Log.e(TAG, "Failed to write $name", e)
    }
  }

  fun delete(context: Context, name: String) {
    synchronized(lock) { file(context, name).delete() }
  }
}
