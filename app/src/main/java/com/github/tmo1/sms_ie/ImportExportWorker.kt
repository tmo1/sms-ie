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
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.text.format.DateUtils.formatElapsedTime
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit

enum class Action {
    EXPORT_AUTOMATIC,
    EXPORT_CALL_LOG_MANUAL,
    IMPORT_CALL_LOG_MANUAL,
    EXPORT_CONTACTS_MANUAL,
    IMPORT_CONTACTS_MANUAL,
    EXPORT_MESSAGES_MANUAL,
    IMPORT_MESSAGES_MANUAL,
    WIPE_MESSAGES_MANUAL,
    ;

    // Wiping calls ContentResolver.delete(), which does not have a variant that accepts a
    // CancellationSignal instance. The operation cannot be cancelled without killing the app.
    val isCancellable: Boolean
        get() = this != WIPE_MESSAGES_MANUAL
}

data class SuccessData(val message: String) {
    constructor(outputData: Data) : this(
        outputData.getString("message") ?: "",
    )

    fun toOutputData(): Data = workDataOf(
        "success" to true,
        "message" to message,
    )
}

data class FailureData(val title: String, val message: String, val savedLogcat: Boolean) {
    constructor(outputData: Data) : this(
        outputData.getString("title") ?: "",
        outputData.getString("message") ?: "",
        outputData.getBoolean("saved_logcat", false),
    )

    fun toOutputData(): Data = workDataOf(
        "success" to false,
        "title" to title,
        "message" to message,
        "saved_logcat" to savedLogcat,
    )
}

// Prevent multiple operations from running at the same time. We don't use the unique work API
// because if there is an upcoming scheduled export, manual operations are forced to wait until that
// executes first.
private val GLOBAL_LOCK = Mutex()

fun logcatFile(context: Context) = File(context.getExternalFilesDir(null), "logcat.log")

// https://developer.android.com/topic/libraries/architecture/workmanager/basics#kotlin
// https://developer.android.com/codelabs/android-workmanager#3
class ImportExportWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {
    companion object {
        const val TAG_MANUAL_ACTION = "manual"
        const val TAG_AUTOMATIC_EXPORT = "export"
    }

    private val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
    private val action = inputData.getInt("action", -1).let { index ->
        if (index == -1) {
            // See scheduleAutomaticExport() below for why we don't specify this action explicitly.
            Action.EXPORT_AUTOMATIC
        } else {
            Action.values()[index]
        }
    }
    val actionFile = inputData.getString("file")?.toUri()

    // Avoid trying setForeground() multiple times when updating progress if it is not allowed.
    private var foregroundIsDenied = false
    // Avoid updating the notification too frequently or else Android will rate limit us and block
    // any notification from being sent.
    private var foregroundLastTimestamp = 0L

    private suspend fun updateProgress(progress: Progress) {
        // [Unthrottled] For updating MainActivity and anything else that might be monitoring this
        // worker's progress. We currently funnel information about whether the operation can be
        // cancelled here because WorkInfo does not expose the input parameters like the action.
        // MainActivity has no other way to know if this is cancellable.
        setProgress(progress.copy(canCancel = action.isCancellable).toWorkData())
        // [Throttled] For updating the foreground service notification.
        refreshForegroundNotification(progress)
    }

