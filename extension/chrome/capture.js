// Automatic capture (M8): which of Chrome's downloads go to SDM, and what SDM is told.
// Pure logic, no chrome.* calls, so `node --test` can check every rule.
//
// Capture is on from installation (1.0.1, the owner's choice: the "downloads" permission is
// asked for at install); the toolbar panel's switch turns it off and on again ("enabled").

import { messageFor, webUrl } from "./handoff.js";

export const SETTINGS_KEY = "sdmCapture";
export const DEFAULT_SETTINGS = { enabled: true, minMegabytes: 0 };
export const MAX_MIN_MEGABYTES = 100000;

// Why a download is left to Chrome, or null when SDM should take it.
// `item` is a chrome.downloads.DownloadItem; `ownId` is this extension's ID.
export function reasonToSkip(item, { enabled = true, minMegabytes = 0, ownId } = {}) {
  if (enabled === false) return "capture is off";
  if (item === null || typeof item !== "object") return "not a download";
  if (item.byExtensionId && item.byExtensionId === ownId) {
    return "handed back to Chrome by SDM's extension"; // never capture our own fallback
  }
  if (item.incognito) return "incognito";
  if (item.state && item.state !== "in_progress") return "not in progress";
  if (webUrl(item.url) === null) return "not an http(s) address"; // blob:, data:, file:, ...
  if (madeByThePage(item.url)) return "made inside the page";
  const size = knownSize(item);
  if (size !== null && size < minMegabytes * 1024 * 1024) return "smaller than the minimum";
  return null;
}

// What SDM is told about a captured download.
export function captureMessage(item) {
  return messageFor(item.url, {
    referrer: item.referrer,
    filename: item.filename,
    size: knownSize(item) ?? undefined,
    mime: item.mime,
    source: "capture",
  });
}

// The panel's minimum size: a whole number of megabytes from 0 to 100000, else null.
export function parseMinMegabytes(text) {
  const value = String(text ?? "").trim();
  if (!/^\d{1,6}$/.test(value)) return null;
  const number = Number(value);
  return number <= MAX_MIN_MEGABYTES ? number : null;
}

// Sites whose downloads only exist inside their open tab: the page fetches the file itself
// (Telegram Web over its own protocol, for the signed-in user) and hands Chrome an address
// its service worker answers. Asked directly, the server sends no file (web.telegram.org/k/d/…
// redirects to the app's HTML page; the owner's report, 2026-10-28), so SDM could only fail.
export const PAGE_MADE_HOSTS = ["web.telegram.org"];

function madeByThePage(url) {
  try {
    return PAGE_MADE_HOSTS.includes(new URL(url).hostname);
  } catch {
    return false;
  }
}

function knownSize(item) {
  for (const value of [item.totalBytes, item.fileSize]) {
    if (Number.isInteger(value) && value > 0) return value;
  }
  return null; // Chrome often doesn't know the size yet when the download starts
}
