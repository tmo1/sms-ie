/*
 * SMS Import / Export: a simple Android app for importing and exporting SMS and MMS messages,
 * call logs, contacts, and blocked numbers from and to JSON / NDJSON files.
 *
 * Copyright (c) 2021-2022,2024-2026 Thomas More
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

/*
 * This file contains various utility functions used by the various import and export routines
 * (which are in their own eponymous files), as well as the message wiping and counting routines.
 */

package com.github.tmo1.sms_ie

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build.VERSION.SDK_INT
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import androidx.work.Data
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Locale

data class Progress(
    val current: Int,
    val total: Int,
    val message: String?,
    val canCancel: Boolean = false,
) {
    constructor(workData: Data) : this(
        workData.getInt("current", 0),
        workData.getInt("total", 0),
        workData.getString("message"),
        workData.getBoolean("can_cancel", false),
    )

    fun toWorkData(): Data = workDataOf(
        "current" to current,
        "total" to total,
        "message" to message,
        "can_cancel" to canCancel,
    )
}

class UserFriendlyException(message: String? = null, cause: Throwable? = null) :
    Exception(message, cause)

fun checkReadSMSPermission(appContext: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        appContext, Manifest.permission.READ_SMS
    ) == PackageManager.PERMISSION_GRANTED
}

fun checkReadCallLogsContactsPermissions(appContext: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        appContext, Manifest.permission.READ_CALL_LOG
    ) == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(
        appContext, Manifest.permission.READ_CONTACTS
    ) == PackageManager.PERMISSION_GRANTED
}

fun checkReadWriteCallLogPermissions(appContext: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        appContext, Manifest.permission.WRITE_CALL_LOG
    ) == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(
        appContext, Manifest.permission.READ_CALL_LOG
    ) == PackageManager.PERMISSION_GRANTED
}

fun checkReadContactsPermission(appContext: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        appContext, Manifest.permission.READ_CONTACTS
    ) == PackageManager.PERMISSION_GRANTED
}

fun checkWriteContactsPermission(appContext: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        appContext, Manifest.permission.WRITE_CONTACTS
    ) == PackageManager.PERMISSION_GRANTED
}

fun lookupDisplayName(
    appContext: Context, displayNames: MutableMap<String, String?>, address: String?
): String? {
//        look up display name by phone number
    if (address == null || address == "") return null
    if (displayNames[address] != null) return displayNames[address]
    val displayName: String?
    val uri = Uri.withAppendedPath(
        ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address)
    )
    val nameCursor = appContext.contentResolver.query(
        uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
    )
    nameCursor.use {
        displayName = if (it != null && it.moveToFirst()) it.getString(
            it.getColumnIndexOrThrow(
                ContactsContract.PhoneLookup.DISPLAY_NAME
            )
        )
        else null
    }
    displayNames[address] = displayName
    return displayName
}

suspend fun wipeSmsAndMmsMessages(appContext: Context, updateProgress: suspend (Progress) -> Unit) {
    val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)

    withContext(Dispatchers.IO) {
        if (prefs.getBoolean("sms", true)) {
            updateProgress(Progress(0, 0, appContext.getString(R.string.wiping_sms_messages)))
            appContext.contentResolver.delete(
                Telephony.Sms.CONTENT_URI, messageSelection(appContext, SMS), null
            )
        }
        if (prefs.getBoolean("mms", true)) {
            updateProgress(Progress(0, 0, appContext.getString(R.string.wiping_mms_messages)))
            appContext.contentResolver.delete(
                Telephony.Mms.CONTENT_URI, messageSelection(appContext, MMS), null
            )
        }
    }
}

suspend fun countMessages(appContext: Context): MessageTotal {
    Log.d(LOG_TAG, "Counting messages")
    val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
    return withContext(Dispatchers.IO) {
        val totals = MessageTotal()
        if (prefs.getBoolean("sms", true)) {
            val smsCursor = appContext.contentResolver.query(
                Telephony.Sms.CONTENT_URI, null, messageSelection(appContext, SMS), null, null
            )
            smsCursor?.use {
                totals.sms = smsCursor.count
            }
        }
        if (prefs.getBoolean("mms", true)) {
            val mmsCursor = appContext.contentResolver.query(
                Telephony.Mms.CONTENT_URI, null, messageSelection(appContext, MMS), null, null
            )
            mmsCursor?.use {
                totals.mms = mmsCursor.count
            }
        }
        totals
    }
}

