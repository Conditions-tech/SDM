# SDM privacy policy

*Applies to SDM (Smart Download Manager) for Windows and its browser extension "SDM: Download
with SDM" for Microsoft Edge, Google Chrome, Brave and Mozilla Firefox. Last updated
2026-11-22.*

**In short: SDM collects nothing and sends nothing to its makers or to anyone else.** It has no
accounts, no analytics, no advertising and no servers of its own.

## What the extension handles, and where it goes

The extension talks only to the SDM program on your own computer (through the browser's
"native messaging"). It never sends anything over the internet itself.

- **When you click a download, or choose "Download with SDM" or the SDM button over a video**,
  the extension passes SDM on your computer: the file's or page's address, the page it came
  from, and the file name, size and type the browser reported. SDM then asks you to confirm.
- **Settings** (whether downloads go to SDM, the minimum size) stay in the browser's own
  extension storage on your computer.
- **The SDM button on videos** is off until you turn it on; the browser then asks for page
  access. The button only looks at where videos are on the page, and sends the page's address
  to SDM when you click it. It does not read, store or send what pages say.
- **The extension never reads your cookies, passwords or browsing history.**

## What the SDM program does

SDM downloads the files you confirm, directly from the website that offers them, and keeps a
history of your downloads (addresses, file names, sizes, dates) in a file on your computer
(`%LOCALAPPDATA%\SDM\sdm.db`). You can remove entries from the list at any time; uninstalling SDM
keeps your downloaded files.

- **Video pages (optional, off by default)** are downloaded by yt-dlp, a separate free program
  you install yourself. If you also turn on "Login for video pages" in SDM's settings, yt-dlp
  reads the login (cookies) of the one browser you chose, on your computer, to download a video
  you're allowed to see; it is sent only to that video's own website, as your browser would.
- SDM's logs on your computer never contain cookies, passwords or private headers.

## Children, sharing, changes

SDM is a general-purpose tool and doesn't knowingly handle children's data. Nothing is sold or
shared. Changes to this policy are published in this file, in SDM's public repository:
https://github.com/Conditions-tech/SDM

Questions: open an issue at https://github.com/Conditions-tech/SDM/issues
