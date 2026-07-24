/*
 * SMS Import / Export: a simple Android app for importing and exporting SMS and MMS messages,
 * call logs, contacts, and blocked numbers from and to JSON / NDJSON files.
 *
 * Copyright (c) 2026 Thomas More
 *
 * This file is part of SMS Import / Export.
 *
 * SMS Import / Export is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * SMS Import / Export is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with SMS Import / Export.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.github.tmo1.sms_ie

import android.util.Log
import android.content.Context
import android.content.SharedPreferences
import com.goterl.lazysodium.exceptions.SodiumException
import com.goterl.lazysodium.interfaces.PwHash
import com.goterl.lazysodium.interfaces.SecretStream
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.sun.jna.NativeLong
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.min

const val DEFAULT_CHUNK_SIZE = 65536

const val ENCRYPTION_KEY_LENGTH = 32

fun passphraseToKey(
    passphrase: String, tCostInIterations: Int, mCostInKibibytes: Int, salt: ByteArray
): ByteArray {
    val key = ByteArray(ENCRYPTION_KEY_LENGTH)
    val passphraseByteArray = passphrase.toByteArray()
    if (!lazySodiumAndroid.cryptoPwHash(
            key,
            ENCRYPTION_KEY_LENGTH,
            passphraseByteArray,
            passphraseByteArray.size,
            salt,
            tCostInIterations.toLong(),
            NativeLong(mCostInKibibytes.toLong()),
            PwHash.Alg.PWHASH_ALG_ARGON2ID13
        )
    ) throw SodiumException("Key derivation failure, probably due to memory allocation failure")
    return key
}

/*fun passphraseToKey(passphrase: String, salt: ByteArray): ByteBuffer {
    val argon2Kt = Argon2Kt()
    val hashResult = argon2Kt.hash(
        mode = Argon2Mode.ARGON2_ID,
        password = passphrase.toByteArray(),
        salt = salt,
        tCostInIterations = T_COST_IN_ITERATIONS,
        mCostInKibibyte = M_COST_IN_KIBIBYTES,
        parallelism = PARALLELISM,
        hashLengthInBytes = ENCRYPTION_KEY_LENGTH,
    )
    return hashResult.rawHash
}*/

class PassphraseManager(private val passphraseKeyAlias: String, context: Context) {
    val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore")
    var cipher: Cipher
    var prefs: SharedPreferences

    init {
        keyStore.load(null)
        cipher = Cipher.getInstance("AES/GCM/NoPadding")
        prefs = PreferenceManager.getDefaultSharedPreferences(context)
    }

    fun storePassphrase(passphrase: String) {
        if (!keyStore.containsAlias(passphraseKeyAlias)) {
            val keyGenerator =
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            keyGenerator.init(
                KeyGenParameterSpec.Builder(
                    passphraseKeyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).setBlockModes(
                    KeyProperties.BLOCK_MODE_GCM
                ).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()
            )
            keyGenerator.generateKey()
        }
        val passphraseKey = keyStore.getKey(passphraseKeyAlias, null)
        cipher.init(Cipher.ENCRYPT_MODE, passphraseKey)
        prefs.edit {
            putString(
                "iv-and-encrypted_passphrase", Base64.encodeToString(
                    cipher.iv + cipher.doFinal(passphrase.toByteArray()), Base64.DEFAULT
                )
            )
        }
    }

    fun retrievePassphrase(): String? {
        val encryptedIvPassphrase = prefs.getString("iv-and-encrypted_passphrase", null)
        if (encryptedIvPassphrase == null) {
            Log.e(LOG_TAG, "Passphrase retrieval failure")
            return null
        }
        if (!keyStore.containsAlias(passphraseKeyAlias)) {
            Log.e(LOG_TAG, "Missing passphrase encryption key")
            return null
        }
        val passphraseKey = keyStore.getKey(passphraseKeyAlias, null)
        val decodedIvPassphrase = Base64.decode(encryptedIvPassphrase, Base64.DEFAULT)
        // The default authentication tag length is apparently 128, although I'm not sure where
        // this is officially documented.
        val parameterSpec = GCMParameterSpec(128, decodedIvPassphrase, 0, 12)
        cipher.init(Cipher.DECRYPT_MODE, passphraseKey, parameterSpec)
        val encryptedPassphrase = decodedIvPassphrase.sliceArray(12 until decodedIvPassphrase.size)
        return cipher.doFinal(encryptedPassphrase).decodeToString()
    }
}

