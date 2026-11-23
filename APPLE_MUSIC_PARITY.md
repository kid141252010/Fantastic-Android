# Apple Music 播放页逐项差异清单

**清单包含已实施修正、仍需决定的项目边界和未完成的设备验收；“已改”不等于已与原版像素级一致。**

对照对象：用户提供的 Apple Music Android **7.0.0 / 1614**，样本记录及 SHA-256 见[歌词渲染器说明](app/src/main/assets/lyrics/README.md#apk-来源与曲线)。本次范围为当前待办的**封面播放页、歌词页、待播队列、播放控件、相关操作面板及迷你播放器入口**。其他首页、搜索、发现、账号、资料库页面没有在本次完成全应用逐页对照，不能从本表推断它们相同。

本轮已恢复对齐，继续核对原版证据并补齐中断处的播放器图标接线；此前暂停与回退记录保留为历史，不代表本轮仍处于暂停状态。仍不新增、修改或运行自动化测试与断言，实机验收由用户完成；编译通过不等于视觉或播放一致。证据不足、宿主结构取舍及上游能力缺失统一列入文末待决定项。

## 结论如何阅读

- **未对齐**：代码或 XML 已给出不同的具体实现/取值；这是未完成的移植，不是默认允许保留的差异。
- **项目边界**：音源、账号或服务端能力不同，需要保留项目功能边界；不因此豁免外观和交互还原。
- **待核对**：当前实现已明确，但尚未取得原版对应运行状态或分支的完整证据；不猜数值、不写成相同，也不写成必然不同。
- **已改，待用户验收**：实现已经调整，未以本轮自动检查或实机观察证明体验相同。

XML 中存在一个控件，只能证明该样本包含该布局能力，不能证明所有账号、地区、曲目和屏幕都显示它。框架、字体或播放器不同，也不能直接推出某个视觉细节“无法实现”。

## 项目边界与需求冲突

| 编号 | 项目边界 / 冲突 | 当前处理原则 | 是否可据此保留视觉差异 |
| --- | --- | --- | --- |
| C01 | Fantastic 同时支持用户服务器、QQ / Apple / NetEase；原版以 Apple 的账号和服务链路为基础 | 保留多音源、服务器配置及实际能力限制；音轨元数据以来源上报为准 | 否。不能据此更改歌词显隐、排版、图标、动画或菜单材质。 |
| C02 | 之前要求通用 MD3 液态玻璃，后来要求每个细节以原版为准 | 以较新的原版还原要求为准；通用玻璃面板属于尚未对齐项，不是已经批准的例外 | 否。歌词/队列图标外额外添加的玻璃圆盘已移除。 |
| C03 项目边界已确认 | 当前未接入与原版等价的声部衰减、协作、SharePlay、推荐队列等能力 | 按用户决定保留真实能力缺口和禁用/不显示状态，不伪造数据 | 批准保留功能边界；其他视觉与交互仍以原版为准。 |
| C04 项目边界已确认：字体分发 | 忽略目录 `.work/local-am-fonts/assets` 提供可选 Apple 字体；公开仓库不含字体文件，系统 Android 字体为 fallback | 用户明确批准本地可选、公开构建系统回退的策略，不提交字体二进制 | 两种构建的字形差异继续明确记录，不宣称字体完全一致。 |
| C05 | 用户要求所有实机验收亲自完成，并删除全部测试 | 已删除项目测试及其依赖、脚本和 CI 测试任务；不再新增或运行自动化测试与断言，未实测项明确标识 | 不把“未验收”写成“已一致”。 |

## 来源索引

下文使用这些代号定位证据；项目以符号名定位，避免行号随编辑失效。`R` 为 `.work/applemusic-reverse/applemusic/jadx/resources/res/`，`J` 为 `.work/applemusic-reverse/applemusic/jadx/sources/`。这些反编译文件是本地参考，不进入应用打包。

| 代号 | 项目文件 / 参考文件 |
| --- | --- |
| P1 | [NowPlaying.kt](app/src/main/java/io/github/kid141252010/fantastic/ui/player/NowPlaying.kt)、[PlayerPanes.kt](app/src/main/java/io/github/kid141252010/fantastic/ui/player/PlayerPanes.kt)、[PlayerChrome.kt](app/src/main/java/io/github/kid141252010/fantastic/ui/player/PlayerChrome.kt)、[TransportControls.kt](app/src/main/java/io/github/kid141252010/fantastic/ui/player/TransportControls.kt)、[QueuePane.kt](app/src/main/java/io/github/kid141252010/fantastic/ui/player/QueuePane.kt)：播放器编排、各 pane、歌曲头和控制区 |
| P2 | [PlayerGlyph.kt](app/src/main/java/io/github/kid141252010/fantastic/ui/player/PlayerGlyph.kt)：`PlayerGlyph`、`glyphPath` |
| P3 | [LyricInkView.kt](app/src/main/java/io/github/kid141252010/fantastic/lyrics/LyricInkView.kt)：`shouldShowBackground`、`build`、`addPairedVoice`、`onDraw`、`paintBand` |
| P4 | [NativeLyricsView.kt](app/src/main/java/io/github/kid141252010/fantastic/lyrics/NativeLyricsView.kt)：`updatePosition`、`updateInk`、`moveTo`、`onLayout`、`makeRows`、`shareFrom` |
| P5 | [LyricMotion.kt](app/src/main/java/io/github/kid141252010/fantastic/lyrics/LyricMotion.kt)、[LyricSong.kt](app/src/main/java/io/github/kid141252010/fantastic/lyrics/LyricSong.kt) |
| P6 | [GlassSheet.kt](app/src/main/java/io/github/kid141252010/fantastic/ui/GlassSheet.kt)、[Design.kt](app/src/main/java/io/github/kid141252010/fantastic/ui/Design.kt)：`Glass`、`Motion`、`sharedArtwork` |
| P7 | [MiniPlayer.kt](app/src/main/java/io/github/kid141252010/fantastic/ui/player/MiniPlayer.kt)、[AppShell.kt](app/src/main/java/io/github/kid141252010/fantastic/ui/AppShell.kt) |
| P8 | [LyricsController.kt](app/src/main/java/io/github/kid141252010/fantastic/lyrics/LyricsController.kt)：`createView`、`load`、`sync` |
| A1 | `R/layout/music_player.xml`、`R/layout-w600dp/music_player.xml` |
| A2 | `R/layout/fragment_player_lyrics_sheet.xml` |
| A3 | `R/layout/constraint_player_media_controls.xml`、`R/layout/player_progress_seek_bar.xml` |
| A4 | `R/layout/fragment_player_queue_sheet.xml`、`R/layout/new_fragment_player_queue_view.xml`、`R/layout/layout_player_buttons.xml` |
| A5 | `R/values/dimens.xml`、`R/values/styles.xml`、`R/values/colors.xml`、`R/values/integers.xml` |
| A6 | `R/layout/lyrics_line_karaoke.xml`、`R/layout/lyrics_body.xml`、`R/layout/lyrics_line_writers_credits.xml` |
| A7 | `R/layout/fragment_share_lyrics_action_sheet.xml` |
| A8 | `R/drawable/selector_nowplaying_lyrics.xml`、`selector_nowplaying_queue.xml` 及它们引用的 `ic_nowplaying_*` |
| A9 | `J/com/apple/android/music/player/C4022z.java`：`U`、`W`、`e0`、`j0`、词/行绑定；`J/com/apple/android/music/player/U0.java`：`V`、`X` |
| A10 | `J/com/apple/android/music/player/fragment/PlayerLyricsViewFragment.java`：`W1`、长按分享；`J/com/apple/android/music/common/views/AlphaGradientFrameLayout.java` |
| A11 | `R/layout/fragment_player_main.xml`、`J/com/apple/android/music/player/LyricsBackgroundLayerView.java`：`setArtwork`、`setReducedEffects`、`setViewLifecycleOwner` |
| A12 | `R/layout/mini_player.xml`；尺寸仍由 A5 解析 |

## 封面页、标题区和背景

| 编号 / 状态 | 原版依据 | 当前项目 | 原因及可见影响 |
| --- | --- | --- | --- |
| V01 已改待用户验收：封面页主标题 | A1 / A5：`Title2Medium`，20sp | P1 歌曲头改为原生 `MetadataRail`，标题 20sp / 500 | 本地可选字体、长标题与设备字号下的栅格仍待核对。 |
| V02 已改待用户验收：封面页副标题 | A1 / A5：20sp `Title2`、secondary alpha 0.45 | P1 艺人为 20sp / 400、`#73FFFFFF` | 不再使用 16sp / Medium / 0.60 的独立样式。 |
| V03 已改待用户验收：歌词页标题 | A2 / A5：17sp `BodyEmphasized` | P1 小封面旁标题为 17sp / 500 | 与队列当前曲目头复用，待实际长标题核对。 |
| V04 已改待用户验收：歌词页艺人 | A2：17sp `Body`、alpha 0.45 | P1 艺人为 17sp / 400、`#73FFFFFF` | 不再使用 14sp Medium / alpha 0.60。 |
| V05 已改待用户验收：跑马灯参数 | A1 / A2 的 `CustomTextView`、单次 marquee 和配对重启 | P1 使用原生 TextView、`marqueeRepeatLimit=1`；歌曲头成对启动，边缘等待与重启使用 3000ms；隐藏/移除时停止 | 不再依赖无限重复的 `basicMarquee()`；原字体测量及真实溢出文本仍需设备验收。 |
| V06 已改待用户验收：歌词标题区右侧空间 | A2：右 padding 16dp，图标原 padding 与 intrinsic size | P1 header 左/右 32/16dp，喜爱/更多按源 28dp 图形与各自 padding 布局 | 不再把两个入口都强制成 48dp。 |
| V07 已改待用户验收：歌词小封面描边 | A2 / A5：60dp、5dp 圆角、0.5dp stroke | P1 `PlayerArtwork` 使用对应尺寸、圆角和亮/暗描边色 | 实际图像边缘与阴影栅格仍待核对。 |
| V08 已改待用户验收：封面与 metadata 的纵向关系 | A1：方形 artwork、metadata barrier −44dp、横向 24dp guide | P1 `CoverPane` 按实际 metadata 测量高度求解方形与 barrier，控制区保留 0.345 高度比例 | 不再固定 metadata 为 60dp；不同窗口比例仍须设备核对。 |
| V09 已改待用户验收：大屏布局 | A1 w600dp：metadata / controls 约束到 artwork_container | P1 宽度达到 600dp 后，metadata 和 controls 使用求得的方形宽度并居中 | 大屏、分屏、较大字体没有完成视觉验收。 |
| V10 部分对齐、人工验收待做：视频封面与全屏 | A1 的 `TextureView` / `enter_full_screen`；目录 `editorialVideo.motionDetailSquare` 等返回方形 HLS | P1 对 Apple 曲目按真实 album `editorialVideo` 查找 HLS，HTTPS 且限定 Apple mvod 主机；Media3 只解码视频轨，静音循环，首帧淡入静态 artwork，全屏复用同一播放器 / 时间轴并保留系统播放控件 | 不解析音频或 DRM 视频地址；Fallback 为静态封面。桌面媒体页仍走既有 Fantastic WebView 路由。机型编解码、真实 HLS、视频切曲和全屏返回尚未实机验收。 |
| V11 项目边界已确认：emoji / 协作信息 | A1 含 emoji、collaborator pill 和头像控件；显示取决于真实账号态 | Fantastic 无协作成员 / 反应数据，不显示空壳控件 | 用户批准保留协作能力差异；其它播放器细节继续以原版为准。 |
| V12 已改待用户验收：动态背景 | A11 `LyricsBackgroundLayerView` 使用三层封面旋转（120/90/70秒）、饱和度 2.5、资源 scrim 及 radius-25 低分辨率 blur | P1 已由 `AppleArtworkBackdrop` 渲染三层旋转、scrim、低分辨率模糊及 1 秒换图 crossfade；API 31+ 使用 RenderEffect，API 26–30 使用 separable Gaussian blur | 运动与换图现在已接入；系统 blur raster 和全部设备的色彩/性能未逐帧对照，仍交用户验收。 |
| V13 已改待用户验收：空歌词状态 | A2：无歌词文字 34sp、alpha 0.45 | P1 空状态为 34sp Bold / secondary；加载使用原生 ProgressBar，错误单独显示真实来源信息 | 不再把无歌词、加载和错误都压成 15sp 提示。 |

## 播放控制区

| 编号 / 状态 | 原版依据 | 当前项目 | 原因及可见影响 |
| --- | --- | --- | --- |
| T01 已改待用户验收：按钮水平约束 | A3：播放键居中，切歌/歌词/队列与它保持 40dp distance | P1 按播放目标半宽、40dp 距离和邻键半宽定位；切歌保持源绝对左右约束，底部入口按 start/end 布局 | 不再使用页面 25% / 50% / 75% 近似。 |
| T02 已改待用户验收：点击区与图形尺寸 | A3：intrinsic + 11dp / 16dp padding | P1 播放目标 60×60dp / 图形 38×38dp；切歌目标 62×56dp / 图形 40×34dp；歌词/队列 60dp / 图形 28dp | 采用源尺寸和 padding，不套用 Material IconButton 的默认目标。 |
| T03 已改待用户验收：底部留白 | A3：`action_button_space_margin_bottom=14dp` | P1 底部按钮行按 14dp bottom margin 布局 | 系统安全区和全部导航模式尚待验收。 |
| T04 歌词/队列选中态已验收 | A8：歌词与队列的 selector 均按 `state_selected` 切换图标 | P1 使用 Fantastic 的 28dp Material 图标。歌词从轮廓切换到实心，队列从音符切换到勾选清单，当前面板以亮白显示。P2 保留其他播放器矢量 | API 34 已核对封面、歌词、队列互切，Android 无障碍节点的 `checked` 与当前面板一致。 |
| T05 随机/循环角标已验收 | A3：20dp 底盘与 16dp 图形的四边相互约束，角标位于队列入口顶端/end | P1 在同一圆底内居中图形，随机优先，随机和循环均关闭时隐藏。P2 在缓存中校正三个角标路径的中心，随机保留 RTL 镜像 | API 34 原始截图测得全部循环和单曲循环的横纵偏差为 (0, −0.5) px，随机为 (0, 0) px。返回封面时保留随机角标。 |
| T06 已改待用户验收：进度条组件 | `MinimalSeekBar`、`minimal_seekbar_progress`、双段 2000ms indeterminate 动画与四条 pathInterpolator | P1 改为原生 SeekBar，7dp 轨道、上下 5dp padding、0×0 thumb、不镜像 RTL；使用源状态 drawable 与双段缓冲动画，进度按源整秒范围映射 | 移除单段 1050ms Canvas 近似和自定义拖动检测；点按、拖动、取消、键盘和读屏均仍需用户设备验收。 |
| T07 已改待用户验收：时间标注 | A3：12sp `Caption1Emphasized`、等宽数字、上下 10dp margin | P1 12sp Medium、`tnum` 数字与上下 10dp 留白，拖动/禁用使用源 alpha 分支 | 极端字号与 TextView/Compose 字体测量差异尚未验收。 |
| T08 已改待用户验收：音质徽标与详情 | A3：无损图形+11sp 文字；Atmos 83×15dp 单独图形；karaoke 分支 | P1 只依据真实上报规格显示无损 / Atmos；质量入口只显示真实 `audioDetails` 和当前来源档位，提供“打开设置”去切换，不在播放页重复候选列表 | 未报告时明确显示无规格数据；不从偏好反推正在播放的码流；不伪装 Apple 候选。规格仍待真实播放验收。 |
| T09 已改待用户验收：直播电台进度区 | A3：`player_live_radio_seek_bar` | P1 根据真实 `isLive` 显示 LIVE、两侧 7dp 渐变和顶部 10dp 留白，不显示普通时间轴 | 是否能 seek 仍由音源真实能力决定；未取得真实直播曲目验收。 |
| T10 项目边界已确认 | A3：Cast 输出按钮与设备名 | P1 使用当前系统 output switcher 及设备名，不伪称 Apple Cast 会话 | 用户批准保留完整 Cast 服务链路差异；系统输出入口不是完整 Apple Cast 发现/专属面板。 |
| T11 项目边界已确认：SharePlay | A3 包含参与人数与 SharePlay badge | Fantastic 当前没有协同会话及参与状态 | 用户批准不显示假的 badge / 会话状态，保留能力差异。 |

## 歌词渲染与触控

| 编号 / 状态 | 原版依据 | 当前项目 | 原因及可见影响 |
| --- | --- | --- | --- |
| L01 已修正待用户验收：逐文字块缩放轴 | A9 `U0` 行绑定的同一回调为主词、翻译、音译设置底边 Y pivot，并按主词方向设置左/右边缘 X pivot；不能仅凭 `U0.V`、`X.O` / `U.W` 未覆写 pivot 推断为默认中心 | P3 各歌词 Block 构建时按主词方向保存自身左下/右下锚点；逐词单元复用物理行 rightAligned，右边缘计入 scaleX。创作者/归属文字保留中心，backingPivotX/Y 不变 | 翻译与音译不按自身字符串方向另选缩放侧；不改布局、词扫亮或动画参数。左/右对齐、RTL/duet 与上移激活的实际像素仍待设备核对。 |
| L02 已改待用户验收：非逐词主行 alpha | A9 `U0.V` 用 tertiary→primary alpha；进入延迟/时长 250/250ms，退出 250/350ms，曲线 `(.39,.575,.565,1)` | P3 `LyricInkView` 按播放行状态重建 alpha，并使用同样延迟、时长与曲线；不是直接赋值 0/1 | 动效参数已移植到 Canvas；不同字体 fallback 下的 alpha raster 尚未做原版逐帧 A/B。 |
| L03 已核对现有控件，待用户验收：歌词遮罩 | A10 `W1` / `k` 的顶部 7%、底部 27.75% 加 controls inset；`AlphaGradientFrameLayout.d` 将左右普通渐变宽度置 0，`V.onLayoutChange` 则把 end mask 的矩形和圆角绑定到 `VocalAttenuationControl` | P4 在同一 500ms 过渡中连续插值原版底部两套颜色与 stops；项目尚无人声衰减控件，未添加其避让遮罩，也不擅自添加整页左右边缘渐变 | 原清单仅凭 XML 四方向声明将其判作整页横向渐隐缺失，依据不充分；人声控件和其局部 end mask 一并归入 L13。原版全部状态、真实曲目逐帧视觉仍未验收。 |
| L04 已改待用户验收：歌词与底控的覆盖和显隐 | A2 列表区域延伸到父底部，controls 与 0.425 tap-target 区域覆盖其上；A10 `W1` / `k` 逐帧更新底部遮罩，`AbstractC3935d.N1` / `Q1` 同组驱动控件与遮罩 | `LyricsPlayerPane` 用同一 Transition 驱动控件、注释入口及底部 mask 进度。滑动/点按会重置待执行的 3 秒无操作计时；滑动隐藏后即使暂停，超时仍转为下半区首个短点按优先唤出控件 | 按用户最新要求不再永久保留滑动隐藏的歌词优先；0.425 边界与 500ms 显隐时长不变。最新 APK 已编译、安装并打开首页；模拟器 DNS 失败，真实歌词双向渐变、超时和中途反向仍未验收。 |
| L05 已改待用户验收：静态歌词水平边距 | A6 `lyrics_body`：左右 16dp | P3 静态正文使用 16dp；同步行和作者区仍保持各自源布局 | 不再把所有 timing 都固定为 32dp。 |
| L06 已改待用户验收：静态行间装饰方式 | A10：静态 item decoration 40dp；A6：legacy body 的独立底距 | P4 静态首项顶部 30dp、项间 40dp，P3 保留静态 body 底距 11dp 和自己的行高/译文间距 | 结构参数分别采用，不把 decoration 与 body 底距合并成经验行距；真实折行待核对。 |
| L07 已整改待用户验收：词单元折行 | A9 的 `c0/b0` 把真实空白纳入词 View 测量，Flexbox 按子项/非断行 wrapper 建物理行 | P3 逐词主/背景声部统一使用测量词单元；真实空白参与宽度与折行，自动间距不改词时间；可容纳词组保持同一行，超宽 CJK 采用原生折行与累计 advance | 不再用无音译整句 Layout 与配对列两套计时几何。原版完整条件性分片 View 树、具体设备尾空白像素、复杂混排仍需运行对照，不宣称全输入像素一致。 |
| L08 待用户核对：RTL 扫亮 | A9 文字方向选择 mask，duet 改变布局/对齐而不直接翻转渐变；`e0` 会更新前序物理行 mask | P3 物理行保存独立前沿，主词/音译各用其文字方向与 metadata；在整行坐标内夹羽化边缘，再映射到局部 cell | 主从方向混排、阿拉伯/Indic 连写及对唱组合仍需设备核对；只核实了静态调用链，未完成原版账号态逐帧 A/B。 |
| L09 已改待用户验收：逐词音译排版与扫亮 | A9 按 pronunciation getBegin 配对；BOTTOM 使用自己的词模型和 View 宽度，并明确关闭 CJK rush | P3 主词/音译共用词时间和测量列，分别保存物理行 mask；音译不继承主词的 CJK/grouped/词长，不独立上浮、长音或 rush；保留 tracking 与逐词组底部 8dp | 字体来自可选本地 AM 资产，缺失时回退系统；字形、复杂混排与真实曲库尚未进行原版逐帧 A/B，不宣称原版字体 raster 一致。 |
| L10 已改待用户验收：创作者/归属文字 | A6：22sp / alpha 0.45；原版 `values-zh-rCN/strings.xml` 的 `writers_credits_prefix` 为粗体“创作者：”；A9：自动翻译/音译使用 `ic_sparkles`；disclaimer 为 15sp | P4 使用真实作者和自动生成标志；前缀改为“创作者：”并保留前缀粗体。按用户要求移除随播放进度变化的整行淡入，P3 创作者文字始终使用固定 alpha 115/255（约 45%），不随播放、暂停或 seek 改变；18×20dp sparkles、有作者时归属上距 22sp、原有视口边缘遮罩不变 | 不为普通人工歌词捏造自动生成声明；固定透明度是用户明确要求，不将其写成原版全部父层动画的还原。实际歌词页效果仍待用户验收。 |
| L11 保留已批准分享，未确认的原版限制已回退 | A7、源分享选择模型 | P4 传出完整可分享歌词及被点行位置；P1 恢复独立多选、不设字符上限，计数含发送文本的换行；空选禁止发送，只列设备真实分享目标 | 本轮撤回未经最终确认的连续范围、149 字符限制及超限替换确认，不再把这套临时规则作为已完成对齐。 |
| L12 已改待用户验收：翻译/音译入口 | A2 translations selector；17sp menu、2.5dp elevation、10dp / black-80% popup | P1 使用源翻译入口与四种显示/隐藏菜单矢量，保留 17sp、16dp 水平/14dp 垂直 padding、0.5dp divider 和锚定 popup | 不再使用 Material Translate 图标和通用 GlassSheet；全部可用性组合与 RTL popup 位置仍待用户核对。 |
| L13 项目边界已确认：人声衰减 | A2 有 `VocalAttenuationControl` 和拖动指引 | Fantastic 当前无对应音频处理能力 | 用户批准保留此功能缺口；不显示无效控件。其 end mask 是该控件局部效果，已见 L03。 |
| L14 已改待验收：主行交接与预移 | A10 `PlayerLyricsViewFragment.f/d2/onCreate` 的 -500ms offset 与逐词 480–750ms D；A9 `C4022z.P` / `U0.P` 的集合差、子集保留与 `M.b(first,last)`；`z.j0` / `U0.V` 的退出延迟 | P4 保留独立段落 cue，按准备点和段落重叠形成候选组，仅在新增 ID 时提交；首行定位、尾行 D/stagger，特定不连续组只跟随尾行。P3 在对应位移开始帧提交退场，缩放 50/350ms、alpha 250/350ms | 已移除固定 previous.end + 300ms 和离屏退亮；背景候选按可核验本地状态保守裁剪，原版词 ID 分支及 JNI 调度不冒充等价。修复 TransportControls.kt 无效路由常量后构建通过，真实曲目预移、重叠与状态中断尚未验收。 |
| L15 已改待用户核对：字体 fallback、连写与 glow | A9 原词视图使用 SF Pro、脚本 fallback 字体、wght/opsz 轴及阴影 | 本地 APK 从忽略目录加载 AM 字体；主词、音译、译文、原生歌曲信息和 Compose 主题已接入。Android 29+ 使用配套脚本 fallback，缺失字体时保持 Android 默认；公开 Git 不含字体二进制 | 本地与公开构建走不同字体资产状态；所有 API、连写、缩放及 glow 的实机 raster 对照尚未完成。 |
| L16 已改待用户验收：低动态模式 | A9 `U0.X()`：transition/window animation scale | P4 读取同一两个系统值，二者都为 0 时关闭歌词动画；附着及播放同步时更新并完成在途位移 | 不再用 `ValueAnimator.areAnimatorsEnabled()` 代替这组条件；系统设置实际切换仍待设备验收。 |
| L17 已移除自建入口，自动跟随待核对 | A10 包含独立手势/布局/动画控制器 | 按用户要求删除 P1 的“回到当前歌词”文字按钮及其专用状态、回调和命令；P4 保留 browsing、touch、active-visible 的既有自动跟随判断与滚动条行为 | 没有另加自动返回计时器或替代入口；原版每个可见性条件、fling 结束处理和恢复时机仍未逐分支动态验收。 |
| L18 已改待用户验收：背景声部展开/收起 | A9 DEX `C4022z.j0`：高度 0 时以 `D=f33320e`、`(.4,.1,0,1)` 运行进入动画，结束时恢复 `WRAP_CONTENT`；背景 Flexbox alpha 0 / scale .9，延迟 `round(.85D)` 后 500ms 到 alpha/scale 1；普通退出分支 scale 到 .9/500ms、alpha 到 0/250ms / `(.39,.575,.565,1)` | P3 在所属行视觉激活时立即预留背景声部高度；P4 以 D 和原 stagger 移开邻行，布局及位移动画结束后才放行 P3 的显现。背景正文、逐词 mask、音译和翻译使用同一合成图层的 alpha 0→1，与 scale .9→1 同步运行 500ms，不再让逐词 Paint 的不透明白覆盖淡入；退出完成后收起 | 按本次要求，显现还受实际布局完成屏障约束，不只等待 `.85D`。本次整体淡入修复未做实机或原版逐帧 A/B。DEX 中 `type=2` 有跳过退出动画的分支；项目没有对应模式开关，未伪造该选项。 |
| L19 已改待用户验收：封面/歌词与 player 过渡 | A10：普通 fade 500ms / `(.25,.1,.25,1)`；shared artwork 300ms / `(.2,.06,0,1)` | P1 整页和 cover/lyrics/queue 均采用源 500ms fade，封面继续共享 300ms bounds | 当前不保留之前独立的整页底部滑入/滑出参数；真实曲目与中途反向切换没有完成视觉验收。 |
| L20 已改待用户验收：翻译样式与显隐 | A6/A9 的逐词译文为主 20sp Bold、背景 14sp Bold，C4022z.l 的最后一段译文遮罩固定 resting alpha 18%；lyrics_line 的译文无 alpha 绑定；静态 lyrics_body 为 15sp Medium、次级标签色与 4dp 间距 | P3 分别使用逐词 18%、逐句白色 100%、静态 #99ebebf5（60%）；补齐 20/14/15sp 对应 tracking，保留 10/2/4dp 上间距；不再把 45% 次级 alpha 套给所有译文 | 依据 XML、资源表及 fallback 字节码核对；本地可选字体与公开库系统回退均保留；折行和所有混排方向仍需用户设备对照。 |
| L21 已修正待用户验收：歌词行 seek/分享边界 | 原版 7.0.0 / 1614 `lyrics_line_karaoke.xml` 根左右 margin 为 `lyrics_line_margin_horiz=20dp`，点击绑定在行容器 | P3 只按 DOWN 起点限制中间命中带，UP/CANCEL 后恢复非触摸许可；P4 的既有 seek/分享监听都检查 rowActivationAllowed。带外取消延迟 tap/long-press、清 pressed；foreground hotspot 同带；极窄 item 回退全宽 | 不改 item 全宽、正文 32dp inset 或折行，不截断 RecyclerView 的边缘起拖。两侧 19/21dp、RTL、ripple、TalkBack 与键盘的真实设备验收尚未完成。 |

## 待播队列与面板

| 编号 / 状态 | 原版依据 | 当前项目 | 原因及可见影响 |
| --- | --- | --- | --- |
| Q01 已改待验收：当前曲目头 | A4 current_player_item / layout_currently_playing_header | 复用真实当前歌曲头、60dp 封面、17sp metadata、喜爱与更多入口 | 原版账号态与全部屏幕仍待对照。 |
| Q02 已改待验收：队列控件 | A4 `layout_player_buttons` / `layout_player_button`、`C5980h8.l`：38dp、32dp 边距、12dp 间距；8% 白底、选中 45% 白色掏空层、关闭态白色与禁用态 8% 白色图形 | 随机/循环位于真实队列上方；`QueueModeButton` 已接通 `QueueModeGlyph`，选中层只清除自身图形并透出父层底色，保留真实状态、服务回调和无障碍 selected/disabled | 不再漏掉整宽胶囊底盘或用 badge 轮廓代替队列图形；全部状态的设备栅格与点击仍待用户验收。 |
| Q03 项目边界已确认：自动播放 | A4 有 `button_autoplay` | 当前会话不提供真实推荐队列，仍显示已禁用状态并说明原因 | 用户批准保留真实能力差异，不伪造推荐 / 播放状态。 |
| Q04 已改待验收：分组 | A4 sticky_header_container | 固定“接下来播放”头，使用引擎实际 upNext 顺序；无历史数据不伪造历史组 | 队列编辑、换曲与循环模式待真实播放核对。 |
| Q05 已改待验收：队列渐隐 | A4；NewPlayerQueueViewFragment.W1 的 16dp bottom fade | 独立 DstIn 底部渐隐包含 controls overlap | 栅格、不同窗口大小待对照。 |
| Q06 已改待验收：队列行/编辑 | small_list_d_upnext_item 与实际会话能力 | 60dp 行、48dp 封面、17/13sp 文字；仅真实命令可用时允许选择、删除和非随机重排 | 不把静态实现或编译通过当作端到端播放验收。 |
| M01 已改待验收：共享面板 | action_sheet.xml、ActionSheetDialogFragment 及 tablet 分支 | 复用 GlassSheet：手机底部贴边、平板 400dp 居中，实体头与动作行使用源字级/间距 | 歌词分享单独使用 10dp 卡片、sw600dp 520×600dp；不复制 APK 二进制资源。 |
| M02 已改待验收：材质与动效 | layer1 亮/暗色；mtrl_bottom_sheet_slide_in/out；share_lyrics_sheet_fade_in | 去掉通用玻璃 blur/border/shadow；普通面板进入 250ms、退出 200ms；歌词分享进入 50ms、dim 0.4 | 使用原生 Dialog 窗口动画，全部 Android 版本与导航模式待用户核对。 |
| M03 项目边界已确认：多音源音质 | 原版按 Apple 流 / 账号能力呈现，A3 仅直接证明音频徽标分支 | 播放页显示当前来源真实档位及音轨规格，音质切换留在设置内各来源自己的实际选项 | 用户批准保留差异；不复制未核实的 Apple 面板行序或反馈。 |
| M04 已改待验收：实体动作与条件 | d1.G0、C3469t.h/b/d/f/g/a、EnumC3475z | 真 ID 的艺人→专辑选择；单目标直达。媒体动作按实体类型和本地资料库状态分支，分享/收藏/本地歌单/播放/音质保留；未接入动作禁用并说明原因 | 不推断队列歌单来源，不按名称搜目录，不冒充 Apple 订阅、云同步、推荐或离线能力。 |

## 状态、入口和需要用户核对的边界

| 编号 / 状态 | 当前实现 / 对照情况 | 原因及处理 |
| --- | --- | --- |
| U01 已改待用户验收：播放器展开/关闭与 pane | A10：普通转场 fade 500ms，封面 bounds 单独 300ms；P1 整页与 cover/lyrics/queue 已按该组参数连接 | 撤去额外整页 slide 后，不把过往 400/250ms 说明当作当前实现；若保留旧交互例外需在最后明确决定。 |
| U02 已恢复原版下拉释放与收起判据，待设备验收 | Apple Music 7.0.0 / 1614 的 `BottomSheetBehavior.b.j`、`O` 和 `H2.d` | 依据源 `VelocityTracker` 阈值清零 / 限速；向上松手回 expanded。向下松手按 `O` 的 `.1` hide friction / 半个 `A()` 行程判定，再按横纵速度、500ms `significantVelocity` 和 expanded/collapsed/hidden 位置分支；非 hidden 的 collapsed 完成后回到 mini。settling 使用源 `(t−1)^5+1` 与 `H2.d.f/i` 距离/速度时长，不使用弹簧。列表滚动仍先消费，触顶后交给 sheet | 恢复原版短拖回展开、下拉收回两类状态；在 Compose 宿主上实现等价判据，嵌套事件与所有设备分支仍待实机确认。 |
| U03 已改待用户验收：封面/歌词共享过渡 | A10 `PlayerMainFragment.q1` 注册共享过渡，`p156ha.f` 设置封面 bounds 300ms / `F.f50852g=(.2,.06,0,1)`。P1 整页开合仅当前 pane 使用外层可见性作用域；页内切换保留各自的 pane 作用域。三个封面端点仍共用 `ARTWORK_KEY` 与 300ms `ArtworkBounds` | 本轮未在设备上验证封面移动是否连续，或是否出现重叠、闪回。 |
| U04 已改，符合用户指定的 Missing 策略 | P7 持久保存 Cover/Lyrics/Queue 与翻译/发音偏好；Fantastic 歌词状态与 mediaId 分别提供 StateFlow | 只在当前 mediaId 的歌词状态 Missing 时，从 Lyrics 返回 Cover；不自动记忆或重返歌词。Loading、Failed、None 不回退 | 已按用户确认限制回退条件，不把网络失败或解析失败当作无歌词；首屏默认仍不臆测。 |
| U05 已改待用户验收：迷你播放器封面与按钮 | A12：小封面 30dp / 5dp、文字前距 8dp、专用 32dp drawable；P7 已对应，标题/艺人 13sp Medium / 12sp，播放/暂停按真实 playWhenReady / ended / error 状态 | mini 与 full 均不在缓冲时把仍可暂停的会话误画成播放键；边缘与真实播放待核对。 |
| U05a 已改兼项目边界：迷你播放器大屏/字号约束 | A12 / A5：maxWidth 600dp、maxFontScale 1.5；P7 已加入这两项限制 | 没有 SharePlay 会话数据时不创建专属文字；协作能力统一留待决策。 |
| U06 待用户核对：缓冲、失败与重试反馈 | P1 歌词加载为原生 ProgressBar、进度缓冲为源双段动画；错误仍显示 Fantastic/Media3 的真实信息，mini 可显示播放失败 | 不将 Apple 特有错误文案套给不匹配的服务端；实际断网、恢复和重试尚未设备验收。 |
| U07 待核对：RTL、语言、字号与无障碍 | A1 / A2 / A3 显式使用 textDirection、textAlignment、rotation、selected state、contentDescription；项目混用 Compose 和原生 View | 当前未逐组合确认焦点顺序、读屏文案、字体缩放、触控目标和 RTL 布局；代码中有 semantics 不等于体验相同。 |
| U08 待核对：屏幕与系统窗口 | 原版包含布局资源变体；项目由 Compose 的 status/navigation padding 和本地玻璃面板控制窗口 | 横屏、折叠/分屏、三键/手势导航、API 26–30 fallback、深浅色需分别由用户核对，不把手机默认状态当成全部。 |

## 已采用的源参数，不作为剩余差异的豁免

以下是静态参数层面的采用情况，**不是已完成实机一致性验收**：

- controls 高度比例 0.345；大封面圆角 10dp、描边 0.5dp、播放/暂停阴影 15/8dp；歌词小封面 60dp / 5dp；barrier、600dp 宽屏和原按钮 padding 已在 V06–V09 / T01–T03 分别记录，仍不等于各屏幕已完成验收。
- 主/背景字 34/22sp；翻译 20/14sp、上距 10/2dp；静态主字/翻译 20/15sp、译文上距 4dp；当前音译主/背景参数 20/14sp，逐行 padding 8dp。
- 行 scale 0.98→1；进入延迟 150ms / 500ms、退出延迟 50ms / 350ms，曲线 `(.4,.1,0,1)`；歌词块以各自实际底边为 Y pivot，X 按主词方向取各块左/右边缘，逐词横向压缩计入右边缘。创作者/归属文字保留原中心轴，独立背景声部动画仍保留自己的底边 pivot。非逐词 alpha 已按 U0.V 配置 250ms 延迟及 250/350ms enter/exit；与源控件 raster 的差异仍未做逐帧 A/B。
- 逐词渐变羽化 30dp、主/背景已唱 alpha 0.94/0.35、未唱 alpha 0.18；抬升 −2dp；长音强调、尾迹和间奏完整采样曲线见[参数来源表](app/src/main/assets/lyrics/README.md#apk-来源与曲线)。参数相同不覆盖 L07–L09、L14–L17 的布局/事件差异。

## 已修改但仍交由用户验收的项目

1. **换行来回闪动**：P4 不再在旧布局正在绘制时先把 translationY 归零；保存可见位置，在同一 `onLayout` 阶段重置并恢复起点，再沿源曲线移动。这是实现修订，不宣称用户报告已被实机证明消失。
2. **背景声部**：P3 跟随所属行进入已接受呈现组立即展开占位，不额外等待音频 begin；P4 在布局及邻行位移动画结束后才允许背景词淡入，避免展开过程中与下一行重叠。退出组的行在同轮交接启动退亮与背景退出，交集不重新启动；词时间只控制扫亮，本行 end 或背景末词结束不单独清组。静态歌词仍按静态内容显示。本次组交接与既有展开/显现效果均待用户实机验收。
3. **歌词/队列及相关图标**：本轮在独立 API 34 模拟器 `emulator-5570` 用 QQ 音乐 aespa《Whiplash》完成播放器底栏验收。MediaSession 到达 `PLAYING`，歌词加载 83 行，随后暂停以固定截图时刻。确认封面 → 歌词 → 队列 → 封面、封面 → 歌词 → 封面，以及循环关闭 → 全部循环 → 单曲循环 → 关闭。原始截图及无障碍快照见 `.work/player-footer-{cover,lyrics,queue,repeat-all,repeat-one,shuffle,cover-shuffle}.{png,xml}`。JDK 17、SDK 35、Gradle 8.11.1 离线执行 `:app:assembleDebug --rerun-tasks` 成功，Kotlin 编译实际执行。`app/build/outputs/apk/debug/app-debug.apk` 为 29,921,926 字节，SHA-256 `dc0534d31243159698b5b26170bba4ff65c14e5d0d4657a52a0eed252edee5ae`。
4. **歌词点击跳转**：同曲跳转直接同步音频时间，但不再把列表位置瞬移到目标；复用现有行位移曲线、duration 和 stagger 从当前可见位置过渡。同目标的播放回执也沿用该分支；首次载入/切歌保留直接定位。实机动画由用户验收。

## 本清单完成与未完成的边界

完成的是：对当前待办播放页各主要可见区域、触控入口、菜单和状态进行静态比对，记录了可定位的差异、原因及未核对项。没有以实现理由把未对齐项自动批准为例外。

未完成的是：上述移植缺口本身的全部修复、所有原版登录态/内容态下的运行行为还原、逐帧像素对照，以及全应用其他页面的穷尽审查。这些不能标为已经与 Apple Music 一致；后续实机结论由用户给出，本清单随结论更新。

## 2026-11-21 详情与子菜单实施记录

- `Track` 新增 artistId / albumId 默认空串；Apple 使用真实 relationships，QQ/NetEase 使用 mid；父专辑列表保留父 albumId。Media3 extras 和资料库嵌套 track JSON 读写两字段，快照版本仍为 1；旧数据缺字段兼容为空。
- `AppShell` 接收 NowPlaying 的同音源、kind/ID 跳转并关闭播放器。Apple station/music-video/post 仍经现有受限 WebView 路由；origin/path/资源类型校验不变。
- 原生目录页采用集合封面 72dp 横边距、10dp 顶距/圆角、15dp 标题上距、20sp 标题/艺人；艺人页按方形头图、32sp 标题及关于区组织；曲目、关联导航、分页、错误和真实数据加载继续沿用原接口。
- 资料库管理与设置面板只统一外观，不混入 Apple 媒体动作。分享目标仅为设备实际安装应用和系统 chooser。
- 迷你封面左边距固定为内容边缘 20dp + 内部 12dp = 32dp；并行播放器改动中的右侧 16dp 外/内边距、封面尺寸与共享元素逻辑保持不变。
- 最终构建：使用 Temurin JDK 17.0.21.1+2、Gradle 8.11.1 和本地 SDK 35 执行 `:app:assembleDebug --offline --console=plain`，结果 `BUILD SUCCESSFUL in 18s`；输出 `app/build/outputs/apk/debug/app-debug.apk`。并行播放器/歌词改动曾产生回调类型、未完成函数和局部作用域编译错误，修复后最终构建通过；没有新增、修改或运行自动化测试、断言、lint 或 fixture。
- 设备检查：初始 adb 没有连接设备；启动 API 34 模拟器后看到原版 Apple Music 的网络错误页，随后该进程以 1 退出。另启只读 5560 实例并看到 Android 桌面，但在新 APK 安装前该实例以 0 退出；本轮没有完成新 APK 安装、启动或页面/播放验收。主机真实 Fantastic `/status` 可达，不代表模拟器或播放链路通过。
- 尚未验收：同分辨率/字体下的全部目录详情与 WebView 承载页、关联返回链/分页、Track/Quality/Lyrics/实体菜单、队列编辑与关闭、歌词实际发送、资料库和设置动作、迷你封面 32dp 实测、12,299→12,300ms 交接及坏 end/seek/手动浏览。未取得这些设备证据，不能宣称像素级或端到端一致。

## 搜索、目录详情与资料库的 Material 3 外观

- 资料库以 Apple Music Android 7.0.0 / 1614 为依据。`LibrarySections` 给出歌单、艺人、专辑、歌曲和下载入口，`LibraryQueryHelper.getRecentlyAddedOrDownloadedQueryParams` 按最近加入时间降序查询专辑与集合。收藏筛选和歌单里的收藏歌曲入口也有 [Apple 官方说明](https://support.apple.com/en-us/111118)。
- 资料库使用 Material 3 大顶栏、分类列表和封面网格。已加入的目录专辑、目录歌单和本地歌单进入最近添加。有真实来源和专辑 ID 的已存歌曲按专辑归组，缺少 ID 的旧记录仍可从歌曲分类打开。文件夹归入歌单，音乐视频入口随本机保存内容出现。下载入口与本地 JSON 导入导出仍可使用。
- 搜索采用 Material 3 搜索输入框、分段来源选择、分类标签与媒体列表。歌曲、艺人、专辑、目录歌单、音乐视频和本地歌单详情统一顶栏、排版与操作按钮。Apple 视频沿用现有 WebView 内容，在原生承载层使用 Material 3 顶栏。
- JDK 17 / Gradle 8.11.1 / SDK 35 执行 `:app:assembleDebug --offline --console=plain --no-daemon` 成功，`compileDebugKotlin` 实际执行。最终安装包副本 `.work/md3-ui/fantastic-md3-debug.apk` 的 SHA-256 为 `2d9082c9d1e2469c10eca59002e66473a5a22633e6560fead84afa286059eea1`。
- 在隔离的 API 34 模拟器上打开真实搜索结果、类别页、歌曲、专辑、关联艺人及目录歌单。检查了 QQ 音乐视频详情、Apple 视频原生顶栏、资料库明暗模式、专辑网格、下载入口、收藏筛选和本地歌单添加曲目。最终包在无默认网络时仍能从资料库封面菜单打开已收藏专辑的本地操作。截图在 `.work/md3-ui/`。设备验收范围为界面和导航，音视频实际播放、下载和真机效果由用户验收。

## 启动闪退修复与验证

- 用户报告打开即退出后，在 API 34 模拟器安装实际调试 APK 并取得 AndroidRuntime 堆栈：`Unable to create service PlaybackService`，由 `FantasticSessionPlayer.nativeQueueMetadata()` 的 `Bundle(playlistMetadata.extras)` 触发 `NullPointerException: Attempt to do a synchronize operation on a null object`。启动时元数据为 `MediaMetadata.EMPTY`，extras 可以为 null，与服务器或是否已有歌曲无关。
- 根因修复只改该方法：extras 非空时复制；为空时新建 Bundle，再写入真实队列遍历顺序。不吞异常、不清除用户资料、不改播放队列或来源行为。
- 验证：JDK 17 / Gradle 8.11.1 的 `:app:assembleDebug --offline --console=plain` 构建通过（17s）；覆盖安装成功。启动后截图可见应用首页，activity 为 resumed，进程与 PlaybackService 均存活，启动后的 crash buffer 为空。
- 该记录只证明本次启动故障已在 API 34 上修复，不把首页打开当作歌曲播放、歌词及所有详情/菜单的完整验收。未新增或运行自动化测试、断言或 fixture。

## 歌词页控件显隐与底部渐隐修正

- 首轮修正（已被后文“底部歌词渐入与操作超时规则”替代）：对照样本为 Apple Music Android 7.0.0 / 1614。`PlayerLyricsViewFragment.W1` 确认顶部 7%、底部 27.75% 加控件覆盖高度及原 stops；其内部 `k.onAnimationUpdate` 另有颜色/stops 插值。该轮只修正可见性关联，当时尚未接入原版的渐变状态与动画。
- 首轮 `LyricsPlayerPane` 传递布尔 controlsVisible，隐藏时直接去掉底部渐隐中的 inset。这正是后续用户指出歌词缺少渐入的路径；当前已改为固定范围并传连续进度，不再沿用该布尔切换方案。词时钟、行 alpha、独立动画和点击区域未因底部遮罩插值而修改。
- 构建：Temurin JDK 17.0.21.1+2、Gradle 8.11.1、SDK 35，`:app:assembleDebug --offline --console=plain` 返回 `BUILD SUCCESSFUL in 5s`，生成 `app/build/outputs/apk/debug/app-debug.apk`。未新增或运行自动化测试、断言或 lint。
- 安装与启动：随后执行 `:app:installDebug --offline --console=plain` 时，工作区的 `LyricShareSheet.kt:115–116` 报 `Unresolved reference 'dark'`，安装任务未完成；未修改该无关文件。改用 `adb -s emulator-5554 install -r` 安装上一步成功产出的 APK，返回 `Success`；`am start -W` 返回 `Status: ok`，首页截图及 resumed activity 证明已启动。
- 视觉验收边界：在该模拟器通过现有配置搜索真实曲目时仍显示加载，设备无法解析已配置服务器（`ping: unknown host`）。未取得可播放的长篇同步歌词；控件显示、自动隐藏 3 秒并退出 500ms 后、点按重新显示，以及手动浏览后隐藏这四种场景均未完成视觉验收。构建和首页启动不作为渐隐效果已通过的证据。

## 全屏歌词按隐藏来源区分触控优先级

- `LyricsPlayerPane` 使用 `Shown`、`HiddenByScroll`、`HiddenByTimeout` 三态。列表进入 `SCROLL_STATE_DRAGGING` 时隐藏控件并记录滑动来源；操作结束无进一步操作满 3 秒后，无论是否仍在播放，滑动隐藏也转入超时隐藏。触摸按下或 popup 展开会取消待执行计时，释放/关闭后重新计时；控件显示时仍仅播放中自动隐藏，加载/错误及触摸探索状态继续强制显示。
- 滑动隐藏后尚未超时：不设置独占防挡区，歌词行照常接收点按、长按和滚动；下半区点按可同时唤出控件，不禁止该次歌词行操作。无后续操作满 3 秒则转入下一条的独占唤出规则；不因隐藏来源曾是用户滑动或点按而永久豁免。
- 自动隐藏：仅此状态启用下半区独占唤出。`NativeLyricsView` 从 `ACTION_DOWN` 开始接管该区域的手势，短点按只显示控件，不把按下交给歌词行，避免行点击异步投递后误跳转，也不触发行长按分享。拖动仍由 RecyclerView 处理，并转入滑动隐藏模式。
- 区域起点仍沿用原版资源的 0.425 指南线，控件显隐动画为 500ms；底部遮罩已在后续修正中接入连续过渡。滑动隐藏转超时只改变触控优先级，不把仍隐藏的控件再闪现或重播淡出。已撤回的歌词行触控高度/宽度调整未重新加入；隐藏来源与 3 秒后恢复优先级是用户明确交互规则，不冒充原版账号态完整行为。
- 本轮验证：Temurin JDK 17、Gradle 8.11.1、SDK 35，`:app:assembleDebug --offline --console=plain` 返回 `BUILD SUCCESSFUL in 3s`。没有新增或运行自动化测试、断言或 lint；本轮未重新安装 APK 或完成真实歌词触控验收，前述模拟器服务器无法解析的限制未在本轮重新测试。编译通过不代表两种全屏路径及其视觉效果已验收。

## 歌词行激活、退场与预移时序

- 基准仍为用户样本 Apple Music Android **7.0.0 / 1614**。已核对 `PlayerLyricsViewFragment.f` 的逐词 D 与负 offset、初始化/切歌的 -500ms；`C4022z.P` / `U0.P` 的非空子集保留、集合差与不连续跟随范围；`M.b(first,last)` 的定位/错峰范围；`z.j0` Smali 的 alpha 250ms 延迟 / 350ms 时长和 scale 50ms 延迟 / 350ms 时长。原版 JNI 的 `SongInfoTimeProcessor.processEvents_` 未核实，不引入固定 p.end + 300ms 或上邻行 end 单独退出事件；此前固定 300ms 行交接的实现记录不再作为当前规则。
- `ttml.mjs` 每个 p 的 begin/end 原属性只读取一次，两个非空且 end > begin 时保存独立 paragraphBegin/paragraphEnd，显式 0 有效；其余输出 null。`lyrics.mjs` 原样透传，`LyricLine` 两个可空字段默认 null。原 begin/end 合并词包络、错误桥、注释、YRC/LRC 和旧 JSON 行为不改。规范依据为 [Apple TTML Timing](https://help.apple.com/itc/videoaudioassetguide/en.lproj/itcd7579a252.html#itcd1f730b22)。
- `NativeLyricsView.makeRows` 只为时序按 cueBegin / lyricIndex 排序，显示和稳定 ID 仍按文档顺序。逐词 D 复用 lineDuration(next.cueBegin - cueEnd)，末行传 0；首组及逐句 lead 为 500ms，其后逐词组取前组最后一行 D，同起点共用 lead，准备点限制不早于 0。这是把已知 offset 应用于本地行时码，不是原版 JNI 回调时点的测量结论。
- `emphasizedLyricIds` 是唯一已提交组。以最新准备行 cueBegin 查询同起点及覆盖该点的已准备行，candidateLyricIds 只在查询点变化/重建时计算；非空子集保持旧组，有新增才统一交接，不因末词、行结束、离屏、回收或拖动清除。跟随使用新组首行，组尾提供 D/stagger；仅相交、不连续且尾索引前进时改为首尾均跟随尾行，连续性不受 adapter 间奏行影响。逐词背景候选保留候选与旧组交集加最新准备行，不伪造原版绑定背景行/词 ID 的等价状态。
- 首次、切歌及明确同曲跳转会重建组和查询点；普通播放回执不重建。sync 保留 30ms reanchor 阈值，仅首次锚定/discontinuity 同时重置 lastDispatched；updatePosition 按原公式外推，再与已派发时间取最大值，普通回调倒退不清组或退扫亮。长间奏保留原入列窗与准备时刻，仅切入时清组，并记住查询点；下方真实行已准备后不再抢回间奏。作者项仍按原 end + 1000ms 定位且不作为额外结束事件；无下一组或长间奏保持组。触摸/fling 与手动浏览保留原有逻辑；独立“回到当前歌词”按钮已按后续用户要求移除。
- `LyricInkView.setLinePresentation` 接收批量 uptime；原始词 position 始终更新，等待自动位移布局或本行 stagger 时仅暂缓退场。onLayout 保留 pendingMovePosition 至 super 返回，只在实际位移子行设置 lineMoveNotBefore；完整绑定、取消、resize、finishMoves 清旧值。退出保留各自延迟/时长/曲线；无位移或手动浏览不等待，低动态 snap。背景占位与显现屏障不变，退出结束后继续收高；moveEndAt 不作为主行退场条件。
- 静态核验：旧 handoff、activeLyricId、preparedLyricId、rebuildEmphasis、attachedLyricIds 已从 P4 删除；解析字段、空值默认与调用者时间参数已接通。首次 `:app:assembleDebug --offline --console=plain` 构建曾因 `TransportControls.kt:388:66` 的 `Unresolved reference 'TYPE_BUILTIN_EARPIECE'` 失败（2s），该次没有安装旧 APK 代替验证。
- 后续按用户要求修复编译：SDK 35 android.jar 的 javap 输出及 [Android 官方 MediaRoute2Info API](https://developer.android.com/reference/android/media/MediaRoute2Info) 均没有 TYPE_BUILTIN_EARPIECE。`rememberPlatformRouteName` 删除该无效条件，仅保留公开的 TYPE_BUILTIN_SPEAKER 过滤；不借用其他类型的常量、不硬编码数字，路由监听不变。Temurin 17.0.21.1+2、Gradle 8.11.1、SDK 35 下运行相同构建命令，返回 **BUILD SUCCESSFUL in 14s**；产物为 `app/build/outputs/apk/debug/app-debug.apk`。仍有现有 API 弃用警告，没有编译错误。
- 上轮设备检查当时 adb 仅列 `emulator-5554`。本次编译修复未覆盖安装或启动，也没有新增、修改或运行自动化测试、断言、fixture 或 lint。真实 QQ DRIP 候选匹配与预移/退出帧、三行重叠和跨 p end 词尾、暂停/恢复、前后 seek、手动浏览/回跟随、切歌、长间奏、低动态和背景退出均**未验收**；构建通过和静态核验不作为视觉通过证据。

## 底部歌词渐入与操作超时规则

- 用户报告的根因是两条不同路径：控件 alpha 已用 500ms 动画，而原生歌词收到 controlsVisible 后立即重建另一高度的遮罩，没有中间帧。原版 Android 7.0.0 / 1614 的 `PlayerLyricsViewFragment.W1` 固定遮罩范围，内部 `k.onAnimationUpdate` 同时插值颜色与 stops；`Q1` 与 `AbstractC3935d.N1` 将它和控件放进同一 500ms AnimatorSet。
- 底部范围保持 `round(.2775 * height) + controlsInset`。显示态颜色为 `#ff000000/#0d000000/#00000000/#00000000`，stops 为 `0, .24/(.2775+inset/height), .2775/(.2775+inset/height), 1`；隐藏态为 `#ff000000/#99000000/#4d000000/#00000000` 与 `0, .3, .6, 1`。颜色资源及数值已从该样本核对，不再通过“隐藏时去掉 inset”突然揭开底部歌词。
- P1 采用同一 Compose Transition 驱动控件、注释入口和底部 mask；控件保留源加速进入/减速退出，mask 使用原 ValueAnimator 的默认 AccelerateDecelerateInterpolator（见 [Android ValueAnimator](https://developer.android.com/reference/android/animation/ValueAnimator)）。P4 接收连续 Float 进度并插值黑色 alpha 与 stops；中途反向从当前呈现值继续。每帧只更新 mask，不重设 padding、不滚动选中行；色值/stops 数组与手势回调复用，不重复重建顶部 shader 或扫描分享可用性。
- 用户新增规则：操作结束后 3 秒无后续操作，HiddenByScroll 也转为 HiddenByTimeout，包括暂停时已经滑动隐藏的情况。下半区首个短点按仅唤出控件，不跳词或触发行长按；3 秒前保持手动浏览的正常歌词操作。隐藏来源切换不改 visible 目标，因此不会重播显隐。未改变 0.425 点击边界、歌词时钟、组交接、词 mask 或已撤回的行点击区域尺寸。
- 构建首轮撞到共享工作区 `TransportControls.kt:260–264` 的重复残缺 PlayerGlyph 调用及缺失闭合括号，产生一系列局部函数/未解析符号错误；保留完整的 16dp 调用、移除残缺副本并补齐闭合后，Temurin 17 / Gradle 8.11.1 / SDK 35 的 `:app:assembleDebug --offline --console=plain` 返回 **BUILD SUCCESSFUL in 3s**。未新增、修改或运行自动化测试、断言、fixture 或 lint。
- `adb -s emulator-5554 install -r` 返回 Success，启动新 APK 后截图可见首页；通知权限弹窗仅用返回键关闭，没有授予权限。已配置服务器为用户现有 Fantastic，设备解析其域名返回 `ping: unknown host`，未更换服务器或注入假曲目。本轮实际歌词页的双向渐变、滑动/点按后 3 秒优先级、长按/拖动及中途反向仍**未验收**；首页截图和构建通过不算该效果的动态证明。

## 删除额外的回跟随按钮

- 按用户明确要求移除歌词页“回到当前歌词”文字按钮，不以其他图标或手势入口替代。同步移除 LyricsPlayerPane 的 follow 参数、NowPlaying 的状态订阅、LyricsController 的 followAvailable / follow 与 NativeLyricsView 的专用回调和命令，避免只隐藏界面却继续维护无用状态。
- 保留 isActiveVisible 在自动跟随中的用途；原先同时维护按钮与滚动条的函数只留下滚动条逻辑，命名为 updateScrollBarVisibility。手动浏览、fling、歌词点按跳转、既有自动跟随条件、500ms 遮罩过渡及 3 秒后控件优先规则均不改变，不新增自动回位计时器。
- 验证：Temurin 17 / Gradle 8.11.1 / SDK 35 下执行 `:app:assembleDebug --offline --console=plain`，结果 **BUILD SUCCESSFUL in 2s**，产物 `app/build/outputs/apk/debug/app-debug.apk`。代码中旧入口及专用连线已清除。未新增或运行测试、断言、fixture 或 lint；本轮未重新安装或做真实歌词页设备验收，上一轮设备服务器 DNS 失败的限制未在本轮重测。

## 会话暂停与回退清单

- 按用户“停下、整理改动、回退未完成部分”的要求暂停整个对齐任务，停止后台歌词参考调查；不再继续补功能或追加原版核对。
- 回退前快照：`.work/stopped-parity-20261121-224035.tar`，保存当时播放器、原生歌词、相关资源、偏好及两份说明。没有执行整仓 reset/restore，没有提交或推送。
- **保留的本会话实现**：歌曲头字号/颜色/原生跑马灯，封面布局及 600dp 分支，播放器与 mini 图形/尺寸，原生 SeekBar 和双段缓冲资源，时间标注、真实音质/直播状态，队列与共享面板、翻译/发音 popup，静态歌词边距/间距、作者归属及低动态判断，pane 与注释偏好持久化。均为代码已落盘，不表示实机一致性验收通过。
- **撤回的未收口修改**：歌词分享恢复此前批准的独立多选、无字符上限；删除连续范围、149 字符阈值、超限行状态及替换确认。图标撤回未完成的逐分支内联，恢复复用既有 `lyricsPath` / `queuePath` / `shufflePath` / `repeatPath` / `autoplayPath`，保留已完成的源路径和图层绘制。
- **未动的其他会话改动**：最新段落 cue/呈现组交接、控件与底部遮罩连续过渡、3 秒触控优先级规则、独立回跟随按钮删除、启动闪退修复、详情/关联导航与已有多音源能力。不能将 Git 中所有未提交文件视为本会话产物。
- **停止时尚未完成**：原版下拉/速度/嵌套滚动，状态保存的完整生命周期，横向渐隐及全部歌词状态，动态封面视频与播放视频交接，协作/emoji、SharePlay、人声衰减、完整 Cast/输出面板，音质说明面板与字体分发取舍；均未用虚假状态补齐。
- **验收边界**：真实播放、歌词/队列交互、分享实际发送、RTL/读屏、横屏/平板/分屏和逐帧 A/B 仍待用户验收。既有模拟器服务器 DNS 失败记录保留，本次不重测、不换服务器、不注入假曲目。

 - **本次回退后构建**：Gradle 输出 `:app:assembleDebug --offline --console=plain` 的 **BUILD SUCCESSFUL in 18s**（Temurin JDK 17.0.21.1+2、Gradle 8.11.1、SDK 35）；但外层命令包装在 300s 时限结束时报告 timeout。因此可确认 Gradle 已打印构建成功，不能确认外层命令正常退出。日志为 `.work/tmp/stopped-parity-build.log`，APK 为 `app/build/outputs/apk/debug/app-debug.apk`。未新增或运行测试、断言、lint，未安装、启动、实际分享或做页面/播放验收。任务保持暂停。

## Apple 歌词请求预算与播放启动诊断

- 本次范围仅为已批准的请求、就绪等待与诊断修正；不恢复此前暂停的界面对齐工作。歌词行为基准为 [Apple 安卓歌词支持页](https://support.apple.com/en-us/109355)（2025-09-17）：同步歌词可用时自动显示，无同步歌词时可能显示全文，无歌词与未联网不能混为一种结果。本次不修改排版、字体、动画或解析器。
- `AppleCatalogApi` 只创建并复用一个歌词派生客户端，read timeout 取现有 call timeout；当前为 read/call 30 秒、connect 10 秒，复用原连接池与 dispatcher。目录继续使用原客户端，QQ、网易和音频请求预算不变；不增加重试。保留请求取消、响应关闭、ID/标签校验、404→null 和成功空正文报错，HTTP 成功后的解析期限仍为 15 秒。预算依据是本地参考服务端 wrapper 的单次 15 秒请求及主备顺序转发，并非已取得生产部署版本或权限。
- `AppleCatalogHttpException` 保留状态码，并只从 JSON 对象提取非空 `msg` 首行、trim 后最多 320 字符；HTML、非对象或无有效消息时只保留 HTTP 状态。歌词与目录最终非成功响应共用该处理，目录已有重试及 UI 状态码分类不变，不输出完整正文或请求 URL。
- `LyricsController` 使用单调时钟记录“请求／解析／完成”、请求与解析耗时、行数及正文字符数。null 正文跳过解析并记 0ms；普通失败明确标注请求或解析阶段，解析超时仍显示“歌词解析超时”。设置页原有诊断与 `FantasticLyrics` Logcat 输出完全相同的字符串，切歌取消仍向上传递，不写入旧曲失败状态。
- 隐藏播放 WebView 在 `loadUrl` 后立即启动现有唯一轮询器，不再等整页完成才识别 `window.AmApp.player`；`onPageFinished` 只补采样。保留同源检查、20 秒就绪期限及 100/250ms 等待／状态周期。真正就绪超时先销毁页面，下次播放重新创建并计时；暂停或切歌取消等待不销毁正常可复用页面。首页主文档 HTTP 错误报告实际状态并销毁页面，子资源错误不一律终止播放器。
- `FantasticApple` 仅在启动阶段变化时记录 `request=<序号> stage=<Page|Ready|Resolve|Prepare|Playing|Failed> elapsedMs=<累计毫秒> mediaId=<媒体ID>`，可附实际 mode 或最多 320 字符的已有错误；每个页面另记单次 `event=page-finished`。私有请求归属保护避免暂停、停止或旧回调借用新请求计时，Playing 后普通缓冲不再输出启动阶段。日志不包含队列 JSON、歌词、完整 URL、模板、内容密钥或账号。
- Resolve 仅表示网页来源解析；Prepare 包含后续 CDN、模板、解密、解码与缓冲，不能称为单独的“解密耗时”。批准计划中的既有宿主机证据为歌词 HTTP 200 耗时 21,686ms，以及 `/parse/song/1649434293` 在约 19,961ms 才返回首响应；后者发生在 CDN 取流与 Worker 解密之前。这些是先前测量，不是本轮新 APK 的验收结果；本地修改不宣称消除上游约 20 秒等待，不改服务器、DRM、音质偏好或 Worker/WASM。

- 本轮构建：Temurin JDK 17.0.21.1+2、Gradle 8.11.1、SDK 35，执行 `:app:assembleDebug --offline --console=plain`，结果 **BUILD SUCCESSFUL in 8s**，外层命令正常退出；产物为 `app/build/outputs/apk/debug/app-debug.apk`。未新增、修改或运行自动化测试、断言、fixture 或 lint。
- 本轮设备前提：`adb devices -l` 仅列出在线的 `emulator-5554`；只读执行 `ping -c 1 -W 2 music.1412.tech` 返回 **ping: unknown host music.1412.tech**（退出码 2）。按批准计划未安装或启动新 APK，未修改 DNS、服务器或设备设置，也未使用旧日志、宿主机 GET 或替代歌词冒充验收。因此慢响应歌词 Loading→Ready、原生歌词可见、诊断与日志一致性、切歌取消、首声与进度同步、实际 mode 和阶段耗时均**未验收**；自然页面超时后的重新连接分支**未覆盖**。没有新 APK 的 Resolve 实测，先前约 20 秒上游等待仍是独立剩余问题，不能归因为本地解密。

## Apple 源加入 AMLL 词库

- 按用户新增要求，Apple 来源按歌曲 ID 获取 AMLL TTML，并已按后续“并行请求”要求改为同时启动 AMLL 与 Apple 官方请求，结果仍优先选用 AMLL。AMLL 有效后直接显示并取消尚未完成的官方请求；未收录、请求失败或歌词内容解析无效时使用已并行发起的官方结果，不再重发。一条网络请求失败不取消另一条，切歌取消同时取消两路；解析器自身的 15 秒超时仍单独报告。
- `AppleCatalogApi.amllSongTtml` 使用 [AMLL 原生接口](https://amll.dev/reference/http-api/native.html) 的 `appleMusicId` 参数，地址从当前 Fantastic `/assets/host.js` 的 `amllApi` 读取并按服务器地址缓存；仅缺省字段时采用网页端公共默认地址，不覆盖部署的自定义词库。`LyricsController` 记录实际来源、回退原因、当前尝试耗时及全程总耗时。QQ／网易选择逻辑、字体、动画与音频解密均未改。
- 接入词库时的服务契约核对：当时部署的 `host.js` 发布了自定义 AMLL 地址；该词库按 Apple ID `6803773692` 返回 HTTP 200 与《晴天娃娃》的真实 Word TTML。此前用于慢响应核对的 `1649434293` 在该词库返回 HTTP 404，不能声称每首 Apple 歌曲均已被 AMLL 收录。以上是先前宿主机真实接口证据，不是本次并行请求的端到端验收。
- AMLL 串行接入初版的验证：`:app:assembleDebug --offline --console=plain` 返回 **BUILD SUCCESSFUL in 4s**，命令正常退出，产物 `app/build/outputs/apk/debug/app-debug.apk`。未新增或运行测试、断言、fixture、lint。当时指定 `emulator-5554` 在线，但网络前提检查在 **30 秒超时**，未取得新的域名解析或 HTTP 结果；按此前批准的联网前提未安装该 APK、未修改网络或服务器配置。该构建不能作为后续并行修正已通过的证据。
- 并行修正的本轮验证：在两个 `async` 子任务都创建后才等待 AMLL 结果；普通请求异常封装到各自结果，不取消另一路。请求计时取子任务实际起止时刻，解析计时独立，结束或切歌时取消未完成任务。本轮唯一一次 `:app:assembleDebug --offline --console=plain` 返回 **BUILD FAILED in 1s**，失败点为本次未修改的 `ui/player/NowPlaying.kt`（`calculateBottomPadding`、`pointerInput`、手势及 `abs` 等未解析引用）与 `ui/player/PlayerGlyph.kt`（`Translations`、`mirroredSymbols` 未解析引用）。未覆盖或回滚这两个文件，未生成经本轮验证通过的并行版 APK；并行请求、回退、切歌取消与首声仍**未做设备验收**。本轮未新增或运行测试、断言、fixture、lint，也未重复此前的设备网络检查。

## 创作者行修正验证

- 中文标识与固定文字透明度的实现及依据见 L10。本次使用 Temurin JDK 17.0.21.1+2、Gradle 8.11.1、SDK 35 执行一次 `:app:assembleDebug --offline --console=plain`，结果 **BUILD FAILED in 3s**。错误来自本次未修改的 `LyricsAnnotationLauncher.kt`（`Translations`）、`NowPlaying.kt`（`calculateBottomPadding`、`pointerInput`、手势及 `abs`）、`PlayerGlyph.kt`（`Translations`、`mirroredSymbols`）和 `TransportControls.kt`（`Shuffle`、`RepeatOne`、`Repeat`）未解析引用；未覆盖这些文件或重复构建。本轮没有生成验证通过的新 APK，未安装或验收实际歌词界面，也未新增或运行测试、断言、fixture 或 lint。


## Whiplash 歌词验证

- 生产 `parseFantasticLyrics` 在 Chromium 回查原始和 AMLL TTML：各 83 行、36 行主音译、0 行背景音译；L35 为单份主词和 `Yeah`，L9 三个背景词时码不变；嵌套括号回查仅移除外层。
- 修复共享播放器源码中阻断编译的引用后，JDK 17 / Gradle 8.11.1 / SDK 35 执行 `:app:assembleDebug --offline --console=plain`：`BUILD SUCCESSFUL in 12s`。产物 `app/build/outputs/apk/debug/app-debug.apk`，SHA-256 `4621562445c14037464ff62f44c4bc9e4dbd944294bf26b9a7369209418e3e44`。
- API 34 模拟器覆盖安装并启动成功。设置重新打开显示 `zh-Hans-CN` / `zh-Latn`；含下划线的语言草稿被拒绝并显示字段错误，返回后未保存。Apple Music 搜索 `Whiplash aespa` 返回 `搜索失败：timeout`，未能播放 `apple:1772214981`；未验收实际 query、同曲刷新次数、音频连续性和 L9/L35 可见帧。未修改服务、DNS 或账号。

## 本轮恢复对齐与待决定项

- 中断处的 `PlayerGlyph` 当前已具备完整函数边界；本轮未重复覆盖其余图形。修正集中在队列模式的实际接线、底盘与透明掏空，以及 full player badge 与 queue mode 两组源矢量的分离。其他会话的歌词解析、配置及启动修复保留。
- 证据：`layout_player_button.xml` 的父背景与 `MaskingView`、`C5980h8.l` 的三态切换、`nowplaying_*` 与 `ic_nowplaying_*` 的独立 viewport / autoMirrored 定义。歌词 end mask 的性质由 `AlphaGradientFrameLayout.d/dispatchDraw` 和 `fragment/V.onLayoutChange` 核实，不以 XML 方向标志猜测渐变宽度。
- 本轮唯一一次构建：Temurin JDK 17.0.21.1+2、Gradle 8.11.1、SDK 35，`:app:assembleDebug --offline --console=plain` 返回 **BUILD SUCCESSFUL in 2s**，外层命令正常退出。产物为 `app/build/outputs/apk/debug/app-debug.apk`。未新增或运行测试、断言、fixture、lint；未安装或启动 APK，未重测既有设备网络失败。
- **用户已确认的取舍**：U02 保留 Compose 宿主与共享封面，在此基础上移植嵌套关闭规则；U04 只在当前曲目的歌词 `Missing` 时回封面、不自动返回歌词；V10 实现 Apple 目录动态封面 HLS 与同一播放器的全屏交接；M03 播放页显示当前来源码流说明、切换留在设置；C04 保留本地可选字体、公开构建系统 fallback。其余完整 Cast、SharePlay/协作、人声衰减、自动播放不实现，不伪造状态。实现边界和实机验收见下文。
- **不在本轮回退的用户已定规则**：创作者文字固定透明度、独立无字符上限的歌词多选分享、删除额外回跟随按钮，以及控件隐藏来源 / 3 秒后触控优先级。它们不因继续对齐而被默默恢复为另一套行为。
- **仅待人工验收，不冒充已通过**：逐词 / 混排 / 背景声部与换行、状态中断、真实播放 / 队列 / 分享，以及 RTL、读屏、字号、横屏 / 平板 / 分屏和系统版本组合。编译与静态源参数对应不能替代这些运行证据。

## 播放器触控区域与实体跳转

- 按已批准计划，以本地 Apple Music Android **7.0.0 / 1614** 的布局、标签点击绑定及实体菜单为入口位置与文案参考。圆形 DOWN 命中是用户本次明确要求，不把原版圆形背景当作已测量的触控几何证据。本轮未修改圆形直径、图标中心、共享封面目标或动画参数。
- `ui/Design.kt` 新增局部 `CircularTouchScope`，只将委托配置的 `minimumTouchTargetSize` 设为 `DpSize.Zero`；`CircularIconButton` 继续委托 Material3 原 `IconButton`，不关闭其布局占位。22 处标准按钮已迁移；源码检索仅剩共享包装中的一处原调用，艺人播放按钮原有 `size(36.dp)` 保留。`HeaderAction` 仅将 Star / More 的命中、反馈和选中语义收进原 28dp 圆内，外层 padding 只排版；翻译圆角方入口、队列胶囊、裸传输图标、无损及其他排除项未改。
- 移除 `CurrentTrackHeader` 整个横条的回封面点击，只给其 60dp、5dp 圆角缩略封面传 `onOpenCover`；`PlayerArtwork` 在 shadow / clip 后按非空回调添加原生 Compose clickable。页头下拉、大封面、mini、分享封面与原共享元素链路保留，不新增大封面点按切歌词。
- 封面页歌名和歌手均接入原有实体动作，歌词 / 队列 compact 仅歌手接入。只在播放器可见、当前 pane 且无覆盖面板、至少有一个有效实体 ID 时提供新动作；沿用空白 / `"0"` 排除、艺人→专辑顺序、零项不动作 / 单项直达 / 多项面板以及先清面板再打开真实 ID 详情。没有名称搜索、新请求或队列替换。原生两个标签各自在 init 安装一次监听，bind 更新动作；不可用时清空动作并关闭点击 / 键盘焦点。cover 保持列宽，compact 使用受列宽约束的 WRAP_CONTENT；主题按压背景限于标签，保留 20sp / 17sp、ANY_RTL、配对跑马灯和 3000ms 生命周期。probe 不接动作并保留清除语义。实体行保留 72dp / 40dp 图槽，改为上行“前往艺人 / 前往专辑”、下行实际实体名，按 kind 明确映射。
- **源码核对**：每个实施步骤完成后已核对工具返回的实际修改内容；圆钮调用清单和四类内部接口调用均已检索迁移。LSP 状态为 `No language servers configured for this project`，因此使用计划指定的精确检索。未回滚或接管共享工作区的其他修改。
- **本轮构建**：Temurin JDK 17.0.21.1+2、Gradle 8.11.1、SDK 35，唯一一次 `:app:assembleDebug --offline --console=plain` 返回 **BUILD SUCCESSFUL in 5s**，Gradle 退出码检查通过。日志 `.work/player-touch-targets-build.log`；APK `app/build/outputs/apk/debug/app-debug.apk`，29,791,222 字节，SHA-256 `c0e2f00ba75bf0e0b846839192b358b100c03a8e2c80ebe6fe3fd9af34430fca`。外层 PowerShell 在构建后的可选 `Get-FileHash` 步骤因 cmdlet 不存在而退出 1；随后仅用 `sha256sum` 成功获取摘要，没有重复构建。未新增或运行自动化测试、断言、fixture 或 lint。
- **界面验收前提未满足**：本轮 `adb devices -l` 只有标题、没有设备；宿主桌面工具同时报告 Windows 会话锁定 / 断开或输入桌面不可用。因此没有安装或启动本轮 APK，没有可达设备用于读取服务器 / 真实曲目，没有调整设备网络、配置或注入状态。
- **未验收清单**：各圆钮圆心 / 圆周内 / 四角 / 留白 / 圆外移入的命中和按压反馈、父行与滚动取消、禁用语义；歌词及队列缩略封面独占切页；封面歌名 / 歌手与 compact 歌手的两实体菜单、单实体直达、无 ID 禁用、短标签空白、切歌动作更新、覆盖 / 退出状态；真实来源与 ID 详情及播放队列保持；长名跑马灯、TalkBack / 键盘、下拉与歌词滚动、3 秒隐藏 / 500ms 过渡，以及竖屏 / 宽屏位置和尺寸。涉及删除、移除、重排或导入的动作均未执行。源码核对和构建通过不代表以上触控、视觉或端到端行为通过。

## 歌词触控、时钟修正与退场中心

- 该轮批准方案执行触控、时钟和退场三项局部修正，基准为用户样本 Apple Music Android **7.0.0 / 1614**。已核对 `lyrics_line_karaoke.xml` 的根左右 margin、`dimens.xml` 的 20dp 外边距和 12dp 内 padding，以及 `U0.V`、`X.O`、`U.W` 的逐 TextView scale。当时据此推断普通退场使用默认中心，遗漏了 `U0` 行绑定的共享 pivot 回调；该推断已由后文“歌词翻译按主歌词方向锚点缩放”纠正，触控、时钟及原构建/设备记录不变。
- 每项修改后均依据编辑工具返回的实际改动核对，再进入下一项；本项目无已配置 LSP。触控仅改 DOWN 命中许可与 ripple hotspot，没有改正文布局。参考 [Android 14 View 实现](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-14.0.0_r1/core/java/android/view/View.java) 的延迟 tap/PerformClick 行为，对拒绝手势调用 cancelLongPress 同时清除 prepressed/tap 回调，避免 UP 后恢复无障碍默认许可时误 seek。
- 保留 LyricsController 的 500ms 同步、NativeLyricsView 的 30ms reanchor、段落 cue 与候选组规则、100ms 词提前量、lineDuration 和 RecyclerView 位移/stagger。保留正常 scale 0.98、scale 退出 50/350ms 与 alpha 退出 250/350ms 的原曲线，以及 lineMoveNotBefore 对齐本行位移的条件。
- **本轮唯一构建**：Temurin JDK 17.0.21.1+2、Gradle 8.11.1、SDK 35，`:app:assembleDebug --offline --console=plain` 返回 **BUILD SUCCESSFUL in 2s**。日志 `.work/lyrics-touch-tracking-retirement-build.log`，产物 `app/build/outputs/apk/debug/app-debug.apk`。未新增或运行自动化测试、断言、fixture 或 lint；编译通过不作为触控、真实歌词播放或原版逐帧一致性证据。
- **实际安装与启动**：启动已有 `fantastic-port-api34` AVD；`ro.build.version.sdk=34`、`sys.boot_completed=1`。对本轮 APK 执行 `adb -s emulator-5554 install -r` 返回 **Success**，`am start -W` 返回 **Status: ok**；随后设备截图与 UI 层级显示 Fantastic 首页。`logcat -b crash -d` 无输出。APK 为 29,791,222 字节，SHA-256 `b8a8c642c7453b95cd6b6fcc7fe3e0adeb0a14e51485379d40c382ea94ae9fbb`。
- **真实曲目入口阻断**：通过正常搜索界面输入 `Whiplash`，当前选中 QQ 音乐；实际返回“歌曲搜索失败：timeout”，其他目录分类同样 timeout。没有重试、替换服务器、改设备网络、注入歌词或用 fixture 代替真实曲目。宿主 Windows 同时报锁定/不可用输入桌面，原生电脑截图不作为当前状态证据；依据该明确错误改用 ADB 读取实际 Android 截图与 UI 层级。首页证据 `.work/tmp/lyrics-touch-tracking-start.png`，失败页面 `.work/tmp/lyrics-touch-tracking-search-outcome.png`。
- **未验收**：左右两侧 19dp 拒绝 / 21dp seek 与长按分享、带外起拖/fling 和无 ripple、无障碍与键盘点击、RTL 与折行不变；连续播放跨组且跨至少两次正常同步的无闪回/无扫亮后退、显式 seek 立即回退；同设备向上退场、50ms scale 延迟与本行 stagger 重叠、落定中心无 1px 下跳，以及原版 7.0.0 / 1614 的同轨逐帧 A/B。未取得真实可播放歌词，以上不以源码核对、构建或首页启动宣称通过。


## 播放器下拉回位与明暗联动

- 原版依据仍为 Apple Music Android **7.0.0 / 1614**；本轮重新核对 `applemusic.apk` SHA-256 为 `49e83b64fccff79310c7dff174e02228dd0455a3d87df4d9655d1e4f8cd2e351`。本地 `J/H2/d.java` 的插值器为 `(t-1)^5+1`，`f/i` 按父宽、位移、平台 fling 速度阈值计算时长并限于 600ms；`BottomSheetBehavior.b/P` 使用该收束路径，不是欠阻尼弹簧。另以 JADX fallback 单类回查原始 DEX，证据 `.work/applemusic-reverse/player-pull-bytecode/sources/H2/d.java:78-87,404-451` 明确保留浮点位移 / 宽度、位移 / 行程换算，未机械照搬普通反编译中省略 cast 的整数除法表象。
- 当前实现已移除用户报告仍显著弹簧时使用的简化 return easing，恢复 Apple 原版 release→detent 选择：负向速度回 expanded；向下按 `BottomSheetBehavior.O` 及 `b.j` 分支选择 expanded / collapsed(mini) / hidden，保留 `fitToContents=true`、`hideable=true`、`.1` friction 与默认 `significantVelocity=500`；释放速度按 Android min/max fling velocity 处理。settling 精确采用 H2.d 的五次减速、双轴速度权重、距离时长公式及 600ms 上限，不是弹簧；向上再次拖动会中断 settling 并跟随手指。
- 原版 `LyricsBackgroundLayerView` 位于 `fragment_player_main.xml` 并由 cover-art LiveData 更新；`PlayerMainFragment` 进入 state 4 时调用 `c(true)` 暂停三组旋转 animator，保留已载入 artwork/shader，展开再恢复。当前 `AppShell` 在迷你播放器仍存在期间就请求封面并离屏渲染第一张低分辨率背景帧；全屏播放器直接复用已渲染帧，首张不做黑到亮的一秒淡入，后续换曲 crossfade 保留。全屏退出后暂停运动并把相位存回预热状态，重开后从已预热相位继续。
- 原有用户规则不变：创作者文字自身固定透明度、歌词页 3 秒后控件优先、歌词控件自身 500ms 过渡、圆形按钮命中、实体跳转及共享封面作用域均保留。父层随播放器拖动的淡出不改变歌词内部各行的固定 alpha 或时钟。
- 使用 Compose / 共享封面宿主而非原生 CoordinatorLayout；`NowPlaying` 仍接线 panel touch 和歌词页顶部/歌曲头专用拖动区，队列到顶仍可交给 sheet，歌词正文的 nested-scroll 不交给 sheet；歌词页通过顶部/歌曲头专用拖动区有意收起。原版 `A()` 会受窗口 inset 和资源 peek height 影响；本地对应尺寸用实际 bottom bars 的 `LocalBarsPadding` 求取。平板 flat host 的额外 `.25 ln(p)` 和 mini `exp(-300p)` 未迁移；不将局部曲线对应写成全部平台分支完整一致。
- **早期简化动效构建记录**：Gradle 输出 `BUILD SUCCESSFUL in 37s`，外层命令最终在 300s 超时；当时产物已安装，SHA-256 `16747515e35a99e815ebc57e10421b3165089cb76b9a958cb0787863f91037b5`。该版的五次回位仍被用户报告为有明显回弹，后续先误改成直接关闭；本轮已撤销该阈值变更并恢复原版判据。
- **上一轮设备记录**：`emulator-5554` 仅显示首页 / 空资料库后断开；没有该初版的真实拖拽 / 明暗逐帧验收。
- **本轮最终构建**：首次编译发现 `AppleArtworkBackdrop.kt` 的 nullable 预热对象写入未做安全调用，修正后 `:app:assembleDebug --offline --console=plain` **BUILD SUCCESSFUL in 18s**，38 项任务全部执行，外层命令正常退出。APK SHA-256 `2102c19283b3a35f5262ef68bbb13c152d49561ddee14642c6bd95850186ec08`。
- **本轮设备验收**：最终 `adb devices -l` 无设备；未安装本轮 APK，真实下拉释放阈值、展开/收起动画及背景待机相位未能实机核验。未新增或运行自动化测试、断言或 lint。

## 歌词翻译按主歌词方向锚点缩放

- 按已批准计划，重新核对同一 Apple Music Android **7.0.0 / 1614** 样本。计划中的 `U0.N` 对应本地 JADX `player/U0.java` 的行绑定方法 `l`：1026–1050 行以主词方向建立一个回调，并为主词、翻译、音译共同设置 `pivotY=height`、`pivotX=0/width`。不能从创建 scale 动画的方法没有显式设置 pivot 推断为中心。
- `LyricInkView.Block` 在构建时缓存歌词块的左下/右下锚点；普通主词、翻译及非配对音译使用同一主词方向 `(Bidi(voice.text).baseLevel == 1) xor duet`，逐词主/音译单元直接复用物理行已有 `rightAligned`。右边缘按 `x + layout.width * scaleX` 计算，Y 为各块自身 `y + layout.height`；不改词列位置、折行、压缩比例或绘制帧计算。
- 创作者/归属文字不传歌词方向，保留原中心 pivot；间奏点的独立绘制不变。背景声部独立 `backingPivotX/Y`、词扫亮、透明度、时长、曲线及行位移/stagger 均不变。左/右对齐 duet 的同步翻译、上移激活时的边缘固定及原版逐帧 A/B 仍交由用户设备验收，源码核对不能代替视觉验收。
- **本轮唯一构建**：使用 Temurin JDK **17.0.21.1+2**、Gradle **8.11.1**，确认 SDK 35 `android.jar` 存在后执行 `:app:assembleDebug --offline --console=plain`，返回 **BUILD FAILED in 16s**，外层命令退出码 **1**。日志 `.work/lyric-translation-scale-anchor-build.log`；失败任务为 `:app:compileDebugKotlin`，不能把已有 APK 作为本轮通过验证的产物。
- **构建阻断位置（本轮未修改）**：`ui/AppShell.kt:179` 的 `onPronunciationChange` 命名参数不存在，伴随 `it` 未解析；`ui/player/NowPlaying.kt:177,181` 的 `app`、`:232,236,244` 的 `returnDrag`、`:404,436` 的 `onPronunciationChange` 未解析；`ui/player/PlayerMotionArtwork.kt:122` 的 `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` 未解析。保留共享工作区这些文件，不为本次 pivot 修改扩大修复范围，未重复构建。
- **未执行的验收**：未新增或运行自动化测试、断言或 lint；本轮未安装/启动 APK，未做真实同步翻译/duet 曲目的上移激活或原版逐帧 A/B。按批准计划由用户完成设备视觉验收；当前实现及原版绑定回调的静态核对不构成视觉一致性通过。

## 本轮确认的实施决定与验证

- 用户要求以 Apple 原版为依据；概述性描述不作为实现阈值。当前 Compose / 共享封面宿主对照 Apple Music 7.0.0 / 1614 恢复 expanded、collapsed(mini)、hidden 释放判据和队列 nested-scroll 到顶 handoff；歌词正文不交给 sheet，歌词页通过顶部/歌曲头专用拖动区收起。无歌词仍只在当前 `mediaId` 为 `Missing` 时回封面，不自动记忆/返回歌词；码流规格只显示当前来源真实值；Apple 字体保留本地可选、公开构建系统 fallback。
- Apple 动态封面只取真实 Apple album `editorialVideo` 方形 HLS；HTTPS 地址限制在 mvod host；Media3 静音循环，cover pane 激活后才开始准备，首帧淡入静态 artwork，全屏交接同一播放器。此链路只播放封面 HLS，不解析歌曲 DRM/MV 地址、不替代已存在的 Fantastic WebView `music-video` 页。
- **仍按用户批准保留的能力边界**：emoji / 协作、SharePlay、人声衰减、推荐队列/自动播放、完整 Cast 发现与专属面板；不创建假的状态。圆形触控规则、固定 alpha 创作者文字、无字数上限独立分享、删除回跟随按钮及隐藏来源 / 3 秒控件优先级规则保留。
- **人工验收边界**：未从设备验证系统媒体 HLS 的站点证书 / 编解码支持、motion 首帧、循环、暂停恢复、全屏 surface 重挂、失焦系统栏、真实 Missing 歌词回退、cover shared-element 往返、nested-scroll flings、RTL / 读屏 / 多窗口。未安装或启动新 APK；本轮构建不作为上述行为的设备验收。
- **此前播放器接线轮构建记录**：Temurin JDK 17.0.21.1+2、Gradle 8.11.1、SDK 35，修正播放器回调 / HLS surface / inset 常量接线后 `:app:assembleDebug --offline --console=plain` 最终 **BUILD SUCCESSFUL in 1s**，38 项任务中 4 项执行、34 项 up-to-date。该 APK 未安装；本轮当前构建与设备记录见“播放器下拉回位与明暗联动”。未新增或运行测试、断言、fixture 或 lint。


## 歌词行按压背景的矩形边界

- 原版参数来自本地 Apple Music Android **7.0.0 / 1614** 样本（`applemusic.apk` SHA-256 `49e83b64fccff79310c7dff174e02228dd0455a3d87df4d9655d1e4f8cd2e351`）：`lyrics_line_karaoke.xml` 与 `lyrics_line.xml` 将系统 ripple 设为行容器背景，左右各留 20dp；`bg_lyrics_line_ripple.xml` 使用 16dp 圆角 mask，`player_vibrant_quarternary` 解析为 `#14ffffff`。原版行点击/长按绑定在容器上，圆角外观不将矩形角落变成触摸死区。
- 当前 `bg_lyrics_row_press.xml` 使用系统 ripple 和同一颜色、圆角。`LyricInkView` 通过 `DrawableWrapper` 仅将整个背景左右内缩 20dp；`pressInsetFor(width)` 同时决定背景边界与 `ACTION_DOWN` 命中许可。宽度不超过 40dp 时，两者都回退全宽。背景只绑定逐句/逐词歌词行，静态歌词、间奏和署名清除背景；不裁剪圆角矩形内的透明角落。
- 不改正文布局、32dp 内容 inset、行高、seek/share 监听或列表滚动仲裁。按压反馈本身仍由 Android 系统 ripple 绘制。
- **本轮构建与产物**：Temurin JDK 17.0.21.1+2、Gradle 8.11.1、SDK 35，执行 `:app:assembleDebug --offline --console=plain`，`BUILD SUCCESSFUL in 48s`，38 项任务执行，Gradle 退出码 0。`app/build/outputs/apk/debug/app-debug.apk` 大小 29,697,922 字节，SHA-256 `e615064f767cf69c605edb614da6a5f793d857e4720cfd6ca6bf7c1024956f2f`；APK 清单中包含 `res/drawable/bg_lyrics_row_press.xml`。未新增或运行自动化测试、断言、fixture 或 lint。
- **效果未验收**：按用户要求，不进行 AVD 安装、实际歌词页操作、触摸/视觉验收或原版逐帧对照。构建和 APK 资源清单仅证明本轮源码编译成功及资源已打包，不代表按压画面或触摸行为已在设备上验收通过。

## Apple Music parity UI plan（本轮）

- 已完成翻译/音译入口命中区收窄：`HeaderAction(exactHitTarget = true)` 只让可见的 `symbol.intrinsicSize` 按钮承载 clickable、Button role 和 selected 语义；原有定位 padding、Popup 锚点及 Popup 内整行选项不变。
- 已完成长间奏拓扑重排：`NativeLyricsView` 在 `notifyItemInserted` / `notifyItemRemoved` 前保存 stable item ID 与 `top + translationY`，复用 `moveExtraPx`、`positionsBeforeMove`、`LyricMotion.line` 和 stagger；非动画条件直接落最终布局，间奏点自身 750ms / 50ms / 4s 曲线未改。
- 已完成音质展示：`qualityPresentation` 只消费 `qualityLabel` 与真实 `audioDetails`，缺位深、采样率或编码时不补值；无损徽标只接受已报告的无损编码，Atmos 保持独立图形。播放页徽标改为 wrap-content 胶囊，质量入口改为 Material 3 AlertDialog，设置按钮先关闭弹窗再进入设置。
- 已完成设置与资料库 UI 重排：新增 `Md3ListComponents.kt`；设置根页 / 质量 detail screen 使用 Scaffold、TopAppBar、LazyColumn、66dp preference rows；资料库使用 Scaffold、overflow menu、segmented filter、透明 ListItem 行和 AlertDialog。服务器检查、SAF、草稿保存、资料库 mutation、快照格式和回调业务逻辑未改。
- 当前源码曾以 Temurin JDK 17.0.21.1+2、Gradle 8.11.1、SDK 35 执行 `:app:compileDebugKotlin --rerun-tasks --offline --console=plain`，结果 **BUILD SUCCESSFUL in 8s**；未新增或运行测试、断言或 lint。
- 随后执行 `:app:assembleDebug --offline --console=plain` 时，共享工作区中新出现的未接线 `ui/player/PlayerSheetMotion.kt` 阻断 `:app:compileDebugKotlin`（`NowPlayingContent`、`sheetFrame` / `artworkModifier` 参数、`zIndex` 及 `childAtTop` 函数值等未解析）；该文件不属于本轮 UI 计划，未覆盖或删除其他会话改动，因此本轮没有可声称通过的新 APK。未安装、启动或做设备视觉 / 播放验收。
## 播放器 Sheet / Mini 单一 host 实施（本轮）

- 已按 Apple Music Android **7.0.0 / 1614** 的 phone stacked 分支接入 `PlayerSheetMotion.kt`：`PlayerSheetDetent`、`PlayerSheetFrame`、`playerSheetReleaseTarget`、`playerSheetSettleDuration` 与 `(t - 1)^5 + 1` easing 现在只有这一处公式 owner。释放速度按平台 min/max fling velocity 处理；`.1` hide friction、`fitToContents=true`、`hideable=true`、500 significant velocity、expanded/collapsed/hidden 三个 detent 和 600ms duration cap 均保留。
- `PlayerSheetHost` 常驻承载 full pane、mini 和主底栏。`AppShell` 不再用 `AnimatedVisibility`、`fadeIn`、`fadeOut` 或 spacer 切换 mini/full；mini 点击、系统返回、手指释放和 nested-scroll handoff 都进入同一 detent settle。`MiniPlayer`、full artwork、非封面内容、动态背景和主底栏读取同一个 `PlayerSheetFrame.progress`；full artwork 的 endpoint 由实际 mini/full 布局测量后套 `artworkTransform`，pane 内 `ARTWORK_KEY` 共享仍保留，mini/full 不再共用该 shared-element tween。
- `NowPlaying.kt` 已收窄为 `NowPlayingContent`：不再持有 `Animatable`、sheet offset、release target、独立 settle job 或 `onClose`；host 接管 drag modifier、nested-scroll、queue-at-top handoff、background pause/resume 和开合回调。歌词/队列 pane 内已有 500ms transition、触控和滚动规则未改。
- 静态检查已通过：`AppShell.kt` 无 `AnimatedVisibility` / `fadeIn` / `fadeOut` / `nowPlayingEnter` / `nowPlayingExit`；`NowPlaying.kt` 无 `spring()`、旧 sheet offset 或直接 `onClose`；sheet easing/release/duration 符号仅在 `PlayerSheetMotion.kt` 定义。
- 固定环境复跑 `:app:assembleDebug --offline --console=plain` 的最终结果为 **BUILD FAILED in 3s**，Gradle/外层退出码均为 **1**。当前共享工作区仍有未由本轮修改的 `FantasticApp.kt`、`playback/FantasticSessionPlayer.kt`、`OfflineDownloadService.kt`、`PlaybackService.kt`、`PlayerConnection.kt`、`PlaybackStore.kt` 与 `ui/search/CatalogDetails.kt` 编译错误；第二次输出未再报告本轮五个播放器文件的错误。未生成可归属本轮的验证 APK，因此不记录 APK SHA-256，也不使用旧 APK 代替。
- `adb devices -l` 返回只有 `List of devices attached`、无在线设备；未安装、启动或伪造 track，未做 collapsed/expanded/hidden 截帧、返回键、真实拖拽、背景相位或逐帧视觉验收。未新增或运行自动化测试、断言或 lint。
- 最终复核在修正 nested-fling 速度钳制后再次执行同一构建命令，仍为 **BUILD FAILED in 3s**、外层退出码 **1**；本次新增输出仅落在共享工作区的 `playback/AppleOfflineExporter.kt`、`FantasticSessionPlayer.kt`、`OfflineDownloads.kt`、`PlaybackService.kt` 与 `ui/search/CatalogDetails.kt`，未报告本轮播放器 host 文件错误。APK 仍无可验证产物。
- 新鲜编译复核：使用同一 JDK 17 / Android SDK 35 / Gradle 8.11.1 环境执行 `:app:compileDebugKotlin --rerun-tasks --offline --console=plain`，`BUILD SUCCESSFUL in 9s`，外层退出码 **0**，16 个 actionable tasks 全部执行。仅有既有弃用警告、`AppleOfflineExporter.kt:85` 的 always-true 警告；未运行 assembleDebug、未生成或核验 APK，也未进行设备验收。

## 目录、搜索、会话和离线下载

- 本轮以 Android 7.0.0 / 1614、`AM-Review/search.mp4` 及 `collection_search_layout`、`content_layout_search2_results`、`search_list_a`、`button_group_play_shuffle_action_buttons` 为依据。采用 42dp 搜索框、2dp 筛选下划线、56dp 搜索图像、集合播放/随机/下载按钮，并让目录详情留在统一迷你播放器下方。保留 QQ、Apple Music、网易云切换和非音频媒体既有路由；不宣称图像/滚动效果已逐帧对齐。
- `MediaPlaybackManager.w`（本地 `player/O.java:2519–2527`）在当前位置达到 4000ms 或没有上一首时回曲首；否则切上一首。`FantasticSessionPlayer` 按该判据处理，Apple 与 Media3 共用按出现位置区分的队列；插播、追加、移除、重排和清空待播不会把目录 id 当成队列行 id。搜索只传单曲；集合从真实游标读取全部曲目，缺页报错而非静默播放部分。`PlaybackStore` 恢复队列、当前位置和随机/循环，不自动播。
- Media3 保留单曲循环。Apple MSE/FLAC 对单曲循环提前接入同一已准备变体，其余托管模式沿用原播放器的 seek 回路。整曲播放缓存的范围与验收见下文。
- Apple 动态封面：在线歌曲 `6814997425` → 专辑 `6814997249`；真实 HLS master HTTP 200、28 个变体；H.264 1080×1080 媒体列表 HTTP 200、首次 init + media 字节范围 HTTP 206。FFmpeg 已解码首帧，见 `.work/apple-motion-first-frame.jpg`。不以宿主机解码替代 Android Surface 验收。网易云 `/ncm/capabilities`、歌曲 DTO 和 `Fantastic/src/ncm.rs::routes` 仅提供静态封面或 MV ID；原生 Rust 适配层未暴露动态封面，不接猜测接口，不改上游。
- Apple Music 下载音质默认 ALAC，可在设置切换。QQ 音乐保留 FLAC、E-AC-3 JOC、AC-4、RA360、DTS、DTS:X 等档位；网易云保留 FLAC、MP3。两者的 AI 档位已从客户端选项移除。Apple 的真实主清单与 CDN 分段曾成功响应，网页下载 Worker 随后报错，尚未生成可验证的离线文件。
- 本轮 `:app:assembleDebug --offline --console=plain --no-daemon` 实际执行 `compileDebugKotlin`，13s 成功，退出 0；`apksigner verify` v2 通过。保留 `.work/catalog-queue-downloads.apk`（30,144,046 字节，SHA-256 `a26f3feb947bc99574de4d3aba7aa031f4edc00838f304887a9042e953c4517a`），日志 `.work/catalog-queue-download-final-build.log`。ADB 无设备；未安装、实测播放器帧、切歌听感、断网播放和杀进程恢复，未新增或运行测试/断言/lint。
## 当前 APK 构建复核

- 使用固定 JDK 17 / Android SDK 35 / Gradle 8.11.1 环境执行 `:app:assembleDebug --offline --console=plain`，`BUILD SUCCESSFUL in 14s`，38 个 actionable tasks 全部执行，外层退出码 **0**。
- APK：`app/build/outputs/apk/debug/app-debug.apk`，大小 **29,814,532 bytes**，SHA-256：`fa1f19b849747ba095790eb008c9d472016ff24458b0c04176d08031789831ad`。
- `adb devices -l` 当前有 `emulator-5562` 在线；本次请求仅构建 APK，未安装、启动或进行播放器手势/真实曲目/逐帧视觉验收。未新增或运行测试、断言或 lint。

## 音质选项清理

- 音质选项只显示编码、无损/空间格式和已知规格。移除 QQ QAI、Q360V1、Q360V2、NAC 与网易云 jymaster；旧偏好回落到保留档位。设置中独立选择 Apple Music 下载音质，默认 ALAC。
- 当前源码最终构建：`:app:compileDebugKotlin --offline --rerun-tasks --console=plain` 与 `:app:assembleDebug --offline --console=plain --no-daemon` 均成功；APK SHA-256 `a855f405c7c38b240f0fe21d935acb53568409d2793f21a6bcc224df64d58676`，大小 `29,937,821 bytes`。

## 最终运行验收

- API 34 隔离模拟器 `emulator-5562` 已安装当前 APK。首页冷启动五项底栏出现；搜索页显示 Apple 结果标签和真实类别砖块。搜索 `Patient Zero` 后 MediaSession metadata 为 `Patient Zero, Taylor Swift, The Life of a Showgirl: The Encore`，`FantasticApple` 日志到达 `Page → Ready → Resolve → Prepare → Playing`；动态封面控件和封面帧已观察。
- 应用私有目录的 Apple ALAC 文件：59,567,744 bytes，`codec=alac`、24-bit、48 kHz、双声道、225.868104s，SHA-256 `c0d30749d0fdf33c410e2413d373fe2774951b90e399cb03b5d0e3c536df673a`。关闭模拟器网络后重新打开资料库“已下载”，MediaSession 继续播放该曲目，进度继续增长。
- 旧包循环测试发现的 OOM 根因是 ALAC 文件内嵌 3000×3000 MJPEG 封面被 Media3 extractor 重复读取；当前 `PlaybackItems.nativeItem` 对本地文件清除 artwork URI。最终当前 APK 冷启动和本地播放无新的 OOM/Fatal/PlaybackException；旧进程退出记录属于旧包。
- `PlaybackStore` 在覆盖安装前后保留当前 occurrence、position、shuffle 和 repeat；最终包恢复 Patient Zero 快照。单曲循环状态已由队列 UI 的“单曲循环”和 MediaSession 快照证实。
- Apple 网页下载 Worker 的桌面 Chromium 直接 ALAC 解密仍返回 `Decryption worker error`；Android 113 WebView 的二进制 WebMessage 已增加 Base64 分块回退。当前已验证的离线文件来自先前成功的网页下载任务，新桥接路径尚未单独跑出另一份文件。
- 真实 QQ/网易云下载端点验证了 302→CDN、206 Range 和实际 FLAC 字节；QQ 客户端保留 FLAC、E-AC-3 JOC、AC-4、RA360、DTS、DTS:X，网易云保留 FLAC/MP3；AI 档位已删除。

## 歌词视口加色合成

- 四段 palette、RTL stops 与字形 `DST_IN` 在本次修改前已存在。本次仅在 `NativeLyricsView` 缓存 `viewportCompositePaint`，用 `PorterDuff.Mode.ADD` 恢复已完成字形及视口遮罩的歌词层。依据原版 Android **7.0.0 / 1614** 的 `PlayerLyricsViewFragment.onCreateView`（约 1925–1927 行）及 `AlphaGradientFrameLayout.dispatchDraw`（约 363–383 行）。保留现有扫亮、长音光晕、时码、字体和背景参数。
- 固定 JDK 17 / SDK 35 / Gradle 8.11.1 执行 `:app:assembleDebug --offline --console=plain --rerun-tasks`，**BUILD SUCCESSFUL in 24s**，38 项任务全部执行，包含 `compileDebugKotlin`。APK 为 `app/build/outputs/apk/debug/app-debug.apk`，SHA-256 `0d3e099d1b8158d5becd4d85c22f4af70b30db5c16863e49a578f4afaf8d8e2d`。未新增或运行自动化测试、断言或 lint。
- API 34 的 `emulator-5562` 覆盖安装返回 **Success**，启动返回 **Status: ok**，随后通过正常迷你播放器和歌词按钮打开已恢复的 Taylor Swift《Patient Zero》歌词。原始静帧 `.work/lyrics-visibility-device.png`：译文笔画 `(210,590)` 为 RGB `(169,77,94)`，左右背景 `(190,590)` / `(230,590)` 为 `(125,31,49)` / `(121,32,48)`；相对背景平均值增加 `(46,45.5,45.5)`，符合 resting alpha 46 的 `ADD` 合成，证明最终加色实际叠到了彩色封面上。
- 点进度条可跳到中段并显示歌词，见 `.work/lyrics-visibility-mid.png`。尝试播放后的画面仍停留在约 1:45；`FantasticApple` 日志到达 `Prepare mode=flac`，没有取得连续扫亮或长音播放画面。因此普通扫词、长音字缘清晰度及原版同设备逐帧 A/B **未验收**；静帧像素证据只证明加色模式生效，不证明已解决所有主观模糊。

## 播放器封面交接与点歌闪开

- Apple Music Android **7.0.0 / 1614** 样本 `applemusic.apk` 的 SHA-256 为 `49e83b64fccff79310c7dff174e02228dd0455a3d87df4d9655d1e4f8cd2e351`。核对 `BottomSheetBehavior.B` 的 slide fraction、`PlayerMainFragment.i` 的实测封面矩形与收起隐藏、`J3.D.a` 的线性插值，以及 `PlayerActivity.onEvents` 和 `c1` 的播放事件与展开入口。原版资源中的 Cover 圆角为 10dp，compact 与 mini 分别为 60dp 和 30dp，圆角均为 5dp。
- `PlayerSheetHost` 用有符号 `slide` 跨曲目保存开合位置。迷你条、返回键、详情导航和手势共用 `animateToDetent`，按同一代际提交 settle 结果。播放器收到空队列后复位收起，Activity 保存的展开初值可等到首次曲目恢复。释放阈值和时长沿用 `playerSheetReleaseTarget` / `playerSheetSettleDuration`。
- Cover、Lyrics、Queue 和 Mini 仅提供透明测量槽。唯一的 `PlayerSheetArtwork` 以 host 坐标按 Sheet 进度从 mini 槽过渡到当前 pane，pane 间使用原有 300ms Bézier 曲线独立更新矩形、圆角和阴影。收起时全屏内容保留测量并移出窗口，绘制与语义关闭。歌曲菜单遮挡时迷你条和底栏继续参与布局，点击禁用。Apple HLS 按曲目查询，同一 HLS 的解码播放器随封面节点保留，收起终点显示静态封面。
- 固定 JDK 17 / SDK 35 / Gradle 8.11.1 执行 `:app:compileDebugKotlin :app:assembleDebug --rerun-tasks --offline --console=plain`。`compileDebugKotlin` 实际执行，**BUILD SUCCESSFUL in 15s**，38 项全部执行，Gradle 与外层退出码均为 **0**。产物 `app/build/outputs/apk/debug/app-debug.apk` 为 **29,831,096 bytes**，SHA-256 `35b1811fa5f07f54cb1436de262a9129ec47aa6fe646775e2c4a26af143e3ed2`。
- 资料库点击、三个 pane 的封面交接、逆向拖拽、窗口变化和真实 Apple HLS 待设备验收。回归对照为 `problems-preview/封面意外停留在底部.mp4` 第 267、330、337、373 帧。

## 手机竖屏沉浸式动态封面

- Apple Music Android **7.0.0 / 1614** 的 `player/f1.java` 优先尝试 `DETAIL_TALL`，Tall 接受播放器宽高比 `[0.40, 0.75]`。`view_motion_container.xml` 的 3:4 视频与预览填满顶部宽度，`fragment_player_main.xml` 将动态封面放在 pane 下方。Fantastic 另沿用自身的 600dp 宽布局断点、彩色模糊背景及 transport 几何。
- `PlayerSheetHost` 为同一封面节点测量 Tall、普通 Cover、Lyrics、Queue 和 mini 端点。3:4 封面位于控件下方，底边渐隐露出背景。目录按专辑解析两种素材，当前 URL 终止失败后尝试不同 URL 的 Square，最终回到方形静态封面。
- `cache/apple-motion-artwork.json` 原子保存最近写入的 128 个成功索引项，按服务地址、店面和目录 ID 隔离。Media3 以完整 URI 和字节范围复用 `cache/streaming-motion-artwork` 的 256 MiB LRU。Debug 日志 `Artwork first frame: tall`、`Artwork first frame: square` 和 `Artwork cache hit` 用于设备验收。
- 重新读取专辑 `6814997249` 的两份 CDN master，确认包含 AVC `1078×1438` 和 `1080×1080`。Tall VOD 播放列表含一个初始化范围和四个媒体范围。[Tall master](https://mvod.itunes.apple.com/itunes-assets/HLSVideo221/v4/2a/ea/2c/2aea2c4f-ea27-7411-550c-9ad47db516a6/P1527872678_default.m3u8) · [Square master](https://mvod.itunes.apple.com/itunes-assets/HLSVideo211/v4/c9/08/62/c908623f-0968-cf79-355c-a0274fe7b1bf/P1506810150_default.m3u8) · [Tall VOD](https://mvod.itunes.apple.com/itunes-assets/HLSVideo221/v4/2a/ea/2c/2aea2c4f-ea27-7411-550c-9ad47db516a6/P1527872678_Anull_video_gr265_sdr_1078x1438.m3u8)
- JDK 17 / SDK 35 / Gradle 8.11.1 执行 `:app:compileDebugKotlin :app:assembleDebug --rerun-tasks --offline --console=plain`，**BUILD SUCCESSFUL in 14s**，38 项全部执行，Gradle 与外层退出码均为 0。`app/build/outputs/apk/debug/app-debug.apk` 于工作站本地时间 `2026-11-22 20:10:36` 生成，29,863,864 bytes，SHA-256 `858a11dba9551b3c9d441dc7565d7912001e20798a092e8faf26b908f3092217`。
- `adb devices -l` 发现 `emulator-5562`。安装与测试设备由用户掌握，《Patient Zero》横竖屏实际首帧与逐帧交接、播放后重启复用媒体字节及 CDN 故障回退待人工验收。

## 播放页大图与 Tall 取帧背景

- Media3 extras 保留 `fantastic.artworkTemplate`，并继续使用 `artwork(600)` 生成通知用 URI。`PlayerSheetHost` 以宿主像素宽度展开模板，预热后的同一位图供共享封面和静态流动背景使用。旧条目和大图加载失败时可回退到已有 URI。
- 参考 Apple Music Android **7.0.0 / 1614** 的 `PlayerCoverArtViewModel.setPlayerSize`、`view_motion_container.xml`、`f1.a` 与 `PlayerMainFragment` 的帧任务。Tall Cover 完全展开且收到首帧后，`TextureView` 每 42ms 采样。视频底边上方 150dp 起叠加 25px 模糊与径向遮罩，底边起叠加取色渐变。遮盖封面的可读性渐变随封面一起淡出，静态流动背景持续铺满播放器。暂停、切页、切歌、动封错误和宿主销毁释放当前帧。
- API 31 及以上使用 `RenderEffect` 模糊可见的底部取样条，在 CPU 上用四个高斯取样点计算 2×2 平均色，省去 GPU 回读。API 26–30 与静态流动背景共用可复用的可分离高斯缓冲。设备验收中，默认视频缓冲和应用同堆竞争时出现一次 HLS `SampleDataQueue` 内存耗尽。动封播放器改用 Media3 的 12.5 MiB 缓冲目标，音乐播放与磁盘缓存走各自现有路径。
- JDK 17 / SDK 35 / Gradle 8.11.1 执行 `:app:assembleDebug --offline --console=plain`。Kotlin 编译与打包实际执行，38 项任务中 4 项执行、34 项已是最新，Gradle 与外层退出码均为 0。`app/build/outputs/apk/debug/app-debug.apk` 为 **30,103,521 bytes**，SHA-256 `8d7263b378ddc06dd3195a16bf80c544787b15c6cc9b12cd02c6d8c1c3c6e613`。验收副本为 `.work/fantastic-artwork-debug.apk`。
- 在 API 34 隔离设备的 864px 窗口播放《Spiegel im Spiegel》（`1725562966`）。目录提供 `{w}x{h}` 模板，日志记录 `decoded=864x864 fallback=false`，封面见 `.work/artwork-5572-static.png`。原版 7.0.0 / 1614 打开同一曲目后停在登录页，同曲播放器截图对照因此未完成。
- 最终 APK 经真实《cardigan》（`1524793745`）播放核对，日志记录 `Artwork first frame: tall` 和 `Artwork backdrop sample: 110x72`。约一秒间隔的 `.work/artwork-5572-final-cardigan-a.png` 与 `-b.png` 显示视频和下方背景均随帧变化。底部取样区平均 RGB 从 `(62,59,62)` 变为 `(63,61,63)`，渐变终点附近连续七行的取样像素均为 `(63,61,63)`。约 54 秒暂停后静态背景接管，见 `.work/artwork-5572-final-paused.png`。
- 最终包播放《Cruel Summer》时，Tall 返回 HTTP 416 后切到 Square 并收到首帧。手动核对歌词、待播清单和从 Cover 下拖回迷你条，证据为 `.work/artwork-5572-bounded-{lyrics,queue,mini}.png`。API 26–30 的视觉验收和长时间播放验证尚未执行。本轮仅执行离线 Debug 构建和设备手动验收。

## 逐词歌词的对唱排版

- Apple Music Android **7.0.0 / 1614** 的 `C4022z.S/s0` 只在逐词歌词中启用对唱。`person` 和 `other` 的声明数量合计至少为 2，且排版结果须出现相对于文字方向反向对齐的行。`z.smali` 的 `s0` 核实了合唱 `group` 与无 `agent` 行沿其文字方向排版并跳过换边。同一发声者沿用对齐状态，换人时比较前一发声行与其紧邻后继的文字方向。首个有效声部为 `other` 时从反向开始。
- `ttml.mjs` 读取 `head/metadata` 内的 `ttm:agent`，兼容直接放在 `head` 下的声明。`LyricInkView` 用解析出的物理侧对齐正文、伴唱和注释。依据 `C4022z.l/o0` 与 `C3985k.invoke`，对唱启用后，只有一位 `person` 时其行仍保留常规宽度。多位 `person` 的行和非 `person` 行使用容器可用宽度的 85%，再扣除文字内边距。
- `:app:assembleDebug --offline --console=plain --rerun-tasks` 在 JDK 17 / SDK 35 / Gradle 8.11.1 下完成，38 项任务全部执行。产物 `app/build/outputs/apk/debug/app-debug.apk` 的 SHA-256 为 `e8138ff4751efbba423808a03d7631945dc7c441fdae61eefc00078ac099a028`。
- 在独立 Chromium 进程中加载 APK 内的解析模块。提供的《Whiplash》AMLL TTML 得到 40 行靠左、43 行靠右，末行 `v1000` 靠左。官方 TTML 的 83 行保留常规宽度与对齐。设备上的歌词渲染和实际播放尚未验收。

## 歌词下拉回位与常亮

- Apple Music Android **7.0.0 / 1614** 的 `PlayerBottomSheetBehavior.p/q/v` 在歌词列表顶端连续出现三次未消费的纵向滚动后，允许超过平台 touch slop 的下拉交给播放器。`BottomSheetBehavior.v/P` 在触摸滚动停止时开始收束。Fantastic 的 `NativeLyricsView` 到达边界前保留 RecyclerView 原生滚动。播放器接管后，以窗口坐标跟踪同一根手指。松手和取消都通过同一代际提交给 `PlayerSheetHost`，沿用当前三档目标和五次减速曲线。
- `AppShell` 在全屏歌词页处于 `RESUMED` 时保持屏幕亮起。暂停播放时仍保持，从歌词页切出、收起播放器或 Activity 暂停时释放。依据为原版 `PlayerLyricsViewFragment.v2` 的窗口常亮生命周期和 Android `View.keepScreenOn`。
- `ApplePlaybackEngine` 在当前准备请求内合并连续进度修改，播放器就绪后应用最后一个位置。背景渲染直接消费 `rememberPrewarmedArtwork` 取得的 Coil 预热结果，API 31 及以上使用系统模糊时省去 CPU 模糊缓冲区。
- 在网页加载尚未就绪时暂停会停止状态轮询。再次开始播放时重新启动轮询，复用仍在加载的网页。

- JDK 17、SDK 35、Gradle 8.11.1 离线执行 `:app:compileDebugKotlin :app:assembleDebug` 成功，Kotlin 编译实际执行。最终 APK 为 `.work/fantastic-lyrics-final-debug.apk`，SHA-256 `ba6bc4688218c0e5fe8d264e7fde8bbeff3d74281d8c4530e27ab0c1b51e7089`。
- API 34 隔离模拟器验证《Patient Zero》。无 fling 的短拖松手后，顶边由约 152 px 连续回到 0。长拖松手后显示迷你条，`mHoldScreenWindow` 清空。充电常亮关闭、熄屏时限 8 秒时，暂停的歌词页保持亮屏 9 秒。录屏为 `.work/lyrics-final-detents.mp4`。
- 网页加载中暂停再继续后，播放请求 4 由 Ready 进入 Playing。新请求准备期间连续点击歌词后，使用最后的位置进入 FLAC 播放，WebView 内的音频 LRU 记录 6,166,850 bytes。证据为 `.work/lyrics-5582-final-stages.txt` 和 `.work/lyrics-5582-final-seek.txt`。另一轮同曲验收观察到动态封面首帧和重复读取的封面媒体字节。

## 整曲音频、歌词缓存与音质徽标

- `PlaybackCache` 在 Apple Music、QQ 音乐和网易云音乐之间共享整曲限额。设置默认 500 MB，四档按十进制字节计算。曲目实际起播后按确认档位排队补齐。以媒体提取器验证音轨、时长和文件大小后才发布完整文件。当前曲目和 Media3 的文件读租约受保护，释放后继续淘汰。手动下载使用 `noBackupFilesDir/offline-audio`。
- `ApplePlaybackEngine` 回报实际变体的 group、codecs、播放模式和网络偏好。自动缓存通过 Fantastic `f1ecbdd` 的 `AmWrapper.songMaster` 与 `AmDecrypt.download` 导出该变体。在线自动混音、交叉淡化和网页空间渲染继续走托管播放器。启动时清理旧的 Media3 `streaming-audio` 目录。网页端移除额外的 8 MiB 分段缓存。动态封面继续使用自身的目录。
- QQ 和网易云徽标依据 Media3 下发的当前音轨。FLAC、ALAC、PCM 以 48 kHz 为无损档分界。只有确认的 E-AC-3 JOC 显示杜比全景声，其余已识别编码显示自身名称。MediaSession 以曲目、队列位置和 occurrence 共同限定标签和规格。[Apple 音质说明](https://support.apple.com/en-us/118295)
- `LyricsCache` 使用独立 16 MiB 磁盘缓存，保存成功解析的整首 `LyricSong`。缓存键包含服务器、音源、曲目及 Apple 店面、语言、字形和来源策略。命中后直接交给原有渲染器，词时码、翻译和音译随整首歌词恢复。

- JDK 17、Gradle 8.11.1 与 SDK 35 的离线 `:app:compileDebugKotlin --rerun-tasks` 和 `:app:assembleDebug --rerun-tasks` 均成功，实际执行 Kotlin 编译。共享构建目录产物在验收期间被覆盖后，使用独立构建目录生成交付包 `.work/whole-track-cache/fantastic-whole-track-debug.apk`。文件长 30,003,886 字节，SHA-256 为 `7d49a0c20bfa63415ea41e33a21290d4bcd10150ab1e12bdae9ced120f894eb9`，与 API 34 安装后的包一致。
- API 34 核对默认 500 MB、四档选项、说明文字以及 `5gb` 的独立存储和重启恢复。QQ《Patient Zero》SQ 和 HQ 的实际徽标分别是无损和 MP3。网易云《All Too Well (10 Minute Version)》lossless 和 standard 也分别显示无损和 MP3。设备未报告 QQ DAEAC3 的选中音轨时，播放器隐藏了徽标。
- 三首样本的本地文件分别为 QQ SQ 49,916,104 字节、网易云 lossless 122,365,876 字节、Apple AAC 7,351,447 字节。提取时长分别为 225,868、613,000、225,912 毫秒。关闭默认网络并重启后，三首均命中本地文件且进度增加。歌词日志分别记录 54、134、54 行命中。网易云离线歌词截图包含中文译文。证据位于 `.work/whole-track-cache/`。
- 200 MB 档中，整曲写入《Echoes》时淘汰最久未使用的网易云曲目，保留更新的 QQ 曲目，两份成品合计 167,886,586 字节。播放 391,744,488 字节的《Dopesmoker》本地文件时缩至 200 MB，该文件留在索引和磁盘。停止播放后索引和对应文件均被移除。中断另一次真实传输时只清掉本次 `.part`。索引、列表和会话快照分别以 `clean-lru-`、`clean-lease-` 和 `interrupted-` 为前缀保存。
- 手动 QQ 下载在 `no_backup/offline-audio` 保存 49,916,104 字节。关闭默认网络后从「已下载」起播，自动缓存的索引总量仍为 167,886,586 字节。APK 内的 `apple-session.js` 保留循环 playlist reuse，已移除音频分段 Map。验收期间旧 `streaming-audio` 未生成，动态封面分段写入独立的 `streaming-motion-artwork`。
- 动态封面目录记录 1,872 KiB 磁盘占用和实际 HLS 分段。交付包覆盖安装后，在无默认网络时继续读取 Apple AAC 缓存，进度由 158,761 到 161,628 毫秒，日志同时记录 54 行歌词缓存命中。交付包编译日志、设备安装哈希及复测证据分别以 `delivery-` 为前缀保存。
