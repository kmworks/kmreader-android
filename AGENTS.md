# AGENTS.md

Guidance for AI agents working in this repository.

## What this is

KMReader for Android — a native Jetpack Compose client for [Komga](https://komga.org/) servers
(API v1/v2), part of the kmworks family. It is the Android sibling of the Apple app
([kmreader](https://github.com/kmworks/kmreader)), and **iOS parity is the design contract**:
same information architecture, feature set, and navigation model, translated to Android
containers only. When behavior is ambiguous, mirror what the iOS app does — most non-trivial
code comments reference the iOS counterpart by name (`PaginationState.replaceItems`,
`coverCacheExpirationDays`, …); keep those references accurate when you change behavior.

## Build and test

- **JDK 17**, Android SDK (`compileSdk = 37`, `minSdk = 28`, `targetSdk = 36`).
  `local.properties` must point at the SDK. Gradle is pinned by the wrapper — always use
  `./gradlew` (`gradlew.bat` on Windows), never a system Gradle.

```bash
./gradlew :app:assembleDebug        # APK at app/build/outputs/apk/debug/
./gradlew :app:compileDebugKotlin   # fast compile check after an edit
./gradlew :app:testDebugUnitTest    # JVM unit tests (JUnit 4 + kotlinx-coroutines-test)
./gradlew :app:lintDebug            # the lint gate (see below)
./gradlew :core:api:openApiGenerate # regenerate the API client (see below)
```

- Tests live in `app/src/test` only; there is no `androidTest`. They are plain JVM tests —
  no Robolectric, no Compose UI tests. Follow the existing style when adding one.
- **Lint gate**: Android Lint fails the build on errors (`abortOnError`), and CI runs
  `:app:lintDebug` on every push and PR. `app/lint-baseline.xml` freezes the findings that
  predate the gate, so only NEW issues fail. Fix the issue, or — deliberately, never casually —
  regenerate the baseline by running `:app:lintDebug` with the baseline file removed.
- CI (`.github/workflows/ci.yml`) additionally runs the unit tests, the API decoding tests, and
  both debug and unsigned-release (R8) builds.

## Project layout

```
api/          Pinned OpenAPI snapshot + sha256 — the versioned wire contract
core/api/     Retrofit client GENERATED from api/openapi.json (openApiGenerate runs at build time,
              plus deterministic post-generation patches in core/api/build.gradle.kts)
app/src/main/java/com/kmworks/kmreader/
  core/       Data, network, downloads, readers (EPUB/DIVINA), tracker, komf,
              settings, SSE, stats, logging
  data/       UI navigation and page models
  ui/         Compose screens per feature area; ui/components holds shared UI
```

**Never hand-edit generated sources** under `core/api/build/generated/`. Change
`api/openapi.json` (and its `.sha256`) deliberately, or adjust the deterministic
post-generation fixes in `core/api/build.gradle.kts`.

## Architecture essentials

- **DI**: Hilt everywhere. ViewModels are `@HiltViewModel`; singletons take an application
  `CoroutineScope` for fire-and-forget work. Composables that need a singleton reach it through
  `EntryPointAccessors` (see `ImageLoaderEntryPoint` usage in `ui/components/Components.kt`).
- **Images**: Coil 3, split into two authenticated loaders owned by `ImageCacheStore` — page
  images vs covers/thumbnails, each with its own disk cache namespaced per server instance.
  Cover URLs carry a `cv` stamp (`CoverCacheStamps`) that *is* the cache-busting key: the stamp
  table publishes `null` until its DataStore snapshot is loaded, and composables must not stamp
  or fetch before that (see the commit `fix(covers): settle the stamp table…`). Cover identity in
  the UI is an Int stable id; `CoverUrlRegistry` maps stableId → Komga DTO id and builds the URL.
- **Lists**: `PagedFeed` (`core/data/PagedFeed.kt`) is the one owner of page bookkeeping
  (reset / append / reload-window). Do not reintroduce ad-hoc paging in screens. Row writers are
  serialized per screen (one `Mutex`; the dashboard uses one per section feed) — a load-more that
  finds the rows busy must set a pending flag and be drained by the writer, not queue on the lock.
- **Change propagation**: `ContentChangeBus` merges local writes and SSE events; an open reader
  holds list refreshes and replays them on close. New event kinds need the section-mapping
  treatment (see `SseChangeParser` / `SseContentSync`) rather than a blanket reload.
- **State**: Compose + `StateFlow`, `collectAsStateWithLifecycle`. Per-tab state survives via the
  `SaveableStateProvider` in `AppRoot` — user choices on tab pages belong in `rememberSaveable`.
- **Cover deck**: `CoverDeck` (`ui/reader/ReaderCoverDeck.kt`) keeps all three slots composed at all
  times — idle neighbours sit at `alpha = 0`, they are never dropped — because composing them is what
  requests and decodes the neighbour pages before a turn; gating that on visibility (`if (visible)`,
  a lazy slot list) brings back the first-frame flash the iOS deck fixed. Only the front slot is
  interactive or exposed to accessibility (`LocalCoverSlotInteractive`, `clearAndSetSemantics`):
  Compose hit-tests a covered composable, so a live neighbour's end-page card would fire through the
  page on screen. Do not fix that with a touch consumer — consuming a change zeroes the deck's own
  drag deltas (see `fix(reader): hidden deck slots must not swallow the deck's own drags`).
- **Storage**: Room (cache entities), DataStore (settings, cover stamps), WorkManager (downloads).

## UI conventions

- Monochrome Material 3: no brand color, no gradients, no invented variants.
- Phones get bottom navigation; tablets (≥840dp) a floating capsule; wide detail layout at ≥960dp
  with a golden-ratio info rail (`max(38.2%, 340dp)`).
- UI ships in **English and Simplified Chinese** — user-facing strings go in both locales.
- Comments are part of the style: non-obvious decisions carry a prose comment explaining *why*
  (often naming the iOS counterpart or the bug that motivated the shape). Keep them in English
  and update them when the code's behavior changes.

## Git conventions

- Branch work happens on feature branches; `main` releases are versioned by bumping
  `versionName`/`versionCode` in `app/build.gradle.kts` (the Release workflow publishes
  `v<versionName>`).
- Commit messages: **conventional prefix + lowercase summary + detailed prose body** explaining
  the root cause and the mechanism of the fix, e.g.
  `fix(covers): settle the stamp table before picking a cover cache key`. Look at
  `git log` before writing one; terse one-liners and AI attribution footers are not the style.
- `local.properties`, `keystore.properties`, and `*.jks` stay untracked. Log files
  (`build-*.log`) and temp fixture dirs are local build debris — don't commit them.

## Deliberately out of scope

Do not "helpfully" add these; they mirror the Apple app's scoping decisions: panel-split reading,
AI upscaling/translation overlays, a CoreText EPUB engine, page-curl transitions, offline
*browsing* of the server library (downloads themselves are kept). Tracker/komf/stats features are
capability-gated at runtime — absent, not broken, on plain Komga.
