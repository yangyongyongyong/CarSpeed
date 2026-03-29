# 自动化测试使用说明

## 1) 本地快速验证（推荐每次改需求后执行）

```bash
cd /Users/thomas990p/iqooAPP/iqooAPP
./scripts/ci_local.sh
```

这个脚本会：
1. 跑单元测试 `:app:testDebugUnitTest`
2. 构建 Debug 包 `:app:assembleDebug -x lint`
3. 若检测到 `adb` 且有在线设备，再跑 UI 自动化 `:app:connectedDebugAndroidTest`

---

## 2) UIAutomator 自动权限弹窗

新增测试：
- `app/src/androidTest/java/com/thomas/carspeed/PermissionUiAutomatorTest.kt`

用途：
- 自动点击常见“允许/Allow”按钮，减少手工点权限弹窗。

执行：
```bash
./gradlew :app:connectedDebugAndroidTest
```

> 注意：不同 ROM 的悬浮窗权限页（SYSTEM_ALERT_WINDOW）可能不是标准弹窗，仍可能需要首次手动开一次。

---

## 3) GitHub Actions CI

工作流文件：
- `.github/workflows/android-ci.yml`

触发：
- push / pull_request

执行内容：
- 单元测试
- debug 构建

如果你希望未来把 connected androidTest 也放到云端，可再加 emulator runner（耗时会明显增加）。
