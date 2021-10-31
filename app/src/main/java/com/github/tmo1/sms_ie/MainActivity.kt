/*
 * SMS Import / Export: a simple Android app for importing and exporting SMS messages from and to JSON files.
 * Copyright (c) 2021 Thomas More
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

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Telephony
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.*
//import kotlin.system.measureNanoTime

//import android.util.Log

const val EXPORT = 1
const val IMPORT = 2

//const val LOG_TAG = "MainActivity"
//const val SMS_READ_REQUEST = 1
//const val CONTACTS_READ_REQUEST = 2
const val PERMISSIONS_REQUEST = 1

data class MessageTotal(var sms: Int, var mms: Int)

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // get necessary permissions on startup
        val necessaryPermissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            necessaryPermissions.add(Manifest.permission.READ_SMS)
        }
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            necessaryPermissions.add(Manifest.permission.READ_CONTACTS)
        }
        if (necessaryPermissions.any()) {
            requestPermissions(necessaryPermissions.toTypedArray(), PERMISSIONS_REQUEST)
        }

        // set up UI
        setContentView(R.layout.activity_main)
        val exportButton: Button = findViewById(R.id.export_button)
        val importButton: Button = findViewById(R.id.import_button)
        exportButton.setOnClickListener { exportFile() }
        importButton.setOnClickListener { importFile() }
    }

    private fun exportFile() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_SMS
            ) == PackageManager.PERMISSION_GRANTED
            && ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            val date = getCurrentDateTime()
            val dateInString = date.toString("yyyy-MM-dd")
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "messages-$dateInString.json")
            }
            startActivityForResult(intent, EXPORT)
        } else {
            Toast.makeText(
                this,
                getString(R.string.permissions_required_message),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun importFile() {
        if (Telephony.Sms.getDefaultSmsPackage(this) == this.packageName) {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = if (SDK_INT < 29) "*/*" else "application/json"
            }
            startActivityForResult(intent, IMPORT)
        } else {
            Toast.makeText(
                this@MainActivity,
                getString(R.string.default_sms_app_requirement_message),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onActivityResult(
        requestCode: Int, resultCode: Int, resultData: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, resultData)
        if (requestCode == EXPORT
            && resultCode == Activity.RESULT_OK
        ) {
            resultData?.data?.let {
                val statusReportText: TextView = findViewById(R.id.status_report)
                statusReportText.text = getString(R.string.begin_exporting_msg)
                statusReportText.visibility = View.VISIBLE
                GlobalScope.launch(Dispatchers.Main) {
                    val total = smsToJson(it)
                    statusReportText.text = getString(R.string.export_results, total.sms, total.mms)
                }
            }
        }
        if (requestCode == IMPORT
            && resultCode == Activity.RESULT_OK
        ) {
            resultData?.data?.let {
                val statusReportText: TextView = findViewById(R.id.status_report)
                statusReportText.text = getString(R.string.begin_importing_msg)
                statusReportText.visibility = View.VISIBLE
                GlobalScope.launch(Dispatchers.Main) {
                    val total = jsonToSms(it)
                    statusReportText.text = getString(R.string.import_results, total)
                }
            }
        }
    }

    private suspend fun smsToJson(file: Uri): MessageTotal {
        return withContext(Dispatchers.IO) {
            val json = JSONArray()
            var smsTotal = 0
            val smsCursor =
                contentResolver.query(Telephony.Sms.CONTENT_URI, null, null, null, null)
            // the following is adapted from https://www.gsrikar.com/2018/12/convert-content-provider-cursor-to-json.html
            //val startTime = measureNanoTime {
            smsCursor?.use { it ->
                if (it.moveToFirst()) {
                    do {
                        val sms = JSONObject()
                        it.columnNames.forEachIndexed { i, columnName ->
                            sms.put(columnName, it.getString(i))
                        }
                        val displayName = getDisplayName(sms.optString("address"))
                        if (displayName != null) sms.put("display_name", displayName)
                        json.put(sms)
                        smsTotal++
                        if (smsTotal == 5) break // for debugging only!
                    } while (it.moveToNext())
                }
                //Log.v(LOG_TAG, "$smsTotal SMSs exported")
            }
            var mmsTotal = 0
            val mmsCursor =
                contentResolver.query(Telephony.Mms.CONTENT_URI, null, null, null, null)
            // the following is adapted from https://www.gsrikar.com/2018/12/convert-content-provider-cursor-to-json.html
            mmsCursor?.use { it ->
                if (it.moveToFirst()) {
                    val msgIdIndex = it.getColumnIndexOrThrow("_id")
                    do {
                        val mms = JSONObject()
                        it.columnNames.forEachIndexed { i, columnName ->
                            mms.put(columnName, it.getString(i))
                        }
                        // the following is adapted from https://stackoverflow.com/questions/3012287/how-to-read-mms-data-in-android/6446831#6446831
                        val msgId = it.getString(msgIdIndex)
                        val addressCursor = contentResolver.query(
                            Uri.parse("content://mms/$msgId/addr"),
                            null,
                            "msg_id=$msgId",
                            null,
                            null
                        )
                        val recipientAddresses = JSONArray()
                        addressCursor?.use { it1 ->
                            if (it1.moveToFirst()) {
                                do {
                                    val address = JSONObject()
                                    it1.columnNames.forEachIndexed { i, columnName ->
                                        address.put(columnName, it1.getString(i))
                                    }
                                    val displayName = getDisplayName(address.optString("address"))
                                    if (displayName != null) address.put(
                                        "display_name",
                                        displayName
                                    )
                                    if (address.optString("type") == "137") mms.put(
                                        "sender_address",
                                        address
                                    )
                                    else recipientAddresses.put(address)
                                } while (it1.moveToNext())
                            }
                        }
                        mms.put("recipient_addresses", recipientAddresses)
                        json.put(mms)
                        mmsTotal++
                        if (mmsTotal == 5) break // for debugging only!
                    } while (it.moveToNext())
                }
            }
            //Log.v(LOG_TAG, "$mmsTotal MMSs exported")
            // Android Studio flags all the IO calls here and in jsonToSms() as "Inappropriate blocking method call",
            // despite the fact that they're wrapped with withContext(Dispatchers.IO) - I don't understand why
            // see https://stackoverflow.com/questions/58680028/how-to-make-inappropriate-blocking-method-call-appropriate
            contentResolver.openOutputStream(file).use { outputStream ->
                BufferedWriter(OutputStreamWriter(outputStream)).use { writer ->
                    writer.write(
                        json.toString(2)
                    )
                }
            }
            MessageTotal(smsTotal, mmsTotal)
        }
    }

    private fun getDisplayName(address: String): String? {
        // look up display name by phone number
        if (address == "") return null
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(address)
        )
        val nameCursor = contentResolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
            null,
            null,
            null
        )
        val displayName: String?
        nameCursor.use {
            displayName = if (it != null && it.moveToFirst()) it.getString(
                it.getColumnIndexOrThrow(
                    ContactsContract.PhoneLookup.DISPLAY_NAME
                )
            ) else null
        }
        return displayName
    }

    private suspend fun jsonToSms(uri: Uri): Int {
        return withContext(Dispatchers.IO) {
            var total = 0
            val stringBuilder = StringBuilder()
            uri.let {
                contentResolver.openInputStream(it).use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream)).use { reader ->
                        var line: String? = reader.readLine()
                        while (line != null) {
                            stringBuilder.append(line)
                            line = reader.readLine()
                        }
                    }
                }
            }
            //Log.v(LOG_TAG, "Message: " + stringBuilder.toString())
            try {
                val messages = JSONArray(stringBuilder.toString())
                for (i in 0 until messages.length()) {
                    val message = messages[i]
                    if (message is JSONObject && !message.has("m_type")) { // we don't import MMS yet
                        val values = ContentValues()
                        for (key in listOf(
                            Telephony.Sms.ADDRESS,
                            Telephony.Sms.BODY,
                            Telephony.Sms.DATE,
                            Telephony.Sms.DATE_SENT,
                            Telephony.Sms.TYPE
                        )) {
                            values.put(key, message.optString(key))
                        }
                        val insertUri =
                            contentResolver.insert(Telephony.Sms.CONTENT_URI, values)
                        if (insertUri == null) {
                            //Log.v(LOG_TAG, "Insert failed!")
                        } else total++
                    } /*else {
                        Log.v(LOG_TAG, "Found non-JSONObject!")
                    }*/
                }
            } catch (e: JSONException) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.not_jsonarray_message),
                    Toast.LENGTH_LONG
                ).show()
            }
            total
        }
    }
}

// From https://stackoverflow.com/a/51394768
fun Date.toString(format: String, locale: Locale = Locale.getDefault()): String {
    val formatter = SimpleDateFormat(format, locale)
    return formatter.format(this)
}

fun getCurrentDateTime(): Date {
    return Calendar.getInstance().time
}
