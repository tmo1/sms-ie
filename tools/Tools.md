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
