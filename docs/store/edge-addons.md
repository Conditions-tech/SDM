# Publishing SDM's extension on Microsoft Edge Add-ons

Free. The owner does the account and the submission; everything to upload is prepared.

## What to upload

| What | Where |
|---|---|
| The package | `dist\extension\sdm-edge-store-<version>.zip` (`uv run python scripts/build_extensions.py`; it has no `key`: the store gives its own ID) |
| Logo, 300x300 | `dist\store\logo-300.png` (`uv run python scripts/promo/make_store_assets.py`) |
| Screenshots, 1280x800 | `dist\store\screenshot-1-video-button.png`, `-2-downloads.png`, `-3-convert.png` |
| Privacy policy URL | https://github.com/Conditions-tech/SDM/blob/main/PRIVACY.md |
| Website / support | https://github.com/Conditions-tech/SDM and https://github.com/Conditions-tech/SDM/issues |

## Steps

1. **Register (once, free):** open https://partner.microsoft.com/dashboard/microsoftedge/overview,
   sign in with a Microsoft account and register as a developer (individual). No fee.
2. **Create new extension → Upload package:** choose the `sdm-edge-store-<version>.zip`.
3. **Availability:** Public; all markets.
4. **Properties:** category *Productivity*; privacy policy URL as above; "Does your extension
   access, collect or transmit personal information?": **No** (it only passes addresses to the
   SDM program on the same computer, see PRIVACY.md); website and support URLs as above.
5. **Store listing (English):** paste the texts below; add the logo and the three screenshots.
6. **Submit.** Microsoft reviews it (often a few days). Paste the **testing notes** below into
   "Notes for certification", because the extension needs the SDM program.
7. **When it's published:** the store page shows the extension's ID (32 letters) and its
   address. Send both to Claude: they go into `EDGE_STORE_EXTENSION_ID` in
   `src/sdm/bridge/browsers.py` and `EDGE_STORE_URL` in `src/sdm/core/links.py`, and the next SDM
   release lets the store version talk to SDM. **Until that release, the store version can't
   reach SDM** (SDM only accepts extensions it knows by ID); keep using Load unpacked till then.

## Store texts

**Name:** SDM: Download with SDM

**Short description** (under 120 characters):
Send downloads and videos to SDM, a free, open-source download manager for Windows.

**Description:**
```
SDM (Smart Download Manager) is a free, open-source download manager for Windows. This
extension connects Microsoft Edge to the SDM program on your computer.

• Click a download and SDM's window opens instead of Edge's: faster downloads over up to 8
  connections, with pause and resume (you can turn this off in the extension's panel).
• Right-click a link, image, video or audio player and choose "Download with SDM".
• Optional SDM button over videos: click it and choose 720p, 1080p, 4K or MP3 in SDM
  (needs SDM's optional video add-on).

Requires the free SDM program for Windows 10 and 11:
https://github.com/Conditions-tech/SDM/releases/latest

Privacy: the extension sends nothing over the internet. It only passes addresses you choose
to the SDM program on your own computer. No accounts, no tracking.
Open source (Apache 2.0): https://github.com/Conditions-tech/SDM
```

**Notes for certification:**
```
This extension works together with the free SDM desktop program for Windows
(https://github.com/Conditions-tech/SDM/releases/latest), through native messaging
(host name com.sdm.bridge). To test: install SDM-Setup-<version>.exe, start SDM, then in Edge
right-click any link and choose "Download with SDM": SDM's Add URL window opens. Without the
program, the toolbar icon shows "!" and explains that SDM's browser bridge isn't installed.
Permissions: downloads (send downloads to SDM, can be turned off in the panel), storage (two
settings), contextMenus (the menu items), nativeMessaging (to reach SDM), activeTab (the
panel's "Download this video" button), scripting + optional host access (the optional video
button, only after the user turns it on).
```
