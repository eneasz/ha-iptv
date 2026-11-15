#!/usr/bin/python3
"""Fake Xtream Codes server for developing Channel Deck. Any user/pass works.
Serves ~6000 generated channels in 48 groups; every stream plays the same test pattern."""
import json, os, random, re, sys
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

HERE = os.path.dirname(os.path.abspath(__file__))
random.seed(7)
GROUPS = ["PL| OGÓLNOTEMATYCZNE","PL| FILMOWE","PL| DLA DZIECI","PL| DOKUMENTALNE","PL| SPORTOWE","PL| INFORMACYJNE",
          "PL| MUZYCZNE","PL| RADIO","PL| REGIONALNE","UK| ENTERTAINMENT","UK| SPORT","UK| NEWS","UK| MOVIES","UK| KIDS",
          "US| ENTERTAINMENT","US| SPORT","US| NEWS","US| MOVIES","DE| ALLGEMEIN","DE| SPORT","DE| FILME","FR| GENERALISTE",
          "FR| SPORT","ES| GENERAL","ES| DEPORTES","IT| GENERALE","IT| SPORT","NL| ALGEMEEN","PT| GERAL","TR| GENEL",
          "RO| GENERAL","CZ| OBECNE","SK| VSEOBECNE","HU| ALTALANOS","GR| GENERAL","SE| ALLMAN","NO| ALMEN","DK| ALMEN",
          "FI| YLEINEN","IE| GENERAL","PPV| EVENTS","24/7| SERIES","24/7| MOVIES","4K| UHD","RADIO| MUSIC","XMAS| 24/7",
          "FAV| MIXED","MISC| OTHER"]
NAMES = ["TV","Sport","News","Movies","Kids","Music","Doc","Series","Info","Plus","HD","Extra","Gold","Max","One","Prime"]
cats = [{"category_id": str(i + 1), "category_name": g, "parent_id": 0} for i, g in enumerate(GROUPS)]
streams = []
n = 0
for i, g in enumerate(GROUPS):
    size = random.randint(40, 260)
    for j in range(size):
        n += 1
        streams.append({"num": n, "name": f"{g.split('|')[0].strip()}: {random.choice(NAMES)} {random.choice(NAMES)} {j+1}",
                        "stream_type": "live", "stream_id": 10000 + n, "category_id": str(i + 1)})
print(f"mock: {len(cats)} groups, {len(streams)} channels", flush=True)

class H(SimpleHTTPRequestHandler):
    def log_message(self, *a): pass
    def translate_path(self, path): return os.path.join(HERE, "hls", os.path.basename(urlparse(path).path))
    def do_GET(self):
        u = urlparse(self.path)
        if u.path == "/player_api.php":
            q = parse_qs(u.query); a = q.get("action", [""])[0]
            body = {"get_live_categories": cats, "get_live_streams": streams}.get(a)
            if body is None: body = {"user_info": {"auth": 1, "status": "Active"}, "server_info": {}}
            data = json.dumps(body).encode()
            self.send_response(200); self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data); return
        m = re.match(r"^/live/[^/]+/[^/]+/\d+\.(m3u8|ts)$", u.path)
        if m:   # every channel plays the same pattern; HLS only (client falls back from .ts)
            if m.group(1) == "ts": self.send_error(404); return
            self.send_response(302); self.send_header("Location", "/test.m3u8"); self.end_headers(); return
        super().do_GET()

ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1]) if len(sys.argv) > 1 else 8099), H).serve_forever()
