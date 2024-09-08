#!/usr/bin/python3

# Browse SMS and MMS messages files exported by https://github.com/tmo1/sms-ie
# Copyright (c) 2024 Alain Ducharme
#
# This program is free software: you can redistribute it and/or modify it under the
# terms of the GNU General Public License as published by the Free Software Foundation,
# either version 3 of the License, or (at your option) any later version.
#
# This program is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
# PARTICULAR PURPOSE. See the GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License along with this
# program. If not, see <http://www.gnu.org/licenses/>.

from datetime import datetime
from html import escape
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler, HTTPStatus
import json
from os import path as os_path
import re
from sys import argv
from zipfile import is_zipfile, ZipFile

URL_REGEX = re.compile(r"(https?://*\S+)")

base_html = '''
<!DOCTYPE html><html lang=””><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"> 
<title>TITLE</title><style>
:root {
  --bg: #ffffff; --fg: #9ba0a6;
  --tofg: #202020; --tobg: #f2f2f2;
  --frfg: #202020; --frbg: #d2e4fc;
}
@media (prefers-color-scheme: dark) { 
  :root { 
    --bg: #202020; --fg: #9ba0a6;
    --tofg: #ffffff; --tobg: #404040;
    --frfg: #202020; --frbg: #82a8e7;
  }
}
body {
  background: var(--bg);
  color: var(--fg);
  font-family: sans-serif;
  max-width: 50em;
  margin: auto;
}
a {
  color: inherit;
  font-style: italic;
}
div.row { 
  max-width: 75%;
  margin-bottom: 1em;
  border-radius: 1em;
  padding: 0.5em;
}
div.row.from { 
  background-color: var(--frbg);
  color: var(--frfg); 
  float:right;
}
div.row.to {
  background-color: var(--tobg);
  color: var(--tofg);
  float:left;
}
div.date {
  font-size: 0.8em;
  text-align: center;
  margin-left: auto;
  clear: both;
}
div.thread { 
  margin-bottom:0.1em;
  border-radius: 0.8em;
  padding: 0.5em;
}
div.thread.contact {
  background-color: var(--tobg);
  color: var(--tofg);
  float: left;
}
div.thread.last {
  float: right;
}
br {
  clear: both;
}
</style></head><body>BODY</body></html>
'''

