# Repository Guidelines

## Project Overview

Fantastic Android is a Kotlin/Jetpack Compose client for a user-configured Fantastic server. Search supports QQ Music and Apple Music. QQ playback uses Media3; Apple playback is delegated to Fantastic’s web player through `ApplePlaybackEngine`. The app includes library and Apple discovery/radio surfaces; Home remains a placeholder. Project-authored code is Apache-2.0; bundled assets retain their own notices.

## Architecture & Data Flow

- `FantasticApp` is the process-wide manual dependency container. It creates shared `Prefs` and `FantasticApi` instances and lazily provides API clients, `PlayerConnection`, and `LyricsController`; reuse them instead of adding a DI framework.
- `MainActivity` hosts `FantasticTheme` and `AppShell`. Navigation is Compose state and overlays, not a Navigation component graph. `SearchScreen` debounces input, calls `FantasticApi`, and maps serialized responses to `Track`.
- `PlayerConnection` routes source-tagged queue items to the service-owned `FantasticSessionPlayer`. QQ/NetEase use the native ExoPlayer resolver path; Apple items are handled by `ApplePlaybackEngine` behind the same Media3 session. UI state and commands flow through `PlayerConnection` and read-only `StateFlow`.
- `MusicLibrary` serializes mutations on an IO coroutine scope, persists snapshots with `AtomicFile`, and exposes immutable state through `StateFlow`.
- The parser loads local ES modules through `WebViewAssetLoader` at `https://appassets.androidplatform.net/assets/lyrics/`, not `file://`. Preserve the `ready`/`status` bridge, `parseFantasticLyrics` contract, and attach-after-background-load lifecycle. The WebView parses; it does not render the playback surface.

## Key Directories

Paths below are relative to `app/src/main/java/io/github/kid141252010/fantastic/` unless stated otherwise:

- `data/`: preferences, Fantastic/Apple/QQ API clients, DTOs, and `Track` conversion.
- `playback/`: service-owned player, Media3 session, and UI-facing connection/state.
- `lyrics/`: lyric loading/parsing lifecycle, synchronization, RecyclerView layout, Canvas renderer, and motion curves.
- `ui/`: Compose shell, search, player, settings, and theme.
- `app/src/main/assets/lyrics/`: local parser modules, the optional ICU-backed word segmenter, and third-party dictionary notices.
- `scripts/sync-lyrics.sh`: refreshes only the permissively licensed ICU dictionary; it must not overwrite local parser modules.

## Development Commands

There is no Gradle wrapper. Use Gradle 8.11.1, JDK 17, and Android SDK 35 from the repository root:

```sh
gradle :app:assembleDebug
gradle :app:lintDebug # standard AGP task; not run by CI
gradle :app:installDebug
adb shell am start -n io.github.kid141252010.fantastic/.MainActivity
bash scripts/sync-lyrics.sh /path/to/Fantastic
# CI installs Temurin JDK 17 and Gradle 8.11.1, then runs:
gradle :app:assembleDebug --stacktrace
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Set `sdk.dir` in ignored `local.properties` or configure the Android SDK through the environment; do not commit machine-specific paths.

## Code Conventions & Common Patterns

- Follow Kotlin official style (`kotlin.code.style=official`): four-space indentation, `PascalCase` types/composables, and `camelCase` functions/properties. User-visible strings and existing comments are predominantly Chinese.
- Keep screen-local Compose state in `remember`/`rememberSaveable`; collect shared flows with `collectAsStateWithLifecycle`. Keep playback ownership in the service.
- Use `LaunchedEffect` for keyed UI work and cancellable jobs for lyric loads. Rethrow `CancellationException` before handling other failures; run blocking HTTP work on `Dispatchers.IO`.
- Close OkHttp responses with `use`; report HTTP and empty-body errors explicitly. DTOs use kotlinx.serialization and tolerate unknown response keys. Preserve existing error/status state and `FantasticLyrics` diagnostics instead of silently returning empty content.
- Reuse `ui/Design.kt` dimensions, motion, and composition locals. Lyrics intentionally extend beyond player horizontal padding to avoid clipping scaled text.
- Keep lyric-provider parsing in locally maintained `.mjs`/HTML modules; playback rendering and timing are native Kotlin. Candidate `source: "amll"` identifies provenance, not a rendering dependency.

## Important Files

- `app/src/main/AndroidManifest.xml`: application, launcher activity, playback service, permissions, and cleartext-server support.
- `FantasticApp.kt`, `MainActivity.kt`, `ui/AppShell.kt`: dependency container and app entry points.
- `data/Prefs.kt`, `data/FantasticApi.kt`, `data/AppleCatalogApi.kt`: settings and server/API access.
- `playback/PlaybackService.kt`, `playback/PlayerConnection.kt`, `playback/FantasticSessionPlayer.kt`, `playback/ApplePlaybackEngine.kt`: Media3 session ownership and source-specific playback adapters.
- `lyrics/LyricsController.kt`, `lyrics/LyricsWebView.kt`: lyric loading, parser bridge, and WebView lifecycle.
- `lyrics/NativeLyricsView.kt`, `lyrics/LyricInkView.kt`, `lyrics/LyricMotion.kt`: native lyric list, Canvas rendering, and animation curves.
- Root `LICENSE` is Apache-2.0 and `app/build.gradle.kts` copies it into the APK assets during `preBuild`.
- `app/build.gradle.kts`, root `build.gradle.kts`, `settings.gradle.kts`, and `gradle.properties`: Android and Kotlin build configuration.
- `.github/workflows/android.yml`: CI APK build and nightly publication.
- `app/src/main/assets/lyrics/README.md`, `lyrics.mjs`, `ttml.mjs`, `word-segmenter.mjs`, and `CJDICT-LICENSE.txt`: local parser ownership and retained dictionary notice.
- `data/MusicLibrary.kt`, `ui/library/MusicLibraryScreen.kt`: local catalog library state and UI.
- `ui/player/AppleMediaScreen.kt`, `ui/browse/AppleDiscoveryScreen.kt`, `APPLE_MUSIC_PARITY.md`: Apple media WebView surfaces, discovery, and implementation gaps.

## Runtime/Tooling Preferences

- CI uses Temurin JDK 17 and Gradle 8.11.1. Kotlin is 2.1.0, AGP is 8.7.3, compile/target SDK is 35, minimum SDK is 26, and Java/Kotlin output targets 17.
- No Bun, Node.js, or JavaScript package manager is required. The WebView loads `.mjs` ES modules from Android assets with JavaScript MIME. Gzip dictionaries use `.gz.bin` packaging and `LyricsWebView` maps `.gz` requests back to those files.
- Keep downloaded tools, temporary build setup, and diagnostic artifacts under ignored `.work/`. `app/debug.keystore` is a public debug key, not a production signing secret. `GITHUB_RUN_NUMBER` sets version code and `GITHUB_SHA` supplies the diagnostic build identity.

## Testing & QA

- The repository currently has no automated test sources, fixtures, test-only dependencies, or coverage threshold. Do not add, modify, or run automated tests/assertions unless explicitly requested.
- CI builds the debug APK and publishes a nightly release from `main`; it has no test, lint, or device-install job.
- Build success does not verify the RecyclerView/Canvas lyric surface or network-backed playback. Device, server, and playback acceptance is performed manually; state explicitly which such checks were not run.
- In Settings → 歌词诊断, tap the report to copy lyric status/build details; WebView load errors use the `FantasticLyrics` Logcat tag.
