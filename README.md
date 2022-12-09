# SMS Import / Export

SMS Import / Export is a simple Android app that imports and exports SMS and MMS messages, call logs, and contacts from and to JSON files. (Contacts import and export are currently functional but considered experimental.) Root is not required.

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="80">](https://f-droid.org/packages/com.github.tmo1.sms_ie/)

## Installation

SMS Import / Export is available from [Github](https://github.com/tmo1/sms-ie). The repository can be cloned and built locally, from the command line (e.g., by issuing `gradlew assembleDebug` in the root directory of the project) or within Android Studio. Prebuilt APK packages can be downloaded from the [Releases page](https://github.com/tmo1/sms-ie/releases), and are also available at [F-Droid](https://f-droid.org/packages/com.github.tmo1.sms_ie/).

## Usage

 - Import or export messages, call log, or contacts: Click the respective button, then select an import or export source or destination.
 - Wipe messages: Click the `Wipe Messages` button, then confirm by pressing the `Wipe` button in the pop-up dialog box.

These operations may take some time for large numbers of messages, calls, or contacts. The app will report the total number of SMS and MMS messages, calls, or contacts imported or exported, and the elapsed time, upon successful conclusion.

By default, binary MMS data (such as images and videos) are exported. The user can choose to exclude them, which will often result in a file that is much smaller and more easily browsable by humans. (The setting is currently ignored on import.)

### Scheduled Export

To enable the scheduled export of messages, call logs and / or contacts, enable the feature in the app's Settings, and select a time to export at and a directory to export to. (Optionally, select which of the various data types to export.) The app will then attempt to export the selected data to a new, datestamped file or files in the selected directory every day at the selected time. (See [the TODO section](#todo) below.)

#### Retention

When scheduled exports are enabled, the following options can be used to control retention:

 - `Delete old exports` - If this option is not enabled (the default), then any old exports will be left untouched (i.e., all exports are retained indefinitely). If it is enabled, then for each data type (contacts, call log, and messages), upon successful export, the app will try to delete any old exports (i.e., all files with names of the form `<data-type>-<yyyy-MM-dd>.json`, where `<data-type>` is the data type successfully exported, and `<yyyy-MM-dd>` is a datestamp). Selective retention of a subset of old exports can be accomplished by enabling this option in conjunction with the use of external software with snapshotting and selective retention functionality, such as [rsnapshot](https://rsnapshot.org/) or [borg](https://borgbackup.readthedocs.io/en/stable/usage/prune.html), running either on the local device, or on a system to which the exports are synced via software such as [Syncthing](https://syncthing.net/). This software should be scheduled to run between exports, and configured to preserve copies of the previous exports before the app deletes them following its next scheduled exports.
 - `Remove datestamps from filenames` - Scheduled exports are always initially created with filenames of the form `<data-type>-<yyyy-MM-dd>.json`. If this option is enabled (in addition to the previous one), then after attempting to delete all old exports (of the relevant data type), the app will then attempt to remove the datestamp from the current export's filename by renaming it to `<data-type>.json`. This is intended to make successive exports appear to be different versions of the same file, which may be useful in conjunction with external software that implements some form of file versioning, such as [Syncthing](https://docs.syncthing.net/users/versioning.html) or [Nextcloud](https://docs.nextcloud.com/server/latest/user_manual/en/files/version_control.html).

### Permissions

To export messages, permission to read SMSs and Contacts is required (the need for the latter is explained below). The app will ask for these permissions on startup, if it does not already have them.

To import or wipe messages, SMS Import / Export must be the default messaging app. This is due to [an Android design decision](https://android-developers.googleblog.com/2013/10/getting-your-sms-apps-ready-for-kitkat.html).

**Warning:** While an app is the default messaging app, it takes full responsibility for handling incoming SMS and MMS messages, and if does not store them, they will be lost. SMS Import / Export ignores incoming messages, so in order to avoid losing such messages, the device it is running on should be disconnected from the network (by putting it into airplane mode, or similar means) before the app is made the default messaging app, and only reconnected to the network after a proper messaging app is made the default.

To export call logs, permission to read Call Logs and Contacts is required (the need for the latter is explained below). Currently, the app does not ask permission to read Call Logs, and it must be granted by the user on his own initiative.

To import call logs, permission to read and write Call Logs is required.

To export contacts, permission to read Contacts is required.

To import contacts, permission to write Contacts is required. (Granting the app permission to access Contacts grants both read and write permission, although if the app is upgraded from an earlier version which did not declare that it uses permission to write Contacts, then it may be necessary to deny and re-grant Contacts permission in order to enable permission to write Contacts.)

### Contacts

SMS and MMS messages include phone numbers ("addresses") but not the names of the communicating parties. The contact information displayed by Android is generated by cross-referencing phone numbers with the device's Contacts database. When exporting messages, SMS Import / Export does this cross-referencing in order to include the contact names in its output; this is why permission to read Contacts in necessary. When importing, included contact names are ignored, since the app is (at least currently) not in the business of adding to or modifying the Android Contacts database. The best way to maintain the association of messages with contacts is to separately transfer contacts to the device into which SMS Import / Export is importing messages, via either SMS Import / Export's contacts export / import functionality or Android's built in contacts export / import functionality. Contacts cross-referencing is performed for call log export as well, despite the fact that call log metadata will often already include the contact name; see below for a discussion of this point.

## JSON Structure

Following is the structure of the JSON currently exported by SMS Import / Export; this is subject to change in future versions of the app.

### Messages

The exported JSON is an array of JSON objects representing messages, SMSs followed by MMSs. Each JSON message object contains a series of tag-value pairs taken directly from Android's internal message data / metadata structures, documented in the Android API Reference: [SMS](https://developer.android.com/reference/android/provider/Telephony.TextBasedSmsColumns), [MMS](https://developer.android.com/reference/android/provider/Telephony.BaseMmsColumns). In addition, SMS Import / Export adds some other tag-value pairs and child JSON objects, as described below.

#### SMS Messages

In SMS messages, the value of `type` specifies (among other things) the direction of the message: the two most common values are `1`, denoting "inbox" (i.e., received), and `2`, denoting "sent".

SMS messages contain a single `address` tag; depending on the message direction, this is either the sender or receiver address. SMS Import / Export attempts to look up the address in the Android Contacts database. If this is successful, a tag-value pair of the form `"display_name": "Alice"` is added to the SMS message object.

#### MMS Messages

MMS message objects have the following additions to the tag-value pairs of their internal Android MMS representation:

 - A tag-value pair of the form `"sender_address": { ... }`
 
 - A tag-value pair of the form `"recipient_addresses": [ { ... }, { ... } ]`. The child JSON objects associated with `sender_address` and `recipient_addresses` contain a series of tag-value pairs taken directly from Android's internal MMS address structure, documented [here](https://developer.android.com/reference/android/provider/Telephony.Mms.Addr), plus possibly a single added tag-value pair of the form `"display_name": "Alice"`, as with SMS messages.
 
 - A tag-value pair of the form `"parts": [ { ... }, { ... }]`, where the child JSON objects contain a series of tag-value pairs taken directly from Android's internal MMS part structure, documented [here](https://developer.android.com/reference/android/provider/Telephony.Mms.Part), plus, for parts containing binary data (assuming binary data inclusion is checked), a tag-value pair of the form `"binary_data": "<Base64 encoded binary data>"`. (If there is [a problem accessing the binary data](https://github.com/tmo1/sms-ie/issues/42), then this tag-value pair may not be present.)

### Call Logs

The exported JSON is an array of JSON objects representing calls. Each JSON call object contains a series of tag-value pairs taken directly from Android's internal call metadata structures, documented in the [Android API Reference](https://developer.android.com/reference/android/provider/CallLog.Calls). In addition, SMS Import / Export will try to add a `display-name` tag, as with SMS and MMS messages. The call logs may already have a `CACHED_NAME` (`name`) field, but the app will still try to add a `display-name`, since [the documentation of the `CACHED_NAME` field](https://developer.android.com/reference/android/provider/CallLog.Calls#CACHED_NAME) states:

> The cached name associated with the phone number, if it exists.
>
> This value is typically filled in by the dialer app for the caching purpose, so it's not guaranteed to be present, and may not be current if the contact information associated with this number has changed.

### Contacts

As explained in [the official documentation](https://developer.android.com/guide/topics/providers/contacts-provider), Android stores contacts in a complex system of three related database tables:

 - `[ContactsContract.Contacts](https://developer.android.com/reference/android/provider/ContactsContract.Contacts)`: Rows representing different people, based on aggregations of raw contact rows.
 - `[ContactsContract.RawContacts](https://developer.android.com/reference/android/provider/ContactsContract.RawContacts)`: Rows containing a summary of a person's data, specific to a user account and type. 
 - `[ContactsContract.Data](https://developer.android.com/reference/android/provider/ContactsContract.Data)`: Rows containing the details for raw contact, such as email addresses or phone numbers.
 
SMS Import / Export simply dumps these tables in structured JSON format, resulting in a rather cluttered representation of the data with a great deal of repetition and redundancy. This is in accordance with the design principles of the app, which prioritize making sure that no useful information is excluded from the export, and avoiding the code complexity and coding time that would be necessary to filter and / or reorganize the raw data.

The exported JSON is an array of JSON objects representing aggregated contacts, each containing a series of tag-value pairs taken directly from the `Contacts` table. To each contact JSON object, a tag-value pair of the form `"raw_contacts": [ { ... }, { ... }]` is added, where the child JSON objects represent the (aggregated) contacts' associated raw contacts, and each contain a series of tag-value pairs taken directly from the `RawContacts` table. To each raw contact JSON object, a tag-value pair of the form `"contacts_data": [ { ... }, { ... }]` is added, where the child JSON objects represent the raw contacts' associated data (i.e., the actual details of the contacts, such as phone numbers, postal mail addresses, and email addresses), and each contain a series of tag-value pairs taken directly from the `Data` table.

Currently, [social stream data](https://developer.android.com/guide/topics/providers/contacts-provider#SocialStream), [contact groups](https://developer.android.com/guide/topics/providers/contacts-provider#Groups), and [contact photos](https://developer.android.com/guide/topics/providers/contacts-provider#Photos) are not exported.

Contacts import and export is currently considered experimental, and the JSON format is subject to change.

**Note:** Currently, when contacts are exported and then imported, the app may report a larger total of contacts imported than exported. This is due to the fact that when exporting, the total number of **`Contacts`** exported is reported (since this is a logical and straightforward thinng to do), whereas when importing, the total number of **`Raw Contacts`** imported is reported (since [as per the documentation](https://developer.android.com/guide/topics/providers/contacts-provider#ContactBasics), applications are not allowed to add `Contacts`, only `Raw Contacts`, and as noted above, a `Contact` may consist of an aggregation of multiple `Raw Contacts`).

## Limitations

Currently, no deduplication is done. For example, if messages are exported and then immediately reimported, the device will then contain two copies of every message. To avoid this, the device can be wiped of all messages before importing by using the `Wipe Messages` button.

Contacts import only imports basic contact data (name, phone numbers, email and postal addresses, etc.), but not the contacts metadata that Android stores. Additionally, imported contacts are not associated with [the accounts with which they had been associated](https://developer.android.com/guide/topics/providers/contacts-provider#InformationTypes) on the system from which they were exported, and the user has no control over which account they will be associated with on the target system; all contacts are inserted into the target system's default account.

### Call Log Maximum Capacity

Although this is apparently not publicly officially documented, Android's Call Log has a fixed maximum number of calls that it will store ([500 in many / most versions of Android](https://android.gadgethacks.com/how-to/bypass-androids-call-log-limits-keep-unlimited-call-history-0175494/), 1000 in API 30 (version 11) on a Pixel [my own experience, corroborated [here](https://stackoverflow.com/questions/70501885/the-max-of-incoming-outgoing-or-missed-calls-logs-in-android)]).

Earlier versions of this document stated that:

> Attempting to import calls when the log is full may fail, in which case the app will not report an error, but the reported number of imported calls will be lower then the number of calls provided for import. E.g., if calls are exported from a phone with a full log, and the output file is then imported to the same phone, the app will report 0 calls imported.

This was a misinterpretation of observed call import failures, which were actually caused by [a bug in the app](https://github.com/tmo1/sms-ie/issues/63), which has since been [fixed](https://github.com/tmo1/sms-ie/commit/718e8b214c03be5858d161860377604c2da7e1db).

## Bugs, Feature Requests, and Other Issues

Bugs, feature requests, and other issues can be filed at [the SMS Import / Export issue tracker](https://github.com/tmo1/sms-ie/issues). When reporting any problem with the app, please try to reproduce the problem with [the latest release of the app](https://github.com/tmo1/sms-ie/releases), and please specify the versions of the app used for export and / or import, as applicable.

### Posting JSON

When reporting a problem with import or export functionality, please try to include the JSON involved, in accordance with the following guidelines:

#### Minimal Reproducible Example

Please try to reproduce the problem with as small a JSON file as possible. The simplest way to reduce the size of the JSON is to use the app's `Settings / Debugging options / Maximum records ...` option to export only a small number of messages.

#### Redaction

It is strongly recommended to redact any posted JSON and remove any sensitive information. To help automate this process (currently, for message collections only), a Python script [`redact-messages.py`](/tools/redact-messages.py) is available. It has no external dependencies beyond a standard Python environment. It expects a collection of messages in the JSON format used by SMS Import / Export on standard input, and writes a redacted version of the same to standard output:

```
~$ ./redact-messages.py < messages-nnnn-nn-nn.json > messages-redacted-nnnn-nn-nn.json
```

**:warning:There is no guarantee that this script will correctly and completely redact all sensitive information. It as provided as is, with no warranty. If the JSON in question contains any particularly sensitive information, do not rely on this script to redact it. Note that the script does not consider sensitive certain metadata, such as message timestamps, that might be considered sensitive in some contexts.**

### Crashes

When reporting a crash, particularly a reproducible one, please attach a logcat (a collection of log messages produced by Android - see [here](https://developer.android.com/studio/command-line/logcat) and [here](https://developer.android.com/studio/debug/am-logcat)). Instructions for doing so (with increasing level of detail) can be found [here](https://wiki.lineageos.org/how-to/logcat), [here](https://f-droid.org/en/docs/Getting_logcat_messages_after_crash/), and [here](https://www.xda-developers.com/guide-sending-a-logcat-to-help-debug-your-favorite-app/).

## Translations

SMS Import / Export has been translated (from the original English) into the following languages (note that some of these translations may contain inaccuracies, due to changes to the app's original text since they were made):

<!---
 - German
 - French
 - Norwegian Bokmål
 - Simplified Chinese
 - Portuguese
 - Hebrew
 - Russian
 - Italian
 - Polish
--->
 
<a href="https://hosted.weblate.org/engage/sms-import-export/">
	<img src="https://hosted.weblate.org/widgets/sms-import-export/-/ui-strings/multi-auto.svg" alt="Translation status" />
</a>
  
To add a translation into a new language, or to correct, update, or improve an existing translation, see the [Contributions](#contributions) section below.
 
## TODO

The following are various features and improvements to the app that have been suggested and may be implemented in the future:

 - Greater flexibility of scheduled exporting, including intervals other than daily, incremental / differential exporting, and retention handling (discussion in [issue #7](https://github.com/tmo1/sms-ie/issues/7))

## Contributions

Code can be contributed via [pull request](https://github.com/tmo1/sms-ie/pulls), but for any substantial changes or additions to the existing codebase, please first [open an issue](https://github.com/tmo1/sms-ie/issues) to discuss the proposed changes or additions. All contributed code should be licensed under the [GNU GPLv3](https://www.gnu.org/licenses/gpl-3.0.en.html) or later.

SMS Import / Export is integrated with [Weblate](https://weblate.org). Translations into new languages, as well as corrections, updates, and improvements to existing translations, can be submitted via [SMS Import / Export @ Hosted Weblate](https://hosted.weblate.org/engage/sms-import-export/), or by ordinary [pull requests against the SMS Import / Export repository](https://github.com/tmo1/sms-ie/pulls).

The primary author of SMS Import / Export is [Thomas More](https://github.com/tmo1). The following individuals have contributed to the app:

 - [sxwxs](https://github.com/sxwxs): call log export support
 - [Bindu (vbh)](https://github.com/vbh): call log import support
 - [nautilusx](https://github.com/nautilusx): German translation
 - [AntoninDelFabbro](https://github.com/AntoninDelFabbro): French translation (and assistance with the German one)
 - [baitmooth](https://github.com/baitmooth): additions to German translation
 - [Jan Hustak (codingjourney)](https://github.com/codingjourney): [bug fix](https://github.com/tmo1/sms-ie/pull/30)
 - [Allan Nordhøy (comradekingu)](https://github.com/comradekingu): Norwegian Bokmål translation
 - poi: Simplified Chinese translation
 - [Dani Wang (EpicOrange)](https://github.com/EpicOrange): [bug fix](https://github.com/tmo1/sms-ie/pull/39)
 - [Onno van den Dungen (Donnno)](https://github.com/Donnnno): Application icon
 - [Merlignux](https://github.com/Merlignux): Portuguese translation
 - [Eric (hamburger1024)](https://hosted.weblate.org/user/hamburger1024/): updates to Simplified Chinese translation
 - [Shopimisrel](https://github.com/Shopimisrel): Hebrew translation
 - [Артём (Artem13327)](https://hosted.weblate.org/user/Artem13327/): Russian translation
 - [pjammo](https://github.com/pjammo): Italian translation
 - [jacek (TX5400)](https://hosted.weblate.org/user/TX5400/): Polish translation
 - [gallegonovato](https://github.com/gallegonovato): Spanish translation
 - [Bai (Baturax)](https://github.com/Baturax): Turkish translation
 - [Philippe (Philippe213)](https://hosted.weblate.org/user/philippe213/): Update to French translation
 - [Oğuz Ersen (ersen0)](https://github.com/ersen0): Update to Turkish translation

## Privacy

SMS Import / Export does no tracking, advertising, or phoning home. No user data is stored or transmitted anywhere except as explicitly designated by the user.

## sms-db

SMS Import / Export is a sibling project to [sms-db](https://github.com/tmo1/sms-db), a Linux tool to build an SQLite database out of collections of SMS and MMS messages in various formats. sms-db can import JSON files created by SMS Import / Export, and it can export its database to JSON files that can be imported by SMS Import / Export.

## Background

Coming from a procedural, command line interface, synchronous, Linux, Perl and Python background, the development of SMS Import / Export served as a crash course in object-oriented, graphical user interface, asynchronous, Android, Kotlin programming, and consequently entailed a fair amount of amateurishness and cargo cult programming. After much work and learning, however, the app does seem to function correctly and effectively.

## Donations

SMS Import / Export is absolutely free software, and there is no expectation of any sort of compensation or support for the project. That being said, if anyone wishes to donate (to Thomas More, the app's primary author), this can be done via [the Ko-fi platform](https://ko-fi.com/thomasmore).

## License

SMS Import / Export is free / open source software, released under the terms of the [GNU GPLv3](https://www.gnu.org/licenses/gpl-3.0.en.html) or later.
