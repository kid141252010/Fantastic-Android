# 歌词渲染器

`lyrics.mjs` 保留原生 `FantasticBridge` / `fantastic` 接口，`apple-music-player.mjs` 负责 DOM 排版、跟随、对唱、背景人声与逐词绘制，`apple-music-motion.mjs` 保存独立重写的动画函数。已删除 AMLL 的渲染内核与样式；服务端候选里的 `source: "amll"` 仍表示歌词来源，不代表使用 AMLL 渲染器。

## APK 来源与曲线

样本：用户提供的 `applemusic.apk`，`com.apple.android.music`，版本 **7.0.0 / 1614**。

SHA-256：`49e83b64fccff79310c7dff174e02228dd0455a3d87df4d9655d1e4f8cd2e351`。

反编译结果在项目内 `.work/applemusic-reverse/applemusic/jadx/`，不参与 APK 打包。下列名称为该样本的 jadx 命名；核心动画在 DEX/Java 层，JNI `LyricsController` 负责原应用的 TTML/播放事件，不需要移植 `.so`。

| 行为 | 已确认参数 | APK 实现位置 |
| --- | --- | --- |
| 换行移动 | cubic Bézier `(0.4, 0.1, 0, 1)`；默认 750ms；按句间隙在 480–750ms 之间调整 | `utils.F.f50854i`、`PlayerLyricsViewFragment.f/onCreateView` |
| 下方行错峰 | `round(25 × .25 × (5n − n(n+1)/2))`，`n` 限制到 0–4；不是统一 25ms 递增 | `p236lc.y.a.run` |
| 逐词上浮 | −2dp；质量 1、刚度 25、阻尼比 .93、初速度 0 的弹簧解析解 | `player.C4022z.f0`、`Q2.e.c` |
| 逐词渐变 | 线性时间推进；羽化宽度 30dp；主文字白色 alpha .94、未唱 .18；背景人声 .35 | `C4022z.e0/m0`、`res/values/dimens.xml`、`colors.xml` |
| 长音强调 | Bézier `(.25, .1, .25, 1)`；最大 1.14 倍、5dp 光晕；攻击上限 3000ms，逐字延时和从当前值回落 | `C4022z.W`、`player.C3977g`、`C4022z.f/g` |
| 间奏进度 | Bézier `(0, .25, 1, .58)`；三点依次推进，淡入 750ms、点间 50ms | `C4022z.G/Y` |
| 间奏呼吸 | `X9.a.f26614c` 的 **97 个完整采样点**及原线性插值；基本周期 4000ms，按间奏长度整周期铺开 | `X9.a.getInterpolation`、`C4022z.Y` |
| 间奏退出 | 750ms 放至 1.2 倍，随后用 `(.25, 0, 1, .2)` 在 250ms 内缩至 .5 倍并淡出 | `C4022z.M` |
| 作者淡入 | Bézier `(.75, 0, .25, 1)` | `PlayerLyricsViewFragment.f46134q1` |

贝塞尔函数先求解横轴参数，再计算纵轴；没有把四个控制点当作直接的时间多项式。采样表逐项保存在 `apple-music-motion.mjs`，不是只取几个关键帧。

## 时钟与平台适配

- 主时间单位为毫秒。原生 500ms 锚点在页面外推；词事件按 APK 的 100ms 提前量采样。暂停停止音频时间，跳转直接重建渐变、弹簧、强调和间奏状态。换行使用独立的 UI 时间完成位移。
- 与原生回调一致，当前换行的移动时长取当前句之后的间隙，进入当前句的提前量由前一句事件决定；二者不能简单共用一个「句前间隙」值。
- 原生 RecyclerView 的行移动改为 WebView DOM 的 FLIP 位移；保留原曲线、时长与非线性错峰。字体使用 Android 系统字体，不带 Apple 字体；排版、字形与原应用不能据此声称逐像素相同。
- 原应用的逐行遮罩改为按可见区域计算透明度，避免透明 WebView 的遮罩/加色混合兼容问题；不添加来源不明的「弹簧」或模糊曲线。手动滚动后 5 秒恢复跟随是宿主适配策略，不是声称从 APK 提取的数值。
- 对不宜逐字拆分的 Arabic/Indic 文本保留连写；旧 WebView 的中日韩分词继续使用现有词典。对唱宽度 .85、普通行缩放 .98、高亮行 1、字号 34/22、背景人声上间距 22dp 来自 APK 资源。
- 曲线和参数经过静态提取；尚未进行登录后原版 Apple Music 与移植版的逐帧 A/B 比对，不能声称完整原生事件系统或所有机型上的视觉结果完全一致。

## 上游文件与验证

`ttml.mjs`、`word-segmenter.mjs`、两份词典及其许可证仍由 `scripts/sync-lyrics.sh` 从 Fantastic 的 `web/lyrics/` 同步，版本见 `FANTASTIC_COMMIT`；不要直接修改这些 vendored 文件。同步不会覆盖本地渲染器或重新引入 AMLL。压缩词典以 `.gz.bin` 存放，原生映射回 `.gz` 请求。

设备回归见 `LyricsPageTest`（后台预载后挂载、向前/向后跳转、暂停、切歌清空、真实 DOM 位移验证句前提前量与句后移动时长的区别）和 `LyricsEndToEndTest.lyricsVisibleInNowPlaying`（真实 Compose 播放页布局）。`liveSongPixelsVisible` 另外依赖外部服务器，不属于离线回归。修改后用实际 WebView 截图确认文字像素可见，不能只数 DOM。
