"""Checks sdm_media.extract() on the PC against a local page (never a real site).

Run: uv run --python 3.13 --with yt-dlp==2026.10.26 python tools/check_extract.py
A page with a <video> tag and a direct MP4 is served on 127.0.0.1; yt-dlp's generic extractor
must find it, with its title, and a missing page must give a short message.
"""

import http.server
import json
import pathlib
import sys
import threading

sys.path.insert(0, str(pathlib.Path(__file__).parent.parent / "app" / "src" / "main" / "python"))
import sdm_media  # noqa: E402

ASSETS = pathlib.Path(__file__).parent.parent / "app" / "src" / "androidTest" / "assets"
CLIP = (ASSETS / "h264_aac.mp4").read_bytes()
PAGE = (
    b"<html><head><title>Local test clip</title></head>"
    b'<body><video src="/media/clip.mp4"></video></body></html>'
)
ROUTES = {"/page": (PAGE, "text/html"), "/media/clip.mp4": (CLIP, "video/mp4")}


def check(ok: bool, what: object) -> None:
    if not ok:
        raise SystemExit(f"FAILED: {what}")


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):  # noqa: N802
        body, kind = ROUTES.get(self.path, (None, None))
        if body is None:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_HEAD(self):  # noqa: N802
        self.do_GET()

    def log_message(self, *args):
        pass


server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
threading.Thread(target=server.serve_forever, daemon=True).start()
base = f"http://127.0.0.1:{server.server_port}"

result = json.loads(sdm_media.extract(base + "/page"))
streams = [(s["id"], s["ext"], s["url"]) for s in result["streams"]]
print("title:", result["title"], "| streams:", streams)
check(bool(streams) and streams[0][2].endswith("/media/clip.mp4"), result)
try:
    sdm_media.extract(base + "/missing")
    raise SystemExit("FAILED: a missing page must fail")
except sdm_media.PageError as exc:
    print("missing page:", exc)
    check("404" in str(exc), exc)
print("OK")
server.shutdown()
