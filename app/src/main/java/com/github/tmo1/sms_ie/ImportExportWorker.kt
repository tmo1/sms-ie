/*
 * SMS Import / Export: a simple Android app for importing and exporting SMS and MMS messages,
 * call logs, and contacts, from and to JSON / NDJSON files.
 *
 * Copyright (c) 2022-2023,2025 Thomas More
 * Copyright (c) 2023-2024 Andrew Gunnerson
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

package com.github.tmo1.sms_ie

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

// https://developer.android.com/topic/libraries/architecture/workmanager/basics#kotlin
// https://developer.android.com/codelabs/android-workmanager#3
class ImportExportWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {
    companion object {
        const val TAG_AUTOMATIC_EXPORT = "export"
    }

    private suspend fun updateProgress(progress: Progress) {
        setProgress(progress.toWorkData())
    }

    override suspend fun doWork(): Result {
        val context = applicationContext
        var result = Result.success()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        // Android 14 introduced a new battery optimization that will kill apps that perform too
        // many binder transactions in the background, which can happen when exporting many
        // messages. Running the service in the foreground prevents the app from being killed.
        // https://android.googlesource.com/platform/frameworks/base.git/+/71d75c09b9a06732a6edb4d1488d2aa3eb779e14%5E%21/
        val foregroundNotification =
            NotificationCompat.Builder(applicationContext, CHANNEL_ID_PERSISTENT)
                .setSmallIcon(R.mipmap.ic_launcher_foreground)
                .setContentTitle(context.getString(R.string.scheduled_export_executing))
                .setOngoing(true).build()
        val foregroundFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        try {
            setForeground(
                ForegroundInfo(
                    NOTIFICATION_ID_PERSISTENT, foregroundNotification, foregroundFlags
                )
            )
        } catch (e: Exception) {
            // If the user didn't allow the disabling of battery optimizations, then Android 12+'s
            // restrictions for starting a foreground service from the background will prevent this
            // from working. Try to run the job in the background anyway because it might work if
            // there aren't too many items to export.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                Log.w(LOG_TAG, "Foreground service not allowed - trying to run in background", e)
            } else {
                throw e
            }
        }

        withContext(Dispatchers.IO) {
            val message = try {
                val (messages, calls, contacts) = automaticExport(context, ::updateProgress)

                context.getString(
                    R.string.scheduled_export_success,
                    messages.sms,
                    messages.mms,
                    calls,
                    contacts,
                )
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Scheduled export failed", e)
                result = Result.failure()

                context.getString(R.string.scheduled_export_failure)
            }

            if (ActivityCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED && (prefs.getBoolean(
                    "export_success_notification", true
                ) || result != Result.success())
            ) {
                // https://developer.android.com/training/notify-user/build-notification#builder
                val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID_ALERTS)
                    //.setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setSmallIcon(R.drawable.ic_scheduled_export_done)
                    .setContentTitle(context.getString(R.string.scheduled_export_executed))
                    .setContentText(message)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                // https://developer.android.com/training/notify-user/build-notification#notify
                with(NotificationManagerCompat.from(applicationContext)) {
                    // notificationId is a unique int for each notification that you must define
                    notify(NOTIFICATION_ID_ALERT, builder.build())
                }
            }
        }
        scheduleAutomaticExport(context, false)
        //FIXME: as written, this always returns success, since the work is launched asynchronously and these lines execute immediately upon coroutine launch
        return result
    }
}

fun scheduleAutomaticExport(context: Context, cancel: Boolean) {
    if (cancel) {
        WorkManager.getInstance(context).cancelAllWorkByTag(ImportExportWorker.TAG_AUTOMATIC_EXPORT)
    }
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
        Log.d(LOG_TAG, "Scheduling backup for $deferMillis milliseconds from now")
        val exportRequest: WorkRequest =
            OneTimeWorkRequestBuilder<ImportExportWorker>().addTag(ImportExportWorker.TAG_AUTOMATIC_EXPORT)
                .setInitialDelay(deferMillis, TimeUnit.MILLISECONDS).build()
        WorkManager.getInstance(context).enqueue(exportRequest)
    }
}
