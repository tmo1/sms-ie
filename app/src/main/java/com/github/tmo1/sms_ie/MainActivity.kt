/*
 * SMS Import / Export: a simple Android app for importing and exporting SMS and MMS messages,
 * call logs, and contacts, from and to JSON / NDJSON files.
 *
 * Copyright (c) 2021-2024 Thomas More
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
import android.app.Dialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS
import android.provider.Telephony
import android.text.format.DateUtils.formatElapsedTime
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

const val EXPORT_MESSAGES = 1
const val IMPORT_MESSAGES = 2
const val EXPORT_CALL_LOG = 3
const val IMPORT_CALL_LOG = 4
const val EXPORT_CONTACTS = 5
const val IMPORT_CONTACTS = 6
const val BECOME_DEFAULT_SMS_APP = 100
const val PERMISSIONS_REQUEST = 1
const val LOG_TAG = "SMSIE"
const val CHANNEL_ID_PERSISTENT = "PERSISTENT"
const val CHANNEL_ID_ALERTS = "ALERTS"
const val NOTIFICATION_ID_PERSISTENT = 0
const val NOTIFICATION_ID_ALERT = 1

class MainActivity : AppCompatActivity(), ConfirmWipeFragment.NoticeDialogListener,
    BecomeDefaultSMSAppFragment.NoticeDialogListener {

    private lateinit var prefs: SharedPreferences

    // We use 'operation' to tell onActivityResult() which operation to run after the user has made
    // the app into the default SMS app. This feels hackish, but it's simple and effective, and
    // while it would be easy enough to pass the operation as a parameter to checkDefaultSMSApp(),
    // I couldn't figure out a simple and effective way to pass the operation through to
    // to onDefaultSMSAppDialogPositiveClick()
    private lateinit var operation: () -> Unit

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

            R.id.about -> {
                val launchAboutActivity = Intent(this, AboutActivity::class.java)
                startActivity(launchAboutActivity)
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
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG
        )
        val necessaryPermissions = mutableListOf<String>()
        allPermissions.forEach {
            if (ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED) {
                necessaryPermissions.add(it)
            }
        }

        if (necessaryPermissions.any()) {
            ActivityCompat.requestPermissions(
                this, necessaryPermissions.toTypedArray(), PERMISSIONS_REQUEST
            )
        }

        // set up UI
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))
        val exportMessagesButton: Button = findViewById(R.id.export_messages_button)
        val exportCallLogButton: Button = findViewById(R.id.export_call_log_button)
        val importMessagesButton: Button = findViewById(R.id.import_messages_button)
        val importCallLogButton: Button = findViewById(R.id.import_call_log_button)
        val wipeAllMessagesButton: Button = findViewById(R.id.wipe_all_messages_button)
        val exportContactsButton: Button = findViewById(R.id.export_contacts_button)
        val importContactsButton: Button = findViewById(R.id.import_contacts_button)
        val setDefaultSMSAppButton: Button = findViewById(R.id.set_default_sms_app_button)

        exportMessagesButton.setOnClickListener { exportMessagesManual() }
        importMessagesButton.setOnClickListener {
            operation = ::importMessagesManual
            checkDefaultSMSApp()
        }
        exportCallLogButton.setOnClickListener { exportCallLogManual() }
        importCallLogButton.setOnClickListener { importCallLogManual() }
        exportContactsButton.setOnClickListener { exportContactsManual() }
        importContactsButton.setOnClickListener { importContactsManual() }
        wipeAllMessagesButton.setOnClickListener {
            operation = ::wipeMessagesManual
            checkDefaultSMSApp()
        }
        setDefaultSMSAppButton.setOnClickListener { startActivity(Intent(ACTION_MANAGE_DEFAULT_APPS_SETTINGS))}
        //actionBar?.setDisplayHomeAsUpEnabled(true)

        prefs = PreferenceManager.getDefaultSharedPreferences(this)

        // Create and register notification channels
        // https://developer.android.com/training/notify-user/channels
        // https://developer.android.com/training/notify-user/build-notification#Priority
        if (SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this

            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID_PERSISTENT,
                    getString(R.string.persistent_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = getString(R.string.persistent_channel_description)
                }
            )

            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID_ALERTS,
                    getString(R.string.alerts_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = getString(R.string.alerts_channel_description)
                }
            )

            // Remove legacy notification channels to accommodate upgrades
            notificationManager.deleteNotificationChannel("MYCHANNEL")
        }
    }

    override fun onResume() {
        super.onResume()
        val defaultSMSAppWarning: TextView = findViewById(R.id.default_sms_app_warning)
        val setDefaultSMSAppButton: Button =
            findViewById(R.id.set_default_sms_app_button)
        val areWeDefaultSMSApp = if (SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            roleManager.isRoleHeld(RoleManager.ROLE_SMS)
        } else {
            Telephony.Sms.getDefaultSmsPackage(this) == packageName
        }
        if (areWeDefaultSMSApp) {
            defaultSMSAppWarning.visibility = View.VISIBLE
            setDefaultSMSAppButton.visibility = if (SDK_INT >= 24) View.VISIBLE else View.GONE
        } else {
            defaultSMSAppWarning.visibility = View.GONE
            setDefaultSMSAppButton.visibility = View.GONE
        }
    }

    private fun exportMessagesManual() {/*if (ContextCompat.checkSelfPermission(
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
                type = "application/zip"
                putExtra(Intent.EXTRA_TITLE, "messages-$dateInString.zip")
            }
            startActivityForResult(intent, EXPORT_MESSAGES)
        } else {
            setStatusReport(getString(R.string.sms_permissions_required))
        }
    }

    private fun importMessagesManual() {
        if (SDK_INT < 23) {
            setStatusReport(getString(R.string.message_import_api_23_requirement))
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type =
                if (SDK_INT < 29) "*/*" else "application/zip" //see https://github.com/tmo1/sms-ie/issues/3#issuecomment-900518890
        }
        startActivityForResult(intent, IMPORT_MESSAGES)
    }

    private fun exportCallLogManual() {
        if (checkReadCallLogsContactsPermissions(this)) {
            val date = getCurrentDateTime()
            val dateInString = date.toString("yyyy-MM-dd")
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "calls-$dateInString.json")
            }
            startActivityForResult(intent, EXPORT_CALL_LOG)
        } else {
            setStatusReport(getString(R.string.call_logs_permissions_required))
        }
    }

    private fun importCallLogManual() {
        if (checkReadWriteCallLogPermissions(this)) {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type =
                    if (SDK_INT < 29) "*/*" else "application/json" //see https://github.com/tmo1/sms-ie/issues/3#issuecomment-900518890
            }
            startActivityForResult(intent, IMPORT_CALL_LOG)
        } else {
            setStatusReport(getString(R.string.call_logs_read_write_permissions_required))
        }
    }

    private fun exportContactsManual() {
        if (checkReadContactsPermission(this)) {
            val date = getCurrentDateTime()
            val dateInString = date.toString("yyyy-MM-dd")
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "contacts-$dateInString.json")
            }
            startActivityForResult(intent, EXPORT_CONTACTS)
        } else {
            setStatusReport(getString(R.string.contacts_read_permission_required))
        }
    }

    private fun importContactsManual() {
        if (checkWriteContactsPermission(this)) {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type =
                    if (SDK_INT < 29) "*/*" else "application/json" //see https://github.com/tmo1/sms-ie/issues/3#issuecomment-900518890
            }
            startActivityForResult(intent, IMPORT_CONTACTS)
        } else {
            setStatusReport(getString(R.string.contacts_write_permissions_required))
        }
    }

    private fun wipeMessagesManual() {
        ConfirmWipeFragment().show(supportFragmentManager, "wipe")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(
        requestCode: Int, resultCode: Int, resultData: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, resultData)
        var total: MessageTotal
        val statusReportText: TextView = findViewById(R.id.status_report)
        val progressBar: ProgressBar = findViewById(R.id.progressBar)
        val startTime = System.nanoTime()
        // Throughout this function, we pass 'this@MainActivity' to the import functions, since they
        // currently create AlertDialogs upon catching exceptions, and AlertDialogs need
        // Activity context - see:
        // https://stackoverflow.com/a/7229248
        // https://stackoverflow.com/a/52224145
        // https://stackoverflow.com/a/51516252
        // But we pass 'applicationContext' to the export functions, since they don't currently
        // create AlertDialogs. Perhaps we should just pass Activity context to them as well, to be
        // consistent.
        if (requestCode == EXPORT_MESSAGES && resultCode == RESULT_OK) {
            resultData?.data?.let {
                //statusReportText.text = getString(R.string.begin_exporting_messages)
                CoroutineScope(Dispatchers.Main).launch {
                    total = exportMessages(applicationContext, it, progressBar, statusReportText)
                    statusReportText.text = getString(
                        R.string.export_messages_results, total.sms, total.mms, formatElapsedTime(
                            TimeUnit.SECONDS.convert(
                                System.nanoTime() - startTime, TimeUnit.NANOSECONDS
                            )
                        )
                    )
//                    logElapsedTime(startTime)
                }
            }
        }
        if (requestCode == IMPORT_MESSAGES && resultCode == RESULT_OK) {
            resultData?.data?.let {
                CoroutineScope(Dispatchers.Main).launch {
                    // importMessages() requires API level 23, but we check for that back in importMessagesManual()
                    total = importMessages(this@MainActivity, it, progressBar, statusReportText)
                    statusReportText.text = getString(
                        R.string.import_messages_results, total.sms, total.mms, formatElapsedTime(
                            TimeUnit.SECONDS.convert(
                                System.nanoTime() - startTime, TimeUnit.NANOSECONDS
                            )
                        )
                    )
//                    logElapsedTime(startTime)
                }
            }
        }
        if (requestCode == EXPORT_CALL_LOG && resultCode == RESULT_OK) {
            resultData?.data?.let {
                CoroutineScope(Dispatchers.Main).launch {
                    val callsExported =
                        exportCallLog(applicationContext, it, progressBar, statusReportText)
                    statusReportText.text = getString(
                        R.string.export_call_log_results, callsExported, formatElapsedTime(
                            TimeUnit.SECONDS.convert(
                                System.nanoTime() - startTime, TimeUnit.NANOSECONDS
                            )
                        )
                    )
                }
            }
        }
        if (requestCode == IMPORT_CALL_LOG && resultCode == RESULT_OK) {
            resultData?.data?.let {
                CoroutineScope(Dispatchers.Main).launch {
                    val callsImported =
                        importCallLog(this@MainActivity, it, progressBar, statusReportText)
                    statusReportText.text = getString(
                        R.string.import_call_log_results, callsImported, formatElapsedTime(
                            TimeUnit.SECONDS.convert(
                                System.nanoTime() - startTime, TimeUnit.NANOSECONDS
                            )
                        )
                    )
                }
            }
        }
        if (requestCode == EXPORT_CONTACTS && resultCode == RESULT_OK) {
            resultData?.data?.let {
                CoroutineScope(Dispatchers.Main).launch {
                    val contactsExported =
                        exportContacts(applicationContext, it, progressBar, statusReportText)
                    statusReportText.text = getString(
                        R.string.export_contacts_results, contactsExported, formatElapsedTime(
                            TimeUnit.SECONDS.convert(
                                System.nanoTime() - startTime, TimeUnit.NANOSECONDS
                            )
                        )
                    )
                }
            }
        }

        if (requestCode == IMPORT_CONTACTS && resultCode == RESULT_OK) {
            resultData?.data?.let {
                CoroutineScope(Dispatchers.Main).launch {
                    val contactsImported =
                        importContacts(this@MainActivity, it, progressBar, statusReportText)
                    statusReportText.text = getString(
                        R.string.import_contacts_results, contactsImported, formatElapsedTime(
                            TimeUnit.SECONDS.convert(
                                System.nanoTime() - startTime, TimeUnit.NANOSECONDS
                            )
                        )
                    )
                }
            }
        }
        if (requestCode == BECOME_DEFAULT_SMS_APP && resultCode == RESULT_OK) {
            operation()
        }
    }

    // Dialog ('wipe confirmation' and 'become default SMS app') button callbacks

    // From: https://developer.android.com/guide/topics/ui/dialogs#PassingEvents
    // The dialog fragment receives a reference to this Activity through the
    // Fragment.onAttach() callback, which it uses to call the following methods
    // defined by the NoticeDialogFragment.NoticeDialogListener interface

    override fun onWipeDialogPositiveClick(dialog: DialogFragment) {
        val statusReportText: TextView = findViewById(R.id.status_report)
        val progressBar: ProgressBar = findViewById(R.id.progressBar)
        CoroutineScope(Dispatchers.Main).launch {
            wipeSmsAndMmsMessages(applicationContext, statusReportText, progressBar)
            statusReportText.text = getString(R.string.messages_wiped)
        }
    }

    override fun onWipeDialogNegativeClick(dialog: DialogFragment) {
        val statusReportText: TextView = findViewById(R.id.status_report)
        statusReportText.text = getString(R.string.wipe_cancelled)
    }

    override fun onDefaultSMSAppDialogPositiveClick(dialog: DialogFragment) {
        if (SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            startActivityForResult(
                roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS), BECOME_DEFAULT_SMS_APP
            )
        } else {
            val becomeDefaultSMSAppIntent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
            becomeDefaultSMSAppIntent.putExtra(
                Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName
            )
            startActivityForResult(becomeDefaultSMSAppIntent, BECOME_DEFAULT_SMS_APP)
        }
    }

    private fun setStatusReport(statusReport: String) {
        val statusReportText: TextView = findViewById(R.id.status_report)
        statusReportText.text = statusReport
    }

    private fun checkDefaultSMSApp() {
        // https://stackoverflow.com/questions/32885948/how-to-set-an-sms-app-as-default-app-in-android-programmatically
        // https://stackoverflow.com/questions/64135681/change-default-sms-app-intent-not-working-on-android-10
        // https://stackoverflow.com/questions/59554835/android-10-default-sms-app-dialog-not-showing-up/60372137#60372137
        if (SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_SMS) && !roleManager.isRoleHeld(
                    RoleManager.ROLE_SMS
                )
            ) {
                BecomeDefaultSMSAppFragment().show(supportFragmentManager, "become_default_sms_app")
            } else operation()
        } else {
            if (Telephony.Sms.getDefaultSmsPackage(this) != packageName) {
                BecomeDefaultSMSAppFragment().show(supportFragmentManager, "become_default_sms_app")
            } else operation()
        }
    }
}

