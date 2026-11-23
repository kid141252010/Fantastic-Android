# 歌词渲染器

`lyrics.mjs` adapts provider payloads; the locally maintained `ttml.mjs` parses TTML inside `WebViewAssetLoader`. Neither module draws the playback screen. `NativeLyricsView` and `LyricInkView` render lyrics with Android `RecyclerView` and Canvas; `LyricMotion.kt` owns native timing curves. There is no AMLL rendering engine; candidate `source: "amll"` identifies lyric provenance only.

## Apple Music 歌词来源

- Apple 来源默认优先使用 AMLL 词库，通过 [AMLL 原生接口](https://amll.dev/reference/http-api/native.html) 的 `/v1/lyrics/get?appleMusicId=<歌曲ID>` 精确匹配，不用歌名搜索替换成其他版本。
- 词库地址读取当前 Fantastic `/assets/host.js` 中的 `amllApi`，按服务器地址缓存成功解析的端点；换服务器后重新读取。不执行配置脚本，也不转交 Apple 账号或内容密钥。仅字段缺省时沿用 Fantastic 网页端的 `https://api.amll.dev` 默认值，不能用公共地址覆盖部署配置。
- 使用默认参数正常切歌时，Apple 官方与 AMLL 在同一任务内并行请求，结果仍优先选用有效 AMLL。AMLL 有效后取消尚未完成的官方请求；未收录、请求异常或歌词内容无效时使用已发起的 Apple `/lyrics/{id}` 结果，不重发。
- Apple `/lyrics/{id}` 默认发送 `language=zh-Hans-CN`、`script=zh-Latn`。设置中用“完成”保存，留空恢复相应默认值。自定义参数与同曲刷新只请求官方来源；播放中保存后请求一次，不改音频位置，暂停或缓冲期间的改动合并到同曲恢复播放时请求。换歌不补发上一首。`language` 同时用于选择官方 TTML 译文，`script` 仅作为请求参数，不过滤回包的音译语言。
- 每路普通网络错误独立保存，不取消另一路。切歌取消会同时取消两路，不能触发旧曲回退；解析器自身的 15 秒超时仍报告“歌词解析超时”。两个来源继续复用现有歌词专用客户端与 TTML 解析器，QQ／网易候选优先级不变。
- “歌词诊断”与 `FantasticLyrics` 日志记录来源、请求／解析阶段及回退原因；并行开始或单独官方请求会记录 `language` 和 `script`。请求耗时按任务实际起止记录，解析耗时从实际解析开始，不把等待 AMLL 的时间算进官方请求或解析耗时。总耗时是整个加载任务经过的时间，不是两路耗时之和。
- AMLL 是歌词数据来源，不是渲染引擎。Apple 与 AMLL TTML 都走原有 `parseFantasticLyrics("apple", ...)` 和 Android 原生歌词表面，不改字体、排版、动画或播放／解密实现。


## 中西文自动间距

原生歌词排版默认启用 AMLL fork `master`（`1682096`）同款的中西文自动间距：在汉字、平假名、片假名与拉丁字母 / ASCII 数字相邻处加入 `0.125em` 的渲染间隙；ASCII 数字与波浪号相邻时也加间隙。标点、原有空白、emoji 和全角拉丁字母不会触发间距。只影响 Canvas 布局，不改歌词源文本、词时间或解析结果；主歌词、背景人声、翻译和音译都使用同一规则。

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
| 原版作者淡入（本项目不采用） | Bézier `(.75, 0, .25, 1)`；按用户要求，创作者行不再叠加这段随播放进度变化的淡入 | `PlayerLyricsViewFragment.f46134q1` |
| 主行激活淡入 | 主文本 alpha 进入延迟 250ms / 时长 250ms，退出延迟 250ms / 时长 350ms，退出曲线 `(.39, .575, .565, 1)` | `U0.V`、`BR.isSeeAllInvisible`、`BR.queueItem`、`utils.F.f50855j` |
| 背景声部展开/收起 | `C4022z.j0`：高度为 0 的背景 Flexbox 在 D（当前行移动时长）结束后恢复 `wrap_content`；进入 alpha 0 / scale .9，在 `round(.85D)` 延迟后用 500ms / `(.4,.1,0,1)` 到 alpha/scale 1；正常退出 scale 至 .9 用时 500ms，alpha 至 0 用时 250ms / `(.39,.575,.565,1)` | `C4022z.j0` DEX、`player.D`、`player.E` |
| Player / artwork 转场 | Player 普通 enter/exit fade 500ms / `(.25,.1,.25,1)`；共享封面 300ms / `(.2,.06,0,1)` | `PlayerMainFragment.q1`、`p156ha.f`、`utils.F.f50852g/h` |
| 动态 artwork 背景 | 三层旋转周期 120/90/70s，饱和度 2.5，低分辨率层、黑白 scrim、半径 25 blur | `LyricsBackgroundLayerView.b` |

贝塞尔曲线由 Android `PathInterpolator` 求解；完整呼吸采样表和线性插值保存在 `LyricMotion.kt`，不从少量关键帧拟合。逐行位移通过 stable IDs 取回中断时的屏幕位置，再以原曲线重定向。

## 时钟与平台适配

- 原生每 500ms 推送播放锚点；Canvas 按单调时钟外推到当前音频时间，逐词扫亮保留 APK 的 100ms 提前量。偏差超过 30ms 时仍重设播放锚点，但普通回执的显示时间取外推时间与上一已派发时间的较大值，不因小幅回调倒退重建呈现组或让扫亮后退。暂停冻结外推；首次锚定、切歌及显式 seek/discontinuity 重置单调边界，允许立即回到较早词时码。同曲跳转的列表从当前可见位置沿换行曲线过渡，后续回执不强制瞬移；首次载入和切歌仍直接定位。
- 换行采用 RecyclerView 的真实布局与 `ViewPropertyAnimator`；同一帧重定位取当前可见位置，取消旧动画并用 APK 的 duration / stagger 重定向。安卓触控、fling 与滚动条由 RecyclerView 处理；手动滚动时跟随暂停，保留既有自动跟随判断与歌词点按跳转，不再显示额外的回跟随按钮。
- 同步歌词行的 seek/分享命中按 DOWN 起点限制在排除左右各 20dp 后的中间带，依据原版 7.0.0 / 1614 的 `lyrics_line_karaoke.xml` 根 margin 与 `lyrics_line_margin_horiz=20dp`；不改全宽 item、正文 32dp inset、行高或折行。带外起点仍走原生触控/滚动派发，但取消待执行 tap/long-press 并清除 pressed，不 seek、不分享、不显示误导 ripple；foreground hotspot 使用同一水平带。总宽不大于 40dp 时退回全宽目标。UP/CANCEL 派发结束后恢复非触摸默认许可，保留无障碍和键盘点击。
- 播放控件与下半区歌词显隐使用同一 500ms Transition；控件保留加速进入/减速退出，底部 mask 沿原 ValueAnimator 默认的 AccelerateDecelerateInterpolator 插值。按样本 W1 / k 固定底部范围为 27.75% 加实际控件 inset，颜色/stops 在显示态与隐藏态之间连续变化，不再收到布尔值就改变高度。进度帧不重设 padding、不触发回跟随；顶部 shader、词时钟和行强调不随这段动画重建。
- 触摸/弹出菜单会暂停待执行的 3 秒计时；用户滑动隐藏控件后，释放且无进一步操作 3 秒也会转入超时隐藏，即使已暂停播放。超时后下半区首个短点按优先显示控件，不跳歌词；未超时的手动浏览仍允许正常点按、长按和滚动。来源切换只改变触控优先级，不重播显隐；0.425 边界不变。
- TTML 的显式 `<p begin/end>` 对独立保存为 `paragraphBegin/paragraphEnd`；两个原属性均非空且 end > begin 才有效，显式零起点有效，缺失或零长返回 null。保留原 parseTime 错误；不从 dur、词、声部或注释推断。原 begin/end 仍合并主/背景词包络，继续用于点击、间奏和词时钟；原始 YRC/LRC 与旧 JSON 缺字段时沿用原行时码。依据为 [Apple TTML Timing](https://help.apple.com/itc/videoaudioassetguide/en.lproj/itcd7579a252.html#itcd1f730b22)。
- 准备点为 `max(0, cueBegin - lead)`。首组和逐句 lead 为 500ms；逐词后续组沿用前一时间组最后一行的 D，同起点共用 lead。D 为 `round(480 + (clamp(next.cueBegin - cueEnd, 200, 750) - 200) * 270/550)`，末行向该函数传入 0 间隙。只在构造时按 cueBegin / lyricIndex 排时序，显示顺序与稳定 ID 不变；替代旧 `previous.end + 300ms`。offset 与 D 来自 `PlayerLyricsViewFragment.f/onCreate/d2`，不是声称测出了同一 JNI 回调时点。
- 最新已准备行的 cueBegin 为查询点，同起点和覆盖该点的已准备主行构成候选组。非空子集（含相等）保留旧组；只有新增 ID 才按集合差交接，不因本行结束、末词结束、离屏、回收或拖动退亮。新组首行定位、尾行决定 D 与 stagger；相交且不连续、尾索引推进时只跟随组尾，连续性按 lyricIndex 判断。逐词候选含背景声部时仅保留候选与旧组交集及最新准备行，不推测原版缺失的词 ID 状态。长间奏仅在自身准备点切入时清组，下一真实行已准备后不再抢焦点；作者项仍在原 end + 1000ms 定位而不清组。仅首次锚定、显式 seek/discontinuity 和切歌重建，普通播放锚点的倒退修正不重建。
- 非成员行从对应位移开始的布局/绘制帧提交缩放复位、降亮和背景退出；行错峰开始前只暂缓退场，不冻结原始词时钟。按 `U0` 行绑定中主词、翻译及音译共用的 pivot 回调，各歌词 Block 的 Y 轴取自身底边、X 轴按主词方向取自身左/右边缘，不按翻译字符串方向另选一侧；逐词单元复用物理行 rightAligned，右边缘包含横向压缩比例。创作者/归属文字保留原中心轴，背景声部 .9→1 的独立动画仍保留自己的底边/端点 pivot。缩放退出延迟 50ms / 时长 350ms，alpha 退出延迟 250ms / 时长 350ms，仍用各自原曲线。无位移或手动浏览不等待，低动态直接落到最终状态；`moveEndAt` 仅负责清理额外布局空间，不控制退亮或背景显现。
- 逐词高亮和长音先在原生 Canvas 内通过 Alpha mask / `DST_IN` 计算字形与光晕的覆盖度；视口再应用上下渐隐，最后以 `PorterDuff.Mode.ADD` 将整层歌词合成到封面背景。后者对应原版 `PlayerLyricsViewFragment.onCreateView` 的 `setCanvasLayerPaint(ADD)` 与 `AlphaGradientFrameLayout.dispatchDraw` 的层恢复画笔，由 `NativeLyricsView.dispatchDraw` 实现；不能用字内 mask 代替最终加色合成。词/行状态仍由音频时间驱动，seek 可重建扫亮状态。
- 对不宜逐字拆分的 Arabic/Indic 文本保留连写；逐字定位以 Android `BreakIterator` 和 `StaticLayout` grapheme / glyph 行为为准。对唱宽度 .85、正常行缩放 .98、高亮行缩放 1、34/22sp 主/背景人声、背景人声上间距 22dp 来自 APK 资源。原行缩放使用 APK 曲线 `(0.4, 0.1, 0, 1)`：高亮延迟 150ms 后运行 500ms；复位延迟 50ms 后运行 350ms。
- 翻译按计时模式分别处理：逐词主/背景译文为 Title3Emphasized 20sp Bold / SubheadBoldShort 14sp Bold，上间距 10/2dp；`C4022z.l` 将最后的译文 mask 固定为 resting palette（白色 alpha 18%），不随主词扫亮或激活变亮。逐句译文为 20sp Bold，`lyrics_line` 的绑定不对译文设置 alpha，保留白色 100%。无时间戳的静态译文为 SubheadEmphasized 15sp Medium、4dp 间距、深色次级标签色 `#99ebebf5`（60% alpha）。45% 次级 alpha 仅保留给相应播放器归属文字，不再错误套给所有译文。来源为对应布局、`C4022z.l` 字节码、`C6031k9.l`、`styles.xml`、`colors.xml` 和 `dimens.xml`。
- 音译共享对应主词的 begin/duration，但使用独立的物理行 sweep、文字方向、文本长度、CJK 分类和列宽扩展条件，不继承主词的 grouped 标记或 rush。音译只扫亮，不独立触发拉伸、上浮或光晕；长音分组和伸缩仅作用于主词 glyph。
- 活跃背景声部保留独立 alpha/scale 状态；所属行进入已接受呈现组时立即预留完整高度。展开期间 alpha 固定为 0、scale 固定为 .9；本行和已附着的下一行布局及位移完成后就放行显现，不等其他行的 stagger 或背景词时码。保留 `round(.85D)` 最短进入延迟及 500ms / `(.4,.1,0,1)` 淡入缩放；alpha 作用于包含正文、逐词 mask、音译和翻译的同一图层，完全显示时不再额外分配图层。退出仍先淡出/缩放再移除布局；暂停不冻结过渡动画，首次载入和低动态模式直接同步最终状态。
- Full player、cover 与 lyrics pane 使用原版 fade 及 shared-artwork bounds 参数；封面背景由三层旋转 artwork 组成，并在 artwork 更换时交叉淡入。
- 曲线和参数经过静态提取；尚未进行登录后原版 Apple Music 与移植版的逐帧 A/B 比对，不能声称完整原生事件系统或所有机型上的视觉结果完全一致。

## 逐词音译

- APK `C4022z.b0/c0` 用词的 `getBegin()` 查找音译，并通过 `lyrics_pronunciation_vertical_group` 将主词与音译作为同一折行单元；主音译为 Title3Emphasized 20sp Bold，背景音译为 SubheadBoldShort 14sp Bold。按 `sf_font_tracking_font_units` 与 `g1` 的单位换算，20/14sp 音译及译文字距分别为 −46/2048em、−22/2048em，15sp 静态译文为 −32/2048em。8dp 仅为逐词组底部留白，不放在主词与音译之间；逐句布局不额外插入这段留白。本地可选 AM 字体优先用于主词、音译、译文与作者文字，实际 Medium/Semibold/Black 字重按样本映射为 510/590/1000，光学字号按实际显示尺寸设置；字体仅从 Git 忽略的 `.work/local-am-fonts/assets/am-fonts/` 加入本地 APK。公开源码不附带这些字体，缺失时回退 Android 默认字体；Android 26/27 的系统 Medium 回退仍使用 sans-serif-medium。
- `ttml.mjs` 分开提取主、背景声部的注释，并只删除背景内容最外层完整且匹配的一对括号，包括跨词 span 的括号；内层标点、词时间和间隔保留。`lyrics.mjs` 按声部比较整段音译与原词，只折叠空白后去重，保留混合语言音译中的相同片段。
- `lyrics.mjs` 配对时优先匹配开始时间；同起点歧义以结束时间消歧；完全落在一个主词内的音译细分合入该词。显式分段但无时间且数量相同的音译按段序关联；缺词留空，不把后续音译前移。
- 序列无法可靠关联时保留整句音译，不按数量强行绑定带时间的不同词。只有整句文本的音译不猜测分词。Apple、QQ TTML 与 NetEase YRC/YROMA 最终都将音译存入原 `LyricToken.pronunciation`，使用原词起止时间。
- `LyricInkView` 的逐词主声部、背景声部，无论音译开关，都使用同一测量词单元排版。真实尾空白、`spaceAfter` 与独立空白 token 合入显示词的宽度，参与折行；自动中西文间隙另行保留，不伪造音频停顿。配对列取两层文字的较宽者；每层 sweep 仍取自身文字单元的宽度。能放入一物理行的连续词组一起换行，主词在上、音译在下，保留 Bidi 重排及各声部对唱端对齐。超宽 CJK 单元使用原生折行和累计 advance，整词仍只有一个原始音频 fraction；不人为拆分词时码。非 CJK 超宽单元保留横向压缩以避免截断。字体、字距及全部语言组合仍待设备验收。
- 历史验证记录（本轮遮罩整改前）：`:app:assembleDebug --offline` 构建通过。曾在 API 34 模拟器以本地 QRC/TTML 与 seekable WAV 经正常搜索/播放界面检查开关音译、逐 span 对齐、折行、seek 后背景重排、封面旋转及转场；这些旧记录不作为本轮整改的动态通过证据。未对真实在线曲目、其他 Android API 或原版账号态做逐帧 A/B。

## 逐词遮罩的字节码核查与整改

普通 JADX Java 的条件重构曾把 `e0` 的语言分支反向。关键结论以原 APK 的 Smali 为准；只读取原始样本，在 `.work/applemusic-reverse/animation-smali/` 导出取证文件，不将其打包。

- `z.e0@0x1c0`：CJK 检查为真，且 binding 是当前物理行末端文本时，才创建结束后 500ms 的线性 rush。它不是“非 CJK 行末一律 rush”；BOTTOM 音译调用明确传 false。
- `z.d0`：保留 CJK、配对列扩展、Flexbox 父项及词组长度的原分支，不再将所有物理行末的 extension 强制补为 1。`c0` 给当前收尾 span 置 `m=true`；`l != null && !m` 表示该组的先前成员，不是“除首字以外”。
- `z.W@0xbc`：新词开始前，前词已启动的普通动画执行 `end()`；带 lift/rush tag 的独立动画豁免。原生实现据此在构建时封存普通 sweep 的起点和终点，绘制帧不再用上一词的本帧进度重新计算新词起点。
- `FullWidthAlphaGradientFlexboxLayout.w/B/z` 与 `z.e0@0x218`：物理行有独立 mask；非 CJK/音译进入下一物理行时会完成之前物理行的 mask。本地实现记录此交接事件，不另造“空格倒计时”或“上一行跟着下一词慢慢补白”的规则。
- 本地适配边界：词级事件仍映射到现有音频时钟及 100ms 提前量；暂停冻结，seek 从新时间重算，不依赖旧帧历史。真实声部结束后（CJK 末行等待其 rush 完成）固定已完成 mask，不在行内停顿或软换行时全行补白；行整体的强调与退亮独立由已接受呈现组交接，不再保留到离屏才退出。这不是声称已还原原版 JNI 调度或完整退出状态机。
- 本轮检查过 API 34 模拟器内原版 7.0.0/1614 的实际界面：New 报网络错误，曲库无可播放内容，仅显示 Try it Now。没有登录、开通订阅或注入伪造歌词。现有 iOS 录屏能观察到折行和末字最终完成，但录屏秒数不是歌曲时码；空格恰落在折行处的逐帧 A/B、原版账号态暂停/seek 仍未动态验证。

本轮代码使用便携 Temurin 17 与 Gradle 8.11.1 执行 `:app:assembleDebug --offline` 已通过；没有安装本轮新 APK 进行移植版播放验收。不新增或运行自动化测试、断言、fixture 服务；编译结果不代替真实曲目的视觉验收。


## 与原版的逐项差异

详见仓库根目录的 [Apple Music 播放页逐项差异清单](../../../../../APPLE_MUSIC_PARITY.md)。清单覆盖封面页、歌词页、待播队列、控制区、面板、迷你入口及状态边界，每项列出原版证据、当前实现、原因和“未对齐 / 项目边界 / 待核对 / 已改待用户验收”状态；不把框架不同当作自动保留的例外。

当前已按源证据实施歌曲头、布局、图形、控制区、静态歌词边距/间距、作者归属和低动态判断等修正；尚未完成的是项目能力取舍、缺少运行证据的分支，以及全屏幕/语言/字体的设备验收。已有字号、曲线和相同矢量路径不能推出实际布局、触控与所有状态相同。

背景声部与主行共用已接受呈现组：新增成员激活，退出成员在同一交接轮次退亮并启动背景退出，交集不重启动画；无下一组或长间奏时保持当前组，作者项不清组。长按通过 `onShareLyrics(List<LyricShareLine>, Int)` 将完整可分享歌词与被点行索引交给 NowPlaying，移除旧 5 行限制和原生 AlertDialog；用户停止本轮对齐后，已撤回尚未确认的连续范围与 149 字符限制，恢复此前批准的独立多选、无字符上限，计数包含发送文本中的换行。空选、取消和关闭不发送；分享只列设备真实目标，不模拟 Apple 专属目的地。

实机验收全部由用户亲自完成，不新增、修改或运行测试与断言。

创作者区使用原版简体中文前缀“创作者：”，前缀含全角冒号一并加粗；文字为 22sp / 固定 alpha 115/255（约 45%）。中文前缀与基础透明度分别核对原版 7.0.0 / 1614 的 `values-zh-rCN/strings.xml`、`lyrics_line_writers_credits.xml` 和 `player_vibrant_secondary_alpha=0.45`。按用户要求移除随播放进度变化的整行淡入，播放、暂停及 seek 均不改变创作者文字自身透明度；原有视口边缘渐隐不变。归属文字为 15sp；仅当歌词源真实报告自动生成标志时显示说明及原 18×20dp sparkles，未报告时不推定自动翻译/音译。静态正文按 16dp 水平边距、40dp 项间装饰和独立 body 底距布局。低动态判断读取 transition/window animation scale；两者都为 0 时结束在途歌词动画。

播放器进度区使用原生 SeekBar、零尺寸透明 thumb、源状态 drawable 与 2000ms 双段缓冲动画，不再另写 Canvas 拖动；按源整秒范围映射到实际播放时间。无损与 Atmos 仅在真实格式元数据支持时显示；没有人声衰减能力时不创建 karaoke 徽标。



## Parser ownership

The TTML parser and provider adapter are maintained in this project. Do not overwrite them from a Fantastic checkout. Parsing and rendering remain separate: provider payloads are converted to `LyricSong` in the WebView, and the Android RecyclerView/Canvas displays the result.

根据用户要求，项目测试源码、夹具、测试专用依赖、运行脚本和 CI 测试任务已全部删除；不再新增或运行自动化测试与断言。CI 仅构建 APK 并发布 nightly，后续实机验收由用户亲自完成。没有覆盖的原版账号态、视频封面、所有屏幕尺寸及无障碍/RTL 场景，均标为未核对，不作为已一致的结论。

当前实现修订：音频行状态与视觉激活集合分开，背景声部展开和主行强调共用视觉状态。歌词点击和同曲跳转复用现有位移曲线，从当前屏幕位置重定向；相同目标的回执也不走首次载入的瞬移分支。歌词/队列按钮不再套玻璃圆盘；位移复位和恢复起点在同一布局阶段完成。以上为实现说明，实机效果由用户验收。

本次行时序改动及后续编译修复使用 `:app:assembleDebug --offline --console=plain`（Temurin 17.0.21.1+2、Gradle 8.11.1、SDK 35）。首轮失败于 `TransportControls.kt` 引用不存在的 MediaRoute2Info.TYPE_BUILTIN_EARPIECE；按用户要求删除无效条件、保留公开 TYPE_BUILTIN_SPEAKER 过滤后，最新结果为 **BUILD SUCCESSFUL in 14s**，产物为 `app/build/outputs/apk/debug/app-debug.apk`。未安装或启动验收；未新增、修改或运行测试、断言、fixture、lint。预移/退场录屏、真实重叠与跨段落词尾、暂停/seek/回跟随、长间奏、低动态及背景声部播放验收均未执行。完整证据边界见根目录差异清单的“歌词行激活、退场与预移时序”。

后续底部遮罩/操作超时修正：`:app:assembleDebug --offline --console=plain` 返回 **BUILD SUCCESSFUL in 3s**，最新 APK 已覆盖安装并打开首页。模拟器无法解析现有 Fantastic 服务域名，未取得真实可播放歌词；双向渐变、滑动/点按后 3 秒优先级与中途反向仍未验收。没有新增或运行自动化测试、断言、fixture 或 lint；本轮证据及并行 TransportControls 语法修复记录见根清单“底部歌词渐入与操作超时规则”。

本轮对齐已按用户要求暂停，未继续移植下拉、视频、协作或其他待确认能力；回退范围、保留项及验证结果统一记录在根目录 `APPLE_MUSIC_PARITY.md` 的“会话暂停与回退清单”。其他会话已完成的时序、底部遮罩和触控规则不回退。

