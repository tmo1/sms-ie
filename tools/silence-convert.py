#! /usr/bin/python3

# SMS Import / Export: a simple Android app for importing and exporting SMS and MMS messages,
# call logs, and contacts, from and to JSON / NDJSON files.
#
# Copyright (c) 2023 Thomas More
#
# This file is part of SMS Import / Export.
#
# SMS Import / Export is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# SMS Import / Export is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with SMS Import / Export.  If not, see <https://www.gnu.org/licenses/>

# This utility converts message in Silence (https://silence.im/) XML format to SMS I/E 'v2' format
# Usage: 'silence-convert.py <silence-xxx.xml>'
# This will read messages from <silence-xxx.xml> and write them to <silence-xxx.zip>.


import sys
import json
import xml.etree.ElementTree as ET
import zipfile
import os


def copy_convert(json_old):
    json_new = {}
    for k, v in json_old.items():
        if k != 'binary_data':
            new_k = '__' + k if k in custom_names else k
            new_v = [copy_convert(x) for x in v] if isinstance(v, list) else copy_convert(v) if isinstance(v, dict) else v
            json_new[new_k] = new_v
    return json_new


input_file = sys.argv[1]
tree = ET.parse(input_file)
smses = tree.getroot()
messages_ndjson = []
output_file = input_file[:-3] + 'zip' if input_file[-3:] == 'xml' else input_file + 'zip'
with (zipfile.ZipFile(output_file, mode='w') as messages_zip):
    for sms in smses:
        messages_ndjson.append(json.dumps(dict(sms.items())) + '\n')
    messages_zip.writestr('messages.ndjson', ''.join(messages_ndjson))
