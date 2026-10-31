"""把 Layout 配置翻译成参考稿结构的 HTML 片段。

这里只负责「数据 → DOM」，样式全在 overlay_template.html 的 CSS 里。
参考稿的每个组件都对应一个 builder，产出的 class 名和参考稿完全一致，
所以浏览器渲染出来的就是设计稿那一套。

尺寸：参考稿是按像素写的（计时栏时间戳 13px、地图 220×140、表盘 250px）。
--ui 把这些像素按「当前组件 / 原稿」放大，圆角、斜切、字号一起变，不会只放大外框。
"""
from __future__ import annotations

import math
import re
from typing import Any

from overlay_render import escape

# 参考稿 ai_studio_code.html 里的基准像素。
REFERENCE_TIMESTAMP_PX = 13.0
REFERENCE_MAP_WIDTH = 220.0
REFERENCE_MAP_HEIGHT = 140.0
REFERENCE_GAUGE_PX = 250.0
FORZA_CIRCUMFERENCE = 471.0
FORZA_GAP = 118.0
GT_BLOCK_COUNT = 9
AERO_TICK_COUNT = 7


def canvas_ui(canvas_height: int, canvas_width: int | None = None) -> float:
    """短边 1080p 上是 1，更高的画面按短边放大，横竖屏比例均与参考稿一致。"""
    short = min(canvas_width, canvas_height) if canvas_width else canvas_height
    return max(0.5, short / 1080)


def timing_ui(time_size: int, canvas_height: int, canvas_width: int | None = None) -> float:
    """时刻字号 13 在短边 1080p 上就是参考稿的时间戳。"""
    return max(0.5, time_size / REFERENCE_TIMESTAMP_PX) * canvas_ui(canvas_height, canvas_width)


def map_card_size(size_percent: float, canvas_width: int, canvas_height: int) -> tuple[float, float, float]:
    """地图高度占短边的百分比，宽度按参考稿 220:140。返回宽、高、--ui。"""
    short = min(canvas_width, canvas_height)
    card_h = max(80.0, size_percent * short)
    card_w = card_h * (REFERENCE_MAP_WIDTH / REFERENCE_MAP_HEIGHT)
    return card_w, card_h, card_h / REFERENCE_MAP_HEIGHT


def gauge_box_px(radius_percent: float, canvas_width: int, canvas_height: int) -> tuple[int, float]:
    """Forza 表盘外框边长，以及相对参考稿 250px 的 --ui。"""
    short = min(canvas_width, canvas_height)
    size = max(120, int(radius_percent * short * 1.15 * 2))
    return size, size / REFERENCE_GAUGE_PX


def forza_dash_offset(ratio: float) -> float:
    """参考稿脚本：周长 471，缺口 118，速度从满缺口收到实线。"""
    clamped = max(0.0, min(1.0, ratio))
    return FORZA_CIRCUMFERENCE - clamped * (FORZA_CIRCUMFERENCE - FORZA_GAP)


def _format_hud_timestamp(raw_time: str, use_24h: bool = True, show_date: bool = False) -> str:
    """按 24 小时制和日期开关格式化 HUD 顶栏时间戳。"""
    text = (raw_time or "").strip()
    match = re.search(r"(\d{4}-\d{2}-\d{2})\s+(\d{2}):(\d{2}):(\d{2})", text)
    if not match:
        return text
    date_part, hour_str, minute_str, second_str = match.groups()
    hour = int(hour_str)
    if use_24h:
        clock = f"{hour_str}:{minute_str}:{second_str}"
    else:
        suffix = "AM" if hour < 12 else "PM"
        hour12 = hour % 12 or 12
        clock = f"{hour12:02d}:{minute_str}:{second_str} {suffix}"
    return f"{date_part} {clock}" if show_date else clock


def _extract_satellite_count(raw_satellites: str) -> str:
    """从 SRT 卫星字段（如 'GPS卫星：19'）提取简短数值文本。"""
    text = (raw_satellites or "").strip()
    if not text:
        return ""
    match = re.search(r"(\d+)", text)
    return match.group(1) if match else text


