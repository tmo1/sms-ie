/*
 * SMS Import / Export: a simple Android app for importing and exporting SMS messages from and to JSON files.
 * Copyright (c) 2021-2022 Thomas More
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

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Base64
import android.util.JsonReader
import android.util.JsonWriter
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter

fun checkReadSMSContactsPermissions(appContext: Context): Boolean {
    if (ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED
        && ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    ) return true
    /*else {
        Toast.makeText(
            appContext,
            appContext.getString(R.string.sms_permissions_required),
            Toast.LENGTH_LONG
        ).show()
    }*/
    return false
}

fun checkReadCallLogsContactsPermissions(appContext: Context): Boolean {
    if (ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
        && ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    ) return true
    /*else {
        Toast.makeText(
            appContext,
            appContext.getString(R.string.call_logs_permissions_required),
            Toast.LENGTH_LONG
        ).show()
    }*/
    return false
}

fun checkReadWriteCallLogPermissions(appContext: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        appContext,
        Manifest.permission.WRITE_CALL_LOG
    ) == PackageManager.PERMISSION_GRANTED
            && ContextCompat.checkSelfPermission(
        appContext,
        Manifest.permission.READ_CALL_LOG
    ) == PackageManager.PERMISSION_GRANTED
}

suspend fun exportMessages(
    appContext: Context,
    file: Uri,
    progressBar: ProgressBar?,
    statusReportText: TextView?
): MessageTotal {
    val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
    return withContext(Dispatchers.IO) {
        val totals = MessageTotal()
        val displayNames = mutableMapOf<String, String?>()
        appContext.contentResolver.openOutputStream(file).use { outputStream ->
            BufferedWriter(OutputStreamWriter(outputStream)).use { writer ->
                val jsonWriter = JsonWriter(writer)
                jsonWriter.setIndent("  ")
                jsonWriter.beginArray()
                if (prefs.getBoolean("sms", true)) {
                    totals.sms = smsToJSON(appContext, jsonWriter, displayNames, progressBar, statusReportText)
                }
                if (prefs.getBoolean("mms", true)) {
                    totals.mms = mmsToJSON(appContext, jsonWriter, displayNames, progressBar, statusReportText)
                }
                jsonWriter.endArray()
            }
        }
        totals
    }
}

suspend fun exportCallLog(
    appContext: Context,
    file: Uri,
    progressBar: ProgressBar?,
    statusReportText: TextView?
): MessageTotal {
    //val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
    return withContext(Dispatchers.IO) {
        val totals = MessageTotal()
        val displayNames = mutableMapOf<String, String?>()
        appContext.contentResolver.openOutputStream(file).use { outputStream ->
            BufferedWriter(OutputStreamWriter(outputStream)).use { writer ->
                val jsonWriter = JsonWriter(writer)
                jsonWriter.setIndent("  ")
                jsonWriter.beginArray()
                totals.sms = callLogToJSON(
                    appContext,
                    jsonWriter,
                    displayNames,
                    progressBar,
                    statusReportText
                )
                jsonWriter.endArray()
            }
        }
        totals
    }
}

private suspend fun callLogToJSON(
    appContext: Context,
    jsonWriter: JsonWriter,
    displayNames: MutableMap<String, String?>,
    progressBar: ProgressBar?,
    statusReportText: TextView?
): Int {
    val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
    var total = 0
    val callCursor =
        appContext.contentResolver.query(
            Uri.parse("content://call_log/calls"),
            null,
            null,
            null,
            null
        )
    callCursor?.use { it ->
        if (it.moveToFirst()) {
            val totalCalls = it.count
            initProgressBar(progressBar, it)
            val addressIndex = it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            do {
                jsonWriter.beginObject()
                it.columnNames.forEachIndexed { i, columnName ->
                    val value = it.getString(i)
                    if (value != null) jsonWriter.name(columnName).value(value)
                }
                // The call logs do have a CACHED_NAME ("name") field, but it may still be useful to add the current display name, if available
                // From the documentation at https://developer.android.com/reference/android/provider/CallLog.Calls#CACHED_NAME
                // "The cached name associated with the phone number, if it exists.
                // This value is typically filled in by the dialer app for the caching purpose, so it's not guaranteed to be present, and may not be current if the contact information associated with this number has changed."
                val displayName =
                    lookupDisplayName(appContext, displayNames, it.getString(addressIndex))
                if (displayName != null) jsonWriter.name("display_name").value(displayName)
                jsonWriter.endObject()
                total++
                incrementProgress(progressBar)
                setStatusText(statusReportText, appContext.getString(R.string.call_log_export_progress, total, totalCalls))
                if (BuildConfig.DEBUG && total == prefs.getString("max_messages", "")
                        ?.toIntOrNull() ?: -1
                ) break
            } while (it.moveToNext())
            hideProgressBar(progressBar)
        }
    }
    return total
}

