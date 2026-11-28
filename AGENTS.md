# Repository Guidelines

## 第一要点（强制）：以既定设计规格为准

- 界面、交互、歌词、动画、排版和时序以产品既定设计规格及已确认的项目行为为准，不以个人偏好替代。
- 需求描述不等于精确参数。修改前核对相关设计文档与现有实现；描述与规格冲突时指出差异，再按确认后的规格执行。
- 参数缺少依据时保留现状并说明缺失项，不猜测数值，不将经验值冒充已确认规格。
- 不得提交第三方应用的分析或拆包资料、内部类名／方法名／资源名、样本哈希及抓包证据；参数只写数值与行为，不写内部出处。

## Project Overview

Fantastic Android is a Kotlin and Jetpack Compose client for a user-configured Fantastic server. It supports Apple Music, QQ Music, and NetEase Cloud Music, with library, discovery, and radio screens. Home provides the settings entry point. [Features and setup](README.md), [Usage guide](docs/usage.md)

## Architecture & Data Flow

- `FantasticApp` is the process-wide manual dependency container. It creates shared `Prefs` and `FantasticApi` instances and lazily provides API clients, `PlayerConnection`, and `LyricsController`; reuse them instead of adding a DI framework.
- `MainActivity` hosts `FantasticTheme` and `AppShell`. Compose state and overlays drive navigation. `SearchScreen` debounces queries and uses the source catalog clients to populate results.
- `PlayerConnection` routes source-tagged queue items to the service-owned `FantasticSessionPlayer`. QQ Music, NetEase Cloud Music, and local audio use Media3. Online Apple songs and full-track export use in-process FairPlay/Widevine decryption with Media3 or ALAC AudioTrack; session state remains exposed through `PlayerConnection`.
- `MusicLibrary` serializes mutations on an IO coroutine scope, persists snapshots with `AtomicFile`, and exposes immutable state through `StateFlow`.
- `LyricParser` parses Apple TTML and provider lyrics in native Kotlin on `Dispatchers.Default`. `NativeLyricsView` and `LyricInkView` render the playback surface. Apple online playback and full-track export use in-process FairPlay/Widevine decryption with Media3 or ALAC AudioTrack; media-page WebViews remain.

## Key Directories

Paths below are relative to `app/src/main/java/io/github/kid141252010/fantastic/` unless stated otherwise:

- `data/` owns preferences, source catalog clients, DTOs, and `Track` conversion.
- `playback/`: service-owned player, Media3 session, and UI-facing connection/state.
- `lyrics/`: lyric loading/parsing lifecycle, synchronization, RecyclerView layout, Canvas renderer, and motion curves.
- `ui/`: Compose shell, search, player, settings, and theme.
- `app/src/main/assets/lyrics/README.md` documents the lyric parser and playback surface.

## Development Commands

There is no Gradle wrapper. Use Gradle 8.11.1, JDK 17, and Android SDK 35 from the repository root:

