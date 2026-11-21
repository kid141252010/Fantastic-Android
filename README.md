# Fantastic Android

[Fantastic](https://github.com/kid141252010/Fantastic) 的原生安卓客户端。只调用 Fantastic 服务端接口，界面参照安卓版 Apple Music。歌词使用本地独立渲染器，动画曲线和时序参数来自对用户提供的 Apple Music Android APK 的静态分析。

## 路线

1. **骨架 + CI**（已完成）：Compose 界面外壳、底栏、迷你播放条、全屏播放页；Fantastic API 客户端；QQ / Apple Music 搜索；QQ 原生播放（Media3，音质不可用时自动降级）。
2. **QQ 歌词**（当前）：WebView 中的独立渲染器（`app/src/main/assets/lyrics/apple-music-player.mjs`），替代 AMLL；支持逐词渐变、弹簧上浮、长音逐字缩放/光晕、错峰换行、对唱/背景人声和间奏。原生每 500ms 推送播放锚点，动画按音频时间重建，暂停和跳转不会遗留旧动画。
3. Apple Music 播放：先用 WebView 引擎，再换成 Rust 核心（cargo-ndk 复用 Fantastic 的 `am-mp4`、`am-alac` 等 crate）。
4. 全景声、MV、下载、资料库。

曲线来源、全部间奏采样点与移植边界见 [歌词渲染器说明](app/src/main/assets/lyrics/README.md)。不打包 Apple APK 的字节码、原生库、字体或图片；不是直接套用另一套「仿 Apple Music」动画参数。

## 下载

`main` 每次构建后会更新 [Nightly](../../releases/tag/nightly) 预发布里的 `fantastic-debug.apk`。

## 构建

```sh
gradle :app:assembleDebug   # Gradle 8.11、JDK 17、Android SDK 35
```

首次打开后在「主页」右上角进入设置，填写自己的 Fantastic 服务器地址。

## 许可证

Apache-2.0

Project-authored code is Apache-2.0. Bundled third-party components retain their own licenses and notices.
