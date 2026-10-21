# Minimal Browser

A small, fast Android WebView browser with host-based ad blocking, subscription-driven cosmetic filtering, and a focus on privacy and clean UI.

## Features

**Browsing**
- Multi-tab UI (up to 6 live tabs, LRU eviction)
- Auto-freeze inactive tabs to save memory and battery
- Session restore (survives app kill, reboot, and swipe-from-recents)
- Desktop mode (per-host User-Agent switching)
- HTML5 Fullscreen support
- Find in page (live search with match counter and navigation)

**Privacy & Ad Blocking**
- Host-based ad/tracker blocking from remote subscription lists
- Cosmetic filtering from EasyList-format subscriptions
- Custom URL keyword pattern blocking
- Custom tracking parameter stripping (e.g., `utm_source`, `fbclid`)
- Intercepts `target="_blank"` popups into new tabs to protect the current tab's history

**Utilities**
- Downloads via system `DownloadManager` (http/https)
- In-process downloader for `blob:` and `data:` URLs (e.g., canvas exports, PDF generators)
- History (searchable, sortable, with favicons)
- Bookmarks (searchable, sortable, with favicons)
- Address-bar suggestions from local visit history
- Add to home screen (launcher shortcut per page)
- Magnet link and external app handoff (`magnet:`, `mailto:`, `intent:`, etc.)
- JavaScript toggle (global and per-tab)

## Architecture

**Ad Blocking Warehouses**
Rule storage lives in two warehouses under the app private directory:
- `filesDir/blocklists/` — one ZIP per host-list subscription, merged into a single sorted `LongArray` of FNV-1a hashes (`HostSet`). ~1.2 MB for 150k hosts.
- `filesDir/cosmetics/` — one ZIP per EasyList-format subscription, parsed at boot into generic + per-domain selector sets (`CosmeticRules`) and injected as one CSS block per navigation. No MutationObserver, no runtime DOM walks.

A `WorkManager` job refreshes both warehouses every 12 hours. Lists carry their own freshness windows (24h for hosts, 72h for cosmetics) and conditional-GET validators, so repeat runs are cheap.

**Data & Downloads**
- History and Bookmarks are stored in `SharedPreferences` with an on-disk `LruCache` for favicons.
- Blob and data URL downloads are streamed in 1MB chunks via a JS bridge (`BlobDownloadHelper`) directly to `MediaStore`, preventing out-of-memory crashes on large files.

## Build

Built entirely on GitHub Actions. Push to `main` or run the **Build APK** workflow manually from the Actions tab. The debug artifact is published on the run summary page. Release builds are signed if the `KEYSTORE_BASE64` secret is provided.

## Blocklist format

Host lists are standard hosts-file or Adblock-Plus `||domain^` lines. Cosmetic lists are EasyList-format `##` / `#@#` rules; procedural rules (`:has()`, `:has-text()`, `#?#`) are ignored. Custom URL patterns are simple substring matches.

## License

MIT
