# Repository Guidelines

## Project Overview

Fantastic Android is a Kotlin/Jetpack Compose client for a user-configured Fantastic server. Search supports QQ Music and Apple Music; native playback currently supports QQ through Media3. Apple Music playback and most non-search tabs are not implemented. Do not mistake UI placeholders or roadmap items for working features. The project is AGPL-3.0.

## Architecture & Data Flow

- `FantasticApp` is the process-wide dependency container: eagerly creates `Prefs` and `FantasticApi`, lazily creates `PlayerConnection` and `LyricsController`. Reuse these instances; there is no DI framework.
- `MainActivity` hosts `FantasticTheme` → `AppShell`. Navigation is Compose state and overlays, not a Navigation component graph. `SearchScreen` debounces input, calls `FantasticApi`, and maps serialized responses into `Track`.
- Playback: Compose → `PlayerConnection`/`MediaController` → `PlaybackService`/`MediaSession` → `ExoPlayer`. `PlayerState` is exposed as read-only `StateFlow`. QQ stream failures downgrade quality using `Prefs.QQ_QUALITIES`. Keep the exported service's restricted commands for untrusted controllers.
- Lyrics: media ID changes → `LyricsController` fetches `/qq/song/{mid}/lyrics` → a persistent WebView parses TTML candidates → the page renders lyrics. Native code sends playback anchors every 500 ms while attached; the page extrapolates each frame. Click-to-seek returns through `FantasticBridge.seek`.
- The WebView loads local ES modules from `https://appassets.androidplatform.net/assets/lyrics/` through `WebViewAssetLoader`, not `file://`. Preserve `ready`/`status`/`seek`, `fantastic.setLyrics`/`sync`/`relayout`/`debug`, and attach-after-background-load behavior when changing renderers.

## Key Directories

Paths below the Kotlin root `app/src/main/java/io/github/kid141252010/fantastic/`:

- `data/`: server preferences, OkHttp API access, serializable DTOs and `Track` conversion.
- `playback/`: service-owned player and UI-facing controller/state.
- `lyrics/`: WebView asset routing, lifecycle, playback synchronization and diagnostics.
- `ui/`: Compose shell, shared dimensions/motion, player/search/settings screens and theme.

Other working areas: `app/src/main/assets/lyrics/` for the lyric page, `app/src/androidTest/` for device tests and fixtures, `scripts/` for asset synchronization and CI diagnostics. Keep downloaded tools, decompilation output, screenshots and other temporary files under ignored `.work/` inside this repository.

## Development Commands

Use an installed Gradle; this repository has no Gradle wrapper. Run from the root:

