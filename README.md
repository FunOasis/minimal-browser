# Minimal Browser

A small, fast Android WebView browser. No bundled ad-blocklists, no
telemetry, no third-party SDKs. Every network-facing feature is
opt-in.

## Features

**Browsing**
- Multi-tab (max 10, LRU-evicted), auto-freeze of idle tabs
- Session restore across app kill, reboot, and swipe-from-recents
- Desktop mode (per-host UA), find-in-page, JS toggle (per-tab)
- HTML5 fullscreen, `target="_blank"` popups open in new tabs

**Ad blocking** (opt-in — ships with zero lists)
- Host-based blocking from user-added subscription URLs, merged into a
  single sorted `LongArray` of FNV-1a hashes (~1.2 MB at 150k hosts)
- Cosmetic filtering from EasyList `##` / `#@#` rules, injected as one
  CSS block per page — no `MutationObserver`, no runtime DOM walks
- Custom host list, custom URL substring patterns, `@@` exceptions
- Tracking-param stripping (`utm_*`, `fbclid`, `gclid`, …) on main-frame
  navigations only
- Per-site disable / desktop-site override

**Downloads**
- `http(s)`: in-process, cookies + referer + UA forwarded (Chrome-style,
  not `DownloadManager`)
- `blob:` / `data:`: streamed in 1 MB chunks over a JS bridge → no OOM
  on large canvas exports
- Status-bar progress notifications; merged list view backed by
  `DownloadRepository` (system `DownloadManager` + local records)

**Storage**
- History (searchable, sortable, favicons, `Clear older than 30 d`)
- Bookmarks (searchable, sortable)
- Password vault: `EncryptedSharedPreferences` (AES256-SIV keys,
  AES256-GCM values, master key in Android Keystore). Autofill pill on
  detected password fields; per-site "never save" list; add / edit /
  delete / clear-all
- Add-to-home-screen launcher shortcut per page

## Architecture notes

**Two warehouses under `filesDir/`**
- `blocklists/` — one ZIP per subscription, merged to `HostSet`
  (sorted FNV-1a 64-bit `LongArray`; membership = binary search of
  reversed-host prefixes)
- `cosmetics/` — one ZIP per EasyList-format list, parsed once per boot
  into generic + per-domain selector sets

Both refresh via `WorkManager` every 12 h with per-list freshness
windows (24 h hosts, 72 h cosmetic), conditional-GET validators, retry
backoff on failure, and a fingerprinted flat cache for the host set so
cold start skips ZIP decompression.

**Persistence** — history / bookmarks / prefs in `SharedPreferences`;
favicons as PNGs in `filesDir/history_icons/` with an in-memory
`LruCache`; parsed history list cached in memory so address-bar
suggestions are O(1) after first load. Session snapshot written in
`onStop`.

**Security** — `allowFileAccess=false`, mixed content never allowed,
geolocation off, third-party cookies off, Safe Browsing on. SSL errors
are refused (`handler.cancel()`).

## Build

CI-only, via GitHub Actions (`.github/workflows/build.yml`).

- **Push to `main`** → debug APK artifact on the workflow run
- **Push a tag** `v1.2.3` or `1.2.3` → signed release APK attached to a
  GitHub Release; `versionName` = tag (leading `v` stripped),
  `versionCode` = `GITHUB_RUN_NUMBER`
- **Manual run** from the Actions tab

Release signing requires four repo secrets:
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
Without them, the release build falls back to debug signing.

SDK: `minSdk 26`, `targetSdk 34`, `compileSdk 34`, JDK 17, Gradle 8.4,
Kotlin 1.9.22, AGP 8.2.2. `arm64-v8a` only.

## Blocklist formats

Host lists — plain hosts-file or Adblock-Plus `||domain^` lines.
Cosmetic lists — EasyList `##` / `#@#` rules. Procedural rules
(`:has()`, `:has-text()`, `#?#`, `:-abp-*`) are ignored by design:
a single injected CSS block cannot express them. Custom URL patterns
are plain substring matches.

## License

MIT
