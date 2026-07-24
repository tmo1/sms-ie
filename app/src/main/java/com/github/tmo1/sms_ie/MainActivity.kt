/*
 * SMS Import / Export: a simple Android app for importing and exporting SMS and MMS messages,
 * call logs, contacts, and blocked numbers from and to JSON / NDJSON files.
 *
 * Copyright (c) 2021-2026 Thomas More
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
import android.net.Uri
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS
import android.provider.Telephony
import android.text.method.PasswordTransformationMethod
//import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.Observer
import androidx.preference.PreferenceManager
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

const val LOG_TAG = "SMSIE"
const val CHANNEL_ID_PERSISTENT = "PERSISTENT"
const val CHANNEL_ID_ALERTS = "ALERTS"
const val NOTIFICATION_ID_PERSISTENT = 0
const val NOTIFICATION_ID_ALERT = 1

const val ENCRYPTED_FILE_EXTENSION = "ssef"

private const val STATE_PENDING_ACTION = "pending_action"
private const val STATE_POST_SMS_ROLE_ACTION = "post_sms_role_action"
private const val URI_STRING = "uri_string"
private const val NULL_URI_ALLOWED = "null_uri_allowed"
private const val PASSPHRASE = "passphrase"

private enum class PostSmsRoleAction {
    IMPORT_MESSAGES, EXPORT_BLOCKED_NUMBERS, IMPORT_BLOCKED_NUMBERS, WIPE_MESSAGES,
}

val sodium = SodiumAndroid()
val lazySodiumAndroid = LazySodiumAndroid(sodium)

class MainActivity : AppCompatActivity(), ConfirmWipeFragment.NoticeDialogListener,
    BecomeDefaultSMSAppFragment.NoticeDialogListener {
    private lateinit var prefs: SharedPreferences
    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            // We currently request permissions on startup, but don't block UI interactions if they
            // are denied. When performing an action, the user will be notified that they need to
            // grant permissions.
        }
    private val requestExistingFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
            uri = u
            nullUriAllowed = false
            launchPendingAction()
        }
    private val requestNewJsonFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { u ->
            uri = u
            nullUriAllowed = false
            launchPendingAction()
        }
    private val requestNewZipFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { u ->
            uri = u
            nullUriAllowed = false
            launchPendingAction()
        }

    private val requestNewBinaryFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { u ->
            uri = u
            nullUriAllowed = false
            launchPendingAction()
        }
    private val requestSmsRole =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            launchPostSmsRoleAction(result.resultCode == RESULT_OK)
        }

    // The following variables are all saved across Activity recreation in onSavedInstanceState.
    // Action to perform after file selection.
    private var pendingAction: Action? = null

    // Action to perform after the SMS role has been acquired.
    private var postSmsRoleAction: PostSmsRoleAction? = null

    // User supplied passphrase
    private var passphrase: String? = null

    // User supplied URI
    private var uri: Uri? = null
    private var nullUriAllowed: Boolean = false

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

            R.id.message_filters -> {
                val launchMessageFiltersActivity = Intent(this, MessageFiltersActivity::class.java)
                startActivity(launchMessageFiltersActivity)
                //finish()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Let the app draw under the status bar. The appcompat library figures out what needs to be
        // done to prevent the app from shifting up. Edge-to-edge is required for
        // android:windowLightStatusBar in themes.xml to work so that light text isn't used on a
        // light status bar.
        enableEdgeToEdge()

        super.onCreate(savedInstanceState)

        if (savedInstanceState != null) {
            val pendingActionIndex = savedInstanceState.getInt(STATE_PENDING_ACTION, -1)
            if (pendingActionIndex != -1) {
                pendingAction = Action.entries.toTypedArray()[pendingActionIndex]
            }

            val postSmsRoleActionIndex = savedInstanceState.getInt(STATE_POST_SMS_ROLE_ACTION, -1)
            if (postSmsRoleActionIndex != -1) {
                postSmsRoleAction = PostSmsRoleAction.entries.toTypedArray()[postSmsRoleActionIndex]
            }
        }

        // get necessary permissions on startup
        requestPermissions.launch(
            arrayOf(
                Manifest.permission.READ_SMS,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.WRITE_CONTACTS,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.WRITE_CALL_LOG,
                // No need to API level check since androidx does so itself for POST_NOTIFICATIONS.
                //noinspection InlinedApi
                Manifest.permission.POST_NOTIFICATIONS,
            )
        )

        // set up UI
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.avoidObstructions { insets ->
            // Shift the toolbar down using margins, but use padding for the sides. When the phone
            // is in landscape and the phone has a notch, it looks nicer to have the bar's
            // background extend horizontally fully.
            updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = insets.top
            }
            updatePadding(
                left = insets.left,
                right = insets.right,
            )
        }
        setSupportActionBar(toolbar)

        findViewById<ScrollView>(R.id.main_content).avoidObstructions { insets ->
            updatePadding(
                left = insets.left,
                right = insets.right,
                bottom = insets.bottom,
            )
        }

        val exportMessagesButton: Button = findViewById(R.id.export_messages_button)
        val importMessagesButton: Button = findViewById(R.id.import_messages_button)
        val exportCallLogButton: Button = findViewById(R.id.export_call_log_button)
        val importCallLogButton: Button = findViewById(R.id.import_call_log_button)
        val exportContactsButton: Button = findViewById(R.id.export_contacts_button)
        val importContactsButton: Button = findViewById(R.id.import_contacts_button)
        val exportBlockedNumbersButton: Button = findViewById(R.id.export_blocked_numbers_button)
        val importBlockedNumbersButton: Button = findViewById(R.id.import_blocked_numbers_button)
        val wipeAllMessagesButton: Button = findViewById(R.id.wipe_all_messages_button)
        val countMessagesButton: Button = findViewById(R.id.count_messages_button)
        val setDefaultSMSAppButton: Button = findViewById(R.id.set_default_sms_app_button)
        val statusReportText: TextView = findViewById(R.id.status_report)
        val progressBar: ProgressBar = findViewById(R.id.progressBar)
        val cancelButton: Button = findViewById(R.id.cancel_button)

        exportMessagesButton.setOnClickListener { exportMessagesManual() }
        importMessagesButton.setOnClickListener {
            postSmsRoleAction = PostSmsRoleAction.IMPORT_MESSAGES
            checkDefaultSMSApp()
        }
        exportCallLogButton.setOnClickListener { exportCallLogManual() }
        importCallLogButton.setOnClickListener { importCallLogManual() }
        exportContactsButton.setOnClickListener { exportContactsManual() }
        importContactsButton.setOnClickListener { importContactsManual() }
        exportBlockedNumbersButton.setOnClickListener {
            postSmsRoleAction = PostSmsRoleAction.EXPORT_BLOCKED_NUMBERS
            checkDefaultSMSApp()
        }
        importBlockedNumbersButton.setOnClickListener {
            postSmsRoleAction = PostSmsRoleAction.IMPORT_BLOCKED_NUMBERS
            checkDefaultSMSApp()
        }
        wipeAllMessagesButton.setOnClickListener {
            postSmsRoleAction = PostSmsRoleAction.WIPE_MESSAGES
            checkDefaultSMSApp()
        }
        countMessagesButton.setOnClickListener { countMessagesManual() }
        setDefaultSMSAppButton.setOnClickListener {
            startActivity(Intent(ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }
        //actionBar?.setDisplayHomeAsUpEnabled(true)

        supportFragmentManager.setFragmentResultListener(
            "setPassphrase", this
        ) { requestKey, bundle ->
            //passphrase = bundle.getString("passphrase")
            //Toast.makeText(this, "Passphrase: $passphrase", Toast.LENGTH_SHORT).show()
            //launchPendingAction()
            scheduleManualAction(this, pendingAction!!, uri, bundle.getString("passphrase"))
        }

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
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.persistent_channel_description)
                })

            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID_ALERTS,
                    getString(R.string.alerts_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = getString(R.string.alerts_channel_description)
                })

            // Remove legacy notification channels to accommodate upgrades
            notificationManager.deleteNotificationChannel("MYCHANNEL")
        }

        val workManager = WorkManager.getInstance(this)
        workManager.getWorkInfosLiveData(
            WorkQuery.fromTags(
                ImportExportWorker.TAG_MANUAL_ACTION,
                ImportExportWorker.TAG_AUTOMATIC_EXPORT,
            )
        ).observe(this, Observer {
            var isRunning = false
            var canCancel = false

            // There should only be one active worker. The only other one would be the enqueued
            // work for the next scheduled export.
            for (workInfo in it) {
                when (workInfo.state) {
                    WorkInfo.State.RUNNING -> {
                        isRunning = true

                        val progress = Progress(workInfo.progress)
                        progressBar.isIndeterminate = progress.total == 0
                        progressBar.max = progress.total
                        progressBar.progress = progress.current
                        statusReportText.text = progress.message
                        canCancel = progress.canCancel

                        cancelButton.setOnClickListener {
                            workManager.cancelWorkById(workInfo.id)
                        }
                    }

                    WorkInfo.State.SUCCEEDED -> {
                        val success = SuccessData(workInfo.outputData)
                        statusReportText.text = success.message
                    }

                    WorkInfo.State.FAILED -> {
                        val failure = FailureData(workInfo.outputData)
                        // Just show the general error from the title in the status. The more
                        // detailed message will be shown in the dialog box.
                        statusReportText.text = failure.title

                        ErrorMessageFragment.newInstance(
                            failure.title,
                            failure.message,
                            failure.savedLogcat,
                        ).show(supportFragmentManager, "error")
                    }
                    // Canceled work can occur when changing scheduled export settings or when
                    // the user explicitly cancels a running operation. We want both to be
                    // pruned below.
                    WorkInfo.State.CANCELLED -> {
                        statusReportText.text = getString(R.string.operation_cancelled)
                    }

                    else -> continue
                }

                // WorkManager keeps a history of completed jobs for a certain period of time.
                // We want to get rid of this history once we've seen the result and updated the
                // UI accordingly. It's a bit hacky, but this way, we'll only see new status
                // updates without needing to separately keep track of which completed work IDs
                // we've already observed.
                if (workInfo.state.isFinished) {
                    workManager.pruneWork()
                }
            }

            progressBar.visibility = if (isRunning) View.VISIBLE else View.INVISIBLE
            cancelButton.visibility = if (canCancel) View.VISIBLE else View.INVISIBLE

            // Although ImportExportWorker uses a unique work ID to guarantee that multiple
            // operations won't run at the same time, we should still try to prevent the user
            // from causing this situation.
            listOf(
                exportMessagesButton,
                importMessagesButton,
                exportCallLogButton,
                importCallLogButton,
                exportContactsButton,
                importContactsButton,
                exportBlockedNumbersButton,
                importBlockedNumbersButton,
                wipeAllMessagesButton,
                countMessagesButton,
                setDefaultSMSAppButton
            ).forEach { button -> button.isEnabled = !isRunning }
        })
    }

    override fun onResume() {
        super.onResume()
        val defaultSMSAppWarning: TextView = findViewById(R.id.default_sms_app_warning)
        val setDefaultSMSAppButton: Button = findViewById(R.id.set_default_sms_app_button)
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        pendingAction?.let {
            outState.putInt(STATE_PENDING_ACTION, it.ordinal)
        }
        postSmsRoleAction?.let {
            outState.putInt(STATE_POST_SMS_ROLE_ACTION, it.ordinal)
        }
        uri?.let {
            outState.putString(URI_STRING, it.toString())
        }
        nullUriAllowed.let {
            outState.putBoolean(NULL_URI_ALLOWED, it)
        }
        passphrase?.let {
            outState.putString(PASSPHRASE, it)
        }
    }

    private fun exportMessagesManual() {
        if (checkReadSMSPermission(this) && checkReadContactsPermission(this)) launchDispatcher(
            "messages", "zip", true, Action.EXPORT_MESSAGES_MANUAL
        )
        else setStatusReport(getString(R.string.sms_and_contacts_read_permissions_required))
    }

    private fun importMessagesManual() {
        if (SDK_INT < 23) {
            setStatusReport(getString(R.string.message_import_api_23_requirement))
            return
        }
        pendingAction = Action.IMPORT_MESSAGES_MANUAL
        //see https://github.com/tmo1/sms-ie/issues/3#issuecomment-900518890
        requestExistingFile.launch(
            arrayOf(
                if (SDK_INT < 29 || prefs.getBoolean(
                        "encryption_manual_operations", false
                    )
                ) "*/*" else "application/zip"
            )
        )
    }

    private fun exportCallLogManual() {
        if (checkReadCallLogsContactsPermissions(this)) launchDispatcher(
            "calls", "json", false, Action.EXPORT_CALL_LOG_MANUAL
        )
        else setStatusReport(getString(R.string.call_logs_permissions_required))
    }

    private fun importCallLogManual() {
        if (checkReadWriteCallLogPermissions(this)) {
            pendingAction = Action.IMPORT_CALL_LOG_MANUAL
            //see https://github.com/tmo1/sms-ie/issues/3#issuecomment-900518890
            requestExistingFile.launch(
                arrayOf(
                    if (SDK_INT < 29 || prefs.getBoolean(
                            "encryption_manual_operations", false
                        )
                    ) "*/*" else "application/json"
                )
            )
        } else setStatusReport(getString(R.string.call_logs_read_write_permissions_required))
    }

    private fun exportContactsManual() {
        if (checkReadContactsPermission(this)) launchDispatcher(
            "contacts", "json", false, Action.EXPORT_CONTACTS_MANUAL
        )
        else setStatusReport(getString(R.string.contacts_read_permission_required))
    }

    private fun importContactsManual() {
        if (checkWriteContactsPermission(this)) {
            pendingAction = Action.IMPORT_CONTACTS_MANUAL
            //see https://github.com/tmo1/sms-ie/issues/3#issuecomment-900518890
            requestExistingFile.launch(
                arrayOf(
                    if (SDK_INT < 29 || prefs.getBoolean(
                            "encryption_manual_operations", false
                        )
                    ) "*/*" else "application/json"
                )
            )
        } else setStatusReport(getString(R.string.contacts_write_permissions_required))
    }

    private fun exportBlockedNumbersManual() {
        if (SDK_INT < 24) {
            setStatusReport(getString(R.string.blocked_numbers_api_24_requirement))
            return
        }
        launchDispatcher("blocked_numbers", "zip", true, Action.EXPORT_BLOCKED_NUMBERS_MANUAL)
    }

    private fun importBlockedNumbersManual() {
        if (SDK_INT < 24) {
            setStatusReport(getString(R.string.blocked_numbers_api_24_requirement))
            return
        }
        pendingAction = Action.IMPORT_BLOCKED_NUMBERS_MANUAL
        //see https://github.com/tmo1/sms-ie/issues/3#issuecomment-900518890
        requestExistingFile.launch(
            arrayOf(
                if (SDK_INT < 29 || prefs.getBoolean(
                        "encryption_manual_operations", false
                    )
                ) "*/*" else "application/zip"
            )
        )
    }

    private fun wipeMessagesManual() {
        ConfirmWipeFragment().show(supportFragmentManager, "wipe")
    }

    private fun countMessagesManual() {
        if (checkReadSMSPermission(this)) scheduleManualAction(
            this, Action.COUNT_MESSAGES_MANUAL, null, null
        )
        else setStatusReport(getString(R.string.sms_read_permission_required))
    }

    private fun launchDispatcher(
        dataType: String, extension: String, zipfile: Boolean, action: Action
    ) {
        pendingAction = action
        val date = getCurrentDateTime()
        val dateInString = date.toString("yyyy-MM-dd")
        val filename = "$dataType-$dateInString.$extension"
        if (prefs.getBoolean(
                "encryption_manual_operations", false
            )
        ) requestNewBinaryFile.launch("$filename.${ENCRYPTED_FILE_EXTENSION}")
        else if (zipfile) requestNewZipFile.launch(filename) else requestNewJsonFile.launch(filename)
    }

    private fun launchPendingAction() {
        if (uri != null) {
            if (prefs.getBoolean("encryption_manual_operations", false)) {
                if (prefs.getBoolean("encryption_stored_passphrase_manual_operations", false)) {
                    val passphraseManager = PassphraseManager("passphrase_key", this)
                    val passphrase = passphraseManager.retrievePassphrase()
                    if (passphrase == null) {
                        GeneralErrorDialogFragment.newInstance(
                            getString(R.string.encryption_passphrase_retrieval_failure_title),
                            getString(R.string.encryption_passphrase_retrieval_manual_operations_failure_message)
                        ).show(supportFragmentManager, GeneralErrorDialogFragment.TAG)
                        return
                    }
                    scheduleManualAction(this, pendingAction!!, uri, passphrase)
                    return
                }
                PassphraseEntryFragment().show(supportFragmentManager, "passphrase_entry")
                return
            }
            scheduleManualAction(this, pendingAction!!, uri, null)
        }
        if (nullUriAllowed) scheduleManualAction(this, pendingAction!!, null, null)
        // Always clear the pending action, even if we don't start anything (e.g. if the user
        // canceled the file selection).
        pendingAction = null
    }

    // Dialog ('wipe confirmation' and 'become default SMS app') button callbacks

    // From: https://developer.android.com/guide/topics/ui/dialogs#PassingEvents
    // The dialog fragment receives a reference to this Activity through the
    // Fragment.onAttach() callback, which it uses to call the following methods
    // defined by the NoticeDialogFragment.NoticeDialogListener interface

    override fun onWipeDialogPositiveClick(dialog: DialogFragment) {
        pendingAction = Action.WIPE_MESSAGES_MANUAL
        uri = null
        nullUriAllowed = true
        launchPendingAction()
    }

    override fun onWipeDialogNegativeClick(dialog: DialogFragment) {
        val statusReportText: TextView = findViewById(R.id.status_report)
        statusReportText.text = getString(R.string.wipe_cancelled)
    }

    override fun onDefaultSMSAppDialogPositiveClick(dialog: DialogFragment) {
        val intent =
            if (SDK_INT >= Build.VERSION_CODES.Q) getSystemService(RoleManager::class.java).createRequestRoleIntent(
                RoleManager.ROLE_SMS
            )
            else Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
            }
        requestSmsRole.launch(intent)
    }

    private fun setStatusReport(statusReport: String) {
        val statusReportText: TextView = findViewById(R.id.status_report)
        statusReportText.text = statusReport
    }

    private fun checkDefaultSMSApp() {
        // https://stackoverflow.com/questions/32885948/how-to-set-an-sms-app-as-default-app-in-android-programmatically
        // https://stackoverflow.com/questions/64135681/change-default-sms-app-intent-not-working-on-android-10
        // https://stackoverflow.com/questions/59554835/android-10-default-sms-app-dialog-not-showing-up/60372137#60372137
        val haveRole = if (SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            !roleManager.isRoleAvailable(RoleManager.ROLE_SMS) || roleManager.isRoleHeld(RoleManager.ROLE_SMS)
        } else Telephony.Sms.getDefaultSmsPackage(this) == packageName

        if (haveRole) launchPostSmsRoleAction(true)
        else BecomeDefaultSMSAppFragment().show(supportFragmentManager, "become_default_sms_app")
    }

    private fun launchPostSmsRoleAction(canLaunch: Boolean) {
        if (canLaunch) {
            when (postSmsRoleAction!!) {
                PostSmsRoleAction.IMPORT_MESSAGES -> importMessagesManual()
                PostSmsRoleAction.EXPORT_BLOCKED_NUMBERS -> exportBlockedNumbersManual()
                PostSmsRoleAction.IMPORT_BLOCKED_NUMBERS -> importBlockedNumbersManual()
                PostSmsRoleAction.WIPE_MESSAGES -> wipeMessagesManual()
            }
        }
        postSmsRoleAction = null
    }
}

