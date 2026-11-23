# Fantastic Android

[Fantastic](https://github.com/kid141252010/Fantastic) 的原生安卓客户端。

## 路线

1. **骨架 + CI**（已完成）：Compose 界面外壳、底栏、迷你播放条、全屏播放页；Fantastic API 客户端；QQ / Apple Music 搜索；QQ 原生播放（Media3，音质不可用时自动降级）。
2. **原生歌词**（QQ / Apple Music / 网易云）：歌词界面由 Android `RecyclerView` + Canvas 原生渲染（`NativeLyricsView` / `LyricInkView`）；隔离的 WebView 仅解析 Fantastic TTML/歌词候选，不负责绘制。不使用 AMLL 渲染引擎，但保留 AMLL 词库来源；支持逐词渐变、上浮、长音强调、错峰换行、对唱/背景人声和间奏。原生每 500ms 推送播放锚点，暂停与跳转按音频时钟重建状态。
   歌词页在前台展开时保持屏幕常亮。歌词列表先滚动，到顶后继续下拉才会带动播放器，松手后会回到展开位置或收成迷你播放器。重新展开保留所选播放页。菜单和音质面板复用窗口内 Material 3 玻璃材质。音质面板把档位说明与音轨实际报告的编码、采样率、位深和码率分开显示，未上报规格不作推断。
   Apple Music 歌词同时请求 AMLL TTML 与 Apple 官方 TTML，结果仍优先选用 AMLL；AMLL 未收录、请求失败或歌词内容无法解析时使用已并行发起的官方结果，不重发请求。AMLL 地址使用 Fantastic `/assets/host.js` 发布的 `amllApi`，字段缺省时与网页端一样使用公共 AMLL API。AMLL 有效后不等待官方请求完成，并取消尚未完成的官方请求；设置页“歌词诊断”和 `FantasticLyrics` 日志显示实际来源与回退原因，界面仍由原生渲染器绘制。
   已成功解析的整首歌词会自动写入磁盘缓存。逐词时码、翻译和音译会随歌曲一起保存。断网或重启后，播放器可读取本机已有的适用版本。歌词独立使用 16 MiB 缓存，按音源、服务器及 Apple 店面、语言和字形区分。
3. **多音源播放**：Apple Music 在线复用 Fantastic 网页播放器，QQ / 网易云和本地下载走 Media3。搜索曲目单独播放；专辑、艺人、播放列表按服务端曲目分页形成完整队列，从所点曲目开始。应用重启后恢复曲目、进度、待播顺序、随机和循环，但不自动继续播放。应用退到后台且无有效播放请求时释放 MediaSession 绑定；播放中的后台音频保留 `PlaybackService`，回到前台后恢复暂停队列和位置。
   「设置 → 播放 → 播放缓存上限」控制 Apple Music、QQ 音乐和网易云音乐的整曲播放缓存。默认 500 MB，可选 200 MB、500 MB、1 GB、5 GB。在线曲目实际起播后，应用才补齐完整音频。缓存满时优先清理最久未播放且未使用的文件。缓存命中时可播放本地音频。Apple Music 在线启用自动混音、交叉淡化或需要网页空间渲染时继续使用网页播放器。
   播放器按确认的音轨显示无损、高解析度无损、杜比全景声或编码徽标。点按可查看已报告的规格。准备播放期间拖动进度条时，Apple Music 使用本次请求的最终位置。播放器背景复用 Coil 封面预热结果。
4. **离线音乐**：在歌曲或集合的菜单选择“下载”，到「资料库 → 已下载」播放、重试或移除。Apple Music 下载音质可在设置中选择，默认 ALAC 无损。QQ 音乐下载档位包括 FLAC、E-AC-3 JOC、AC-4、RA360、DTS 和 DTS:X；网易云保留 FLAC、MP3 档位。已下载文件不随播放缓存淘汰。

曲线来源、全部间奏采样点与移植边界见 [歌词渲染器说明](app/src/main/assets/lyrics/README.md)。公开仓库不包含 Apple APK 的字节码、原生库、私有字体或图片；私有字体仅允许从下面的本地可选目录加入本地 APK。

## 下载

`main` 每次构建后会更新 [Nightly](../../releases/tag/nightly) 预发布里的 `fantastic-debug.apk`。

## 构建

```sh
gradle :app:assembleDebug   # Gradle 8.11、JDK 17、Android SDK 35
```

### 发行 APK

```sh
gradle :app:assembleRelease -PincludeLocalAmFonts=false
```

构建生成 `app/build/outputs/apk/release/app-release-unsigned.apk`。使用 Android SDK Build Tools 中的 `zipalign` 对齐，再用 `apksigner` 和专用发行密钥签名。同包名的发行更新使用同一把密钥。

### 本地可选 AM 字体

字体文件保存在 `.work/local-am-fonts/assets/am-fonts/`，由现有 `.work/` Git 忽略规则覆盖，不提交字体二进制。

本地目录存在时，Debug 和 Release 构建都会把这些字体作为资产打入 APK；歌词、原生歌曲信息与 Compose 主题优先使用本地 SF Pro 字体。Android 29+ 使用配套脚本字体回退链，缺字仍交给 Android 字体；Android 26/27 使用本地主要字体与系统回退。字体按字重与光学字号缓存。

公开仓库/CI 没有这些字体时照常构建，并自动使用 Android 默认字体。需要在本地生成不含私有字体的 APK，可运行：

```sh
gradle :app:assembleDebug -PincludeLocalAmFonts=false
```


首次打开后在「主页」右上角进入设置，填写自己的 Fantastic 服务器地址。

按用户要求，项目不保留自动化测试及其依赖、脚本和 CI 测试任务。CI 仅构建 APK 并发布 nightly；实机与播放效果由用户亲自验收。

## 许可证

Apache-2.0

Project-authored code is Apache-2.0. Bundled third-party components retain their own licenses and notices.

启动图标改编自 Fantastic 的旧版红色图标。[上游 SVG](https://github.com/kid141252010/Fantastic/blob/73d1f19c7f49dc77413f5d32f92f639df2a850df/web/icons/icon.svg)
