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
import android.annotation.SuppressLint
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
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.Calendar
import java.util.concurrent.TimeUnit

data class SuccessData(val message: String) {
    constructor(outputData: Data) : this(
        outputData.getString("message") ?: "",
    )

    fun toOutputData(): Data = workDataOf(
        "success" to true,
        "message" to message,
    )
}

data class FailureData(val title: String, val message: String) {
    constructor(outputData: Data) : this(
        outputData.getString("title") ?: "",
        outputData.getString("message") ?: "",
    )

    fun toOutputData(): Data = workDataOf(
        "success" to false,
        "title" to title,
        "message" to message,
    )
}

// https://developer.android.com/topic/libraries/architecture/workmanager/basics#kotlin
// https://developer.android.com/codelabs/android-workmanager#3
class ImportExportWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {
    companion object {
        const val TAG_AUTOMATIC_EXPORT = "export"
    }

    private val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)

    // Avoid trying setForeground() multiple times when updating progress if it is not allowed.
    private var foregroundIsDenied = false
    // Avoid updating the notification too frequently or else Android will rate limit us and block
    // any notification from being sent.
    private var foregroundLastTimestamp = 0L

    private suspend fun updateProgress(progress: Progress) {
        // [Unthrottled] For updating MainActivity and anything else that might be monitoring this
        // worker's progress.
        setProgress(progress.toWorkData())
        // [Throttled] For updating the foreground service notification.
        refreshForegroundNotification(progress)
    }

    override suspend fun doWork(): Result {
        val context = applicationContext

        refreshForegroundNotification(Progress(0, 0, null))

        val result = try {
            Log.i(LOG_TAG, "Starting scheduled export")
            Result.success(performAction().toOutputData())
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Scheduled export failed", e)

            val title = context.getString(R.string.scheduled_export_failure)
            val message = buildString {
                append(e.localizedMessage)
            }

            Result.failure(FailureData(title, message).toOutputData())
        } finally {
            // Regardless of what happens, ensure that the next scheduled run occurs.
            scheduleAutomaticExport(context, false)
        }

        notifyResult(result)

        Log.i(LOG_TAG, "Result: $result")

        //FIXME: as written, this always returns success, since the work is launched asynchronously and these lines execute immediately upon coroutine launch
        return result
    }

    private suspend fun refreshForegroundNotification(progress: Progress) {
        if (foregroundIsDenied) {
            return
        }

        // Throttle to 1 update per second to avoid hitting Android's rate limits.
        val now = System.nanoTime()
        if (now - foregroundLastTimestamp < 1_000_000_000) {
            return
        }
        foregroundLastTimestamp = now

        val title = applicationContext.getString(R.string.scheduled_export_executing)

        // Android 14 introduced a new battery optimization that will kill apps that perform too
        // many binder transactions in the background, which can happen when exporting many
        // messages. Running the service in the foreground prevents the app from being killed.
        // https://android.googlesource.com/platform/frameworks/base.git/+/71d75c09b9a06732a6edb4d1488d2aa3eb779e14%5E%21/
        val foregroundNotification =
            NotificationCompat.Builder(applicationContext, CHANNEL_ID_PERSISTENT)
                .setSmallIcon(R.mipmap.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(progress.message)
                .setStyle(NotificationCompat.BigTextStyle())
                .setProgress(progress.total, progress.current, progress.total == 0)
                .setOngoing(true)
                .apply {
                    // Inhibit 10-second delay when showing persistent notification
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                    }
                }
                .build()
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
                foregroundIsDenied = true
            } else {
                throw e
            }
        }
    }

    private suspend fun performAction(): SuccessData {
        val context = applicationContext
        val (messages, calls, contacts) = automaticExport(context, ::updateProgress)

        val successMsg = context.getString(
            R.string.scheduled_export_success,
            messages.sms,
            messages.mms,
            calls,
            contacts,
        )

        return SuccessData(successMsg)
    }

    @SuppressLint("InlinedApi")
    private fun notifyResult(result: Result) {
        val havePermissions = ActivityCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        val notifyForSuccess = prefs.getBoolean("export_success_notification", true)

        val success = result.outputData.getBoolean("success", false)
        val title = result.outputData.getString("title")
            ?: applicationContext.getString(R.string.scheduled_export_executed)
        val message = result.outputData.getString("message")

        if (havePermissions && (notifyForSuccess || !success)) {
            // https://developer.android.com/training/notify-user/build-notification#builder
            val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID_ALERTS)
                //.setSmallIcon(R.drawable.ic_launcher_foreground)
                .setSmallIcon(R.drawable.ic_scheduled_export_done)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle())
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)

            // https://developer.android.com/training/notify-user/build-notification#notify
            NotificationManagerCompat.from(applicationContext)
                // notificationId is a unique int for each notification that you must define
                .notify(NOTIFICATION_ID_ALERT, builder.build())
        }
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