class ErrorMessageFragment : DialogFragment() {
    companion object {
        fun newInstance(title: String, message: String, savedLogcat: Boolean) =
            ErrorMessageFragment().apply {
                arguments = Bundle().apply {
                    putString("title", title)
                    putString("message", message)
                    putBoolean("saved_logcat", savedLogcat)
                }
            }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        AlertDialog.Builder(requireContext()).setTitle(requireArguments().getString("title"))
            .setMessage(requireArguments().getString("message"))
            .setPositiveButton(android.R.string.ok) { _, _ -> }.apply {
                if (requireArguments().getBoolean("saved_logcat")) {
                    setNeutralButton(R.string.open_log_dir) { _, _ ->
                        // The external files directory (/sdcard/Android/data/<package>/files) is
                        // only readable via USB (adb or mtp) or via the system file manager
                        // (DocumentsUI) in recent versions of Android. This intent will open the
                        // system file manager to the proper directory. Unfortunately, passing it a
                        // file:// URI directly will fail with FileUriExposedException. We need to
                        // manually construct a SAF content:// URI. While these constants are not
                        // documented, since they are part of content:// URIs, Android realistically
                        // can't ever change them without revoking permissions to files that apps
                        // have already been granted access to. Android's own tests also hardcode
                        // them.
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            val appExternalDir = requireContext().getExternalFilesDir(null)!!
                            val globalExternalDir = Environment.getExternalStorageDirectory()
                            val relPath = appExternalDir.relativeTo(globalExternalDir)

                            setDataAndType(
                                DocumentsContract.buildDocumentUri(
                                    "com.android.externalstorage.documents",
                                    "primary:$relPath",
                                ),
                                "vnd.android.document/directory",
                            )
                        }
                        startActivity(intent)
                    }
                }
            }.create()
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
            throw ClassCastException(("$context must implement NoticeDialogListener"))
        }
    }
}

