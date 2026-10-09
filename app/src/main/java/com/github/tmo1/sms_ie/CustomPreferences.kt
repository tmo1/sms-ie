/*
 * SMS Import / Export: a simple Android app for importing and exporting SMS and MMS messages,
 * call logs, contacts, and blocked numbers from and to JSON / NDJSON files.
 *
 * Copyright (c) 2021-2022,2026 Thomas More
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
import android.text.format.DateUtils
import android.util.AttributeSet
import androidx.preference.DialogPreference
import java.util.Formatter
import java.util.Locale

// from: https://old.black/2020/09/18/building-custom-timepicker-dialog-preference-in-android-kotlin/
class TimePickerPreference(context: Context, attrs: AttributeSet?) :
    DialogPreference(context, attrs) {

    // Get saved preference value (in minutes from midnight, so 1 AM is represented as 1*60 here
    fun getPersistedMinutesAfterMidnight(): Int {
        return super.getPersistedInt(DEFAULT_MINUTES_AFTER_MIDNIGHT)
    }

    // Save preference
    fun persistMinutesAfterMidnight(minutesAfterMidnight: Int) {
        super.persistInt(minutesAfterMidnight)
        notifyChanged()
        scheduleAutomaticExport(context, true)
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        super.onSetInitialValue(defaultValue)
        summary = minutesAfterMidnightToHourlyTime(context, getPersistedMinutesAfterMidnight())
    }

    // Mostly for default values
    companion object {
        // default is 2:00 a.m.
        private const val DEFAULT_HOUR = 2
        const val DEFAULT_MINUTES_AFTER_MIDNIGHT = DEFAULT_HOUR * 60
    }
}

fun minutesAfterMidnightToHourlyTime(context: Context, minutesAfterMidnight: Int): CharSequence {
    // https://stackoverflow.com/questions/38594746/android-dateutils-formatdatetime-wrong-timezone/38596172
    val stringBuilder = StringBuilder(50)
    val formatter = Formatter(stringBuilder, Locale.getDefault())
    val millisAfterMidnight = minutesAfterMidnight.toLong() * 60000
    return DateUtils.formatDateRange(
        context,
        formatter,
        millisAfterMidnight,
        millisAfterMidnight,
        DateUtils.FORMAT_SHOW_TIME,
        "UTC"
    ).toString()
}
