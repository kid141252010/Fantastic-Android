# Repository Guidelines

## 第一要点（强制）：以原版 Apple Music 为准

- 本项目所有内容及实现改动，包括界面、交互、歌词、动画、排版、时序和参数，必须以原版 Apple Music 为实现与验收基准，不得以个人偏好或自创方案替代。
- 用户的提示词只是建议和宽泛的目标描述，不是精确规格，也不是具体参数的依据。不得完全顺从或机械照搬用户描述；必须先对照原版 Apple Music，依据核实后的实际行为与具体参数落实修改。用户描述与原版不一致时，应指出差异，不得直接按描述改造原版行为。
- 修改前必须核对 Apple 官方资料或原版官方客户端、网页的可验证实现，明确参考平台、版本及参数来源。官方未公开的参数，只能依据原版的可复现测量或实现取证确定；不得把第三方仿制库、经验值或推测冒充 Apple 官方参数。
- 缺少可核验证据时，保留相关现状并说明缺失的依据，不得猜测数值、编造参数、擅自采用自己的实现方式，或以“改进”为由偏离原版。

## Project Overview

Fantastic Android is a Kotlin and Jetpack Compose client for a user-configured Fantastic server. It supports Apple Music, QQ Music, and NetEase Cloud Music, with library, discovery, and radio screens. Home provides the settings entry point. [Features and setup](README.md)

## Architecture & Data Flow

- `FantasticApp` is the process-wide manual dependency container. It creates shared `Prefs` and `FantasticApi` instances and lazily provides API clients, `PlayerConnection`, and `LyricsController`; reuse them instead of adding a DI framework.
- `MainActivity` hosts `FantasticTheme` and `AppShell`. Compose state and overlays drive navigation. `SearchScreen` debounces queries and uses the source catalog clients to populate results.
- `PlayerConnection` routes source-tagged queue items to the service-owned `FantasticSessionPlayer`. QQ Music, NetEase Cloud Music, and local audio use Media3. Online Apple Music uses Fantastic’s web player through `ApplePlaybackEngine`, with shared session state exposed through `PlayerConnection`.
- `MusicLibrary` serializes mutations on an IO coroutine scope, persists snapshots with `AtomicFile`, and exposes immutable state through `StateFlow`.
- The parser loads local ES modules through `WebViewAssetLoader` at `https://appassets.androidplatform.net/assets/lyrics/`, not `file://`. Preserve the `ready`/`status` bridge, `parseFantasticLyrics` contract, and attach-after-background-load lifecycle. The WebView parses; it does not render the playback surface.

## Key Directories

Paths below are relative to `app/src/main/java/io/github/kid141252010/fantastic/` unless stated otherwise:

- `data/` owns preferences, source catalog clients, DTOs, and `Track` conversion.
- `playback/`: service-owned player, Media3 session, and UI-facing connection/state.
- `lyrics/`: lyric loading/parsing lifecycle, synchronization, RecyclerView layout, Canvas renderer, and motion curves.
- `ui/`: Compose shell, search, player, settings, and theme.
- `app/src/main/assets/lyrics/` contains the locally maintained parser modules and provider adapters.

## Development Commands

There is no Gradle wrapper. Use Gradle 8.11.1, JDK 17, and Android SDK 35 from the repository root:

```sh
gradle :app:assembleDebug
gradle :app:installDebug
adb shell am start -n io.github.kid141252010.fantastic/.MainActivity
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Set `sdk.dir` in ignored `local.properties` or configure the Android SDK through the environment; do not commit machine-specific paths.

### Local release APKs

Build the unsigned release APK with `gradle :app:assembleRelease -PincludeLocalAmFonts=false`. Align `app/build/outputs/apk/release/app-release-unsigned.apk` with `zipalign`, then sign it with `apksigner` and the existing release key. The signing certificate must match the previous release for an in-place update.

Local builds can optionally load fonts from the ignored directory `.work/local-am-fonts/assets/am-fonts/`. Set `-PincludeLocalAmFonts=false` to use the Android system fallback.

## 提交与 APK 交付

- 开始新任务前，按类型新建分支。新功能用 `feat/<主题>`，缺陷修复用 `fix/<主题>`，文档用 `docs/<主题>`，日常维护用 `chore/<主题>`。
- 任务完成后，逐块核对并只提交本次改动，保留其他已暂存和未暂存的改动。主动将任务分支推送到 `origin`，无需等待用户提醒或另行确认。
- 推送成功后，询问用户是否将该任务分支合入 `main`。用户明确同意后再合入。
- 提交后用上文指定的 JDK、Gradle 和 SDK 重新编译并打包。先执行 `gradle :app:compileDebugKotlin --offline --rerun-tasks --console=plain`，再执行 `gradle :app:assembleDebug --offline --console=plain`。交付本次 APK 路径和构建结果，由用户安装并验收界面、设备和播放行为。

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
- The [lyrics renderer guide](app/src/main/assets/lyrics/README.md) describes the parser contract, timing and layout.
- `data/MusicLibrary.kt`, `ui/library/MusicLibraryScreen.kt`: local catalog library state and UI.
- `ui/player/AppleMediaScreen.kt` and `ui/browse/AppleDiscoveryScreen.kt` handle media pages and discovery. [Player behavior and device acceptance](APPLE_MUSIC_PARITY.md)

## Runtime/Tooling Preferences

- CI uses Temurin JDK 17 and Gradle 8.11.1. Kotlin is 2.1.0, AGP is 8.7.3, compile/target SDK is 35, minimum SDK is 26, and Java/Kotlin output targets 17.
- No Bun, Node.js, or JavaScript package manager is required. The WebView loads `.mjs` ES modules from Android assets with JavaScript MIME.
- Keep downloaded tools, temporary build setup, and diagnostic artifacts under ignored `.work/`. `app/debug.keystore` is a public debug key, not a production signing secret. `GITHUB_RUN_NUMBER` sets version code and `GITHUB_SHA` supplies the diagnostic build identity.

## Testing & QA

- The repository currently has no automated test sources, fixtures, test-only dependencies, or coverage threshold. Do not add, modify, or run automated tests/assertions unless explicitly requested.
- CI builds the debug APK and publishes a nightly release from `main`; it has no device-install job.
- Build success does not verify the RecyclerView/Canvas lyric surface or network-backed playback. Device, server, and playback acceptance is performed manually; state explicitly which such checks were not run.
- In Settings → 歌词诊断, tap the report to copy lyric status/build details; WebView load errors use the `FantasticLyrics` Logcat tag.