suspend fun automaticExport(
    appContext: Context, updateProgress: suspend (Progress) -> Unit
): Triple<MessageTotal, Int, Int> {

    val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
    val passphrase = if (prefs.getBoolean("encryption_scheduled_operations", false)) {
        val passphraseManager = PassphraseManager("passphrase_key", appContext)
        passphraseManager.retrievePassphrase()
            ?: throw UserFriendlyException(appContext.getString(R.string.encryption_passphrase_retrieval_failure_scheduled_operations_message))
    } else null

    var messages = MessageTotal()
    var calls = 0
    var contacts = 0
    //var blockedNumbers = 0

    val treeUri = prefs.getString(EXPORT_DIR, "")!!
        .toUri() // https://stackoverflow.com/questions/57813653/why-sharedpreferences-getstring-may-return-null
    // Cannot fail because our min SDK version is >= 21.
    val documentTree = DocumentFile.fromTreeUri(appContext, treeUri)!!
    val date = getCurrentDateTime()
    val dateInString = "-${date.toString("yyyy-MM-dd")}"

    // We want to back up as much as possible, so avoid failing fast.
    var firstException: Exception? = null

    if (prefs.getBoolean("export_messages", true)) {
        try {
            val file = createFile(
                documentTree, "application/zip", "messages$dateInString.zip", passphrase != null
            )
            messages = exportMessages(
                appContext, getOutputStream(appContext, file.uri, passphrase), updateProgress
            )
            deleteOldExports(prefs, documentTree, file, "messages")
        } catch (e: Exception) {
            firstException = e
        }
    }

    if (prefs.getBoolean("export_calls", true)) {
        try {
            val file = createFile(
                documentTree, "application/json", "calls$dateInString.json", passphrase != null
            )
            calls = exportCallLog(
                appContext, getOutputStream(appContext, file.uri, passphrase), updateProgress
            )
            deleteOldExports(prefs, documentTree, file, "calls")
        } catch (e: Exception) {
            firstException = firstException ?: e
        }
    }

    if (prefs.getBoolean("export_contacts", true)) {
        try {
            val file = createFile(
                documentTree, "application/json", "contacts$dateInString.json", passphrase != null
            )
            contacts = exportContacts(
                appContext, getOutputStream(appContext, file.uri, passphrase), updateProgress
            )
            deleteOldExports(prefs, documentTree, file, "contacts")
        } catch (e: Exception) {
            firstException = firstException ?: e
        }
    }

    /*It doesn't seem practical to include blocked numbers in scheduled exports, since using the blocked numbers API
    requires that we be the default SMS app or the default phone app, both of which require manual intervention
    to do and undo.
    https://developer.android.com/reference/android/provider/BlockedNumberContract#permissions*/

    /*if (prefs.getBoolean("export_blocked_numbers", true)) {
        try {
            val file = documentTree.createFile("application/zip", "blocked_numbers$dateInString.zip")
                ?: throw IOException("Failed to create blocked numbers output file")

            blockedNumbers = exportBlockedNumbers(appContext, file.uri, updateProgress)
            deleteOldExports(prefs, documentTree, file, "blocked_numbers")
        } catch (e: Exception) {
            firstException = firstException ?: e
        }
    }*/

    if (firstException != null) throw firstException

    return Triple(messages, calls, contacts)
}

fun deleteOldExports(
    prefs: SharedPreferences, documentTree: DocumentFile, newExport: DocumentFile?, prefix: String
) {
    if (prefs.getBoolean("delete_old_exports", false)) {
        Log.i(LOG_TAG, "Deleting old exports ...")
        // The following line is necessary in case there already existed a file with the
        // provided filename, in which case Android will add a numeric suffix to the new
        // file's filename ("messages-yyyy-MM-dd (1).json")
        val newFilename = newExport?.name.toString()
        val files = documentTree.listFiles()
        var total = 0
        val extension = if (prefix == "messages") "zip" else "json"
        val encryptedFileExtension = "$extension.${ENCRYPTED_FILE_EXTENSION}"
        files.forEach {
            val name = it.name
            if (name != null && name != newFilename && name.startsWith(prefix) && (name.endsWith(".$extension") || (name.endsWith(
                    ".${encryptedFileExtension}"
                )))
            ) {
                it.delete()
                total++
            }
        }
        if (prefs.getBoolean("remove_datestamps_from_filenames", false)) {
            newExport?.renameTo("$prefix.$extension")
        }
        Log.i(LOG_TAG, "$total exports deleted")
    }
}

