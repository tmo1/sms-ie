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

import android.content.ContentProviderOperation
import android.content.Context
import android.database.Cursor.FIELD_TYPE_BLOB
import android.net.Uri
import android.provider.BaseColumns
import android.provider.ContactsContract
import android.util.Base64
import android.util.JsonReader
import android.util.JsonWriter
import android.util.Log
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter

suspend fun exportContacts(
    appContext: Context,
    file: Uri,
    updateProgress: suspend (Progress) -> Unit,
): Int {
    return withContext(Dispatchers.IO) {
        var total: Int
        appContext.contentResolver.openOutputStream(file).use { outputStream ->
            BufferedWriter(OutputStreamWriter(outputStream)).use { writer ->
                val jsonWriter = JsonWriter(writer)
                jsonWriter.setIndent("  ")
                jsonWriter.beginArray()
                total = contactsToJSON(
                    appContext,
                    jsonWriter,
                    updateProgress,
                )
                jsonWriter.endArray()
            }
        }
        total
    }
}

private suspend fun contactsToJSON(
    appContext: Context,
    jsonWriter: JsonWriter,
    updateProgress: suspend (Progress) -> Unit,
): Int {
    val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
    var progress = Progress(0, 0, null)
    //TODO
    val contactsCursor =
        appContext.contentResolver.query(
            //Uri.parse("content://call_log/calls"),
            ContactsContract.Contacts.CONTENT_URI,
            null,
            null,
            null,
            null
        )
    contactsCursor?.use { it ->
        if (it.moveToFirst()) {
            progress = progress.copy(total = it.count)
            updateProgress(progress)

            val contactsIdIndex = it.getColumnIndexOrThrow(BaseColumns._ID)
            do {
                jsonWriter.beginObject()
                it.columnNames.forEachIndexed { i, columnName ->
                    val value = it.getString(i)
                    if (value != null) jsonWriter.name(columnName).value(value)
                }
                val contactId = it.getString(contactsIdIndex)
                val rawContactsCursor = appContext.contentResolver.query(
                    ContactsContract.RawContacts.CONTENT_URI,
                    null,
                    ContactsContract.RawContacts.CONTACT_ID + "=?",
                    arrayOf(contactId),
                    null,
                    null
                )
                rawContactsCursor?.use { raw ->
                    if (raw.moveToFirst()) {
                        val rawContactsIdIndex = raw.getColumnIndexOrThrow(BaseColumns._ID)
                        jsonWriter.name("raw_contacts")
                        jsonWriter.beginArray()
                        do {
                            jsonWriter.beginObject()
                            raw.columnNames.forEachIndexed { i, columnName ->
                                val value = raw.getString(i)
                                if (value != null) jsonWriter.name(columnName).value(value)
                            }
                            val rawContactId = raw.getString(rawContactsIdIndex)
                            val dataCursor = appContext.contentResolver.query(
                                ContactsContract.Data.CONTENT_URI,
                                null,
                                ContactsContract.Data.RAW_CONTACT_ID + "=?",
                                arrayOf(rawContactId),
                                null,
                                null
                            )
                            dataCursor?.use { data ->
                                if (data.moveToFirst()) {
                                    jsonWriter.name("contacts_data")
                                    jsonWriter.beginArray()
                                    do {
                                        jsonWriter.beginObject()
                                        data.columnNames.forEachIndexed { i, columnName ->
                                            if (data.getType(i) != FIELD_TYPE_BLOB) {
                                                val value = data.getString(i)
                                                if (value != null) jsonWriter.name(columnName)
                                                    .value(value)
                                            } else {
                                                val value = data.getBlob(i)
                                                if (value != null) jsonWriter.name("${columnName}__base64__")
                                                    .value(
                                                        Base64.encodeToString(
                                                            value,
                                                            Base64.NO_WRAP
                                                        )
                                                    )
                                            }
                                        }
                                        jsonWriter.endObject()
                                    } while (data.moveToNext())
                                }
                            }
                            jsonWriter.endArray()
                            jsonWriter.endObject()
                        } while (raw.moveToNext())
                        jsonWriter.endArray()
                    }
                }
                jsonWriter.endObject()

                progress = progress.copy(
                    current = progress.current + 1,
                    message = appContext.getString(
                        R.string.contacts_export_progress,
                        progress.current + 1,
                        progress.total,
                    ),
                )
                updateProgress(progress)

                if (progress.current == (prefs.getString("max_records", "")?.toIntOrNull() ?: -1)) break
            } while (it.moveToNext())
        }
    }
    return progress.current
}

