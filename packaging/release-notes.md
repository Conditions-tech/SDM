## Install

1. Download **SDM-<version>.zip** below and unzip it (right-click > Extract All). Run
   **SDM-Setup-<version>.exe** from it. It installs for your Windows account
   only and needs no administrator rights. Windows SmartScreen may warn that the publisher is
   unknown (SDM isn't code-signed): choose **More info**, then **Run anyway**.
2. SDM's welcome assistant connects your browsers. For each one, press **Add to Chrome** (or
   Edge, Brave) and follow the four steps it shows (the first: type `chrome://extensions` in
   the browser's address bar). The extension comes with SDM, so there's nothing else to
   download. Later: **Help > Add the browser extension**.
   Reinstalling or updating SDM keeps the browsers connected: SDM reconnects them at start.
3. Optional: in the extension's panel, tick **Show the SDM button on videos**.
4. Firefox: from Firefox Add-ons (Firefox 128 or newer).

Optional, for converting and video pages (both free; then restart SDM):
`winget install Gyan.FFmpeg` and `winget install yt-dlp.yt-dlp`. Video pages are off until
you turn them on in Tools > Settings > Media.

The zip also holds **sdm-chromium-<version>.zip** (the extension on its own) and
**SHA256SUMS.txt** (the checksums of both files).

Check the download: `Get-FileHash .\SDM-<version>.zip` in PowerShell must print the
checksum listed below.