def build_timing_widget(
    hud: Any,
    values: dict[str, str],
    telemetry: dict[str, str],
    progress: float,
    canvas_height: int = 1080,
    canvas_width: int | None = None,
) -> str:
    """左上角计时栏：REC + 时间戳 / 进度条 / 三组遥测。x/y 是左上角。"""
    if not hud.enabled:
        return ""
    use_24h = bool(getattr(hud, "time_format_24h", True))
    show_date = bool(getattr(hud, "show_date", False))
    show_duration = bool(getattr(hud, "show_duration", True))
    show_distance = bool(getattr(hud, "show_distance", True))
    show_bar = bool(getattr(hud, "progress_bar", True))
    show_bg = bool(getattr(hud, "background", True))
    time_text = escape(_format_hud_timestamp(values.get("time") or "", use_24h=use_24h, show_date=show_date))
    sat_num = _extract_satellite_count(values.get("satellites") or "")
    sat_badge = (
        f'<span class="hud-sat" id="hud-satellites">GPS <b id="hud-sat-val">{escape(sat_num)}</b></span>'
        if sat_num
        else '<span class="hud-sat" id="hud-satellites" style="display:none">GPS <b id="hud-sat-val"></b></span>'
    )
    duration = escape(telemetry.get("duration", "00:00:00"))
    distance = escape(telemetry.get("distance", "—"))
    percent = f"{progress * 100:.1f}%"
    ui = timing_ui(int(getattr(hud, "time_size", REFERENCE_TIMESTAMP_PX)), canvas_height, canvas_width)
    extra_style = ""
    if not show_bg:
        extra_style += ";background:transparent;backdrop-filter:none;-webkit-backdrop-filter:none;border-color:transparent;box-shadow:none"
    progress_color = str(getattr(hud, "progress_color", "") or "").strip()
    if progress_color and progress_color.lower() not in {"#ffb020", "#ff5e00"}:
        extra_style += f";--primary-color:{escape(progress_color)}"
    text_color = str(getattr(hud, "text_color", "") or "").strip()
    ts_style = f' style="color:{escape(text_color)}"' if text_color and text_color.lower() != "#ffffff" else ""
    bar_style = "" if show_bar else ' style="display:none"'
    dur_style = "" if show_duration else ' style="display:none"'
    dist_style = "" if show_distance else ' style="display:none"'
    return f"""
<div class="hud-widget" id="widget-timing" style="left:{hud.x * 100}%;top:{hud.y * 100}%;opacity:{hud.opacity / 100:.2f};--ui:{ui:.4f}{extra_style}">
  <div class="timing-header">
    <div class="rec-badge"><span class="rec-dot"></span>REC</div>
    {sat_badge}
    <div class="timestamp"{ts_style}>{time_text}</div>
  </div>
  <div class="progress-track"{bar_style}>
    <div class="progress-bar" style="width:{max(0.0, min(1.0, progress)) * 100:.1f}%"></div>
  </div>
  <div class="telemetry-row">
    <span data-telemetry="duration"{dur_style}>耗时 <span class="telemetry-val" id="hud-duration">{duration}</span></span>
    <span data-telemetry="distance"{dist_style}>行程 <span class="telemetry-val" id="hud-distance">{distance}</span></span>
    <span data-telemetry="progress">进度 <span class="telemetry-val accent" id="hud-progress">{percent}</span></span>
  </div>
</div>"""


def _nice_scale_meters(scale_meters: float) -> tuple[int, float]:
    """根据地图比例尺半径挑一个整洁的米制刻度及占卡片宽度的百分比。"""
    scale = max(40.0, float(scale_meters or 220.0))
    target = scale * 0.36
    for nice in (25, 50, 100, 200, 500, 1000, 2000):
        if target <= nice:
            meters = nice
            break
    else:
        meters = 2000
    width_pct = max(12.0, min(42.0, (meters / scale) * 30.0))
    return meters, width_pct


def cycle_overview_blend_factor(
    output_elapsed_sec: float,
    overview_sec: float = 10.0,
    detail_sec: float = 50.0,
    transition_sec: float = 1.8,
) -> float:
    """计算周期性「全程轨迹图 ↔ 局部固定比例尺图」平滑缩放融合权重 (0.0=局部跟随, 1.0=全程总览)。

    - 无论是否倍速播放或倍速导出，均按输出视频时间 output_elapsed_sec 计算周期；
    - 每个周期 (overview_sec + detail_sec) 内，前 overview_sec 秒展示全程轨迹图，后 detail_sec 秒展示用户配置的固定比例尺当前位置图；
    - 在切换交界处使用 Cosine Smoothstep 余弦缓动平滑过渡，避免画面生硬跳变。
    """
    ov = max(0.0, float(overview_sec))
    dt = max(0.0, float(detail_sec))
    if ov <= 0.01:
        return 0.0
    if dt <= 0.01:
        return 1.0
    period = ov + dt
    t = max(0.0, float(output_elapsed_sec)) % period
    trans = min(max(0.25, float(transition_sec)), ov * 0.35, dt * 0.25)
    if t < ov - trans:
        return 1.0
    if t < ov:
        u = (t - (ov - trans)) / max(1e-6, trans)
        return float(0.5 * (1.0 + math.cos(math.pi * u)))
    if t < period - trans:
        return 0.0
    u = (t - (period - trans)) / max(1e-6, trans)
    return float(0.5 * (1.0 - math.cos(math.pi * u)))


def _point_lat_lon_speed(point: Any) -> tuple[float, float, float]:
    """统一提取轨迹点的 (latitude, longitude, speed_kmh)，兼容对象、字典或元组。"""
    if isinstance(point, dict):
        lat = float(point.get("latitude", point.get("lat", 0.0)) or 0.0)
        lon = float(point.get("longitude", point.get("lon", 0.0)) or 0.0)
        spd = float(point.get("speed_kmh", point.get("speedKmh", 0.0)) or 0.0)
        return lat, lon, spd
    if isinstance(point, (tuple, list)):
        if len(point) >= 6:
            return float(point[3] or 0.0), float(point[4] or 0.0), float(point[5] or 0.0)
        if len(point) >= 4:
            return float(point[1] or 0.0), float(point[2] or 0.0), float(point[3] or 0.0)
    lat = float(getattr(point, "latitude", getattr(point, "lat", 0.0)) or 0.0)
    lon = float(getattr(point, "longitude", getattr(point, "lon", 0.0)) or 0.0)
    spd = float(getattr(point, "speed_kmh", getattr(point, "speedKmh", 0.0)) or 0.0)
    return lat, lon, spd


def max_speed_point(points: list[Any] | None) -> Any | None:
    """找出整条轨迹中速度最高（km/h）的采样点，用于在地图上标注最高速度点。"""
    if not points:
        return None
    best = max(points, key=lambda p: _point_lat_lon_speed(p)[2])
    if _point_lat_lon_speed(best)[2] <= 0.5:
        return None
    return best


