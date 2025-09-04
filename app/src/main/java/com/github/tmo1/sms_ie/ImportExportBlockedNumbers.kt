/*
 * SMS Import / Export: a simple Android app for importing and exporting SMS and MMS messages,
 * call logs, contacts, and blocked numbers from and to JSON / NDJSON files.
 *
 * Copyright (c) 2025 Thomas More
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

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.BaseColumns
import android.provider.BlockedNumberContract
import android.util.Log
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

suspend fun exportBlockedNumbers(
    appContext: Context, file: Uri, updateProgress: suspend (Progress) -> Unit
): Int {
    return withContext(Dispatchers.IO) {
        val total: Int
        appContext.contentResolver.openOutputStream(file).use { outputStream ->
            ZipOutputStream(outputStream).use { zipOutputStream ->
                val jsonZipEntry = ZipEntry("blocked_numbers.ndjson")
                zipOutputStream.putNextEntry(jsonZipEntry)
                total = blockedNumbersToJson(
                    appContext, zipOutputStream, updateProgress
                )
                zipOutputStream.closeEntry()
            }
            total
        }
    }
}

private suspend fun blockedNumbersToJson(
    appContext: Context,
    zipOutputStream: ZipOutputStream,
    updateProgress: suspend (Progress) -> Unit,
): Int {
    val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
    var progress = Progress(0, 0, null)
    val callCursor = appContext.contentResolver.query(
        BlockedNumberContract.BlockedNumbers.CONTENT_URI, null, null, null, null
    )
    callCursor?.use {
        if (it.moveToFirst()) {
            progress = progress.copy(total = it.count)
            updateProgress(progress)
            do {
                coroutineContext.ensureActive()
                val blockedNumber = JSONObject()
                it.columnNames.forEachIndexed { i, columnName ->
                    val value = it.getString(i)
                    if (value != null) blockedNumber.put(columnName, value)
                }
                zipOutputStream.write((blockedNumber.toString() + "\n").toByteArray())
                progress = progress.copy(
                    current = progress.current + 1,
                    message = appContext.getString(
                        R.string.blocked_numbers_export_progress,
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

suspend fun importBlockedNumbers(
    appContext: Context, uri: Uri, updateProgress: suspend (Progress) -> Unit
): Int {
    val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
    var progress = Progress(0, 0, null)
    return withContext(Dispatchers.IO) {
        val blockedNumberColumns = mutableSetOf<String>()
        val blockedNumberCursor = appContext.contentResolver.query(
            BlockedNumberContract.BlockedNumbers.CONTENT_URI, null, null, null, null
        )
        blockedNumberCursor?.use {
            blockedNumberColumns.addAll(it.columnNames subtract setOf(BaseColumns._ID))
        }
        uri.let { zipUri ->
            appContext.contentResolver.openInputStream(zipUri).use { inputStream ->
                ZipInputStream(inputStream).use { zipInputStream ->
                    var zipEntry = zipInputStream.nextEntry
                    while (zipEntry != null) {
                        if (zipEntry.name == "blocked_numbers.ndjson") {
                            break
                        }
                        zipEntry = zipInputStream.nextEntry
                    }
                    if (zipEntry == null) {
                        throw UserFriendlyException(
                            appContext.getString(R.string.missing_blocked_numbers_ndjson_error)
                        )
                    }
                    progress =
                        progress.copy(message = appContext.getString(R.string.importing_blocked_numbers))
                    updateProgress(progress)
                    BufferedReader(InputStreamReader(zipInputStream)).useLines { lines ->
                        lines.forEachIndexed JSONLine@{ lineNumber, line ->
                            if (progress.current == (prefs.getString(
                                    "max_records", ""
                                )?.toIntOrNull() ?: -1)
                            ) {
                                Log.d(LOG_TAG, "Skipping due to debug settings")
                                return@JSONLine
                            }
                            coroutineContext.ensureActive()
                            Log.d(LOG_TAG, "Processing line #$lineNumber")
                            // Log.d(LOG_TAG, "Processing: $line")
                            val blockedNumberMetadata = ContentValues()
                            val blockedNumberJSON = JSONObject(line)
                            blockedNumberJSON.keys().forEach { key ->
                                if (key in blockedNumberColumns) blockedNumberMetadata.put(
                                    key, blockedNumberJSON.getString(key)
                                )
                            }
                            val insertUri = appContext.contentResolver.insert(
                                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                                blockedNumberMetadata
                            )
                            if (insertUri == null) {
                                Log.e(LOG_TAG, "Blocked number insert failed!")
                            } else {
                                Log.d(LOG_TAG, "Blocked number insert succeeded")

                                progress = progress.copy(
                                    current = progress.current + 1,
                                    message = appContext.getString(
                                        R.string.blocked_numbers_import_progress,
                                        progress.current + 1,
                                    )
                                )
                                updateProgress(progress)
                            }
                        }
                    }
                }
            }
        }
        progress.current
    }
}
