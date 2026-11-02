#!/usr/bin/env python3
"""HTTPS-terminating reverse proxy for the Termux apt repo.

The bundled Termux rootfs's apt (gnutls https method) has its CA lookup
compiled against Termux's own install prefix, which our prefix can't
satisfy — so the device fetches over plain HTTP from this host-side proxy,
and THIS side does the real TLS verification against the live repo.
Package integrity is still enforced end-to-end: apt verifies the GPG
signature on InRelease/Packages independently of transport.

Usage:  python3 termux-proxy.py [port]     # default 8391, binds 127.0.0.1
Mount:  adb reverse tcp:8391 tcp:8391
"""
import sys
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

UPSTREAM = "https://packages-cf.termux.dev"
# The repo is only reachable through the host's clash; TLS is verified at
# this hop (opener default), so the chain stays end-to-end authenticated.
OPENER = urllib.request.build_opener(
    urllib.request.ProxyHandler({"https": "http://127.0.0.1:7890", "http": "http://127.0.0.1:7890"})
)


class Proxy(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        url = UPSTREAM + self.path
        try:
            req = urllib.request.Request(url, headers={
                "User-Agent": self.headers.get("User-Agent", "apt-proxy"),
                "Accept": self.headers.get("Accept", "*/*"),
            })
            with OPENER.open(req, timeout=60) as r:
                body = r.read()
                self.send_response(r.status)
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Content-Type", r.headers.get("Content-Type", "application/octet-stream"))
                self.end_headers()
                self.wfile.write(body)
        except urllib.error.HTTPError as e:
            body = e.read()
            self.send_response(e.code)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except Exception as e:  # noqa: BLE001 - proxy must never crash
            msg = f"proxy error: {e}".encode()
            self.send_response(502)
            self.send_header("Content-Length", str(len(msg)))
            self.end_headers()
            self.wfile.write(msg)

    def log_message(self, fmt, *args):  # quiet
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8391
    print(f"termux repo proxy on 127.0.0.1:{port} -> {UPSTREAM}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", port), Proxy).serve_forever()
