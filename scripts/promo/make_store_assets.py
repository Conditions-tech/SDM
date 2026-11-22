"""Images for the extension stores (Edge Add-ons, and later Firefox): a 300x300 logo and three
1280x800 screenshots, from SDM's real windows (see make_promo.py).

    uv run python scripts/promo/make_store_assets.py   # -> dist\\store\\
"""

import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import make_promo as mp  # noqa: E402
from PySide6.QtCore import QPointF, QRectF, Qt  # noqa: E402
from PySide6.QtGui import QImage, QPainter  # noqa: E402

OUT = mp.ROOT / "dist" / "store"
W, H = 1280, 800


def canvas() -> tuple[QImage, QPainter]:
    image = QImage(W, H, QImage.Format.Format_ARGB32)
    p = QPainter(image)
    p.setRenderHint(QPainter.RenderHint.Antialiasing)
    p.setRenderHint(QPainter.RenderHint.TextAntialiasing)
    mp.background(p, W, H, 2.0)
    return image, p


def caption(p: QPainter, title: str, subtitle: str) -> None:
    mp.text(p, QRectF(0, 34, W, 70), 46, "#ffffff", title, weight=mp.QFont.Weight.Bold)
    mp.text(p, QRectF(0, 100, W, 40), 22, "#cbd5e1", subtitle)


def main() -> int:
    app = mp.QApplication(sys.argv[:1])
    OUT.mkdir(parents=True, exist_ok=True)
    logo = QImage(300, 300, QImage.Format.Format_ARGB32)
    logo.fill(Qt.GlobalColor.transparent)
    lp = QPainter(logo)
    lp.setRenderHint(QPainter.RenderHint.Antialiasing)
    windows = mp.Windows(app, OUT / "work")
    windows.logo.render(lp, QRectF(0, 0, 300, 300))
    lp.end()
    logo.save(str(OUT / "logo-300.png"))
    studio = mp.Studio(windows)

    image, p = canvas()
    caption(p, "One click on any video", "The SDM button appears over videos; choose the quality")
    rect = QRectF(150, 160, 980, 610)
    mp.browser(p, rect, studio.logo, 3.0, 980 / 1280)
    mp.picture(p, studio.add_url, QPointF(W / 2, 500), 600)
    p.end()
    image.save(str(OUT / "screenshot-1-video-button.png"))

    image, p = canvas()
    caption(
        p, "Your downloads go to SDM", "Faster, over up to 8 connections, with pause and resume"
    )
    mp.picture(p, windows.main(progress=0.42), QPointF(W / 2, 470), 1080)
    p.end()
    image.save(str(OUT / "screenshot-2-downloads.png"))

    image, p = canvas()
    caption(p, "Free and open source", "github.com/Conditions-tech/SDM · Windows 10 and 11")
    mp.picture(p, studio.dark_main, QPointF(W / 2 - 120, 480), 820)
    mp.picture(p, studio.converts[60], QPointF(W / 2 + 260, 600), 520)
    p.end()
    image.save(str(OUT / "screenshot-3-convert.png"))
    for path in sorted(OUT.glob("*.png")):
        print(f"Made {path.name}")
    windows.close()
    return 0


if __name__ == "__main__":
    code = main()
    sys.stdout.flush()
    os._exit(code)  # see make_promo.py: Qt's off-screen teardown crashed at exit
