# CarSpeed

CarSpeed 是一个面向驾驶场景的 Android 应用：手机行车记录仪 + 驾驶 HUD + 测速雷达提醒 + 行驶轨迹记录。

## Features

- 行车录像：后台无缝分段录像、循环覆盖、收藏保护、录像字幕（速度/位置/时间）、录像中拍照
- 回放与分享：分段连续回放、轨迹地图同步、导出带字幕和地图的分享视频、GPX 导出
- 驾驶 HUD：悬浮窗显示当前速度、最高速度、行程时长、等待时长和速度趋势，支持拖动/折叠/缩放
- 测速雷达：基于高德导航 SDK 的电子眼和限速提醒，可独立于导航运行
- 轨迹记录：不录像时也能记录行驶轨迹，进入驾驶记录和行程统计
- 自动触发：连接车载蓝牙或打开高德地图时自动开始录像/记录
- 离线语音口令：拍照、开始/停止录像、开关测速雷达（Vosk 中文模型，首次在 Wi‑Fi 下载约 42MB）
- 桌面小部件和快捷设置开关

## Tech Stack

- Kotlin, Android Views + Material 3, Gradle Kotlin DSL
- Camera2 + MediaCodec/MediaMuxer（录像），高德 3D 地图/导航/定位/搜索 SDK
- ML Kit 文字识别（车牌），Vosk 离线语音

## Package Name

`com.thomas.carspeed`

## Local Development

在 `local.properties` 中配置 SDK 路径和高德 Key（不要提交该文件）：

```properties
sdk.dir=/path/to/android/sdk
AMAP_API_KEY=your_amap_key
```

```bash
./gradlew :app:assembleDebug
```

Run unit tests:

```bash
./gradlew :app:testDebugUnitTest
```

## Release Build

Release 构建开启了 R8 混淆/资源压缩，只打包 `arm64-v8a` 和 `armeabi-v7a`：

```bash
./gradlew :app:assembleRelease
```

更多本地脚本见 [`scripts/`](scripts/)，测试说明见 [`doc/testing.md`](doc/testing.md)。

## Notes

- 首次启动需同意隐私说明，之后才会初始化高德 SDK。
- 需要定位、相机、麦克风、通知、悬浮窗权限；车载蓝牙触发在 Android 12+ 还需要“附近设备”权限。可在应用内“状态检查”页逐项处理。
- 因为包名迁移到了 `com.thomas.carspeed`，它会作为新应用安装，而不是升级旧包名的版本。