private suspend fun smsToJSON(
    appContext: Context,
    jsonWriter: JsonWriter,
    displayNames: MutableMap<String, String?>,
    progressBar: ProgressBar?,
    statusReportText: TextView?
): Int {
    val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
    var total = 0
    val smsCursor =
        appContext.contentResolver.query(Telephony.Sms.CONTENT_URI, null, null, null, null)
    smsCursor?.use { it ->
        if (it.moveToFirst()) {
            initProgressBar(progressBar, it)
            val totalSms = it.count
            val addressIndex = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            do {
                jsonWriter.beginObject()
                it.columnNames.forEachIndexed { i, columnName ->
                    val value = it.getString(i)
                    if (value != null) jsonWriter.name(columnName).value(value)
                }
                val displayName =
                    lookupDisplayName(appContext, displayNames, it.getString(addressIndex))
                if (displayName != null) jsonWriter.name("display_name").value(displayName)
                jsonWriter.endObject()
                total++
                incrementProgress(progressBar)
                setStatusText(statusReportText, appContext.getString(R.string.sms_export_progress, total, totalSms))
                if (BuildConfig.DEBUG && total == prefs.getString("max_messages", "")
                        ?.toIntOrNull() ?: -1
                ) break
            } while (it.moveToNext())
            hideProgressBar(progressBar)
        }
    }
    return total
}

private suspend fun mmsToJSON(
    appContext: Context,
    jsonWriter: JsonWriter,
    displayNames: MutableMap<String, String?>,
    progressBar: ProgressBar?,
    statusReportText: TextView?
): Int {
    val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
    var total = 0
    val mmsCursor =
        appContext.contentResolver.query(Telephony.Mms.CONTENT_URI, null, null, null, null)
    mmsCursor?.use { it ->
        if (it.moveToFirst()) {
            val totalMms = it.count
            initProgressBar(progressBar, it)
            val msgIdIndex = it.getColumnIndexOrThrow("_id")
            // write MMS metadata
            do {
                jsonWriter.beginObject()
                it.columnNames.forEachIndexed { i, columnName ->
                    val value = it.getString(i)
                    if (value != null) jsonWriter.name(columnName).value(value)
                }
//                        the following is adapted from https://stackoverflow.com/questions/3012287/how-to-read-mms-data-in-android/6446831#6446831
                val msgId = it.getString(msgIdIndex)
                val addressCursor = appContext.contentResolver.query(
//                                Uri.parse("content://mms/addr"),
                    Uri.parse("content://mms/$msgId/addr"),
                    null,
                    null,
                    null,
                    null
                )
                addressCursor?.use { it1 ->
                    val addressTypeIndex =
                        addressCursor.getColumnIndexOrThrow(Telephony.Mms.Addr.TYPE)
                    val addressIndex =
                        addressCursor.getColumnIndexOrThrow(Telephony.Mms.Addr.ADDRESS)
                    // write sender address object
                    if (it1.moveToFirst()) {
                        do {
                            if (addressTypeIndex.let { it2 -> it1.getString(it2) } == PDU_HEADERS_FROM) {
                                jsonWriter.name("sender_address")
                                jsonWriter.beginObject()
                                it1.columnNames.forEachIndexed { i, columnName ->
                                    val value = it1.getString(i)
                                    if (value != null) jsonWriter.name(columnName).value(value)
                                }
                                val displayName =
                                    lookupDisplayName(
                                        appContext,
                                        displayNames,
                                        it1.getString(addressIndex)
                                    )
                                if (displayName != null) jsonWriter.name("display_name")
                                    .value(displayName)
                                jsonWriter.endObject()
                                break
                            }
                        } while (it1.moveToNext())
                    }
                    // write array of recipient address objects
                    if (it1.moveToFirst()) {
                        jsonWriter.name("recipient_addresses")
                        jsonWriter.beginArray()
                        do {
                            if (addressTypeIndex.let { it2 -> it1.getString(it2) } != PDU_HEADERS_FROM) {
                                jsonWriter.beginObject()
                                it1.columnNames.forEachIndexed { i, columnName ->
                                    val value = it1.getString(i)
                                    if (value != null) jsonWriter.name(columnName).value(value)
                                }
                                val displayName =
                                    lookupDisplayName(
                                        appContext,
                                        displayNames,
                                        it1.getString(addressIndex)
                                    )
                                if (displayName != null) jsonWriter.name("display_name")
                                    .value(displayName)
                                jsonWriter.endObject()
                            }
                        } while (it1.moveToNext())
                        jsonWriter.endArray()
                    }
                }
                val partCursor = appContext.contentResolver.query(
                    Uri.parse("content://mms/part"),
//                      Uri.parse("content://mms/$msgId/part"),
                    null,
                    "mid=?",
                    arrayOf(msgId),
                    "seq ASC"
                )
                // write array of MMS parts
                partCursor?.use { it1 ->
                    if (it1.moveToFirst()) {
                        jsonWriter.name("parts")
                        jsonWriter.beginArray()
                        val partIdIndex = it1.getColumnIndexOrThrow("_id")
                        val dataIndex = it1.getColumnIndexOrThrow("_data")
                        do {
                            jsonWriter.beginObject()
                            it1.columnNames.forEachIndexed { i, columnName ->
                                val value = it1.getString(i)
                                if (value != null) jsonWriter.name(columnName).value(value)
                            }
                            //if (includeBinaryData && it1.getString(dataIndex) != null) {
                            if (prefs.getBoolean("include_binary_data", true) && it1.getString(
                                    dataIndex
                                ) != null
                            ) {
                                val inputStream = appContext.contentResolver.openInputStream(
                                    Uri.parse(
                                        "content://mms/part/" + it1.getString(
                                            partIdIndex
                                        )
                                    )
                                )
                                val data = inputStream.use {
                                    Base64.encodeToString(
                                        it?.readBytes(),
                                        Base64.NO_WRAP // Without NO_WRAP, we end up with corrupted files upon decoding - see https://stackoverflow.com/questions/16091883/sending-base64-encoded-image-results-in-a-corrupt-image
                                    )
                                }
                                jsonWriter.name("binary_data").value(data)
                            }
                            jsonWriter.endObject()
                        } while (it1.moveToNext())
                        jsonWriter.endArray()
                    }
                }
                jsonWriter.endObject()
                total++
                incrementProgress(progressBar)
                setStatusText(statusReportText, appContext.getString(R.string.mms_export_progress, total, totalMms))
                if (BuildConfig.DEBUG && total == prefs.getString("max_messages", "")
                        ?.toIntOrNull() ?: -1
                ) break
            } while (it.moveToNext())
            hideProgressBar(progressBar)
        }
    }
    return total
}

