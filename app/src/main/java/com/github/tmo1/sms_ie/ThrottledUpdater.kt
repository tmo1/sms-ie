/*
 * SMS Import / Export: a simple Android app for importing and exporting SMS and MMS messages,
 * call logs, contacts, and blocked numbers from and to JSON / NDJSON files.
 *
 * Copyright (c) 2026 Thomas More
 * Copyright (c) 2026 Andrew Gunnerson
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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration

/**
 * Call a function on value updates, but at most once every [delay]. The latest update will always
 * be made visible after the delay when no further progress updates have occurred in the meantime.
 *
 * All methods are thread-safe.
 */
abstract class ThrottledUpdater<T: Any>(private val delay: Duration) {
    private var lastTimestamp = 0L
    private lateinit var currentValue: T
    private var delayedRetry: Job? = null
    private val lock = Mutex()

    abstract suspend fun onThrottledUpdate(value: T)

    suspend fun setValue(value: T) {
        lock.withLock {
            currentValue = value
            postValueLocked()
        }
    }

    private suspend fun postValueLocked() {
        val now = System.nanoTime()
        if (now - lastTimestamp < delay.inWholeNanoseconds) {
            // Ensure that a long-running operation that posts infrequent updates still has the
            // latest update shown after the delay. A pre-existing retry that was scheduled earlier
            // is never overwritten or else the delay would keep getting extended.
            if (delayedRetry == null) {
                delayedRetry = CoroutineScope(currentCoroutineContext()).launch {
                    setValueDelayed()
                }
            }

            return
        }

        // We're about to show the latest update. There's no need for a previously scheduled retry
        // anymore.
        lastTimestamp = now
        cancelLocked()

        onThrottledUpdate(currentValue)
    }

    private suspend fun setValueDelayed() {
        try {
            delay(delay)

            lock.withLock {
                delayedRetry = null
                postValueLocked()
            }
        } catch (_: CancellationException) {
            // Canceled either by postValueLocked() because a newer update could be shown in the
            // meantime, or by explicit cancellation.
        }
    }

    suspend fun cancelPendingUpdate() {
        lock.withLock {
            cancelLocked()
        }
    }

    private suspend fun cancelLocked() {
        delayedRetry?.let { job ->
            job.cancelAndJoin()
            delayedRetry = null
        }
    }
}
