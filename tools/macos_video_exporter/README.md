# CarSpeed macOS 视频导出服务

这是第二套分享版视频合成方案：在 Mac 上运行本地 Web/API 服务，通过 `adb` 从已连接 Android 手机拉取 CarSpeed 原始录像、`.srt` 字幕和 `.track.json` 轨迹，再用 macOS `h264_videotoolbox` 硬件编码生成分享版 MP4。

## 依赖

- macOS
- Android 手机已开启 USB 调试，并能被 `adb devices` 识别
- `ffmpeg` 和 `ffprobe` 在 `PATH` 中
- Python Pillow，用于在 Mac 侧逐帧绘制中文字幕和轨迹图
- 安装示例：`brew install android-platform-tools ffmpeg && python3 -m pip install pillow`

## 启动

```bash
python3 tools/macos_video_exporter/server.py --host 127.0.0.1 --port 8765
```

打开：`http://127.0.0.1:8765`

## Web 功能

- 刷新并列出手机里的 `Movies/CarSpeed` 历史录像
- 选择导出时间段
- 选择播放倍率：`1x / 2x / 4x / 8x / 16x`
- 选择字幕字段：时间、速度、GPS、地理位置、对焦状态、录像规格
- 可选底部拼接轨迹图
- 调整轨迹图高度和比例尺
- 后台合成并提供 MP4 下载

## API

### 健康检查

```bash
curl http://127.0.0.1:8765/api/health
```

### 列出录像

```bash
curl http://127.0.0.1:8765/api/trips
```

### 创建导出任务

```bash
curl -X POST http://127.0.0.1:8765/api/export \
  -H 'content-type: application/json' \
  -d '{
    "trip": "carspeed-trip-20261011-153040",
    "startMs": 0,
    "endMs": 30000,
    "playbackSpeed": 4,
    "includeRouteMap": true,
    "mapHeightPercent": 28,
    "mapScalePercent": 100,
    "fps": 15,
    "subtitleFields": {
      "time": true,
      "speed": true,
      "gps": false,
      "address": true,
      "focus": false,
      "videoMode": false
    }
  }'
```

返回中的 `id` 用于查询任务。

### 查询任务

```bash
curl http://127.0.0.1:8765/api/jobs/<job_id>
```

### 下载结果

```bash
curl -L -o share.mp4 http://127.0.0.1:8765/api/jobs/<job_id>/download
```

## 说明

- 合成产物保存在 `tools/macos_video_exporter/workspace/<job_id>/`。
- 这个 Mac 方案不会修改手机上的原始录像。
- 当前导出视频不混入原始音频，适合短视频平台直接上传带字幕画面。
- 底部轨迹图是本地矢量轨迹渲染，不依赖高德地图底图；这样能离线、快速、稳定地在 Mac 上合成。
- 字幕绘制不依赖 ffmpeg 的 `subtitles/drawtext` 滤镜；当前 Homebrew ffmpeg 即使未启用这些滤镜，也可以由 Python/Pillow 完成文字渲染，再交给 `h264_videotoolbox` 编码。
