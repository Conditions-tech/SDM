"""SDM's Python side on Android (ADR-0023): yt-dlp for video pages, LAME for MP3.

Kotlin calls these functions through Chaquopy. Nothing here touches the network by itself.
"""

import lameenc


def versions() -> dict:
    """The versions of the bundled tools, for the About screen."""
    import yt_dlp.version

    return {"yt-dlp": yt_dlp.version.__version__, "lameenc": getattr(lameenc, "__version__", "1.7.0")}


class Mp3Encoder:
    """Turns 16-bit PCM from Android's decoder into MP3 with LAME, a chunk at a time.

    The converter (Kotlin) decodes the audio with MediaCodec and passes the samples here;
    each call returns the MP3 bytes ready so far, so nothing big stays in memory.
    """

    def __init__(self, sample_rate: int, channels: int, kbps: int = 192) -> None:
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