```sh
gradle :app:assembleDebug
gradle :app:installDebug
adb shell am start -n io.github.kid141252010.fantastic/.MainActivity
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Set `sdk.dir` in ignored `local.properties` or configure the Android SDK through the environment; do not commit machine-specific paths.

### Local release APKs

Build the unsigned release APK with `gradle :app:assembleRelease`. Align `app/build/outputs/apk/release/app-release-unsigned.apk` with `zipalign`, then sign it with `apksigner` and the existing release key. The signing certificate must match the previous release for an in-place update.

For local font assets, preparation, build switches, fallback families, and weight mappings, see [Fonts and local packaging](docs/fonts.md). Keep private fonts out of Git; public CI uses Android system fonts.

## 提交与 APK 交付

- 开始新任务前，按类型新建分支。新功能用 `feat/<主题>`，缺陷修复用 `fix/<主题>`，文档用 `docs/<主题>`，日常维护用 `chore/<主题>`。
- 任务完成后，逐块核对并只提交本次改动，保留其他已暂存和未暂存的改动。主动将任务分支推送到 `origin`，无需等待用户提醒或另行确认。
- 推送成功后，询问用户是否合入。用户明确批准后，同一个任务改动先合入发行主线 `main`，再将 `main` 同步到本地调试主线 `fix/debug-side-by-side`。已明确批准时不重复询问。
- `main` 与 `origin/main` 保持同步，保留正式应用 ID `io.github.kid141252010.fantastic` 和发行签名。`fix/debug-side-by-side` 不推远端、不反向合入 `main`；保留 Debug 的 `.debug` 应用 ID 后缀和现有调试签名，以便与正式版并存。两条线的功能源码保持一致，合入后核对差异只涉及 Debug 安装身份。
- 用户明确只要求 Debug 验收时，可先将指定任务提交合入本地 `fix/debug-side-by-side`；不得因打 Debug 包而将该功能合入 `main`。必须从 `.work/debug-local` 的实际 Debug 分支构建，不在任务分支临时注入 `.debug` 后缀。
- 双线交付使用上文固定的工具链和独立构建目录：从 `main` 构建并用既有发行密钥签署 Release APK，从本地 Debug 分支执行 `:app:compileDebugKotlin --offline --rerun-tasks --console=plain` 与 `:app:assembleDebug --offline --console=plain`。两边本机交付均显式传 `-PincludeLocalAmFonts=true`，缺少字体则停止交付；不提交私有字体和签名材料，公开 CI 继续使用系统字体。
- 每次将任务更新合入 `main`，必须在同一次合入中更新 `app/build.gradle.kts` 的 `versionName` 和本地基准 `versionCode`，无需用户另行提醒。依据合入前 `main` 的版本及本次全部改动判断升级级别，不按提交数量重复升级。
- 小版本（patch，`X.Y.Z → X.Y.(Z+1)`）：缺陷修复、小范围界面或交互调整、常规性能优化、依赖维护及文档更新，且不增加显著新能力。中版本（minor，`X.Y.Z → X.(Y+1).0`）：新增功能或显著扩展现有能力；混合改动取较高级别。不要因普通合入自动升级主版本（major）。
- 本地基准 `versionCode` 每次升级必须递增，并高于合入前 `main` 的基准及上次交付的本地正式版/Debug APK；保留 `GITHUB_RUN_NUMBER` 的 CI 构建号规则，但不得低于源码基准。版本直接固化在源码，不使用临时 init 脚本覆盖。
- 将该 `main` 同步到本地 `fix/debug-side-by-side` 时使用相同的版本，不为同步、重建或重新签名再次升级；保留两线各自的应用 ID 和签名。打包后用 `aapt2 dump badging` 核对最终 `versionName`/`versionCode`，交付时说明本次采用 patch 或 minor 的依据。
- 交付前核对两种应用 ID、APK 签名、字体资产和 SHA-256，分别给出 Release 和 Debug 路径。构建和静态检查通过不等于实机验收。

## Code Conventions & Common Patterns

- Follow Kotlin official style (`kotlin.code.style=official`): four-space indentation, `PascalCase` types/composables, and `camelCase` functions/properties. User-visible strings and existing comments are predominantly Chinese.
- Keep screen-local Compose state in `remember`/`rememberSaveable`; collect shared flows with `collectAsStateWithLifecycle`. Keep playback ownership in the service.
- Use `LaunchedEffect` for keyed UI work and cancellable jobs for lyric loads. Rethrow `CancellationException` before handling other failures; run blocking HTTP work on `Dispatchers.IO`.
- Close OkHttp responses with `use`; report HTTP and empty-body errors explicitly. DTOs use kotlinx.serialization and tolerate unknown response keys. Preserve existing error/status state and `FantasticLyrics` diagnostics instead of silently returning empty content.
- Reuse `ui/Design.kt` dimensions, motion, and composition locals. Lyrics intentionally extend beyond player horizontal padding to avoid clipping scaled text.
- Keep lyric-provider parsing in native Kotlin and preserve the `LyricSong` cache contract. Candidate `source: "amll"` identifies provenance.

## Important Files

- `app/src/main/AndroidManifest.xml`: application, launcher activity, playback service, permissions, and cleartext-server support.
- `FantasticApp.kt`, `MainActivity.kt`, `ui/AppShell.kt`: dependency container and app entry points.
- `data/Prefs.kt`, `data/FantasticApi.kt`, `data/AppleCatalogApi.kt`: settings and server/API access.
- `playback/PlaybackService.kt`, `playback/PlayerConnection.kt`, `playback/FantasticSessionPlayer.kt`, `playback/ApplePlaybackEngine.kt`: Media3 session ownership and source-specific playback adapters.
- `lyrics/LyricsController.kt`, `lyrics/LyricParser.kt`: lyric loading, caching, and the native parser entry point.
- `lyrics/NativeLyricsView.kt`, `lyrics/LyricInkView.kt`, `lyrics/LyricMotion.kt`: native lyric list, Canvas rendering, and animation curves.
- Root `LICENSE` is Apache-2.0 and `app/build.gradle.kts` copies it into the APK assets during `preBuild`.
- `app/build.gradle.kts`, root `build.gradle.kts`, `settings.gradle.kts`, and `gradle.properties`: Android and Kotlin build configuration.
- `.github/workflows/android.yml`: CI APK build and nightly publication.
- The [lyrics renderer guide](app/src/main/assets/lyrics/README.md) describes the parser contract, timing and layout.
- `data/MusicLibrary.kt`, `ui/library/MusicLibraryScreen.kt`: local catalog library state and UI.
- `ui/player/AppleMediaScreen.kt` and `ui/browse/AppleDiscoveryScreen.kt` handle media pages and discovery. [Player behavior and device acceptance](docs/player-behavior.md)

## Runtime/Tooling Preferences

- CI uses Temurin JDK 17 and Gradle 8.11.1. Kotlin is 2.1.0, AGP is 8.7.3, compile/target SDK is 35, minimum SDK is 26, and Java/Kotlin output targets 17.
- Android builds and runtime require only the Android and Gradle toolchain.
- Keep downloaded tools, temporary build setup, and diagnostic artifacts under ignored `.work/`. `app/debug.keystore` is a public debug key, not a production signing secret. `GITHUB_RUN_NUMBER` sets version code and `GITHUB_SHA` supplies the diagnostic build identity.

## Testing & QA

- The repository currently has no automated test sources, fixtures, test-only dependencies, or coverage threshold. Do not add, modify, or run automated tests/assertions unless explicitly requested.
- CI builds the debug APK and publishes a nightly release from `main`; it has no device-install job.
- Build success does not verify the RecyclerView/Canvas lyric surface or network-backed playback. Device, server, and playback acceptance is performed manually; state explicitly which such checks were not run.
- In Settings → 歌词诊断, tap the report to copy lyric status/build details. The `FantasticLyrics` Logcat tag records lyric requests, parsing, timings, and fallbacks.
