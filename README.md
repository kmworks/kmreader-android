<div align="center">

# KMReader for Android

**Native Jetpack Compose Komga client for Android.**

[![Android](https://img.shields.io/badge/Android-9.0%2B-green.svg)](https://www.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-purple.svg)](https://kotlinlang.org/)
[![Compose](https://img.shields.io/badge/Jetpack-Compose-blue.svg)](https://developer.android.com/compose)
[![Komga](https://img.shields.io/badge/Komga-1.20%2B-orange.svg)](https://komga.org/)

</div>

KMReader for Android is the Android client of the KMReader family, built and maintained
inside the [kmworks](https://github.com/kmworks) organization next to
[kmreader](https://github.com/kmworks/kmreader) (iOS, macOS, tvOS) and
[kmrs](https://github.com/kmworks/kmrs) (Komga-compatible server).
It is an official sibling app — not a fork, re-skin, or third-party port — and it follows the
same design contract as the Apple app: identical information architecture, feature set, and
navigation model, translated to Android containers only.

## Features

### Readers

- **DIVINA reader** for CBZ/CBR image pages: LTR, RTL, and vertical/webtoon directions; spreads with
  wide-page splitting and solo cover pages; double-tap and pinch zoom with pan; configurable tap zones
  and long-press action; page transitions, page shadow, progress bar, and chrome gradient; border
  cropping; preload profiles; per-book preferences; keep-screen-awake.
- **EPUB reader** on the WebView + ReadiumCSS path: theme presets, typography (font size, weight,
  line height, margins, columns), publisher styles, flow style, page-turn animation, nested table of
  contents, and importable custom fonts.
- **PDF reading** with a choice of reader, matching the iOS app: **Use Native PDF Reader** (Reader
  defaults → PDF, on by default) opens PDFs in the dedicated AndroidX PDF viewer with a text layer
  for selection/search, table of contents, page jump, continuous scroll, page presentation,
  solo-cover, reading background, and progress overlay. Turning it off opens PDFs in the DIVINA
  comic reader instead — the better fit for comic and manga PDFs, and the only way a
  downloaded PDF is rendered page-by-page from disk while offline.

### Browse and discovery

- **Dashboard** with Keep Reading, On Deck, Recently Added, Recently Updated, and Read Lists in
  Progress sections; per-section card style, ordering, visibility, and dedicated section pages.
- **Library browse**: content-type chips with counts, library scopes, sort and layout switching
  (list / grid / large grid), and filters for reading status, one-shots, authors, tags, notable
  entities, publishers, genres, age ratings, languages, release years, and series status — with
  all/any matching and saved filter presets.
- **Lists**: collections, ordered read lists, and smart lists, including the condition builder
  (field / operator / value, match all or any, book or series target) and list creation from anywhere.
- **Search** tab with series, books, read lists, and collections results.
- **Series, book, and one-shot detail** pages: hero with server-side thumbnail, action card,
  metadata, tracker section, `More` menu (edit, komf identify), and multi-select batch actions
  (mark read/unread, download, add to list, delete).

### Downloads

Downloads are managed on device with WorkManager: a download list and queue with pause, resume,
retry, and cancel, library-scoped auto-download of unread books, a Wi-Fi-only constraint, storage
usage, and cleanup of downloaded books. Downloads are chosen per book format, and the readers read
them from disk so a downloaded book opens without the server:

| Book format | Stored as | Read offline by |
| --- | --- | --- |
| CBZ (zip comic archive) | one `book.cbz` file | DIVINA reader, pages extracted on demand |
| CBR and Divina-compatible EPUB | page images | DIVINA reader, page files |
| EPUB (webpub) | one `book.epub` file | EPUB reader, resources read straight from the zip |
| PDF | one `book.pdf` file | PDF viewer (text layer), or the DIVINA reader when the native PDF reader is off — its pages are rasterized from disk on demand |

Divina rendering places local page files in front of the network, EPUB resources fall back to the
server when a zip entry is missing, and read progress and table-of-contents links are compared after
normalizing href spelling. Offline browsing mode (browsing the server library while disconnected) is
intentionally out of scope (see [Platform differences](#platform-differences)).

### Server and sync

- Multiple Komga servers with instant switching; sign in with username/password or an API key.
  Library scope, pinned libraries, pinned collections and read lists, and saved filter presets are
  kept per server, so a switch never shows another server's selection and every server starts with
  all libraries in scope.
- Komga API **v1 and v2** through a Retrofit client generated from the pinned
  [`api/openapi.json`](api/openapi.json) snapshot, so the wire contract is versioned with the app.
- Server-sent events for real-time library updates, client settings sync, reading stats, and an
  in-app log viewer with share/export.
- Network settings (timeout, concurrency), image cache usage and cleanup, true-black theme, and app
  icon variants (primary / classic / legacy).
- **Update check** on the About page: the app looks up the latest [GitHub
  release](https://github.com/kmworks/kmreader-android/releases) at launch, at most once a day, and
  prompts when the tag is newer than the installed build. The switch turns the automatic check off,
  `Check Now` queries on demand, and `Download` opens the release APK in the browser (no installer
  integration, no storage permission).

### Tracking and metadata

- Tracker accounts for **AniList, MyAnimeList, Bangumi, and MangaBaka**, with series binding, link
  editing, progress mapping, and remote progress (requires a kmrs server).
- **komf** integration for Identify and metadata matching on servers that provide it.

### Platforms

Phones and tablets in one build: bottom navigation on phones, a floating capsule navigation bar on
tablets (≥840dp), and the wide detail layout from the Apple app at ≥960dp with a golden-ratio info
rail of `max(38.2%, 340dp)`. UI is monochrome Material 3 — no brand color, no gradients, no
invented variants. List and collection covers are always the server-generated thumbnails.

The UI ships in English and Simplified Chinese.

## Design reference

[`docs/preview.html`](docs/preview.html) is a self-contained static mock of the full design —
every screen, phone and tablet, no build required. It documents the intended layout and states;
the Compose implementation is the source of truth once a screen ships.

| Area | Screen |
| --- | --- |
| `ui/dashboard` | Dashboard, section detail, dashboard customization |
| `ui/library`, `ui/browse` | Library browse, filters, search |
| `ui/lists` | Collections, read lists, smart lists, detail and edit sheets |
| `ui/detail` | Series, book, one-shot detail; edit and komf sheets |
| `ui/reader` | DIVINA, EPUB, PDF readers; settings; custom fonts |
| `ui/offline` | Download management |
| `ui/tracker`, `ui/stats` | Tracker accounts and reading stats |
| `ui/settings` | All settings sub-pages, servers, logs, about, licenses |

## Platform differences

Deliberately not implemented, mirroring the scoping of the Apple app to Android:

- Panel-split reading mode (CoreML shot detection) — not implemented.
- AI upscaling and manga translation overlays — not implemented.
- EPUB CoreText layout engine — EPUB always uses the WebView + ReadiumCSS path.
- Page-curl transition — only slide/cover/scroll transitions are offered.
- iOS accessory bar and iOS-only integrations (widgets, Live Activities, Spotlight, Live Text).
- Offline browsing mode — downloads and download management are kept.

Tracker, komf, and reading-stats screens are capability-gated the same way as in the Apple app
(server support detected at runtime), so they are absent rather than broken on plain Komga.

## Requirements

- **JDK 17**
- **Android SDK 37** (`compileSdk = 37`, `minSdk = 28`, `targetSdk = 36`)
- **Gradle 9.7.1** — pinned by the committed wrapper, so `./gradlew` downloads it; no separate
  Gradle installation is needed (Android Studio works too).

`local.properties` must point at your SDK:

```properties
sdk.dir=/path/to/Android/Sdk
```

## Build and run

```bash
./gradlew :app:assembleDebug        # APK at app/build/outputs/apk/debug/
./gradlew :app:installDebug         # install on a connected device
./gradlew :app:lintDebug            # lint gate; app/lint-baseline.xml freezes pre-existing findings
./gradlew :core:api:openApiGenerate # regenerate the API client from api/openapi.json
```

On Windows run `gradlew.bat` instead, for example `gradlew.bat :app:assembleDebug`.

Or open the repository root in Android Studio and run the `app` configuration.

The OpenAPI client is generated at build time into `core/api/build/generated/`, with a small set of
deterministic post-generation fixes for known kotlin-generator quirks (see
[`core/api/build.gradle.kts`](core/api/build.gradle.kts)). Never hand-edit generated sources; update
[`api/openapi.json`](api/openapi.json) and its [`sha256`](api/openapi.json.sha256) deliberately.

## Releases

The version lives in [`app/build.gradle.kts`](app/build.gradle.kts). Bumping `versionName` (and
`versionCode`) on `main` makes the [`Release`](.github/workflows/release.yml) workflow build the
APK, add a matching `sha256` file, and publish `v<versionName>`; a run for an already published
version does nothing.

Release builds are minified with R8 (`isMinifyEnabled` and `isShrinkResources`) and signed with the
configured keystore. Once the repository secrets `KEYSTORE_BASE64` (base64 of the keystore),
`KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` are set, the workflow publishes
`kmreader-android-<version>.apk`. Without them it falls back to a debug-signed
`kmreader-android-<version>-debug.apk`, so the channel keeps working before the keystore exists —
such builds are installable but cannot upgrade a properly signed build in place.

For a local release build, put the same values in an untracked `keystore.properties` at the
repository root (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) and run
`./gradlew :app:assembleRelease`.

## Project layout

```
api/          Pinned OpenAPI snapshot + hash (the API contract)
core/api/     Retrofit client generated from api/openapi.json
app/
  core/       Data, network, downloads, readers (EPUB/PDF/DIVINA), tracker, komf,
              settings, SSE, stats, logging
  data/       UI navigation and page models
  ui/         Compose screens, per feature area; ui/components for shared UI
docs/         Static design reference
```

## Compatibility

- Komga 1.20.0+ (API v1 and v2).
- Responses from an older server decode tolerantly: a field the pinned OpenAPI
  snapshot declares required but the server does not send (for example
  `notableEntities`, added in Komga 1.29) reads as an empty value instead of
  failing the whole page, matching the optional properties of the Apple app.
- Enhanced features (trackers, smart-list editing, reading stats, client settings sync) require a
  [kmrs](https://github.com/kmworks/kmrs) server or a server with the corresponding integration.

## Community

- [Discord](https://discord.gg/WQtE6VhjpP)

## Acknowledgements

KMReader for Android is built on Jetpack Compose and Material 3, Coil, OkHttp, Retrofit,
kotlinx.serialization/coroutines, Room, DataStore, WorkManager, and Hilt. EPUB rendering follows
the Readium CSS approach; PDFs use the AndroidX PDF viewer and its bundled PDFium by default and can
instead be read through the DIVINA comic reader, whose downloaded PDFs are rasterized with the
platform `PdfRenderer`.
The in-app licenses screen shows the license of each bundled component.
