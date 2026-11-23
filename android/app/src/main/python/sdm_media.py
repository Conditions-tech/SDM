"""SDM's Python side on Android (ADR-0024): yt-dlp for video pages, LAME for MP3.

Kotlin calls these functions through Chaquopy. Nothing here touches the network by itself.
"""

import json


def versions() -> dict:
    """The versions of the bundled tools, for the About screen."""
    import lameenc  # loaded only when needed: PCs have no lameenc for Python 3.13
    import yt_dlp.version

    return {
        "yt-dlp": yt_dlp.version.__version__,
        "lameenc": getattr(lameenc, "__version__", "1.7.0"),
    }


class Mp3Encoder:
    """Turns 16-bit PCM from Android's decoder into MP3 with LAME, a chunk at a time.

    The converter (Kotlin) decodes the audio with MediaCodec and passes the samples here;
    each call returns the MP3 bytes ready so far, so nothing big stays in memory.
    """

    def __init__(self, sample_rate: int, channels: int, kbps: int = 192) -> None:
        import lameenc

        if channels not in (1, 2):
            raise ValueError("MP3 holds mono or stereo sound only")
        self._encoder = lameenc.Encoder()
        self._encoder.set_bit_rate(kbps)
        self._encoder.set_in_sample_rate(sample_rate)
        self._encoder.set_channels(channels)
        self._encoder.set_quality(2)  # 2 = high quality, 7 = fastest

    def encode(self, pcm: bytes) -> bytes:
        return bytes(self._encoder.encode(bytes(pcm)))

    def finish(self) -> bytes:
        return bytes(self._encoder.flush())


# Only what SDM needs from yt-dlp: it reads a page and lists the streams; it never downloads,
# never reads cookies or browser data, and writes nothing (ADR-0024). SDM's own engine then
# downloads the chosen streams through its network policy.
class _Silent:
    """yt-dlp's messages are not shown: failures come back as PageError instead."""

    def debug(self, msg):
        pass

    info = warning = error = debug


_OPTIONS = {
    "logger": _Silent(),
    "quiet": True,
    "no_warnings": True,
    "skip_download": True,
    "noplaylist": True,
    "cachedir": False,
    "socket_timeout": 20,
    "extractor_retries": 1,
}
_HEADERS_KEPT = ("User-Agent", "Referer", "Origin", "Accept", "Accept-Language")


class PageError(Exception):
    """A page yt-dlp could not read; the message is shown to the user."""


def extract(url: str) -> str:
    """The page's title and its directly downloadable streams, as JSON for the Kotlin side."""
    import yt_dlp
    from yt_dlp.utils import DownloadError

    try:
        with yt_dlp.YoutubeDL(dict(_OPTIONS)) as ydl:
            info = ydl.extract_info(url, download=False)
    except DownloadError as exc:
        raise PageError(_short(str(exc))) from None
    if info is None:
        raise PageError("No video was found on this page.")
    if info.get("_type") == "playlist":
        entries = [entry for entry in info.get("entries") or [] if entry]
        if not entries:
            raise PageError("No video was found on this page.")
        info = entries[0]
    formats = info.get("formats") or [info]
    streams = []
    for f in formats:
        if f.get("protocol") not in ("http", "https") or not f.get("url"):
            continue  # HLS/DASH manifests, RTMP...: not supported on the phone
        headers = {k: v for k, v in (f.get("http_headers") or {}).items() if k in _HEADERS_KEPT}
        streams.append(
            {
                "id": str(f.get("format_id") or ""),
                "url": f["url"],
                "ext": f.get("ext") or "",
                # yt-dlp: "none" = the stream has no video/sound; None = not known. Keep them
                # apart: Facebook and Instagram list their ready-made MP4s with unknown codecs.
                "vcodec": f.get("vcodec") or "",
                "acodec": f.get("acodec") or "",
                "height": f.get("height") or 0,
                "width": f.get("width") or 0,
                "tbr": f.get("tbr") or 0,
                "size": f.get("filesize") or f.get("filesize_approx") or 0,
                "headers": headers,
            }
        )
    return json.dumps({"title": info.get("title") or "video", "streams": streams})


def _short(message: str) -> str:
    message = message.replace("ERROR: ", "").strip()
    return message.splitlines()[0][:300] if message else "yt-dlp could not read this page."
