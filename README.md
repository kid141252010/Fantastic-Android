# Fantastic Android

[Fantastic](https://github.com/kid141252010/Fantastic) 的原生安卓客户端。只调用 Fantastic 服务端接口，界面参照安卓版 Apple Music，歌词用 [AMLL](https://github.com/Steve-xmh/applemusic-like-lyrics)。

## 路线

1. **骨架 + CI**（已完成）：Compose 界面外壳、底栏、迷你播放条、全屏播放页；Fantastic API 客户端；QQ / Apple Music 搜索；QQ 原生播放（Media3，音质不可用时自动降级）。
2. **QQ 歌词**（当前）：WebView 里跑 AMLL（`app/src/main/assets/lyrics`，由 `scripts/sync-lyrics.sh` 从 Fantastic 同步），原生每 500ms 推送播放进度锚点，页面每帧外推。
3. Apple Music 播放：先用 WebView 引擎，再换成 Rust 核心（cargo-ndk 复用 Fantastic 的 `am-mp4`、`am-alac` 等 crate）。
4. 全景声、MV、下载、资料库。

动画和手势按真机录屏逐项还原，不使用 Apple Music 安装包里的任何代码或资源。

## 下载

`main` 每次构建后会更新 [Nightly](../../releases/tag/nightly) 预发布里的 `fantastic-debug.apk`。

## 构建

```sh
gradle :app:assembleDebug   # Gradle 8.11、JDK 17、Android SDK 35
```

首次打开后在「主页」右上角进入设置，填写自己的 Fantastic 服务器地址。

## 许可证

AGPL-3.0
