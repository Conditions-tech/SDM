"""The optional HTTP proxy (M16, ADR-0022): downloads go through it, and SDM still refuses
unsafe destinations itself before asking the proxy."""

import socket
import threading
import urllib.request
from dataclasses import replace
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from sdm.core.errors import BlockedDestinationError, DownloadError
from sdm.engine.policy import NetworkPolicy
from sdm.engine.proxy import parse_proxy

DATA = bytes(range(256)) * 64


class _Proxy(BaseHTTPRequestHandler):
    """A forwarding proxy for plain http, just enough for the tests."""

    seen: list[str]

    def do_GET(self):  # noqa: N802 (http.server's name)
        self.seen.append(self.path)  # a proxy gets the full address
        request = urllib.request.Request(self.path)  # noqa: S310 (http from the tests)
        for name in ("Range", "User-Agent", "If-Range"):
            if self.headers.get(name):
                request.add_header(name, self.headers[name])
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        try:
            response = opener.open(request, timeout=5)  # noqa: S310 (the tests' own server)
        except urllib.error.HTTPError as exc:
            response = exc
        body = response.read()
        self.send_response(response.status if hasattr(response, "status") else response.code)
        for name, value in response.headers.items():
            if name.lower() not in ("transfer-encoding", "connection", "content-length"):
                self.send_header(name, value)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


@pytest.fixture
def proxy():
    seen: list[str] = []
    handler = type("Handler", (_Proxy,), {"seen": seen})
    server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    yield f"127.0.0.1:{server.server_port}", seen
    server.shutdown()
    server.server_close()


def test_parsing_the_proxy_setting():
    assert parse_proxy("") is None and parse_proxy("  ") is None
    assert parse_proxy("10.10.10.10:8080") == "http://10.10.10.10:8080"
    assert parse_proxy("http://proxy.example:3128/") == "http://proxy.example:3128"
    assert parse_proxy("[::1]:8080") == "http://[::1]:8080"
    for bad in ("socks5://h:1080", "h", "h:0", "h:99999", "h:port", "user:pw@h:80", "h:80/x"):
        with pytest.raises(ValueError):
            parse_proxy(bad)


def test_a_download_goes_through_the_proxy(fetch, fast_settings, mock_server, proxy):
    address, seen = proxy
    mock_server.add("/file.bin", mock_server.file(DATA))
    settings = replace(fast_settings, proxy=parse_proxy(address))
    result = fetch(mock_server.url("/file.bin"), settings=settings)
    assert result.path.read_bytes() == DATA
    assert seen and all(path.startswith(mock_server.url("/file.bin")) for path in seen)


def test_the_proxy_never_lets_a_link_reach_this_computer(fetch, fast_settings, proxy):
    # SDM checks the destination itself: the proxy is never even asked.
    address, seen = proxy
    settings = replace(fast_settings, proxy=parse_proxy(address))
    for url in ("http://127.0.0.1:9/x.bin", "http://localhost:9/x.bin", "http://10.0.0.1/x"):
        with pytest.raises(BlockedDestinationError):
            fetch(url, settings=settings, policy=NetworkPolicy())
    assert seen == []


def test_a_proxy_that_isnt_there_says_so(fetch, fast_settings, mock_server):
    mock_server.add("/file.bin", mock_server.file(DATA))
    with socket.socket() as probe:  # a port nothing listens on
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    settings = replace(fast_settings, proxy=f"http://127.0.0.1:{port}", max_attempts=1)
    with pytest.raises(DownloadError, match="proxy"):
        fetch(mock_server.url("/file.bin"), settings=settings)
