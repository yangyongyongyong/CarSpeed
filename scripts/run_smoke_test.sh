#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if ! command -v adb >/dev/null 2>&1; then
  echo "[ERROR] adb 未找到，请先安装 Android SDK platform-tools 并加入 PATH"
  exit 1
fi

JAVA_HOME_DEFAULT="/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home"
export JAVA_HOME="${JAVA_HOME:-$JAVA_HOME_DEFAULT}"

echo "[1/6] 构建 debug APK"
./gradlew :app:assembleDebug

echo "[2/6] 等待设备"
adb wait-for-device

echo "[3/6] 安装 APK"
adb install -r app/build/outputs/apk/debug/app-debug.apk

echo "[4/6] 预授权定位权限"
adb shell pm grant com.thomas.carspeed android.permission.ACCESS_FINE_LOCATION || true
adb shell pm grant com.thomas.carspeed android.permission.ACCESS_COARSE_LOCATION || true
adb shell pm grant com.thomas.carspeed android.permission.POST_NOTIFICATIONS || true

echo "[5/6] 启动 App"
adb shell am start -n com.thomas.carspeed/.MainActivity

echo "[6/6] 注入一组模拟定位点（用于速度变化观察）"
adb emu geo fix 121.4737 31.2304 || true
sleep 1
adb emu geo fix 121.4745 31.2310 || true
sleep 1
adb emu geo fix 121.4756 31.2318 || true

echo "[DONE] Smoke test 脚本执行完成。"
echo "提示：悬浮窗权限（SYSTEM_ALERT_WINDOW）无法通过 adb grant，需手动在系统设置中允许一次。"
