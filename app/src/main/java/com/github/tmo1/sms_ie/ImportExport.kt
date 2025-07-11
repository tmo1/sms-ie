/*
 * SMS Import / Export: a simple Android app for importing and exporting SMS and MMS messages,
 * call logs, and contacts, from and to JSON / NDJSON files.
 *
 * Copyright (c) 2021-2022,2024-2025 Thomas More
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
 * (which are in their own eponymous files), as well as the message database wiping routine.
 */

package com.github.tmo1.sms_ie

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
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

class UserFriendlyException(message: String? = null, cause: Throwable? = null)
    : Exception(message, cause)

fun checkReadSMSContactsPermissions(appContext: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        appContext, Manifest.permission.READ_SMS
    ) == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(
        appContext, Manifest.permission.READ_CONTACTS
    ) == PackageManager.PERMISSION_GRANTED
    /*else {
        Toast.makeText(
            appContext,
            appContext.getString(R.string.sms_permissions_required),
            Toast.LENGTH_LONG
        ).show()
    }*/
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
            appContext.contentResolver.delete(Telephony.Sms.CONTENT_URI, null, null)
        }
        if (prefs.getBoolean("mms", true)) {
            updateProgress(Progress(0, 0, appContext.getString(R.string.wiping_mms_messages)))
            appContext.contentResolver.delete(Telephony.Mms.CONTENT_URI, null, null)
        }
    }
}

suspend fun automaticExport(
    appContext: Context, updateProgress: suspend (Progress) -> Unit
): Triple<MessageTotal, Int, Int> {
    val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)

    var messages = MessageTotal()
    var calls = 0
    var contacts = 0

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
            val file = documentTree.createFile("application/zip", "messages$dateInString.zip")
                ?: throw IOException("Failed to create messages output file")

            messages = exportMessages(appContext, file.uri, updateProgress)
            deleteOldExports(prefs, documentTree, file, "messages")
        } catch (e: Exception) {
            firstException = firstException ?: e
        }
    }

    if (prefs.getBoolean("export_calls", true)) {
        try {
            val file = documentTree.createFile("application/json", "calls$dateInString.json")
                ?: throw IOException("Failed to create call log output file")

            calls = exportCallLog(appContext, file.uri, updateProgress)
            deleteOldExports(prefs, documentTree, file, "calls")
        } catch (e: Exception) {
            firstException = firstException ?: e
        }
    }

    if (prefs.getBoolean("export_contacts", true)) {
        try {
            val file = documentTree.createFile("application/json", "contacts$dateInString.json")
                ?: throw IOException("Failed to create contacts output file")

            contacts = exportContacts(appContext, file.uri, updateProgress)
            deleteOldExports(prefs, documentTree, file, "contacts")
        } catch (e: Exception) {
            firstException = firstException ?: e
        }
    }

    if (firstException != null) {
        throw firstException
    }

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
        files.forEach {
            val name = it.name
            if (name != null && name != newFilename && name.startsWith(prefix)
                    && name.endsWith(".$extension")) {
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
