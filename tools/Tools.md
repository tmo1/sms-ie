# Tools

This directory contains several command line tools intended to be used in conjunction with SMS Import / Export. They are written in Python 3, and have no external dependencies beyond Python itself. They have been developed and tested on Linux, although they will likely run in any Python environment with little or no modification. They have not been extensively tested, and should be considered experimental.

### `redact-messages.py`

See [here](../README.md#redaction) for documentation of this script.

### `v1-v2-convert.py`

This script converts message files in SMS I/E `v1` format to `v2` format. Usage:

`v1-v2-convert.py <messages-xxx.json>`

This will read messages from <messages-xxx.json> and write them to <messages-xxx.zip>.

### `silence-convert.py`

This script converts SMS messages in [Silence](https://silence.im/) XML format to SMS I/E `v2` format. Usage:

`silence-convert.py <silence-xxx.xml>`

This will read messages from <silence-xxx.xml> and write them to <silence-xxx.zip>.

> [!WARNING]
> This script uses the Python ElementTree XML API, [which "is not secure against maliciously constructed data"](https://docs.python.org/3/library/xml.etree.elementtree.html). It should only be used on trusted XML.

> [!NOTE]
> [Silence produces invalid XML](https://git.silence.dev/Silence/Silence-Android/-/issues/317) when encoding certain characters (such as emojis). This will cause the converter to fail with an error message ending in a line like this:

`xml.etree.ElementTree.ParseError: reference to invalid character number: line nnn, column mmm`

If this error is encountered, first use the XML fixer tool to produce valid XML:

`silence-xml-fixer.py < silence-xxx.xml > silence-xxx-fixed.xml`

then run the converter on the fixed XML:

`silence-convert.py <silence-xxx-fixed.xml>`

(See [issue #121](https://github.com/tmo1/sms-ie/issues/121).)

## v1 Conversion Tools

The following tools convert messages in other formats to SMS I/E `v1` format, and have not yet been updated to convert to `v2` format. It should be possible, however, to convert their output to `v2` format via `v1-v2-convert.py`.

### `vmg-convert.py`

This script converts SMS messages in Nokia's VMG format to SMS I/E compatible JSON. To use it, prepare a directory (e.g. `vmgs`) containing some VMG files (and nothing else), then run:

`vmg-convert.py vmgs > converted-vmg-messages.json`

(See [issue #93](https://github.com/tmo1/sms-ie/issues/93).)

### `csv-convert.py`

This script converts SMS messages in CSV format to SMS I/E compatible JSON. The input CSV file must have a first record header containing the field names, which must be [the exact ones used by Android](https://developer.android.com/reference/android/provider/Telephony.TextBasedSmsColumns#constants_1), and the values in all subseqent rows must be of the type and in the format used by Android. To use, run:

`csv-convert.py < messages.csv > messages.json`

(See [issue #100](https://github.com/tmo1/sms-ie/issues/100).)

## Contributed Tools

The `tools/contrib` directory contains tools for use with SMS I/E that have been contributed by outside developers to the SMS I/E project.

### [`messages_browser.py`](contrib/messages_browser.py)

This is a platform independent utility to browse collections of SMS and MMS messages (included binary MMS attachments) exported by SMS I/E (in the `v2` ZIP file format). The messages are displayed similarly to how they are displayed by the standard Android "Messaging" app.

To use, run:

`messages_browser.py messages-xxx.zip`

and then visit `http://127.0.0.1:8222` in a web browser.

### [`nokia-suite-convert.pl`](contrib/nokia-suite-convert.pl)

This is an utility to convert SMS export file as made by Nokia Suite (in CSV format) into CSV format that can be parsed by [csv-convert.py](#csv-convert.py) above.
Requires:
- perl
- Text::CSV (in Debian and alike libtext-csv-perl)
- POSIX::strptime (in Debian and alike libposix-strptime-perl)

Usage:
 
`nokia-suite-convert.pl [input.csv [output.csv]]`

- if output file is omitted, standard output is used
- if both files are omitted, standard input/output are used

## External Tools

This section lists tools for use with SMS I/E that have been developed, and are distributed, by outside developers. Descriptions of the tools are taken from their documentation:

 - [Call Log Analyzer](https://github.com/guruor/analyze-call-logs): "This simple and intuitive web app lets you visualize your call logs in a beautiful chart format." (Processes call logs exported by SMS I/E.)