def deduplicate_radars(radars: list[Any] | None) -> list[tuple[float, float, int]]:
    """对轨迹 JSON 中的测速雷达点按约 30 米网格去重，返回 (lat_gcj, lon_gcj, speed_limit_kmh) 列表。

    注意：手机端写入 `.track.json` 的 `radars` 字段本身已是高德 GCJ-02 坐标，严禁再次调用 wgs84_to_gcj02。
    """
    if not radars:
        return []
    seen: dict[tuple[int, int], tuple[float, float, int]] = {}
    for item in radars:
        if isinstance(item, dict):
            lat = float(item.get("latitude", item.get("lat", 0.0)) or 0.0)
            lon = float(item.get("longitude", item.get("lon", 0.0)) or 0.0)
            spd = int(item.get("speedLimitKmh", item.get("speed_limit_kmh", 0)) or 0)
        elif isinstance(item, (tuple, list)) and len(item) >= 3:
            lat, lon, spd = float(item[0] or 0.0), float(item[1] or 0.0), int(item[2] or 0)
        else:
            lat = float(getattr(item, "latitude", getattr(item, "lat", 0.0)) or 0.0)
            lon = float(getattr(item, "longitude", getattr(item, "lon", 0.0)) or 0.0)
            spd = int(getattr(item, "speed_limit_kmh", getattr(item, "speedLimitKmh", 0)) or 0)
        if not (-90.0 <= lat <= 90.0 and -180.0 <= lon <= 180.0) or (abs(lat) < 1e-5 and abs(lon) < 1e-5):
            continue
        grid_key = (int(round(lon * 3500.0)), int(round(lat * 3500.0)))
        prev = seen.get(grid_key)
        if prev is None or spd > prev[2]:
            seen[grid_key] = (lat, lon, max(0, spd))
    return list(seen.values())


def radar_camera_svg_icon(compact: bool = False) -> str:
    """生成与手机 App `ic_speed_radar.xml` 一致的测速摄像头矢量图标 SVG。"""
    cls = "radar-cam-svg is-compact" if compact else "radar-cam-svg"
    return (
        f'<svg class="{cls}" viewBox="0 0 28 28" aria-hidden="true">'
        '<circle cx="14" cy="14" r="13" fill="#152033"/>'
        '<circle cx="14" cy="14" r="11" fill="#FFFFFF"/>'
        '<path fill="#152033" d="M7.5,10.5h7.8c1.1,0 2,0.9 2,2v3c0,1.1 -0.9,2 -2,2H7.5c-1.1,0 -2,-0.9 -2,-2v-3C5.5,11.4 6.4,10.5 7.5,10.5z"/>'
        '<circle cx="11.4" cy="14" r="2.2" fill="#2E6BFF"/>'
        '<path fill="none" stroke="#FF5A36" stroke-width="1.8" stroke-linecap="round" d="M18.6,11.2c1.5,1.5 1.5,4.1 0,5.6"/>'
        '<path fill="none" stroke="#FF5A36" stroke-width="1.6" stroke-linecap="round" d="M21.0,9.4c2.5,2.5 2.5,6.7 0,9.2"/>'
        "</svg>"
    )


def build_map_overlays_html(
    points: list[Any] | None,
    radars: list[Any] | None,
    origin_x: float,
    origin_y: float,
    span_x: float,
    span_y: float,
    *,
    compact_radar: bool = False,
) -> str:
    """在 0..100% 地图窗口内构建最高速度玫红点与测速雷达限速胶囊 HTML。"""
    if span_x <= 0 or span_y <= 0:
        return ""
    items: list[str] = []
    peak = max_speed_point(points)
    if peak is not None:
        plat, plon, pspd = _point_lat_lon_speed(peak)
        pmx, pmy = amap_mercator(plat, plon)
        px_pct = (pmx - origin_x) / span_x * 100.0
        py_pct = (pmy - origin_y) / span_y * 100.0
        if -8.0 <= px_pct <= 108.0 and -8.0 <= py_pct <= 108.0:
            peak_kmh = int(round(pspd))
            items.append(
                f'<div class="map-max-speed-dot" style="left:{px_pct:.2f}%;top:{py_pct:.2f}%" '
                f'title="最高速度 {peak_kmh} km/h">'
                f'<span class="max-speed-tag">{peak_kmh}</span></div>'
            )
    for r_lat, r_lon, r_spd in deduplicate_radars(radars):
        rmx, rmy = mercator_unit(r_lat, r_lon)
        rx_pct = (rmx - origin_x) / span_x * 100.0
        ry_pct = (rmy - origin_y) / span_y * 100.0
        if -8.0 <= rx_pct <= 108.0 and -8.0 <= ry_pct <= 108.0:
            spd_pill = f'<span class="radar-limit-pill">{r_spd}</span>' if r_spd > 0 else ""
            items.append(
                f'<div class="map-radar-item{" is-compact" if compact_radar else ""}" '
                f'style="left:{rx_pct:.2f}%;top:{ry_pct:.2f}%">'
                f'{radar_camera_svg_icon(compact=compact_radar)}{spd_pill}</div>'
            )
    return "".join(items)


