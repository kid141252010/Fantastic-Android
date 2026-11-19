#!/usr/bin/env bash
# 从 Fantastic 仓库复制 AMLL 内核、TTML 解析和分词器到 app/src/main/assets/lyrics。
# 用法：scripts/sync-lyrics.sh <Fantastic 仓库路径>
set -euo pipefail
src="${1:?需要 Fantastic 仓库路径}/web/lyrics"
dst="$(dirname "$0")/../app/src/main/assets/lyrics"
for f in amll-core.mjs amll.css ttml.mjs word-segmenter.mjs cjdict.tsv.gz jieba-words.txt.gz \
         CJDICT-LICENSE.txt CJDICT-README.md JIEBA-LICENSE.txt LICENSE-cutword.txt; do
  # aapt 会对 .gz 资源做特殊处理（打包后按原名读不到，404），存成 .gz.bin，由 LyricsWebView 映射回来
  case "$f" in *.gz) cp "$src/$f" "$dst/$f.bin" ;; *) cp "$src/$f" "$dst/$f" ;; esac
done
echo "$(git -C "$src" rev-parse --short HEAD)" > "$dst/FANTASTIC_COMMIT"
echo "synced from Fantastic $(cat "$dst/FANTASTIC_COMMIT")"
