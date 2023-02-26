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

# Written for https://github.com/tmo1/sms-ie/issues/100

import csv
import json

print(json.dumps(list(csv.DictReader(open(0, newline=''))), indent=2))
