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
- Apple 在线音频由原生播放引擎处理；在线和完整缓存 ALAC 共用 JNI 解码及 Media3 `DefaultAudioSink` 输出，不依赖设备 MediaCodec 的 ALAC 支持。Apple 媒体页仍保留 WebView。
- 当前服务不提供推荐队列、协作、SharePlay 或人声衰减。自动播放入口显示禁用状态，其他缺少数据或处理能力的入口按实际能力显示。
- 歌词可用时，歌曲菜单显示禁用的「分享歌词」。定时歌词仍可单点定位。歌词尾部创作者文字使用固定透明度，自动跟随由列表管理。
- 「查看歌曲制作信息」按需读取当前录音的署名。Apple Music 通过配置服务读取人员和角色分组，缺少有效人员时使用 `composerName`。QQ 音乐读取官方歌曲详情中的明确角色。网易云音乐只显示官方 LRC 明确标注的「网易云音乐歌词署名」。[Apple Music 制作信息说明](https://support.apple.com/guide/music-web/view-song-credits-apdm3b93fb16/web)
- 公开构建使用 Android 系统字体。可选字体配置见[本地构建说明](AGENTS.md#local-release-apks)。

## Apple 在线流定位与时钟

- 歌词定位沿用 `NativeLyricsView → LyricsController → PlayerConnection → MediaController → FantasticSessionPlayer`，不修改歌词时码或增加 UI 时钟。每次定位同步保存最后目标；暂停定位不自动播放。
- ALAC 的每次定位以请求和 `seekGeneration` 隔离，在线与缓存 source 在启动时统一登记，后续定位仍进入 ALAC 重启分支；先捕获最新目标，保持 generation 失效早于取消。主线程串行访问 sink，解码和复制留在 Default dispatcher；取消先摘除输出并 `pause()+flush()`，target 子任务 cancel/join 后才关闭 reader，最终 `reset()+release()`。时钟读取 `DefaultAudioSink.getCurrentPositionUs(sourceEnded)`，未就绪时保留目标位置；不从下载、提交帧或 raw 播放头推算已呈现时间。每 100ms 发布最新输出位置，缓冲耗尽停止会话外推，源 EOF 后反复排空到 `isEnded()` 才结束。
- AAC/Widevine 沿用 Media3 1.5.1 的原生 HLS/DRM 路径。用户定位以 `requestSerial/seekGeneration/targetMs` 保存最新记录；解析前或占位 timeline 期间不丢弃目标，也不使用 `currentPosition` 覆盖它。创建 source 前已有用户定位时先从 0 安装 source，匹配请求的非占位 window 到达后先消费记录，再应用一次 seek；无用户定位的队列恢复仍使用保存的位置初始化 source。保留播放/暂停意图和 `SimpleBasePlayer` 的 SEEK discontinuity，不把小于一秒的位置伪装为自动循环。
- 诊断标签 `FantasticPlayback` 每次接受定位记录 `apple-seek`，对应 ALAC 首输出或 Media3 READY 记录 `apple-seek-output`；包含安全曲目 ID、occurrence、request、seek 与绝对位置，不打印服务器、鉴权或 CDN URL。
- 当前 Apple 曲目定位后立即 `publish()`，使会话快照的 `contentPosition` 读取引擎待应用的位置；`PlayerConnection` 的毫秒转发、SeekBar 松手提交最后位置和非 Apple 定位行为不变。
- 依据：[Apple Android 同步歌词说明（2025-09-17）](https://support.apple.com/en-us/109355)、[AudioTimestamp 时基与能力边界](https://developer.android.com/reference/android/media/AudioTimestamp)、Media3 1.5.1 [AudioSink](https://github.com/androidx/media/blob/1.5.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/AudioSink.java)、[DefaultAudioSink](https://github.com/androidx/media/blob/1.5.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java) 与 [SimpleBasePlayer](https://github.com/androidx/media/blob/1.5.1/libraries/common/src/main/java/androidx/media3/common/SimpleBasePlayer.java)。10ms 背压轮询、100ms 输出／Apple 歌词采样及最小 32KiB PCM buffer 是工程实现值，不是 Apple 参数；buffer 不低于平台 minimum 并按真实 PCM frame 大小向上对齐，无效 minimum 保持失败。
- 上一轮在线任意位置播放与真实时钟已由用户实测正常；本轮新增完整 ALAC 缓存路径仍需单独设备验收，不能用旧路径的验收代替。
- 首次定位修复的本轮检查：Temurin 17.0.21.1+2、Gradle 8.11.1、SDK 35，显式启用本地字体；强制 `compileDebugKotlin` 成功（17 项任务全部执行），随后 `assembleDebug` 成功，Debug v2 签名通过、25 个字体逐项与源文件一致，3 个 TTC 未压缩。证据在忽略目录 `.work/apple-initial-seek-qa/`。ADB 无连接设备，未安装、未检查测试服务器，high/lossless 的首次缓冲 50% 与出声后 25% 四种场景、路由日志匹配和实际出声位置均未验证；不能以构建通过认定设备定位已修复。未新增或运行自动化测试。
- JNI 实际 16-bit 输入使用 PCM16，20/24/32-bit 使用现有 float32 PCM；只有 float 初始化失败且零 PCM 消费时，允许一次重建 sink 交由 Media3 转 PCM16。任何 PCM 消费后不重播降级，不改速度、音质选择或歌词时码。只有输出时钟前进且 sink 仍有 pending data 才报告播放并启动整曲缓存，单纯接受 PCM 不视为出声。
- Apple 歌词每份输出样本立即重锚，允许显示时间向后修正；仅 Apple INTERNAL discontinuity 不递增 lyric seek token，仍刷新 controller 状态，切曲和 occurrence 变化保持原 token 规则。其它音源仍是 500ms 采样、30ms deadband 和普通回调单调显示。定向 JVM 回归为 `LyricPlaybackClockTest`、`PlayerConnectionClockTest`，无 Robolectric 或设备依赖。
- 本次只交付 `fix/apple-playback-latency` 任务分支，版本仍为 `1.5.2 / 18`；未合入 main、未同步并存 Debug 线、未制作 Release、未运行 ADB、未安装或改动设备／服务器。输出时钟是 Android／Media3 的最佳可用估计，不能补偿系统未知的蓝牙或 DSP 延迟；首响、缓冲恢复与视觉同步仍待设备验收，不据静态或 JVM 结果声称随点即播、Apple 实测等价或百分比提速。构建与包体结果保存在任务树 `.work/qa/`。

## Apple 完整 ALAC 预缓存与复用

- 仅当前实际输出的原生 ALAC 变体，在 sink 输出时钟首次真实推进后加入既有整曲缓存 worker；首响不等整曲下载。解析结果和 decrypt 仅在进程内传递，不再 prepare 第二次，不将 URL、密钥、模板或 cookie 写入索引和 MediaSession。入队身份使用全局队列 uid，不使用 Apple 连续 run 的局部 index。
- 从第 0 片到末片按顺序封装原始压缩 packet 为完整 M4A，不先生成整曲 PCM、不重编码、不额外请求另一份 ALAC。共用 Apple 有界分片窗口，后台待消费与当前消费分片合计最多 8 MiB 声明 range，超大单片独占；并发宽度及 Range 条件见下节。decrypt 的完成 LRU 最多 4 片且不超过 8 MiB，在线播放与缓存共享在途结果，取消一个等待者不取消另一个消费者。
- 单文件 `.part` 直接写入 ftyp/64 位 mdat，追加样本表 moov 并同步关闭后，沿用 `.part → .audio` 原子移动及 AtomicFile 索引。逐次 reserve 计入该文件实际累计大小，没有未计预算的 ALAC payload spool。已知时长大于 10 秒且输出少于期望超过 5 秒的预览不得发布为整曲。
- 沿用现有 200MB/500MB/1GB/5GB 容量与每曲一个当前变体、完整 profile 匹配、pin/lease 和 LRU 策略。只运行一个整曲 worker；最近播放的 pending 优先，同时间保留插入顺序。已启动任务切歌后继续，停止、清队列和服务释放仍取消；完成缓存只刷新 upcoming，不抢占当前声音或时钟。
- 再次选歌、重播和恢复队列可优先读取匹配的完整 ALAC 缓存，包括原有完整条目；暂停恢复不打开文件或强制播放。Media3 1.5.1 `Mp4Extractor` 管理 MP4 样本表，用指定音轨的真实 seek point 定位，压缩 packet 交给现有 JNI 解码与 `AudioSink` 输出。在线和缓存共用输出侧 media clock、请求／seek generation 隔离和曲尾排空。
- 容器、文件读取或缓存 ALAC 解码失败时，仅当前请求保存实际位置和暂停意图，失效该 UUID 后跳过缓存恢复一次在线来源。硬件/AudioTrack 错误不认作缓存损坏；取消及旧请求错误不触发恢复。AAC、Widevine、空间音频及手动下载沿用既有选路，不因缓存任务降音质或改变网络偏好。
- `FantasticPlaybackCache` 的 `apple-cache-start/complete` 记录安全曲目 ID、uid、request、group、真实文件 UUID、字节、时长和耗时；`FantasticPlayback` 的 `path=cache-alac` 只证明已 acquire 本地文件，首输出记录实际 rate/bits/channels。日志本身不证明出声或零网络请求。
- 依据：[Apple 无损说明（2026-11-12）](https://support.apple.com/en-us/118295) 确认 Android 流播/下载音质分别选择及无损的流量/存储成本；[Media3 1.5.1 Mp4Extractor](https://github.com/androidx/media/blob/1.5.1/libraries/extractor/src/main/java/androidx/media3/extractor/mp4/Mp4Extractor.java) 与 [TrackOutput](https://github.com/androidx/media/blob/1.5.1/libraries/extractor/src/main/java/androidx/media3/extractor/TrackOutput.java) 提供本地 packet/seek 契约。3 槽、8 MiB、4 片和完整性容差均为项目实现值，不是 Apple 公布的缓存参数；不承诺整曲完成时限。
- 本轮 `adb devices -l` 无设备。首次生成耗时、首尾实际无损输出、同音质二次试听无 prepare/CDN/模板请求、断网重启、本地 90s/180s 歌词定位与暂停恢复、生成中快速定位/切歌、空间不足/断网失败、随机混源 uid 和音质/profile 隔离均未运行真实设备验收；构建或包体核验不能代替这些证据。

## Apple 分片有界并发与缓存流水线

- 仅优化 Apple 当前曲的分片获取与整曲缓存；不改全局 `AUDIO_LANES`、共享 OkHttp Dispatcher、QQ／网易云、整曲 worker 数或发布事务。进程共享准入先派 Playback，再派 Background；总 active 最多 4，后台 active 最多 3。已 active 的后台片不抢占；同片前台消费者提升原 queued flight，不另建 producer。4 槽来自原 reader 1 路加缓存 3 路组合峰值，不是 Apple 官方参数，也不保证绕过 Dispatcher、冷模板 mutex 或已运行同片请求。
- 每个 resolver 按资源保存 Unknown／Verified／Serial／Failed 身份。首个成功媒体片只有精确 206、匹配的 Content-Range 起止及合法 total、精确 body 长度、strong ETag 才能 Verified；init 不探测。Verified 后发送 If-Range，并在读 body 前检查后续成功响应的相同 ETag／total／范围；200、身份缺失或变化使资源终态 Failed，沿既有 HTTP 502 与有限重试失败清理，不混合实体。Serial 不升格，保留原 200／缺头／弱 ETag 兼容能力。
- `AppleSegmentWindow` 在 IO 获取／解密并按索引交付，Verified 时最多保留 3 个后续片。Unknown／Serial 时 reader 保留 1 个后续片；resolved ALAC 缓存保留原三片总窗口（含正在 mux 的片），generic 导出保留单路网络并与当前片解析／写盘重叠。首片／seek 目标不等待预取完成；旧窗口取消并在 NonCancellable 中 join 后才读取新目标，独立缓存 worker 可继续。
- Playback window 只给最近队首保留一个 urgent hint：排入 Background deferred 前登记，已 queued 的 single-flight 提升同一 ticket；消费后提升新队首，错误／reset／close 清理旧 hint。在线目标 Range 与其余本地 run 初始化在获焦成功或延迟后重叠，拒绝获焦不启动目标请求；TRACK 的 Range 与模板结构化并行，任一路失败或取消都 cancel/join 另一路并保留原异常，PREFETCH 和 Widevine 选路不变。
- 保存的 Cardigan master／media 响应关系为 `audio-alac-stereo-44100-24`、双声道、44,100Hz／24-bit、平均 1,592,793bit/s，init 为 1,023 bytes，17 片且 VOD+ENDLIST；首片为 2,692,872 bytes／14.95365s。首片后的两片声明长度相加为 5,876,021 bytes，再加第三片为 8,912,599 bytes，超过 8MiB。这里只核对历史文件和算术，不发网络请求、不将样本当当前变体、吞吐或设备延迟；运行时只用当前 resolver／cookie／JNI 值。目标 Range 仍须完整校验和解密后才能送 PCM，不扩大缓存或取消实体校验。
- 后台 8 MiB 包含当前片和所有在途／待消费片，当前片到下一次 read／reset／close 才释放；前台 8 MiB 只限制后续队列，当前解码片另计。前台空后续队列允许一个大于 8 MiB 的片独占，后台超大片独占整个窗口。声明长度使用防溢出加法；数组只传引用。此预算不是全应用 RSS 上限，完成 LRU、前台当前片和旧 HTTP 200 整对象临时响应另计。
- 两条 exporter 都用一个 writer，Widevine DRM 操作仍串行；每个非空 trun/chunk 只写一次连续 sample 数据，逐 sample 保留 size／duration／index 和递增输出偏移，spool copy 的 64 KiB buffer 循环外复用。reserve、进度、时长／长度校验、payload 清理、part 原子发布与会话去重保持原契约；engine 缓存启动门控改为输出时钟首次推进。
- DEBUG `FantasticPlayback` 增加 `apple-segment-start/end`（job、过滤后的 id/group、index、priority、active/background、queueMs/elapsedMs、bytes、结束状态）、每 read 一条 `apple-window`（role、index、queued、heldBytes、queuedBytes、waitMs、Range 模式）。仅 tagged media 请求的 `apple-segment-http` 记录 headers/status/end/failed、job/index/code/bytes；不记录 URL、资源名、ETag 原值、模板、cookie 或 token。只有 wire headers→body-end 区间重叠才证明实际 HTTP 并发，producer 开始时间不替代它；发布耗时沿用 `FantasticPlaybackCache` 的 `apple-cache-complete elapsedMs`。
- 本次设备前提检查 `adb devices -l` 无设备，未安装、未清缓存，未取得当前 server／storefront／真实变体与可比冷输入。连续播放、缓存忙时 seek／切歌／取消、实际 wire 并发、200／弱 ETag／变实体与超大片边界、AAC／Widevine／手动导出、完整缓存再播、真实播放头、可听输出及完成时长均未端上验收。静态核对、编译和包体核验不能证明卡顿修复或量化加速；构建与 APK 身份证据保存在根工作树忽略目录 `.work/apple-segment-concurrency-qa/`。

## MiniPlayer 材质、资料库与国家来源

- MiniPlayer 与底栏复用同一 `Glass`／`LocalHaze`，不再叠加自己的不透明胶囊底色和边框；保留 48dp 布局、封面交接、拖动、按钮与同一帧透明度。材质关系依据 iOS 26 [WWDC25 284 的 2:31–3:35](https://developer.apple.com/videos/play/wwdc2025/284/)：Music MiniPlayer 是外观匹配 tab bar 的 bottom accessory；[Materials HIG](https://developer.apple.com/design/human-interface-guidelines/materials) 要求控制层浮于内容上方。24dp 模糊、0.8 tint、0.02 noise、8dp 阴影及边框均沿用本项目底栏，不是 Apple 官方 Android 参数；API 26–30 或无采样源沿用共享不透明回退。
- 播放器按实际展开、拖动和收束状态发布系统返回所有权；资料库及其下载／本地专辑子页明确服从该所有权、目录和设置前景层。最近添加歌单仍用已有 `playlistId`／`goBack` 逐层退回来源页，不新增横向手势，也不把它改成歌单类别页。截图当次系统回调被谁消费未获运行证据，不能归因于原本没有 BackHandler。
- Apple 专辑字段投影包含 `artistName` 和 `trackCount`；只信同身份专辑的非负总数，不以已保存数量或可播放返回数猜总数。真实服务 GET 的 us／tw `1716152955` 均返回 `trackCount=11`，关联艺人 `1049828482` 有独立 `Features115/.../mzl.yiqjixhi.jpg` 头像；此证据不等于 APK 已通过界面验收。
- Apple／QQ／网易云的有效专辑信息写入 `am-hook-library` v1 可选 `albumMetadata`；沿用同一 AtomicFile、容量边界与保存错误状态。参考 tracks 不是库成员；Apple 只持久化其中已入库且国家相容的成员。共享请求在进程内锁住恢复、查询、请求与发布窗口，命中后不自动刷新；未知总数不缓存为成功，离线重启与零重复请求仍需真实网络记录验收。
- 完整专辑的艺人关联行按真实 source、artistId 与国家补图，单个失败保留 Person 占位，不用专辑图替代；补图不阻塞曲目展示，不另造图片缓存。
- Apple `CatalogHit`／`Track` 保留实际 catalog 国家，合法同身份 href 优先于开始请求时捕获的国家，其次才是同资源官方 URL。已知国家导出为 `country` 与 `countrySource=catalog`，并保留嵌套 Track 国家；无证据旧 us 保留未知，不删歌曲、不猜当前偏好。资料库键和 mediaId 不因国家变化，目录导航、MediaSession、制作信息、分享、缓存、下载、歌词本地身份与动封查询分别保留来源；不跨两个已知国家补关系或曲序。
- JNI、DRM、ALAC 封装与帧时钟未改变。歌词／媒体解密 wrapper 未暴露地区参数，不新增伪 query；本地隔离不能证明服务端媒体选择地区。真实出声、系统返回手势、明暗玻璃、跨区请求／导入／重启与 QQ／网易云零重复请求必须单独设备验收。

## 资料库专辑艺人入口与 Apple 完整性摘要

- 主线版本 `1.4.6 / 15`：本次按 patch 合入既有资料库艺人入口与 Apple 完整性摘要修复，保留此前歌词修正；其他十一项审查发现不在本次范围。Release 与本地并存 Debug 共用此版本，构建／签名／资产核验和端上验收分别记录，不以打包通过代替页面、持久化或竞争场景验收。
- 本轮仅接通库内专辑歌手名，并持久化 Apple 专辑完整性摘要；其他导航审查发现只报告，不混入页面或播放器重构。入口仍打开全局目录覆盖层，本地专辑和其滚动状态保留在原页面；不添加曲目、不改变队列或系统返回处理。
- 主艺人规则取自本地 Apple Music Android 7.0.0 / 1614：`mediaapi/models/Album.java:412–413` 从专辑 artists 关系的原始第 0 项取 ID，`common/C3608w0.java:345–383` 构造艺人目标。原版的特定库模型曲目回退在当前模型中没有可验证映射，本次不猜测首曲歌手。空、0、非法或异源首项显示无目标，不提升第二项；这是本项目防御策略，不声称原版也做这些过滤。艺人自身官方 URL／href 的已知国家优先于父专辑国家，头像只取艺人资源。
- `artists=null` 表示旧参考尚未读取身份，`[]` 表示已确认无有效原始主艺人。完整专辑查询顺带保存关系；旧 QQ／网易云参考只有首次点击才读取专辑自身，不额外分页、搜索名字或请求艺人详情。只补 artists 的队列命令不会覆盖较新的曲目参考；完整曲目查询失败时仍可独立读取艺人关系。网络错误允许点击重试，已确认空结果不循环联网。
- Apple 摘要键为 source／albumId／有执行请求证据的规范化国家；QQ／网易云仍按 source／albumId。Apple 恢复必须有合法 `country` 和 `countrySource=catalog`，不把偏好或旧默认 us 当成历史证据。沿用 v1、同一个 `music-library.json` 与 AtomicFile，不新增存储或 TTL；曲目按响应顺序裁成当前已保存成员，保留真实 `trackCount` 和艺人顺序。
- 仅 Apple 歌曲首次成为相应专辑的有效库成员、显式专辑入库／更新，或首次喜爱隐式加入专辑时删除该专辑所有国家摘要并推进进程内 revision。重复加同专辑歌曲、已有项目正常喜爱切换、加入本地歌单、播放、下载、删除、排序和普通进出不会触发完整性查询。合并导入只按实际新增成员失效，重复导入优先保留当前成功摘要；整库替换保留文件的有效摘要，同时推进旧／新专辑版本以拒绝旧库在途结果。
- 队列回执只表示记录已接纳，随后等待对应内存快照发布，不当作落盘成功，也不以旧 `persistenceError` 提前结束。版本变化后旧完整性响应重读最新版本，旧艺人响应取消；错误经现有保存状态显示。非 Apple 参考仍按 FIFO 淘汰整条记录，预算含 tracks＋artists；Apple 摘要有独立同额上限，超限拒绝新增记录而不淘汰其他成功摘要。32MiB 检查仍在 AtomicFile.startWrite 前执行。
- 歌手点击保存点击当时的提交回调，等待本页完整性读取结束后才决定是否补关系。提交前核对实时服务器、国家、专辑 revision、菜单和前景；父页检查本地专辑身份，AppShell 同步检查根导航代次及目录／设置／播放器／GlassSheet 原始状态。系统返回权不是独立的异步导航许可，不更改原 BackHandler 或其他目录入口语义。
- 本轮准备时 `adb devices -l` 无设备，未安装或改动任何设备资料库。三音源真实跳转与返回位置、离线重启、服务端零额外请求、导入／替换、容量／写入失败及在途覆盖竞争均须单独端上验收，静态 guard 不能替代这些证据。构建和 APK 证据保存在任务工作树忽略目录 `.work/library-navigation-qa/`。





## Apple Music 搜索选区

- 这是用户指定的 Fantastic 集成功能，不声称是 Apple Music Android 7.0.0 / 1614 的原版控件。地区和语言契约依据 Apple 官方 [Get All Storefronts](https://developer.apple.com/documentation/applemusicapi/get-all-storefronts) 与 [Storefronts and Localization](https://developer.apple.com/documentation/applemusicapi/storefronts-and-localization)；菜单位置依据用户的搜索栏标记图，使用现有 Material3 下拉菜单模式。
- 搜索与设置共用持久化 `storefront` 和只读 `storefrontState`，未保存时默认 `ca`，不迁移已有 `us` 或按 wrapper 首项换区。菜单保留清除按钮，列出全部实时取得的目录地区，wrapper 支持项优先并独立标注；勾选只标记当前选择。支持未知、目录缺失或当前代码不在目录时保留选择并说明，不生成假列表。
- `AppleRegions` 在进程启动先用 IO 读取 AtomicFile `files/apple-regions.json`，以 normalized server 隔离两个可空列表；wrapper 空列表表示已确认没有支持项，null 表示从未成功。成功数据无 TTL；首次缺失的两路并行获取，只允许设置主动刷新，失败不覆盖成功缓存。只存目录四字段和支持代码，不存 endpoint、wrapperUrl、鉴权或错误。读盘、刷新和原子写入由同一 Mutex 串行；取消不合并旧结果，写失败保留内存并提示。
- 搜索/建议保留 330ms / 220ms 防抖，入口显式传入地区快照，Cursor 保留 opaque next 的地区。Apple 切区重建结果、建议、滚动和行面板，旧回调检查实时选择；QQ/网易的地区 key 固定为空。五处 catalog 入口捕获地区，旧 Apple 详情/媒体页在渲染前失效，发现导航按地区重建，资料库仅重载在线参考，不更改收藏、队列或离线身份。
- 设置只改地区时用已缓存 AM 目录验证，不要求 wrapper 支持；未编辑草稿采用实时 prefs，未改变地址或地区允许离线完成。检查新服务器用显式地址请求两路地区和网易能力，只接受本次有效 Fantastic 身份及包含请求地区的 AM 结果；失败不临时污染已保存服务器、不 fallback。普通地区刷新不请求网易、不写地区、不主动停止或重播音频。
- 编译、APK 身份与真实页面/缓存/网络验收分别记录；构建通过不等于已验证切区音频连续性或所有手动场景。
- 本任务验证：带私有字体的 fresh `compileDebugKotlin --offline --rerun-tasks` 13s 成功，17 项全部执行；`assembleDebug --offline` 8s 成功。验收 APK 沿用基线 `1.3.1 / 8`，正式 applicationId 加既有公开 Debug v2 签名（不是发行签名或 `.debug` 并存包）；25 字体逐项大小/SHA-256 匹配，三个 TTC 未压缩。未新增或运行自动化测试/断言。
- 本机真实转发器以原 OkHttp UA 转发 `/status` 与 `/amp/v1/storefronts`，分别取得 HTTP 200、wrapper `[ca]` 及 172 个含 `ca/jp/us` 的目录条目；这不是 app 页面验收，也不是固定地区数。专用 API34 `emulator-5620` 于 32.5s 启动，新 APK 安装 Success 并启动；75.6s 观察到 app 创建 v1、单服务器条目的缓存文件，但两路仍未成功取得数据，列表均为 null。设备上游连通诊断 25s 超时，宿主 `screenLocked=true`；未操作页面、未取得本轮 UI 截图，菜单位置/滚动、切区请求/分页/建议、成功缓存重启复用、手动刷新及失败保留、草稿服务器隔离、旧详情销毁与音频连续性均未通过手动验收。日志位于任务树忽略目录 `.work/apple-search-region-qa/`；没有清理任何既有设备数据。

## Apple ALAC 音频焦点

- 此修复遵循 Android 官方[音频焦点生命周期](https://developer.android.com/media/optimize/audio-focus)，不声称已取得 Apple Music 官方客户端的焦点参数。仅在 `ApplePlaybackEngine` 内管理自建 ALAC `AudioTrack`；焦点请求与输出共用 `USAGE_MEDIA`／`CONTENT_TYPE_MUSIC` 属性，使用 `AUDIOFOCUS_GAIN` 和延迟获焦。未设置 `setWillPauseWhenDucked(true)`；系统自动 duck 保留，收到 duck 回调时按批准方案以 `0.2f` 兜底，gain 恢复 `1.0f`，此系数不是 Apple 官方参数。
- 实现约定：登记当前输出后、首次 play／PCM 写入前请求焦点；延迟获焦等待且不报告源错误，失败沿现有错误通道显示“音频焦点不可用”。瞬时丢焦暂停并保留用户播放意图，gain 仅恢复仍有效且用户未暂停的播放；永久丢焦保存真实播放头、取消并清空输出，手动播放从保存位置重建解码器。在线 ALAC 在统一启动点登记 source，使位置保存及手动恢复使用真实 ALAC 播放头。
- 用户暂停、停止、切歌、源错误、自然结束、服务释放及转入 Media3 时撤销 ALAC 焦点请求；每次撤销使回调 epoch 失效，回调另核对当前 request serial，不能启动已取消的输出。seek 保留同一有效请求并为重建输出保留 duck 音量；旧 generation 的 finally 不撤销新输出的焦点。`PlaybackService`、`FantasticSessionPlayer`、清单及两个既有 Media3 `setAudioAttributes(..., true)` 未改变。
- 焦点等待期间引擎 `isPlaying=false`、`buffering=false`，真实网络等待仍由输出状态判断。既有会话适配器会把 `playWhenReady && !isPlaying` 映射为 `STATE_BUFFERING`；本任务遵照范围未改适配器，不能据此声称会话已单独呈现焦点抑制原因。
- 构建证据：隔离分支 `fix/apple-audio-focus` 基于 `f7d6fae`，版本保留 `1.4.1 / 10`。Temurin 17.0.21.1+2、Gradle 8.11.1、SDK 35，显式 `includeLocalAmFonts=true`；fresh 离线 Kotlin 编译 19s 成功、17 项全部执行；独立 Debug 构建 10s 成功。未重编 JNI，未新增或运行自动化测试／断言／lint。
- APK：任务树 `app/build/outputs/apk/debug/app-debug.apk`，197966928 字节，SHA-256 `850aef692d13219757f4c44893e9f71b644ae84c8c495bc2df38ccb669d83263`。正式 applicationId 加原公开 Debug v2 签名（非发行签名，非 `.debug` 并存包）；25 字体及 8 JNI 库逐项匹配，三个 TTC 未压缩，16KiB zipalign 通过。详细证据位于根工作树忽略目录 `.work/apple-audio-focus-qa/`。
- 构建后 `adb devices -l` 无设备；未安装、未清缓存。实际 ALAC 出声前获焦、第二音频应用打断、瞬时恢复位置、永久丢焦保持暂停、duck／恢复音量、MediaSession 状态、Media3 `mode=mse` 回归以及停止／释放后无迟到恢复均未端上验收；源码和 APK 核验不能代替实际扬声器与日志证据。

## 歌词三类行凸显变换修正

- 原版固定为 Apple Music Android 7.0.0 / 1614，`applemusic.apk` SHA-256 为 `49e83b64fccff79310c7dff174e02228dd0455a3d87df4d9655d1e4f8cd2e351`。依据原版 `lyrics_line_karaoke.xml`、`C4022z.U` 与 `z.smali` 的实际 animator target／`end()`，不以 Java 的 `j0` 占位实现推导语义。
- Word 前景主词、配对或既有回退音译及译文共用一次父组矩阵，轴取实际 Flexbox 外框的物理左／右底角。外框沿用文本 edge 与窄行宽度，再包含横向 12dp padding；底边包含已显示注释和全部折行，但不含行根底部 10dp 或伴唱。伴唱是兄弟组，只应用自身 `.9↔1`，不再乘前景 `.98↔1`；clip／alpha 层先回到原行坐标，最终恢复根 Canvas checkpoint。
- 选轴复用 `NativeTtmlParser.startsRtl` 的首 letter U+0590…U+06FF 分类。Word 的 `duetRightAligned` 已是物理侧，非 null 时直接使用（含 false），否则只分类前景主词；伴唱跟随此前景物理侧，不再次 XOR。逐句主词、音译、译文仍为独立完整文本、各自底边，并共享本声部主词的选轴侧别；None、间奏、创作者以及原有 Bidi 排版和扫亮方向不变。
- 行级真实状态改向使用固定端点：进入 scale `.98→1`／alpha `0→1`，退出反向；延迟期间保持新方向固定起点，不从打断瞬间采样值续接。保留既有 scale 进入 150ms＋500ms、退出 50ms＋350ms，alpha 进入 250ms＋250ms、退出 250ms＋350ms 及两套原曲线。同状态不重启，deferRetirement 仍提前返回，snap／低动态仍直接目标；注释 payload 只重建几何，不重置同行 emphasized、scaleAt／alphaAt 或播放位置，跨行绑定仍初始化。
- 不改行级 native 集合事件时刻、列表位移、音频时钟、+100ms 扫亮、字级 −2dp 上浮／长音及退场回落。伴唱 22dp 间隔、高度与显现时序仍沿用现状；原版高度 ValueAnimator 的 q／r 回调未确认，不宣称其过渡 pivotY 已逐帧等价。仅覆盖现有主词主位模式，不把解析纯文本与 native HTML 的所有混排输入称为像素等价。
- 静态几何手算：令前景轴 `(20,150)`，未缩放点 A `(32,40)`、B `(140,40)`、R `(32,80)`、T `(32,120)`；统一 `.98` 矩阵得到 `(31.76,42.2)`、`(137.6,42.2)`、`(31.76,81.4)`、`(31.76,120.6)`，s=1 恢复原点。这不是 Apple 实测参数或设备调用结果。固定端点中途改向、注释动画中重排、Word 兄弟组、Line 三类文本、窄行／RTL 和低动态均须另有真实设备输入及中间帧证据；编译与包体核验不等于视觉验收。

## 设备验收

多数原先列为待实机验收的项目已由项目维护者在实机验收。下表汇总有具体曲目或操作记录的场景。

| 场景 | API 34 模拟器记录 |
| --- | --- |
| 静态封面与 Tall 背景 | 《Spiegel im Spiegel》在 864px 窗口解码出 864×864 大图。《cardigan》实播时取得 110×72 Tall 帧，约一秒间隔的两张截图记录了视频和下半区变化，暂停后回到静态背景。《Cruel Summer》遇到 Tall HTTP 416 后成功切到 Square。记录在本地 `.work/artwork-5572-verified.log` 与 `.work/artwork-5572-final-cardigan-{a,b}.png`。 |
| 切页、下拉与常亮 | 检查了封面、歌词、待播和迷你条之间的切换及底栏图标状态。用《Patient Zero》观察到歌词列表到顶后短拖回到展开位置、长拖回到迷你条，暂停时歌词页仍常亮。录屏为本地 `.work/lyrics-final-detents.mp4`。 |
| 搜索与资料库 | 打开了真实搜索结果、目录详情、关联艺人和歌单，检查资料库明暗模式、收藏筛选、下载入口及本地歌单添加曲目。断网时仍可进入已收藏专辑的本地操作。 |
| 音质与离线播放 | 核对 QQ 与网易云曲目的无损和 MP3 徽标，以及 Apple、QQ、网易云缓存曲目在断网重启后继续播放并命中歌词缓存。另从「已下载」离线播放了手动下载的 QQ 曲目，并核对过独立的 Apple ALAC 文件。 |

同曲目的原版逐帧对照受 Apple Music 登录限制。API 26–30 的模糊效果、长时间播放以及横屏、分屏、RTL 和读屏的完整组合仍缺少专门的对照记录。

## 资料库导航十一项修复

- 独立任务分支基于 `fb70bf9688a66115b060e2397bda766db5989221`，保留 `1.5.0 / 16`、PingFangUI-VF 和既有播放／资料库格式；不在此任务合入 main、升版或同步本地 Debug。此前「其他十一项只报告」仅描述上一轮的范围，本节记录后续修复。
- 原版参考固定为本地 Apple Music Android `7.0.0 / 1614`，不是最新版本声明。`player/d1.java:397–469` 证明已知单目标直接导航、多目标独立选择器且艺人在前、零目标不动作；本项目的缺 ID 查询、加载／空／失败／重试是多音源稀疏元数据扩展。`common/C3528t.java:843–862,1163–1177` 只阻止当前同目的地，不清祖先历史。`common/actionsheet/C3453d.java:778–801,1371–1398` 的头部艺人／专辑链接与普通命令分离。简中 `empty_favorite_playlist_*` 为下表空态原文。

| 项目 | 实施行为 | 与 AM 的边界 |
| --- | --- | --- |
| ① 下载 | Ready 记录更多菜单使用所点歌曲，头部标题／艺人分别去专辑／艺人；查看详情仍去歌曲；移除下载独立于库成员。 | 保留 Fantastic 扁平下载记录页，不重做 AM 下载分类／最近下载。 |
| ② 移动 | 根行与目标采用 LazyColumn，复用文件夹索引，保留祖先防环与存储守卫。 | AM 只核实 Add to Playlist recycler／文件夹钻取，未证实同款 Move 对话框。 |
| ③ 媒体 | Dialog 系统返回和页内返回依次消费操作面板、custom fullscreen、直接允许前驱、关闭。 | Fantastic 仍是受限 WebView，不是 AM 原生视频 Activity；PiP／station／post 细节未证实。 |
| ④ 空态 | 喜爱歌曲显示「未添加歌曲／喜爱的歌曲将在这里显示。」，不带新建歌单动作。 | 文案来自原版资源；保留 Fantastic 自身入口显示规则。 |
| ⑤ 删除 | 确认文案明确本机子文件夹、本地歌单及保存目录歌单的递归范围，不删除云端歌单或独立歌曲。 | 陈述本项目真实行为，未证实 AM Android 相同删除／级联。 |
| ⑥ 标题 | 0／1／2 目标分别明确空／直接导航／艺人后专辑选择；缺 ID 首次标题点击才查，失败显式重试，在途回包受 occurrence／上下文／面板 epoch／实时运动状态限制。 | 已知目标路由及行序参考原版；查询和反馈为 Fantastic 扩展。compact 仅艺人可点，More 不承担关系查询。 |
| ⑦ 历史 | 外部目录打开独立计数，同当前四元组不重复，其他追加；A→B 后再去 A 保留 B 返回路径。 | 不清祖先栈；保留本项目先打开再折叠，未对齐 AM 单目标先请求折叠的时序。 |
| ⑧ 更多 | 每个详情帧仅注册已加载模型的动作，加载／失败不排队，重试成功不重放点击。 | 参考实体就绪门控，不声称 disabled 外观与所有 AM 页面一致。 |
| ⑨ 共享菜单 | 仅歌曲菜单 opt in，现有头部文字链接按所选歌曲解析；不新增两条命令；owner 先核对身份再关闭菜单并导航。 | 播放器 More／媒体／集合不启用；QQ／网易属于跨音源适配。 |
| ⑩ 国家 | artistCountry／albumCountry 只存各自有效关系资源证据；成对跟随 ID，经 Media3 extras、v1 嵌套 track、队列／下载 serializer 保存，旧缺字段默认 null。 | 是数据正确性修复，不声称观察到 AM 跨区响应或服务自然错区；不写回播放器歌曲、不额外分页或修改库成员。 |
| ⑪ 身份 | QQ／网易歌曲及艺人先校验响应 ID，再构造页面和关系，错 ID 沿失败／重新加载路径。 | 本项目多音源防御，不归为 AM 实现。 |

- 构建、APK 身份／字体／签名核验与真实 UI、网络、持久化验收独立记录于任务树 `.work/eleven-fixes-qa/`。本节的源码行为不表示十一项已经端上通过；没有获准隔离 serial、自然响应或可修改测试输入时保留未验收，不造响应、不导入替换真实库、不改服务器、不自动启动模拟器。

## 整专辑入库成员修复与十一项合入

- 本轮 `1.5.1 / 17` 是既有入库及导航缺陷的单次 patch；合入固定批准的十一项提交 `8e1176713f808b54ac8b9a7cf75addbe28ac26b1`，不增加全库联网迁移、云同步或整专辑级联删除。旧空记录可通过明确的「更新资料库专辑」补齐。
- 操作语义参考 Apple 官方[添加音乐](https://support.apple.com/en-us/118288)与[喜爱音乐](https://support.apple.com/en-us/111118)：整专辑与单曲添加是不同操作，喜爱专辑会加入资料库。这里只约束成员授权，不声称 Fantastic 本机存储、菜单外观或云行为与 Apple Music Android 等价。
- 显式专辑添加／更新和首次喜爱走同一批命令，验证完整音频列表后一次发布专辑及顶层真实歌曲成员；空列表、异源／异专辑、无效歌曲或超过 10000 个条目时整批拒绝。保留既有歌曲加入时间、喜爱、文件夹、metadata 及播放列表 UID／内容；喜爱专辑不批量喜爱其歌曲，取消喜爱也不移除歌曲。
- 根详情继续使用现有完整 resolver；库内及其他无 resolver 的菜单复用未裁剪的提供者读取，Apple 读尽分页并校验身份／国家，不以合法空／部分摘要缓存或菜单已载入子集冒充完整结果。点击时固定专辑及 Apple 国家，失败沿原操作面板显示并允许明确重试。
- 普通 `add`、歌曲菜单的父专辑参考、被动摘要、成员投影和播放／下载／本地歌单语义不变。`addAlbum` 在现有 IO 队列中完成规范化、去重、容量门控和合并，Apple 受影响专辑统一失效一次；沿用 v1 与同一 AtomicFile，回执只表示批命令接纳，不是落盘保证，32MiB 或实际 IO 失败仍由 `persistenceError` 表达。
- 最终两线源码身份、独立四次离线构建、原密钥签名及包体核验记录于任务树 `.work/album-library-merge-qa/`。UI、三音源自然完整响应、旧空记录／部分成员／缓存回退、单曲不扩张、失败／容量及读盘重启恢复必须分别取得获准隔离设备证据；源码和构建通过不能替代成员落盘或十一项端上回归验收。

