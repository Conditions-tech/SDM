# Roadmap

Milestones run in order. A milestone is **Done** only when its acceptance criteria have been met
and its tests have actually run and passed.

| Milestone | Goal | Status |
|---|---|---|
| M0 | Inspect the environment, repository and dependencies | Done (2026-10-17) |
| M1 | Project structure, configuration, documentation, initial desktop window | Done (2026-10-17) |
| M2 | Reliable single-connection HTTP downloader | Done (2026-10-18) |
| M3 | Progress, cancellation, history and persistent state | Done (2026-10-19) |
| M4 | Pause/resume and crash recovery | Done (2026-10-19) |
| M5 | Parallel segmented downloads | Done (2026-10-19) |
| M6 | Queue management and scheduling | Done (2026-10-19) |
| M7 | Chrome extension and native messaging bridge | Done (2026-10-19) |
| M8 | Browser handoff integrated with the download engine | Done (2026-10-20) |
| M9 | Interface, settings, notifications and usability | Done (2026-10-20) |
| M10 | Security, performance, regression testing and Windows packaging | Done (2026-10-20) |
| M11 | Open-source release on GitHub (Apache-2.0, UI/UX, more browsers) | Done (2026-10-25; owner's checks reported done with 1.2.0) |
| M12 | Media: video/audio converter (ffmpeg) and optional yt-dlp add-on | Done (2026-10-26; owner's install check reported done with 1.2.0) |
| M13 | SDM's own logo and look, a button over videos, the extension shipped with SDM | Done (2026-10-26) |
| M14 | Android version: a native Kotlin app (APK) | In progress (release M14f after M15) |
| M15 | Media on Android: converter (MP4, M4A, MP3) and video pages (yt-dlp, off by default) | Done (2026-11-23; released with M14f) |

## M0: Environment inspection (done)

Findings are in [research/environment-m0.md](research/environment-m0.md).

## M1: Project skeleton (done)

Acceptance criteria, all met on 2026-10-17:

- `uv run python app.py` opens the SDM window.
- `uv run pytest` passes (4 tests).
- `uv run ruff check .` and `uv run ruff format --check .` report no problems.
- The work is committed on branch `m1/project-skeleton`.

## M2: Single-connection downloader (done)

Done on branch `m2/single-connection-downloader`:

- HTTP library chosen by measurement: aiohttp (ADR-0006, `scripts/bench_http_clients/`).
- Spec FEAT-001; network policy (ADR-0007); naming and no-overwrite policy (ADR-0008).
- `sdm-get` command line; local mock server; unit, integration and security tests.

Acceptance criteria, all met on 2026-10-18: FEAT-001's tests pass (145 in total), ruff is
clean, and:

- [x] One real-world download (2026-10-17, approved by the owner):
  `uv run sdm-get https://www.python.org/ftp/python/3.14.7/python-3.14.7-embed-amd64.zip -v`
  saved 12,673,227 bytes to `Downloads` in about 3 s. The SHA-256 matched the value python.org
  publishes (`d297e5ff…a6ab1f15`), all 37 zip members passed their CRC checks, and no
  `.sdmpart` file was left behind. Nothing from the zip was run.
- [x] IDM comparison (2026-10-18): the owner ran the six scenarios in IDM by hand. They were
  checked against the scenario server's request log and the SHA-256 of every saved file, and
  recorded as OBS-001 to OBS-006 in
  [behavior-observations.md](research/behavior-observations.md). Two small fixes came out of
  it: a warning when completeness can't be confirmed (unknown size) and a "not found" message
  for 404/410. Not observed, so carried forward: IDM's live progress display for unknown sizes
  (M3) and how many times IDM retries automatically (M4).

Known issue (cosmetic, found in the real-world demo): with `-v`, log lines are printed on the
same line as `sdm-get`'s progress display, because both write to stderr and the progress line
doesn't end with a newline. The downloaded file is unaffected.

## M3: Downloads in the window, with history (done)

Done on branch `m3/window-downloads-history`:

- M3a (`6419fe8`): `DownloadManager` on its own thread (ADR-0009), the SQLite history
  (ADR-0010, data-model.md), and the speed/time-left calculator.
- M3b: the window. Add URL dialog with a "downloaded before" warning, a live table (name, size,
  percent, speed, time left, status), Cancel, Remove from list (keeps the file), a status bar
  summary, a question before quitting with downloads running, and startup notices for an
  interrupted or recovered history. Spec FEAT-002.

Checked on 2026-10-19: 234 tests pass, ruff is clean, and `uv run python app.py` opens and
closes cleanly, creating `%LOCALAPPDATA%\SDM\sdm.db` (schema 1, WAL). A screenshot with real
local downloads showed three running at once, a fourth waiting, and live numbers.

Acceptance criteria, all met on 2026-10-19:

- [x] One real-world download through the window (approved by the owner): python.org's
  `python-3.14.7-embed-amd64.zip`, through the Add URL dialog's checks and the window's
  `add_download`, with the real history and the default network policy. Saved as
  `python-3.14.7-embed-amd64 (1).zip`, because M2's copy was already there; that copy was
  untouched (same SHA-256 and timestamp). 12,673,227 bytes; the SHA-256 matched python.org's
  published value (`d297e5ff…a6ab1f15`); no `.sdmpart` left; the history entry is completed and
  verified. It took 23.9 s: the window itself measured 536 KB/s from the server. The same
  window path moves 261 MB/s over loopback, so the time was the server's and the network's.
  No "downloaded before" warning was shown, as expected: M2's copy came from `sdm-get`, which
  doesn't write to the history.
- [ ] Optional: IDM re-run R1 to compare the live progress display (makes it *Verified*).

## M4: Pause, resume and crash recovery (done)

Done on branch `m4/pause-resume`:

- M4a (`8e41d12`): resume from partial files with `Range` + `If-Range` or a 64-byte overlap
  check; only an exact `206` continues (ADR-0011). Retries continue, the `paused` state is
  added, history schema version 2, and the manager and controller can pause and resume.
- M4b: Pause, Resume (Retry for failed downloads), Resume all, and Cancel for paused entries
  in the window. A question before pausing on a server that can't resume. Quitting pauses
  ("Quit and pause them?"). Startup points to Resume / Resume all. Status tooltips say
  whether Resume continues or starts over. Spec FEAT-003.

Checked on 2026-10-19: 293 tests pass (three full runs), ruff is clean, and
`uv run python app.py` opens and closes cleanly. It upgraded the real history from schema 1
to 2 in place, keeping entry #1. A screenshot with real local downloads showed one paused at
27% beside two running.

Found and fixed while testing:

- The table had no label for `paused` (KeyError while drawing).
- The window didn't learn that a server can resume until the next state change, so Pause
  asked "Pause anyway?" for no reason.
- A race in the test server's request log (written after sending); it is now written before.
- Tests now fail at once on an unexpected message box instead of hanging.

Acceptance criteria, all met on 2026-10-19:

- [x] One real-world pause and resume across a full SDM restart (approved by the owner):
  python.org's `python-3.14.7-embed-amd64.zip`, through the window code with the real history
  and the default policy, in two separate processes. Phase 1: the Add URL dialog showed the
  "already downloaded" warning (naming the `(1)` copy); Pause at 3,932,160 of 12,673,227
  bytes (31%); partial file exactly that size; the server advertised resume, with a strong
  ETag and Last-Modified; SDM quit. Phase 2 (new process): the entry was still paused at
  3,932,160; Resume continued from there (the smallest progress shown afterwards was
  3,932,160, and the engine logged no restart); done in 3.3 s as
  `python-3.14.7-embed-amd64 (2).zip`. The SHA-256 matched python.org's published value; the
  earlier two copies were untouched; no `.sdmpart` left; history entry #2 completed and
  verified.
- [ ] Optional: IDM comparison R2 (automatic retries) and resume against a `206` server.

## M5: Parallel segmented downloads (done)

Done on branch `m5/segmented-downloads`:

- M5a (`b5590ea`): split downloads over up to 8 connections, exact-`206` validation, fallback
  to one connection, per-part retries, a sparse pre-sized partial file, an off-thread `fsync`
  before saving the parts map, history schema version 3, and resume part by part (ADR-0012).
- M5b: dynamic splitting (free connections take over half of the biggest part), the
  connection count in the window ("Downloading · 8 connections"), spec FEAT-004.

Checked on 2026-10-19: 343 tests pass (three full runs), ruff is clean, and
`uv run python app.py` opens and closes cleanly; it upgraded the real history to schema 3 with
both entries kept. With the defaults against a local server limited to 0.4 MB/s per
connection, the window showed "Downloading · 8 connections" at 3.0 MB/s.

Found and fixed while testing:

- `fsync` on the event loop thread stalled every connection on a busy disk (a speed test that
  only failed in full runs); it now runs on a worker thread.
- A part's reply was checked against its end *after* a dynamic split had shrunk it, so a
  correct reply looked wrong and the file restarted.
- The connection count briefly counted a finished part twice.

Acceptance criteria, all met on 2026-10-19:

- [x] One real-world segmented download (approved by the owner): python.org's
  `python-3.14.7-embed-amd64.zip` (12,673,227 bytes) through the window code with the real
  history, default policy and default settings. The window showed up to 8 connections
  (counts seen: 2, 3, 5, 7, 8); the engine logged no fallback, so every part came back as an
  exact `206`. Done in 3.8 s as `python-3.14.7-embed-amd64 (3).zip`; the SHA-256 matched
  python.org's published value; the three earlier copies were untouched; no `.sdmpart` left;
  the history entry is completed and verified, with its parts map cleared.
- [ ] Optional: IDM comparison against a resumable server.

## M6: Queue management and scheduling (done)

Done on branch `m6/queue-scheduling`:

- M6a (`9217188`): the queue planner (order, limit 1-8, schedule, Start now, automatic retries
  after about 1, 5 and 15 minutes), categories, history schema version 4 with a settings
  table; the controller's `pump()` is the only place that starts downloads (ADR-0013).
- M6b: a Queue menu (Start now, Move to top/up/down/bottom), the Scheduler dialog (limit,
  times and days, category subfolders), "Start the download now" in Add URL, and a Category
  column with a filter. Spec FEAT-005.

Checked on 2026-10-19: 395 tests pass (three full runs), ruff is clean, and
`uv run python app.py` opens and closes cleanly; it upgraded the real history to schema 4.
That launch check showed existing entries labelled "General": upgrades now label existing
entries from their file names (the owner's history had already been upgraded, so its three
entries keep "General"). A screenshot showed the limit of 2 holding with three waiting.

Acceptance criteria, all met on 2026-10-19:

- [x] One real-world scheduled download (approved by the owner): python.org's
  `python-3.14.7-embed-amd64.zip` through the window code with the real history and policy,
  and a schedule window of 03:10-03:40. Added at 03:08:31, it waited as Queued; the app's own
  30-second timer (no manual `pump()`) started it at 03:10:01, 1.7 s after the window opened.
  Saved as `python-3.14.7-embed-amd64 (4).zip`, category Compressed; the SHA-256 matched
  python.org's published value; the earlier four copies were untouched. The owner's queue
  settings were saved before the demo and restored afterwards (checked).
- [ ] Optional: observe IDM's queue and scheduler screens (checklist).

## M7: Chrome extension and native messaging bridge (done)

Done on branch `m7/browser-bridge` (ADR-0014, spec FEAT-006):

- `sdm.bridge.protocol`: framing (4-byte length + JSON, 64 KiB cap) and the strict request
  schema. `sdm.bridge.pipe`: the per-user key, the authenticated named pipe, the listener
  and client. `sdm.bridge.host`: the host Chrome starts; it starts SDM if needed.
  `sdm.bridge.install`: registration for one extension ID, and its removal.
- The window: a handoff opens Add URL filled in, one at a time; the pipe makes SDM
  single-instance.
- `extension/chrome`: Manifest V3, "Download with SDM" on links; `node --test` tests.

Checked on 2026-10-19: 485 Python tests pass (90 new), 12 extension tests pass, ruff is
clean. A launch check with a throwaway `LOCALAPPDATA` used the real `sdm.exe` and host
processes: a second SDM exited quietly in 1.2 s; the host handed a link to the running SDM in
0.2 s; with SDM closed, the host started it and got `{"ok": true}` in 1.5 s.

Acceptance, all met on 2026-10-19:

- [x] Register the host (approved by the owner, 2026-10-19): one HKCU value pointing at
  `%LOCALAPPDATA%\SDM\bridge\com.sdm.bridge.json`, allowed origin limited to the owner's
  extension ID. The launcher, run through `cmd.exe` as Chrome does, refused a message with an
  extra field (`bad_request`) and wrote nothing else to stdout.
  **Correction (2026-10-20):** Claude ran this registration from its sandboxed shell. The
  registry value and files were visible only to programs started inside that sandbox, so the
  owner's own Chrome kept answering "host not found" (`reg query` in the owner's terminal
  found no key). The owner then registered it from their own PowerShell, which fixed it.
  Registration must be run by the owner, in their own terminal.
- [x] Chrome finds and runs the registered host (2026-10-19). A throwaway Chrome started from
  Claude's shell found it: an invalid link got `bad_request`, and a valid one started SDM
  (throwaway data) and got `{"ok": true}`. `scripts/chrome_bridge_check/check.mjs` repeats the
  first check, but it only proves what the environment it runs in can see. (The explanation
  given at the time, that Chrome had to be restarted, was wrong; see the correction above.)
- [x] Owner's Chrome check (in the owner's Default profile rather than a separate one; IDM's
  extension is also installed there, which didn't interfere with the right-click item):
  - With SDM closed, a right-click handoff started SDM (`sdm.exe --from-browser`, 5:42:05 PM)
    and opened "Add URL from the browser" filled in. That link was a web page, so the owner
    pressed Cancel: no history entry was added.
  - With SDM running, a handoff of python.org's `python-3.15.0-amd64.exe` was confirmed and
    completed: 45,383,680 bytes, the size the server announced, category Programs, in
    about 9 s. The file was not opened or run.
  - The first two tries said "host not found"; the try that worked used a Chrome that Claude
    had started from its sandboxed shell (to switch logging on), which could see the
    sandboxed registration. The owner's own Chrome only worked after the owner registered the
    host themselves (M8, 2026-10-20).

## M8: Browser handoff integrated with the download engine (done)

Done on branch `m8/browser-integration` (ADR-0015, spec FEAT-007):

- M8a (`c140cbf`): message version 2; the browser's Referer rules on every request, redirect
  hop, split part and resume; the browser's file name as a fallback; history schema version 5;
  https-to-http redirects refused for every download.
- M8b: the extension's opt-in capture (toolbar panel, optional `downloads` permission, minimum
  size, hand-back to Chrome without loops), right-click on video and audio, and the dialog
  showing what the browser knew.

