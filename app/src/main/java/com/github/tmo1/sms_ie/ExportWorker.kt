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
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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
        var result = Result.success()
        var messageTotal = MessageTotal()
        var callsTotal = 0
        var contacts = 0
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val treeUri = Uri.parse(prefs.getString(EXPORT_DIR, ""))
        val documentTree = context.let { DocumentFile.fromTreeUri(context, treeUri) }
        val date = getCurrentDateTime()
        val dateInString = "-${date.toString("yyyy-MM-dd")}"
        CoroutineScope(Dispatchers.IO).launch {
            if (prefs.getBoolean("export_messages", true)) {
                val file =
                    documentTree?.createFile("application/json", "messages$dateInString.json")
                val fileUri = file?.uri
                if (fileUri != null) {
                    Log.v(LOG_TAG, "Beginning messages export ...")
                    messageTotal = exportMessages(context, fileUri, null, null)
                    Log.v(
                        LOG_TAG,
                        "Messages export successful: ${messageTotal.sms} SMSs and ${messageTotal.mms} MMSs exported"
                    )
                    deleteOldExports(prefs, documentTree, file, "messages")
                } else {
                    Log.e(LOG_TAG, "Messages export failed - could not create file")
                    result = Result.failure()
                }
            }
            if (prefs.getBoolean("export_calls", true)) {
                val file =
                    documentTree?.createFile("application/json", "calls$dateInString.json")
                val fileUri = file?.uri
                if (fileUri != null) {
                    Log.v(LOG_TAG, "Beginning call log export ...")
                    val total = exportCallLog(context, fileUri, null, null)
                    callsTotal = total.sms
                    Log.v(
                        LOG_TAG,
                        "Call log export successful: $callsTotal calls exported"
                    )
                    deleteOldExports(prefs, documentTree, file, "calls")
                } else {
                    Log.e(LOG_TAG, "Call log export failed - could not create file")
                    result = Result.failure()
                }
            }
            if (prefs.getBoolean("export_contacts", true)) {
                val file =
                    documentTree?.createFile("application/json", "contacts$dateInString.json")
                val fileUri = file?.uri
                if (fileUri != null) {
                    Log.v(LOG_TAG, "Beginning contacts export ...")
                    contacts = exportContacts(context, fileUri, null, null)
                    Log.v(
                        LOG_TAG,
                        "Contacts export successful: $contacts contacts exported"
                    )
                    deleteOldExports(prefs, documentTree, file, "contacts")
                } else {
                    Log.e(LOG_TAG, "Contacts export failed - could not create file")
                    result = Result.failure()
                }
            }
            // see: https://stackoverflow.com/a/8765766
            val notification = if (result == Result.success()) context.getString(
                R.string.scheduled_export_success,
                messageTotal.sms,
                messageTotal.mms,
                callsTotal,
                contacts
            ) else context.getString(R.string.scheduled_export_failure)
            // https://developer.android.com/training/notify-user/build-notification#builder
            val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                //.setSmallIcon(R.drawable.ic_launcher_foreground)
                .setSmallIcon(R.drawable.ic_scheduled_export_done)
                .setContentTitle(context.getString(R.string.scheduled_export_executed))
                .setContentText(notification)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            // https://developer.android.com/training/notify-user/build-notification#notify
            with(NotificationManagerCompat.from(applicationContext))
            {
                // notificationId is a unique int for each notification that you must define
                notify(0, builder.build())
            }
        }
        updateExportWork(context)
//FIXME: as written, this always returns success, since the work is launched asynchronously and these lines execute immediately upon coroutine launch
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

fun deleteOldExports(
    prefs: SharedPreferences,
    documentTree: DocumentFile,
    newExport: DocumentFile?,
    prefix: String
) {
    if (prefs.getBoolean("delete_old_exports", false)) {
        Log.v(LOG_TAG, "Deleting old exports ...")
        // The following line is necessary in case there already existed a file with the
        // provided filename, in which case Android will add a numeric suffix to the new
        // file's filename ("messages-yyyy-MM-dd (1).json")
        val newFilename = newExport?.name.toString()
        val files = documentTree.listFiles()
        var total = 0
        files.forEach {
            val name = it.name
            if (name != null && name != newFilename && name.startsWith(prefix) && name.endsWith(
                    ".json"
                )
            ) {
                it.delete()
                total++
            }
        }
        if (prefs.getBoolean("remove_datestamps_from_filenames", false)
        ) {
            newExport?.renameTo("$prefix.json")
        }
        Log.v(LOG_TAG, "$total exports deleted")
    }
}
