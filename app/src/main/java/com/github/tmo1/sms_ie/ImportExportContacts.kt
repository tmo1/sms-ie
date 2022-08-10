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

import android.content.Context
import android.net.Uri
import android.provider.BaseColumns
import android.provider.ContactsContract
import android.util.JsonWriter
import android.widget.ProgressBar
import android.widget.TextView
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.OutputStreamWriter

suspend fun exportContacts(
    appContext: Context,
    file: Uri,
    progressBar: ProgressBar?,
    statusReportText: TextView?
): Int {
    //val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
    return withContext(Dispatchers.IO) {
        var total: Int
        //val displayNames = mutableMapOf<String, String?>()
        appContext.contentResolver.openOutputStream(file).use { outputStream ->
            BufferedWriter(OutputStreamWriter(outputStream)).use { writer ->
                val jsonWriter = JsonWriter(writer)
                jsonWriter.setIndent("  ")
                jsonWriter.beginArray()
                total = contactsToJSON(
                    appContext,
                    jsonWriter,
                    //displayNames,
                    progressBar,
                    statusReportText
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
    //displayNames: MutableMap<String, String?>,
    progressBar: ProgressBar?,
    statusReportText: TextView?
): Int {
    val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
    var total = 0
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
            val totalContacts = it.count
            initProgressBar(progressBar, it)
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
                                            val value = data.getString(i)
                                            if (value != null) jsonWriter.name(columnName)
                                                .value(value)
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
                total++
                incrementProgress(progressBar)
                setStatusText(
                    statusReportText,
                    appContext.getString(R.string.contacts_export_progress, total, totalContacts)
                )
                if (BuildConfig.DEBUG && total == (prefs.getString("max_messages", "")
                        ?.toIntOrNull() ?: -1)
                ) break
            } while (it.moveToNext())
            hideProgressBar(progressBar)
        }
    }
    return total
}
