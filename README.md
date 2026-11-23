# Fantastic Android

Fantastic 的 Android 客户端，支持 Android 8.0 及以上。[Fantastic 服务端](https://github.com/kid141252010/Fantastic)

## 安装与配置

从 Nightly 下载 `fantastic-debug.apk`。首次打开应用后，从「主页」右上角进入设置，填写你的 Fantastic 服务器地址。[Nightly](https://github.com/kid141252010/Fantastic-Android/releases/tag/nightly)

## 功能

- 搜索和播放 Apple Music、QQ 音乐、网易云音乐。搜索结果可播放单曲，也可从专辑、艺人或歌单建立队列。重开应用时恢复曲目、进度、随机和循环状态，等待手动播放。
- 原生歌词支持逐词高亮、翻译、音译、对唱和伴唱。歌词页在前台展开时常亮。Apple 默认优先显示 AMLL 词库中的歌词，缺失或加载失败时使用 Apple 官方歌词。[歌词来源、时序与排版](app/src/main/assets/lyrics/README.md)
- 播放页按窗口宽度请求封面。部分 Apple Music 曲目支持动态封面和取色背景。封面、歌词、待播清单与迷你条共用播放器状态。[播放器对照与验收](APPLE_MUSIC_PARITY.md)
- 在资料库管理收藏与歌单。从「资料库 → 已下载」播放、重试或移除手动下载的歌曲。「设置 → 播放 → 播放缓存上限」控制三种音源共享的整曲缓存，默认 500 MB。手动下载独立保存。

歌词加载有问题时，可在「设置 → 歌词诊断」复制报告。

## 构建

使用 JDK 17、Gradle 8.11.1 和 Android SDK 35。在 `local.properties` 设置 `sdk.dir`，或通过环境变量指定 SDK。

```sh
gradle :app:assembleDebug
```

调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。`main` 构建成功后更新 Nightly。

### 发行 APK

```sh
gradle :app:assembleRelease -PincludeLocalAmFonts=false
```

构建生成 `app/build/outputs/apk/release/app-release-unsigned.apk`。使用 Android SDK Build Tools 中的 `zipalign` 对齐，再用 `apksigner` 和专用发行密钥签名。同包名的发行更新使用同一把密钥。

### 本地可选 AM 字体

将可选的本地字体放在 Git 忽略的 `.work/local-am-fonts/assets/am-fonts/`。目录存在时，Debug 和 Release 可将本地字体用于歌词、歌曲信息与 Compose 主题。公开构建使用 Android 系统字体。可显式禁用本地字体以生成同类产物。

```sh
gradle :app:assembleDebug -PincludeLocalAmFonts=false
```

## 许可证

项目自有代码采用 Apache-2.0。随包 ICU 字典及其衍生词表适用上游许可。[项目许可证](LICENSE) · [字典声明](app/src/main/assets/lyrics/CJDICT-README.md)

启动图标改编自 Fantastic 的旧版红色图标。[上游 SVG](https://github.com/kid141252010/Fantastic/blob/73d1f19c7f49dc77413f5d32f92f639df2a850df/web/icons/icon.svg)
