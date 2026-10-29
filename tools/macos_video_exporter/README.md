# CarSpeed Studio

CarSpeed Studio 是 CarSpeed Android App 的 macOS 配套工具。它通过 `adb` 读取手机中的完整历史行程，把录像、CarSpeed SRT、GPS 轨迹和车牌数据拉取到本机，再调用官方 `filmcraft-cli` 生成并打开可直接编辑的 FilmCraft 项目。

每个选中的行程会生成一个独立的 `.fcproj`。多段录像按文件名顺序连续排入时间轴，SRT 字幕按片长偏移合并后导入同一个字幕轨。

## 依赖

- macOS
- Android 手机已开启 USB 调试，并能被 `adb devices` 识别
- FilmCraft 0.14.0 或更高版本，安装在 `/Applications/FilmCraft.app`
- 官方 `filmcraft-cli`，默认位于 `~/.local/bin/filmcraft-cli`
- `ffmpeg` 和 `ffprobe` 在 `PATH` 中
- Python Pillow，用于保留旧版 MP4 扁平化导出能力

当前机器的依赖也可以手动复核：

```bash
adb devices
filmcraft-cli --version
ffprobe -version
python3 -c 'import PIL; print(PIL.__version__)'
```

## 启动

```bash
python3 tools/macos_video_exporter/server.py --host 127.0.0.1 --port 8765
```

打开：`http://127.0.0.1:8765`

服务会自动把 Android SDK、Homebrew 和 `~/.local/bin` 加入工具搜索路径，因此从 Finder 或其他 GUI 环境启动时不需要预先修改 shell `PATH`。

## Studio 流程

1. 用 USB 连接手机并确认 `adb devices` 状态为 `device`。
2. 启动服务并打开 Studio 页面。
3. 按“整趟行程”勾选一个或多个历史记录。
4. 点击“生成并打开”，页面会显示总进度和每个行程的状态。
5. 每个行程生成完成后，Studio 会自动用 FilmCraft 打开对应的 `.fcproj`。

同一轮批量任务会依次复用同一个 FilmCraft 实例；再次运行任务时，Studio 只关闭自己上次启动的 FilmCraft 进程，再打开新的项目。这样不会积累窗口，也不会按进程名误关用户手动启动的 FilmCraft。

生成的素材与项目保存在：

```text
tools/macos_video_exporter/workspace/<job-id>/<trip-id>/
```

其中：

- `raw/`：从手机拉取的视频、SRT、轨迹和车牌原始文件。
- `carspeed-timeline.srt`：多段字幕按时间轴偏移合并后的字幕文件。
- `captions.jsonl`：批量写入 FilmCraft 字幕轨的 CLI 指令，便于复现和排查。
- `carspeed-overlay.mov`：HUD、速度仪表盘和独立文字字段的透明叠加层（ProRes 4444）。
- `carspeed-map.mov`：圆形轨迹地图图层（ProRes 4444）。
- `carspeed-edit.json`：行程、时间轴、字幕轨与 Project 的对应清单。
- `*.fcproj`：FilmCraft 项目，包含连续视频时间轴、排版层、地图层和 CarSpeed 字幕轨。

### 关于字幕轨

字幕轨是 FilmCraft 的**元数据轨**，默认不会烧进导出的画面，需要在导出时显式开启：

```bash
filmcraft-cli --project <项目>.fcproj exec file.exportMedia \
  '{"path":"out.mp4","format":"h264","burnCaptions":true}'
```

字幕文本按 `layout.json` 的 `caption` 段设置字体、字号、背景和距底部留白
（默认 `margin: 0.30`，正好避开右下角的圆形速度表）。字幕内容只保留
「时间 + 速度」：地址由独立文字字段显示，坐标由地图图层显示，重复放进字幕会让画面拥挤。

已有 `.track.json` 会原样保留。缺失轨迹但 SRT 含有经纬度与速度时，Studio 会从字幕生成标准轨迹 JSON。估算速度只用于编辑辅助，不会替换手机中的原始数据。

## 素材目录

Studio 会扫描以下 CarSpeed 历史目录，兼容新旧版本：

```text
/sdcard/DCIM/CarSpeed
/sdcard/Android/media/com.thomas.carspeed/CarSpeed
/sdcard/Movies/CarSpeed
/sdcard/Android/data/com.thomas.carspeed/files/Movies/CarSpeed
```

## API

### 健康检查

```bash
curl http://127.0.0.1:8765/api/health
```

### 列出录像

```bash
curl http://127.0.0.1:8765/api/trips
```

返回每一趟行程的段数、视频大小、轨迹覆盖率、字幕覆盖率、缩略图 URL，以及预计从手机拉取的总字节数。

新版 CarSpeed 会在首段录像完成后，把首段视频第 1 分钟的画面生成到行程目录中的
`carspeed-trip-thumbnail.jpg`（不足两分钟的视频取中点）。Studio 只按需通过 ADB 拉取这张 JPEG 并缓存，
不会为了列表封面下载完整视频。

### 创建 FilmCraft Studio 任务

```bash
curl -X POST http://127.0.0.1:8765/api/studio \
  -H 'content-type: application/json' \
  -d '{"trips":["20261015-194951"],"open":true}'
```

`trips` 是 `/api/trips` 返回的行程 ID 列表。`open=true` 表示项目生成成功后自动在 FilmCraft 中打开。任务通过后台线程执行，接口会立即返回任务 ID。

### 旧版 MP4 导出

旧版扁平化 MP4 导出仍保留在 `/legacy` 页面和 `/api/export` 接口，主要用于临时分享；日常编辑应使用 Studio + FilmCraft 流程。

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

## 当前边界

- 不会修改或删除手机上的原始录像。
- 不把不同日期的行程拼进同一个项目；需要合并时在 FilmCraft 中自行组合。
- 已完成视频、轨迹、字幕和车牌素材导入，以及连续时间轴、字幕轨、HUD 叠层、速度仪表盘和圆形轨迹地图图层。
- 排版层和地图层按 1fps 渲染（CarSpeed 字幕本身就是每秒一条），缩放和摆位在 FilmCraft 里完成。
- Studio 目前面向本机 `127.0.0.1` 使用，没有引入账户、局域网鉴权或云端上传。