class Messages:
    def __init__(self):
        self.zf = None
        self.threads = {}
        self.mdata = {}

    def open(self, messages_file):
        if is_zipfile(messages_file):
            self.zf = ZipFile(messages_file)
        with self.zf.open("messages.ndjson") if self.zf else open(messages_file) as f:
            self.messages = [json.loads(l) for l in f]

        for i, m in enumerate(self.messages):
            mms = False
            m_type = m.get("type", None)
            if not m_type:
                m_type = m.get("msg_box", "1")
                mms = True
            outbound = m_type == "2"

            ts_date = int(m["date"])  # ms for SMS, s for MMS!
            if not mms:
                ts_date /= 1000
            m_date = datetime.fromtimestamp(ts_date)

            # Attempt to get correspondent(s)...
            address = ""
            if mms:
                # MMS type: PduHeaders.
                # BCC 0x81, CC 0x82, FROM 0x89, TO 0x97
                if outbound and "__recipient_addresses" in m:
                    for ra in m["__recipient_addresses"]:
                        if "__display_name" in ra:
                            address += ra["__display_name"] + " "
                        if "address" in ra:
                            address += ra["address"] + " "
                elif "__sender_address" in m:
                    sa = m["__sender_address"]
                    if "__display_name" in sa:
                        address = sa["__display_name"] + " "
                    address += sa["address"]
            if not address:
                if "__display_name" in m:
                    address = m["__display_name"] + " "
                address += m.get("address", "")

            t_id = int(m["thread_id"])
            t = self.threads.get(t_id, None)
            if t:
                if len(t[1]) < len(address):
                    t[1] = address
                if t[0] < m_date:
                    t[0] = m_date
            else:
                self.threads[t_id] = [m_date, address, []]  # list of msgs

            self.mdata[i] = [m_date, t_id, outbound]

        # Sort by m_date
        self.threads = dict(sorted(self.threads.items(), key=lambda x: x[1][0], reverse=True))
        self.mdata = dict(sorted(self.mdata.items(), key=lambda x: x[1][0]))

        # Attach messages to threads in date order...
        for m_no, v in self.mdata.items():
            self.threads[v[1]][2].append(m_no)

    def get_threads(self):
        body = ""
        for t_id, (m_date, address, _) in self.threads.items():
            body += f'<div class="thread contact"><a href="/tid/{t_id}">{escape(address)}</a></div><div class="thread last">{m_date.strftime("%F %T")}</div><br>\n'
        html = base_html.replace("TITLE", "Msgs").replace("BODY", body)
        return html.encode()

    def get_thread(self, t_id):
        _, address, msgs = self.threads[t_id]
        body = ""
        for m_no in msgs:
            m_date, _, outbound = self.mdata[m_no]
            body += f'<div class="date">{m_date.strftime("%F %T")}</div>'
            body += '<div class="row from">' if outbound else '<div class="row to">'
            m = self.messages[m_no]
            text = escape(m.get("body", ""))
            mms_parts = m.get("__parts", [])
            for p_no, part in enumerate(mms_parts):
                ptype = part.get("ct", None)
                if ptype == "application/smil":
                    continue  # ignore
                if ptype == "text/plain":
                    text += escape(part.get("text", ""))
                else:
                    cl = part.get("cl", "")
                    if len(cl) < 20:  # add date to short names
                        text += f'<a href="/data/{m_no}_{p_no}/{m_date.strftime("%F")}-{cl}">{escape(cl)}</a><br>'
                    else:
                        text += f'<a href="/data/{m_no}_{p_no}/{cl}">{cl}</a><br>'
            body += URL_REGEX.sub(r'<a href="\1">\1</a>', text).replace("\n", "<br>") + "</div>\n"
        html = base_html.replace("TITLE", f"Msgs: {escape(address)}").replace("BODY", body)
        return html.encode()

    def get_data(self, m_part):
        m_no, p_no = map(int, m_part.split("_"))
        part = self.messages[m_no]["__parts"][p_no]
        data_type = part["ct"]
        if self.zf:
            with self.zf.open(os_path.join("data", os_path.basename(part["_data"])), "r") as f:
                return f.read(), data_type
        else:
            with open(os_path.join(data_path, os_path.basename(part["_data"])), "rb") as f:
                return f.read(), data_type


class Handler(BaseHTTPRequestHandler):

    protocol_version = "HTTP/1.1"  # requires accurate content-length`

    def send_with_headers(self, data, cont_type="text/html; charset=UTF-8"):
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", cont_type)
        self.send_header("pragma", "no-cache")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        tid = "/tid/"
        if self.path == "/favicon.ico":
            self.send_with_headers(b'', 'image/x-icon')
            return
        elif self.path.startswith(tid):
            t_id = int(self.path[len(tid) :])
            self.send_with_headers(messages.get_thread(t_id))
            return
        elif self.path.startswith("/data/"):
            m_part = self.path.split("/")[2]
            self.send_with_headers(*messages.get_data(m_part))
            return
        self.send_with_headers(messages.get_threads())


if __name__ == "__main__":
    if len(argv) < 2:
        print(f"Usage: {argv[0]} messages-YYYY-MM-DD.zip")
        exit()

    messages_file = argv[1]
    data_path = os_path.join(os_path.dirname(messages_file), "data") # in case not zip
    messages = Messages()
    messages.open(messages_file)

    # with open("msg-base.html", "r") as f:
    #     base_html = f.read()

    httpserv = ThreadingHTTPServer(("0.0.0.0", 8222), Handler)
    print("Serving messages browser here: http://127.0.0.1:8222/ - use <Ctrl-C> to stop")
    try:
        httpserv.serve_forever()
    except (KeyboardInterrupt, SystemExit):
        print("BREAK! Done.")
        httpserv.socket.close()

