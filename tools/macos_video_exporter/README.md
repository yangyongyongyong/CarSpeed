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
    "fps": 60,
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
- 排版层和地图层按 1fps 渲染（CarSpeed 字幕本身就是每秒一条），缩放和摆位在 FilmCraft 里完成；Headless Chrome 渲染复用共享字体工作目录以减少每帧磁盘拷贝开销。
- Forza 速度表默认最大速度为 `300 km/h`，默认不透明度 `100%`，数值采用纯实心亮青色（`#00f2ff`），去除外发光与文字边缘渐变阴影，避免画面叠加发虚；当车速超过 `300 km/h` 时，中心数字显示真实速度，外圈弧度停在最右侧满格处。
- 地图卡片在卫星图（`satellite`）模式下采用**三层分层高清合成**：
  - 底层：仅对高德 `style=6` 纯卫星影像做适度压暗（`0.78x`），避免把上层地名文字一起压灰；
  - 中层：叠加高德 `wprd0*.is.autonavi.com` 的 `style=8&ltype=2` 半透明柔和路网线条（`45%` 不透明度），既保留道路走向又不遮挡卫星地表与文字；
  - 顶层：拉取高德 `style=8&ltype=4` 纯中文地名与路名注记层，将原本容易糊成白块的「浅灰字芯（`#939698`）+ 粗白描边」自适应反转为 Apple/Google 卫星图风格的 **「纯白亮字芯（`#ffffff`）+ 深黑描边（`#0c1017`）」**，配合 `Image.BICUBIC` 三次卷积重采样、`UnsharpMask` 笔画锐化与 `JPEG 4:4:4 (subsampling=0, quality=94)` 无色度抽样编码，解决卫星图地名发灰模糊的问题；
  - 去除右上角多余的「道路 / 卫星」文字角标，左下角比例尺采用无黑框极简高德/Apple Maps 风格（上方居中米数 + 下方带深色描边的细标尺线）；开启局部比例尺（`scaleMeters > 0`）时，车标永久固定在地图卡片正中心（`50%, 50%`），高德底图采用浮点仿射亚像素对齐并在网页播放/拖动及视频导出时按 `5 fps`（200ms 步进）平滑跟随车辆中心移动。
  - **电脑端离线卫星图 & 道路图全路线下载与本地磁盘持久化缓存（`workspace/amap-tiles/`）**：
    - 所有原始瓦片（`satellite`、`satellite_roads`、`satellite_text`、`road`）与三层合成后的高清 JPEG 瓦片（`composed_satellite`、`composed_road`）均持久化保存在 `tools/macos_video_exporter/workspace/amap-tiles/` 磁盘目录中，跨进程重启永久有效；
    - 点击「拉到电脑」同步行程时，或点击行程卡片上的 **「离线下载卫星图」**、右侧地图属性面板的 **「⬇ 离线下载本行程卫星图」** 按钮（接口 `POST /api/map-cache`），服务会自动计算该行程全程总览、`10s:50s` 周期缩放过渡及 `z=12..17` 沿线走廊所需的全部高德瓦片并 24 线程并发下载落盘；
    - 前端切换地图窗口时采用 `Image.decode()` 预解码双缓冲原子替换 + 深色底色托底，彻底消除卫星图加载慢导致的白屏闪动。
- 排版编辑器支持一键开启「全屏预览拖拽」工作台模式（在 16:9 / 9:16 全屏大画布上自由拖拽组件位置、拖右下角缩放把手调大小、右键打开参数面板，`Esc` 键或右上角按钮退出全屏）；同时为地图比例尺、高度及所有滑块参数增加了 `−` / `+` 步进微调按钮与数值显示，并在地图热区底部提供快捷比例尺 `− 200m +` 悬浮胶囊。
- 排版编辑器支持**横屏（`16:9` / `1920×1080`）与竖屏（`9:16` / `1080×1920`）画布一键切换**，并提供**命名配置方案的保存、加载与删除**（持久化保存在 `tools/macos_video_exporter/layout_presets.json`，内置「默认横屏 (16:9)」与「默认竖屏 (9:16)」两套基准方案，用户可针对不同横竖屏拍摄场景自定义命名并随时切换加载，组件尺寸按画面短边自动适配）。
- 左上角 HUD 默认展示 **GPS 卫星颗数徽章（`GPS <N>`）**，且右上角**时间戳默认仅显示时分秒（`HH:MM:SS`）隐藏年月日**（`showDate: false`），防止出行日期隐私泄露；地图卡片右下角内嵌**精致半透明玻璃经纬度水印**（`showCoordinates: true`，默认 `coordinateSize: 11` 对应 `8.5px` 等宽精致字号，与左下角比例尺左右对称，如 `30.30408°N 120.06519°E`），在网页预览播放/拖动与成片导出中均实时同步更新。
- 支持 **家庭隐私保护模式（默认开启，首尾各截断 `1 km`）**（`privacy.enabled: true, trimMeters: 1000`）：
  - 从有有效 GPS 信号的轨迹起点起算，自动截断**开头前 `1 km` 与结尾最后 `1 km`** 对应的视频画面与 GPS 轨迹点；
  - 无论是正片视频画面、底部/悬浮地图中的完整路线与已行驶路线、里程统计，还是片头 6 秒的全屏卫星/道路轨迹总览图，均同步抹除首尾各 1km 路段，防止分享行车视频时暴露家庭住址或目的地隐私；
  - 排版编辑器右侧面板新增 **「隐私保护模式 (默认开启 · 首尾截断 1km)」** 开关与截断距离调节（`0–10000m`），并随命名配置方案持久化保存。