class GeneralErrorDialogFragment : DialogFragment() {
    companion object {
        const val TAG = "GeneralErrorDialogFragment"
        const val MESSAGE_KEY = "message"
        const val TITLE_KEY = "title"
        fun newInstance(title: String, message: String): GeneralErrorDialogFragment {
            val fragment = GeneralErrorDialogFragment()
            val args = Bundle()
            args.putString(MESSAGE_KEY, message)
            args.putString(TITLE_KEY, title)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val title = arguments?.getString(TITLE_KEY) ?: "Unknown error"
        val message = arguments?.getString(MESSAGE_KEY) ?: "An unknown error occurred."
        return AlertDialog.Builder(requireActivity()).setTitle(title).setMessage(message)
            .setPositiveButton("OK") { dialog, _ -> dismiss() }.setCancelable(false).create()
    }
}

class PassphraseEntryFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return activity?.let {
            val textInputLayout = TextInputLayout(it).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                hint = "Passphrase"
                endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
                val textInputEditText = TextInputEditText(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    transformationMethod = PasswordTransformationMethod.getInstance()
                }
                addView(textInputEditText)
            }
            val builder = AlertDialog.Builder(it)
            builder.setTitle("Enter encryption passphrase")
                .setIcon(android.R.drawable.ic_dialog_info).setView(textInputLayout)
                .setPositiveButton(
                    R.string.okay
                ) { dialog, id ->
                    setFragmentResult(
                        "setPassphrase", Bundle().apply {
                            putString(
                                "passphrase", textInputLayout.editText?.text.toString().trim()
                            )
                        })
                }
            builder.create()
        } ?: throw IllegalStateException("Activity cannot be null")
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