def build_map_widget(
    config: Any,
    address: str,
    route_polyline: str,
    marker_x: float,
    marker_y: float,
    marker_angle: float,
    canvas_width: int = 1920,
    canvas_height: int = 1080,
    map_image: str = "",
    *,
    played_polyline: str = "",
    coordinates: str = "",
    layout_mode: str = "float",
    split_video_percent: int = 38,
    overlays_html: str = "",
    distance_text: str = "",
    speed_kmh: float = 0.0,
    overview_blend: float = 0.0,
) -> str:
    """地图卡片：斜切徽章 + 高德底图 + SVG 发光轨迹 + 导航箭头车标。

    x/y 是整块（徽章+卡片）的中心。有高德图时用真实路网；拉不到瓦片时才退回点阵底。
    轨迹和底图共用同一套墨卡托裁切，不会错位。
    """
    if not config.enabled:
        return ""
    is_split = str(layout_mode or "float") == "split"
    split_pct = max(25, min(65, int(split_video_percent or 38)))
    map_width, map_height, ui = map_card_size(config.size_percent, canvas_width, canvas_height)
    if is_split:
        map_width = float(canvas_width)
        map_height = max(120.0, float(canvas_height) * (100.0 - split_pct) / 100.0)
        ui = max(0.85, min(canvas_width, canvas_height) / 1080.0 * 1.25)
    style = str(getattr(config, "map_style", "") or "satellite")
    if style not in {"satellite", "road"}:
        style = "satellite"
    photo = (
        f'<img class="map-photo" src="{map_image}" alt="" draggable="false">'
        if map_image
        else '<div class="vector-map"></div>'
    )
    raw_marker_color = str(getattr(config, "marker_color", "") or "#00f2ff").strip()
    if raw_marker_color.lower() in {"#000000", "#000", "#1d2a24", "#0b1410"}:
        raw_marker_color = "#00f2ff"
    # 默认琥珀黄在道路浅底上对比度偏弱，升级为醒目的高亮青蓝/亮橙导航色
    marker_color = "#00f2ff" if raw_marker_color.lower() == "#ffb020" else raw_marker_color
    route_color = str(getattr(config, "route_color", "") or "").strip()
    played_color = str(getattr(config, "played_color", "") or "#ffffff").strip()
    var_styles = f";--marker-color:{escape(marker_color)}"
    if is_split:
        var_styles += ";--route-color:#4fd697;--played-color:#ffffff"
    elif route_color and route_color.lower() != "#7fb7ff":
        var_styles += f";--route-color:{escape(route_color)}"
    if played_color and not is_split:
        var_styles += f";--played-color:{escape(played_color)}"
    if is_split:
        var_styles += f";--split-video-pct:{split_pct}%"
    played_node = (
        f'\n        <path class="route-played" d="{played_polyline}" vector-effect="non-scaling-stroke" />'
        if played_polyline
        else ""
    )
    scale_node = ""
    raw_scale = float(getattr(config, "scale_meters", 220) or 0)
    if getattr(config, "show_scale_bar", False) and not is_split:
        if overview_blend >= 0.75 or raw_scale <= 0:
            label = "全程总览"
            bar_w_px = max(28, int(round(map_width * 0.18)))
        else:
            eff_scale = raw_scale * ((8.0) ** max(0.0, min(1.0, overview_blend)))
            meters, bar_pct = _nice_scale_meters(eff_scale)
            label = f"{meters // 1000} km" if meters >= 1000 else f"{meters} m"
            bar_w_px = int(round(bar_pct * map_width / 100.0))
        scale_node = (
            f'\n      <div class="map-scale-bar">'
            f'<span class="map-scale-label">{label}</span>'
            f'<span class="map-scale-line" style="width:{bar_w_px}px"></span>'
            f'</div>'
        )
    coords_node = ""
    if getattr(config, "show_coordinates", False) and coordinates and not is_split:
        coord_color = escape(str(getattr(config, "coordinate_color", "") or "#e2e8f0"))
        raw_csize = float(getattr(config, "coordinate_size", 11) or 11)
        # 兼容旧版 26 默认值，将字号映射为精致小字（6.5px ~ 13px * --ui）
        coord_px = 8.5 if abs(raw_csize - 26.0) < 0.5 else max(6.5, min(14.0, raw_csize * 0.78))
        var_styles += f";--coord-px:{coord_px:.2f}px"
        coords_node = f'\n      <div class="map-coords" id="map-coords" style="color:{coord_color}">{escape(coordinates)}</div>'
    overlays_layer = f'\n      <div class="map-overlays-layer" id="map-overlays-layer">{overlays_html}</div>' if overlays_html is not None else '\n      <div class="map-overlays-layer" id="map-overlays-layer"></div>'
    high_speed_cls = " is-high-speed" if float(speed_kmh or 0.0) > 180.0 else ""
    if is_split:
        dist_pill = (
            f'\n      <div class="split-distance-pill" id="split-distance-pill">'
            f'里程 <strong id="split-distance-text">{escape(distance_text)}</strong></div>'
            if distance_text and distance_text != "—"
            else '\n      <div class="split-distance-pill" id="split-distance-pill" style="display:none">'
            '里程 <strong id="split-distance-text"></strong></div>'
        )
        return f"""
<div class="hud-widget is-split-map{high_speed_cls}" id="widget-map" data-map-style="{style}" data-layout-mode="split" style="left:0%;top:{split_pct:.2f}%;width:100%;height:{100.0 - split_pct:.2f}%;opacity:1;--ui:{ui:.4f}{var_styles}">
  <div class="map-container" style="width:100%;height:100%">
    <div class="map-plot is-{style}">
      {photo}
      <svg class="map-route-line" viewBox="0 0 100 100" preserveAspectRatio="none">
        <path class="route-full" d="{route_polyline}" vector-effect="non-scaling-stroke" />{played_node}
      </svg>{overlays_layer}
      <div class="car-marker car-pulse-marker" style="left:{marker_x:.1f}%;top:{marker_y:.1f}%;transform:translate(-50%,-50%)">
        <span class="pulse-ring"></span>
        <span class="pulse-core"></span>
      </div>{dist_pill}
    </div>
  </div>
</div>"""
    return f"""
<div class="hud-widget{high_speed_cls}" id="widget-map" data-map-style="{style}" data-layout-mode="float" style="left:{config.x * 100}%;top:{config.y * 100}%;opacity:{config.opacity / 100:.2f};--ui:{ui:.4f}{var_styles}">
  <div class="map-badge">
    <div class="map-indicator"></div>
    <span id="map-address">{escape(address)}</span>
  </div>
  <div class="map-container" style="width:{map_width:.0f}px;height:{map_height:.0f}px">
    <div class="map-plot is-{style}">
      {photo}
      <!-- 0..100 已按卡片宽高比分开归一化，铺满时 none 不会把路拉变形。 -->
      <svg class="map-route-line" viewBox="0 0 100 100" preserveAspectRatio="none">
        <path class="route-full" d="{route_polyline}" vector-effect="non-scaling-stroke" />{played_node}
      </svg>{overlays_layer}
      <div class="car-marker" style="left:{marker_x:.1f}%;top:{marker_y:.1f}%;transform:translate(-50%,-50%) rotate({marker_angle:.0f}deg)">
        <svg viewBox="0 0 36 36" aria-hidden="true">
          <circle cx="18" cy="18" r="14" fill="{escape(marker_color)}" fill-opacity="0.24" />
          <path d="M18 3 L30 30 L18 23.5 L6 30 Z" fill="{escape(marker_color)}" stroke="#ffffff" stroke-width="2.6" stroke-linejoin="round" />
          <path d="M18 5.6 L18 23.5 L8.8 28.2 Z" fill="#ffffff" fill-opacity="0.34" />
        </svg>
      </div>{scale_node}{coords_node}
    </div>
  </div>
</div>"""


