#!/usr/bin/env bash
set -euo pipefail
ADB=/Users/thomas990p/Library/Android/sdk/platform-tools/adb
PKG=com.thomas.carspeed
ACT=.MainActivity

$ADB install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
$ADB shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION || true
$ADB shell pm grant $PKG android.permission.ACCESS_COARSE_LOCATION || true
$ADB shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
$ADB shell appops set $PKG SYSTEM_ALERT_WINDOW allow || true

$ADB logcat -c
$ADB shell am force-stop $PKG || true
$ADB shell am start -n $PKG/$ACT >/dev/null
sleep 1.5

# dump hierarchy and tap the toggle button by text
$ADB shell uiautomator dump /sdcard/window_dump.xml >/dev/null
xml="$($ADB shell cat /sdcard/window_dump.xml)"
coords=$(python3 - <<'PY'
import re,sys
xml=sys.stdin.read()
# support both labels
for text in ["启动悬浮窗","停止悬浮窗","start_overlay","stop_overlay"]:
    m=re.search(r'text="%s"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % re.escape(text), xml)
    if m:
        x=(int(m.group(1))+int(m.group(3)))//2
        y=(int(m.group(2))+int(m.group(4)))//2
        print(f"{x} {y}")
        break
PY
<<<"$xml")
if [ -n "$coords" ]; then
  $ADB shell input tap $coords
fi

sleep 1
for p in \
"121.4737 31.2304" \
"121.4740 31.2306" \
"121.4752 31.2315" \
"121.4778 31.2336" \
"121.4818 31.2368" \
"121.4865 31.2406" \
"121.4920 31.2450" \
"121.4985 31.2505"; do
  $ADB emu geo fix $p >/dev/null
  sleep 1
done

$ADB logcat -d | grep 'OverlayService' | tail -n 80 || true