- 支持 **全 GPU 车载视频防抖与超采样增强（Metal 3 512 块密集运动估计 + 36-Tap Lanczos-3 + 25-Tap 细节增强 + 60fps 亚帧运动补偿）**（默认开启，`stabilize.enabled: true`）：
  - 针对手机支架在行车颠簸、过减速带或路面接缝时产生的高频抖动，`metal_compositor.swift` 完全移除 CPU/Vision 同步等待，改为在 GPU 上通过 `estimate_motion_grid`（`32×16 = 512` 个远景地平线 ROI 锚点块 × 每块 `256` 线程并行搜索 `9×9` 邻域 SAD 与梯度能量过滤）和 `update_smooth_transform`（GPU 树状归约与阻尼指数平滑滤波）100% 在显存内完成帧间运动估计；
  - 可在排版编辑器调节「防抖强度」`10–95` 与「边缘裁切(%)」`3%–15%`（默认强度 `65`、边缘裁切 `6%`），既滤除高频路面抖动，又自然跟随车辆转弯与变道；
  - 单 Pass 融合着色器 `fused_stabilize_cas_and_blend_nv12` 在 GPU 寄存器内一次性完成 **36-Tap (`6×6`) Lanczos-3 窗口 Sinc 重采样 + 25-Tap (`5×5`) 双边微细节增强与 9-Tap AMD FidelityFX CAS 对比度自适应锐化 + 16-Tap (`4×4`) 双三次色度重采样 + 60fps 亚帧运动补偿插值 + 紧凑图集 BT.709 Alpha 混合**，仅写回一次 NV12 显存，确保仪表盘与文字 100% 绝对静止且防抖后画质依然锐利通透。
- 支持 **倍速播放、倍速导出与 FPS 帧率设置（默认 `60 fps`）**：
  - 排版编辑器时间轴旁与右侧属性栏均提供 **倍速选择（`0.5x` / `1.0x` / `1.5x` / `2.0x` / `4.0x` / `8.0x` / `16.0x`）** 与 **FPS 帧率设置（`5–120 fps`，默认 `60 fps`）**，并支持一键点击「导出视频」直接生成成片；
  - 预览播放时，倍速实时作用于 `<video>` 原生 `playbackRate` 与抽帧预览步进，HUD 耗时/里程、卫星地图与速度码表按倍速同步推进；
  - 导出视频时，`DEFAULT_FPS` 默认设为 `60`，`normalized_fps` 支持最高 `120 fps`，`metal_compositor.swift` 在倍速抽帧与高帧率输出下严格保证 `CMSampleBuffer` 时间戳单调递增，配合 VideoToolbox 硬件 HEVC 编码输出丝滑高帧率视频。
- 支持 **原视频分辨率自动探测显示 & 导出视频分辨率多档位调节（`exportResolution: "source" | "2160p" | "1440p" | "1080p" | "720p"`）**：
  - 后端通过 `detect_trip_source_size` 与 `probe_size_cached` 自动探测每段行车录像的原视频真实像素尺寸（如 `3840×2160` 4K），并在左侧行程卡片（`原片 4K 3840×2160`）、顶部工具栏（`原视频 4K · 3840×2160`）以及右侧「视频分辨率与导出设置」面板实时展示；
  - 顶部工具栏与右侧属性栏均提供 **导出分辨率下拉框**，支持 **跟随原视频（推荐）**、**4K UHD (2160p)**、**2K QHD (1440p)**、**1080p FHD**、**720p HD** 五档一键切换，自动适配横屏 `16:9`（如 `3840×2160` / `2560×1440` / `1920×1080` / `1280×720`）与竖屏 `9:16` / 上视频下地图拼接模式（如 `2160×3840` / `1440×2560` / `1080×1920` / `720×1280`），并随命名配置方案持久化保存。
