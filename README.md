# Minimal Browser for Android

A lightweight Android browser with built-in host-based ad blocking.

## Features

- WebView-based browser with multi-tab support-ready architecture
- Host-based ad and tracker blocking
- URL keyword pattern blocking
- JavaScript toggle (on/off persisted between sessions)
- Custom homepage
- Pull-to-refresh
- Handles links from other apps

## Build

This project is built **entirely on GitHub Actions**. No local tooling required.

1. Push to `main`, open a PR, or run the workflow manually from **Actions → Build APK → Run workflow**.
2. When the run completes, download the artifact `MinimalBrowser-debug` from the run summary page.
3. Unzip → install `app-debug.apk` on an arm64 Android device.

## Supported ABIs

- `arm64-v8a` (real devices)
- `x86_64` (emulator)

## Blocklist format

`app/src/main/assets/blocklist.txt` — one host per line. Compatible with
StevenBlack/hosts format (hosts-file layout). Replace the starter list for
full coverage:
curl -o app/src/main/assets/blocklist.txt
https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts

`app/src/main/assets/filters.txt` — one URL keyword per line; a request whose
URL contains the keyword is blocked.

## License

MIT
