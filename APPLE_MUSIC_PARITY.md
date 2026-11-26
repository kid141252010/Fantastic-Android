# Apple Music 播放器对照与验收

播放器和歌词以 Apple Music Android **7.0.0 / 1614** 为对照。本文覆盖播放器、歌词、待播清单、操作面板及迷你播放器。原版样本、歌词参数及其来源见[歌词渲染器说明](app/src/main/assets/lyrics/README.md#apk-来源与曲线)。

## 当前播放器

- `PlayerSheetHost` 用同一进度驱动全屏播放器、迷你条、封面和底栏。单一封面节点在实际测量的槽位间交接，页内封面过渡为 300ms。切歌保留开合位置，清空队列后回到收起态。
- 从歌词列表向下拖时，列表先消费滚动。到顶后连续三次未消费的向下滚动及平台触摸阈值决定播放器接管。接管后的位移与释放使用窗口坐标，松手和取消走同一条收束路径。`PlayerSheetMotion` 按 `BottomSheetBehavior` 和 `H2.d` 计算展开、迷你与隐藏位置，使用五次减速曲线和 600ms 时长上限。
- 播放页按宿主像素宽度请求静态大图，封面和静态流动背景复用解码结果。常规背景使用三层旋转以及明暗主题各自的遮罩，依据 `LyricsBackgroundLayerView`。
- 手机竖屏满足 Tall 宽高比条件时使用 3:4 动态封面。Tall 失败后尝试不同 URL 的 Square，最终使用静态封面。Tall 展开且动封可见时，首帧后每 42ms 取样。视频底边上方 150dp 起绘制底部取样的 25px 模糊，底边起叠加 2×2 取色渐变。暂停、切页与视频不可用时使用静态背景。依据 `view_motion_container.xml`、`f1.a` 和 `PlayerMainFragment`。
- API 31 及以上使用 `RenderEffect`。API 26–30 每轴用三个滑动盒近似半径 25 的模糊，边缘重复采样，2×2 取色仍使用精确高斯权重。动封的 Media3 预读目标为 12.5 MiB，磁盘 HLS 缓存上限为 256 MiB。音频整曲缓存单独设置。
- `TransportControls` 使用原生 SeekBar，根据实际播放状态显示缓冲、直播与音质。选中的歌词和待播入口各自更新图标。队列按引擎提供的播放顺序呈现，选择、移除和重排受会话能力约束。
- 重新展开时恢复上次所选页面。当前曲目的歌词确认为 `Missing` 时从歌词页返回封面。歌词页在前台展开时保持屏幕常亮，暂停播放时也保持，离开该页或应用暂停时释放。
- 艺人和专辑从播放器标题区进入。操作面板按实际窗口测量，底部内容避开导航栏，长菜单可在矮窗口中滚动。窗口与滚动布局参考 Apple Music Android 7.0.0 / 1614 的 `action_sheet.xml` 和 `DialogC3470u`。

## 项目取舍

- Fantastic 提供 Apple Music、QQ 音乐和网易云音乐。各音源的设置提供该来源的音质选项，播放页依据实际报告的编码与规格显示徽标和详情。媒体输出使用 Android 系统入口。
- Apple 在线音频由原生播放引擎处理；ALAC 完整播放缓存由同一 JNI/AudioTrack 输出，不依赖设备 MediaCodec 的 ALAC 支持。Apple 媒体页仍保留 WebView。
- 当前服务不提供推荐队列、协作、SharePlay 或人声衰减。自动播放入口显示禁用状态，其他缺少数据或处理能力的入口按实际能力显示。
- 歌词可用时，歌曲菜单显示禁用的「分享歌词」。定时歌词仍可单点定位。歌词尾部创作者文字使用固定透明度，自动跟随由列表管理。
- 「查看歌曲制作信息」按需读取当前录音的署名。Apple Music 通过配置服务读取人员和角色分组，缺少有效人员时使用 `composerName`。QQ 音乐读取官方歌曲详情中的明确角色。网易云音乐只显示官方 LRC 明确标注的「网易云音乐歌词署名」。[Apple Music 制作信息说明](https://support.apple.com/guide/music-web/view-song-credits-apdm3b93fb16/web)
- 公开构建使用 Android 系统字体。可选字体配置见[本地构建说明](AGENTS.md#local-release-apks)。

## Apple 在线流定位与时钟

- 歌词定位沿用 `NativeLyricsView → LyricsController → PlayerConnection → MediaController → FantasticSessionPlayer`，不修改歌词时码或增加 UI 时钟。每次定位同步保存最后目标；暂停定位不自动播放。
- ALAC 的每次定位以请求和 `seekGeneration` 隔离，输出协程独占解码器及 AudioTrack，取消时先摘除输出并 `pause()+flush()`，等待旧协程清理后才创建新输出。时钟为绝对起点加 unsigned 播放头差量，下载/提交帧不充当已播时间；缓冲耗尽停止会话外推，尾部真实排空后才结束。
- AAC/Widevine 沿用 Media3 1.5.1 的原生 HLS/DRM 路径。仅当前请求的非占位 window 可接管位置；加载期多次定位只应用最后目标，保留播放/暂停意图。用户 seek 由 `SimpleBasePlayer` 发出 SEEK discontinuity，不再把小于一秒的位置伪装为自动循环。
- 诊断标签 `FantasticPlayback` 每次接受定位记录 `apple-seek`，对应 ALAC 首输出或 Media3 READY 记录 `apple-seek-output`；包含安全曲目 ID、occurrence、request、seek 与绝对位置，不打印服务器、鉴权或 CDN URL。
- 依据：[Apple Android 同步歌词说明（2025-09-17）](https://support.apple.com/en-us/109355)、[AudioTrack 播放头及 pause/flush 契约](https://developer.android.com/reference/android/media/AudioTrack#getPlaybackHeadPosition())、Media3 1.5.1 [MaskingMediaSource](https://github.com/androidx/media/blob/1.5.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/MaskingMediaSource.java) 与 [SimpleBasePlayer](https://github.com/androidx/media/blob/1.5.1/libraries/common/src/main/java/androidx/media3/common/SimpleBasePlayer.java)。10ms 写入背压轮询、100ms 输出采样是工程实现值，不是 Apple 参数。
- 上一轮在线任意位置播放与真实时钟已由用户实测正常；本轮新增完整 ALAC 缓存路径仍需单独设备验收，不能用旧路径的验收代替。

## Apple 完整 ALAC 预缓存与复用

- 仅当前实际输出的原生 ALAC 变体，在首个非零 PCM 写入后加入既有整曲缓存 worker；首响不等整曲下载。解析结果和 decrypt 仅在进程内传递，不再 prepare 第二次，不将 URL、密钥、模板或 cookie 写入索引和 MediaSession。入队身份使用全局队列 uid，不使用 Apple 连续 run 的局部 index。
- 从第 0 片到末片按顺序封装原始压缩 packet 为完整 M4A，不先生成整曲 PCM、不重编码、不额外请求另一份 ALAC。复用已有 `AUDIO_LANES=3` 上限，待消费与当前消费分片合计最多 8 MiB 声明 range；超大单片独占。decrypt 的完成 LRU 最多 4 片且不超过 8 MiB，在线播放与缓存共享在途结果，取消一个等待者不取消另一个消费者。
- 单文件 `.part` 直接写入 ftyp/64 位 mdat，追加样本表 moov 并同步关闭后，沿用 `.part → .audio` 原子移动及 AtomicFile 索引。逐次 reserve 计入该文件实际累计大小，没有未计预算的 ALAC payload spool。已知时长大于 10 秒且输出少于期望超过 5 秒的预览不得发布为整曲。
- 沿用现有 200MB/500MB/1GB/5GB 容量与每曲一个当前变体、完整 profile 匹配、pin/lease 和 LRU 策略。只运行一个整曲 worker；最近播放的 pending 优先，同时间保留插入顺序。已启动任务切歌后继续，停止、清队列和服务释放仍取消；完成缓存只刷新 upcoming，不抢占当前声音或时钟。
- 再次选歌、重播和恢复队列可优先读取匹配的完整 ALAC 缓存，包括原有完整条目；暂停恢复不打开文件或强制播放。Media3 1.5.1 `Mp4Extractor` 管理 MP4 样本表，用指定音轨的真实 seek point 定位，压缩 packet 交给现有 JNI 解码与 AudioTrack 输出。绝对时钟仍为目标位置加真实已播帧，保留请求/seek generation 隔离和曲尾排空。
- 容器、文件读取或缓存 ALAC 解码失败时，仅当前请求保存实际位置和暂停意图，失效该 UUID 后跳过缓存恢复一次在线来源。硬件/AudioTrack 错误不认作缓存损坏；取消及旧请求错误不触发恢复。AAC、Widevine、空间音频及手动下载沿用既有选路，不因缓存任务降音质或改变网络偏好。
- `FantasticPlaybackCache` 的 `apple-cache-start/complete` 记录安全曲目 ID、uid、request、group、真实文件 UUID、字节、时长和耗时；`FantasticPlayback` 的 `path=cache-alac` 只证明已 acquire 本地文件，首输出记录实际 rate/bits/channels。日志本身不证明出声或零网络请求。
- 依据：[Apple 无损说明（2026-11-12）](https://support.apple.com/en-us/118295) 确认 Android 流播/下载音质分别选择及无损的流量/存储成本；[Media3 1.5.1 Mp4Extractor](https://github.com/androidx/media/blob/1.5.1/libraries/extractor/src/main/java/androidx/media3/extractor/mp4/Mp4Extractor.java) 与 [TrackOutput](https://github.com/androidx/media/blob/1.5.1/libraries/extractor/src/main/java/androidx/media3/extractor/TrackOutput.java) 提供本地 packet/seek 契约。3 槽、8 MiB、4 片和完整性容差均为项目实现值，不是 Apple 公布的缓存参数；不承诺整曲完成时限。
- 本轮 `adb devices -l` 无设备。首次生成耗时、首尾实际无损输出、同音质二次试听无 prepare/CDN/模板请求、断网重启、本地 90s/180s 歌词定位与暂停恢复、生成中快速定位/切歌、空间不足/断网失败、随机混源 uid 和音质/profile 隔离均未运行真实设备验收；构建或包体核验不能代替这些证据。



## 设备验收

多数原先列为待实机验收的项目已由项目维护者在实机验收。下表汇总有具体曲目或操作记录的场景。

| 场景 | API 34 模拟器记录 |
| --- | --- |
| 静态封面与 Tall 背景 | 《Spiegel im Spiegel》在 864px 窗口解码出 864×864 大图。《cardigan》实播时取得 110×72 Tall 帧，约一秒间隔的两张截图记录了视频和下半区变化，暂停后回到静态背景。《Cruel Summer》遇到 Tall HTTP 416 后成功切到 Square。记录在本地 `.work/artwork-5572-verified.log` 与 `.work/artwork-5572-final-cardigan-{a,b}.png`。 |
| 切页、下拉与常亮 | 检查了封面、歌词、待播和迷你条之间的切换及底栏图标状态。用《Patient Zero》观察到歌词列表到顶后短拖回到展开位置、长拖回到迷你条，暂停时歌词页仍常亮。录屏为本地 `.work/lyrics-final-detents.mp4`。 |
| 搜索与资料库 | 打开了真实搜索结果、目录详情、关联艺人和歌单，检查资料库明暗模式、收藏筛选、下载入口及本地歌单添加曲目。断网时仍可进入已收藏专辑的本地操作。 |
| 音质与离线播放 | 核对 QQ 与网易云曲目的无损和 MP3 徽标，以及 Apple、QQ、网易云缓存曲目在断网重启后继续播放并命中歌词缓存。另从「已下载」离线播放了手动下载的 QQ 曲目，并核对过独立的 Apple ALAC 文件。 |

同曲目的原版逐帧对照受 Apple Music 登录限制。API 26–30 的模糊效果、长时间播放以及横屏、分屏、RTL 和读屏的完整组合仍缺少专门的对照记录。

