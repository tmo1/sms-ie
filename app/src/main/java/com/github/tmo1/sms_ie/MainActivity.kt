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
import android.provider.Telephony.Threads.getOrCreateThreadId
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
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
import java.util.concurrent.TimeUnit
import kotlin.system.measureNanoTime

const val EXPORT = 1
const val IMPORT = 2
const val PERMISSIONS_REQUEST = 1
const val LOG_TAG = "DEBUG"
const val MAX_MESSAGES = -1
const val SMS = true
const val MMS = true

data class MessageTotal(var sms: Int, var mms: Int)

class MainActivity : AppCompatActivity() {

    private var includeBinaryData: Boolean = true

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
                type =
                    if (SDK_INT < 29) "*/*" else "application/json" //see https://github.com/tmo1/sms-ie/issues/3#issuecomment-900518890
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
                    statusReportText.text =
                        getString(R.string.export_results, total.sms, total.mms)
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
                    statusReportText.text = getString(R.string.import_results, total.sms, total.mms)
                }
            }
        }
    }

    private suspend fun smsToJson(file: Uri): MessageTotal {
        return withContext(Dispatchers.IO) {
            val json = JSONArray()
            var smsTotal = 0
            var mmsTotal = 0
            val time = measureNanoTime {
                val displayNames = mutableMapOf<String, String?>()
                // the following is adapted from https://www.gsrikar.com/2018/12/convert-content-provider-cursor-to-json.html
                val smsCursor =
                    contentResolver.query(Telephony.Sms.CONTENT_URI, null, null, null, null)
                smsCursor?.use { it ->
                    if (it.moveToFirst()) {
                        do {
                            val sms = JSONObject()
                            it.columnNames.forEachIndexed { i, columnName ->
                                sms.put(columnName, it.getString(i))
                            }
                            val displayName =
                                lookupDisplayName(displayNames, sms.optString("address"))
                            if (displayName != null) sms.put("display_name", displayName)
                            json.put(sms)
                            smsTotal++
                            if (BuildConfig.DEBUG && smsTotal == MAX_MESSAGES) break
                        } while (it.moveToNext())
                    }
                }
                val mmsCursor =
                    contentResolver.query(Telephony.Mms.CONTENT_URI, null, null, null, null)
                mmsCursor?.use { it ->
                    if (it.moveToFirst()) {
                        val msgIdIndex = it.getColumnIndexOrThrow("_id")
                        do {
                            val mms = JSONObject()
                            it.columnNames.forEachIndexed { i, columnName ->
                                mms.put(columnName, it.getString(i))
                            }
//                        the following is adapted from https://stackoverflow.com/questions/3012287/how-to-read-mms-data-in-android/6446831#6446831
                            val msgId = it.getString(msgIdIndex)
                            val recipientAddresses = JSONArray()
                            val addressCursor = contentResolver.query(
//                                Uri.parse("content://mms/addr"),
                                Uri.parse("content://mms/$msgId/addr"),
                                null,
//                                "msg_id=?",
//                                arrayOf(msgId),
                                null,
                                null,
                                null
                            )
                            addressCursor?.use { it1 ->
                                if (it1.moveToFirst()) {
                                    do {
                                        val address = JSONObject()
                                        it1.columnNames.forEachIndexed { i, columnName ->
                                            address.put(columnName, it1.getString(i))
                                        }
                                        val displayName =
                                            lookupDisplayName(
                                                displayNames,
                                                address.optString("address")
                                            )
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
                            val parts = JSONArray()
                            val partCursor = contentResolver.query(
                                Uri.parse("content://mms/part"),
//                                Uri.parse("content://mms/$msgId/part"),
                                null,
                                "mid=?",
                                arrayOf(msgId),
                                "seq ASC"
                            )
                            partCursor?.use { it1 ->
                                if (it1.moveToFirst()) {
                                    val partIdIndex = it1.getColumnIndexOrThrow("_id")
                                    val dataIndex = it1.getColumnIndexOrThrow("_data")
                                    do {
                                        val part = JSONObject()
                                        it1.columnNames.forEachIndexed { i, columnName ->
                                            part.put(columnName, it1.getString(i))
                                        }
                                        if (includeBinaryData && it1.getString(dataIndex) != null) {
                                            val inputStream = contentResolver.openInputStream(
                                                Uri.parse(
                                                    "content://mms/part/" + it1.getString(
                                                        partIdIndex
                                                    )
                                                )
                                            )
                                            val data = inputStream.use {
                                                Base64.encodeToString(
                                                    it?.readBytes(),
                                                    Base64.NO_WRAP // Without NO_WRAP, we end up with corrupted files upon decoding - see https://stackoverflow.com/questions/16091883/sending-base64-encoded-image-results-in-a-corrupt-image
                                                )
                                            }
                                            part.put("binary_data", data)
                                        }
                                        parts.put(part)
                                    } while (it1.moveToNext())
                                }
                            }
                            mms.put("parts", parts)
                            json.put(mms)
                            mmsTotal++
                            if (BuildConfig.DEBUG && mmsTotal == MAX_MESSAGES) break
                        } while (it.moveToNext())
                    }
                }
            }
            if (BuildConfig.DEBUG) {
                val seconds = TimeUnit.SECONDS.convert(time, TimeUnit.NANOSECONDS).toString()
                Log.v(LOG_TAG, "Elapsed time: $seconds seconds ($time nanoseconds)")
            }
            /*Android Studio flags all the IO calls here and in jsonToSms() as "Inappropriate blocking method call",
            despite the fact that they're wrapped with withContext(Dispatchers.IO) - I don't understand why
            see https://stackoverflow.com/questions/58680028/how-to-make-inappropriate-blocking-method-call-appropriate*/
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

    private fun lookupDisplayName(
        displayNames: MutableMap<String, String?>,
        address: String
    ): String? {
//        look up display name by phone number
        if (address == "") return null
        if (displayNames[address] != null) return displayNames[address]
        val displayName: String?
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
        nameCursor.use {
            displayName = if (it != null && it.moveToFirst())
                it.getString(
                    it.getColumnIndexOrThrow(
                        ContactsContract.PhoneLookup.DISPLAY_NAME
                    )
                )
            else null
        }
        displayNames[address] = displayName
        return displayName
    }

    private suspend fun jsonToSms(uri: Uri): MessageTotal {
        return withContext(Dispatchers.IO) {
            var smsTotal = 0
            var mmsTotal = 0
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
            try {
                val messages = JSONArray(stringBuilder.toString())
                for (i in 0 until messages.length()) {
                    val message = messages[i]
                    if (message is JSONObject) {
                        if (!message.has("m_type")) { // it's SMS
                            if (BuildConfig.DEBUG && (!SMS || smsTotal == MAX_MESSAGES)) continue
                            val smsMetadata = ContentValues()
                            for (key in listOf(
                                Telephony.Sms.ADDRESS,
                                Telephony.Sms.BODY,
                                Telephony.Sms.DATE,
                                Telephony.Sms.DATE_SENT,
                                Telephony.Sms.TYPE
                            )) {
                                smsMetadata.put(key, message.optString(key))
                            }
                            val insertUri =
                                contentResolver.insert(Telephony.Sms.CONTENT_URI, smsMetadata)
                            if (insertUri == null) {
                                Log.v(LOG_TAG, "SMS insert failed!")
                            } else smsTotal++
                        } else { //it's MMS
                            /*the following is adapted from here https://stackoverflow.com/questions/25584442/android-save-or-insert-mms-in-content-provider-programmatically
                            here https://stackoverflow.com/questions/11673543/inserting-sent-mms-into-sent-box
                            and here https://coderedirect.com/questions/310606/sending-mms-in-android-4-4
                            MMS insertion seems to work fine without the dummy SMS, so we omit it*/
                            if (BuildConfig.DEBUG && (!MMS || mmsTotal == MAX_MESSAGES)) continue
                            val addresses = mutableSetOf<JSONObject>()
                            val senderAddress = message.optJSONObject("sender_address")
                            if (senderAddress != null) addresses.add(senderAddress)
                            val recipientAddresses = message.optJSONArray("recipient_addresses")
                            if (recipientAddresses != null) {
                                for (j in 0 until recipientAddresses.length()) {
                                    addresses.add(recipientAddresses[j] as JSONObject)
                                }
                            }
                            val threadId = getOrCreateThreadId(
                                this@MainActivity,
                                addresses.map { it.optString("address") }.toSet()
                            )
                            val mmsMetadata = ContentValues()
                            mmsMetadata.put("thread_id", threadId)
                            val keys = message.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                if (key !in setOf(
                                        "sender_address",
                                        "recipient_addresses",
                                        "parts",
                                        "thread_id",
                                        "_id"
                                    )
                                ) mmsMetadata.put(
                                    key,
                                    message.optString(key)
                                )
                            }
                            val insertUri =
                                contentResolver.insert(Telephony.Mms.CONTENT_URI, mmsMetadata)
                            if (insertUri == null) {
                                Log.v(LOG_TAG, "MMS insert failed!")
                            } else {
                                mmsTotal++
//                                Log.v(LOG_TAG, "MMS insert succeeded!")
                                val messageId = insertUri.lastPathSegment
                                val addressUri = Uri.parse("content://mms/$messageId/addr")
                                addresses.forEach { address ->
                                    val addressValues = ContentValues()
                                    addressValues.put(Telephony.Mms.Addr.MSG_ID, messageId)
                                    for (key in listOf(
                                        Telephony.Mms.Addr.ADDRESS,
                                        Telephony.Mms.Addr.CHARSET,
                                        Telephony.Mms.Addr.TYPE
                                    )) {
                                        addressValues.put(key, address.optString(key))
                                    }
                                    val insertAddressUri =
                                        contentResolver.insert(addressUri, addressValues)
                                    if (insertAddressUri == null) {
                                        Log.v(LOG_TAG, "MMS address insert failed!")
                                    } /*else {
                                        Log.v(LOG_TAG, "MMS address insert succeeded. Address metadata:" + address.toString())
                                    }*/
                                }
                                val partUri = Uri.parse("content://mms/$messageId/part")
                                val parts = message.optJSONArray("parts")
                                if (parts != null) {
                                    for (j in 0 until parts.length()) {
                                        val part = parts[j] as JSONObject
                                        val partMetadata = ContentValues()
                                        val partKeys = part.keys()
                                        while (partKeys.hasNext()) {
                                            val key = partKeys.next()
                                            if (key !in setOf(
                                                    Telephony.Mms.Part.MSG_ID,
                                                    Telephony.Mms.Part._ID,
                                                    Telephony.Mms.Part._DATA,
                                                    Telephony.Mms.Part._COUNT,
                                                    "binary_data"
                                                )
                                            ) partMetadata.put(
                                                key,
                                                part.optString(key)
                                            )
                                        }
                                        partMetadata.put(Telephony.Mms.Part.MSG_ID, messageId)
                                        val insertPartUri =
                                            contentResolver.insert(partUri, partMetadata)
                                        if (insertPartUri == null) {
                                            Log.v(
                                                LOG_TAG,
                                                "MMS part insert failed! Part metadata:$part"
                                            )
                                        } else {
                                            if (part.has("binary_data")) {
                                                val binaryData = Base64.decode(
                                                    part.optString("binary_data"),
                                                    Base64.NO_WRAP
                                                )
                                                val os =
                                                    contentResolver.openOutputStream(
                                                        insertPartUri
                                                    )
                                                if (os != null) {
                                                    os.use {
                                                        os.write(binaryData)
                                                    }
                                                } else {
                                                    Log.v(
                                                        LOG_TAG,
                                                        "Failed to open OutputStream!"
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
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
            MessageTotal(smsTotal, mmsTotal)
        }
    }

    fun onCheckBoxClicked(view: View) {
        if (view is CheckBox) {
            includeBinaryData = view.isChecked
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
