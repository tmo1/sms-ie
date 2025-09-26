/*
 * SMS Import / Export: a simple Android app for importing and exporting SMS and MMS messages,
 * call logs, and contacts, from and to JSON / NDJSON files.
 *
 * Copyright (c) 2025 Thomas More
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

import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import androidx.preference.PreferenceManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

const val SMS = 0
const val MMS = 1

class MessageFiltersActivity : AppCompatActivity() {
    val list = arrayListOf<MessageFilter>()
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // https://developer.android.com/guide/fragments/communicate#fragment-result
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val prefsEditor = prefs.edit()
        getMessageFilters(prefs, list)
        // set up UI
        setContentView(R.layout.activity_message_filters)
        val tapFilterToEditOrDelete: TextView = findViewById(R.id.tap_filter_to_edit_or_delete)
        tapFilterToEditOrDelete.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        // https://developer.android.com/develop/ui/views/layout/declaring-layout#AdapterViews
        val messageFiltersAdapter =
            ArrayAdapter<MessageFilter>(this, android.R.layout.simple_list_item_1, list)
        val messageFiltersList: ListView = findViewById(R.id.message_filters_list)
        messageFiltersList.adapter = messageFiltersAdapter
        fun updateMessageFilters() {
            messageFiltersAdapter.notifyDataSetChanged()
            prefsEditor.putString("message_filters", Json.encodeToString(list)).apply()
            tapFilterToEditOrDelete.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        }
        supportFragmentManager.setFragmentResultListener("saveFilter", this) { requestKey, bundle ->
            val result = bundle.getString("filter")
            if (result != null) {
                val filter = Json.decodeFromString<MessageFilter>(result)
                val position = bundle.getInt("position")
                if (position != -1) {
                    list[position] = filter
                } else {
                    list.add(filter)
                }
                updateMessageFilters()
            }
        }
        supportFragmentManager.setFragmentResultListener(
            "deleteFilter", this
        ) { requestKey, bundle ->
            val result = bundle.getInt("filterPosition")
            list.removeAt(result)
            updateMessageFilters()
        }
        messageFiltersList.onItemClickListener =
            AdapterView.OnItemClickListener { parent, view, position, id ->
                val newFragment = AddEditDeleteMessageFilterFragment.newInstance(
                    position, Json.encodeToString(list[position])
                )
                newFragment.show(supportFragmentManager, "edit_message_filter")
                true
            }
        val addMessageFiltersButton: Button = findViewById(R.id.add_message_filter_button)
        addMessageFiltersButton.setOnClickListener {
            val newFragment = AddEditDeleteMessageFilterFragment()
            newFragment.show(supportFragmentManager, "add_message_filter")
        }
    }
}

// https://developer.android.com/develop/ui/views/components/dialogs#CustomLayout
class AddEditDeleteMessageFilterFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return activity?.let {
            val position = arguments?.getInt("position")
            val builder = AlertDialog.Builder(it)
            // Get the layout inflater.
            val inflater = requireActivity().layoutInflater
            val view = inflater.inflate(R.layout.add_message_filter, null)
            // Inflate and set the layout for the dialog.
            // Pass null as the parent view because it's going in the dialog layout.
            val messageColumnsSpinner: Spinner = view.findViewById(R.id.message_column_name)
            val operatorSpinner: Spinner = view.findViewById(R.id.operator)
            val messageColumnValue: EditText = view.findViewById(R.id.message_column_value)
            val active: SwitchCompat = view.findViewById(R.id.filter_active)
            builder.setView(view).setPositiveButton(
                "Save"
            ) { dialog, id ->
                val messageFilter = MessageFilter(
                    messageColumnsSpinner.selectedItem.toString(),
                    operatorSpinner.selectedItem.toString(),
                    messageColumnValue.text.toString(),
                    active.isChecked
                )
                // https://developer.android.com/guide/fragments/communicate#fragment-result
                setFragmentResult(
                    "saveFilter", bundleOf(
                        "filter" to Json.encodeToString<MessageFilter>(messageFilter),
                        "position" to (position ?: -1)
                    )
                )
            }.setNegativeButton(
                "Cancel"
            ) { dialog, id ->
                getDialog()?.cancel()
            }
            if (position != null) {
                builder.setNeutralButton("Delete") { dialog, id ->
                    // https://developer.android.com/guide/fragments/communicate#fragment-result
                    setFragmentResult(
                        "deleteFilter", bundleOf("filterPosition" to position)
                    )
                }
            }
            // https://developer.android.com/develop/ui/views/components/spinner
            val messageColumnsSpinnerAdapter = ArrayAdapter(
                // https://stackoverflow.com/a/60486970/17994624
                requireContext(), android.R.layout.simple_spinner_item, listOf(
                    "date", "date_sent", "sms.address", "sms.type", "sms.body", "mms.msg_box"
                )
            )
            messageColumnsSpinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            messageColumnsSpinner.adapter = messageColumnsSpinnerAdapter
            val operatorSpinnerAdapter = ArrayAdapter(
                // https://stackoverflow.com/a/60486970/17994624
                requireContext(),
                android.R.layout.simple_spinner_item,
                listOf("==", "<", "<=", ">", ">=", "!=", "LIKE", "BETWEEN", "IN")
            )
            operatorSpinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            operatorSpinner.adapter = operatorSpinnerAdapter
            if (position != null) {
                arguments?.getString("messageFilter")?.let { mf ->
                    val messageFilter = Json.decodeFromString<MessageFilter>(mf)
                    // https://www.w3docs.com/snippets/java/how-to-set-selected-item-of-spinner-by-value-not-by-position.html
                    messageColumnsSpinner.setSelection(
                        messageColumnsSpinnerAdapter.getPosition(
                            messageFilter.column
                        )
                    )
                    operatorSpinner.setSelection(operatorSpinnerAdapter.getPosition(messageFilter.operator))
                    messageColumnValue.setText(messageFilter.value)
                    active.setChecked(messageFilter.active)
                }
            }
            builder.create()
        } ?: throw IllegalStateException("Activity cannot be null")
    }

    companion object {
        // @JvmStatic
        // https://stackoverflow.com/questions/48780003/why-and-when-to-use-jvmstatic-with-companion-objects
        fun newInstance(position: Int, messageFilter: String) =
            AddEditDeleteMessageFilterFragment().apply {
                arguments = Bundle().apply {
                    putInt("position", position)
                    putString("messageFilter", messageFilter)
                }
            }
    }
}

@Serializable
data class MessageFilter(
    val column: String, val operator: String, val value: String, val active: Boolean
) {
    override fun toString(): String {
        return "$column $operator \"$value\" [${if (active) "Active" else "Inactive"}]"
    }
}

private fun getMessageFilters(prefs: SharedPreferences, list: ArrayList<MessageFilter>) {
    val messageFilters = prefs.getString("message_filters", "[]")
    if (messageFilters != null) {
        // https://www.baeldung.com/kotlin/kotlinx-serialization
        try {
            list.addAll(Json.decodeFromString<ArrayList<MessageFilter>>(messageFilters))
        } catch (e: SerializationException) {
            Log.e(LOG_TAG, "Deserialization error\n$e")
        }
    }
}

fun messageSelection(appContext: Context, messageType: Int): String? {
    val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
    val selection = if (prefs.getBoolean("message_filtering", false)) {
        val list = arrayListOf<MessageFilter>()
        getMessageFilters(prefs, list)
        Log.d(LOG_TAG, list.toString())
        list.filter {
            it.active && ((messageType == SMS && !it.column.startsWith("mms.")) || (messageType == MMS && !it.column.startsWith(
                "sms."
            )))
        }.joinToString(separator = " AND ") {
            var value = it.value
            if (it.column == "date" || it.column == "date_sent") {
                if (messageType == SMS && value.length <= 11) value = value + "000"
                else if (messageType == MMS && value.length > 11) value = value.dropLast(3)
            }
            "${it.column.substringAfter('.')} ${it.operator} $value"
        }
    } else null
    Log.d(LOG_TAG, "${if (messageType == SMS) "SMS" else "MMS"} selection: $selection")
    return selection
}