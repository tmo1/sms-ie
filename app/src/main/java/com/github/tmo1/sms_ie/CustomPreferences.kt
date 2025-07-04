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
import java.util.concurrent.TimeUnit
import android.util.AttributeSet
import androidx.preference.DialogPreference

// from: https://old.black/2020/09/18/building-custom-timepicker-dialog-preference-in-android-kotlin/
class TimePickerPreference(context: Context, attrs: AttributeSet?) : DialogPreference(context, attrs) {

    // Get saved preference value (in minutes from midnight, so 1 AM is represented as 1*60 here
    fun getPersistedMinutesFromMidnight(): Int {
        return super.getPersistedInt(DEFAULT_MINUTES_FROM_MIDNIGHT)
    }

    // Save preference
    fun persistMinutesFromMidnight(minutesFromMidnight: Int) {
        super.persistInt(minutesFromMidnight)
        notifyChanged()
        scheduleAutomaticExport(context, true)
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        super.onSetInitialValue(defaultValue)
        summary = minutesFromMidnightToHourlyTime(getPersistedMinutesFromMidnight())
    }

    // Mostly for default values
    companion object {
        // default is 2:00 a.m.
        private const val DEFAULT_HOUR = 2
        const val DEFAULT_MINUTES_FROM_MIDNIGHT = DEFAULT_HOUR * 60
    }
}

// from: https://stackoverflow.com/a/8916605
fun minutesFromMidnightToHourlyTime(minutesFromMidnight: Int): CharSequence {
    val hours = TimeUnit.MINUTES.toHours(minutesFromMidnight.toLong())
    val remainMinutes = minutesFromMidnight - TimeUnit.HOURS.toMinutes(hours)
    return String.format("%02d:%02d", hours, remainMinutes)
}