def _resolve_gauge_value_color(raw_color: str | None) -> str:
    """解析速度表数字颜色：避免黑色或旧默认白字在浅底/反相下看不清，默认采用高对比亮青色。"""
    color = (raw_color or "").strip()
    if not color or color.lower() in {"#000000", "#000", "#ffffff", "#fff", "#0b1410", "#17201e"}:
        return "#00f2ff"
    return color


def build_speed_widget(
    gauge: Any,
    speed_kmh: float,
    canvas_width: int = 1920,
    canvas_height: int = 1080,
) -> str:
    """速度表：按 style 输出参考稿对应的三套结构之一。x/y 是中心。"""
    if not gauge.enabled:
        return ""
    max_speed = max(1, int(getattr(gauge, "max_speed", 300) or 300))
    # 超过最大速度（如 300kph）时，数值照常显示真实车速，但表盘弧度 ratio 夹在 1.0（停在最右侧）
    ratio = max(0.0, min(1.0, speed_kmh / float(max_speed)))
    size, ui = gauge_box_px(gauge.radius_percent, canvas_width, canvas_height)
    value_color = _resolve_gauge_value_color(getattr(gauge, "value_color", None))
    unit_color = str(getattr(gauge, "unit_color", "") or "#cbd5e1").strip()
    arc_color = str(getattr(gauge, "arc_color", "") or "").strip()
    extra_vars = f";--gauge-value-color:{escape(value_color)};--gauge-unit-color:{escape(unit_color)}"
    if arc_color:
        extra_vars += f";--primary-color:{escape(arc_color)}"
    box = f"left:{gauge.x * 100}%;top:{gauge.y * 100}%;opacity:{gauge.opacity / 100:.2f};--ui:{ui:.4f}{extra_vars}"
    number = f"{speed_kmh:.0f}"

    if gauge.style == "aero":
        active = int(round(ratio * AERO_TICK_COUNT))
        ticks = "".join(
            f'<div class="tick{" active" if index < active else ""}"></div>' for index in range(AERO_TICK_COUNT)
        )
        return f"""
<div class="hud-widget" id="widget-speed" style="{box}">
  <div class="style-aero">
    <div class="ladder-ticks">{ticks}</div>
    <div class="aero-numbers">
      <div class="speed-num" style="color:{escape(value_color)}">{number}</div>
      <div class="speed-unit">KM/H HUD</div>
    </div>
  </div>
</div>"""

    if gauge.style == "gt":
        filled = int(round(ratio * GT_BLOCK_COUNT))
        blocks = "".join(
            f'<div class="gt-block{" fill" if index < filled else ""}"></div>'
            for index in range(GT_BLOCK_COUNT)
        )
        return f"""
<div class="hud-widget" id="widget-speed" style="{box}">
  <div class="style-gt">
    <div class="gt-header"><span>TELEMETRY</span><span>{ratio * 100:.0f}%</span></div>
    <div class="speed-num" style="color:{escape(value_color)}">{number}</div>
    <div class="gt-bars">{blocks}</div>
  </div>
</div>"""

    if gauge.style == "circular":
        # 旧圆形表盘：整圈，不套 Forza 的 135° 缺口。
        circumference = 377.0
        offset = circumference * (1 - ratio)
        return f"""
<div class="hud-widget" id="widget-speed" style="{box}">
  <div class="style-circular" style="width:{size}px;height:{size}px">
    <svg class="gauge-svg" viewBox="0 0 160 160">
      <circle class="gauge-bg" cx="80" cy="80" r="60" />
      <circle class="gauge-bar" cx="80" cy="80" r="60" style="stroke-dasharray:{circumference:.0f};stroke-dashoffset:{offset:.1f}" />
    </svg>
    <div class="center-info">
      <div class="speed-num" style="color:{escape(value_color)}">{number}</div>
      <div class="speed-unit">KM/H</div>
    </div>
  </div>
</div>"""

    offset = forza_dash_offset(ratio)
    return f"""
<div class="hud-widget" id="widget-speed" style="{box}">
  <div class="style-forza" style="width:{size}px;height:{size}px">
    <svg class="gauge-svg" viewBox="0 0 180 180">
      <circle class="gauge-bg" cx="90" cy="90" r="75" />
      <circle class="gauge-bar" cx="90" cy="90" r="75" style="stroke-dashoffset:{offset:.1f}" />
    </svg>
    <div class="center-info">
      <div class="speed-num" style="color:{escape(value_color)}">{number}</div>
      <div class="speed-unit">KM/H</div>
      <div class="gear-pill">MAX {max_speed}</div>
    </div>
  </div>
</div>"""


