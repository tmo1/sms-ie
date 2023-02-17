#! /usr/bin/python3

# SMS Import / Export: a simple Android app for importing and exporting SMS messages from and to JSON files.
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
# along with SMS Import / Export.  If not, see <https://www.gnu.org/licenses/>.

import os
import json
import sys
from datetime import datetime
import argparse

parser = argparse.ArgumentParser(
    description='convert SMS messages in Nokia VMG format to SMS Import / Export JSON format')
parser.add_argument('directory', help='directory containing (only) VMG files')
parser.add_argument('-d', '--debug', action='store_true', help='debugging output')
args = parser.parse_args()

messages = []
for file in os.listdir(args.directory):
    if args.debug:
        sys.stderr.write('Processing ' + file + '\n')
    FILE, BODY = 0, 1
    mode = FILE
    sms = {}
    for line in open(os.path.join(args.directory, file), encoding='utf-16'):
        if mode == BODY:
            if line.strip() == 'END:VBODY':
                mode = FILE
            elif line[:5] != 'Date:':
                sms['body'] += line.strip()
        else:
            name, value = line.strip().split(':')
            if name == 'BEGIN':
                if value == 'VBODY':
                    mode = BODY
                    sms['body'] = ''
            elif name == 'X-IRMC-STATUS':
                sms['read'] = '1' if value == 'READ' else '0'
            elif name == 'X-IRMC-BOX':
                sms['type'] = '2' if value == 'SENT' else '1'
            # I'm not sure if we should set 'date', 'date_sent', or both here
            elif name == 'X-NOK-DT':
                sms['date'] = str(int(datetime.fromisoformat(value).timestamp() * 1000))
            elif name == 'TEL':
                sms['address'] = value
    messages.append(sms)
print(json.dumps(messages, indent=2))
