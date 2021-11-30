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
import android.util.JsonReader
import android.util.JsonWriter
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

const val EXPORT = 1
const val IMPORT = 2
const val PERMISSIONS_REQUEST = 1
const val LOG_TAG = "DEBUG"
const val MAX_MESSAGES = -1
const val SMS = true
const val MMS = true

// PduHeaders are referenced here https://developer.android.com/reference/android/provider/Telephony.Mms.Addr#TYPE
// and defined here https://android.googlesource.com/platform/frameworks/opt/mms/+/4bfcd8501f09763c10255442c2b48fad0c796baa/src/java/com/google/android/mms/pdu/PduHeaders.java
// but are apparently unavailable in a public class
const val PDU_HEADERS_FROM = "137"

data class MessageTotal(var sms: Int = 0, var mms: Int = 0)

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
        var total: MessageTotal
        val startTime = System.nanoTime()
        if (requestCode == EXPORT
            && resultCode == Activity.RESULT_OK
        ) {
            resultData?.data?.let {
                val statusReportText: TextView = findViewById(R.id.status_report)
                statusReportText.text = getString(R.string.begin_exporting_msg)
                statusReportText.visibility = View.VISIBLE
                CoroutineScope(Dispatchers.Main).launch {
                    total = exportJSON(it)
                    statusReportText.text = getString(R.string.export_results, total.sms, total.mms)
                    logElapsedTime(startTime)
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
                CoroutineScope(Dispatchers.Main).launch {
                    total = importJson(it)
                    statusReportText.text = getString(R.string.import_results, total.sms, total.mms)
                    logElapsedTime(startTime)
                }
            }
        }
    }

    private fun logElapsedTime(since: Long) {
        if (BuildConfig.DEBUG) {
            val elapsedTime = System.nanoTime() - since
            val seconds =
                TimeUnit.SECONDS.convert(elapsedTime, TimeUnit.NANOSECONDS).toString()
            Log.v(LOG_TAG, "Elapsed time: $seconds seconds ($elapsedTime nanoseconds)")
        }
    }

    private suspend fun exportJSON(file: Uri): MessageTotal {
        return withContext(Dispatchers.IO) {
            val totals = MessageTotal()
            val displayNames = mutableMapOf<String, String?>()
            // the following is adapted from https://www.gsrikar.com/2018/12/convert-content-provider-cursor-to-json.html
            contentResolver.openOutputStream(file).use { outputStream ->
                BufferedWriter(OutputStreamWriter(outputStream)).use { writer ->
                    val jsonWriter = JsonWriter(writer)
                    jsonWriter.setIndent("  ")
                    jsonWriter.beginArray()
                    if (!BuildConfig.DEBUG || SMS) totals.sms = smsToJSON(jsonWriter, displayNames)
                    if (!BuildConfig.DEBUG || MMS) totals.mms = mmsToJSON(jsonWriter, displayNames)
                    jsonWriter.endArray()
                }
            }
            totals
        }
    }

    private fun smsToJSON(
        jsonWriter: JsonWriter,
        displayNames: MutableMap<String, String?>
    ): Int {
        var total = 0
        val smsCursor =
            contentResolver.query(Telephony.Sms.CONTENT_URI, null, null, null, null)
        smsCursor?.use { it ->
            if (it.moveToFirst()) {
                val addressIndex = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                do {
                    jsonWriter.beginObject()
                    it.columnNames.forEachIndexed { i, columnName ->
                        val value = it.getString(i)
                        if (value != null) jsonWriter.name(columnName).value(value)
                    }
                    val displayName =
                        lookupDisplayName(displayNames, it.getString(addressIndex))
                    if (displayName != null) jsonWriter.name("display_name").value(displayName)
                    jsonWriter.endObject()
                    total++
                    if (BuildConfig.DEBUG && total == MAX_MESSAGES) break
                } while (it.moveToNext())
            }
        }
        return total
    }

    private fun mmsToJSON(
        jsonWriter: JsonWriter,
        displayNames: MutableMap<String, String?>
    ): Int {
        var total = 0
        val mmsCursor =
            contentResolver.query(Telephony.Mms.CONTENT_URI, null, null, null, null)
        mmsCursor?.use { it ->
            if (it.moveToFirst()) {
                val msgIdIndex = it.getColumnIndexOrThrow("_id")
                // write MMS metadata
                do {
                    jsonWriter.beginObject()
                    it.columnNames.forEachIndexed { i, columnName ->
                        val value = it.getString(i)
                        if (value != null) jsonWriter.name(columnName).value(value)
                    }
//                        the following is adapted from https://stackoverflow.com/questions/3012287/how-to-read-mms-data-in-android/6446831#6446831
                    val msgId = it.getString(msgIdIndex)
                    val addressCursor = contentResolver.query(
//                                Uri.parse("content://mms/addr"),
                        Uri.parse("content://mms/$msgId/addr"),
                        null,
                        null,
                        null,
                        null
                    )
                    addressCursor?.use { it1 ->
                        val addressTypeIndex =
                            addressCursor.getColumnIndexOrThrow(Telephony.Mms.Addr.TYPE)
                        val addressIndex =
                            addressCursor.getColumnIndexOrThrow(Telephony.Mms.Addr.ADDRESS)
                        // write sender address object
                        if (it1.moveToFirst()) {
                            do {
                                if (addressTypeIndex.let { it2 -> it1.getString(it2) } == PDU_HEADERS_FROM) {
                                    jsonWriter.name("sender_address")
                                    jsonWriter.beginObject()
                                    it1.columnNames.forEachIndexed { i, columnName ->
                                        val value = it1.getString(i)
                                        if (value != null) jsonWriter.name(columnName).value(value)
                                    }
                                    val displayName =
                                        lookupDisplayName(displayNames, it1.getString(addressIndex))
                                    if (displayName != null) jsonWriter.name("display_name")
                                        .value(displayName)
                                    jsonWriter.endObject()
                                    break
                                }
                            } while (it1.moveToNext())
                        }
                        // write array of recipient address objects
                        if (it1.moveToFirst()) {
                            jsonWriter.name("recipient_addresses")
                            jsonWriter.beginArray()
                            do {
                                if (addressTypeIndex.let { it2 -> it1.getString(it2) } != PDU_HEADERS_FROM) {
                                    jsonWriter.beginObject()
                                    it1.columnNames.forEachIndexed { i, columnName ->
                                        val value = it1.getString(i)
                                        if (value != null) jsonWriter.name(columnName).value(value)
                                    }
                                    val displayName =
                                        lookupDisplayName(displayNames, it1.getString(addressIndex))
                                    if (displayName != null) jsonWriter.name("display_name")
                                        .value(displayName)
                                    jsonWriter.endObject()
                                }
                            } while (it1.moveToNext())
                            jsonWriter.endArray()
                        }
                    }
                    val partCursor = contentResolver.query(
                        Uri.parse("content://mms/part"),
//                      Uri.parse("content://mms/$msgId/part"),
                        null,
                        "mid=?",
                        arrayOf(msgId),
                        "seq ASC"
                    )
                    // write array of MMS parts
                    partCursor?.use { it1 ->
                        if (it1.moveToFirst()) {
                            jsonWriter.name("parts")
                            jsonWriter.beginArray()
                            val partIdIndex = it1.getColumnIndexOrThrow("_id")
                            val dataIndex = it1.getColumnIndexOrThrow("_data")
                            do {
                                jsonWriter.beginObject()
                                it1.columnNames.forEachIndexed { i, columnName ->
                                    val value = it1.getString(i)
                                    if (value != null) jsonWriter.name(columnName).value(value)
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
                                    jsonWriter.name("binary_data").value(data)
                                }
                                jsonWriter.endObject()
                            } while (it1.moveToNext())
                            jsonWriter.endArray()
                        }
                    }
                    jsonWriter.endObject()
                    total++
                    if (BuildConfig.DEBUG && total == MAX_MESSAGES) break
                } while (it.moveToNext())
            }
        }
        return total
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

    private suspend fun importJson(uri: Uri): MessageTotal {
        return withContext(Dispatchers.IO) {
            val totals = MessageTotal()
            //val stringBuilder = StringBuilder()
            uri.let {
                contentResolver.openInputStream(it).use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream)).use { reader ->
                        val jsonReader = JsonReader(reader)
                        val messageMetadata = ContentValues()
                        val addresses = mutableSetOf<ContentValues>()
                        val parts = mutableListOf<ContentValues>()
                        val binaryData = mutableListOf<ByteArray?>()
                        jsonReader.beginArray()
                        while (jsonReader.hasNext()) {
                            jsonReader.beginObject()
                            messageMetadata.clear()
                            addresses.clear()
                            parts.clear()
                            binaryData.clear()
                            var name: String?
                            var value: String?
                            while (jsonReader.hasNext()) {
                                name = jsonReader.nextName()
                                when (name) {
                                    "sender_address" -> {
                                        jsonReader.beginObject()
                                        val address = ContentValues()
                                        while (jsonReader.hasNext()) {
                                            val name1 = jsonReader.nextName()
                                            val value1 = jsonReader.nextString()
                                            if (name1 !in setOf(
                                                    Telephony.Mms.Addr._ID,
                                                    Telephony.Mms.Addr._COUNT,
                                                    Telephony.Mms.Addr.MSG_ID,
                                                    "display_name"
                                                )
                                            ) address.put(name1, value1)
                                        }
                                        addresses.add(address)
                                        jsonReader.endObject()
                                    }
                                    "recipient_addresses" -> {
                                        jsonReader.beginArray()
                                        while (jsonReader.hasNext()) {
                                            jsonReader.beginObject()
                                            val address = ContentValues()
                                            while (jsonReader.hasNext()) {
                                                val name1 = jsonReader.nextName()
                                                val value1 = jsonReader.nextString()
                                                if (name1 !in setOf(
                                                        Telephony.Mms.Addr._ID,
                                                        Telephony.Mms.Addr._COUNT,
                                                        Telephony.Mms.Addr.MSG_ID,
                                                        "display_name"
                                                    )
                                                ) address.put(name1, value1)
                                            }
                                            addresses.add(address)
                                            jsonReader.endObject()
                                        }
                                        jsonReader.endArray()
                                    }
                                    "parts" -> {
                                        jsonReader.beginArray()
                                        while (jsonReader.hasNext()) {
                                            jsonReader.beginObject()
                                            val part = ContentValues()
                                            var hasBinaryData = false
                                            while (jsonReader.hasNext()) {
                                                val name1 = jsonReader.nextName()
                                                val value1 = jsonReader.nextString()
                                                if (name1 !in setOf(
                                                        Telephony.Mms.Part.MSG_ID,
                                                        Telephony.Mms.Part._ID,
                                                        Telephony.Mms.Part._DATA,
                                                        Telephony.Mms.Part._COUNT,
                                                        "binary_data"
                                                    )
                                                ) part.put(name1, value1)
                                                if (name1 == "binary_data") {
                                                    binaryData.add(
                                                        Base64.decode(
                                                            value1,
                                                            Base64.NO_WRAP
                                                        )
                                                    )
                                                    hasBinaryData = true
                                                }
                                            }
                                            if (!hasBinaryData) binaryData.add(null)
                                            parts.add(part)
                                            jsonReader.endObject()
                                        }
                                        jsonReader.endArray()
                                    }
                                    else -> {
                                        value = jsonReader.nextString()
                                        if (name !in setOf(
                                                "_id",
                                                "thread_id",
                                                "display_name"
                                            )
                                        ) messageMetadata.put(name, value)
                                    }
                                }
                            }
                            jsonReader.endObject()
                            if (!messageMetadata.containsKey("m_type")) { // it's SMS
                                if (BuildConfig.DEBUG && (!SMS || totals.sms == MAX_MESSAGES)) continue
                                val insertUri =
                                    contentResolver.insert(
                                        Telephony.Sms.CONTENT_URI,
                                        messageMetadata
                                    )
                                if (insertUri == null) {
                                    Log.v(LOG_TAG, "SMS insert failed!")
                                } else totals.sms++
                            } else { // it's MMS
                                if (BuildConfig.DEBUG && (!MMS || totals.mms == MAX_MESSAGES)) continue
                                val threadId = getOrCreateThreadId(
                                    this@MainActivity,
                                    addresses.map { it1 -> it1.getAsString("address") }.toSet()
                                )
                                messageMetadata.put("thread_id", threadId)
                                val insertUri =
                                    contentResolver.insert(
                                        Telephony.Mms.CONTENT_URI,
                                        messageMetadata
                                    )
                                if (insertUri == null) {
                                    Log.v(LOG_TAG, "MMS insert failed!")
                                } else {
                                    totals.mms++
//                                Log.v(LOG_TAG, "MMS insert succeeded!")
                                    val messageId = insertUri.lastPathSegment
                                    val addressUri = Uri.parse("content://mms/$messageId/addr")
                                    addresses.forEach { address1 ->
                                        address1.put(Telephony.Mms.Addr.MSG_ID, messageId)
                                        val insertAddressUri =
                                            contentResolver.insert(addressUri, address1)
                                        if (insertAddressUri == null) {
                                            Log.v(LOG_TAG, "MMS address insert failed!")
                                        } /*else {
                                        Log.v(LOG_TAG, "MMS address insert succeeded. Address metadata:" + address.toString())
                                    }*/
                                    }
                                    val partUri = Uri.parse("content://mms/$messageId/part")
                                    parts.forEachIndexed { j, part1 ->
                                        part1.put(Telephony.Mms.Part.MSG_ID, messageId)
                                        val insertPartUri =
                                            contentResolver.insert(partUri, part1)
                                        if (insertPartUri == null) {
                                            Log.v(
                                                LOG_TAG,
                                                "MMS part insert failed! Part metadata:$part1"
                                            )
                                        } else {
                                            if (binaryData[j] != null) {
                                                val os =
                                                    contentResolver.openOutputStream(insertPartUri)
                                                if (os != null) os.use { os.write(binaryData[j]) }
                                                else Log.v(LOG_TAG, "Failed to open OutputStream!")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        jsonReader.endArray()
                    }
                }
                totals
            }
        }
    }

    fun onCheckBoxClicked(view: View) { if (view is CheckBox) includeBinaryData = view.isChecked }
}

// From https://stackoverflow.com/a/51394768
fun Date.toString(format: String, locale: Locale = Locale.getDefault()): String {
    val formatter = SimpleDateFormat(format, locale)
    return formatter.format(this)
}

fun getCurrentDateTime(): Date { return Calendar.getInstance().time }