def _hex_to_rgba_css(hex_color: str | None, opacity_percent: int, fallback: tuple[int, int, int] = (13, 17, 23)) -> str:
    """把十六进制颜色和不透明度百分比转成 CSS rgba() 字符串。"""
    text = (hex_color or "").strip().lstrip("#")
    if len(text) == 3:
        text = "".join(ch * 2 for ch in text)
    rgb = fallback
    if len(text) == 6:
        try:
            rgb = (int(text[0:2], 16), int(text[2:4], 16), int(text[4:6], 16))
        except ValueError:
            rgb = fallback
    alpha = max(0.0, min(1.0, float(opacity_percent) / 100.0))
    return f"rgba({rgb[0]}, {rgb[1]}, {rgb[2]}, {alpha:.2f})"


def build_info_bar_widget(info_bar: Any, values: dict[str, str], speed_kmh: float) -> str:
    """底部信息栏组件：开启时在画面底部显示白底/自定义底色的串联字段信息。"""
    if info_bar is None or not getattr(info_bar, "enabled", False):
        return ""
    entries: list[str] = []
    for key in getattr(info_bar, "fields", []) or []:
        text = values.get(key)
        if key == "speed" and not text:
            text = f"{speed_kmh:.1f} km/h"
        if text:
            entries.append(escape(text))
    if not entries:
        return ""
    text_size = max(10, int(getattr(info_bar, "text_size", 30) or 30))
    padding = max(0, int(getattr(info_bar, "padding", 14) or 14))
    opacity = max(0, min(100, int(getattr(info_bar, "opacity", 100) or 100)))
    bg_css = _hex_to_rgba_css(getattr(info_bar, "background_color", "#ffffff"), opacity, (255, 255, 255))
    text_color = escape(str(getattr(info_bar, "text_color", "#17201e") or "#17201e"))
    joined = " &nbsp;·&nbsp; ".join(entries)
    return (
        f'<div class="info-bar" id="widget-infobar" '
        f'style="padding:{padding}px;font-size:{text_size}px;background:{bg_css};color:{text_color}">'
        f'{joined}</div>'
    )


def build_field_widgets(
    fields: list[Any],
    values: dict[str, str],
    speed_kmh: float,
    info_bar: Any = None,
) -> str:
    """独立文字字段：参考稿没有这块，沿用它的面板语言（玻璃底 + 左侧色带）。"""
    parts: list[str] = []
    bar_active = bool(info_bar is not None and getattr(info_bar, "enabled", False))
    bar_fields = set(getattr(info_bar, "fields", []) or []) if bar_active else set()
    for index, item in enumerate(fields):
        if not item.enabled:
            continue
        if bar_active and item.key in bar_fields:
            continue
        text = values.get(item.key)
        if item.key == "speed" and not text:
            text = f"{speed_kmh:.1f} km/h"
        if not text:
            continue
        boxed = " boxed" if item.background else ""
        color = escape(str(getattr(item, "color", "#ffffff") or "#ffffff"))
        extra = f";color:{color}"
        if getattr(item, "background", False):
            bg_hex = str(getattr(item, "background_color", "#000000") or "#000000")
            bg_op = int(getattr(item, "background_opacity", 70) or 70)
            if bg_hex.lower() != "#000000" or bg_op != 70:
                extra += f";background:{_hex_to_rgba_css(bg_hex, bg_op, (13, 17, 23))}"
        parts.append(
            f'<div class="field{boxed}" data-field="{index}" data-field-key="{escape(item.key)}" style="left:{item.x * 100}%;top:{item.y * 100}%;'
            f'font-size:{item.size}px;opacity:{item.opacity / 100:.2f}{extra}">{escape(text)}</div>'
        )
    return "\n".join(parts)


# 纬度每度大约 111.32 公里。经度再乘纬度余弦，东西向和南北向用同一把尺子。
METERS_PER_DEG_LAT = 111_320.0
ROUTE_PAD = 12.0