// https://developer.android.com/guide/topics/ui/dialogs
// https://developer.android.com/guide/fragments/dialogs
class ConfirmWipeFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return activity?.let {
            val builder = AlertDialog.Builder(it)
            builder.setMessage(R.string.dialog_confirm_wipe).setPositiveButton(
                R.string.wipe
            ) { dialog, id ->
                listener.onWipeDialogPositiveClick(this)
            }.setNegativeButton(
                R.string.cancel
            ) { dialog, id ->
                listener.onWipeDialogNegativeClick(this)
            }.setTitle(R.string.wipe_messages)
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
        fun onWipeDialogPositiveClick(dialog: DialogFragment)
        fun onWipeDialogNegativeClick(dialog: DialogFragment)
    }

    // Override the Fragment.onAttach() method to instantiate the NoticeDialogListener
    override fun onAttach(context: Context) {
        super.onAttach(context)
        // Verify that the host activity implements the callback interface
        try {
            // Instantiate the NoticeDialogListener so we can send events to the host
            listener = context as NoticeDialogListener
        } catch (_: ClassCastException) {
            // The activity doesn't implement the interface, throw exception
            throw ClassCastException(
                ("$context must implement NoticeDialogListener")
            )
        }
    }
}

class BecomeDefaultSMSAppFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return activity?.let {
            val builder = AlertDialog.Builder(it)
            builder.setMessage(R.string.become_default_sms_app_warning).setPositiveButton(
                R.string.okay
            ) { dialog, id ->
                listener.onDefaultSMSAppDialogPositiveClick(this)
            }.setTitle(R.string.default_sms_app_dialog_title)
                .setIcon(android.R.drawable.ic_dialog_alert)
            builder.create()
        } ?: throw IllegalStateException("Activity cannot be null")
    }

    private lateinit var listener: NoticeDialogListener

    interface NoticeDialogListener {
        fun onDefaultSMSAppDialogPositiveClick(dialog: DialogFragment)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        try {
            listener = context as NoticeDialogListener
        } catch (_: ClassCastException) {
            throw ClassCastException(
                ("$context must implement NoticeDialogListener")
            )
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