- 支持 **片头全屏高德轨迹图（默认插入 `3s` 全屏卫星图 + `3s` 全屏道路图，比例尺可调并随配置持久化）**：
  - 导出视频时，默认在正片前面自动插入 **3 秒高德地图全屏卫星轨迹图（带路网与中文地名注记）** 和 **3 秒高德地图全屏道路轨迹图**（`introMap.enabled: true, satelliteSeconds: 3, roadSeconds: 3`）；
  - 默认比例尺为 `scaleMeters: 0`（自动包络整趟行程的完整 GPS 轨迹，绘制全屏高对比轨迹线、绿色起点、青色终点、左上角行程里程/时长信息卡以及左下角全屏比例尺）；
  - 排版编辑器右侧属性栏新增 **「片头全屏地图 (3s卫星 + 3s道路)」** 面板，支持开关片头、通过滑块或 `− / +` 步进按钮修改片头地图比例尺（`0–20000m`，`0` 代表完整行程轨迹）及卫星图/道路图秒数，所有修改与悬浮窗参数一样实时保存到 `layout.json` 及横竖屏命名配置方案中；
  - `metal_compositor.swift` 通过新增的 `rgba_to_nv12` Metal Compute Shader 将全屏片头图直接在 GPU 显存中转为 `NV12` 帧并按目标帧率写入视频开头，正片时间戳自动顺延衔接。
- 支持 **双展示模式组合（经典 `float` 悬浮窗模式 & 手机竖屏 `split` 上视频下地图拼接模式）**：
  - **经典悬浮窗模式（`layoutMode: "float"`）**：视频铺满画布，HUD、速度码表与圆形/卡片地图作为悬浮层叠加；
  - **上视频下地图手机竖屏模式（`layoutMode: "split"`）**：以 `9:16` 常见手机竖屏比例（如 `1080×1920` 或 `2160×3840`）上下无白边拼接，上方视频区域默认占总高 `38%`（可在 `25%–65%` 调节，由 Metal 着色器自动 Center-Crop 填满上方视口）且仅保留速度码表显示，下方铺满高德地图（支持卫星图/道路图切换、右键及底部快捷栏 `−` / `+` / `全程` 缩放、左上角毛玻璃里程胶囊 `里程 626 m / 13.23 km`）；
  - **还原手机 App（`MapRouteView.kt`）路线与测速标记样式**：下方地图与片头全屏图均采用翠绿色路线底线 `#4fd697`（车速 `> 180 km/h` 自动 `1.5x` 加粗）+ 纯白已行驶内芯 `#ffffff`、三色循环（青/黄/玫红）+ 脉冲扩散光环的当前位置闪动圈、整段行程最高速度所在点（玫红实心圆 `#ff4081` + 白边 + 最高车速标签），以及从 `.track.json` 读取的测速雷达摄像头图标与深藏青限速胶囊（如 `80` / `120`）。
- 支持 **周期性全程图↔局部图丝滑缩放切换（默认 `10s` 全程图 + `50s` 局部固定比例尺图）**：
  - 两种展示模式（`float` 与 `split`）均支持开启周期性全貌展示（`map.cycleOverviewEnabled: true, overviewSeconds: 10, detailSeconds: 50`），无论正常播放还是倍速快进/倍速导出，均统一按**输出成片时间**每 60 秒循环展示 10 秒完整轨迹全貌图 + 50 秒用户设定比例尺的当前位置局部图；
  - 全程图与局部图交界处采用 Cosine Smoothstep 余弦缓动（`1.8s` 过渡窗口）与墨卡托对数尺度（Log-scale）跨度插值，镜头拉远看全貌与推近看局部细节丝滑连贯、无生硬跳切。
