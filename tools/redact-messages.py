#! /usr/bin/python3

# SMS Import / Export: a simple Android app for importing and exporting SMS messages from and to JSON files.
# Copyright (c) 2021-2022 Thomas More
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

import sys
import json
from itertools import count

address_iterator = count(start=12345)
address_map = {}
redacted_fields = {'body', '__display_name', 'text', 'sub'}
REDACTED = 'REDACTED '


def redact(obj):
    for x in obj:
        if isinstance(x, dict):
            redact(x)
        elif isinstance(obj[x], list) or isinstance(obj[x], dict):
            redact(obj[x])
        elif x == 'address':
            if obj[x] in address_map:
                obj[x] = address_map[obj[x]]
            else:
                old_address = obj[x]
                obj[x] = str(next(address_iterator))
                address_map[old_address] = obj[x]
        elif x == 'body':
            obj[x] = REDACTED + '(Message ID: ' + obj['_id'] + ')'
        elif x == 'text':
            obj[x] = REDACTED + '(Message ID: ' + obj['mid'] + ', Part ID: ' + obj['_id'] + ')'
        elif x in redacted_fields:
            obj[x] = REDACTED


for message in sys.stdin:
    message = json.loads(message)
    redact(message)
    print(json.dumps(message))