Checked on 2026-10-19: 570 Python tests and 36 extension tests pass; ruff is clean. In a
throwaway headless Chrome, the new extension loaded, and a full version 2 message (referrer,
name, size, `source: capture`) went through the host to a throwaway SDM: `{"ok": true}`.

Fix after the owner's first capture test (`63ec0bf`, extension 0.2.1): with extension 0.2.0,
one click made Chrome's Save As appear again and again. Capture ran at `onCreated` (after Save
As), SDM's handoff failed ("host not found", see the M7 correction), and the download handed
back to Chrome was captured again because Chrome hadn't marked it with `byExtensionId`. Capture
now runs in `onDeterminingFilename`, before Save As; a handed-back address is left alone for
60 s; the toolbar panel shows the last problem.

Acceptance, met on 2026-10-20 in the owner's own Chrome (Default profile, capture on, the host
registered by the owner from their own PowerShell):

- [x] A normal left-click on python.org's "Windows embeddable package (64-bit)" opened SDM's
  "Captured from Chrome" dialog. SDM saved `python-3.14.7-embed-amd64 (5).zip`, 12,673,227
  bytes, completed; its history (schema version 5, the owner's real history, entry #1) keeps
  the browser's name `python-3.14.7-embed-amd64.zip` and the page `www.python.org`. The
  SHA-256 matches python.org's published value (checked in the owner's own terminal).
