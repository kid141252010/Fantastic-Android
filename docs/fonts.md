# 字体与本地打包

本文面向本机构建与字体维护；日常设置见[使用指南](usage.md#字体字重)。

## 本地字体打包

可选字体只从 Git 忽略的 `.work/local-am-fonts/assets/am-fonts/` 读取，不随源码或公开 CI 分发，也不在构建时下载。该目录需同时包含 `sf_pro_android_ui.ttf`、`sf_pro_rounded_android_ui.ttf`、`pingfang_ui_vf.ttc`、`hiragino_sans.ttc` 和 `apple_sd_gothic_neo.ttc`；现有其他语种字体可继续放在同一目录。来源说明须放在同一资产根目录的 `licenses/pingfang-ui-vf.txt`，随本地 APK 打包。

按 [Apple 的 CJK 字体建议](https://support.apple.com/zh-cn/guide/pages/tanfbd4156e/mac)，Android 10 及以上的界面和歌词共用 SF Pro 与 CJK 回退链：简体中文用苹方-简、台湾繁体用苹方-繁、香港和澳门用苹方-港，日文用无衬线 Hiragino Sans，韩文用 Apple SD Gothic Neo。应用语言决定汉字字形与回退顺序；苹方 UI 使用自身的 `wght`（100–900）和固定 `wdth=100`，不传 SF 的 `opsz`。请求字重优先，并补齐真实 400/700 实例供常规/粗体 run 选择；日文和韩文保留完整静态字重家族。Android 8–9 的 CJK 继续使用系统字体。

本地苹方来自 [ACT-02/PingFangUI-VF 的 21.4d1e1 版本](https://github.com/ACT-02/PingFangUI-VF/releases/tag/21.4d1e1)附件 `PingFangUI.tahoe.26.4.1.wght.wdth.ttc`。输入 SHA-256 为 `d1705d6a5e8e4c5981ec39ffd12effb7854c8bd4c20b5dba7e2db922f558e7c7`；使用 fontTools 4.68.0 的 `TTCollection`，以 `lazy=True, recalcBBoxes=False, recalcTimestamp=False` 载入，保存前仅保留 face `[0,1,2,4]`，并以 `shareTables=True` 保存为 `pingfang_ui_vf.ttc`。输出为 69,647,704 bytes，SHA-256 为 `e8d6762532ee1aeb9239e67d0a8f487d61b000ebddfe63f0293552658b3b5197`，顺序为 SC/TC/HK/JA，物理移除 MO；JA 保留但不加入实际回退链。来源说明使用[固定上游 commit 的 README 原文](https://raw.githubusercontent.com/ACT-02/PingFangUI-VF/8a901f9d8820bf8752fa20ca9903a33c9f225bbc/README.md)，按其学习研究、非商业及附带来源条件使用；项目的 Apache-2.0 不替代字体使用条件。新库的轮廓、字宽和字符覆盖与旧静态苹方不同，部分字符可能依赖系统回退，不承诺像素相同或全字符无差异。

本机构建显式传 `-PincludeLocalAmFonts=true`，缺少任一必需字体或来源说明时停止构建；资产目录残留旧 `pingfang.ttc` 或 `pingfang_ui.ttc` 也会停止，需自行移至 assets 外，不同时打包。用 `-PincludeLocalAmFonts=false` 可构建纯系统字体包。TTC 在 APK 内不压缩，按集合共享只读映射；苹方家族按地区与请求字重缓存并跨字号共享，日文/韩文各自缓存完整静态家族。

## 字重映射与布局

正文、标题和歌词的自定义字重范围为 100–900，按整数保存；未设置或恢复默认时沿用各处既有样式，不改变字号、行高或字宽轴。操作入口见[使用指南](usage.md#字体字重)。

- 正文：正文/标签、列表文字、说明和播放器艺人等；标题：页面/分区/卡片标题及播放器曲名。显式加粗或中黑的标题也服从标题设置，不被固定的 `FontWeight` 覆盖。
- 歌词：主词、伴唱、译文和音译共用该值；修改时重建文本布局，但不重新加载歌词、不重置播放时钟。创作者署名与来源说明保留原有常规/粗体区分。
- 默认仍使用原有 `500→510`、`600→590`、`900→1000` 映射；自定义值按原值请求 SF 和苹方 VF，不再套用该映射。苹方的地区与字重缓存继续共享同一只读 TTC 映射；日文、韩文和系统字体按实际可用字重匹配，旧系统不保证连续字重显示。字体自然字宽或轮廓变化可能影响折行，不用改字号或压扁文字掩盖。
