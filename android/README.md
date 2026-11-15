# 24FM for Android

The Android app for [24FM.ir](https://24fm.ir). It shows the website in a hardened WebView and
moves the audio into a native Media3 service, so the radio keeps playing in the background, on
the lock screen and after the app is closed.

Version **1.0.0** (versionCode 1), application ID `ir.fm24.app`.

## SDK and toolchain

| | Value | Why |
| --- | --- | --- |
| `minSdk` | 24 (Android 7.0) | The lowest level AndroidX WebKit 1.18 supports |
| `targetSdk` | 37 (Android 17) | Latest stable level |
| `compileSdk` | 37 | Latest stable platform |
| Build-Tools | 37.0.0 | |
| Android Gradle Plugin | 9.5.1 | |
| Gradle | 9.9.1 (wrapper, checksum pinned) | |
| Kotlin | 2.4.21 (AGP built-in Kotlin) | |
| JDK | 17 or newer (built with Temurin 21) | |

Target level: Myket rejects new apps and updates below API 34 since 1 Aban 1405, and Google Play
has required API 36 since 31 August 2026. Cafe Bazaar has no published floor today. Targeting
37 clears all three with room to spare. Both store flavors share these values.

## Store flavors

| Flavor | Store | Output |
| --- | --- | --- |
| `bazaar` | Cafe Bazaar | APK and AAB |
| `myket` | Myket | APK |

Shared code lives in `app/src/main`. Anything that only one store needs goes in
`app/src/bazaar` or `app/src/myket`. Each currently holds a `StoreInfo` object, used to tag the
user agent. Both flavors use the same application ID.

## Features

- **Native background audio:** ExoPlayer inside a `MediaSessionService` with a media
  notification, lock-screen, headset and Bluetooth controls, audio focus handling and bounded
  reconnection.
- **Web player bridge:** `assets/bridge.js` swaps the site's `new Audio()` for a proxy that drives
  the native player, so the page never plays a second stream. It's injected only on 24fm.ir and
  talks over an origin-restricted `WebMessageListener`. The website itself is unchanged.
- **Loading screen:** the system splash hands over to an identical gramophone, which keeps
  spinning until the site has fully loaded, then grows and fades out.
- **Smart cache:** see below.
- **Friendly errors:** calm Persian messages with a retry button, and the technical code in small
  print. The full-screen error only appears when there's no cached copy to show.
- **Network awareness:** bottom banners for offline, server trouble, a weak connection and stream
  retries. A "turn off your VPN" notice appears when the connection is outside Iran.
- **Pull to refresh**, and **back** that closes the genre popup, then goes back, then asks for a
  second press to exit.
- **Any screen:** edge-to-edge insets, phone landscape (the page scrolls when the screen is
  short), split screen, tablets and foldables.

## Smart cache

Before each load, `SiteCache` fingerprints the site with HEAD requests over one keep-alive
connection. It covers the home page plus its CSS and JS, using ETag, Last-Modified and
Content-Length. Nothing is downloaded in full.

| Result | What happens |
| --- | --- |
| Unchanged | Cached copy served as-is (`LOAD_CACHE_ELSE_NETWORK`), so the app opens fast |
| Changed | WebView HTTP cache cleared, new version loaded, new fingerprint saved once it loads |
| Unreachable (offline, weak, timeout) | Last cached copy shown, then checked again quietly after startup |
| First run | Normal load, fingerprint saved |

The check is capped at 4 seconds and repeats at most every 30 minutes when the app returns from
the background. Only the HTTP cache is ever cleared; cookies, localStorage and settings are never
touched. Failures are silent, and the cache is reset if it grows past 40 MB.

## Security

- HTTPS only through `network_security_config`; no cleartext and no `usesCleartextTraffic`. On
  Android 17 the platform also enforces Certificate Transparency, and every certificate the app
  uses carries SCTs.
- Android 7.0 predates the Let's Encrypt roots in the system store, so `res/xml/` (API 24 only)
  bundles the official ISRG Root X1/X2. API 25 and up use `res/xml-v25/` with system CAs only.
- SSL errors are never bypassed. External links open outside the app, and `javascript:`, `file:`
  and `intent:` URLs are blocked.

## Local setup

These files hold secrets or machine paths. They're git-ignored and must never be committed:

- `local.properties`: `sdk.dir=...`, plus an optional `ipinfo.token=...` for the VPN notice.
  Without the token that feature is compiled out.
- `keystore.properties`: release signing. Copy `keystore.properties.example` and fill it in.
- `keystore/`: the release keystore.

Any client-side token can be pulled out of an APK. The IPinfo token is free and only returns a
country, so rotate it in the IPinfo dashboard if it's ever abused.

## Build

```bash
./gradlew clean test lint
./gradlew assembleBazaarDebug assembleMyketDebug
./gradlew assembleBazaarRelease bundleBazaarRelease assembleMyketRelease
```

Release outputs are signed only when `keystore.properties` is present. They land in
`app/build/outputs/apk/<flavor>/release/` and `app/build/outputs/bundle/<flavor>Release/`.
Behind a proxy, pass it to Gradle, for example
`./gradlew -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=1082 ...`.

## Notes for maintainers

- If the site's `js/player.js` changes, re-check `bridge.js`. It relies on `new Audio()`,
  `audio.src/play/pause/volume`, `#btn-play`, `#track-genre-label`, `window.Player` and
  `window.Popup`.
- Devices whose System WebView can't run document-start scripts fall back to the site's own
  player, which only plays in the foreground. Very old WebViews (around Chrome 53) also can't
  render the site's modern CSS properly; updating Android System WebView fixes it.
- Fonts in `res/font` are the website's own Vazirmatn files, converted unchanged
  (SIL Open Font License 1.1).