- 已通过「拉到电脑」保存在 `workspace/library/<trip-id>/raw` 的行程支持离线生成 FilmCraft 项目与导出视频，并通过硬链接优先复用本地文件，无需重复 `adb pull`。
- 当本机未连接手机或默认 `adb` 端口冲突时，`/api/health` 与 `/api/trips` 自动降级为离线模式并秒级返回本地资料库行程与视频抽帧缩略图；覆盖层 `iframe` 采用 `color-scheme: normal` + `forced-color-adjust: none` 保持画布透明，并通过 `ResizeObserver` 与 `scale(scaleX, scaleY)` 与预览容器 100% 贴边对齐，隐藏外层占位底图 `#layoutImage` 防止双层底图叠出右侧重复条带。
- `/api/export` 视频导出采用 **Metal 3 全 GPU 异步满载计算流水线（100% GPU 利用率）+ 紧凑组件图集（Packed Widget Atlas）+ 多路并行 VideoToolbox HEVC 硬件编解码** 架构：
  - **全 GPU 3-Pass 算子融合与零拷贝显存闭环**：原生 `metal_compositor.swift`（编译为 `bin/carspeed_metal_compositor`）通过 `AVAssetReader` 将 4K 帧直接解码进驻留 GPU `IOSurface` 的 `NV12`（`kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange`）显存表面，并在同一 `MTLCommandBuffer` 内依次调度 `estimate_motion_grid`（512 块 GPU 运动估计）、`update_smooth_transform`（GPU 轨迹滤波）与 `fused_stabilize_cas_and_blend_nv12`（单 Pass 融合 36-Tap Lanczos-3 + 25-Tap 细节锐化 + 60fps 亚帧插值 + 寄存器级图集叠加），**全程 0 次 4K 画面 GPU→CPU 下载、0 次 CPU→GPU 上传、每帧仅写回 1 次 NV12 显存**。
  - **$O(1)$ 恒定有界内存架构（长视频导出不随视频时长涨内存，杜绝 OOM 死机）**：
    - **有界滑动窗口流式喂帧**：Python 端完全移除无界 `Executor.map` 预取，改为 `deque(max_in_flight=2)` 滑动窗口按需提交并消费 `Future` 帧字节流，同一时刻全系统在途 Atlas 帧最多仅 `8` 帧（`< 55MB`）；
    - **1080p 紧凑图集 + Metal GPU 36-Tap Lanczos-3 & 9-Tap CAS 超分采样（解决竖屏 `split` 模式 GPU 负载偏低问题）**：当导出分辨率大于 `1080p`（如 `2160×3840` 4K 竖屏）时，Atlas 覆盖层按最高 `1080p` 基准渲染（单帧体积降低 `75%`），并在 `fused_stabilize_cas_and_blend_nv12` 中通过 `sample_bicubic_cas_rgba` 对图集执行 **36-Tap (`6×6`) Lanczos-3 抗锯齿超分重采样 + 9-Tap CAS 对比度自适应锐化**。即使在竖屏 `split` 模式（下半屏 62% 为高德地图）下，全画布 100% 像素线程均全速运行 GPU 纹理与 ALU 单元，使 GPU 利用率从 `15%` 跃升至 **`83%–100%`**，同时让超分后的高德卫星图文字与路线边缘更加锐利清晰；
    - **Swift 主循环 `autoreleasepool` + 定期排空 `CVMetalTextureCacheFlush`**：`metal_compositor.swift` 逐帧解码与合成循环包裹在 `autoreleasepool` 内，每 30 帧调用 `CVMetalTextureCacheFlush` 释放 CoreVideo/Metal 临时包装对象，配合 `maxInFlightFrames = 10`、`atlasRingSize = 10` 与 4 路并发分片，长视频导出全程总内存稳定控制在 **`~1 GB`** 以内。
  - **消除高德瓦片嵌套线程池死锁与重复拼接开销**：拆分独立的 `_AMAP_TILE_FETCH_POOL` 与子瓦片线程池 `_AMAP_SUBTILE_FETCH_POOL`，彻底杜绝卫星图三层并发合成时的线程饥饿死锁；通过有界 `_AMAP_GRID_CANVAS_CACHE`（16 槽位）与 SIMD 亚像素 `resize(..., box=...)` 将单帧地图裁切耗时降至 `2.6ms`，以 `rawvideo rgba` 管道直通合成器。
- **后台常驻守护与任务断线自愈**：
  - `server.py` 支持 `--daemon` 双 `fork` + `setsid` 脱离控制终端常驻后台运行，并忽略 `SIGHUP`，避免终端会话关闭导致导出中途断连；
  - `start_export_job` 在本地资料库已缓存行程时仅校验 `ffmpeg`、`ffprobe`、`pillow`，并在 `ensure_tool_path()` 中自动发现 `~/platform-tools/platform-tools/adb`；
  - `handle_job_get` 支持 `recover_job_from_workspace` 从磁盘自动恢复已完成或中断的历史任务状态，前端 `studio.html` 明确区分 `视频 GPU 硬件导出中` 与 `FilmCraft 项目生成中` 并在断线/重启时自动重置轮询状态与按钮锁。
- Studio 面向本机 `127.0.0.1` 使用，内置 `Host`/`Origin` 校验、`HEAD` 预检支持与请求体大小上限保护。
