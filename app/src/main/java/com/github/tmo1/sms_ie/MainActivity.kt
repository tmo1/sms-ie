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

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.provider.Telephony
import android.text.format.DateUtils.formatElapsedTime
import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

const val EXPORT_MESSAGES = 1
const val IMPORT_MESSAGES = 2
const val EXPORT_CALL_LOG = 3
const val IMPORT_CALL_LOG = 4
const val PERMISSIONS_REQUEST = 1
const val LOG_TAG = "MYLOG"
const val CHANNEL_ID = "MYCHANNEL"

// PduHeaders are referenced here https://developer.android.com/reference/android/provider/Telephony.Mms.Addr#TYPE
// and defined here https://android.googlesource.com/platform/frameworks/opt/mms/+/4bfcd8501f09763c10255442c2b48fad0c796baa/src/java/com/google/android/mms/pdu/PduHeaders.java
// but are apparently unavailable in a public class
const val PDU_HEADERS_FROM = "137"

data class MessageTotal(var sms: Int = 0, var mms: Int = 0)

class MainActivity : AppCompatActivity(), ConfirmWipeFragment.NoticeDialogListener {

    private lateinit var prefs: SharedPreferences

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val inflater: MenuInflater = menuInflater
        inflater.inflate(R.menu.options_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.settings -> {
                val launchSettingsActivity = Intent(this, SettingsActivity::class.java)
                startActivity(launchSettingsActivity)
                //finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // get necessary permissions on startup
        val allPermissions = listOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG
        )
        val necessaryPermissions = mutableListOf<String>()
        allPermissions.forEach {
            if (ContextCompat.checkSelfPermission(this, it)
                != PackageManager.PERMISSION_GRANTED
            ) {
                necessaryPermissions.add(it)
            }
        }

        if (necessaryPermissions.any()) {
            requestPermissions(necessaryPermissions.toTypedArray(), PERMISSIONS_REQUEST)
        }

        // set up UI
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))
        val exportMessagesButton: Button = findViewById(R.id.export_messages_button)
        val exportCallLogButton: Button = findViewById(R.id.export_call_log_button)
        val importMessagesButton: Button = findViewById(R.id.import_messages_button)
        val importCallLogButton: Button = findViewById(R.id.import_call_log_button)
        val wipeAllMessagesButton: Button = findViewById(R.id.wipe_all_messages_button)
        exportMessagesButton.setOnClickListener { exportMessagesFile() }
        importMessagesButton.setOnClickListener { importMessagesFile() }
        exportCallLogButton.setOnClickListener { exportCallLogFile() }
        importCallLogButton.setOnClickListener { importCallLogFile() }
        wipeAllMessagesButton.setOnClickListener { wipeMessages() }
        //actionBar?.setDisplayHomeAsUpEnabled(true)

        prefs = PreferenceManager.getDefaultSharedPreferences(this)

        // Create and register notification channel
        // https://developer.android.com/training/notify-user/channels
        // https://developer.android.com/training/notify-user/build-notification#Priority
        if (SDK_INT >= Build.VERSION_CODES.O) {
            // Create the NotificationChannel
            val name = getString(R.string.channel_name)
            val descriptionText = getString(R.string.channel_description)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val mChannel = NotificationChannel(CHANNEL_ID, name, importance)
            mChannel.description = descriptionText
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(mChannel)
        }
    }

    private fun exportMessagesFile() {
        /*if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_SMS
            ) == PackageManager.PERMISSION_GRANTED
            && ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED
        )*/
        if (checkReadSMSContactsPermissions(this)) {
            val date = getCurrentDateTime()
            val dateInString = date.toString("yyyy-MM-dd")
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "messages-$dateInString.json")
            }
            startActivityForResult(intent, EXPORT_MESSAGES)
        } else {
            Toast.makeText(
                this,
                getString(R.string.sms_permissions_required),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun exportCallLogFile() {
        if (checkReadCallLogsContactsPermissions(this)) {
            val date = getCurrentDateTime()
            val dateInString = date.toString("yyyy-MM-dd")
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "call-log-$dateInString.json")
            }
            startActivityForResult(intent, EXPORT_CALL_LOG)
        } else {
            Toast.makeText(
                this,
                getString(R.string.call_logs_permissions_required),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun importMessagesFile() {
        if (Telephony.Sms.getDefaultSmsPackage(this) == this.packageName) {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type =
                    if (SDK_INT < 29) "*/*" else "application/json" //see https://github.com/tmo1/sms-ie/issues/3#issuecomment-900518890
            }
            startActivityForResult(intent, IMPORT_MESSAGES)
        } else {
            Toast.makeText(
                this@MainActivity,
                getString(R.string.default_sms_app_requirement),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onActivityResult(
        requestCode: Int, resultCode: Int, resultData: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, resultData)
        var total: MessageTotal
        val statusReportText: TextView = findViewById(R.id.status_report)
        val progressBar: ProgressBar = findViewById(R.id.progressBar)
        val startTime = System.nanoTime()
        if (requestCode == EXPORT_MESSAGES
            && resultCode == Activity.RESULT_OK
        ) {
            resultData?.data?.let {
                //statusReportText.text = getString(R.string.begin_exporting_messages)
                //statusReportText.visibility = View.VISIBLE
                CoroutineScope(Dispatchers.Main).launch {
                    total = exportMessages(applicationContext, it, progressBar, statusReportText)
                    statusReportText.text = getString(
                        R.string.export_messages_results,
                        total.sms,
                        total.mms,
                        formatElapsedTime(
                            TimeUnit.SECONDS.convert(
                                System.nanoTime() - startTime,
                                TimeUnit.NANOSECONDS
                            )
                        )
                    )
//                    logElapsedTime(startTime)
                }
            }
        }
        if (requestCode == IMPORT_MESSAGES
            && resultCode == Activity.RESULT_OK
        ) {
            resultData?.data?.let {
                CoroutineScope(Dispatchers.Main).launch {
                    total = importMessages(applicationContext, it, progressBar, statusReportText)
                    statusReportText.text = getString(
                        R.string.import_messages_results,
                        total.sms,
                        total.mms,
                        formatElapsedTime(
                            TimeUnit.SECONDS.convert(
                                System.nanoTime() - startTime,
                                TimeUnit.NANOSECONDS
                            )
                        )
                    )
//                    logElapsedTime(startTime)
                }
            }
        }
        if (requestCode == EXPORT_CALL_LOG
            && resultCode == Activity.RESULT_OK
        ) {
            resultData?.data?.let {
                CoroutineScope(Dispatchers.Main).launch {
                    total = exportCallLog(applicationContext, it, progressBar, statusReportText)
                    statusReportText.text = getString(
                        R.string.export_call_log_results,
                        total.sms,
                        formatElapsedTime(
                            TimeUnit.SECONDS.convert(
                                System.nanoTime() - startTime,
                                TimeUnit.NANOSECONDS
                            )
                        )
                    )
                }
            }
        }
        if (requestCode == IMPORT_CALL_LOG && resultCode == Activity.RESULT_OK) {
            resultData?.data?.let {
                CoroutineScope(Dispatchers.Main).launch {
                    val callsImported = importCallLog(applicationContext, it)
                    statusReportText.text = getString(
                        R.string.import_call_log_results,
                        callsImported,
                        formatElapsedTime(
                            TimeUnit.SECONDS.convert(
                                System.nanoTime() - startTime,
                                TimeUnit.NANOSECONDS
                            )
                        )
                    )
                }
            }
        }
    }

    /*private fun logElapsedTime(since: Long) {
        if (BuildConfig.DEBUG) {
            val elapsedTime = System.nanoTime() - since
            val seconds =
                TimeUnit.SECONDS.convert(elapsedTime, TimeUnit.NANOSECONDS).toString()
            Log.v(LOG_TAG, "Elapsed time: $seconds seconds ($elapsedTime nanoseconds)")
        }
    }*/

    /*private suspend fun exportJSON(file: Uri): MessageTotal {
        return withContext(Dispatchers.IO) {
            val totals = MessageTotal()
            val displayNames = mutableMapOf<String, String?>()
            contentResolver.openOutputStream(file).use { outputStream ->
                BufferedWriter(OutputStreamWriter(outputStream)).use { writer ->
                    val jsonWriter = JsonWriter(writer)
                    jsonWriter.setIndent("  ")
                    jsonWriter.beginArray()
                    if (prefs.getBoolean("sms", true)) totals.sms =
                        smsToJSON(jsonWriter, displayNames)
                    if (prefs.getBoolean("mms", true)) totals.mms =
                        mmsToJSON(jsonWriter, displayNames)
                    jsonWriter.endArray()
                }
            }
            totals
        }
    }*/

    /*private suspend fun exportCallLog(file: Uri): MessageTotal {
        return withContext(Dispatchers.IO) {
            val totals = MessageTotal()
            val displayNames = mutableMapOf<String, String?>()
            contentResolver.openOutputStream(file).use { outputStream ->
                BufferedWriter(OutputStreamWriter(outputStream)).use { writer ->
                    val jsonWriter = JsonWriter(writer)
                    jsonWriter.setIndent("  ")
                    jsonWriter.beginArray()
                    if (prefs.getBoolean("sms", true)) totals.sms = callLogToJSON(jsonWriter, displayNames)
                    jsonWriter.endArray()
                }
            }
            totals
        }
    }*/

    /*private fun callLogToJSON(
        jsonWriter: JsonWriter,
        displayNames: MutableMap<String, String?>
    ): Int {
        var total = 0
        val callCursor =
            contentResolver.query(Uri.parse("content://call_log/calls"), null, null, null, null)
        callCursor?.use { it ->
            if (it.moveToFirst()) {
                val addressIndex = it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                do {
                    jsonWriter.beginObject()
                    it.columnNames.forEachIndexed { i, columnName ->
                        val value = it.getString(i)
                        if (value != null) jsonWriter.name(columnName).value(value)
                    }
                    // The call logs do have a CACHED_NAME ("name") field, but it may still be useful to add the current display name, if available
                    // From the documentation at https://developer.android.com/reference/android/provider/CallLog.Calls#CACHED_NAME
                    // "The cached name associated with the phone number, if it exists.
                    // This value is typically filled in by the dialer app for the caching purpose, so it's not guaranteed to be present, and may not be current if the contact information associated with this number has changed."
                    val displayName =
                        lookupDisplayName(displayNames, it.getString(addressIndex))
                    if (displayName != null) jsonWriter.name("display_name").value(displayName)
                    jsonWriter.endObject()
                    total++
                    if (BuildConfig.DEBUG && total == prefs.getString("max_messages", "")?.toIntOrNull() ?: -1) break
                } while (it.moveToNext())
            }
        }
        return total
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
                    if (BuildConfig.DEBUG && total == prefs.getString("max_messages", "")
                            ?.toIntOrNull() ?: -1
                    ) break
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

                                //if (includeBinaryData && it1.getString(dataIndex) != null) {
                                if (prefs.getBoolean("include_binary_data", true) && it1.getString(
                                        dataIndex
                                    ) != null
                                ) {
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
                    if (BuildConfig.DEBUG && total == prefs.getString("max_messages", "")
                            ?.toIntOrNull() ?: -1
                    ) break
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
    }*/

    /*private suspend fun importMessages(uri: Uri): MessageTotal {
        return withContext(Dispatchers.IO) {
            val totals = MessageTotal()
            // get column names of local SMS and MMS tables
            val smsColumns = mutableSetOf<String>()
            val smsCursor =
                contentResolver.query(Telephony.Sms.CONTENT_URI, null, null, null, null)
            smsCursor?.use { smsColumns.addAll(it.columnNames) }
            val mmsColumns = mutableSetOf<String>()
            val mmsCursor =
                contentResolver.query(Telephony.Mms.CONTENT_URI, null, null, null, null)
            mmsCursor?.use { mmsColumns.addAll(it.columnNames) }
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
                                if (!prefs.getBoolean("sms", true) || totals.sms == prefs.getString(
                                        "max_messages",
                                        ""
                                    )?.toIntOrNull() ?: -1
                                ) continue
                                val fieldNames = mutableSetOf<String>()
                                fieldNames.addAll(messageMetadata.keySet())
                                fieldNames.forEach { key ->
                                    if (!smsColumns.contains(key)) {
                                        messageMetadata.remove(key)
                                    }
                                }
                                val insertUri =
                                    contentResolver.insert(
                                        Telephony.Sms.CONTENT_URI,
                                        messageMetadata
                                    )
                                if (insertUri == null) {
                                    Log.v(LOG_TAG, "SMS insert failed!")
                                } else totals.sms++
                            } else { // it's MMS
                                if (!prefs.getBoolean("mms", true) || totals.mms == prefs.getString(
                                        "max_messages",
                                        ""
                                    )?.toIntOrNull() ?: -1
                                ) continue
                                val fieldNames = mutableSetOf<String>()
                                fieldNames.addAll(messageMetadata.keySet())
                                fieldNames.forEach { key ->
                                    if (!mmsColumns.contains(key)) {
                                        messageMetadata.remove(key)
                                    }
                                }
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
                                        } *//*else {
                                        Log.v(LOG_TAG, "MMS address insert succeeded. Address metadata:" + address.toString())
                                    }*//*
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
    }*/

    private fun importCallLogFile() {
        if (checkReadWriteCallLogPermissions(this)) {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
            }
            startActivityForResult(intent, IMPORT_CALL_LOG)
        } else {
            Toast.makeText(
                this,
                getString(R.string.call_logs_read_write_permissions_required),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun wipeMessages() {
        if (Telephony.Sms.getDefaultSmsPackage(this) == this.packageName) {
            ConfirmWipeFragment().show(supportFragmentManager, "wipe")
        } else {
            Toast.makeText(
                this@MainActivity,
                getString(R.string.default_sms_app_requirement),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // From: https://developer.android.com/guide/topics/ui/dialogs#PassingEvents
    // The dialog fragment receives a reference to this Activity through the
    // Fragment.onAttach() callback, which it uses to call the following methods
    // defined by the NoticeDialogFragment.NoticeDialogListener interface

    override fun onDialogPositiveClick(dialog: DialogFragment) {
        // User touched the dialog's positive button
        val statusReportText: TextView = findViewById(R.id.status_report)
        val progressBar: ProgressBar = findViewById(R.id.progressBar)
        CoroutineScope(Dispatchers.Main).launch {
            wipeSmsAndMmsMessages(applicationContext, statusReportText, progressBar)
            Log.v(LOG_TAG, "Pretending to wipe messages ...")
            statusReportText.text = getString(R.string.messages_wiped)
        }
    }

    override fun onDialogNegativeClick(dialog: DialogFragment) {
        // User touched the dialog's negative button
        val statusReportText: TextView = findViewById(R.id.status_report)
        statusReportText.text = getString(R.string.wipe_cancelled)
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

// https://developer.android.com/guide/topics/ui/dialogs
// https://developer.android.com/guide/fragments/dialogs
class ConfirmWipeFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return activity?.let {
            // Use the Builder class for convenient dialog construction
            val builder = AlertDialog.Builder(it)
            builder.setMessage(R.string.dialog_confirm_wipe)
                .setPositiveButton(R.string.wipe
                ) { dialog, id ->
                    // Send the positive button event back to the host activity
                    listener.onDialogPositiveClick(this)
                }
                .setNegativeButton(R.string.cancel
                ) { dialog, id ->
                    // User cancelled the dialog
                    // Send the negative button event back to the host activity
                    listener.onDialogNegativeClick(this)
                }
                .setTitle(R.string.wipe_messages)
                // https://stackoverflow.com/a/45386778
                .setIcon(android.R.drawable.ic_dialog_alert)
            // Create the AlertDialog object and return it
            builder.create()
        } ?: throw IllegalStateException("Activity cannot be null")
    }

    // From: https://developer.android.com/guide/topics/ui/dialogs#PassingEvents
    // Use this instance of the interface to deliver action events
    private lateinit var listener: NoticeDialogListener

    /* The activity that creates an instance of this dialog fragment must
     * implement this interface in order to receive event callbacks.
     * Each method passes the DialogFragment in case the host needs to query it. */
    interface NoticeDialogListener {
        fun onDialogPositiveClick(dialog: DialogFragment)
        fun onDialogNegativeClick(dialog: DialogFragment)
    }

    // Override the Fragment.onAttach() method to instantiate the NoticeDialogListener
    override fun onAttach(context: Context) {
        super.onAttach(context)
        // Verify that the host activity implements the callback interface
        try {
            // Instantiate the NoticeDialogListener so we can send events to the host
            listener = context as NoticeDialogListener
        } catch (e: ClassCastException) {
            // The activity doesn't implement the interface, throw exception
            throw ClassCastException(
                (context.toString() +
                        " must implement NoticeDialogListener")
            )
        }
    }
}