def mercator_unit(latitude: float, longitude: float) -> tuple[float, float]:
    """Web 墨卡托归一化坐标。入参要已经是和高德瓦片一致的 GCJ-02。"""
    lat = max(-85.05112878, min(85.05112878, latitude))
    x = (longitude + 180.0) / 360.0
    lat_rad = math.radians(lat)
    y = (1.0 - math.log(math.tan(lat_rad) + 1.0 / math.cos(lat_rad)) / math.pi) / 2.0
    return x, y


def wgs84_to_gcj02(latitude: float, longitude: float) -> tuple[float, float]:
    """手机 GPS 是 WGS-84，高德瓦片是 GCJ-02。不转换的话线会整段偏出路面。

    境外坐标原样返回。算法是公开的国测局偏移，和手机上 CoordinateConverter 同一方向。
    """
    if not (73.66 < longitude < 135.05 and 3.86 < latitude < 53.55):
        return latitude, longitude
    a = 6378245.0
    ee = 0.00669342162296594323

    def transform_lat(x: float, y: float) -> float:
        value = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * math.sqrt(abs(x))
        value += (20.0 * math.sin(6.0 * x * math.pi) + 20.0 * math.sin(2.0 * x * math.pi)) * 2.0 / 3.0
        value += (20.0 * math.sin(y * math.pi) + 40.0 * math.sin(y / 3.0 * math.pi)) * 2.0 / 3.0
        value += (160.0 * math.sin(y / 12.0 * math.pi) + 320.0 * math.sin(y * math.pi / 30.0)) * 2.0 / 3.0
        return value

    def transform_lon(x: float, y: float) -> float:
        value = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * math.sqrt(abs(x))
        value += (20.0 * math.sin(6.0 * x * math.pi) + 20.0 * math.sin(2.0 * x * math.pi)) * 2.0 / 3.0
        value += (20.0 * math.sin(x * math.pi) + 40.0 * math.sin(x / 3.0 * math.pi)) * 2.0 / 3.0
        value += (150.0 * math.sin(x / 12.0 * math.pi) + 300.0 * math.sin(x / 30.0 * math.pi)) * 2.0 / 3.0
        return value

    dlat = transform_lat(longitude - 105.0, latitude - 35.0)
    dlon = transform_lon(longitude - 105.0, latitude - 35.0)
    rad = math.radians(latitude)
    magic = 1.0 - ee * math.sin(rad) ** 2
    sqrt_magic = math.sqrt(magic)
    dlat = (dlat * 180.0) / ((a * (1.0 - ee)) / (magic * sqrt_magic) * math.pi)
    dlon = (dlon * 180.0) / (a / sqrt_magic * math.cos(rad) * math.pi)
    return latitude + dlat, longitude + dlon


def amap_mercator(latitude: float, longitude: float) -> tuple[float, float]:
    """GPS 点落到高德瓦片上的墨卡托坐标。"""
    lat, lon = wgs84_to_gcj02(latitude, longitude)
    return mercator_unit(lat, lon)


def route_subset(points: list[Any], limit: int = 0) -> list[Any]:
    """limit<=0 时保留全部点。拐弯被抽成几十个点时，线会抄近道离开路面。"""
    if limit <= 0 or len(points) <= limit:
        return list(points)
    return [points[round(index * (len(points) - 1) / (limit - 1))] for index in range(limit)]


def route_mercator_frame(
    points: list[Any],
    limit: int = 0,
    *,
    scale_meters: float = 0,
    center: Any = None,
    aspect: float | None = None,
    overview_blend: float = 0.0,
) -> tuple[list[Any], float, float, float, float] | None:
    """轨迹落在和卡片同比例的墨卡托窗口里。

    返回子集、左上角 (min_x, min_y)、东西跨度、南北跨度。
    - 当 scale_meters > 0 且 overview_blend == 0 时，窗口以当前点为中心，卡片短边的一半就是这个米数；
    - 当 scale_meters <= 0 或 overview_blend >= 1 时，展示整条轨迹的全貌窗口；
    - 当 0 < overview_blend < 1 时，对局部窗口与全程窗口做对数尺度（Log-scale）跨度插值与平滑中心过渡，
      实现 10s 全程图 ↔ 50s 局部当前位置图的丝滑缩放镜头。
    """
    if len(points) < 2:
        return None
    subset = route_subset(points, limit)
    if aspect is None or aspect <= 0:
        aspect = REFERENCE_MAP_WIDTH / REFERENCE_MAP_HEIGHT
    w = max(0.0, min(1.0, float(overview_blend or 0.0)))
    local_win: tuple[float, float, float, float] | None = None
    if scale_meters and scale_meters > 0 and center is not None:
        # 和圆盘同一口径：scale_meters 是中心到短边边缘的地面距离。
        cx, cy = amap_mercator(center.latitude, center.longitude)
        lat = max(-85.0, min(85.0, float(center.latitude)))
        meters_per_unit = 40075016.686 * math.cos(math.radians(lat))
        span_y = (2.0 * float(scale_meters)) / max(1.0, meters_per_unit)
        span_x = span_y * aspect
        origin_x = cx - span_x / 2.0
        origin_y = cy - span_y / 2.0
        if w <= 1e-4:
            return subset, origin_x, origin_y, span_x, span_y
        local_win = (cx, cy, span_x, span_y)
    coords = [amap_mercator(point.latitude, point.longitude) for point in subset]
    xs = [item[0] for item in coords]
    ys = [item[1] for item in coords]
    min_x, max_x = min(xs), max(xs)
    min_y, max_y = min(ys), max(ys)
    dx = max(max_x - min_x, 1e-12)
    dy = max(max_y - min_y, 1e-12)
    if dx / dy < aspect:
        span_y = dy
        span_x = span_y * aspect
    else:
        span_x = dx
        span_y = span_x / aspect
    # 四周再留一点，车标和线不会贴边。两轴同比例加，窗口比例不变。
    pad_ratio = ROUTE_PAD / (100.0 - ROUTE_PAD * 2)
    span_x *= 1.0 + pad_ratio * 2
    span_y *= 1.0 + pad_ratio * 2
    cx_full = (min_x + max_x) / 2.0
    cy_full = (min_y + max_y) / 2.0
    if local_win is None or w >= 1.0 - 1e-4:
        return subset, cx_full - span_x / 2.0, cy_full - span_y / 2.0, span_x, span_y
    cx0, cy0, sx0, sy0 = local_win
    sx1 = max(sx0 * 1.02, span_x)
    sy1 = max(sy0 * 1.02, span_y)
    blend_sx = sx0 * ((sx1 / max(1e-12, sx0)) ** w)
    blend_sy = sy0 * ((sy1 / max(1e-12, sy0)) ** w)
    w_pos = w ** 0.78
    blend_cx = cx0 + (cx_full - cx0) * w_pos
    blend_cy = cy0 + (cy_full - cy0) * w_pos
    return subset, blend_cx - blend_sx / 2.0, blend_cy - blend_sy / 2.0, blend_sx, blend_sy


