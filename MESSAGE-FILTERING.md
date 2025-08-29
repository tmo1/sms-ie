# Message Filtering

Sms Import / Export currently implements a rudimentary but powerful mechanism for filtering messages upon export. In order to use this mechanism effectively, it is important to understand how Android stores messages internally and how SMS I/E accesses them.

## Background

Android stores both SMS and MMS messages in an [SQLite](https://en.wikipedia.org/wiki/SQLite) database. For SMSs messages, a single table contains both the message data (in the `body` column) and all its metadata (in various other columns). (The name of the other party to the message is not stored in the message table, since a message contains only an "address" (phone number) but not any sort of contact name. Android associates a name with the message via user provided contact information, and this information is stored in a different database.)

For MMS messages, however, the situation is more complicated. The main MMS message table contains most of the message metadata (including the dates the message was sent and received in the `date_sent` and `date` columns (present in the SMS message table as well) respectively), but not the sender and recipient "addresses" or the actual message data ("parts," both text and binary), which are stored in different tables.

Android provides a [`ContentProvider`](https://developer.android.com/guide/topics/providers/content-provider-basics) API for accessing SMS and MMS messages, which functions as a thin abstraction layer over the underlying SQLite API. Due to the previously described internal message storage architecture, for SMS messages. SMS I/E executes a single query to retrieve all data and metadata of all messages (except for the contact names of the messages' other parties, which requires additional queries since they are stored in a separate database, as above). For MMS messages, however, SMS I/E initially executes a single query to retrieve the message metadata (of all messages) that is stored in the main MMS table, but then executes additional queries for each message to retrieve its sender and recipient "addresses" and its "parts."

**The current message filtering mechanism is implemented by constructing an SQLite `WHERE` clause out of user-specified filters, which is used with the queries of the main SMS and MMS tables.** Consequently, SMS messages can be filtered based on their data and (in principle) any of their metadata, whereas MMS messages can be filtered based only on their metadata that is present in the main MMS table.

## Usage

To use message filtering, it must be enabled in the app's Settings (under Export Settings), and one or more filters must be configured (and set to `Active`) using the Message Filtering interface. When an export (manual or scheduled) is executed, the app will combine all active filters using the SQLite `AND` keyword and use the result as an SQLite `WHERE` clause. (A filter that is not currently desired but may be desired in the future can be set to `Inactive` rather than deleted in order to avoid having to reconfigure it later.)

Message filters have three fields:

 - `Column name`: the name of an Android SMS or MMS column (these columns are documented here: [SMS](https://developer.android.com/reference/android/provider/Telephony.TextBasedSmsColumns), [MMS)(https://developer.android.com/reference/android/provider/Telephony.BaseMmsColumns)). If the name is prefixed by `sms.` or `mms.`, then this filter will be used only when querying the SMS or MMS table respectively (since the column in question is only present in the respective table).
 - `Operator`: an SQLite operator (these are officially documented [here](https://sqlite.org/lang_expr.html), and somewhat more readably [here](https://www.sqlitetutorial.net/sqlite-where/)).
 - `Column value`: a user-provided value.
 
> [!WARNING] The app does not perform any syntax checking of provided column values, and it is the user's responsibility to ensure that the configured filter constitutes valid SQLite syntax. If it does not, subsequent exports will fail.

## Examples

Following are some examples of message filter lists and the resulting message selections:

| Column name       | Operator | Column value                |
|-------------------|----------|-----------------------------|
| date_sent         | BETWEEN  | 1735689600000 AND 1735775999000|


all SMS and MMS messages sent between Wednesday, January 1, 2025 12:00:00 AM and Wednesday, January 1, 2025 11:59:59 PM (GMT, inclusive). (Android stores timestamps in [Unix time](https://en.wikipedia.org/wiki/Unix_time) (milliseconds since the epoch). To convert between Android timestamps and human readable dates, use (on Unix-like systems) `date -d'@<Android_timestamp>'` / `date -d<human_readable_date> +%s` (omit the last three digits of the Android timestamp in the first command, and add `000` to the output of the second command, since the `date` command works with seconds rather than milliseconds), or use an online converter [such as this one](https://www.epochconverter.com/).)

| Column name       | Operator | Column value                |
|-------------------|----------|-----------------------------|
| date              | >        | 1735689600000               |
| sms.body          | LIKE     | '%Thanks%'                  |

all SMS messages sent after Wednesday, January 1, 2025 12:00:00 AM whose bodies contain the string "Thanks", and all MMS messages sent after that time. **Be sure to include the single quotes around the column value, since [SQLite string literals must be surrounded by quotes](https://sqlite.org/lang_expr.html#literal_values_constants_).** When using the `LIKE` operator, the default [SQLite case-sensitivity semantics](https://sqlite.org/lang_expr.html#the_like_glob_regexp_match_and_extract_operators) are used.

| Column name       | Operator | Column value                |
|-------------------|----------|-----------------------------|
| sms.type          | ==       | 1                           |
| mms.msg_box       | ==       | 1                           |

all received (as opposed to sent) SMS and MMS messages. (For the meaning of the various possible values for `sms.type` and `mms.msg_box`, see [here](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/core/java/android/provider/Telephony.java#136) and [here](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/core/java/android/provider/Telephony.java#1478) respectively.)