private fun lookupDisplayName(
    appContext: Context,
    displayNames: MutableMap<String, String?>,
    address: String
): String? {
//        look up display name by phone number
    if (address == "") return null
    if (displayNames[address] != null) return displayNames[address]
    val displayName: String?
    val uri = Uri.withAppendedPath(
        ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
        Uri.encode(address)
    )
    val nameCursor = appContext.contentResolver.query(
        uri,
        arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
        null,
        null,
        null
    )
    nameCursor.use {
        displayName = if (it != null && it.moveToFirst())
            it.getString(
                it.getColumnIndexOrThrow(
                    ContactsContract.PhoneLookup.DISPLAY_NAME
                )
            )
        else null
    }
    displayNames[address] = displayName
    return displayName
}

suspend fun importMessages(
    appContext: Context,
    uri: Uri,
    progressBar: ProgressBar?,
    statusReportText: TextView?
): MessageTotal {
    val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
    return withContext(Dispatchers.IO) {
        val totals = MessageTotal()
        // get column names of local SMS and MMS tables
        val smsColumns = mutableSetOf<String>()
        val smsCursor =
            appContext.contentResolver.query(Telephony.Sms.CONTENT_URI, null, null, null, null)
        smsCursor?.use { smsColumns.addAll(it.columnNames) }
        val mmsColumns = mutableSetOf<String>()
        val mmsCursor =
            appContext.contentResolver.query(Telephony.Mms.CONTENT_URI, null, null, null, null)
        mmsCursor?.use { mmsColumns.addAll(it.columnNames) }
        uri.let {
            initIndeterminateProgressBar(progressBar)
            appContext.contentResolver.openInputStream(it).use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    val jsonReader = JsonReader(reader)
                    val messageMetadata = ContentValues()
                    val addresses = mutableSetOf<ContentValues>()
                    val parts = mutableListOf<ContentValues>()
                    val binaryData = mutableListOf<ByteArray?>()
                    jsonReader.beginArray()
                    while (jsonReader.hasNext()) {
                        jsonReader.beginObject()
                        messageMetadata.clear()
                        addresses.clear()
                        parts.clear()
                        binaryData.clear()
                        var name: String?
                        var value: String?
                        while (jsonReader.hasNext()) {
                            name = jsonReader.nextName()
                            when (name) {
                                "sender_address" -> {
                                    jsonReader.beginObject()
                                    val address = ContentValues()
                                    while (jsonReader.hasNext()) {
                                        val name1 = jsonReader.nextName()
                                        val value1 = jsonReader.nextString()
                                        if (name1 !in setOf(
                                                Telephony.Mms.Addr._ID,
                                                Telephony.Mms.Addr._COUNT,
                                                Telephony.Mms.Addr.MSG_ID,
                                                "display_name"
                                            )
                                        ) address.put(name1, value1)
                                    }
                                    addresses.add(address)
                                    jsonReader.endObject()
                                }
                                "recipient_addresses" -> {
                                    jsonReader.beginArray()
                                    while (jsonReader.hasNext()) {
                                        jsonReader.beginObject()
                                        val address = ContentValues()
                                        while (jsonReader.hasNext()) {
                                            val name1 = jsonReader.nextName()
                                            val value1 = jsonReader.nextString()
                                            if (name1 !in setOf(
                                                    Telephony.Mms.Addr._ID,
                                                    Telephony.Mms.Addr._COUNT,
                                                    Telephony.Mms.Addr.MSG_ID,
                                                    "display_name"
                                                )
                                            ) address.put(name1, value1)
                                        }
                                        addresses.add(address)
                                        jsonReader.endObject()
                                    }
                                    jsonReader.endArray()
                                }
                                "parts" -> {
                                    jsonReader.beginArray()
                                    while (jsonReader.hasNext()) {
                                        jsonReader.beginObject()
                                        val part = ContentValues()
                                        var hasBinaryData = false
                                        while (jsonReader.hasNext()) {
                                            val name1 = jsonReader.nextName()
                                            val value1 = jsonReader.nextString()
                                            if (name1 !in setOf(
                                                    Telephony.Mms.Part.MSG_ID,
                                                    Telephony.Mms.Part._ID,
                                                    Telephony.Mms.Part._DATA,
                                                    Telephony.Mms.Part._COUNT,
                                                    "binary_data"
                                                )
                                            ) part.put(name1, value1)
                                            if (name1 == "binary_data") {
                                                binaryData.add(
                                                    Base64.decode(
                                                        value1,
                                                        Base64.NO_WRAP
                                                    )
                                                )
                                                hasBinaryData = true
                                            }
                                        }
                                        if (!hasBinaryData) binaryData.add(null)
                                        parts.add(part)
                                        jsonReader.endObject()
                                    }
                                    jsonReader.endArray()
                                }
                                else -> {
                                    value = jsonReader.nextString()
                                    if (name !in setOf(
                                            "_id",
                                            "thread_id",
                                            "display_name"
                                        )
                                    ) messageMetadata.put(name, value)
                                }
                            }
                        }
                        jsonReader.endObject()
                        if (!messageMetadata.containsKey("m_type")) { // it's SMS
                            if (!prefs.getBoolean("sms", true) || totals.sms == prefs.getString(
                                    "max_messages",
                                    ""
                                )?.toIntOrNull() ?: -1
                            ) continue
                            val fieldNames = mutableSetOf<String>()
                            fieldNames.addAll(messageMetadata.keySet())
                            fieldNames.forEach { key ->
                                if (!smsColumns.contains(key)) {
                                    messageMetadata.remove(key)
                                }
                            }
                            val insertUri =
                                appContext.contentResolver.insert(
                                    Telephony.Sms.CONTENT_URI,
                                    messageMetadata
                                )
                            if (insertUri == null) {
                                Log.v(LOG_TAG, "SMS insert failed!")
                            } else {
                                totals.sms++
                                setStatusText(statusReportText, appContext.getString(R.string.message_import_progress, totals.sms, totals.mms))
                            }
                        } else { // it's MMS
                            if (!prefs.getBoolean("mms", true) || totals.mms == prefs.getString(
                                    "max_messages",
                                    ""
                                )?.toIntOrNull() ?: -1
                            ) continue
                            val fieldNames = mutableSetOf<String>()
                            fieldNames.addAll(messageMetadata.keySet())
                            fieldNames.forEach { key ->
                                if (!mmsColumns.contains(key)) {
                                    messageMetadata.remove(key)
                                }
                            }
                            val threadId = Telephony.Threads.getOrCreateThreadId(
                                appContext,
                                addresses.map { it1 -> it1.getAsString("address") }.toSet()
                            )
                            messageMetadata.put("thread_id", threadId)
                            val insertUri =
                                appContext.contentResolver.insert(
                                    Telephony.Mms.CONTENT_URI,
                                    messageMetadata
                                )
                            if (insertUri == null) {
                                Log.v(LOG_TAG, "MMS insert failed!")
                            } else {
                                totals.mms++
                                setStatusText(statusReportText, appContext.getString(R.string.message_import_progress, totals.sms, totals.mms))
//                                Log.v(LOG_TAG, "MMS insert succeeded!")
                                val messageId = insertUri.lastPathSegment
                                val addressUri = Uri.parse("content://mms/$messageId/addr")
                                addresses.forEach { address1 ->
                                    address1.put(Telephony.Mms.Addr.MSG_ID, messageId)
                                    val insertAddressUri =
                                        appContext.contentResolver.insert(addressUri, address1)
                                    if (insertAddressUri == null) {
                                        Log.v(LOG_TAG, "MMS address insert failed!")
                                    } /*else {
                                        Log.v(LOG_TAG, "MMS address insert succeeded. Address metadata:" + address.toString())
                                    }*/
                                }
                                val partUri = Uri.parse("content://mms/$messageId/part")
                                parts.forEachIndexed { j, part1 ->
                                    part1.put(Telephony.Mms.Part.MSG_ID, messageId)
                                    val insertPartUri =
                                        appContext.contentResolver.insert(partUri, part1)
                                    if (insertPartUri == null) {
                                        Log.v(
                                            LOG_TAG,
                                            "MMS part insert failed! Part metadata:$part1"
                                        )
                                    } else {
                                        if (binaryData[j] != null) {
                                            val os =
                                                appContext.contentResolver.openOutputStream(
                                                    insertPartUri
                                                )
                                            if (os != null) os.use { os.write(binaryData[j]) }
                                            else Log.v(LOG_TAG, "Failed to open OutputStream!")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    jsonReader.endArray()
                }
            }
            hideProgressBar(progressBar)
            totals
        }
    }
}

suspend fun importCallLog(appContext: Context, uri: Uri): Int {
    return withContext(Dispatchers.IO) {
        val callLogColumns = mutableSetOf<String>()
        val callLogCursor = appContext.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            null, null, null, null
        )
        callLogCursor?.use { callLogColumns.addAll(it.columnNames) }
        var callLogCount = 0
        uri.let {
            appContext.contentResolver.openInputStream(it).use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    val jsonReader = JsonReader(reader)
                    val callLogMetadata = ContentValues()
                    jsonReader.beginArray()
                    while (jsonReader.hasNext()) {
                        jsonReader.beginObject()
                        callLogMetadata.clear()
                        while (jsonReader.hasNext()) {
                            val name = jsonReader.nextName()
                            val value = jsonReader.nextString()
                            if (callLogColumns.contains(name)) {
                                callLogMetadata.put(name, value)
                            }
                        }
                        var insertUri: Uri? = null
                        if (callLogMetadata.keySet().contains(CallLog.Calls.NUMBER)) {
                            insertUri = appContext.contentResolver.insert(
                                CallLog.Calls.CONTENT_URI,
                                callLogMetadata
                            )
                        }
                        if (insertUri == null) {
                            Log.v(LOG_TAG, "Call log insert failed!")
                        } else callLogCount++
                        jsonReader.endObject()
                    }
                    jsonReader.endArray()
                }
            }
            callLogCount
        }
    }
}

suspend fun wipeSmsAndMmsMessages(appContext: Context, statusReportText: TextView, progressBar: ProgressBar) {
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

private suspend fun initProgressBar(progressBar: ProgressBar?, cursor: Cursor) {
    withContext(Dispatchers.Main) {
        progressBar?.isIndeterminate = false
        progressBar?.progress = 0
        progressBar?.visibility = View.VISIBLE
        progressBar?.setMax(cursor.count)
    }
}

private suspend fun initIndeterminateProgressBar(progressBar: ProgressBar?) {
    withContext(Dispatchers.Main) {
        progressBar?.isIndeterminate = true
        progressBar?.visibility = View.VISIBLE
    }
}

private suspend fun hideProgressBar(progressBar: ProgressBar?) {
    withContext(Dispatchers.Main) {
        progressBar?.visibility = View.INVISIBLE
    }
}

private suspend fun setStatusText(statusReportText: TextView?, message: String) {
    withContext(Dispatchers.Main) { statusReportText?.text = message }
}

private suspend fun incrementProgress(progressBar: ProgressBar?) {
    withContext(Dispatchers.Main) {
        progressBar?.incrementProgressBy(1)
    }
}
