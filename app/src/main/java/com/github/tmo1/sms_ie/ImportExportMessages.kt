/*
 * SMS Import / Export: a simple Android app for importing and exporting SMS and MMS messages,
 * call logs, and contacts, from and to JSON / NDJSON files.
 *
 * Copyright (c) 2021-2025 Thomas More
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
 * along with SMS Import / Export.  If not, see <https://www.gnu.org/licenses/>
 *
 */

// This file contains the routines that import and export SMS and MMS messages.

package com.github.tmo1.sms_ie

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import android.provider.Telephony
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

// PduHeaders are referenced here https://developer.android.com/reference/android/provider/Telephony.Mms.Addr#TYPE
// and defined here https://android.googlesource.com/platform/frameworks/opt/mms/+/4bfcd8501f09763c10255442c2b48fad0c796baa/src/java/com/google/android/mms/pdu/PduHeaders.java
// but are apparently unavailable in a public class
const val PDU_HEADERS_FROM = "137"

data class MessageTotal(var sms: Int = 0, var mms: Int = 0)

data class MmsBinaryPart(val uri: Uri, val filename: String)

suspend fun exportMessages(
    appContext: Context, file: Uri, updateProgress: suspend (Progress) -> Unit
): MessageTotal {
    val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
    return withContext(Dispatchers.IO) {
        val totals = MessageTotal()
        val displayNames = mutableMapOf<String, String?>()
        appContext.contentResolver.openOutputStream(file).use { outputStream ->
            ZipOutputStream(outputStream).use { zipOutputStream ->
                val jsonZipEntry = ZipEntry("messages.ndjson")
                zipOutputStream.putNextEntry(jsonZipEntry)
                if (prefs.getBoolean("sms", true)) {
                    totals.sms = smsToJSON(
                        appContext, zipOutputStream, displayNames, updateProgress
                    )
                }
                val mmsPartList = mutableListOf<MmsBinaryPart>()
                if (prefs.getBoolean("mms", true)) {
                    totals.mms = mmsToJSON(
                        appContext,
                        zipOutputStream,
                        displayNames,
                        mmsPartList,
                        updateProgress,
                    )
                }
                zipOutputStream.closeEntry()
                if (prefs.getBoolean("mms", true)) {
                    updateProgress(
                        Progress(
                            0, 0, appContext.getString(R.string.copying_mms_binary_data)
                        )
                    )

                    val buffer = ByteArray(1048576)
                    mmsPartList.forEach {
                        ensureActive()

                        val partZipEntry = ZipEntry(it.filename)
                        zipOutputStream.putNextEntry(partZipEntry)
                        try {
                            appContext.contentResolver.openInputStream(it.uri)?.use { inputStream ->
                                var n = inputStream.read(buffer)
                                while (n > -1) {
                                    zipOutputStream.write(buffer, 0, n)
                                    n = inputStream.read(buffer)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(
                                LOG_TAG,
                                "Error accessing binary data for MMS message part " + it.filename,
                                e
                            )
                        }
                        zipOutputStream.closeEntry()
                    }
                }
            }
        }
        totals
    }
}

private suspend fun smsToJSON(
    appContext: Context,
    zipOutputStream: ZipOutputStream,
    displayNames: MutableMap<String, String?>,
    updateProgress: suspend (Progress) -> Unit,
): Int {
    val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
    var progress = Progress(0, 0, null)
    val smsCursor = appContext.contentResolver.query(
        Telephony.Sms.CONTENT_URI, null, messageSelection(appContext, SMS), null, null
    )
    smsCursor?.use {
        if (it.moveToFirst()) {
            progress = progress.copy(total = it.count)
            updateProgress(progress)
            val addressIndex = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            do {
                coroutineContext.ensureActive()

                val smsMessage = JSONObject()
                it.columnNames.forEachIndexed { i, columnName ->
                    val value = it.getString(i)
                    if (value != null) smsMessage.put(columnName, value)
                }
                val displayName =
                    lookupDisplayName(appContext, displayNames, it.getString(addressIndex))
                if (displayName != null) smsMessage.put("__display_name", displayName)
                zipOutputStream.write((smsMessage.toString() + "\n").toByteArray())

                progress = progress.copy(
                    current = progress.current + 1,
                    message = appContext.getString(
                        R.string.sms_export_progress,
                        progress.current + 1,
                        progress.total,
                    ),
                )
                updateProgress(progress)

                if (progress.current == (prefs.getString("max_records", "")?.toIntOrNull()
                        ?: -1)
                ) break
            } while (it.moveToNext())
        }
    }
    return progress.current
}

private suspend fun mmsToJSON(
    appContext: Context,
    zipOutputStream: ZipOutputStream,
    displayNames: MutableMap<String, String?>,
    mmsPartList: MutableList<MmsBinaryPart>,
    updateProgress: suspend (Progress) -> Unit,
): Int {
    val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
    var progress = Progress(0, 0, null)
    val mmsCursor = appContext.contentResolver.query(
        Telephony.Mms.CONTENT_URI, null, messageSelection(appContext, MMS), null, null
    )
    mmsCursor?.use {
        if (it.moveToFirst()) {
            progress = progress.copy(total = it.count)
            updateProgress(progress)
            val msgIdIndex = it.getColumnIndexOrThrow("_id")
            // write MMS metadata
            do {
                coroutineContext.ensureActive()
                val mmsMessage = JSONObject()
                it.columnNames.forEachIndexed { i, columnName ->
                    val value = it.getString(i)
                    if (value != null) mmsMessage.put(columnName, value)
                }
                // the following is adapted from https://stackoverflow.com/questions/3012287/how-to-read-mms-data-in-android/6446831#6446831
                val msgId = it.getString(msgIdIndex)
                val addressCursor = appContext.contentResolver.query(
                    "content://mms/$msgId/addr".toUri(), null, null, null, null
                )
                addressCursor?.use { address ->
                    val addressTypeIndex =
                        addressCursor.getColumnIndexOrThrow(Telephony.Mms.Addr.TYPE)
                    val addressIndex =
                        addressCursor.getColumnIndexOrThrow(Telephony.Mms.Addr.ADDRESS)
                    // write sender address object
                    if (address.moveToFirst()) {
                        do {
                            if (addressTypeIndex.let { x -> address.getString(x) } == PDU_HEADERS_FROM) {
                                val mmsSenderAddress = JSONObject()
                                address.columnNames.forEachIndexed { i, columnName ->
                                    val value = address.getString(i)
                                    if (value != null) mmsSenderAddress.put(columnName, value)
                                }
                                val displayName = lookupDisplayName(
                                    appContext, displayNames, address.getString(addressIndex)
                                )
                                if (displayName != null) mmsSenderAddress.put(
                                    "__display_name", displayName
                                )
                                mmsMessage.put("__sender_address", mmsSenderAddress)
                                break
                            }
                        } while (address.moveToNext())
                    }
                    // write array of recipient address objects
                    if (address.moveToFirst()) {
                        val mmsRecipientAddresses = JSONArray()
                        do {
                            if (addressTypeIndex.let { x -> address.getString(x) } != PDU_HEADERS_FROM) {
                                val mmsRecipientAddress = JSONObject()
                                address.columnNames.forEachIndexed { i, columnName ->
                                    val value = address.getString(i)
                                    if (value != null) mmsRecipientAddress.put(
                                        columnName, value
                                    )
                                }
                                val displayName = lookupDisplayName(
                                    appContext, displayNames, address.getString(addressIndex)
                                )
                                if (displayName != null) mmsRecipientAddress.put(
                                    "__display_name", displayName
                                )
                                mmsRecipientAddresses.put(mmsRecipientAddress)
                            }
                        } while (address.moveToNext())
                        mmsMessage.put("__recipient_addresses", mmsRecipientAddresses)
                    }
                }
                val partCursor = appContext.contentResolver.query(
                    "content://mms/part".toUri(),
//                      Uri.parse("content://mms/$msgId/part"),
                    null, "mid=?", arrayOf(msgId), "seq ASC"
                )
                // write array of MMS parts
                partCursor?.use { part ->
                    if (part.moveToFirst()) {
                        val mmsParts = JSONArray()
                        val partIdIndex = part.getColumnIndexOrThrow("_id")
                        val dataIndex = part.getColumnIndexOrThrow("_data")
                        do {
                            val mmsPart = JSONObject()
                            part.columnNames.forEachIndexed { i, columnName ->
                                val value = part.getString(i)
                                if (value != null) mmsPart.put(columnName, value)
                            }
                            if (prefs.getBoolean("include_binary_data", true) && part.getString(
                                    dataIndex
                                ) != null
                            ) {
                                var filename = mmsPart.getString(Telephony.Mms.Part._DATA)
                                    .toUri().lastPathSegment
                                // see https://android.googlesource.com/platform/packages/providers/TelephonyProvider/+/master/src/com/android/providers/telephony/MmsProvider.java#520
                                if (filename == null) {
                                    filename =
                                        "MISSING_FILENAME" + System.currentTimeMillis() + mmsPart.getString(
                                            Telephony.Mms.Part.CONTENT_LOCATION
                                        )
                                    mmsPart.put(Telephony.Mms.Part._DATA, filename)
                                }
                                filename = "data/$filename"
                                mmsPartList.add(
                                    MmsBinaryPart(
                                        ("content://mms/part/" + part.getString(partIdIndex)).toUri(),
                                        filename
                                    )
                                )
                            }
                            mmsParts.put(mmsPart)
                        } while (part.moveToNext())
                        mmsMessage.put("__parts", mmsParts)
                    }
                }
                zipOutputStream.write((mmsMessage.toString() + "\n").toByteArray())
                progress = progress.copy(
                    current = progress.current + 1,
                    message = appContext.getString(
                        R.string.mms_export_progress,
                        progress.current + 1,
                        progress.total,
                    ),
                )
                updateProgress(progress)
                if (progress.current == (prefs.getString("max_records", "")?.toIntOrNull()
                        ?: -1)
                ) break
            } while (it.moveToNext())
        }
    }
    return progress.current
}

@RequiresApi(Build.VERSION_CODES.M)
suspend fun importMessages(
    appContext: Context, uri: Uri, updateProgress: suspend (Progress) -> Unit
): MessageTotal {
    val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
    var progress = Progress(0, 0, null)
    val deduplication = prefs.getBoolean("deduplication", false)
    return withContext(Dispatchers.IO) {
        val totals = MessageTotal()
        // get column names of local SMS, MMS, and MMS part tables
        val smsColumns = mutableSetOf<String>()
        val smsCursor =
            appContext.contentResolver.query(Telephony.Sms.CONTENT_URI, null, null, null, null)
        smsCursor?.use {
            smsColumns.addAll(it.columnNames)
            smsColumns.removeAll(
                setOf(
                    "_id", "thread_id", "deleted", "sync_state", "need_download"
                )
            )
        }
        val mmsColumns = mutableSetOf<String>()
        val mmsCursor =
            appContext.contentResolver.query(Telephony.Mms.CONTENT_URI, null, null, null, null)
        mmsCursor?.use {
            mmsColumns.addAll(it.columnNames)
            mmsColumns.removeAll(
                setOf(
                    "_id", "thread_id", "deleted", "sync_state", "need_download"
                )
            )
        }
        val partColumns = mutableSetOf<String>()
        // I can't find an officially documented way of getting the Part table URI for API < 29
        // the idea to use "content://mms/part" comes from here:
        // https://stackoverflow.com/a/6446831
        val partTableUri =
            if (SDK_INT >= 29) Telephony.Mms.Part.CONTENT_URI else "content://mms/part".toUri()
        val partCursor = appContext.contentResolver.query(partTableUri, null, null, null, null)
        partCursor?.use {
            partColumns.addAll(it.columnNames)
            partColumns.removeAll(
                setOf(
                    Telephony.Mms.Part.MSG_ID,
                    Telephony.Mms.Part._ID,
                    Telephony.Mms.Part._DATA,
                    Telephony.Mms.Part._COUNT
                )
            )
        }
        val addressExcludedKeys = setOf(
            Telephony.Mms.Addr._ID,
            Telephony.Mms.Addr._COUNT,
            Telephony.Mms.Addr.MSG_ID,
            "__display_name"
        )
        val threadIdMap = HashMap<String, String>()
        uri.let { zipUri ->
            val mmsPartMap =
                mutableMapOf<String, Uri>() // This assumes that no binary data file is ever referenced by more than one message part
            appContext.contentResolver.openInputStream(zipUri).use { inputStream ->
                ZipInputStream(inputStream).use { zipInputStream ->
                    var zipEntry = zipInputStream.nextEntry
                    while (zipEntry != null) {
                        if (zipEntry.name == "messages.ndjson") {
                            break
                        }
                        zipEntry = zipInputStream.nextEntry
                    }
                    if (zipEntry == null) {
                        throw UserFriendlyException(
                            appContext.getString(R.string.missing_messages_ndjson_error)
                        )
                    }
                    progress =
                        progress.copy(message = appContext.getString(R.string.importing_messages))
                    updateProgress(progress)
                    BufferedReader(InputStreamReader(zipInputStream)).useLines { lines ->
                        lines.forEachIndexed JSONLine@{ lineNumber, line ->
                            coroutineContext.ensureActive()
                            Log.d(LOG_TAG, "Processing line #$lineNumber")
                            // Log.d(LOG_TAG, "Processing: $line")
                            val messageMetadata = ContentValues()
                            val messageJSON = JSONObject(line)
                            val oldThreadId = messageJSON.optString("thread_id")
                            // See https://github.com/tmo1/sms-ie/issues/128
                            if (!prefs.getBoolean("import_sub_ids", false)) {
                                messageJSON.put("sub_id", "-1")
                            }
                            if (oldThreadId in threadIdMap) {
                                messageMetadata.put(
                                    "thread_id", threadIdMap[oldThreadId]
                                )
                            }
                            if (!messageJSON.has("m_type")) { // it's SMS
                                Log.d(LOG_TAG, "Message is SMS")
                                // It would obviously be more efficient to break rather then continue when hitting 'max_records', but this option is primarily for debugging and the inefficiency doesn't matter very much
                                if (!prefs.getBoolean(
                                        "sms", true
                                    ) || totals.sms == (prefs.getString(
                                        "max_records", ""
                                    )?.toIntOrNull() ?: -1)
                                ) {
                                    Log.d(LOG_TAG, "Skipping due to debug settings")
                                    return@JSONLine
                                }
                                if (deduplication) {
                                    val smsDuplicatesCursor = appContext.contentResolver.query(
                                        Telephony.Sms.CONTENT_URI,
                                        arrayOf(Telephony.Sms._ID),
                                        "${Telephony.Sms.ADDRESS}=? AND ${Telephony.Sms.TYPE}=? AND ${Telephony.Sms.DATE}=? AND ${Telephony.Sms.BODY}=?",
                                        arrayOf(
                                            messageJSON.optString(Telephony.Sms.ADDRESS),
                                            messageJSON.optString(Telephony.Sms.TYPE),
                                            messageJSON.optString(Telephony.Sms.DATE),
                                            messageJSON.optString(Telephony.Sms.BODY)
                                        ),
                                        null
                                    )
                                    smsDuplicatesCursor?.use {
                                        if (it.moveToFirst()) {
                                            Log.d(LOG_TAG, "Duplicate message - skipping")
                                            return@JSONLine
                                        }
                                    }
                                }
                                messageJSON.keys().forEach { key ->
                                    if (key in smsColumns) messageMetadata.put(
                                        key, messageJSON.getString(key)
                                    )
                                }/* If we don't yet have a 'thread_id' (i.e., the message has a new
                                   'thread_id' that we haven't yet encountered and so isn't yet in
                                   'threadIdMap'), then we need to get a new 'thread_id' and record the mapping
                                   between the old and new ones in 'threadIdMap'
                                */
                                if (!messageMetadata.containsKey("thread_id")) {
                                    val newThreadId = Telephony.Threads.getOrCreateThreadId(
                                        appContext,
                                        messageMetadata.getAsString(Telephony.TextBasedSmsColumns.ADDRESS)
                                    )
                                    messageMetadata.put("thread_id", newThreadId)
                                    if (oldThreadId != "") {
                                        threadIdMap[oldThreadId] = newThreadId.toString()
                                    }
                                }
                                //Log.v(LOG_TAG, "Original thread_id: $oldThreadId\t New thread_id: ${messageMetadata.getAsString("thread_id")}")
                                val insertUri = appContext.contentResolver.insert(
                                    Telephony.Sms.CONTENT_URI, messageMetadata
                                )
                                if (insertUri == null) {
                                    Log.e(LOG_TAG, "SMS insert failed!")
                                } else {
                                    Log.d(LOG_TAG, "SMS insert succeeded")
                                    totals.sms++
                                    progress = progress.copy(
                                        message = appContext.getString(
                                            R.string.message_import_progress,
                                            totals.sms,
                                            totals.mms,
                                        )
                                    )
                                    updateProgress(progress)
                                }
                            } else { // it's MMS
                                Log.d(LOG_TAG, "Message is MMS")
                                if (!prefs.getBoolean(
                                        "mms", true
                                    ) || totals.mms == (prefs.getString(
                                        "max_records", ""
                                    )?.toIntOrNull() ?: -1)
                                ) {
                                    Log.d(LOG_TAG, "Skipping due to debug settings")
                                    return@JSONLine
                                }
                                if (deduplication) {
                                    val messageID = messageJSON.optString(Telephony.Mms.MESSAGE_ID)
                                    val contentLocation =
                                        messageJSON.optString(Telephony.Mms.CONTENT_LOCATION)
                                    var selection =
                                        "${Telephony.Mms.DATE}=? AND ${Telephony.Mms.MESSAGE_BOX}=?"
                                    var selectionArgs = arrayOf(
                                        messageJSON.optString(Telephony.Mms.DATE),
                                        messageJSON.optString(Telephony.Mms.MESSAGE_BOX)
                                    )
                                    if (messageID != "") {
                                        selection = "$selection AND ${Telephony.Mms.MESSAGE_ID}=?"
                                        selectionArgs += messageJSON.optString(Telephony.Mms.MESSAGE_ID)
                                    } else if (contentLocation != "") {
                                        selection =
                                            "$selection AND ${Telephony.Mms.CONTENT_LOCATION}=?"
                                        selectionArgs += messageJSON.optString(Telephony.Mms.CONTENT_LOCATION)
                                    }
                                    val mmsDuplicatesCursor = appContext.contentResolver.query(
                                        Telephony.Mms.CONTENT_URI,
                                        arrayOf(Telephony.Mms._ID),
                                        selection,
                                        selectionArgs,
                                        null
                                    )
                                    mmsDuplicatesCursor?.use {
                                        if (it.moveToFirst()) {
                                            Log.d(LOG_TAG, "Duplicate message - skipping")
                                            return@JSONLine
                                        }
                                    }
                                }
                                messageJSON.keys().forEach { key ->
                                    if (key in mmsColumns) messageMetadata.put(
                                        key, messageJSON.getString(key)
                                    )
                                }
                                val addresses = mutableSetOf<ContentValues>()
                                val senderAddress = messageJSON.optJSONObject("__sender_address")
                                senderAddress?.let {
                                    val address = ContentValues()
                                    it.keys().forEach { addressKey ->
                                        if (addressKey !in addressExcludedKeys) address.put(
                                            addressKey, senderAddress.getString(addressKey)
                                        )
                                    }
                                    addresses.add(address)
                                }
                                val recipientAddresses =
                                    messageJSON.optJSONArray("__recipient_addresses")
                                recipientAddresses?.let {
                                    for (i in 0 until recipientAddresses.length()) {
                                        val recipientAddress = recipientAddresses.getJSONObject(i)
                                        val address = ContentValues()
                                        for (recipientAddressKey in recipientAddress.keys()) {
                                            if (recipientAddressKey !in addressExcludedKeys) {
                                                address.put(
                                                    recipientAddressKey, recipientAddress.getString(
                                                        recipientAddressKey
                                                    )
                                                )
                                            }
                                        }
                                        addresses.add(address)
                                    }
                                }/* If we don't yet have a thread_id (i.e., the message has a new
                                   thread_id that we haven't yet encountered and so isn't yet in
                                   threadIdMap), then we need to get a new thread_id and record the mapping
                                   between the old and new ones in threadIdMap
                                */
                                if (!messageMetadata.containsKey("thread_id")) {/* Calling getOrCreateThreadId with an empty set of addresses
                                    will cause it to complain:
                                    "getThreadId: NO receipients specified -- NOT creating thread" (sic)
                                    and then throw an exception:
                                    "java.lang.IllegalArgumentException: Unable to find or allocate a thread ID."
                                    So if an MMS message has no associated recipient addresses, we add a dummy one here.
                                    See: https://github.com/tmo1/sms-ie/issues/150
                                   */
                                    if (addresses.isEmpty()) {
                                        addresses.add(ContentValues())
                                    }
                                    val newThreadId = Telephony.Threads.getOrCreateThreadId(
                                        appContext,
                                        addresses.map { x -> x.getAsString(Telephony.Mms.Addr.ADDRESS) }
                                            .toSet())
                                    messageMetadata.put("thread_id", newThreadId)
                                    if (oldThreadId != "") {
                                        threadIdMap[oldThreadId] = newThreadId.toString()
                                    }
                                }
                                val insertUri = appContext.contentResolver.insert(
                                    Telephony.Mms.CONTENT_URI, messageMetadata
                                )
                                if (insertUri == null) {
                                    Log.e(LOG_TAG, "MMS insert failed!")
                                } else {
                                    Log.d(LOG_TAG, "MMS insert succeeded")
                                    totals.mms++
                                    progress = progress.copy(
                                        message = appContext.getString(
                                            R.string.message_import_progress,
                                            totals.sms,
                                            totals.mms,
                                        )
                                    )
                                    updateProgress(progress)
                                    val messageId = insertUri.lastPathSegment
                                    val addressUri = "content://mms/$messageId/addr".toUri()
                                    addresses.forEach { address ->
                                        // Some MMS address metadata contain sub_ids, and attempting to import them can cause the address import to fail:
                                        // See: https://github.com/tmo1/sms-ie/issues/213
                                        if (!prefs.getBoolean(
                                                "import_sub_ids", false
                                            ) && address.containsKey("sub_id")
                                        ) {
                                            address.put("sub_id", "-1")
                                        }
                                        address.put(
                                            Telephony.Mms.Addr.MSG_ID, messageId
                                        )/*Log.v(
                                            LOG_TAG,
                                            "Trying to insert MMS address - metadata:" + address.toString()
                                        )*/
                                        val insertAddressUri = appContext.contentResolver.insert(
                                            addressUri, address
                                        )
                                        if (insertAddressUri == null) Log.e(
                                            LOG_TAG, "MMS address insert failed!"
                                        )
                                        else Log.d(LOG_TAG, "MMS address insert succeeded")
                                    }
                                    val messageParts = messageJSON.optJSONArray("__parts")
                                    messageParts?.let {
                                        val partUri = "content://mms/$messageId/part".toUri()
                                        for (i in 0 until messageParts.length()) {
                                            val messagePart = messageParts.getJSONObject(i)
                                            val part = ContentValues()
                                            part.put(Telephony.Mms.Part.MSG_ID, messageId)
                                            for (partKey in messagePart.keys()) {
                                                if (partKey in partColumns) part.put(
                                                    partKey, messagePart.getString(partKey)
                                                )
                                            }
                                            // Some MMS part metadata contain sub_ids, and attempting to import them can cause a FileNotFoundException: No entry for content
                                            // when subsequently trying to write the part's binary data.
                                            // See: https://github.com/tmo1/sms-ie/issues/142
                                            if (!prefs.getBoolean(
                                                    "import_sub_ids", false
                                                ) && part.containsKey("sub_id")
                                            ) {
                                                part.put("sub_id", "-1")
                                            }
                                            val insertPartUri = appContext.contentResolver.insert(
                                                partUri, part
                                            )
                                            if (insertPartUri == null)
//                                                    Log.e(
//                                                        LOG_TAG,
//                                                        "MMS part insert failed! Part metadata: $part"
//                                                    )
                                                Log.e(LOG_TAG, "MMS part insert failed!")
                                            else {
                                                Log.d(LOG_TAG, "MMS part insert succeeded")
                                                // Log.d(LOG_TAG, "MMS part insert succeeded - old part ID: ${messagePart.getString(Telephony.Mms.Part._ID)}, old message ID: ${messagePart.getString(Telephony.Mms.Part.MSG_ID)}")
                                                if (prefs.getBoolean(
                                                        "include_binary_data", true
                                                    )
                                                ) {
                                                    val filename =
                                                        messagePart.optString(Telephony.Mms.Part._DATA)
                                                    if (filename != "") {
                                                        mmsPartMap[filename.toUri().lastPathSegment.toString()] =
                                                            insertPartUri
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (prefs.getBoolean("include_binary_data", true)) {
                progress =
                    progress.copy(message = appContext.getString(R.string.copying_mms_binary_data))
                updateProgress(progress)

                val buffer = ByteArray(1048576)
                appContext.contentResolver.openInputStream(zipUri).use { inputStream ->
                    ZipInputStream(inputStream).use { zipInputStream ->
                        var zipEntry = zipInputStream.nextEntry
                        while (zipEntry != null) {
                            coroutineContext.ensureActive()

                            if (zipEntry.name.startsWith("data/")) {
                                val partUri = mmsPartMap[zipEntry.name.substring(5)]
                                partUri?.let {
                                    Log.d(LOG_TAG, "Writing part: $zipEntry")
                                    //Log.v(LOG_TAG, "Writing to: $partUri")
                                    appContext.contentResolver.openOutputStream(
                                        partUri
                                    )?.use { outputStream ->
                                        var n = zipInputStream.read(
                                            buffer
                                        )
                                        while (n > -1) {
                                            //Log.v(LOG_TAG, "Read $n bytes")
                                            outputStream.write(
                                                buffer, 0, n
                                            )
                                            n = zipInputStream.read(
                                                buffer
                                            )
                                        }
                                    } ?: Log.e(
                                        LOG_TAG,
                                        "Error opening OutputStream to write MMS binary data"
                                    )
                                }
                            }
                            zipEntry = zipInputStream.nextEntry
                        }
                    }
                }
            }
        }
        totals
    }
}
