/*
 * SMS Import / Export: a simple Android app for importing and exporting SMS and MMS messages,
 * call logs, and contacts, from and to JSON / NDJSON files.
 *
 * Copyright (c) 2021-2023 Thomas More
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

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen

const val REQUEST_EXPORT_FOLDER = 4
const val EXPORT_DIR = "export_dir"
const val EXPORT_WORK_TAG = "export"

class SettingsActivity : AppCompatActivity() {

    //private lateinit var prefs: SharedPreferences
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, SettingsFragment())
                .commit()
        }
        //supportActionBar?.setDisplayHomeAsUpEnabled(true)
        //setSupportActionBar(findViewById(R.id.toolbar))
        //prefs = PreferenceManager.getDefaultSharedPreferences(this)
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        // https://stackoverflow.com/questions/70803830/updating-a-preference-summary-in-android-when-the-user-sets-it
        private val prefs by lazy { preferenceManager.sharedPreferences }
        private val targetDirPreference: Preference by lazy {
            findPreference<Preference>(EXPORT_DIR)
                ?: error("Missing export directory preference!")
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)
            /* The time picker is somehow calling 'android.widget.TimePicker.setHour', which was added in API level 23,
             and 'Intent.ACTION_OPEN_DOCUMENT_TREE' was added in API level 21, so we remove scheduled export functionality for API < 23
             https://stackoverflow.com/questions/32297765/android-timepicker-methods-being-stubs
             https://developer.android.com/reference/android/content/Intent#ACTION_OPEN_DOCUMENT_TREE
             */
            if (SDK_INT < 23) {
                // https://stackoverflow.com/a/45274037
                val preferenceScreen =
                    findPreference<PreferenceScreen>("main_preference_screen")
                val preferenceCategory =
                    findPreference<Preference>("scheduled_export_preference_category")
                if (preferenceCategory != null && preferenceScreen != null) {
                        preferenceScreen.removePreference(preferenceCategory)
                }
            }
            targetDirPreference.setOnPreferenceClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    //addCategory(Intent.CATEGORY_OPENABLE)
                    //putExtra(DocumentsContract.EXTRA_INITIAL_URI, "")
                }
                startActivityForResult(intent, REQUEST_EXPORT_FOLDER)
                true
            }
            updateExportDirPreferenceSummary()

            // see: https://stackoverflow.com/questions/26242581/call-method-after-changing-preferences-in-android
            // https://stackoverflow.com/questions/7020446/android-registeronsharedpreferencechangelistener-causes-crash-in-a-custom-view#7021068
            // https://stackoverflow.com/questions/66449883/kotlin-onsharedpreferencechangelistener
            val prefListener =
                SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == "schedule_export") {
                        context?.let { updateExportWork(it) }
                    }
                }
            prefs?.registerOnSharedPreferenceChangeListener(prefListener)
        }

        // from: https://old.black/2020/09/18/building-custom-timepicker-dialog-preference-in-android-kotlin/
        override fun onDisplayPreferenceDialog(preference: Preference) {
            when (preference) {
                is TimePickerPreference -> {
                    if (SDK_INT >= 23) {
                        val timePickerDialog = TimePreferenceDialog.newInstance(preference.key)
                        timePickerDialog.setTargetFragment(this, 0)
                        timePickerDialog.show(parentFragmentManager, "TimePickerDialog")
                    }
                }

                else -> {
                    super.onDisplayPreferenceDialog(preference)
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
            super.onActivityResult(requestCode, resultCode, intent)
            // from: https://stackoverflow.com/questions/34331956/trying-to-takepersistableuripermission-fails-for-custom-documentsprovider-via
            if (requestCode == REQUEST_EXPORT_FOLDER && resultCode == RESULT_OK && intent != null) {
                val treeUri = intent.data
                //Log.v(LOG_TAG, "Tree acquired: ${Uri.decode(treeUri.toString())}")
                if (treeUri != null) {
                    // TODO: we should probably call releasePersistableUriPermission on the current URI
                    context?.contentResolver?.takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    /*val documentTree = activity?.let { DocumentFile.fromTreeUri(it, treeUri) }
                    val file = documentTree?.createFile("text/plain", "sms-ie.test")
                    val fileUri = file?.uri
                    if (fileUri != null) {
//                  Log.v(LOG_TAG, "File acquired: $fileUri")
                        context?.contentResolver?.openOutputStream(fileUri).use { outputStream ->
                            BufferedWriter(OutputStreamWriter(outputStream)).use { writer ->
                                writer.write("It works!")
                            }
                        }
                    }*/
                }
                prefs?.edit {
                    putString(EXPORT_DIR, treeUri.toString())
                }
                updateExportDirPreferenceSummary()
                // for worker testing: https://developer.android.com/topic/libraries/architecture/workmanager/basics#samples
                /*val exportRequest: WorkRequest =
                    OneTimeWorkRequestBuilder<ExportWorker>()
                        .addTag(EXPORT_WORK_TAG)
                        .build()
                activity?.let {
                    WorkManager
                        .getInstance(it)
                        .enqueue(exportRequest)
                }*/
            } else {
                Log.e(
                    LOG_TAG,
                    "Tree acquisition failed:\trequestCode: $requestCode\tresultCode: $resultCode"
                )
            }
        }

        private fun updateExportDirPreferenceSummary() {
            findPreference<Preference>(EXPORT_DIR)?.summary =
                Uri.decode(prefs?.getString(EXPORT_DIR, ""))
        }
    }
}