    override suspend fun doWork(): Result = GLOBAL_LOCK.withLock {
        val context = applicationContext

        refreshForegroundNotification(Progress(0, 0, null))

        // Redirecting stdout is better than using -f because the logcat implementation calls
        // fflush() only when outputting to stdout. When using -f, interrupting logcat may mean that
        // its buffered data doesn't get flushed.
        val logcatProcess = if (prefs.getBoolean("save_logcat", false)) {
            Log.d(LOG_TAG, "Starting log file")
            Log.d(LOG_TAG, "- App version: ${BuildConfig.VERSION_NAME}")
            Log.d(LOG_TAG, "- API level: ${Build.VERSION.SDK_INT}")

            val logcatFile = logcatFile(context)
            val logcatUseStdout = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            val logcatExtraArgs = if (logcatUseStdout) {
                emptyArray()
            } else {
                arrayOf("-f", logcatFile.absolutePath)
            }

            ProcessBuilder("logcat", "*:V", *logcatExtraArgs)
                .apply {
                    if (logcatUseStdout) {
                        redirectOutput(logcatFile)
                    }
                }
                .redirectErrorStream(true)
                .start()
        } else {
            null
        }

        val result = try {
            Log.i(LOG_TAG, "Starting $action")
            Result.success(performAction().toOutputData())
        } catch (e: Exception) {
            Log.e(LOG_TAG, "$action failed", e)

            val titleResId = when (action) {
                Action.EXPORT_AUTOMATIC -> R.string.scheduled_export_error_title
                Action.EXPORT_CALL_LOG_MANUAL -> R.string.call_log_export_error_title
                Action.IMPORT_CALL_LOG_MANUAL -> R.string.call_log_import_error_title
                Action.EXPORT_CONTACTS_MANUAL -> R.string.contacts_export_error_title
                Action.IMPORT_CONTACTS_MANUAL -> R.string.contacts_import_error_title
                Action.EXPORT_MESSAGES_MANUAL -> R.string.messages_export_error_title
                Action.IMPORT_MESSAGES_MANUAL -> R.string.messages_import_error_title
                Action.WIPE_MESSAGES_MANUAL -> R.string.messages_wipe_error_title
            }
            val title = context.getString(titleResId)
            val message = buildString {
                append(e.localizedMessage)

                // If we're showing an user-friendly error message, also include the description of
                // the direct cause.
                if (e is UserFriendlyException) {
                    e.cause?.let {
                        append("\n\n")
                        append(it.localizedMessage)
                    }
                }

                append("\n\n")
                if (logcatProcess != null) {
                    append(context.getString(R.string.see_logcat_save_enabled))
                } else {
                    append(context.getString(R.string.see_logcat_save_disabled))
                }
            }

            Result.failure(FailureData(title, message, logcatProcess != null).toOutputData())
        } finally {
            // Regardless of what happens, ensure that the next scheduled run occurs.
            if (action == Action.EXPORT_AUTOMATIC) {
                scheduleAutomaticExport(context, false)
            }
        }

        // The androidx work library currently has a bug where the foreground notification is
        // not removed when the foreground service is stopped after cancellation. This is
        // reproducible even if this function is just replaced with a simple delay(10000).
        if (isStopped) {
            Log.w(LOG_TAG, "Explicitly cancelling foreground notification")
            val notificationManager = NotificationManagerCompat.from(applicationContext)
            notificationManager.cancel(NOTIFICATION_ID_PERSISTENT)
        } else {
            notifyResult(result, action)
        }

        Log.i(LOG_TAG, "$action result: $result")

        // This log message also serves as an indicator to know that the logs are complete. See the
        // note about the -f option above.
        logcatProcess?.let {
            try {
                Log.d(LOG_TAG, "Stopping log file")
                delay(100)

                it.destroy()
            } finally {
                it.waitFor()
            }
        }

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

        val titleResId = when (action) {
            Action.EXPORT_AUTOMATIC -> R.string.scheduled_export_executing
            Action.EXPORT_CALL_LOG_MANUAL -> R.string.exporting_calls
            Action.IMPORT_CALL_LOG_MANUAL -> R.string.importing_calls
            Action.EXPORT_CONTACTS_MANUAL -> R.string.exporting_contacts
            Action.IMPORT_CONTACTS_MANUAL -> R.string.importing_contacts
            Action.EXPORT_MESSAGES_MANUAL -> R.string.exporting_messages
            Action.IMPORT_MESSAGES_MANUAL -> R.string.importing_messages
            Action.WIPE_MESSAGES_MANUAL -> R.string.wiping_messages
        }
        val title = applicationContext.getString(titleResId)

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
                // Ensure that the device won't vibrate or make a notification sound every time the
                // progress is updated.
                .setOnlyAlertOnce(true)
                .apply {
                    // Inhibit 10-second delay when showing persistent notification
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                    }

                    if (action.isCancellable) {
                        val actionPendingIntent = PendingIntent.getService(
                            applicationContext,
                            0,
                            CancelWorkerService.createIntent(applicationContext, id),
                            PendingIntent.FLAG_IMMUTABLE or
                                    PendingIntent.FLAG_UPDATE_CURRENT or
                                    PendingIntent.FLAG_ONE_SHOT,
                        )

                        addAction(NotificationCompat.Action.Builder(
                            null,
                            applicationContext.getString(android.R.string.cancel),
                            actionPendingIntent,
                        ).build())
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
        if (actionFile == null && action != Action.EXPORT_AUTOMATIC
                && action != Action.WIPE_MESSAGES_MANUAL) {
            throw IllegalStateException("No file specified for $action")
        }

        val context = applicationContext
        val startTime = System.nanoTime()

        val successMsg = when (action) {
            Action.EXPORT_AUTOMATIC -> {
                val (messages, calls, contacts) = automaticExport(context, ::updateProgress)

                context.getString(
                    R.string.scheduled_export_success,
                    messages.sms,
                    messages.mms,
                    calls,
                    contacts,
                )
            }
            Action.EXPORT_CALL_LOG_MANUAL -> {
                val calls = exportCallLog(context, actionFile!!, ::updateProgress)

                context.getString(
                    R.string.export_call_log_results, calls, formatElapsedTime(
                        TimeUnit.SECONDS.convert(
                            System.nanoTime() - startTime, TimeUnit.NANOSECONDS
                        )
                    )
                )
            }
            Action.IMPORT_CALL_LOG_MANUAL -> {
                val calls = importCallLog(context, actionFile!!, ::updateProgress)

                context.getString(
                    R.string.import_call_log_results, calls, formatElapsedTime(
                        TimeUnit.SECONDS.convert(
                            System.nanoTime() - startTime, TimeUnit.NANOSECONDS
                        )
                    )
                )
            }
            Action.EXPORT_CONTACTS_MANUAL -> {
                val contacts = exportContacts(context, actionFile!!, ::updateProgress)

                context.getString(
                    R.string.export_contacts_results, contacts, formatElapsedTime(
                        TimeUnit.SECONDS.convert(
                            System.nanoTime() - startTime, TimeUnit.NANOSECONDS
                        )
                    )
                )
            }
            Action.IMPORT_CONTACTS_MANUAL -> {
                val contacts = importContacts(context, actionFile!!, ::updateProgress)

                context.getString(
                    R.string.import_contacts_results, contacts, formatElapsedTime(
                        TimeUnit.SECONDS.convert(
                            System.nanoTime() - startTime, TimeUnit.NANOSECONDS
                        )
                    )
                )
            }
            Action.EXPORT_MESSAGES_MANUAL -> {
                val messages = exportMessages(context, actionFile!!, ::updateProgress)

                context.getString(
                    R.string.export_messages_results, messages.sms, messages.mms, formatElapsedTime(
                        TimeUnit.SECONDS.convert(
                            System.nanoTime() - startTime, TimeUnit.NANOSECONDS
                        )
                    )
                )
            }
            Action.IMPORT_MESSAGES_MANUAL -> {
                // MainActivity will not launch this action if the Android version is too old.
                @SuppressLint("NewApi")
                val messages = importMessages(context, actionFile!!, ::updateProgress)

                context.getString(
                    R.string.import_messages_results, messages.sms, messages.mms, formatElapsedTime(
                        TimeUnit.SECONDS.convert(
                            System.nanoTime() - startTime, TimeUnit.NANOSECONDS
                        )
                    )
                )
            }
            Action.WIPE_MESSAGES_MANUAL -> {
                wipeSmsAndMmsMessages(context, ::updateProgress)

                context.getString(R.string.messages_wiped)
            }
        }

        return SuccessData(successMsg)
    }

    @SuppressLint("InlinedApi")
    private fun notifyResult(result: Result, action: Action) {
        // We only show completion notifications for the scheduled export. For manual actions, the
        // error is communicated back to MainActivity through the worker result.
        if (action != Action.EXPORT_AUTOMATIC) {
            return
        }

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

fun scheduleManualAction(context: Context, action: Action, file: Uri?) {
    if (action == Action.EXPORT_AUTOMATIC) {
        throw IllegalArgumentException("Cannot schedule for manual action: $action")
    }

    val request = OneTimeWorkRequestBuilder<ImportExportWorker>()
        .addTag(ImportExportWorker.TAG_MANUAL_ACTION)
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .setInputData(workDataOf(
            "action" to action.ordinal,
            "file" to file?.toString(),
        ))
        .build()
    WorkManager.getInstance(context).enqueue(request)
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
        val exportRequest = OneTimeWorkRequestBuilder<ImportExportWorker>()
            .addTag(ImportExportWorker.TAG_AUTOMATIC_EXPORT)
            .setInitialDelay(deferMillis, TimeUnit.MILLISECONDS)
            // We intentionally do not pass in any input data for the periodic job. The parameters
            // are persisted to disk, which makes refactoring more difficult in the future since
            // care must be taken during upgrades to ensure old parameter values will still work.
            // Instead, we'll just assume that no parameters means scheduled automatic exports.
            .build()
        WorkManager.getInstance(context).enqueue(exportRequest)
    }
}
