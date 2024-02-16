# SMS Import / Export

![GitHub Release](https://img.shields.io/github/v/release/tmo1/sms-ie)
![GitHub Release Date - Published_At](https://img.shields.io/github/release-date/tmo1/sms-ie)
![F-Droid Version](https://img.shields.io/f-droid/v/com.github.tmo1.sms_ie)

![GitHub issues](https://img.shields.io/github/issues/tmo1/sms-ie)
![GitHub closed issues](https://img.shields.io/github/issues-closed/tmo1/sms-ie)
![GitHub commit activity](https://img.shields.io/github/commit-activity/m/tmo1/sms-ie)

SMS Import / Export is a simple Android app that imports and exports SMS and MMS messages, call logs, and contacts from and to (ND)JSON files. (Contacts import and export are currently functional but considered experimental.) Root is not required.

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="80">](https://f-droid.org/packages/com.github.tmo1.sms_ie/)

## Changes In Version 2.0.0 ##

Version 2.0.0 introduced a major rewrite of the SMS and MMS messages import / export code, implementing a new message storage format (`v2`):

 - The messages are now stored in a [Newline-delimited JSON](https://en.wikipedia.org/wiki/JSON_streaming#Newline-Delimited_JSON) file (always named `messages.ndjson`), as opposed to the standard JSON previously used.
 
 - Binary MMS data is now stored separately from message text data and metadata; the `messages.ndjson` file, along with a `data/` directory containing the MMS binary data files copied directly from the Android filesystem (with their original filenames), are both encapsulated in a ZIP file.
 
 - All (ND)JSON tags added by SMS Import / Export are now prefixed with a double underscore (e.g., `__display_name`, `__parts`), to clearly indicate that they have been added by the app.
 
For a discussion of the advantages and disadvantages of the new format over the old one (`v1`), see [here](https://github.com/tmo1/sms-ie/commit/a505f66cdfae19cd2a21edae2774bc3f37fb5af9).

The NDJSON file is not as human-readable as the previous pretty-printed JSON file, due to the necessary absence of newlines within each JSON message record, but this is easily rectified by feeding the NDJSON to the [jq](https://jqlang.github.io/jq/) tool, which will pretty-print it:

```
~$ jq < messages.ndjson
```

**These format changes unfortunately render versions of the app from 2.0.0 and on incompatible with JSON message files produced by earlier versions of the app.** Several solutions to this incompatibility are possible:

 - An earlier version of the app (with a 1.x.x version number) can be used to import messages in `v1` format.
 
 - Where feasible, a current version of the app can be used to re-export the messages to `v2` format.
 
 - A conversion tool to convert message files from `v1` to `v2` format is available [here](https://github.com/tmo1/sms-ie/blob/master/tools/v1-v2-convert.py) (documented [here](https://github.com/tmo1/sms-ie/blob/master/tools/Tools.md)). This tool is experimental, and has not been extensively tested.

The above applies only to SMS and MMS messages; the format for call logs and contacts is currently unchanged, although they may be switched to the new format in the future.

## Installation

SMS Import / Export is available from [Github](https://github.com/tmo1/sms-ie). Releases, which include pre-built APK packages, can be downloaded from the [Releases page](https://github.com/tmo1/sms-ie/releases), and are also available at [F-Droid](https://f-droid.org/packages/com.github.tmo1.sms_ie/). Automatically built (debug) packages of the latest code pushed to the repository are generally available [here](https://github.com/tmo1/sms-ie/actions/workflows/build.yml) (click on the latest workflow run, then click on `com.github.tmo1.sms_ie` in the `Artifacts` section).

For instructions on building the app from its source code, see [`BUILDING.md`](BUILDING.md).

## Compatibility

Current versions of SMS Import / Export should run on any Android (phone-like) device running KitKat / 4.4 (API level 19) or later, although message import and scheduled message export are only possible on devices running Marshmallow / 6.0 (API level 23) or later.

The app is tested primarily on stock Android and [LineageOS](https://lineageos.org/), but should generally run on other versions of Android as well.

## Usage

 - Import or export messages, call log, or contacts: Click the respective button, then select an import or export source or destination.
 
 - Wipe messages: Click the `Wipe Messages` button, then confirm by pressing the `Wipe` button in the pop-up dialog box.

These operations may take some time for large numbers of messages, calls, or contacts. The app will report the total number of SMS and MMS messages, calls, or contacts imported or exported, and the elapsed time, upon successful conclusion.

By default, binary MMS data (such as images and videos) are exported. The user can choose to exclude them, which will often result in a much smaller ZIP file.

Note that upon import or wipe, message apps present on the system may not immediately correctly reflect the new state of the message database due to app caching and / or local storage. This can be resolved by clearing such cache and storage, e.g. `Settings / Apps / Messaging / Storage & cache / Clear storage | Clear cache`.

## Import / Export Locations

SMS Import / Export does all input and output via the Android [Storage Access Framework (SAF)](https://developer.android.com/guide/topics/providers/document-provider). The app should thus be able to import from and export to any location available via the SAF, including both local storage (internal, SD card, or USB attached) as well as cloud storage accessible through the SAF, via either a dedicated app (e.g., the [Nextcloud Android App](https://github.com/nextcloud/android)) or [Rclone](https://rclone.org/) through [RSAF](https://github.com/chenxiaolong/RSAF).

### Encryption

[SMS Import / Export does not have any internal encryption / decryption functionality](https://github.com/tmo1/sms-ie/issues/82), and [there are currently no plans to add such functionality](https://github.com/tmo1/sms-ie/issues/82#issuecomment-1908098763). Instead, the currently recommended method for automatic encryption / decryption is to use an [Rclone crypt remote](https://rclone.org/crypt/) via [RSAF](https://github.com/chenxiaolong/RSAF) to transparently encrypt data as it is exported and decrypt it as it is imported. (The RSAF developer explains how to do this [here](https://github.com/tmo1/sms-ie/issues/82#issuecomment-1907097444), but cautions that he would only suggest this method for those already familiar with Rclone.) [Note](https://github.com/tmo1/sms-ie/issues/82#issuecomment-1908772982) that this method will only work for internal storage or cloud storage accessible via Rclone, but not for SD card or USB attached storage.

## Importing Messages

### Subscription IDs

SMS Import / Export tries to preserve as much data and metadata as possible upon import. Android includes a `sub_id` ([Subscription ID](https://developer.android.com/training/articles/user-data-ids#accounts)) field in both [SMS](https://developer.android.com/reference/android/provider/Telephony.TextBasedSmsColumns) and [MMS](https://developer.android.com/reference/android/provider/Telephony.BaseMmsColumns#SUBSCRIPTION_ID) message metadata. Earlier versions of the app included these `sub_id`s when importing, but this can cause messages to disappear on Android 14 ([issue #128](https://github.com/tmo1/sms-ie/issues/128), [Reddit](https://old.reddit.com/r/android_beta/comments/15mzaij/sms_backup_and_restore_issues/)), so the current default is to set all `sub_id`s to `-1` upon import ([negative values indicate that "the sub id cannot be determined"](https://developer.android.com/reference/android/provider/Telephony.TextBasedSmsColumns#SUBSCRIPTION_ID)). The old behavior is still available via a settings toggle.

Additionally, some MMS part metadata apparently contain a `sub_id` field as well (despite the absence of any mention of this in [the API documentation](https://developer.android.com/reference/android/provider/Telephony.Mms.Part)), and [attempting to import these `sub_id`s can cause the app to crash](https://github.com/tmo1/sms-ie/issues/142). These `sub_id`s are currently handled the same way as the ones in the SMS and MMS metadata.

### Deduplication

SMS Import / Export can attempt to deduplicate messages upon import. If this feature is enabled in the app's settings, the app will check all new messages against the existing message database (including those messages already inserted earlier in the import process) and ignore those it considers to be duplicates of ones already present. This feature is currently considered experimental.

If this feature is not enabled, no deduplication is done. For example, if messages are exported and then immediately reimported, the device will then contain two copies of every message. To avoid this, the device can be wiped of all messages before importing by using the `Wipe Messages` button.

SMS Import / Export cannot directly deduplicate messages already present in the Android database, but it should be possible to use the app to perform such deduplication by first exporting messages, then wiping messages, and finally re-importing the exported messages.

#### Implementation

Message deduplication is tricky, since on the one hand, unlike email messages, SMS and MMS messages do not generally have a unique [`Message-ID`](https://en.wikipedia.org/wiki/Message-ID), while on the other hand, some message metadata (e.g., [`THREAD_ID`](https://developer.android.com/reference/android/provider/Telephony.TextBasedSmsColumns#THREAD_ID)) does not remain constant when messages are moved around, and some metadata is not present for all messages. SMS Import / Export therefore tries to identify duplicate messages by comparing carefully chosen message data and metadata fields and concludes that two messages are identical if the compared fields are. Currently, SMS messages are assumed to be identical if they have identical [`ADDRESS`](https://developer.android.com/reference/android/provider/Telephony.TextBasedSmsColumns#ADDRESS), [`TYPE`](https://developer.android.com/reference/android/provider/Telephony.TextBasedSmsColumns#TYPE), [`DATE`](https://developer.android.com/reference/android/provider/Telephony.TextBasedSmsColumns#DATE), and [`BODY`](https://developer.android.com/reference/android/provider/Telephony.TextBasedSmsColumns#BODY) fields, and MMS messages are assumed to be identical if they have identical [`DATE`](https://developer.android.com/reference/android/provider/Telephony.BaseMmsColumns#DATE) and [`MESSAGE_BOX`](https://developer.android.com/reference/android/provider/Telephony.BaseMmsColumns#MESSAGE_BOX) fields, plus identical [`MESSAGE_ID`](https://developer.android.com/reference/android/provider/Telephony.BaseMmsColumns#MESSAGE_ID) fields if that field is present in the new message, or identical [`CONTENT_LOCATION`](https://developer.android.com/reference/android/provider/Telephony.BaseMmsColumns#CONTENT_LOCATION) fields if that field is present in the new message and `MESSAGE_ID` is not.

This functionality has not been extensively tested, and may yield both false positives and false negatives.

### Scheduled Export

To enable the scheduled export of messages, call logs and / or contacts, enable the feature in the app's Settings, and select a time to export at and a directory to export to. (Optionally, select which of the various data types to export.) The app will then attempt to export the selected data to a new, datestamped file or files in the selected directory every day at the selected time. (See [the TODO section](#todo) below.)

#### Running As A Foreground Service

On recent versions of Android, scheduled exports that export many MMS messages or that run for more than ten minutes may be killed by the system. To avoid this, scheduled exports can be run as a foreground service, which requires disabling battery optimizations for the app. (See [issue #129](https://github.com/tmo1/sms-ie/issues/129) / [PR #131](https://github.com/tmo1/sms-ie/pull/131).)

#### Retention

When scheduled exports are enabled, the following options can be used to control retention:

 - `Delete old exports` - If this option is not enabled (the default), then any old exports will be left untouched (i.e., all exports are retained indefinitely). If it is enabled, then for each data type (contacts, call log, and messages), upon successful export, the app will try to delete any old exports (i.e., all files with names of the form `<data-type>-<yyyy-MM-dd>.[zip|json]`, where `<data-type>` is the data type successfully exported, and `<yyyy-MM-dd>` is a datestamp). Selective retention of a subset of old exports can be accomplished by enabling this option in conjunction with the use of external software with snapshotting and selective retention functionality, such as [rsnapshot](https://rsnapshot.org/) or [borg](https://borgbackup.readthedocs.io/en/stable/usage/prune.html), running either on the local device, or on a system to which the exports are synced via software such as [Syncthing](https://syncthing.net/). This software should be scheduled to run between exports, and configured to preserve copies of the previous exports before the app deletes them following its next scheduled exports.
 
 - `Remove datestamps from filenames` - Scheduled exports are always initially created with filenames of the form `<data-type>-<yyyy-MM-dd>.[zip|json]`. If this option is enabled (in addition to the previous one), then after attempting to delete all old exports (of the relevant data type), the app will then attempt to remove the datestamp from the current export's filename by renaming it to `<data-type>.[zip|json]`. This is intended to make successive exports appear to be different versions of the same file, which may be useful in conjunction with external software that implements some form of file versioning, such as [Syncthing](https://docs.syncthing.net/users/versioning.html) or [Nextcloud](https://docs.nextcloud.com/server/latest/user_manual/en/files/version_control.html).

### Permissions

To export messages, permission to read SMSs and Contacts is required (the need for the latter is explained below). The app will ask for these permissions on startup, if it does not already have them.

To import or wipe messages, SMS Import / Export must be the default messaging app. This is due to [an Android design decision](https://android-developers.googleblog.com/2013/10/getting-your-sms-apps-ready-for-kitkat.html).

> [!WARNING] 
> **While an app is the default messaging app, it takes full responsibility for handling incoming SMS and MMS messages, and if does not store them, they will be lost. SMS Import / Export ignores incoming messages, so in order to avoid losing such messages, the device it is running on should be disconnected from the network (by putting it into airplane mode, or similar means) before the app is made the default messaging app, and only reconnected to the network after a proper messaging app is made the default.**

To export call logs, permission to read Call Logs and Contacts is required (the need for the latter is explained below). Currently, the app does not ask permission to read Call Logs, and it must be granted by the user on his own initiative.

To import call logs, permission to read and write Call Logs is required.

To export contacts, permission to read Contacts is required.

To import contacts, permission to write Contacts is required. (Granting the app permission to access Contacts grants both read and write permission, although if the app is upgraded from an earlier version which did not declare that it uses permission to write Contacts, then it may be necessary to deny and re-grant Contacts permission in order to enable permission to write Contacts.)

To post notifications regarding the result(s) of a scheduled export run, permission to post notifications is required on Android 13 (API level 33) and later.

To run scheduled exports as a foreground service, permission to disable battery optimizations for the app is required (see [Running As A Foreground Service](#running-as-a-foreground-service)).

### Contacts

SMS and MMS messages include phone numbers ("addresses") but not the names of the communicating parties. The contact information displayed by Android is generated by cross-referencing phone numbers with the device's Contacts database. When exporting messages, SMS Import / Export does this cross-referencing in order to include the contact names in its output; this is why permission to read Contacts in necessary. When importing, included contact names are ignored, since the app (at least currently) does not add entries to or modify the Android Contacts database during message import. The best way to maintain the association of messages with contacts is to separately transfer contacts to the device into which SMS Import / Export is importing messages, via either SMS Import / Export's contacts export / import functionality or Android's built in contacts export / import functionality. Contacts cross-referencing is performed for call log export as well, despite the fact that call log metadata will often already include the contact name; see below for a discussion of this point.

## (ND)JSON Structure

Following is the structure of the (ND)JSON currently exported by SMS Import / Export; this is subject to change in future versions of the app.

### Messages

The exported NDJSON is a series of lines, each consisting of a JSON object representing a message, SMSs followed by MMSs. Each JSON message object contains a series of tag-value pairs taken directly from Android's internal message data / metadata structures, documented in the Android API Reference: [SMS](https://developer.android.com/reference/android/provider/Telephony.TextBasedSmsColumns), [MMS](https://developer.android.com/reference/android/provider/Telephony.BaseMmsColumns). In addition, SMS Import / Export adds some other tag-value pairs and child JSON objects, as described below. (All tags added by the app to message JSON objects and their children are prefixed with a double underscore ("__") to clearly indicate that they have been added by the app and are not present in Android's message structures.)

#### SMS Messages

In SMS messages, the value of `type` specifies (among other things) the direction of the message: the two most common values are `1`, denoting "inbox" (i.e., received), and `2`, denoting "sent".

SMS messages contain a single `address` tag; depending on the message direction, this is either the sender or receiver address. SMS Import / Export attempts to look up the address in the Android Contacts database. If this is successful, a tag-value pair of the form `"__display_name": "Alice"` is added to the SMS message object.

#### MMS Messages

MMS message objects have the following additions to the tag-value pairs of their internal Android MMS representation:

 - A tag-value pair of the form `"__sender_address": { ... }`
 
 - A tag-value pair of the form `"__recipient_addresses": [ { ... }, { ... } ]`. The child JSON objects associated with `__sender_address` and `__recipient_addresses` contain a series of tag-value pairs taken directly from Android's internal MMS address structure, documented [here](https://developer.android.com/reference/android/provider/Telephony.Mms.Addr), plus possibly a single added tag-value pair of the form `"__display_name": "Alice"`, as with SMS messages.
 
 - A tag-value pair of the form `"__parts": [ { ... }, { ... }]`, where the child JSON objects contain a series of tag-value pairs taken directly from Android's internal MMS part structure, documented [here](https://developer.android.com/reference/android/provider/Telephony.Mms.Part).
 
Android stores binary data of MMS parts as individual files in its filesystem. SMS Import / Export copies these files directly into a `data/` directory in the ZIP file, retaining their original filenames (without the full path). The association of these files with MMS parts is based on the values of the [`_DATA`](https://developer.android.com/reference/android/provider/Telephony.Mms.Part#_DATA) tags of the MMS parts. (SMS Import / Export utilizes only the actual filename (the last segment of the path) for this association. If there is [a problem accessing the binary data](https://github.com/tmo1/sms-ie/issues/42), then the data may not be present.)

### Call Logs

The exported JSON is an array of JSON objects representing calls. Each JSON call object contains a series of tag-value pairs taken directly from Android's internal call metadata structures, documented in the [Android API Reference](https://developer.android.com/reference/android/provider/CallLog.Calls). In addition, SMS Import / Export will try to add a `display-name` tag, as with SMS and MMS messages. The call logs may already have a `CACHED_NAME` (`name`) field, but the app will still try to add a `display-name`, since [the documentation of the `CACHED_NAME` field](https://developer.android.com/reference/android/provider/CallLog.Calls#CACHED_NAME) states:

> The cached name associated with the phone number, if it exists.
>
> This value is typically filled in by the dialer app for the caching purpose, so it's not guaranteed to be present, and may not be current if the contact information associated with this number has changed.

### Contacts

As explained in [the official documentation](https://developer.android.com/guide/topics/providers/contacts-provider), Android stores contacts in a complex system of three related database tables:

 - [`ContactsContract.Contacts`](https://developer.android.com/reference/android/provider/ContactsContract.Contacts): Rows representing different people, based on aggregations of raw contact rows.
 
 - [`ContactsContract.RawContact`](https://developer.android.com/reference/android/provider/ContactsContract.RawContacts): Rows containing a summary of a person's data, specific to a user account and type. 
 
 - [`ContactsContract.Data`](https://developer.android.com/reference/android/provider/ContactsContract.Data): Rows containing the details for raw contact, such as email addresses or phone numbers.
 
SMS Import / Export simply dumps these tables in structured JSON format, resulting in a rather cluttered representation of the data with a great deal of repetition and redundancy. This is in accordance with the design principles of the app, which prioritize making sure that no useful information is excluded from the export, and avoiding the code complexity and coding time that would be necessary to filter and / or reorganize the raw data.

The exported JSON is an array of JSON objects representing aggregated contacts, each containing a series of tag-value pairs taken directly from the `Contacts` table. To each contact JSON object, a tag-value pair of the form `"raw_contacts": [ { ... }, { ... }]` is added, where the child JSON objects represent the (aggregated) contacts' associated raw contacts, and each contain a series of tag-value pairs taken directly from the `RawContacts` table. To each raw contact JSON object, a tag-value pair of the form `"contacts_data": [ { ... }, { ... }]` is added, where the child JSON objects represent the raw contacts' associated data (i.e., the actual details of the contacts, such as phone numbers, postal mail addresses, and email addresses), and each contain a series of tag-value pairs taken directly from the `Data` table.

Currently, [social stream data](https://developer.android.com/guide/topics/providers/contacts-provider#SocialStream), [contact groups](https://developer.android.com/guide/topics/providers/contacts-provider#Groups), and [contact photos](https://developer.android.com/guide/topics/providers/contacts-provider#Photos) are not exported.

Contacts import and export is currently considered experimental, and the JSON format is subject to change.

**Note:** Currently, when contacts are exported and then imported, the app may report a larger total of contacts imported than exported. This is due to the fact that when exporting, the total number of **`Contacts`** exported is reported (since this is a logical and straightforward thinng to do), whereas when importing, the total number of **`Raw Contacts`** imported is reported (since [as per the documentation](https://developer.android.com/guide/topics/providers/contacts-provider#ContactBasics), applications are not allowed to add `Contacts`, only `Raw Contacts`, and as noted above, a `Contact` may consist of an aggregation of multiple `Raw Contacts`).

## Limitations

### Contacts

Contacts import only imports basic contact data (name, phone numbers, email and postal addresses, etc.), but not the contacts metadata that Android stores. Additionally, imported contacts are not associated with [the accounts with which they had been associated](https://developer.android.com/guide/topics/providers/contacts-provider#InformationTypes) on the system from which they were exported, and the user has no control over which account they will be associated with on the target system; all contacts are inserted into the target system's default account.

### Call logs

Voicemail entries are skipped on call log import (see [issue #110](https://github.com/tmo1/sms-ie/issues/110)).

### Call Log Maximum Capacity

Although this is apparently not publicly officially documented, Android's Call Log has a fixed maximum number of calls that it will store ([500 in many / most versions of Android](https://android.gadgethacks.com/how-to/bypass-androids-call-log-limits-keep-unlimited-call-history-0175494/), 1000 in API 30 (version 11) on a Pixel [my own experience, corroborated [here](https://stackoverflow.com/questions/70501885/the-max-of-incoming-outgoing-or-missed-calls-logs-in-android)]).

Earlier versions of this document stated that:

> Attempting to import calls when the log is full may fail, in which case the app will not report an error, but the reported number of imported calls will be lower then the number of calls provided for import. E.g., if calls are exported from a phone with a full log, and the output file is then imported to the same phone, the app will report 0 calls imported.

This was a misinterpretation of observed call import failures, which were actually caused by [a bug in the app](https://github.com/tmo1/sms-ie/issues/63), which has since been [fixed](https://github.com/tmo1/sms-ie/commit/718e8b214c03be5858d161860377604c2da7e1db).

## Bugs, Feature Requests, and Other Issues

Bugs, feature requests, and other issues can be filed at [the SMS Import / Export issue tracker](https://github.com/tmo1/sms-ie/issues). When reporting any problem with the app, please try to reproduce the problem with [the latest release of the app](https://github.com/tmo1/sms-ie/releases), and please specify the versions of the app used for export and / or import, as applicable.

### Posting JSON / ZIP Files

When reporting a problem with import or export functionality, please try to include the (ND)JSON file involved, in accordance with the following guidelines:

#### Minimal Reproducible Example

Please try to reproduce the problem with as small a (ND)JSON file as possible. The simplest way to reduce the size of the file is to use the app's `Settings / Debugging options / Maximum records ...` option to export only a small number of messages.

#### Redaction

It is strongly recommended to redact any posted (ND)JSON and remove any sensitive information. To help automate this process (currently, for message collections only), a Python script [`redact-messages.py`](/tools/redact-messages.py) is available. It has no external dependencies beyond a standard Python environment. It expects a collection of messages in the NDJSON format used by SMS Import / Export on standard input, and writes a redacted version of the same to standard output:

```
~$ ./redact-messages.py < messages.ndjson > messages-redacted.ndjson
```

**:warning:There is no guarantee that this script will correctly and completely redact all sensitive information. It as provided as is, with no warranty. If the JSON in question contains any particularly sensitive information, do not rely on this script to redact it. Note that the script does not consider sensitive certain metadata, such as message timestamps, that might be considered sensitive in some contexts.**

### Logcat

When reporting a problem, particularly a reproducible one, please attach a logcat (a collection of log messages produced by Android - see [here](https://developer.android.com/studio/command-line/logcat) and [here](https://developer.android.com/studio/debug/am-logcat)). If feasible, please reproduce the problem in a debug build of the latest code (see the [Installation](#intallation) section of this README for an easy way to obtain such a build) and include the logcat from that, since the debug builds have more detailed logging. Instructions for obtaining a logcat (with increasing level of detail) can be found [here](https://wiki.lineageos.org/how-to/logcat), [here](https://f-droid.org/en/docs/Getting_logcat_messages_after_crash/), and [here](https://www.xda-developers.com/guide-sending-a-logcat-to-help-debug-your-favorite-app/).

### Known Issues

#### MIUI

When importing messages that have been exported from a MIUI system into a MIUI system, [the following error may be encountered](https://github.com/tmo1/sms-ie/issues/103):
```
java.lang.IllegalArgumentException: The non-sync-callers AND non-blocked-url should not specify DELETED for inserting.
```
For a possible solution, see [here](https://github.com/tmo1/sms-ie/issues/103#issuecomment-1620890135).

## Translations

SMS Import / Export has been translated (from the original English) into the following languages (note that some of these translations may contain inaccuracies, due to changes to the app's original text since they were made):
 
<a href="https://hosted.weblate.org/engage/sms-import-export/">
	<img src="https://hosted.weblate.org/widgets/sms-import-export/-/ui-strings/multi-auto.svg" alt="Translation status" />
</a>
  
To add a translation into a new language, or to correct, update, or improve an existing translation, see [here](CONTRIBUTING.md).
 
## TODO

The following are various features and improvements to the app that have been suggested and may be implemented in the future:

 - Greater flexibility of scheduled exporting, including intervals other than daily, incremental / differential exporting, and retention handling (discussion in [issue #7](https://github.com/tmo1/sms-ie/issues/7))

## Contributing

For information about contributing to SMS Import / Export, and a list of contributors, see [here](CONTRIBUTING.md).

## Privacy

SMS Import / Export does no tracking, advertising, or phoning home. No user data is stored or transmitted anywhere except as explicitly designated by the user.

## sms-db

SMS Import / Export is a sibling project to [sms-db](https://github.com/tmo1/sms-db), a Linux tool to build an SQLite database out of collections of SMS and MMS messages in various formats. sms-db will hopefully eventually be able to import ZIP files created by SMS Import / Export, and to export its database to ZIP files that can be imported by SMS Import / Export.

## Background

Coming from a procedural, command line interface, synchronous, Linux, Perl and Python background, the development of SMS Import / Export served as a crash course in object-oriented, graphical user interface, asynchronous, Android, Kotlin programming, and consequently entailed a fair amount of amateurishness and cargo cult programming. After much work and learning, however, the app does seem to function correctly and effectively.

## Donations

SMS Import / Export is absolutely free software, and there is no expectation of any sort of compensation or support for the project. That being said, if anyone wishes to donate (to Thomas More, the app's primary author), this can be done via [the Ko-fi platform](https://ko-fi.com/thomasmore).

## License

SMS Import / Export is free / open source software, released under the terms of the [GNU GPLv3](https://www.gnu.org/licenses/gpl-3.0.en.html) or later.