class SecretStreamOutputStream(
    private val outputStream: OutputStream,
    key: ByteArray,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE
) : FilterOutputStream(outputStream) {
    private val st = SecretStream.State()
    private val header = ByteArray(SecretStream.HEADERBYTES)

    init {
        require(key.size == SecretStream.KEYBYTES && chunkSize > 0)
        outputStream.write(ByteBuffer.allocate(4).putInt(chunkSize).array())
        lazySodiumAndroid.cryptoSecretStreamInitPush(st, header, key)
        outputStream.write(header)
    }

    private val plaintextChunk = ByteArray(chunkSize)
    private var plaintextChunkPosition = 0
    private val ciphertextChunk = ByteArray(chunkSize + SecretStream.ABYTES)

    override fun write(b: Int) {
        if (plaintextChunkPosition == chunkSize) writeNonFinalChunk()
        plaintextChunk[plaintextChunkPosition++] = (b and 0xFF).toByte()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        require(off >= 0 && len >= 0 && off + len <= b.size)
        var bPosition = off
        val offPlusLen = off + len
        while (bPosition < offPlusLen) {
            if (plaintextChunkPosition == chunkSize) writeNonFinalChunk()
            val bytesToWrite = min(chunkSize - plaintextChunkPosition, offPlusLen - bPosition)
            b.copyInto(
                plaintextChunk, plaintextChunkPosition, bPosition, bPosition + bytesToWrite
            )
            plaintextChunkPosition += bytesToWrite
            bPosition += bytesToWrite
        }
    }

    private fun writeNonFinalChunk() {
        //Log.d(LOG_TAG, "Writing a chunk")
        sodium.crypto_secretstream_xchacha20poly1305_push(
            st,
            ciphertextChunk,
            null,
            plaintextChunk,
            chunkSize.toLong(),
            null,
            0,
            SecretStream.TAG_MESSAGE
        )
        outputStream.write(ciphertextChunk)
        plaintextChunkPosition = 0
    }

    override fun close() {
        //Log.d(LOG_TAG, "Writing final chunk")
        sodium.crypto_secretstream_xchacha20poly1305_push(
            st,
            ciphertextChunk,
            null,
            plaintextChunk,
            plaintextChunkPosition.toLong(),
            null,
            0,
            SecretStream.TAG_FINAL
        )
        outputStream.write(ciphertextChunk, 0, plaintextChunkPosition + SecretStream.ABYTES)
        super.close()
    }
}

class SecretStreamInputStream(
    private val inputStream: InputStream, key: ByteArray
) : FilterInputStream(inputStream) {
    private val st = SecretStream.State()
    private val header = ByteArray(SecretStream.HEADERBYTES)
    private var chunkSize = 0
    private var chunkNumber = 1

    init {
        require(key.size == SecretStream.KEYBYTES)
        val byteArray = ByteArray(4)
        inputStream.read(byteArray)
        chunkSize = ByteBuffer.wrap(byteArray).getInt()
        if (chunkSize < 0) throw IllegalArgumentException("Negative chunk size in file header - file may be invalid or corrupted")
        inputStream.read(header, 0, SecretStream.HEADERBYTES)
        if (!lazySodiumAndroid.cryptoSecretStreamInitPull(
                st, header, key
            )
        ) throw SodiumException("Incomplete SecretStream header")
    }

    private val plaintextChunk = ByteArray(chunkSize)
    private var plaintextChunkPosition = 0
    private var bytesInPlaintextChunk = 0
    private var finalChunkRead = false

    override fun read(): Int {
        if (plaintextChunkPosition == bytesInPlaintextChunk) readNextChunk()
        if (plaintextChunkPosition == -1) return -1
        return plaintextChunk[plaintextChunkPosition++].toInt()
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        require(off >= 0 && len >= 0 && off + len <= b.size)
        var bPosition = off
        val offPlusLen = off + len
        while (bPosition < offPlusLen) {
            if (plaintextChunkPosition == bytesInPlaintextChunk) readNextChunk()
            if (plaintextChunkPosition == -1) break
            val bytesToRead =
                min(bytesInPlaintextChunk - plaintextChunkPosition, offPlusLen - bPosition)
            plaintextChunk.copyInto(
                b, bPosition, plaintextChunkPosition, plaintextChunkPosition + bytesToRead
            )
            plaintextChunkPosition += bytesToRead
            bPosition += bytesToRead
        }
        return bPosition - off
    }

    private fun readNextChunk() {
        val tag = ByteArray(1)
        val ciphertextChunk = ByteArray(chunkSize + SecretStream.ABYTES)
        //Log.d(LOG_TAG, "Reading a chunk")
        val bytesRead = inputStream.read(ciphertextChunk, 0, ciphertextChunk.size)
        if (bytesRead == -1) {
            if (!finalChunkRead) throw SodiumException("End of file reached before end of stream")
            plaintextChunkPosition = -1
            return
        }
        if (finalChunkRead) throw SodiumException("End of stream reached before end of file")
        if (sodium.crypto_secretstream_xchacha20poly1305_pull(
                st, plaintextChunk, null, tag, ciphertextChunk, bytesRead.toLong(), null, 0
            ) != 0
        ) throw SodiumException("Decryption failure on chunk $chunkNumber")
        if (tag[0] == SecretStream.TAG_FINAL) finalChunkRead = true
        plaintextChunkPosition = 0
        bytesInPlaintextChunk = bytesRead - SecretStream.ABYTES
        chunkNumber++
    }
}
