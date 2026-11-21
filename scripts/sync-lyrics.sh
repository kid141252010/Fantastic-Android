#!/usr/bin/env bash
# 从 Fantastic 仓库同步 ICU CJK 字典数据；TTML parser、word segmenter 和本地渲染器独立维护。
# 用法：scripts/sync-lyrics.sh <Fantastic 仓库路径>
set -euo pipefail
src="${1:?需要 Fantastic 仓库路径}/web/lyrics"
dst="$(dirname "$0")/../app/src/main/assets/lyrics"
for f in cjdict.tsv.gz CJDICT-LICENSE.txt; do
  # AAPT 会对 .gz 资源做特殊处理，打包时改名为 .gz.bin
  case "$f" in *.gz) cp "$src/$f" "$dst/$f.bin" ;; *) cp "$src/$f" "$dst/$f" ;; esac
done
echo "$(git -C "$src" rev-parse --short HEAD)" > "$dst/FANTASTIC_COMMIT"
echo "synced ICU CJK dictionary from Fantastic $(cat "$dst/FANTASTIC_COMMIT")"