fun comparePhoneNumbers(number1: String, number2: String): Boolean {
    val defaultCountryIso: String by lazy { Locale.getDefault().country }
    return if (SDK_INT >= 31) PhoneNumberUtils.areSamePhoneNumber(
        number1, number2, defaultCountryIso
    )
    else PhoneNumberUtils.compare(number1, number2)
}

fun createFile(
    documentTree: DocumentFile, baseMimetype: String, baseFilename: String, encryption: Boolean
): DocumentFile {
    val (mimetype, filename) = if (encryption) Pair(
        "application/octet-stream", "$baseFilename.${ENCRYPTED_FILE_EXTENSION}"
    ) else Pair(baseMimetype, baseFilename)
    return documentTree.createFile(mimetype, filename)
        ?: throw IOException("Failed to create messages output file")
}

val MAGIC_NUMBER = "SSEF".toByteArray() + 0x00 + 0xFF.toByte()
val FORMAT_VERSION = byteArrayOf(0x00, 0x01)

// Argon2id parameters
// These are the values of the "SECOND RECOMMENDED option" of RFC 9106, for situations where
// "much less memory is available."
// https://datatracker.ietf.org/doc/html/rfc9106#name-parameter-choice

const val T_COST_IN_ITERATIONS = 3
const val M_COST_IN_KIBIBYTES = 65536

// libsodium doesn't expose the Argon2 parallelism parameter:
// https://github.com/jedisct1/libsodium/issues/488
// https://github.com/jedisct1/libsodium/issues/986
// https://github.com/jedisct1/libsodium/issues/993
// https://github.com/jedisct1/libsodium/discussions/1092
//const val PARALLELISM = 4
const val SALT_LENGTH = 16

const val INTEGER_LENGTH = Int.SIZE_BYTES
fun getOutputStream(appContext: Context, uri: Uri, passphrase: String?): OutputStream? {
    val outputStream = appContext.contentResolver.openOutputStream(uri) ?: return null
    return if (passphrase == null) outputStream else {
        val salt = ByteArray(SALT_LENGTH)
        val secureRandom = SecureRandom()
        secureRandom.nextBytes(salt)
        val key = passphraseToKey(passphrase, T_COST_IN_ITERATIONS, M_COST_IN_KIBIBYTES, salt)
        outputStream.write(
            MAGIC_NUMBER + FORMAT_VERSION + ByteBuffer.allocate(INTEGER_LENGTH)
                .putInt(T_COST_IN_ITERATIONS).array() + ByteBuffer.allocate(INTEGER_LENGTH)
                .putInt(M_COST_IN_KIBIBYTES).array() + salt
        )
        val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
        val chunkSize = prefs.getString("secretstream_chunk_size", "")?.toIntOrNull()
        if (chunkSize != null) SecretStreamOutputStream(outputStream, key, chunkSize)
        else SecretStreamOutputStream(outputStream, key)
    }
}

fun getInputStream(appContext: Context, uri: Uri, passphrase: String?): InputStream? {
    val inputStream = appContext.contentResolver.openInputStream(uri) ?: return null
    return if (passphrase == null) inputStream else {
        val magicNumber = ByteArray(MAGIC_NUMBER.size)
        inputStream.read(magicNumber)
        if (!magicNumber.contentEquals(MAGIC_NUMBER)) throw UserFriendlyException(
            appContext.getString(
                R.string.encryption_invalid_format
            )
        )
        inputStream.skip(FORMAT_VERSION.size.toLong()) // we don't currently do anything with the FORMAT_VERSION
        val byteArray = ByteArray(INTEGER_LENGTH)
        inputStream.read(byteArray)
        val tCostInIterations = ByteBuffer.wrap(byteArray).getInt()
        inputStream.read(byteArray)
        val mCostInKibibytes = ByteBuffer.wrap(byteArray).getInt()
        val salt = ByteArray(SALT_LENGTH)
        inputStream.read(salt)
        val decryptionKey = passphraseToKey(passphrase, tCostInIterations, mCostInKibibytes, salt)
        SecretStreamInputStream(inputStream, decryptionKey)
    }
}