def _screen_heading(points: list[Any], center: Any, project: Any) -> float:
    """车标朝向。有时间就用当前点前后一段，否则用轨迹末段。"""
    elapsed = getattr(center, "elapsed_ms", None)
    index = len(points) - 2
    if elapsed is not None:
        index = 0
        for cursor, point in enumerate(points):
            if getattr(point, "elapsed_ms", 0) <= elapsed:
                index = cursor
            else:
                break
        index = max(0, min(index, len(points) - 2))
    ax, ay = project(points[index])
    bx, by = project(points[index + 1])
    aspect = REFERENCE_MAP_WIDTH / REFERENCE_MAP_HEIGHT
    return math.degrees(math.atan2((bx - ax) * aspect, ay - by))


def route_polyline(
    points: list[Any],
    limit: int = 0,
    *,
    scale_meters: float = 0,
    center: Any = None,
    aspect: float | None = None,
    overview_blend: float = 0.0,
) -> tuple[str, float, float, float]:
    """把轨迹点压成 SVG path；返回 path、车标位置和角度。

    x、y 各自归一化到 0..100，对应卡片的宽和高。底图用同一扇窗口，
    铺满悬浮窗时橙色轨迹仍和高德路网对齐。给了比例半径时车标在窗口中心。
    """
    frame = route_mercator_frame(
        points,
        limit,
        scale_meters=scale_meters,
        center=center,
        aspect=aspect,
        overview_blend=overview_blend,
    )
    if frame is None:
        return "", 50.0, 50.0, 0.0
    subset, origin_x, origin_y, span_x, span_y = frame

    def project(point: Any) -> tuple[float, float]:
        x, y = amap_mercator(point.latitude, point.longitude)
        return ((x - origin_x) / span_x * 100.0, (y - origin_y) / span_y * 100.0)

    coords = [project(point) for point in subset]
    path = "M " + " L ".join(f"{x:.2f} {y:.2f}" for x, y in coords)
    if center is not None:
        if scale_meters and scale_meters > 0 and float(overview_blend or 0.0) <= 1e-4:
            marker_x, marker_y = 50.0, 50.0
        else:
            marker_x, marker_y = project(center)
        return path, marker_x, marker_y, _screen_heading(points, center, project)
    last = coords[-1]
    prev = coords[-2]
    aspect = REFERENCE_MAP_WIDTH / REFERENCE_MAP_HEIGHT
    angle = math.degrees(math.atan2((last[0] - prev[0]) * aspect, prev[1] - last[1]))
    return path, last[0], last[1], angle


def route_played_polyline(
    points: list[Any],
    elapsed_ms: int,
    *,
    scale_meters: float = 0,
    center: Any = None,
    aspect: float | None = None,
    overview_blend: float = 0.0,
) -> str:
    """生成截至 elapsed_ms 的已行驶轨迹 SVG path，坐标与 route_polyline 对齐。"""
    frame = route_mercator_frame(
        points,
        scale_meters=scale_meters,
        center=center,
        aspect=aspect,
        overview_blend=overview_blend,
    )
    if frame is None:
        return ""
    _, origin_x, origin_y, span_x, span_y = frame

    def project(point: Any) -> tuple[float, float]:
        x, y = amap_mercator(point.latitude, point.longitude)
        return ((x - origin_x) / span_x * 100.0, (y - origin_y) / span_y * 100.0)

    played = [point for point in points if getattr(point, "elapsed_ms", 0) <= elapsed_ms]
    if center is not None and (not played or getattr(played[-1], "elapsed_ms", -1) != getattr(center, "elapsed_ms", -2)):
        played.append(center)
    if len(played) < 2:
        return ""
    coords = [project(point) for point in played]
    if center is not None and scale_meters and scale_meters > 0 and float(overview_blend or 0.0) <= 1e-4:
        coords[-1] = (50.0, 50.0)
    return "M " + " L ".join(f"{x:.2f} {y:.2f}" for x, y in coords)
