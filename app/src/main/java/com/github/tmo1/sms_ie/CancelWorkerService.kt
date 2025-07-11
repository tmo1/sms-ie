/*
 * SMS Import / Export: a simple Android app for importing and exporting SMS messages from and to JSON files.
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
 * along with SMS Import / Export.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.github.tmo1.sms_ie

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.content.IntentCompat
import androidx.work.WorkManager
import java.util.UUID

// This exists only so that the Cancel button in the ImportExportWorker's notification has something
// it can launch.
class CancelWorkerService : Service() {
    companion object {
        private const val EXTRA_UUID = "uuid"

        fun createIntent(context: Context, uuid: UUID) =
            Intent(context, CancelWorkerService::class.java).apply {
                putExtra(EXTRA_UUID, uuid)
            }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        val uuid = IntentCompat.getSerializableExtra(intent, EXTRA_UUID, UUID::class.java)!!
        Log.i(LOG_TAG, "Cancelling worker: $uuid")

        WorkManager.getInstance(this).cancelWorkById(uuid)
        stopSelf()

        return START_NOT_STICKY
    }
}