- [x] Hand-back: while the host couldn't be found, the panel said why and Chrome downloaded the
  file itself, showing Save As once (extension 0.2.1).
- [x] "Ask where to save each file" is on in the owner's Chrome. With 0.2.0, Chrome's Save As
  appeared before capture; 0.2.1 captures before it.
- Not checked separately: turning capture off again (covered by `popup.test.js`).

Note: the owner's real SDM history started with this download. The histories of the M2-M7
demos were written from Claude's sandboxed shell and are not in the owner's
`%LOCALAPPDATA%\SDM` (their downloaded files in Downloads are real).

## M9: Interface, settings, notifications and usability (done)

Done on branch `m9/usability` (ADR-0016, spec FEAT-008):

- M9a (`d6a5e57`): the shared speed limit, connections per download, SHA-256 checks (history
  schema version 6), SDM's saved preferences, and signals for notifications.
- M9b: the Settings dialog, the SHA-256 box in Add URL, the tray icon (optional close-to-tray),
  Windows notifications, the list's right-click menu, Show in folder (no Open), Copy address,
  Verify checksum, total speed in the status bar, remembered window layout and last folder.

Checked on 2026-10-20: 628 Python tests and 38 extension tests pass; ruff is clean. In tests a
3 MiB download split over several connections was held to 1 MiB/s (about 3 s). Screenshots of
the window, the menu and the Settings tabs (drawn off-screen with throwaway data) look right.

Acceptance, met on 2026-10-20 in the owner's own SDM (started from their own PowerShell):