suspend fun importContacts(
    appContext: Context,
    uri: Uri,
    updateProgress: suspend (Progress) -> Unit,
): Int {
    return withContext(Dispatchers.IO) {
        var progress = Progress(0, 0, null)
        uri.let {
            appContext.contentResolver.openInputStream(it).use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    val jsonReader = JsonReader(reader)
                    val contactDataFields = mutableSetOf<String>()
                    for (i in 1..15) {
                        contactDataFields.add("data$i")
                    }
                    contactDataFields.add("mimetype")
                    try {
                        jsonReader.beginArray()
                        // Loop through Contacts
                        while (jsonReader.hasNext()) {
                            jsonReader.beginObject()
                            // Loop through Contact fields until we find the array of Raw Contacts
                            while (jsonReader.hasNext()) {
                                var name = jsonReader.nextName()
                                if (name == "raw_contacts") {
                                    jsonReader.beginArray()
                                    while (jsonReader.hasNext()) {
                                        // See https://developer.android.com/guide/topics/providers/contacts-provider#Transactions
                                        val ops = arrayListOf<ContentProviderOperation>()
                                        var op: ContentProviderOperation.Builder =
                                            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                                                .withValue(
                                                    ContactsContract.RawContacts.ACCOUNT_TYPE,
                                                    null
                                                )
                                                .withValue(
                                                    ContactsContract.RawContacts.ACCOUNT_NAME,
                                                    null
                                                )
                                        ops.add(op.build())
                                        jsonReader.beginObject()
                                        // Loop through Raw Contact fields until we find the array of Contacts Data
                                        while (jsonReader.hasNext()) {
                                            name = jsonReader.nextName()
                                            if (name == "contacts_data") {
                                                jsonReader.beginArray()
                                                while (jsonReader.hasNext()) {
                                                    jsonReader.beginObject()
                                                    op = ContentProviderOperation.newInsert(
                                                        ContactsContract.Data.CONTENT_URI
                                                    )
                                                        .withValueBackReference(
                                                            ContactsContract.Data.RAW_CONTACT_ID,
                                                            0
                                                        )
                                                    while (jsonReader.hasNext()) {
                                                        name = jsonReader.nextName()
                                                        val dataValue = jsonReader.nextString()
                                                        var base64 = false
                                                        if (name.length > 10 && name.substring(name.length - 10) == "__base64__") {
                                                            base64 = true
                                                            name =
                                                                name.substring(0, name.length - 10)
                                                        }
                                                        if (name in contactDataFields) {
                                                            if (base64) {
                                                                op.withValue(
                                                                    name,
                                                                    Base64.decode(
                                                                        dataValue,
                                                                        Base64.NO_WRAP
                                                                    )
                                                                )
                                                            } else {
                                                                op.withValue(name, dataValue)
                                                            }
                                                        }
                                                    }
                                                    op.withYieldAllowed(true)
                                                    ops.add(op.build())
                                                    jsonReader.endObject()
                                                }
                                                jsonReader.endArray()
                                            } else {
                                                jsonReader.nextString()
                                            }
                                        }
                                        try {
                                            appContext.contentResolver.applyBatch(
                                                ContactsContract.AUTHORITY,
                                                ops
                                            )

                                            progress = progress.copy(
                                                current = progress.current + 1,
                                                message = appContext.getString(
                                                    R.string.contacts_import_progress,
                                                    progress.current + 1,
                                                ),
                                            )
                                            updateProgress(progress)
                                        } catch (e: Exception) {
                                            Log.e(
                                                LOG_TAG,
                                                "Exception encountered while inserting contact: $e"
                                            )
                                        }
                                        jsonReader.endObject()
                                    }
                                    jsonReader.endArray()
                                } else {
                                    jsonReader.nextString()
                                }
                            }
                            jsonReader.endObject()
                        }
                        jsonReader.endArray()
                    } catch (e: Exception) {
                        displayError(
                            appContext,
                            e,
                            "Error importing contacts",
                            "Error parsing JSON"
                        )
                    }
                }
            }
            progress.current
        }
    }
}
