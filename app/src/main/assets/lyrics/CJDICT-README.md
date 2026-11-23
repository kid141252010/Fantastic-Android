# ICU CJK dictionary

`cjdict.tsv.gz` is Unicode ICU's compressed UTF-8 `word<TAB>cost` dictionary data, derived from `cjdict.txt`:
https://github.com/unicode-org/icu/blob/main/icu4c/source/data/brkitr/dictionaries/cjdict.txt

The original data and its upstream conditions are preserved in `CJDICT-LICENSE.txt`. Input SHA-256: `e73fd72048981d0cc13e9dc436a7eaba07ffb6eff58c8a59dc75c1df746663a0`.

The locally maintained `word-segmenter.mjs` can use this dictionary for fallback CJK segmentation. The Android lyrics page currently parses provider-supplied TTML spans and does not import that module.

Store the packaged asset as `cjdict.tsv.gz.bin`: AAPT treats `.gz` assets specially, and `LyricsWebView` maps `.gz` requests back to `.gz.bin`. Refresh the data and license notice together from the intended Fantastic checkout; do not overwrite the local parser modules.