- [x] With Settings > Connections at 1024 KB/s, python.org's `python-3.14.7-embed-amd64.zip`
  (12.1 MB) downloaded at about 1.0 MB/s and took about 12 s (owner's report). Entered with its
  published SHA-256, it ended "Completed · verified"; the saved `python-3.14.7-embed-amd64
  (6).zip` (12,673,227 bytes) matches python.org's SHA-256 on disk.
- [x] The Windows notification "Download complete and verified" appeared (owner's report).
- [x] Show in folder opened File Explorer with the file selected, without opening it.

Found in the demo and fixed: the status bar was empty. Qt replaces a status *message* with the
empty hint of any toolbar button or menu item the mouse passes over; the status now lives in a
permanent label (`test_the_status_line_survives_hovering_over_buttons`).

## M10: Security, performance, regression testing and Windows packaging (done)

On branch `m10/hardening` (ADR-0017):

- M10a: NAT64/6to4 addresses checked as their IPv4 address; the browser pipe refuses remote
  clients; seeded fuzz tests of every parser of untrusted data, which found and fixed five
  bugs; a rotating, redacted log file; a crash safety net; Help > Open log folder and About;
  `pip-audit`: no known vulnerabilities.
- M10b: performance measurements ([research/performance-m10.md](research/performance-m10.md)),
  which found that pre-sizing a split download's file wrote the whole file as zeros on the
  event loop (fixed with `set_file_size`); `scripts/check_all.py` runs every check;
  five full runs in a row passed (670 tests each, 2026-10-20): no flaky tests found.
- M10c: PyInstaller build (`dist\SDM`: SDM.exe, sdm-bridge-host.exe, licence notices) and a
  per-user Inno Setup installer script; Help > Set up / Remove Chrome integration for the
  installed app (ADR-0018). Smoke test of the build with throwaway data: the packaged host
  refused a bad message, SDM.exe started and logged version 0.1.0, the host handed a link
  to it, and with SDM closed the host started SDM.exe itself.

Acceptance, met on 2026-10-20 on the owner's PC:

- [x] The owner installed Inno Setup 6 and built `dist\SDM-Setup-0.1.0.exe` (30.2 MB) in their
  own PowerShell (2026-10-20; Claude's sandbox can't see the Inno Setup install).
  `build_release.py` now also finds Inno Setup through its uninstall registry entry, and has
  `--skip-build` (reuse `dist\SDM`) and `--iscc PATH`.
- [x] Installed per user into `%LOCALAPPDATA%\Programs\SDM` without
  administrator rights. Started from the Start menu: the history was there; Help > About
  showed SDM 0.1.0; python.org's embeddable zip with its SHA-256 ended "Completed · verified"
  (`(7).zip`).
- [x] Help > Set up Chrome integration registered the installed `sdm-bridge-host.exe`. The
  first capture failed ("SDM couldn't take the link"; Chrome kept the file, once): that SDM
  was running as administrator, and Windows refused Chrome's normal-level helper ("Access is
  denied", reproduced by running the installed host by hand). An SDM started normally (by the
  helper) accepted the link, and a normal left-click on python.org's zip then opened the
  installed SDM's "Captured from Chrome: python-3.14.7-embed-amd64.zip · 12.1 MB ·
  application/zip · from a page on www.python.org" dialog. SDM now warns at startup when it
  runs as administrator, and the host logs to `bridge-host.log` (`fa02d46`).
- [x] Uninstalled from Settings > Apps: the Start-menu entry and the Chrome registration were
  removed (`reg query` found no key); the history (`%LOCALAPPDATA%\SDM\sdm.db`) was kept.
- Note: the installer the owner tested (built before `fa02d46`) doesn't include the
  administrator warning or the host log yet; rebuild with
  `uv run python scripts/build_release.py --installer` to get them.

## M11: Open-source release on GitHub

Approved by the owner on 2026-10-20 (after M10): publish SDM as open source on their own
GitHub repository under the Apache License 2.0, with a UI/UX update (dark mode and a modern
look, progress bars and a details panel, a first-run setup assistant), an extension for
Chrome, Edge, Brave and Firefox (free Edge and Firefox stores plus GitHub zips), automated
GitHub releases, version 1.0.0, and the commit author email rewritten to the owner's GitHub
noreply address before the first push (owner to confirm right before).

- M11a: `LICENSE` (the official Apache-2.0 text, SHA-256 `cfc7749b...3d30`) and `NOTICE`;
  version 1.0.0 with licence metadata in `pyproject.toml` (the "do not upload" marker is
  gone); a public README, `CONTRIBUTING.md` and `SECURITY.md`; personal paths and the owner's
  extension ID replaced by neutral examples; "personal use" wording updated (About box,
  `CLAUDE.md`, a historical note on the brief).
- M11b (`c2c6e21` and this commit): light/dark themes following Windows, progress bars, a
  details panel, and a welcome assistant (folder, appearance, browsers). A test found that
  changing the theme with the assistant open crashed Qt; the style is now set only once.
- M11c: Edge, Brave and Firefox besides Chrome (ADR-0019): a fixed Chromium extension ID,
  per-browser registration with no typing, Firefox's own manifest, host arguments and
  capture path, `scripts/build_extensions.py` (Chromium, Edge store and Firefox zips), and
  the uninstaller cleaning up every browser. Checked: a throwaway headless Chrome and Edge
  each loaded the extension with the fixed ID and reached SDM's helper. Brave and Firefox
  aren't installed here: their parts are tested with fakes only.
- M11d: GitHub Actions: `checks.yml` (lint, format, Python tests on 3.12 and
  3.14, extension tests, on every push and pull request) and `release.yml` (a `v*` tag must
  match SDM's version; it builds the installer and the extension zips and publishes the
  installer, a portable zip and the Chromium zip with SHA-256 checksums; Edge and Firefox use
  their stores, ADR-0019). Actions are pinned to commit SHAs (checkout v7.0.1, setup-node
  v7.1.0, setup-uv v10.3.2). Checked locally on 2026-10-25: both workflows parse; the checks
  pass on Python 3.12 (scratch venv) and 3.14 (704 tests); `build_release.py --installer` and
  `build_extensions.py` built 1.0.0; the collect step ran in Windows PowerShell 5.1 (GitHub
  uses PowerShell 7). Not checked: the workflows on GitHub itself. Left: the repository address,
  the email rewrite (owner confirms first), the first push and release, and the owner's install
  test from the release.
- 1.0.0 was released on 2026-10-25 from github.com/Conditions-tech/SDM (history rewritten
  first so it shows only the owner's GitHub name). The owner's first try found that a click on
  a download still opened Chrome's Save As, since capture was off until switched on; at their
  request 1.0.1 turns capture on from installation (ADR-0015, amended).
- Installing 1.0.1 over 1.0.0 left `sdm-1.0.0.dist-info` beside `sdm-1.0.1.dist-info`, so
  About said 1.0.0 (owner's report). 1.0.2: the installer clears `{app}\_internal` first;
  tested with a throwaway Inno Setup installer over a fake old install.

**M11 marked done on 2026-10-25 at the owner's request.** Evidence:

- [x] Public repository github.com/Conditions-tech/SDM under Apache-2.0; every commit and tag by
  `Conditions-tech` with its GitHub noreply address; no personal name, email or path in any
  file or commit message (checked over the whole history before the push).
- [x] GitHub Actions on GitHub: Checks passed on Python 3.12 and 3.14 and for the extension;
  Release published 1.0.0, 1.0.1 and 1.0.2 (installer, portable zip, Chromium zip, SHA-256
  sums).
- [x] Owner: installed 1.0.0 and 1.0.1 from the releases (About box; Windows Apps listing),
  with earlier downloads kept. Their reports led to 1.0.1 (capture on from installation) and
  1.0.2 (About version after an update).
- [x] Headless Chrome and Edge loaded the extension with the fixed ID and reached SDM's
  helper; Chrome again with 1.0.1's permissions.
- [ ] **Open:** the owner's upgrade to 1.0.2 (About shows 1.0.2), and a click on a download in
  Chrome and in Edge opening SDM's dialog with 1.0.1 or later. To record when the owner runs it.
- [ ] **Open:** Brave and Firefox on a real PC; Edge Add-ons and Firefox Add-ons listings (the
  owner's submissions; their IDs then go into `browsers.py` and `sdm.core.links`).

## M12: Media converter and optional video-site add-on (done)

Approved by the owner on 2026-10-25 (option B), to start after M11d on branch `m12/media`.
Detailed plan approved 2026-10-25: the user installs ffmpeg and yt-dlp (SDM finds them);
yt-dlp does the video downloading; M12a converter, M12b video add-on, then release 1.1.0.

- M12a (done): ADR-0020; `sdm.media.ffmpeg`, the Convert window, Settings > Media, Convert… on
  finished downloads; tests with a fake ffmpeg, plus `test_convert_real_ffmpeg.py`, which uses
  the real one when installed. With ffmpeg 9.0.2 (winget, in Claude's sandbox) every format
  converted correctly (ffprobe), and the bar moved during a 30 s 720p conversion. The first
  real run showed ffmpeg's unhelpful "Error opening output files: Invalid argument" for a
  silent video; SDM now says "This file has no sound…". Left: the owner's check in their SDM.
- M12b (done): `sdm.media.ytdlp` and `VideoRunner`, which runs yt-dlp and reports with the
  engine's own events, so a video is an ordinary list entry (queue, pause, resume, cancel,
  history, notifications). Settings > Media turns it on after the terms notice; Add URL gets
  "Download as". History schema 7 (`video`). 23 new tests with a fake yt-dlp. Then, with the
  real yt-dlp 2026.10.21 and ffmpeg (winget, Claude's sandbox) against a test server on
  127.0.0.1: every option SDM passes was accepted; a page with a `<video>` came out as 1080p
  MP4 and MP3, a direct link as 720p (ffprobe). That run found two rough edges, now fixed: a
  failed download left an empty `.sdm-video-<id>` folder, and a 404 showed yt-dlp's internal
  wording. `test_video_real_ytdlp.py` repeats it where both tools are installed. Left: the
  owner's check on a real video page they may download.
- The owner ran the M12 test plan in their own SDM from source (separate data folder):
  Settings > Media, the converter and video pages "all worked" (2026-10-25). They then asked
  for a friendlier way in and chose the browser: "Download this video with SDM" in the
  extension's right-click menu and toolbar panel sends the page as `source: "video"`; SDM
  opens Add URL set to a video at the last quality used (ADR-0020). Checked: extension and
  Python tests; a throwaway headless Chrome and Edge loaded the changed extension and reached
  SDM's helper. The owner reported it working in their browser with 1.2.0 (2026-10-26).
- M12c: version 1.1.0 (SDM and the extension), README features, release notes with the
  optional winget installs, changelog.

**M12 marked done on 2026-10-26 at the owner's request.** Evidence:

- [x] SDM 1.1.0 released from `v1.1.0` (`4c41826`): Checks and Release passed on GitHub;
  installer, portable zip, Chromium zip and SHA-256 sums published.
- [x] Converter and video pages: tests with fakes, plus the real ffmpeg 9.0.2 and yt-dlp
  2026.10.21 in Claude's sandbox (local test server only, checked with ffprobe); SDM's real
  window driven off-screen; the PyInstaller build started cleanly.
- [x] The owner ran the M12 test plan from source: Settings > Media, the converter and video
  pages "all worked" (2026-10-25).
- [x] "Download this video with SDM": extension and Python tests; headless Chrome and Edge
  loaded the extension and reached SDM's helper.
- [ ] **Open:** the owner's install of 1.1.0 from the release (About shows 1.1.0), the browser
  reconnected through the setup assistant, and "Download this video" clicked in a real
  Chrome and Edge. To record when the owner runs it.
- [ ] **Open:** pause and resume of a real yt-dlp download (only the fake yt-dlp covers it).

- **Converter (core feature):** convert a local file with ffmpeg, for example video to MP3
  audio, or MKV/WEBM to MP4. ffmpeg runs as a separate program; SDM never runs downloaded files
  and converts only files the user picks.
- **Video-site downloads (optional add-on, off by default):** the user turns on yt-dlp
  themselves; SDM does not ship it. Choices: 720p, 1080p, 4K (2160p) or audio only (MP3/M4A).
  1080p and 4K arrive as separate video and audio streams that ffmpeg merges. SDM shows a
  notice that the user is responsible for each site's terms.
- **Rule change:** the brief's "respect website terms" rule gets a note that site-specific
  downloads happen only through the user-enabled add-on; DRM, paywalls, logins and access
  controls stay off limits (no DRM-protected content, no cookie or account extraction).
- **Before code:** check yt-dlp (Unlicense) and ffmpeg (LGPL/GPL builds) licences and
  Windows support, and record an ADR. URLs from the add-on still count as untrusted input.
- **Tests:** synthetic media files and a fake yt-dlp executable; never real video sites.
- **Known limit:** no GPU here, so 4K conversion runs on the CPU and will be slow.

## M13: SDM's own look, a button over videos, the extension with SDM (done)

Asked for by the owner on 2026-10-26 (screenshot of a video page): a small SDM button over a
playing video, SDM's own icon instead of the Python one, an updated window, and the extension
added when SDM is installed. Owner's choices: the button on any site after a switch in the
extension; $0 guided install (no $5 Chrome Web Store); all four window updates; the hexagon
logo (from nine drawn options). Design: ADR-0021. Branch `m13/look-and-video-button`.

- Logo everywhere: `sdm.svg` and `scripts/make_icons.py` (window/tray PNGs, `sdm.ico` for
  SDM.exe, the helper and the installer, the extension's icons). Checked: the built SDM.exe
  carries it (icon extracted from the file).
- Window: toolbar icons with labels, the sidebar with counts, file-type icons, coloured status
  (WCAG AA tested), two-column details, Add URL with quality buttons and folded options.
  Checked: tests and off-screen screenshots in light and dark.
- Video button: `video-button.js`, registered only while page access is allowed (panel
  switch). Checked: extension tests and `video_button_check.mjs` in headless Chrome and Edge
  (placement, closed shadow root, a scripted click ignored, a real click sends the page).
- The extension ships in `SDM\extension`; the assistant's "Add to Chrome/Edge/Brave" opens the
  browser's extensions page, copies the folder and shows the three steps; Help > Add the
  browser extension. Checked: tests and a build (`dist\SDM\extension`).
- Released as 1.2.0 (tag `v1.2.0`, `9522b61`) on 2026-10-26; post texts and images for
  Facebook, Instagram and LinkedIn prepared for the owner (`dist\social`, not in Git).
- The owner's first look at 1.2.0 found the desktop icon still showing the Python logo: it
  was 1.1.0's shortcut (1.2.0 wasn't installed yet; Windows' icon cache can also lag). A
  DownGit zip went to Chrome's Save As: correct, a `blob:` download made inside the page
  has no address SDM could fetch (ADR-0015).

**M13 marked done on 2026-10-26.** The owner reported the 1.2.0 checks done (no screenshots):
install 1.2.0 (About 1.2.0, the new icon), add the extension with the assistant, turn on the
video button and click it on a video page, a normal link opening SDM. This also covers the
install checks left open in M11 and M12.

**Fix after M13 (2026-10-27): the browser connection after a reinstall.** The owner's Chrome
(and some users') logged "Specified native messaging host not found" after reinstalling SDM.
Cause, found in the code: the uninstaller deletes the registry keys and host manifests
(`packaging/sdm.iss`, by design so no browser points at a deleted program), but the welcome
assistant, the only thing that registers, runs once and its flag lives in the kept settings.
SDM now remembers the connected browsers and repairs their registration at every start;
regression tests in `tests/unit/test_browsers.py` and `tests/unit/test_packaging.py`. The
owner's own check (reinstall, open SDM, click a link in Chrome) is still to do.

**Fix after M13 (2026-10-27): "Add to Chrome" opened a blank tab.** The owner pressed Add to
Chrome in the welcome assistant and no extension appeared. The button starts the browser with
`chrome://extensions` and copies the extension folder; starting Chrome and Edge that way with a
throwaway profile showed they open `chrome://newtab/` / `edge://newtab/` instead (an ordinary
web address opens fine), while the guide said "In the window that opened". The guide now
starts with typing the address; regression tests in `tests/integration/test_extension_setup.py`.
The owner's own check (follow the four steps in Chrome) is still to do.

**Released as 1.2.1 on 2026-10-27** with both fixes above (tag `v1.2.1`; the release workflow
builds and publishes the files). The owner's check as a user (uninstall, install 1.2.1 from
GitHub, add the extension, a link reaching SDM, reinstall again) is still to do.

**Fix after 1.2.1 (2026-10-28): Telegram Web downloads.** Two downloads from Telegram Web
(`https://web.telegram.org/k/d/<number>`) went to SDM and failed. A direct request for such an
address got a redirect to Telegram Web's HTML page, not a file: the page makes the file itself
for the signed-in user. The extension's capture now leaves `web.telegram.org` to the browser
(`PAGE_MADE_HOSTS` in `extension/chrome/capture.js`; regression test in `capture.test.js`).
Not released yet.

**Fix after 1.2.1 (2026-10-28): Facebook videos.** Two video-page downloads failed: the
address of Facebook's news feed (`https://www.facebook.com/`, where the owner pressed the video
button) and a story. yt-dlp 2026.10.21 handled both with its generic extractor, not its
Facebook one; on a page with no video it ends with "Unsupported URL" (checked on a local page;
Claude's sandbox couldn't reach facebook.com). Stories need a login, which SDM never uses
(ADR-0020). SDM's reason now says to open the video on its own page and that stories and
private posts need a login (`NO_VIDEO_TEXT`, `LOGIN_TEXT` in `sdm.media.ytdlp`); extension
README updated. Not released yet.

## 1.3.0: web media fixes (2026-11-22)

The owner's reports: YouTube MP3 arriving as .webm, and Telegram, WhatsApp, Facebook,
Instagram, Pinterest and TikTok downloads failing; and an Edge Add-ons listing. Done: images in
the right-click menu; the video button sends a post's own address on feeds; an opt-in browser
login for video pages (the owner's choice, 2026-11-22); a format check so an unconverted MP3 is
never "Completed" (regression test); a Deno hint for YouTube; Telegram Web left to the browser;
the Edge Add-ons kit (`docs/store/edge-addons.md`). Not possible: Telegram and WhatsApp Web
files exist only inside their tab (`blob:`). Not tested against the real sites (tests use local
servers only): the owner checks them in 1.3.0.

## M14: Android version (in progress)

Asked for by the owner on 2026-10-26; plan approved the same day (ADR-0023). Owner's choices:
native Kotlin with Jetpack Compose, Android 10 or newer, device checks on the owner's phone over
USB, a signed APK on GitHub Releases (no Google Play). The project lives in `android/`.

- M14a: ADR-0023; the `android/` Gradle project (`engine` Kotlin/JVM module, `app` Compose
  app) with the hexagon logo; a debug APK builds; tests run; a GitHub Actions job builds it.
  Checked on 2026-10-26: `gradlew :engine:test :app:assembleDebug` passed in 1 min 34 s on
  this PC (3 engine tests); the APK reports `io.github.conditionstech.sdm` 0.1.0, minSdk 29,
  only the INTERNET permission (`aapt2 dump badging`). The emulator could not start: it needs
  Windows' Android Emulator hypervisor driver, which is not installed (a system change, so
  left to the owner); device checks use the owner's phone. Not checked: the job on GitHub.
- M14b: the engine's safety parts and a single-connection download: network policy (ADR-0007,
  ADR-0017), file names and no overwriting (ADR-0008), hand-followed redirects without
  https-to-http (ADR-0015), errors and retries; tests against a local MockWebServer.
  Done on 2026-10-26: `Urls`, `NetworkPolicy` + `PolicyDns` + a check of the connected
  socket, `FileNames`, `PartFiles` (claim-then-move, never overwriting) and `HttpDownloader`
  (OkHttp 5.5.1, coroutines, cancellable mid-read). 39 engine tests, three full runs in a row
  passed (about 6-16 s each); breaking the loopback rule on purpose failed 6 of them.
- M14c: resume (ADR-0011) and splitting over up to 8 connections (ADR-0012).
  Done on 2026-11-22: `Segments` (plan, saved map in the Windows format, dynamic splitting,
  Content-Range), resume in `HttpDownloader` (Range + If-Range, the 64-byte overlap check, 416,
  start over on anything untrusted, `keepPartial` for pause), and `SegmentedDownload` (first
  connection + exact-206 parts, fallback, per-part retries, dynamic splitting, the parts map
  saved after a disk sync). 69 engine tests, eight full runs in a row passed (about 6 s each).
  Breaking five checks on purpose (part range, resume range, overlap, version tags on parts and
  on resume) each failed a test; the first try found two weak tests, which were strengthened.
- M14d: the app: Add URL, the list, a foreground service with a progress notification, the
  SQLite history, saving to Download/SDM through MediaStore.
  Built on 2026-11-23: `Downloads` (the list's controller, plain Kotlin: add, pause, resume,
  cancel, remove; running downloads come back paused after a restart), `SqliteHistory`
  (schema 1), `MediaStorePublisher` (partial files in the app's own storage, the finished file
  copied to Download/SDM, hidden until complete, MediaStore numbering taken names),
  `DownloadService` (dataSync foreground service with a progress notification; paused on
  Android 15's 6-hour limit), finished/failed notifications, and the Compose list and Add URL
  dialog. Checked: 10 controller tests against a local server (in-memory history, a folder for
  Download/SDM), the 69 engine tests, `assembleDebug`, and lint (0 errors).
  On the owner's phone (Android 16, debug APK over USB, owner-approved, 2026-11-23):
  python.org's `python-3.14.7-embed-amd64.zip` downloaded over 8 connections (about
  1.7 MB/s) into Download/SDM, 12,673,227 bytes, SHA-256 matching python.org's published value
  (`d297e5ff…a6ab1f15`); a second run paused at 847 KB, the app was force-stopped and reopened
  (still paused, 847 KB), Resume finished it as `... (1).zip` (same SHA-256; the first copy
  untouched); the partial folder was left empty; no crashes. Found and fixed: Add URL read the
  clipboard by itself, so Android showed "SDM accessed the clipboard"; it now reads it only on
  a Paste button. Not observed: the notifications' content (only that SDM's icon appeared).
- M14e: Share → SDM (Android's share menu) and the queue limit.
  Built on 2026-11-23: "Download with SDM" in Android's share menu (`ACTION_SEND`,
  `text/plain`): `SharedLinks` takes the first http(s) address that passes the URL check from
  the shared text (at most 16 KiB scanned) and opens Add URL filled in; the user still confirms.
  The queue: at most 3 downloads run, the rest wait ("Waiting") and start oldest first when a
  slot frees (`pump()`, as ADR-0013 on Windows). Checked: 16 app tests (queue order and slot
  hand-over, cancelling a waiting entry, link extraction including hostile text), lint 0
  errors, the share target registered on the owner's phone (package query). On the phone
  (Android 16): a share of "Python 3.14.7 embeddable https://www.python.org/..." opened Add URL
  with only the link filled in; a share with only `file:///...` and words opened it with "The
  shared text has no http:// or https:// link." Nothing downloaded by itself.
- M14f: a signed release APK from GitHub Actions, the owner's check on their phone, the
  release notes and social post drafts.
  Prepared on 2026-11-23: version 1.0.0 (Android has its own versions; tags `android-v*`,
  which the desktop's `v*` release doesn't match); the release key comes from GitHub secrets
  (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
  `ANDROID_KEY_PASSWORD`), else a local build is signed with the debug key;
  `.github/workflows/android-release.yml` checks the tag, tests, builds, refuses a
  debug-signed APK (checked locally: apksigner shows "CN=Android Debug" on a test build), and
  publishes `SDM-android-<version>.apk`, `THIRD_PARTY_NOTICES.md` and SHA-256 sums with
  `--latest=false` (the README's "latest release" stays Windows). The release build (R8,
  45.6 MB, both 64-bit ABIs) on the owner's phone: About 1.0.0, a shared video page downloaded
  and converted to MP3 (ffprobe: MP3 44.1 kHz stereo, 19.1 s). Found on the way: the owner's
  Download/SDM test files had been deleted in the phone's Files/Gallery app; converting one now
  says "The original file is no longer in Download/SDM" instead of "could not be saved".
  Merged main (desktop 1.2.1-1.3.3); the Android ADRs became 0023 and 0024. 814 Python tests,
  ruff clean.

Left out of the first version: the native-messaging bridge, the Windows installer, the
converter and yt-dlp, scheduler times and days, the speed limit.

## M15: Media on Android (done)

Asked for by the owner on 2026-11-23 ("fully working like SDM PC"); plan approved the same day
(ADR-0024). Owner's choices: yt-dlp built in but off by default with a terms notice; real MP3
as well as M4A. The first Android release (M14f) comes after M15.

- M15a: the converter: MP4 (rewrap) and M4A (audio) with Android's MediaExtractor/MediaMuxer,
  MP3 with MediaCodec + LAME (`lameenc` in Chaquopy, Python 3.13); progress, cancel, results in
  Download/SDM; tests with synthetic media.
  Step 1 done (2026-11-23): Chaquopy 17.2.0 with Python 3.13, yt-dlp 2026.10.26 and lameenc
  1.7.0 in the app (debug APK 31 -> 74 MB, both 64-bit ABIs); an About screen with versions and
  licences. On the owner's phone (Android 16) About listed both tools, so Python starts there;
  the app opened in 1.2 s, no crash.
  Converter done (2026-11-23): Convert on finished downloads (MP4 rewrap, M4A audio, MP3 via
  MediaCodec + LAME), progress rows with Cancel, "Converting" and finished notifications, the
  service kept alive while converting; results in Download/SDM. Conversions aren't kept in the
  history (a stopped one is started again). Checked: 6 PC tests of `Conversions` (fake
  converter) and 6 tests on the owner's phone (`ConverterDeviceTest`, Android 16) with
  synthetic files made by ffmpeg: H.264+AAC to MP4 (both tracks, about 3 s), to M4A (AAC only),
  to MP3 (MPEG audio, about 3 s, valid header), VP9+Opus WebM to MP3, and the messages for
  Opus to M4A and for a video without sound. Xiaomi's "Install via USB" prompt must be
  accepted on the phone for each test run.
- M15b: video pages with yt-dlp in Chaquopy: Settings switch + terms notice; 720p, 1080p, MP3,
  M4A; MP4+M4A streams merged by MediaMuxer; pause/resume/cancel in the list; tests with a
  fake yt-dlp and a local page; one real check on the owner's phone, with their OK.
  Built on 2026-11-23. Before building, with the owner's OK: yt-dlp 2026.10.26 without a
  JavaScript runtime listed 24 formats for YouTube's "Me at the zoo" (warning "n challenge
  solving failed"), and the first bytes of streams 140 (M4A) and 160 (MP4) came back as normal
  `206` replies in 0.22 s and 0.06 s (in memory, nothing kept): works today, a recorded risk.
  Design: yt-dlp only lists the page's direct http(s) streams (`sdm_media.extract`, no cookies,
  no downloads, silent); SDM's engine downloads them (network policy, resume, connections, an
  allow-list of request headers: User-Agent, Referer, Origin, Accept, Accept-Language);
  `VideoPages.choose` takes H.264 MP4 video and AAC audio no higher than asked; MediaMuxer joins
  them; MP3/M4A reuse the converter. The page address is checked (`checkDestination`) before
  yt-dlp reads it. Settings switch, off by default, with the terms notice; Add URL's "Download
  as". Pause/resume of a video works while SDM runs; after a restart it starts over. History
  schema 2 (`video_choice`). Checked: 72 engine tests, 33 app tests (three runs), the Python
  part on the PC against a local page (`android/tools/check_extract.py`), and 8 tests on the
  owner's phone (yt-dlp in Chaquopy reading a page served on the phone; MediaMuxer joining a
  video-only and an audio-only file; the 6 converter tests). Found by the tests: a title such
  as "part 1/2" lost its first half (slashes in titles now become `_`).
  Real check on the owner's phone (owner-approved, 2026-11-23): Settings showed the notice;
  YouTube's "Me at the zoo" as 720p finished in under 25 s as `Me at the zoo.mp4` in
  Download/SDM, 743,777 bytes; ffprobe: H.264 320x240 + AAC, 19.06 s (the best H.264 this
  video has); no crash. Note: `connectedDebugAndroidTest` uninstalls SDM afterwards, which
  wipes the phone's SDM history (files in Download/SDM stay).
