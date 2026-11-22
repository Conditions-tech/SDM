"""An optional HTTP proxy the user chose (M16, ADR-0022), for example one on BDIX that their
ISP offers. Off by default.

With a proxy, SDM's connections go only to the proxy, and the proxy reaches the server. So
SDM checks each server's addresses itself before asking the proxy (check_through_proxy): a
link or redirect still can't point SDM at this computer or the local network. https stays
encrypted end to end (the proxy only forwards it, with CONNECT).
"""

import ipaddress
from urllib.parse import urlsplit

from sdm.core.errors import DownloadError
from sdm.engine.policy import NetworkPolicy, PolicyResolver


def parse_proxy(text: str) -> str | None:
    """ "" means no proxy; "host:port" or "http://host:port" gives "http://host:port".
    Raises ValueError with a message fit to show the user."""
    text = text.strip()
    if not text:
        return None
    if "://" not in text:
        text = "http://" + text
    try:
        parts = urlsplit(text)
        port = parts.port
    except ValueError:
        raise ValueError("The proxy's port must be a number from 1 to 65535.") from None
    if parts.scheme != "http":
        raise ValueError("SDM supports HTTP proxies only (http://host:port).")
    if parts.username or parts.password:
        raise ValueError("SDM doesn't keep proxy passwords: use a proxy without a login.")
    if not parts.hostname or parts.path not in ("", "/") or parts.query or parts.fragment:
        raise ValueError("Write the proxy as host:port, for example 10.10.10.10:8080.")
    if port is None or not 1 <= port <= 65535:
        raise ValueError("The proxy needs a port, for example 10.10.10.10:8080.")
    host = parts.hostname
    if ":" in host:  # an IPv6 address
        host = f"[{ipaddress.ip_address(host)}]"
    return f"http://{host}:{port}"


async def check_through_proxy(policy: NetworkPolicy, host: str) -> None:
    """Resolve the server's name here and apply the policy, as the direct path does."""
    policy.check_url_host(host)
    try:
        ipaddress.ip_address(host.split("%", 1)[0])
        return  # an IP literal, checked just above
    except ValueError:
        pass
    resolver = PolicyResolver(policy)
    try:
        await resolver.resolve(host, 0)
    except OSError:
        raise DownloadError(
            f"Could not find the server {host}. Check the address and your connection.",
            retryable=True,
        ) from None
    finally:
        await resolver.close()
