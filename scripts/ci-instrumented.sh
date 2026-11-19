#!/usr/bin/env bash
# CI 模拟器里跑 instrumented 测试；无论成败都打印歌词相关 logcat 并取回截图（base64 打进日志 + artifact）。
# android-emulator-runner 的 script 逐行执行，所以写成单独脚本。
set -u
ok=0
gradle :app:connectedDebugAndroidTest --stacktrace && ok=1
adb logcat -d -s FantasticLyrics TestRunner AndroidRuntime chromium | tail -300
echo "---- crash buffer ----"; adb logcat -d -b crash | tail -150
echo "---- errors ----"; adb logcat -d "*:E" | grep -v "^--------" | tail -150
for f in lyrics.png lyrics-e2e.png; do
  adb pull "/data/local/tmp/$f" "$f" >/dev/null 2>&1 || continue
  echo "PNG_BEGIN $f"; base64 -w0 "$f"; echo; echo "PNG_END $f"
done
[ "$ok" = 1 ]
