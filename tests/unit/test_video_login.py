"""The opt-in browser login for video pages (M15): off by default, one browser, plain words."""

from pathlib import Path

from sdm.media.ytdlp import LOGIN_BROWSERS, QUALITIES, explain_failure, ytdlp_arguments
from sdm.ui.preferences import Preferences

URL = "https://video.example.com/watch?v=abc"


def test_no_login_unless_chosen():
    args = ytdlp_arguments(URL, QUALITIES["720p"], Path("w"))
    assert "--no-cookies-from-browser" in args and "--cookies-from-browser" not in args
    assert Preferences().video_login_browser == ""


def test_a_chosen_browser_lends_its_login_and_nothing_else():
    for key in LOGIN_BROWSERS:
        args = ytdlp_arguments(URL, QUALITIES["720p"], Path("w"), login_browser=key)
        assert args[args.index("--cookies-from-browser") + 1] == key
        assert "--no-cookies-from-browser" not in args
        assert "--no-cookies" in args  # never a cookies file
        assert args[0] == "--ignore-config" and args[-2:] == ["--", URL]
    # anything else (a damaged setting) means no login at all
    args = ytdlp_arguments(URL, QUALITIES["720p"], Path("w"), login_browser="safari; rm")
    assert "--cookies-from-browser" not in args


def test_a_damaged_setting_falls_back_to_no_login():
    assert Preferences(video_login_browser="opera").checked().video_login_browser == ""
    assert Preferences(video_login_browser="firefox").checked().video_login_browser == "firefox"


def test_login_problems_in_plain_words():
    locked = explain_failure(["ERROR: Could not copy Chrome cookie database. See ..."])
    assert "Close that browser" in locked
    protected = explain_failure(["ERROR: Failed to decrypt with DPAPI. See ..."])
    assert "Firefox" in protected
