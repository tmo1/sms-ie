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
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import androidx.work.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.TimeUnit

// https://developer.android.com/topic/libraries/architecture/workmanager/basics#kotlin
// https://developer.android.com/codelabs/android-workmanager#3
class ExportWorker(appContext: Context, workerParams: WorkerParameters) :
    Worker(appContext, workerParams) {
    override fun doWork(): Result {
        val context = applicationContext
        var result = Result.failure()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val treeUri = Uri.parse(prefs.getString(EXPORT_DIR, ""))
        val documentTree = context.let { DocumentFile.fromTreeUri(context, treeUri) }
        val date = getCurrentDateTime()
        val dateInString = date.toString("yyyy-MM-dd")
        val file = documentTree?.createFile("application/json", "messages-$dateInString.json")
//        val file = documentTree?.createFile("text/plain", "sms-ie-worker.test")
        val fileUri = file?.uri
        if (fileUri != null) {
            CoroutineScope(Dispatchers.Main).launch {
                Log.v(LOG_TAG, "Beginning message export ...")
                val total = exportMessages(context, fileUri)
                Log.v(
                    LOG_TAG,
                    "Message export successful: ${total.sms} SMSs and ${total.mms} MMSs exported"
                )
                result = Result.success()
            }
//                  Log.v(LOG_TAG, "File acquired: $fileUri")
/*            context.contentResolver?.openOutputStream(fileUri).use { outputStream ->
                BufferedWriter(OutputStreamWriter(outputStream)).use { writer ->
                    writer.write("Worker works!")
                }
            }*/
        }
        updateExportWork(context)
        return result
    }
}

fun updateExportWork(context: Context) {
    WorkManager.getInstance(context)
        .cancelAllWorkByTag(EXPORT_WORK_TAG)
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    if (prefs.getBoolean("schedule_export", false)) {
        // https://stackoverflow.com/questions/4389500/how-can-i-find-the-amount-of-seconds-passed-from-the-midnight-with-java
        val now = Calendar.getInstance()
        val exportTime = Calendar.getInstance()
        exportTime.set(Calendar.HOUR_OF_DAY, 0)
        exportTime.set(Calendar.MINUTE, 0)
        exportTime.set(Calendar.SECOND, 0)
        exportTime.set(Calendar.MILLISECOND, 0)
        exportTime.add(Calendar.MINUTE, prefs.getInt("export_time", 0))
        if (exportTime < now) {
            exportTime.add(Calendar.DAY_OF_MONTH, 1)
        }
        val deferMillis = exportTime.timeInMillis - now.timeInMillis
        Log.v(LOG_TAG, "Scheduling backup for $deferMillis milliseconds from now")
        val exportRequest: WorkRequest =
            OneTimeWorkRequestBuilder<ExportWorker>()
                .addTag(EXPORT_WORK_TAG)
                .setInitialDelay(deferMillis, TimeUnit.MILLISECONDS)
                .build()
        WorkManager
            .getInstance(context)
            .enqueue(exportRequest)
    }
}
