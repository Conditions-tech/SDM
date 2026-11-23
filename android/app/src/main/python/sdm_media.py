"""SDM's Python side on Android (ADR-0023): yt-dlp for video pages, LAME for MP3.

Kotlin calls these functions through Chaquopy. Nothing here touches the network by itself.
"""


def versions() -> dict:
    """The versions of the bundled tools, for the About screen."""
    import lameenc
    import yt_dlp.version

    return {"yt-dlp": yt_dlp.version.__version__, "lameenc": getattr(lameenc, "__version__", "1.7.0")}