```sh
gradle :app:assembleDebug
gradle :app:lintDebug
gradle :app:connectedDebugAndroidTest
# Focused WebView regression; requires a connected emulator/device:
gradle :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.kid141252010.fantastic.lyrics.LyricsPageTest
# Install and launch:
gradle :app:installDebug
adb shell am start -n io.github.kid141252010.fantastic/.MainActivity
# CI tests plus logcat and screenshots; Bash required:
bash scripts/ci-instrumented.sh
# Refresh vendored lyric assets from a local Fantastic checkout:
bash scripts/sync-lyrics.sh /path/to/Fantastic
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. Device tests require an unlocked device/emulator visible to `adb`; successful compilation alone does not verify the lyric surface.

## Code Conventions & Common Patterns

- Follow Kotlin official style (`kotlin.code.style=official`): four-space indentation, `PascalCase` types/composables, `camelCase` functions/properties. Existing comments and user-visible messages are predominantly Chinese.
- Keep screen-local state in `remember`/`rememberSaveable`; observe shared flows with `collectAsStateWithLifecycle`. Keep player ownership in the service, not composables.
- Use `LaunchedEffect` for keyed UI work, cancellable jobs for lyric loads, and `Dispatchers.IO` for blocking HTTP. Rethrow `CancellationException` before handling general failures. Existing API code closes responses with `use` and reports HTTP/empty-body errors explicitly.
- Show failures through existing error/status state and `FantasticLyrics` diagnostics; do not replace errors with a silently empty page. DTOs use kotlinx.serialization and tolerate unknown response keys.
- Reuse `ui/Design.kt` dimensions, motion and composition locals. Lyrics deliberately extend beyond horizontal player padding to avoid clipping scaled text.
- The lyric page is plain `.mjs`/CSS/HTML, without a JS bundler. `apple-music-player.mjs` and `apple-music-motion.mjs` are the local renderer and APK-derived curve implementation; do not reintroduce AMLL. Files listed in `scripts/sync-lyrics.sh` are vendored TTML/segmentation assets; preserve provenance/licenses. The candidate source name `amll` is a lyric provider, not a rendering dependency.

## Important Files

- `app/src/main/AndroidManifest.xml`: application/activity/service registration, playback permissions, cleartext-server support.
- `FantasticApp.kt`, `MainActivity.kt`, `ui/AppShell.kt`: dependency and UI entry points, relative to the Kotlin root above.
- `data/Prefs.kt`: normalized server URL, storefront and quality selection; never add private credentials to source.
- `lyrics/LyricsController.kt`, `lyrics/LyricsWebView.kt`: lifecycle/bridge contracts, script MIME types and compressed dictionary asset mapping.
- `app/build.gradle.kts`, root Gradle files: Android/dependency versions and signing configuration.
- `.github/workflows/android.yml`: authoritative CI build, device-test matrix and nightly publication.
- `app/src/main/assets/lyrics/README.md` and `FANTASTIC_COMMIT`: lyric asset ownership and upstream provenance.

## Runtime/Tooling Preferences

CI uses Temurin JDK 17 and Gradle 8.11.1. Kotlin is 2.1.0; AGP is 8.7.3. Compile/target SDK is 35, minimum SDK is 26; Java/Kotlin output targets 17. Configure an Android SDK through `local.properties` (`sdk.dir`, ignored) or the environment.

There is no required Bun/Node application runtime or JS package manager. The lyric sync script uses Bash and Git only. Keep local CSS unnested for older WebViews. `.mjs` must be served as JavaScript MIME. Compressed dictionaries are packaged as `.gz.bin` and routed back to `.gz` requests. Transparent WebViews have known mask/blending pitfalls: verify rendered pixels, not only DOM presence.

`app/debug.keystore` is intentionally public and signs both debug and current release builds for compatible nightly updates; it is not a production signing secret. `GITHUB_RUN_NUMBER` controls version code and `GITHUB_SHA` supplies diagnostic build identity. Do not introduce machine-specific absolute paths into tracked build configuration.

## Testing & QA

Tests use AndroidJUnit4, AndroidX Test, Compose UI testing and MockWebServer. There is currently no `app/src/test` suite or coverage threshold.

- `LyricsPageTest`: real WebView, bundled `qq-lyrics.json`, lyrics supplied before attachment, then visible text and screenshot assertions.
- `LyricsEndToEndTest.lyricsVisibleInNowPlaying`: local MockWebServer, real activity/controller/Compose layout, opens lyrics through the player UI. Does not require a working external music server.
- `LyricsEndToEndTest.liveSongPixelsVisible`: external Fantastic/QQ integration and screenshot pixel checks. Requires a reachable configured server; audio may fail outside QQ's supported region. Do not claim this is an offline test.

CI tests Google APIs x86_64 emulators on API 34 and 36, covering old/new WebView behavior. `scripts/ci-instrumented.sh` captures `FantasticLyrics`, Chromium and crash logs and pulls `/data/local/tmp/lyrics*.png` plus player screenshots. For lyric changes, verify pause/resume, seek, track changes and zero-size-to-attached layout on the actual surface. Run the narrowest relevant check and state which device/network-dependent checks were not run.
