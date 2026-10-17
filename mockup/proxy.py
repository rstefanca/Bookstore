"""Serves the staff mockup and forwards /api/* to the Bookstore app, so page and API share one origin.

Usage: python3 -I proxy.py [port] [upstream]   (defaults: 8090, http://localhost:8080)
"""
import http.server
import pathlib
import sys
import urllib.error
import urllib.request

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8090
UPSTREAM = sys.argv[2] if len(sys.argv) > 2 else "http://localhost:8080"
PAGE = pathlib.Path(__file__).with_name("index.html")
FORWARDED_REQUEST_HEADERS = ("Authorization", "Content-Type", "Accept")


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path.startswith("/api/"):
            self.forward()
        elif self.path in ("/", "/index.html"):
            body = PAGE.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_error(404)

    def do_POST(self):
        self.forward()

    def do_PUT(self):
        self.forward()

    def do_DELETE(self):
        self.forward()

    def forward(self):
        if not self.path.startswith("/api/"):
            self.send_error(404)
            return
        length = int(self.headers.get("Content-Length") or 0)
        data = self.rfile.read(length) if length else None
        request = urllib.request.Request(UPSTREAM + self.path, data=data, method=self.command)
        for name in FORWARDED_REQUEST_HEADERS:
            if self.headers.get(name):
                request.add_header(name, self.headers[name])
        try:
            response = urllib.request.urlopen(request, timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        except urllib.error.URLError as error:
            self.send_error(502, f"Bookstore app not reachable: {error.reason}")
            return
        body = response.read()
        self.send_response(response.status)
        # WWW-Authenticate is dropped so a 401 doesn't pop up the browser's own sign-in dialog.
        for name in ("Content-Type", "Location"):
            if response.headers.get(name):
                self.send_header(name, response.headers[name])
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    print(f"Staff mockup on http://localhost:{PORT} -> {UPSTREAM}", flush=True)
    http.server.ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
