# Minimal Browser

A small, fast Android WebView browser with host-based ad blocking
and subscription-driven cosmetic filtering.

## Features

- Multi-tab UI (up to 6 live tabs, LRU eviction)
- Host-based ad/tracker blocking from remote subscription lists
- Cosmetic filtering from EasyList-format subscriptions
- URL keyword pattern blocking
- Address-bar suggestions from local visit history
- Add to home screen (launcher shortcut per page)
- JavaScript toggle
- Pull-to-refresh, downloads via system DownloadManager

## Architecture

Rule storage lives in two warehouses under the app private directory:

- `filesDir/blocklists/` — one ZIP per host-list subscription,
  merged into a single sorted `LongArray` of FNV-1a hashes
  (`HostSet`). ~1.2 MB for 150k hosts.
- `filesDir/cosmetics/` — one ZIP per EasyList-format subscription,
  parsed at boot into generic + per-domain selector sets
  (`CosmeticRules`) and injected as one CSS block per navigation.
  No MutationObserver, no runtime DOM walks.

A `WorkManager` job refreshes both warehouses every 12 hours. Lists
carry their own freshness windows (24h for hosts, 72h for cosmetics)
and conditional-GET validators, so repeat runs are cheap.

## Build

Built entirely on GitHub Actions. Push to `main` or run the
**Build APK** workflow manually from the Actions tab. The debug
artifact is published on the run summary page.

## Blocklist format

Host lists are standard hosts-file or Adblock-Plus `||domain^`
lines. Cosmetic lists are EasyList-format `##` / `#@#` rules;
procedural rules (`:has()`, `:has-text()`, `#?#`) are ignored.

## License

MIT
