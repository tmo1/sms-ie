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
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

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

suspend fun wipeSmsAndMmsMessages(
    appContext: Context, statusReportText: TextView, progressBar: ProgressBar
) {
    val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
    withContext(Dispatchers.IO) {
        if (prefs.getBoolean("sms", true)) {
            setStatusText(statusReportText, appContext.getString(R.string.wiping_sms_messages))
            initIndeterminateProgressBar(progressBar)
            appContext.contentResolver.delete(Telephony.Sms.CONTENT_URI, null, null)
            hideProgressBar(progressBar)
        }
        if (prefs.getBoolean("mms", true)) {
            setStatusText(statusReportText, appContext.getString(R.string.wiping_mms_messages))
            initIndeterminateProgressBar(progressBar)
            appContext.contentResolver.delete(Telephony.Mms.CONTENT_URI, null, null)
            hideProgressBar(progressBar)
        }
    }
}

suspend fun automaticExport(appContext: Context): Triple<MessageTotal, Int, Int> {
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

            Log.i(LOG_TAG, "Beginning messages export ...")
            messages = exportMessages(appContext, file.uri, null, null)
            Log.i(
                LOG_TAG,
                "Messages export successful: ${messages.sms} SMSs and ${messages.mms} MMSs exported"
            )
            deleteOldExports(prefs, documentTree, file, "messages")
        } catch (e: Exception) {
            firstException = firstException ?: e
        }
    }

    if (prefs.getBoolean("export_calls", true)) {
        try {
            val file = documentTree.createFile("application/json", "calls$dateInString.json")
                ?: throw IOException("Failed to create call log output file")

            Log.i(LOG_TAG, "Beginning call log export ...")
            calls = exportCallLog(appContext, file.uri, null, null).sms
            Log.i(
                LOG_TAG, "Call log export successful: $calls calls exported"
            )
            deleteOldExports(prefs, documentTree, file, "calls")
        } catch (e: Exception) {
            firstException = firstException ?: e
        }
    }

    if (prefs.getBoolean("export_contacts", true)) {
        try {
            val file = documentTree.createFile("application/json", "contacts$dateInString.json")
                ?: throw IOException("Failed to create contacts output file")

            Log.i(LOG_TAG, "Beginning contacts export ...")
            contacts = exportContacts(appContext, file.uri, null, null)
            Log.i(
                LOG_TAG, "Contacts export successful: $contacts contacts exported"
            )
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

suspend fun initProgressBar(progressBar: ProgressBar?, cursor: Cursor) {
    withContext(Dispatchers.Main) {
        progressBar?.isIndeterminate = false
        progressBar?.progress = 0
        progressBar?.visibility = View.VISIBLE
        progressBar?.max = cursor.count
    }
}

suspend fun initIndeterminateProgressBar(progressBar: ProgressBar?) {
    withContext(Dispatchers.Main) {
        progressBar?.isIndeterminate = true
        progressBar?.visibility = View.VISIBLE
    }
}

suspend fun hideProgressBar(progressBar: ProgressBar?) {
    withContext(Dispatchers.Main) {
        progressBar?.visibility = View.INVISIBLE
    }
}

suspend fun setStatusText(statusReportText: TextView?, message: String) {
    withContext(Dispatchers.Main) { statusReportText?.text = message }
}

suspend fun incrementProgress(progressBar: ProgressBar?) {
    withContext(Dispatchers.Main) {
        progressBar?.incrementProgressBy(1)
    }
}

// From: https://stackoverflow.com/a/18143773
suspend fun displayError(appContext: Context, e: Exception?, title: String, message: String) {
    val messageExpanded = if (e != null) {
        e.printStackTrace()
        "$message:\n\n\"$e\"\n\nSee logcat for more information."
    } else {
        message
    }
    val errorBox = AlertDialog.Builder(appContext)
    errorBox.setTitle(title).setMessage(messageExpanded)
    //errorBox.setTitle(title).setMessage("$message:\n\n\"$e\"\n\nSee logcat for more information.")
        .setCancelable(false).setNeutralButton("Okay", null)
    withContext(Dispatchers.Main) {
        errorBox.show()
    }
}
