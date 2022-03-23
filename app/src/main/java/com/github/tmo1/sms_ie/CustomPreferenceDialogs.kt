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
import android.os.Bundle
import android.view.View
import android.widget.TimePicker
import androidx.preference.PreferenceDialogFragmentCompat

// from: https://old.black/2020/09/18/building-custom-timepicker-dialog-preference-in-android-kotlin/
class TimePreferenceDialog : PreferenceDialogFragmentCompat() {

    private lateinit var timePicker: TimePicker

    override fun onCreateDialogView(context: Context?): View {
        timePicker = TimePicker(context)
        return timePicker
    }

    override fun onBindDialogView(view: View?) {
        super.onBindDialogView(view)
        val minutesAfterMidnight = (preference as TimePickerPreference)
            .getPersistedMinutesFromMidnight()
        //timePicker.setIs24HourView(true)
        timePicker.hour = minutesAfterMidnight / 60
        timePicker.minute = minutesAfterMidnight % 60
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        // Save settings
        if(positiveResult) {
            val minutesAfterMidnight = (timePicker.hour * 60) + timePicker.minute
            (preference as TimePickerPreference).persistMinutesFromMidnight(minutesAfterMidnight)
            preference.summary = minutesFromMidnightToHourlyTime(minutesAfterMidnight)
        }
    }

    companion object {
        fun newInstance(key: String): TimePreferenceDialog {
            val fragment = TimePreferenceDialog()
            val bundle = Bundle(1)
            bundle.putString(ARG_KEY, key)
            fragment.arguments = bundle
            return fragment
        }
    }
}