- M15c: sharing a video page asks "file or video?" and the quality.
  Done on 2026-11-23: `SharedLinks.looksLikeVideoPage` (known video sites, or an address whose
  last part has no file extension) preselects 720p in Add URL when video pages are on, and
  otherwise says where to turn them on; the user can still choose. Checked: 34 app tests; on
  the owner's phone a shared "Me at the zoo https://youtu.be/..." opened Add URL on 720p.

Facebook and Instagram (owner's own test, 2026-11-23): both failed on the phone with "no
1080p (or smaller) MP4". yt-dlp had read both pages without a login; the bug was SDM's: the
ready-made MP4s (Facebook `sd`/`hd`, Instagram `1`-`3`) have *unknown* codecs, which the Python
side turned into "none" (no video). Their separate streams are AV1 (Facebook) and VP9
(Instagram). Fixed: unknown codecs stay unknown and ready-made MP4s are used; AV1 + M4A is
allowed on Android 14+ (MediaMuxer); "720p" compares the shorter side, so portrait reels
(720 x 1280) count. After Retry on the phone: Instagram 720x900 H.264 + AAC, 25.7 s;
Facebook 720x1280 H.264 + AAC, 18.6 s (ffprobe). TikTok not tested yet (no link).

Found before the first push (2026-11-23): the root `.gitignore` rule `downloads/` (SDM's
runtime folders) had hidden the app's `downloads` source package, so M14d-M15c's list,
history, MediaStore saving, Share and their tests were never committed; `android/.gitignore`
now un-ignores `app/src/**/downloads/`. A fresh clone of the branch then built and passed all
106 tests (72 engine, 34 app). From now on, release checks build from a fresh clone.
