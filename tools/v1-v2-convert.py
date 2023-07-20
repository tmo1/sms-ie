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

# This utility converts message JSON files from the format used by SMS-IE versions prior to 2.0.0 to the format used by
# version 2.0.0 and later.
#
# Usage: v1-v2-convert.py <messages-xxx.json>
# This will read messages from <messages-xxx.json> and write them to <messages-xxx.zip>.


import sys
import json
import zipfile
import base64
import os

custom_names = {'display_name', 'parts', 'addresses', 'sender_address', 'recipient_addresses'}


def copy_convert(json_old):
    json_new = {}
    for k, v in json_old.items():
        if k != 'binary_data':
            new_k = '__' + k if k in custom_names else k
            new_v = [copy_convert(x) for x in v] if isinstance(v, list) else copy_convert(v) if isinstance(v, dict) else v
            json_new[new_k] = new_v
    return json_new


input_file = sys.argv[1]
with open(input_file) as mf:
    messages_json = json.load(mf)
messages_ndjson = []
output_file = input_file[:-4] + 'zip' if input_file[-4:] == 'json' else input_file + 'zip'
with (zipfile.ZipFile(output_file, mode='w') as messages_zip):
    for message in messages_json:
        messages_ndjson.append(json.dumps(copy_convert(message)) + '\n')
    messages_zip.writestr('messages.ndjson', ''.join(messages_ndjson))
    for message in messages_json:
        if 'parts' in message:
            for part in message['parts']:
                if 'binary_data' in part:
                    messages_zip.writestr('data/' + os.path.split(part['_data'])[1], base64.b64decode(part['binary_data']))
