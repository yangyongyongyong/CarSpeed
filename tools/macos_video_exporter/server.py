#!/usr/bin/env python3
"""CarSpeed Studio macOS 本地服务。

首选流程是浏览手机历史行程、拉取原始素材，并用 FilmCraft CLI 生成可直接剪辑的 .fcproj。
旧的 ffmpeg/Pillow 成片接口保留，作为轨迹和字幕扁平化输出的基础。
"""

from __future__ import annotations

import argparse
import bisect
import hashlib
import io
import json
import mimetypes
import math
import os
import re
import shlex
import shutil
import subprocess
import threading
import uuid
import dataclasses
from dataclasses import dataclass, field
from datetime import datetime
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Callable
from urllib.parse import parse_qs, urlparse

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:  # pragma: no cover - 运行时通过 /api/health 暴露
    Image = None
    ImageDraw = None
    ImageFont = None


PUBLIC_REMOTE_ROOT = "/sdcard/DCIM/CarSpeed"
PRIVATE_REMOTE_ROOT = "/sdcard/Android/media/com.thomas.carspeed/CarSpeed"
LEGACY_PUBLIC_REMOTE_ROOT = "/sdcard/Movies/CarSpeed"
LEGACY_PRIVATE_REMOTE_ROOT = "/sdcard/Android/data/com.thomas.carspeed/files/Movies/CarSpeed"
ALL_REMOTE_ROOTS = (
    PUBLIC_REMOTE_ROOT,
    PRIVATE_REMOTE_ROOT,
    LEGACY_PUBLIC_REMOTE_ROOT,
    LEGACY_PRIVATE_REMOTE_ROOT,
)
DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 8765
DEFAULT_FPS = 15
DEFAULT_FILMCRAFT_CLI = "~/.local/bin/filmcraft-cli"
ANDROID_ADB = Path("~/Library/Android/sdk/platform-tools/adb").expanduser()
FILMCRAFT_APP = Path("/Applications/FilmCraft.app")
NATIVE_VIDEO_EXTENSIONS = {".mp4", ".m4v", ".mov", ".3gp"}
# FilmCraft 的时间基是 254016000000 ticks/秒，见 `filmcraft-cli help`。
TICKS_PER_SECOND = 254_016_000_000
TICKS_PER_MS = TICKS_PER_SECOND // 1000
# 字幕轨上相邻两条之间必须留真实空隙，FilmCraft 会拒绝任何时间相交的条目。
CAPTION_GAP_MS = 50
CAPTION_MIN_MS = 200
CAPTION_MAX_MS = 4000
TRIP_FOLDER_RE = re.compile(r"^(?:carspeed-trip-)?(\d{8})-(\d{6})$")
VIDEO_STAMP_RE = re.compile(r"carspeed-(\d{8})-(\d{6})")
DEMO_ITEM_IDS = (5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 21, 27)
TRIP_THUMBNAIL_NAME = "carspeed-trip-thumbnail.jpg"
THUMBNAIL_CACHE_LOCK = threading.Lock()
LAYOUT_CACHE_LOCK = threading.Lock()
DEFAULT_LAYOUT_PATH = Path(__file__).resolve().parent / "layout.json"
LAYOUT_FIELDS = ("time", "speed", "satellites", "coordinates", "address", "focus", "videoMode", "waitTime")
FIELD_LABELS = {
    "time": "时间",
    "speed": "速度",
    "satellites": "卫星数",
    "coordinates": "坐标",
    "address": "地址",
    "focus": "对焦",
    "videoMode": "视频规格",
    "waitTime": "静止等待",
}


@dataclass
class ExportOptions:
    trip: str
    start_ms: int = 0
    end_ms: int | None = None
    playback_speed: float = 1.0
    include_route_map: bool = True
    map_height_percent: int = 28
    map_scale_percent: int = 100
    fps: int = DEFAULT_FPS
    subtitle_time: bool = True
    subtitle_speed: bool = True
    subtitle_gps: bool = True
    subtitle_address: bool = True
    subtitle_focus: bool = True
    subtitle_video_mode: bool = True


@dataclass
class Cue:
    start_ms: int
    end_ms: int
    text: str


@dataclass
class TrackPoint:
    elapsed_ms: int
    time_ms: int
    latitude: float
    longitude: float
    speed_kmh: float


@dataclass
class PulledTrip:
    trip: str
    title: str
    root: Path
    videos: list[Path]
    subtitles: list[Path]
    tracks: list[Path]
    plates: list[Path]
    durations_ms: list[int]
    offsets_ms: list[int]
    total_duration_ms: int


@dataclass
class RemoteSegment:
    name: str
    public_path: str
    track_path: str | None
    subtitle_path: str | None
    plate_path: str | None
    bytes: int
    companion_bytes: int
    has_track: bool
    has_subtitle: bool
    has_plate: bool


@dataclass
class RemoteTrip:
    id: str
    title: str
    started_at_ms: int
    segments: list[RemoteSegment]
    segment_count: int
    track_count: int
    subtitle_count: int
    total_bytes: int
    companion_bytes: int
    thumbnail_path: str | None


@dataclass
class Job:
    id: str
    status: str = "queued"
    progress: int = 0
    message: str = "等待开始"
    phase: str = ""
    output: str | None = None
    outputs: list[str] | None = None
    error: str | None = None
    items: list[dict[str, Any]] | None = None


@dataclass
class TextFieldLayout:
    key: str
    enabled: bool = True
    x: float = 0.5
    y: float = 0.5
    size: int = 42
    opacity: int = 100
    color: str = "#ffffff"
    background: bool = False
    background_color: str = "#000000"
    background_opacity: int = 70
    stroke: bool = True
    stroke_width: int = 3
    stroke_color: str = "#000000"
    align: str = "center"
    radius: int = 10
    padding: int = 12


@dataclass
class GaugeLayout:
    enabled: bool = True
    # circular：圆形表盘；bar：地平线风格的横向速度条。
    style: str = "bar"
    x: float = 0.78
    y: float = 0.80
    radius_percent: float = 0.16
    bar_width: int = 420
    bar_height: int = 34
    bar_segments: int = 28
    opacity: int = 85
    background_color: str = "#0b1410"
    arc_color: str = "#e46b2e"
    value_color: str = "#ffffff"
    unit_color: str = "#9fb3ab"
    max_speed: int = 160
    value_size_percent: float = 0.42
    unit_size_percent: float = 0.16


@dataclass
class InfoBarLayout:
    """底部信息栏：白底 + 深色文字，字段用中点分隔。"""
    enabled: bool = False
    fields: list[str] = field(default_factory=lambda: ["time", "speed", "coordinates", "address"])
    text_size: int = 30
    padding: int = 14
    opacity: int = 100
    background_color: str = "#ffffff"
    text_color: str = "#17201e"


@dataclass
class MapLayerLayout:
    """地平线风格圆形小地图图层，导入 FilmCraft 后可自行调位置、大小和透明度。"""
    enabled: bool = True
    # 圆形小地图的直径占画面短边的比例。地平线风格默认放在右下角。
    size_percent: float = 0.22
    x: float = 0.15
    y: float = 0.72
    opacity: int = 100
    background_color: str = "#1d2a24"
    background_opacity: int = 100
    route_color: str = "#7fb7ff"
    played_color: str = "#ffffff"
    marker_color: str = "#ffb020"
    # 卫星图底图的缩放半径（米）：圆边缘到圆心的真实地面距离。
    scale_meters: int = 220
    show_scale_bar: bool = True
    scale_bar_color: str = "#ffffff"
    map_style: str = "satellite"
    rotate_with_heading: bool = True
    show_speed_label: bool = True
    label_color: str = "#ffffff"
    # 地图旁的速度/经纬度信息，和地图共用位置，可单独关闭。
    show_coordinates: bool = True
    coordinate_size: int = 26
    coordinate_color: str = "#ffffff"


@dataclass
class HudLayout:
    """左上角 HUD：年月日时分秒、驾驶时长、里程进度。"""
    enabled: bool = True
    x: float = 0.055
    y: float = 0.075
    opacity: int = 100
    time_size: int = 38
    duration_size: int = 30
    progress_size: int = 30
    time_format_24h: bool = True
    show_date: bool = True
    show_duration: bool = True
    text_color: str = "#ffffff"
    progress_color: str = "#ffb020"
    background: bool = True
    background_color: str = "#0b1410"
    background_opacity: int = 55
    progress_bar: bool = True
    progress_bar_width: int = 260
    progress_bar_height: int = 8
    progress_bar_track_color: str = "#ffffff"
    show_distance: bool = True


@dataclass
class CaptionLayout:
    """FilmCraft 字幕轨的样式，和排版层一起存进 layout.json。"""
    enabled: bool = True
    font: str = "PingFang SC"
    size: int = 28
    color: str = "#ffffff"
    background_color: str = "#000000b3"
    outline: int = 1
    outline_color: str = "#000000"
    # 距画面底部的留白比例；0.30 能避开右下角速度表。
    margin: float = 0.30


@dataclass
class TripLayout:
    width: int = 1920
    height: int = 1080
    fields: list[TextFieldLayout] = field(default_factory=list)
    gauge: GaugeLayout = field(default_factory=GaugeLayout)
    info_bar: InfoBarLayout = field(default_factory=InfoBarLayout)
    map_layer: MapLayerLayout = field(default_factory=MapLayerLayout)
    hud: HudLayout = field(default_factory=HudLayout)
    caption: CaptionLayout = field(default_factory=CaptionLayout)

    def field(self, key: str) -> TextFieldLayout | None:
        return next((item for item in self.fields if item.key == key), None)


class JobStore:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._jobs: dict[str, Job] = {}

    def create(self) -> Job:
        job = Job(id=uuid.uuid4().hex[:12])
        with self._lock:
            self._jobs[job.id] = job
        return job

    def get(self, job_id: str) -> Job | None:
        with self._lock:
            return self._jobs.get(job_id)

    def update(self, job_id: str, **kwargs: Any) -> None:
        with self._lock:
            job = self._jobs[job_id]
            for key, value in kwargs.items():
                setattr(job, key, value)


JOBS = JobStore()
FILMCRAFT_LOCK = threading.Lock()
FILMCRAFT_PROCESS: subprocess.Popen[bytes] | None = None


def default_layout() -> TripLayout:
    """默认用底部白底信息栏显示主要字段，速度由仪表盘呈现。"""
    presets: dict[str, tuple[float, float, int, bool]] = {
        "time": (0.5, 0.90, 40, True),
        "address": (0.15, 0.56, 34, True),
        "coordinates": (0.14, 0.90, 32, False),
        "satellites": (0.86, 0.90, 32, False),
        "focus": (0.14, 0.95, 30, False),
        "videoMode": (0.86, 0.95, 30, False),
        "waitTime": (0.5, 0.84, 34, False),
        "speed": (0.20, 0.86, 36, False),
    }
    # 信息栏接管这些字段后，独立摆放默认关闭，避免重复显示。
    bar_fields = {"time", "speed", "coordinates", "address"}
    fields = [
        TextFieldLayout(
            key=key,
            # 地址即使关掉底部信息栏也默认单独显示，和 Android app 的街道名一致。
            enabled=(enabled and key not in bar_fields) or key == "address",
            x=x,
            y=y,
            size=size,
            background=True,
        )
        for key, (x, y, size, enabled) in presets.items()
    ]
    return TripLayout(fields=fields)


def load_layout(path: Path | None = None) -> TripLayout:
    target = path or DEFAULT_LAYOUT_PATH
    if not target.is_file():
        return default_layout()
    try:
        data = json.loads(target.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError):
        return default_layout()
    return layout_from_dict(data)


def save_layout(layout: TripLayout, path: Path | None = None) -> Path:
    target = path or DEFAULT_LAYOUT_PATH
    target.write_text(json.dumps(layout_to_dict(layout), ensure_ascii=False, indent=2), encoding="utf-8")
    return target


def _clamp_unit(value: Any, fallback: float) -> float:
    try:
        return max(0.0, min(1.0, float(value)))
    except (TypeError, ValueError):
        return fallback


def _clamp_int(value: Any, low: int, high: int, fallback: int) -> int:
    try:
        return max(low, min(high, int(value)))
    except (TypeError, ValueError):
        return fallback


def layout_from_dict(data: dict[str, Any]) -> TripLayout:
    base = default_layout()
    raw_caption = data.get("caption") if isinstance(data.get("caption"), dict) else {}
    caption = CaptionLayout(
        enabled=bool(raw_caption.get("enabled", base.caption.enabled)),
        font=str(raw_caption.get("font") or base.caption.font),
        size=_clamp_int(raw_caption.get("size"), 10, 96, base.caption.size),
        color=str(raw_caption.get("color") or base.caption.color),
        background_color=str(raw_caption.get("backgroundColor") or base.caption.background_color),
        outline=_clamp_int(raw_caption.get("outline"), 0, 8, base.caption.outline),
        outline_color=str(raw_caption.get("outlineColor") or base.caption.outline_color),
        margin=_clamp_unit(raw_caption.get("margin"), base.caption.margin),
    )
    fields: list[TextFieldLayout] = []
    raw_fields = data.get("fields") if isinstance(data.get("fields"), list) else []
    for raw in raw_fields:
        if not isinstance(raw, dict) or raw.get("key") not in FIELD_LABELS:
            continue
        template = base.field(raw["key"]) or TextFieldLayout(key=raw["key"])
        fields.append(
            TextFieldLayout(
                key=raw["key"],
                enabled=bool(raw.get("enabled", template.enabled)),
                x=_clamp_unit(raw.get("x"), template.x),
                y=_clamp_unit(raw.get("y"), template.y),
                size=_clamp_int(raw.get("size"), 12, 240, template.size),
                opacity=_clamp_int(raw.get("opacity"), 0, 100, template.opacity),
                color=str(raw.get("color") or template.color),
                background=bool(raw.get("background", template.background)),
                background_color=str(raw.get("backgroundColor") or template.background_color),
                background_opacity=_clamp_int(raw.get("backgroundOpacity"), 0, 100, template.background_opacity),
                stroke=bool(raw.get("stroke", template.stroke)),
                stroke_width=_clamp_int(raw.get("strokeWidth"), 0, 24, template.stroke_width),
                stroke_color=str(raw.get("strokeColor") or template.stroke_color),
                align=str(raw.get("align") or template.align),
                radius=_clamp_int(raw.get("radius"), 0, 80, template.radius),
                padding=_clamp_int(raw.get("padding"), 0, 80, template.padding),
            )
        )
    known = {item.key for item in fields}
    fields.extend(item for item in base.fields if item.key not in known)
    gauge_raw = data.get("gauge") if isinstance(data.get("gauge"), dict) else {}
    gauge = GaugeLayout(
        enabled=bool(gauge_raw.get("enabled", True)),
        style=str(gauge_raw.get("style") or "bar"),
        x=_clamp_unit(gauge_raw.get("x"), 0.78),
        y=_clamp_unit(gauge_raw.get("y"), 0.80),
        radius_percent=max(0.05, min(0.45, float(gauge_raw.get("radiusPercent") or 0.16))),
        bar_width=_clamp_int(gauge_raw.get("barWidth"), 120, 1800, 420),
        bar_height=_clamp_int(gauge_raw.get("barHeight"), 8, 160, 34),
        bar_segments=_clamp_int(gauge_raw.get("barSegments"), 4, 80, 28),
        opacity=_clamp_int(gauge_raw.get("opacity"), 0, 100, 85),
        background_color=str(gauge_raw.get("backgroundColor") or "#0b1410"),
        arc_color=str(gauge_raw.get("arcColor") or "#e46b2e"),
        value_color=str(gauge_raw.get("valueColor") or "#ffffff"),
        unit_color=str(gauge_raw.get("unitColor") or "#9fb3ab"),
        max_speed=_clamp_int(gauge_raw.get("maxSpeed"), 20, 400, 160),
        value_size_percent=max(0.1, min(1.0, float(gauge_raw.get("valueSizePercent") or 0.42))),
        unit_size_percent=max(0.05, min(1.0, float(gauge_raw.get("unitSizePercent") or 0.16))),
    )
    bar_raw = data.get("infoBar") if isinstance(data.get("infoBar"), dict) else {}
    raw_bar_fields = bar_raw.get("fields") if isinstance(bar_raw.get("fields"), list) else None
    info_bar = InfoBarLayout(
        enabled=bool(bar_raw.get("enabled", False)),
        fields=[str(name) for name in raw_bar_fields if str(name) in FIELD_LABELS]
        if raw_bar_fields
        else list(InfoBarLayout().fields),
        text_size=_clamp_int(bar_raw.get("textSize"), 10, 120, 30),
        padding=_clamp_int(bar_raw.get("padding"), 0, 80, 14),
        opacity=_clamp_int(bar_raw.get("opacity"), 0, 100, 100),
        background_color=str(bar_raw.get("backgroundColor") or "#ffffff"),
        text_color=str(bar_raw.get("textColor") or "#17201e"),
    )
    map_raw = data.get("mapLayer") if isinstance(data.get("mapLayer"), dict) else {}
    # 旧版地图是左上角矩形卡片；升级成地平线圆形小地图时，旧字段不该把
    # 位置和尺寸继续沿用，否则用户看到的还是旧布局。
    legacy_map = "widthPercent" in map_raw or "followCurrent" in map_raw
    map_layer = MapLayerLayout(
        enabled=bool(map_raw.get("enabled", True)),
        size_percent=max(0.08, min(0.60, float(map_raw.get("sizePercent") or 0.22))) if not legacy_map else 0.22,
        x=0.15 if legacy_map else _clamp_unit(map_raw.get("x"), 0.15),
        y=0.72 if legacy_map else _clamp_unit(map_raw.get("y"), 0.72),
        opacity=_clamp_int(map_raw.get("opacity"), 0, 100, 100),
        background_color="#1d2a24" if legacy_map else str(map_raw.get("backgroundColor") or "#1d2a24"),
        background_opacity=_clamp_int(map_raw.get("backgroundOpacity"), 0, 100, 100),
        route_color="#7fb7ff" if legacy_map else str(map_raw.get("routeColor") or "#7fb7ff"),
        played_color="#ffffff" if legacy_map else str(map_raw.get("playedColor") or "#ffffff"),
        marker_color="#ffb020" if legacy_map else str(map_raw.get("markerColor") or "#ffb020"),
        scale_meters=_clamp_int(map_raw.get("scaleMeters"), 40, 5000, 220),
        show_scale_bar=bool(map_raw.get("showScaleBar", True)),
        scale_bar_color=str(map_raw.get("scaleBarColor") or "#ffffff"),
        map_style=str(map_raw.get("mapStyle") or "satellite"),
        rotate_with_heading=bool(map_raw.get("rotateWithHeading", True)),
        show_speed_label=bool(map_raw.get("showSpeedLabel", True)),
        label_color=str(map_raw.get("labelColor") or "#ffffff"),
        show_coordinates=bool(map_raw.get("showCoordinates", True)),
        coordinate_size=_clamp_int(map_raw.get("coordinateSize"), 10, 100, 26),
        coordinate_color=str(map_raw.get("coordinateColor") or "#ffffff"),
    )
    hud_raw = data.get("hud") if isinstance(data.get("hud"), dict) else {}
    hud = HudLayout(
        enabled=bool(hud_raw.get("enabled", True)),
        x=_clamp_unit(hud_raw.get("x"), 0.055),
        y=_clamp_unit(hud_raw.get("y"), 0.075),
        opacity=_clamp_int(hud_raw.get("opacity"), 0, 100, 100),
        time_size=_clamp_int(hud_raw.get("timeSize"), 12, 160, 38),
        duration_size=_clamp_int(hud_raw.get("durationSize"), 10, 140, 30),
        progress_size=_clamp_int(hud_raw.get("progressSize"), 10, 140, 30),
        time_format_24h=bool(hud_raw.get("timeFormat24h", True)),
        show_date=bool(hud_raw.get("showDate", True)),
        show_duration=bool(hud_raw.get("showDuration", True)),
        text_color=str(hud_raw.get("textColor") or "#ffffff"),
        progress_color=str(hud_raw.get("progressColor") or "#ffb020"),
        background=bool(hud_raw.get("background", True)),
        background_color=str(hud_raw.get("backgroundColor") or "#0b1410"),
        background_opacity=_clamp_int(hud_raw.get("backgroundOpacity"), 0, 100, 55),
        progress_bar=bool(hud_raw.get("progressBar", True)),
        progress_bar_width=_clamp_int(hud_raw.get("progressBarWidth"), 60, 800, 260),
        progress_bar_height=_clamp_int(hud_raw.get("progressBarHeight"), 2, 40, 8),
        progress_bar_track_color=str(hud_raw.get("progressBarTrackColor") or "#ffffff"),
        show_distance=bool(hud_raw.get("showDistance", True)),
    )
    return TripLayout(
        width=_clamp_int(data.get("width"), 320, 7680, base.width),
        height=_clamp_int(data.get("height"), 240, 4320, base.height),
        fields=fields,
        gauge=gauge,
        info_bar=info_bar,
        map_layer=map_layer,
        hud=hud,
        caption=caption,
    )


def layout_to_dict(layout: TripLayout) -> dict[str, Any]:
    return {
        "width": layout.width,
        "height": layout.height,
        "fields": [
            {
                "key": item.key,
                "label": FIELD_LABELS.get(item.key, item.key),
                "enabled": item.enabled,
                "x": item.x,
                "y": item.y,
                "size": item.size,
                "opacity": item.opacity,
                "color": item.color,
                "background": item.background,
                "backgroundColor": item.background_color,
                "backgroundOpacity": item.background_opacity,
                "stroke": item.stroke,
                "strokeWidth": item.stroke_width,
                "strokeColor": item.stroke_color,
                "align": item.align,
                "radius": item.radius,
                "padding": item.padding,
            }
            for item in layout.fields
        ],
        "gauge": {
            "enabled": layout.gauge.enabled,
            "style": layout.gauge.style,
            "x": layout.gauge.x,
            "y": layout.gauge.y,
            "radiusPercent": layout.gauge.radius_percent,
            "barWidth": layout.gauge.bar_width,
            "barHeight": layout.gauge.bar_height,
            "barSegments": layout.gauge.bar_segments,
            "opacity": layout.gauge.opacity,
            "backgroundColor": layout.gauge.background_color,
            "arcColor": layout.gauge.arc_color,
            "valueColor": layout.gauge.value_color,
            "unitColor": layout.gauge.unit_color,
            "maxSpeed": layout.gauge.max_speed,
            "valueSizePercent": layout.gauge.value_size_percent,
            "unitSizePercent": layout.gauge.unit_size_percent,
        },
        "infoBar": {
            "enabled": layout.info_bar.enabled,
            "fields": layout.info_bar.fields,
            "textSize": layout.info_bar.text_size,
            "padding": layout.info_bar.padding,
            "opacity": layout.info_bar.opacity,
            "backgroundColor": layout.info_bar.background_color,
            "textColor": layout.info_bar.text_color,
        },
        "mapLayer": {
            "enabled": layout.map_layer.enabled,
            "sizePercent": layout.map_layer.size_percent,
            "x": layout.map_layer.x,
            "y": layout.map_layer.y,
            "opacity": layout.map_layer.opacity,
            "backgroundColor": layout.map_layer.background_color,
            "backgroundOpacity": layout.map_layer.background_opacity,
            "routeColor": layout.map_layer.route_color,
            "playedColor": layout.map_layer.played_color,
            "markerColor": layout.map_layer.marker_color,
            "scaleMeters": layout.map_layer.scale_meters,
            "showScaleBar": layout.map_layer.show_scale_bar,
            "scaleBarColor": layout.map_layer.scale_bar_color,
            "mapStyle": layout.map_layer.map_style,
            "rotateWithHeading": layout.map_layer.rotate_with_heading,
            "showSpeedLabel": layout.map_layer.show_speed_label,
            "labelColor": layout.map_layer.label_color,
            "showCoordinates": layout.map_layer.show_coordinates,
            "coordinateSize": layout.map_layer.coordinate_size,
            "coordinateColor": layout.map_layer.coordinate_color,
        },
        "hud": {
            "enabled": layout.hud.enabled,
            "x": layout.hud.x,
            "y": layout.hud.y,
            "opacity": layout.hud.opacity,
            "timeSize": layout.hud.time_size,
            "durationSize": layout.hud.duration_size,
            "progressSize": layout.hud.progress_size,
            "timeFormat24h": layout.hud.time_format_24h,
            "showDate": layout.hud.show_date,
            "showDuration": layout.hud.show_duration,
            "textColor": layout.hud.text_color,
            "progressColor": layout.hud.progress_color,
            "background": layout.hud.background,
            "backgroundColor": layout.hud.background_color,
            "backgroundOpacity": layout.hud.background_opacity,
            "progressBar": layout.hud.progress_bar,
            "progressBarWidth": layout.hud.progress_bar_width,
            "progressBarHeight": layout.hud.progress_bar_height,
            "progressBarTrackColor": layout.hud.progress_bar_track_color,
            "showDistance": layout.hud.show_distance,
        },
        "caption": {
            "enabled": layout.caption.enabled,
            "font": layout.caption.font,
            "size": layout.caption.size,
            "color": layout.caption.color,
            "backgroundColor": layout.caption.background_color,
            "outline": layout.caption.outline,
            "outlineColor": layout.caption.outline_color,
            "margin": layout.caption.margin,
        },
    }


def run(command: list[str], timeout: int | None = None) -> str:
    if command and command[0] == "adb":
        ensure_tool_path()
    result = subprocess.run(command, check=False, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
    if result.returncode != 0:
        detail = (result.stderr or result.stdout).strip()
        raise RuntimeError(f"命令失败: {' '.join(command)}\n{detail}")
    return result.stdout


def adb_shell(script: str) -> str:
    # 每次都校准 PATH：GUI 启动或从其他 shell 调用时不一定继承 SDK 工具路径。
    ensure_tool_path()
    return run(["adb", "shell", script], timeout=60)


def check_tools() -> dict[str, str]:
    status: dict[str, str] = {}
    for tool in ("adb", "ffmpeg", "ffprobe", "open"):
        path = shutil.which(tool)
        status[tool] = path or "missing"
    cli = filmcraft_cli_path()
    status["filmcraft-cli"] = str(cli) if cli else "missing"
    status["filmcraft-app"] = str(FILMCRAFT_APP) if FILMCRAFT_APP.exists() else "missing"
    status["pillow"] = "ok" if Image is not None else "missing"
    return status


def ensure_tool_path() -> None:
    """GUI-launched services do not inherit the shell PATH that contains SDK tools."""
    candidates = [ANDROID_ADB.parent, Path("/opt/homebrew/bin"), Path("/usr/local/bin"), Path("~/.local/bin").expanduser()]
    existing = [str(path) for path in candidates if path.is_dir()]
    current = os.environ.get("PATH", "")
    parts = current.split(os.pathsep) if current else []
    additions = [path for path in existing if path not in parts]
    if additions:
        os.environ["PATH"] = os.pathsep.join(additions + parts)


def adb_devices() -> list[str]:
    output = run(["adb", "devices"], timeout=20)
    devices = []
    for line in output.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            devices.append(parts[0])
    return devices


def filmcraft_cli_path() -> Path | None:
    configured = Path(DEFAULT_FILMCRAFT_CLI).expanduser()
    if configured.is_file():
        return configured
    discovered = shutil.which("filmcraft-cli")
    return Path(discovered) if discovered else None


def parse_compact_time_ms(value: str) -> int:
    try:
        return int(datetime.strptime(value, "%Y%m%d-%H%M%S").timestamp() * 1000)
    except ValueError:
        return 0


def remote_inventory(paths: tuple[str, ...]) -> dict[str, int]:
    """Read sizes for all known media roots in one ADB round trip."""
    roots = " ".join(shlex.quote(path) for path in paths)
    script = (
        f"find {roots} -mindepth 1 -maxdepth 2 -type f "
        "-exec stat -c '%n|%s' {} + 2>/dev/null || true"
    )
    inventory: dict[str, int] = {}
    for line in adb_shell(script).splitlines():
        path, separator, size = line.rpartition("|")
        if not separator or not path:
            continue
        try:
            inventory[path] = max(0, int(size))
        except ValueError:
            continue
    return inventory


def trip_folder_names(trip_id: str) -> tuple[str, str]:
    return trip_id, f"carspeed-trip-{trip_id}"


def companion_directories(trip_id: str) -> list[str]:
    plain, legacy = trip_folder_names(trip_id)
    candidates = [
        f"{PUBLIC_REMOTE_ROOT}/{plain}",
        f"{PUBLIC_REMOTE_ROOT}/{legacy}",
        f"{PRIVATE_REMOTE_ROOT}/{plain}",
        f"{PRIVATE_REMOTE_ROOT}/{legacy}",
        f"{LEGACY_PUBLIC_REMOTE_ROOT}/{plain}",
        f"{LEGACY_PUBLIC_REMOTE_ROOT}/{legacy}",
        f"{LEGACY_PRIVATE_REMOTE_ROOT}/{plain}",
        f"{LEGACY_PRIVATE_REMOTE_ROOT}/{legacy}",
    ]
    return list(dict.fromkeys(candidates))


def video_directories(trip_id: str) -> list[str]:
    plain, legacy = trip_folder_names(trip_id)
    return [
        f"{root}/{folder}"
        for root in ALL_REMOTE_ROOTS
        for folder in (plain, legacy)
    ]


def companion_for_stem(inventory: dict[str, int], directories: list[str], stem: str, suffix: str) -> str | None:
    filename = f"{stem}{suffix}"
    for directory in directories:
        candidate = f"{directory}/{filename}"
        if candidate in inventory:
            return candidate
    return None


def segment_stem(name: str) -> str:
    lower = name.lower()
    for suffix in (".track.json", ".plate.json"):
        if lower.endswith(suffix):
            return name[: -len(suffix)]
    return Path(name).stem


def scan_remote_trip(trip_id: str, inventory: dict[str, int] | None = None) -> RemoteTrip:
    if inventory is None:
        inventory = remote_inventory(tuple(f"{directory}" for directory in ALL_REMOTE_ROOTS))
    video_files: list[str] = []
    for directory in video_directories(trip_id):
        video_files.extend(
            path for path in inventory
            if Path(path).parent.as_posix() == directory and Path(path).suffix.lower() in NATIVE_VIDEO_EXTENSIONS
        )
    videos = sorted(dict.fromkeys(video_files))
    if not videos:
        raise RuntimeError(f"行程 {trip_id} 没有视频")

    companion_dirs = companion_directories(trip_id)
    segments: list[RemoteSegment] = []
    for public_path in videos:
        video = Path(public_path).name
        stem = segment_stem(video)
        track_path = companion_for_stem(inventory, companion_dirs, stem, ".track.json")
        subtitle_path = companion_for_stem(inventory, companion_dirs, stem, ".srt")
        plate_path = companion_for_stem(inventory, companion_dirs, stem, ".plate.json")
        segments.append(
            RemoteSegment(
                name=video,
                public_path=public_path,
                track_path=track_path,
                subtitle_path=subtitle_path,
                plate_path=plate_path,
                bytes=inventory.get(public_path, 0),
                companion_bytes=sum(inventory.get(path, 0) for path in (track_path, subtitle_path, plate_path) if path),
                has_track=track_path is not None,
                has_subtitle=subtitle_path is not None,
                has_plate=plate_path is not None,
            )
        )

    match = TRIP_FOLDER_RE.match(trip_id)
    started_at_ms = parse_compact_time_ms(f"{match.group(1)}-{match.group(2)}") if match else 0
    date_label = datetime.fromtimestamp(started_at_ms / 1000).strftime("%Y-%m-%d %H:%M:%S") if started_at_ms else trip_id
    track_count = sum(1 for segment in segments if segment.has_track)
    subtitle_count = sum(1 for segment in segments if segment.has_subtitle)
    thumbnail_path = next(
        (
            path
            for directory in video_directories(trip_id)
            for path in (f"{directory}/{TRIP_THUMBNAIL_NAME}",)
            if path in inventory and inventory[path] > 0
        ),
        None,
    )
    return RemoteTrip(
        id=trip_id,
        title=date_label,
        started_at_ms=started_at_ms,
        segments=segments,
        segment_count=len(segments),
        track_count=track_count,
        subtitle_count=subtitle_count,
        total_bytes=sum(segment.bytes for segment in segments),
        companion_bytes=sum(segment.companion_bytes for segment in segments),
        thumbnail_path=thumbnail_path,
    )


def list_remote_trip_ids() -> list[str]:
    inventory = remote_inventory(ALL_REMOTE_ROOTS)
    return list_remote_trip_ids_from_inventory(inventory)


def list_remote_trip_ids_from_inventory(inventory: dict[str, int]) -> list[str]:
    trip_ids: set[str] = set()
    for path, size in inventory.items():
        if size <= 0 or Path(path).suffix.lower() not in NATIVE_VIDEO_EXTENSIONS:
            continue
        relative = next(
            (
                Path(path).relative_to(Path(root))
                for root in ALL_REMOTE_ROOTS
                if path.startswith(f"{root}/")
            ),
            None,
        )
        if relative is None or len(relative.parts) < 2:
            continue
        match = TRIP_FOLDER_RE.match(relative.parts[0])
        if match:
            trip_ids.add(f"{match.group(1)}-{match.group(2)}")
    return sorted(trip_ids, reverse=True)


def list_trips() -> list[dict[str, Any]]:
    inventory = remote_inventory(ALL_REMOTE_ROOTS)
    trips = [scan_remote_trip(trip_id, inventory) for trip_id in list_remote_trip_ids_from_inventory(inventory)]
    return [
        {
            "id": trip.id,
            "title": trip.title,
            "startedAtMs": trip.started_at_ms,
            "segments": trip.segment_count,
            "tracks": trip.track_count,
            "subtitles": trip.subtitle_count,
            "plates": sum(1 for segment in trip.segments if segment.has_plate),
            "bytes": trip.total_bytes,
            "pullBytes": trip.total_bytes + trip.companion_bytes,
            "videoFiles": [segment.name for segment in trip.segments],
            "trackCoverage": trip.track_count / max(1, trip.segment_count),
            "subtitleCoverage": trip.subtitle_count / max(1, trip.segment_count),
            "thumbnailUrl": f"/api/trips/{trip.id}/thumbnail" if trip.thumbnail_path else None,
        }
        for trip in trips
    ]


def remote_thumbnail(trip_id: str, workspace: Path, remote: RemoteTrip | None = None) -> Path | None:
    remote = remote or scan_remote_trip(trip_id)
    remote_path = remote.thumbnail_path
    if not remote_path:
        return None
    cache_dir = workspace / "thumbnails"
    digest = hashlib.sha1(f"{trip_id}|{remote_path}".encode("utf-8")).hexdigest()[:20]
    local = cache_dir / f"{digest}.jpg"
    if local.is_file() and local.stat().st_size > 0:
        return local
    with THUMBNAIL_CACHE_LOCK:
        if local.is_file() and local.stat().st_size > 0:
            return local
        cache_dir.mkdir(parents=True, exist_ok=True)
        temporary = cache_dir / f".{digest}.{uuid.uuid4().hex[:8]}.tmp"
        try:
            run(["adb", "pull", remote_path, str(temporary)], timeout=90)
            if temporary.stat().st_size <= 0:
                raise RuntimeError("手机缩略图为空")
            temporary.replace(local)
            return local
        finally:
            temporary.unlink(missing_ok=True)


def overlay_preview_image(trip_id: str, workspace: Path, query: dict[str, str]) -> Any:
    """渲染“缩略图 + 排版叠加层”的预览，尺寸与排版画布一致。"""
    assert Image is not None
    layout = load_layout()
    remote = scan_remote_trip(trip_id)
    sample_ms = max(0, int(float(query.get("t") or 0) * 1000))
    values, speed_kmh = sample_trip_values(remote, workspace, sample_ms)
    width, height = layout.width, layout.height

    thumbnail = remote_thumbnail(trip_id, workspace, remote)
    if thumbnail is not None:
        try:
            with Image.open(thumbnail) as handle:
                background = handle.convert("RGB").resize((width, height))
        except Exception:  # noqa: BLE001 - 缩略图损坏时退回纯色底
            background = Image.new("RGB", (width, height), (14, 20, 18))
    else:
        background = Image.new("RGB", (width, height), (14, 20, 18))

    map_points = preview_track_points(remote, workspace)
    overlay = render_overlay_frame(layout, values, speed_kmh, width, height, sample_ms, map_points)
    composed = Image.alpha_composite(background.convert("RGBA"), overlay)
    if map_points:
        map_layer = render_map_layer_frame(layout, map_points, sample_ms, width, height, values)
        composed = Image.alpha_composite(composed, map_layer)
    return composed.convert("RGB")


def preview_track_points(remote: RemoteTrip, workspace: Path) -> list[TrackPoint]:
    """预览用轨迹：从已缓存的 SRT/轨迹里取，够画整条路线即可。"""
    points: list[TrackPoint] = []
    for segment in remote.segments:
        path = segment.track_path or segment.subtitle_path
        if not path:
            continue
        local = adb_pull_cached(path, workspace)
        if local is None:
            continue
        if local.suffix == ".json":
            try:
                data = json.loads(local.read_text(encoding="utf-8", errors="ignore"))
            except json.JSONDecodeError:
                continue
            for item in data.get("points", []):
                lat, lon = item.get("latitude"), item.get("longitude")
                if not isinstance(lat, (int, float)) or not isinstance(lon, (int, float)):
                    continue
                points.append(
                    TrackPoint(
                        elapsed_ms=int(item.get("elapsedMs", 0)),
                        time_ms=int(item.get("timeMs", 0)),
                        latitude=float(lat),
                        longitude=float(lon),
                        speed_kmh=float(item.get("speedKmh", 0.0)),
                    )
                )
        else:
            elapsed = 0
            for cue in parse_srt(local, 0):
                speed = extract_speed_kmh(cue.text)
                match = SRT_COORDINATE_RE.search(cue.text)
                if not match:
                    continue
                points.append(
                    TrackPoint(
                        elapsed_ms=cue.start_ms,
                        time_ms=0,
                        latitude=float(match.group("lat")),
                        longitude=float(match.group("lon")),
                        speed_kmh=speed,
                    )
                )
                elapsed = cue.start_ms
    return sorted(points, key=lambda point: point.elapsed_ms)


def sample_trip_values(
    remote: RemoteTrip,
    workspace: Path,
    sample_ms: int,
) -> tuple[dict[str, str], float]:
    """按时间取出该时刻的字段值和速度；没有字幕时给一组可预览的示例值。"""
    cues: list[Cue] = []
    for segment in remote.segments:
        subtitle_path = segment.subtitle_path
        if not subtitle_path:
            continue
        local = adb_pull_cached(subtitle_path, workspace)
        if local is None:
            continue
        stem = segment_stem(Path(subtitle_path).name)
        offset_ms = segment_offset_ms(remote, segment, stem)
        for cue in parse_srt(local, offset_ms):
            cues.append(cue)
    if not cues:
        return {
            "time": datetime.fromtimestamp(max(1, remote.started_at_ms / 1000)).strftime("%Y-%m-%d %H:%M:%S"),
            "speed": "42.0 km/h",
            "satellites": "GPS卫星：18",
            "coordinates": "30.30408,120.06519",
            "address": "示例路段",
            "focus": "对焦 0.07D(14m) · MF",
            "videoMode": "4K 3840x2160 · 30fps",
            "waitTime": "静止等待：00:00:00",
        }, 42.0
    cues.sort(key=lambda cue: cue.start_ms)
    chosen = next((cue for cue in cues if cue.end_ms > sample_ms), cues[-1])
    values = cue_field_values(chosen.text)
    speed_kmh = extract_speed_kmh(values.get("speed"))
    return values, speed_kmh


def extract_speed_kmh(text: str | None) -> float:
    if not text:
        return 0.0
    match = SRT_SPEED_RE.search(text)
    return float(match.group("speed")) if match else 0.0


def segment_offset_ms(remote: RemoteTrip, segment: RemoteSegment, stem: str) -> int:
    """复用首段时长推算时间轴偏移；拿不到时长就按 0 处理。"""
    index = remote.segments.index(segment)
    if index == 0:
        return 0
    durations = segment_cached_durations_ms(remote)
    if len(durations) != len(remote.segments):
        return 0
    return sum(durations[:index])


def adb_pull_cached(remote_path: str, workspace: Path) -> Path | None:
    cache_dir = workspace / "srt-cache"
    digest = hashlib.sha1(remote_path.encode("utf-8")).hexdigest()[:20]
    local = cache_dir / f"{digest}.srt"
    if local.is_file() and local.stat().st_size > 0:
        return local
    with LAYOUT_CACHE_LOCK:
        if local.is_file() and local.stat().st_size > 0:
            return local
        cache_dir.mkdir(parents=True, exist_ok=True)
        temporary = cache_dir / f".{digest}.{uuid.uuid4().hex[:8]}.tmp"
        try:
            run(["adb", "pull", remote_path, str(temporary)], timeout=120)
            if temporary.stat().st_size <= 0:
                return None
            temporary.replace(local)
            return local
        except Exception:  # noqa: BLE001 - 缓存失败时预览退化为示例值
            return None
        finally:
            temporary.unlink(missing_ok=True)


def segment_cached_durations_ms(remote: RemoteTrip) -> list[int]:
    """用相邻分段的录像起始时间推算每段时长，避免为预览下完整个视频。"""
    starts = [VIDEO_STAMP_RE.search(segment.name) for segment in remote.segments]
    stamps = [
        parse_compact_time_ms(f"{match.group(1)}-{match.group(2)}") if match else 0
        for match in starts
    ]
    durations: list[int] = []
    for index, stamp in enumerate(stamps):
        next_stamp = stamps[index + 1] if index + 1 < len(stamps) else 0
        durations.append(next_stamp - stamp if next_stamp > stamp else 0)
    return durations


def pull_trip(trip: str, work_root: Path, progress: Callable[[int, str], None]) -> PulledTrip:
    remote = scan_remote_trip(trip)
    # 一律用绝对路径：FilmCraft 把路径写进项目后按自己的 cwd 解析，
    # 相对路径会让项目一挪位置就整批素材变成 offline。
    trip_dir = (work_root / safe_name(trip)).resolve()
    raw_dir = trip_dir / "raw"
    raw_dir.mkdir(parents=True, exist_ok=True)

    videos: list[Path] = []
    subtitles: list[Path] = []
    tracks: list[Path] = []
    plates: list[Path] = []
    remote_paths = [segment.public_path for segment in remote.segments]
    remote_paths += [path for segment in remote.segments for path in (segment.track_path, segment.subtitle_path, segment.plate_path) if path]
    total = max(1, len(remote_paths))
    pulled = 0

    progress(5, f"发现 {remote.segment_count} 段录像，准备拉取")
    for segment in remote.segments:
        file_jobs = [
            (segment.public_path, videos),
            (segment.track_path, tracks),
            (segment.subtitle_path, subtitles),
            (segment.plate_path, plates),
        ]
        for remote_path, destination in file_jobs:
            if not remote_path:
                continue
            local = raw_dir / Path(remote_path).name
            if local.exists() and local.stat().st_size > 0:
                destination.append(local)
                pulled += 1
                continue
            progress(8 + pulled * 20 // total, f"拉取 {local.name}")
            run(["adb", "pull", remote_path, str(local)], timeout=600)
            destination.append(local)
            pulled += 1

    videos = sorted(set(videos), key=lambda path: path.name)
    if not videos:
        raise RuntimeError("手机中没有找到可编辑的视频")
    progress(30, "读取视频时长与文件信息")
    durations = [probe_duration_ms(video) for video in videos]
    offsets = running_offsets(durations)
    tracks = hydrate_tracks_from_subtitles(videos, subtitles, tracks, durations, offsets)
    return PulledTrip(
        trip=trip,
        title=remote.title,
        root=trip_dir,
        videos=videos,
        subtitles=sorted(set(subtitles), key=lambda path: path.name),
        tracks=sorted(set(tracks), key=lambda path: path.name),
        plates=sorted(set(plates), key=lambda path: path.name),
        durations_ms=durations,
        offsets_ms=offsets,
        total_duration_ms=max(1, sum(durations)),
    )


SRT_COORDINATE_RE = re.compile(
    r"(?P<lat>-?\d{1,3}\.\d{4,})\s*,\s*(?P<lon>-?\d{1,3}\.\d{4,})"
)
SRT_SPEED_RE = re.compile(r"(?P<speed>\d+(?:\.\d+)?)\s*km/h", re.IGNORECASE)
SRT_TIME_FIELD_RE = re.compile(r"(?P<date>\d{4}-\d{2}-\d{2})\s+(?P<time>\d{2}:\d{2}:\d{2})")


def hydrate_tracks_from_subtitles(
    videos: list[Path],
    subtitles: list[Path],
    tracks: list[Path],
    durations_ms: list[int],
    offsets_ms: list[int],
) -> list[Path]:
    """Fill missing tracks from GPS fields already present in CarSpeed SRT files."""
    track_by_name = {path.name: path for path in tracks}
    subtitle_by_name = {path.name: path for path in subtitles}
    result = list(tracks)
    for index, video in enumerate(videos):
        expected_name = video.with_suffix(".track.json").name
        if expected_name in track_by_name:
            continue
        subtitle = subtitle_by_name.get(video.with_suffix(".srt").name)
        if subtitle is None:
            continue
        points = track_points_from_srt(subtitle, durations_ms[index])
        if len(points) < 2:
            continue
        output = subtitle.parent / expected_name
        payload = {
            "version": 1,
            "videoFileName": video.name,
            "startTimeMs": points[0]["timeMs"],
            "points": points,
            "radars": [],
            "source": "carspeed_srt_fallback",
            "timelineOffsetMs": offsets_ms[index],
        }
        output.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
        result.append(output)
        track_by_name[output.name] = output
    return sorted(dict.fromkeys(result), key=lambda path: path.name)


def track_points_from_srt(path: Path, duration_ms: int) -> list[dict[str, Any]]:
    points: list[dict[str, Any]] = []
    first_wall_ms: int | None = None
    for cue in parse_srt(path, 0):
        coordinate = SRT_COORDINATE_RE.search(cue.text)
        speed = SRT_SPEED_RE.search(cue.text)
        if coordinate is None or speed is None:
            continue
        timestamp = SRT_TIME_FIELD_RE.search(cue.text)
        wall_ms = 0
        if timestamp:
            try:
                wall_ms = int(datetime.strptime(
                    f"{timestamp.group('date')} {timestamp.group('time')}",
                    "%Y-%m-%d %H:%M:%S",
                ).timestamp() * 1000)
            except ValueError:
                wall_ms = 0
        if first_wall_ms is None and wall_ms > 0:
            first_wall_ms = wall_ms
        elapsed_ms = cue.start_ms
        if first_wall_ms is not None and wall_ms > 0:
            elapsed_ms = max(cue.start_ms, wall_ms - first_wall_ms)
        points.append(
            {
                "elapsedMs": min(max(0, elapsed_ms), max(0, duration_ms)),
                "timeMs": wall_ms,
                "latitude": float(coordinate.group("lat")),
                "longitude": float(coordinate.group("lon")),
                "speedKmh": float(speed.group("speed")),
                "accuracyMeters": None,
                "bearingDegrees": None,
            }
        )
    return points


def running_offsets(durations: list[int]) -> list[int]:
    total = 0
    result = []
    for duration in durations:
        result.append(total)
        total += duration
    return result


def probe_duration_ms(video: Path) -> int:
    output = run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", str(video)],
        timeout=60,
    )
    seconds = float(output.strip() or "0")
    return max(1, int(seconds * 1000))


def probe_size(video: Path) -> tuple[int, int]:
    output = run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height", "-of", "csv=s=x:p=0", str(video)],
        timeout=60,
    ).strip()
    width, height = output.split("x")[:2]
    return even(int(width)), even(int(height))


def parse_cli_json(output: str) -> Any:
    """FilmCraft CLI prints one JSON value; tolerate an empty informational prefix."""
    text = output.strip()
    if not text:
        return None
    # 有些命令（例如 captions.setStyle）成功时只打印 null，这同样是一次成功调用。
    if text in {"null", "true", "false"}:
        return json.loads(text)
    for index, character in enumerate(text):
        if character not in "[{":
            continue
        try:
            return json.loads(text[index:])
        except json.JSONDecodeError:
            continue
    raise RuntimeError(f"FilmCraft CLI 返回了无法解析的内容：\n{text}")


def run_filmcraft(cli: Path, args: list[str], timeout: int = 300) -> Any:
    command = [str(cli), "--compact", *args]
    return parse_cli_json(run(command, timeout=timeout))


def project_item_ids(inspection: dict[str, Any]) -> set[int]:
    ids: set[int] = set()

    def walk(node: dict[str, Any]) -> None:
        item_id = node.get("item")
        if isinstance(item_id, int):
            ids.add(item_id)
        for child in node.get("children", []):
            if isinstance(child, dict):
                walk(child)

    root = inspection.get("root")
    if isinstance(root, dict):
        walk(root)
    return ids


def project_bin_ids(inspection: dict[str, Any]) -> set[int]:
    ids: set[int] = set()

    def walk(node: dict[str, Any]) -> None:
        node_id = node.get("id")
        if isinstance(node_id, int) and isinstance(node.get("children"), list) and "item" not in node:
            ids.add(node_id)
        for child in node.get("children", []):
            if isinstance(child, dict):
                walk(child)

    root = inspection.get("root")
    if isinstance(root, dict):
        walk(root)
    return ids


def import_caption_track(
    pulled: PulledTrip,
    cli: Path,
    project_path: Path,
    progress: Callable[[int, str], None],
    layout: TripLayout | None = None,
) -> int | None:
    """把合并后的 CarSpeed 字幕写成 FilmCraft 字幕轨，返回轨道 ID。

    用 `run` 一次提交全部条目：一条命令一次进程的话，十分钟行程近千条字幕
    会白白花掉十几秒的进程启动开销。
    """
    layout = layout or load_layout()
    if not layout.caption.enabled:
        return None
    cues = timeline_cues(pulled)
    if not cues:
        return None
    progress(52, f"导入 {len(cues)} 条字幕到字幕轨")
    track = run_filmcraft(
        cli,
        [
            "--project", str(project_path), "--save", "exec", "captions.newTrack",
            json.dumps({"format": "Subtitle", "name": "CarSpeed GPS", "language": "zh"}),
        ],
    )
    track_id = track.get("track") if isinstance(track, dict) else track
    if not isinstance(track_id, int):
        raise RuntimeError(f"FilmCraft 没有返回字幕轨 ID：{track!r}")

    style = layout.caption
    run_filmcraft(
        cli,
        [
            "--project", str(project_path), "--save", "exec", "captions.setStyle",
            json.dumps(
                {
                    "track": track_id,
                    "font": style.font,
                    "size": style.size,
                    "color": style.color,
                    "background": True,
                    "backgroundColor": style.background_color,
                    "align": "center",
                    "anchor": "bottom",
                    "margin": style.margin,
                    "outline": style.outline,
                    "outlineColor": style.outline_color,
                    "lineSpacing": 1.0,
                },
                ensure_ascii=False,
            ),
        ],
    )

    script: list[str] = []
    for cue in cues:
        script.append(
            json.dumps(
                {
                    "id": "captions.add",
                    "params": {
                        "track": track_id,
                        "text": cue.text.strip(),
                        # captions.add 只认 seconds/durationSeconds；传 time=ticks 会被忽略，
                        # 所有条目都落在播放头位置互相覆盖。
                        "seconds": cue.start_ms / 1000.0,
                        "durationSeconds": (cue.end_ms - cue.start_ms) / 1000.0,
                    },
                },
                ensure_ascii=False,
            )
        )
    script_path = pulled.root / "captions.jsonl"
    script_path.write_text("\n".join(script) + "\n", encoding="utf-8")
    run_filmcraft(
        cli,
        ["--project", str(project_path), "--save", "run", str(script_path)],
        timeout=900,
    )
    return track_id


def import_trip_to_filmcraft(
    pulled: PulledTrip,
    cli: Path,
    project_path: Path,
    progress: Callable[[int, str], None],
) -> dict[str, Any]:
    if not pulled.videos:
        raise RuntimeError("没有可导入 FilmCraft 的视频")
    # 路径写进 FilmCraft 项目后由它自己按 cwd 解析，必须是绝对路径。
    pulled = dataclasses.replace(
        pulled,
        root=pulled.root.resolve(),
        videos=[path.resolve() for path in pulled.videos],
        subtitles=[path.resolve() for path in pulled.subtitles],
        tracks=[path.resolve() for path in pulled.tracks],
        plates=[path.resolve() for path in pulled.plates],
    )
    project_path = project_path.resolve()
    project_path.parent.mkdir(parents=True, exist_ok=True)
    progress(32, "创建 FilmCraft 项目")
    run_filmcraft(cli, ["--demo", "exec", "file.newProject", f"name={safe_name('CarSpeed ' + pulled.title)}"])
    run_filmcraft(cli, ["--demo", "exec", "file.saveAs", f"path={project_path}"])

    inspection = run_filmcraft(cli, ["--project", str(project_path), "inspect", "project"])
    built_in_ids = sorted(project_item_ids(inspection) & set(DEMO_ITEM_IDS))
    if built_in_ids:
        progress(34, "清理 FilmCraft 默认示例素材")
        run_filmcraft(
            cli,
            ["--project", str(project_path), "--save", "exec", "project.delete", f"items={json.dumps(built_in_ids)}"],
        )

    progress(36, f"导入 {len(pulled.videos)} 段手机录像")
    imported = run_filmcraft(
        cli,
        ["--project", str(project_path), "--save", "exec", "file.import", f"paths={json.dumps([str(video) for video in pulled.videos])}"],
        timeout=900,
    )
    item_ids = imported.get("items", []) if isinstance(imported, dict) else []
    if not item_ids:
        raise RuntimeError("FilmCraft 没有返回导入素材 ID")

    bin_result = run_filmcraft(
        cli,
        ["--project", str(project_path), "--save", "exec", "file.newBin", f"name={safe_name('CarSpeed ' + pulled.title)}"],
    )
    trip_bin = bin_result.get("bin") if isinstance(bin_result, dict) else None
    if isinstance(trip_bin, int):
        run_filmcraft(
            cli,
            ["--project", str(project_path), "--save", "exec", "project.moveToBin", f"items={json.dumps(item_ids)}", f"bin={trip_bin}"],
        )

    sequence_name = f"CarSpeed {pulled.title}"
    first_video = pulled.videos[0]
    source_width, source_height = probe_size(first_video)
    # 排版层与序列必须用同一画布，否则透明叠加层会和原视频错位。
    layout = load_layout()
    sequence_width, sequence_height = even(layout.width), even(layout.height)
    if not (source_width >= sequence_width and source_height >= sequence_height):
        sequence_width, sequence_height = even(min(source_width, 3840)), even(min(source_height, 2160))
    # 画布只在本趟行程内降级，不回写 layout.json：
    # 一旦写回，后面拍的非 1080p 行程会把全局排版改成小画布，连带影响之前的设置。
    layout.width, layout.height = sequence_width, sequence_height
    progress(38, "创建 FilmCraft 时间轴")
    if len(item_ids) == 1:
        sequence = run_filmcraft(
            cli,
            [
                "--project", str(project_path), "--save", "exec", "file.newSequenceFromClip",
                f"items={json.dumps(item_ids)}",
            ],
        )
    else:
        sequence = run_filmcraft(
            cli,
            [
                "--project", str(project_path), "--save", "exec", "file.newSequence",
                f"name={safe_name(sequence_name)}",
                # V2 留给透明排版层，否则叠加层没有轨道可放。
                f"width={sequence_width}", f"height={sequence_height}", "fps=30", "video=2", "audio=1",
            ],
        )
    sequence_id = sequence.get("sequence") if isinstance(sequence, dict) else None
    if not isinstance(sequence_id, int):
        raise RuntimeError("FilmCraft 没有返回序列 ID")
    # 单段路径用 newSequenceFromClip 建序列，只会有 V1；排版层需要 V2。
    existing_video_tracks = len(
        run_filmcraft(
            cli,
            ["--project", str(project_path), "inspect", "sequence"],
            timeout=180,
        ).get("video", [])
    )
    if existing_video_tracks < 2:
        run_filmcraft(
            cli,
            ["--project", str(project_path), "--save", "exec", "sequence.addTracks", '{"video":1}'],
            timeout=120,
        )

    if len(item_ids) > 1:
        progress(42, "按录像顺序编排 FilmCraft 时间轴")
        for index, item_id in enumerate(item_ids):
            placement = run_filmcraft(
                cli,
                [
                    "--project", str(project_path), "--save", "exec", "timeline.place",
                    f"item={item_id}", f"time={pulled.offsets_ms[index] * TICKS_PER_MS}",
                    "track=V1", "audio=false",
                ],
            )
            if not isinstance(placement, dict) or not placement.get("clips"):
                raise RuntimeError(f"第 {index + 1} 段没有放入 FilmCraft 时间轴")

    progress(48, "检查 FilmCraft 时间轴和媒体状态")
    sequence_info = run_filmcraft(
        cli,
        ["--project", str(project_path), "inspect", "sequence"],
        timeout=180,
    )
    if sequence_info.get("id") != sequence_id:
        raise RuntimeError("FilmCraft 活动序列与刚创建的序列不一致")
    clip_ids = [
        item.get("clip")
        for track in sequence_info.get("video", [])[:1]
        for item in track.get("items", [])
        if isinstance(item.get("clip"), int)
    ]
    if len(clip_ids) != len(item_ids):
        raise RuntimeError(f"FilmCraft 时间轴片段数不匹配：期望 {len(item_ids)}，实际 {len(clip_ids)}")
    run_filmcraft(
        cli,
        ["--project", str(project_path), "--save", "exec", "clip.fitToFrame", f"clips={json.dumps(clip_ids)}"],
        timeout=300,
    )
    media_status = run_filmcraft(cli, ["--project", str(project_path), "exec", "media.status"], timeout=180)
    offline = [
        item for item in media_status
        if isinstance(item, dict) and item.get("status") in {"missing", "offline", "unreadable"}
    ] if isinstance(media_status, list) else []
    if offline:
        raise RuntimeError("FilmCraft 存在离线素材：" + ", ".join(str(item.get("name")) for item in offline))

    progress(50, "生成透明排版层")
    track_points = read_track_points(pulled)
    overlay_path = generate_overlay_video(pulled, track_points, progress, layout=layout)
    overlay_import = run_filmcraft(
        cli,
        ["--project", str(project_path), "--save", "exec", "file.import", f"paths={json.dumps([str(overlay_path)])}"],
        timeout=900,
    )
    overlay_item = (overlay_import.get("items") or [None])[0] if isinstance(overlay_import, dict) else None
    overlay_clip_id: int | None = None
    if isinstance(overlay_item, int):
        placement = run_filmcraft(
            cli,
            [
                "--project", str(project_path), "--save", "exec", "timeline.place",
                f"item={overlay_item}",
                "track=new",
                "time=0",
                "video=true",
                "audio=false",
            ],
        )
        clips = placement.get("clips") if isinstance(placement, dict) else None
        if isinstance(clips, list) and clips:
            overlay_clip_id = clips[0]
    if overlay_clip_id is None:
        raise RuntimeError("排版层没有放入 FilmCraft 时间轴")

    map_path = generate_map_layer_video(pulled, track_points, progress, layout=layout)
    map_clip_id: int | None = None
    if map_path is not None:
        map_import = run_filmcraft(
            cli,
            ["--project", str(project_path), "--save", "exec", "file.import", f"paths={json.dumps([str(map_path)])}"],
            timeout=900,
        )
        map_item = (map_import.get("items") or [None])[0] if isinstance(map_import, dict) else None
        if isinstance(map_item, int):
            map_placement = run_filmcraft(
                cli,
                [
                    "--project", str(project_path), "--save", "exec", "timeline.place",
                    f"item={map_item}",
                    "track=new",
                    "time=0",
                    "video=true",
                    "audio=false",
                ],
            )
            clips = map_placement.get("clips") if isinstance(map_placement, dict) else None
            if isinstance(clips, list) and clips:
                map_clip_id = clips[0]
        if map_clip_id is None:
            raise RuntimeError("地图图层没有放入 FilmCraft 时间轴")

    # 排版层负责画面上的 HUD/速度表，字幕轨保留原始 GPS 文本，方便在 FilmCraft 里单独开关和改样式。
    caption_track_id = import_caption_track(pulled, cli, project_path, progress, layout=layout)

    manifest = {
        "schemaVersion": 3,
        "tripId": pulled.trip,
        "title": pulled.title,
        "filmcraftProject": str(project_path),
        "sequenceId": sequence_id,
        "sequenceName": sequence_name,
        "segments": [
            {
                "name": video.name,
                "durationMs": pulled.durations_ms[index],
                "timelineOffsetMs": pulled.offsets_ms[index],
                "filmcraftItem": item_ids[index],
            }
            for index, video in enumerate(pulled.videos)
        ],
        "trackCount": len(pulled.tracks),
        "subtitleCount": len(pulled.subtitles),
        "overlayClip": overlay_clip_id,
        "overlayFile": str(overlay_path),
        "mapClip": map_clip_id,
        "mapFile": str(map_path) if map_path else None,
        "captionTrack": caption_track_id,
        "captionFile": str(build_caption_track(pulled) or ""),
        "layout": layout_to_dict(load_layout()),
    }
    manifest_path = pulled.root / "carspeed-edit.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    return manifest


def caption_text(raw: str) -> str:
    """把一行 SRT 压缩成适合字幕轨的短句。

    原始 SRT 是全字段长行（150+ 字符），直接塞进字幕轨会折成三行盖住画面。
    这里只保留时间与速度：地址已经由排版层的独立字段显示，坐标和地图标签
    也各有归属，放进字幕只会和画面上的同类信息重复。
    """
    values = cue_field_values(raw)
    parts = [values.get("time", ""), values.get("speed", "")]
    text = "  ".join(part for part in parts if part).strip()
    return text or raw.strip()


def timeline_cues(pulled: PulledTrip) -> list[Cue]:
    """把所有分段的字幕按时间轴偏移合并成一整条序列字幕。"""
    cues: list[Cue] = []
    for index, video in enumerate(pulled.videos):
        subtitle_name = video.with_suffix(".srt").name
        subtitle = next((path for path in pulled.subtitles if path.name == subtitle_name), None)
        if subtitle is None:
            continue
        offset_ms = pulled.offsets_ms[index]
        duration_ms = pulled.durations_ms[index]
        for cue in parse_srt(subtitle, offset_ms):
            local_start = cue.start_ms - offset_ms
            if local_start >= duration_ms:
                continue
            cues.append(
                Cue(
                    start_ms=max(offset_ms, cue.start_ms),
                    end_ms=min(offset_ms + duration_ms, cue.end_ms),
                    text=caption_text(cue.text),
                )
            )
    return _trim_cues(cues)


def _trim_cues(cues: list[Cue]) -> list[Cue]:
    """按开始时间排序并消除交叠。

    上一段末尾和下一段开头可能撞在一起，而 FilmCraft 连首尾严丝合缝的字幕
    都拒绝，所以每条还要额外留出 CAPTION_GAP_MS 的空隙。
    """
    cues = sorted(cues, key=lambda cue: cue.start_ms)
    trimmed: list[Cue] = []
    for cue in cues:
        if not cue.text.strip() or cue.end_ms <= cue.start_ms:
            continue
        start = max(cue.start_ms, trimmed[-1].end_ms + CAPTION_GAP_MS) if trimmed else cue.start_ms
        end = min(cue.end_ms, start + CAPTION_MAX_MS)
        if end - start >= CAPTION_MIN_MS:
            trimmed.append(Cue(start, end, cue.text))
    return trimmed


def build_caption_track(pulled: PulledTrip) -> Path | None:
    cues = timeline_cues(pulled)
    if not cues:
        return None
    output = pulled.root / "carspeed-timeline.srt"
    with output.open("w", encoding="utf-8") as handle:
        for index, cue in enumerate(cues, start=1):
            handle.write(f"{index}\n")
            handle.write(f"{format_srt_time(cue.start_ms)} --> {format_srt_time(cue.end_ms)}\n")
            handle.write(cue.text.strip() + "\n\n")
    return output


def open_filmcraft_project(project_path: Path, cli: Path, progress: Callable[[int, str], None]) -> None:
    global FILMCRAFT_PROCESS
    progress(52, "打开 FilmCraft")
    if FILMCRAFT_APP.exists():
        # FilmCraft 0.14.0 does not handle macOS open-document Apple Events. Passing
        # the project as a launch argument is the supported path documented in issue #736.
        executable = FILMCRAFT_APP / "Contents" / "MacOS" / "FilmCraft"
        with FILMCRAFT_LOCK:
            if FILMCRAFT_PROCESS is not None and FILMCRAFT_PROCESS.poll() is None:
                FILMCRAFT_PROCESS.terminate()
                try:
                    FILMCRAFT_PROCESS.wait(timeout=8)
                except subprocess.TimeoutExpired:
                    FILMCRAFT_PROCESS.kill()
                    FILMCRAFT_PROCESS.wait(timeout=3)
            FILMCRAFT_PROCESS = subprocess.Popen(
                [str(executable), str(project_path)],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                start_new_session=True,
                cwd=str(project_path.parent),
            )
            try:
                return_code = FILMCRAFT_PROCESS.wait(timeout=2)
            except subprocess.TimeoutExpired:
                return_code = None
            if return_code not in {None, 0}:
                raise RuntimeError(f"FilmCraft 启动失败，退出码 {return_code}")
        return
    run_filmcraft(cli, ["--project", str(project_path), "exec", "file.open", f"path={project_path}"], timeout=60)


def parse_srt(path: Path, offset_ms: int) -> list[Cue]:
    text = path.read_text(encoding="utf-8", errors="ignore").replace("\r\n", "\n")
    cues = []
    for block in re.split(r"\n{2,}", text):
        lines = [line for line in block.splitlines() if line.strip()]
        time_line = next((line for line in lines if "-->" in line), None)
        if not time_line:
            continue
        match = SRT_TIME_RE.search(time_line)
        if not match:
            continue
        body = "\n".join(lines[lines.index(time_line) + 1 :]).replace("  |  ", "\n").strip()
        if not body:
            continue
        start = offset_ms + parse_srt_time(match, 1)
        end = offset_ms + parse_srt_time(match, 5)
        if end > start:
            cues.append(Cue(start, end, body))
    return cues


def parse_srt_time(match: re.Match[str], start_index: int) -> int:
    hours = int(match.group(start_index))
    minutes = int(match.group(start_index + 1))
    seconds = int(match.group(start_index + 2))
    millis = int(match.group(start_index + 3))
    return hours * 3_600_000 + minutes * 60_000 + seconds * 1000 + millis


def build_export_srt(pulled: PulledTrip, options: ExportOptions, out_path: Path) -> Path | None:
    cues: list[Cue] = []
    for index, video in enumerate(pulled.videos):
        subtitle = video.with_suffix(".srt")
        if subtitle.exists():
            cues.extend(parse_srt(subtitle, pulled.offsets_ms[index]))
    selected = []
    start_ms, end_ms = normalized_range(pulled.total_duration_ms, options)
    speed = normalized_speed(options.playback_speed)
    for cue in cues:
        if cue.end_ms <= start_ms or cue.start_ms >= end_ms:
            continue
        lines = [line for line in cue.text.splitlines() if keep_subtitle_line(line, options)]
        if not lines:
            continue
        out_start = int((max(cue.start_ms, start_ms) - start_ms) / speed)
        out_end = int((min(cue.end_ms, end_ms) - start_ms) / speed)
        if out_end > out_start:
            selected.append(Cue(out_start, out_end, "\n".join(lines)))
    if not selected:
        return None
    with out_path.open("w", encoding="utf-8") as handle:
        for index, cue in enumerate(selected, start=1):
            handle.write(f"{index}\n")
            handle.write(f"{format_srt_time(cue.start_ms)} --> {format_srt_time(cue.end_ms)}\n")
            handle.write(cue.text + "\n\n")
    return out_path


def keep_subtitle_line(line: str, options: ExportOptions) -> bool:
    category = subtitle_category(line.strip())
    return {
        "time": options.subtitle_time,
        "speed": options.subtitle_speed,
        "gps": options.subtitle_gps,
        "address": options.subtitle_address,
        "focus": options.subtitle_focus,
        "video": options.subtitle_video_mode,
    }[category]


def subtitle_category(text: str) -> str:
    if re.search(r"\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}", text):
        return "time"
    if "km/h" in text.lower() or "mph" in text.lower():
        return "speed"
    if "静止等待" in text:
        return "waitTime"
    # 经纬度可能有逗号分隔，也可能带 °N/°E 后缀；两种都要认成坐标，
    # 否则后面那句街道名会被挤掉，地址图层就只剩一串数字。
    if text == "GPS --" or re.search(
        r"-?\d{1,3}\.\d{4,}\s*(?:°\s*[NS])?\s*[, ]\s*-?\d{1,3}\.\d{4,}\s*(?:°\s*[EW])?",
        text,
        re.IGNORECASE,
    ):
        return "gps"
    if "GPS卫星" in text or "卫星" in text:
        return "satellites"
    if text.startswith("对焦"):
        return "focus"
    if "fps" in text.lower() or "hdr" in text.lower():
        return "video"
    return "address"


def format_srt_time(ms: int) -> str:
    ms = max(0, ms)
    hours = ms // 3_600_000
    minutes = (ms % 3_600_000) // 60_000
    seconds = (ms % 60_000) // 1000
    millis = ms % 1000
    return f"{hours:02d}:{minutes:02d}:{seconds:02d},{millis:03d}"


def read_track_points(pulled: PulledTrip) -> list[TrackPoint]:
    points: list[TrackPoint] = []
    track_by_name = {path.name: path for path in pulled.tracks}
    for index, video in enumerate(pulled.videos):
        track = track_by_name.get(video.with_suffix(".track.json").name)
        if not track:
            continue
        try:
            data = json.loads(track.read_text(encoding="utf-8", errors="ignore"))
        except json.JSONDecodeError:
            continue
        offset = pulled.offsets_ms[index]
        for item in data.get("points", []):
            lat = item.get("latitude")
            lon = item.get("longitude")
            if not isinstance(lat, (int, float)) or not isinstance(lon, (int, float)):
                continue
            points.append(
                TrackPoint(
                    elapsed_ms=offset + int(item.get("elapsedMs", 0)),
                    time_ms=int(item.get("timeMs", 0)),
                    latitude=float(lat),
                    longitude=float(lon),
                    speed_kmh=float(item.get("speedKmh", 0.0)),
                )
            )
    return sorted(points, key=lambda point: point.elapsed_ms)


def generate_route_video(
    pulled: PulledTrip,
    options: ExportOptions,
    width: int,
    height: int,
    output_duration_ms: int,
    track_points: list[TrackPoint],
    out_path: Path,
    progress: Callable[[int, str], None],
) -> Path | None:
    if not options.include_route_map or len(track_points) < 2:
        return None
    fps = normalized_fps(options.fps)
    frames = max(1, math.ceil(output_duration_ms * fps / 1000))
    start_ms, end_ms = normalized_range(pulled.total_duration_ms, options)
    speed = normalized_speed(options.playback_speed)
    command = [
        "ffmpeg",
        "-y",
        "-f",
        "rawvideo",
        "-pix_fmt",
        "rgb24",
        "-s",
        f"{width}x{height}",
        "-r",
        str(fps),
        "-i",
        "pipe:0",
        "-an",
        "-c:v",
        "h264_videotoolbox",
        "-pix_fmt",
        "yuv420p",
        str(out_path),
    ]
    process = subprocess.Popen(command, stdin=subprocess.PIPE, stderr=subprocess.PIPE)
    assert process.stdin is not None
    try:
        for frame_index in range(frames):
            output_elapsed = frame_index * 1000 / fps
            source_elapsed = min(end_ms - 1, int(start_ms + output_elapsed * speed))
            process.stdin.write(draw_route_frame(width, height, track_points, source_elapsed, options.map_scale_percent))
            if frame_index % fps == 0:
                progress(28 + frame_index * 18 // frames, "生成底部轨迹图")
        process.stdin.close()
        stderr = process.stderr.read().decode("utf-8", errors="ignore") if process.stderr else ""
        code = process.wait(timeout=300)
        if code != 0:
            raise RuntimeError(f"轨迹图视频生成失败\n{stderr}")
        return out_path
    finally:
        if process.poll() is None:
            process.kill()


def draw_route_frame(width: int, height: int, points: list[TrackPoint], elapsed_ms: int, scale_percent: int) -> bytes:
    pixels = bytearray([15, 24, 34]) * (width * height)
    pad = max(12, int(height * 0.16))
    min_lat = min(point.latitude for point in points)
    max_lat = max(point.latitude for point in points)
    min_lon = min(point.longitude for point in points)
    max_lon = max(point.longitude for point in points)
    lat_span = max(0.000001, max_lat - min_lat)
    lon_span = max(0.000001, max_lon - min_lon)
    fit_scale = min((width - pad * 2) / lon_span, (height - pad * 2) / lat_span)
    scale = fit_scale * max(0.6, min(2.2, scale_percent / 100.0))
    center_lat = (min_lat + max_lat) / 2
    center_lon = (min_lon + max_lon) / 2

    def xy(point: TrackPoint) -> tuple[int, int]:
        x = int(width / 2 + (point.longitude - center_lon) * scale)
        y = int(height / 2 - (point.latitude - center_lat) * scale)
        return x, y

    for index in range(1, len(points)):
        draw_line(pixels, width, height, *xy(points[index - 1]), *xy(points[index]), (70, 128, 170), 5)
    played_index = max(1, min(len(points) - 1, last_played_index(points, elapsed_ms)))
    for index in range(1, played_index + 1):
        draw_line(pixels, width, height, *xy(points[index - 1]), *xy(points[index]), (245, 245, 245), 3)
    current = point_at(points, elapsed_ms)
    cx, cy = xy(current)
    draw_circle(pixels, width, height, cx, cy, max(7, height // 22), (79, 214, 151))
    draw_circle_outline(pixels, width, height, cx, cy, max(7, height // 22), (255, 255, 255), 2)
    return bytes(pixels)


def draw_line(buf: bytearray, width: int, height: int, x0: int, y0: int, x1: int, y1: int, color: tuple[int, int, int], stroke: int) -> None:
    dx = abs(x1 - x0)
    dy = -abs(y1 - y0)
    sx = 1 if x0 < x1 else -1
    sy = 1 if y0 < y1 else -1
    err = dx + dy
    while True:
        draw_circle(buf, width, height, x0, y0, max(1, stroke // 2), color)
        if x0 == x1 and y0 == y1:
            break
        e2 = 2 * err
        if e2 >= dy:
            err += dy
            x0 += sx
        if e2 <= dx:
            err += dx
            y0 += sy


def draw_circle(buf: bytearray, width: int, height: int, cx: int, cy: int, radius: int, color: tuple[int, int, int]) -> None:
    r2 = radius * radius
    for y in range(cy - radius, cy + radius + 1):
        if y < 0 or y >= height:
            continue
        for x in range(cx - radius, cx + radius + 1):
            if x < 0 or x >= width:
                continue
            if (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r2:
                set_pixel(buf, width, x, y, color)


def draw_circle_outline(buf: bytearray, width: int, height: int, cx: int, cy: int, radius: int, color: tuple[int, int, int], stroke: int) -> None:
    outer = radius * radius
    inner = max(0, radius - stroke) * max(0, radius - stroke)
    for y in range(cy - radius, cy + radius + 1):
        if y < 0 or y >= height:
            continue
        for x in range(cx - radius, cx + radius + 1):
            if x < 0 or x >= width:
                continue
            d = (x - cx) * (x - cx) + (y - cy) * (y - cy)
            if inner <= d <= outer:
                set_pixel(buf, width, x, y, color)


def set_pixel(buf: bytearray, width: int, x: int, y: int, color: tuple[int, int, int]) -> None:
    offset = (y * width + x) * 3
    buf[offset : offset + 3] = bytes(color)


def point_at(points: list[TrackPoint], elapsed_ms: int) -> TrackPoint:
    if not points:
        raise ValueError("empty route")
    if elapsed_ms <= points[0].elapsed_ms:
        return points[0]
    for index in range(1, len(points)):
        prev = points[index - 1]
        nxt = points[index]
        if elapsed_ms <= nxt.elapsed_ms:
            span = max(1, nxt.elapsed_ms - prev.elapsed_ms)
            ratio = (elapsed_ms - prev.elapsed_ms) / span
            return TrackPoint(
                elapsed_ms=elapsed_ms,
                time_ms=int(prev.time_ms + (nxt.time_ms - prev.time_ms) * ratio),
                latitude=prev.latitude + (nxt.latitude - prev.latitude) * ratio,
                longitude=prev.longitude + (nxt.longitude - prev.longitude) * ratio,
                speed_kmh=prev.speed_kmh + (nxt.speed_kmh - prev.speed_kmh) * ratio,
            )
    return points[-1]


def last_played_index(points: list[TrackPoint], elapsed_ms: int) -> int:
    result = 0
    for index, point in enumerate(points):
        if point.elapsed_ms <= elapsed_ms:
            result = index
        else:
            break
    return result


def compose_video(pulled: PulledTrip, options: ExportOptions, progress: Callable[[int, str], None]) -> Path:
    if Image is None or ImageDraw is None or ImageFont is None:
        raise RuntimeError("缺少 Pillow：请执行 python3 -m pip install pillow")
    progress(25, "准备字幕与轨迹")
    width, height = probe_size(pulled.videos[0])
    start_ms, end_ms = normalized_range(pulled.total_duration_ms, options)
    source_duration_ms = max(1, end_ms - start_ms)
    speed = normalized_speed(options.playback_speed)
    output_duration_ms = max(1, math.ceil(source_duration_ms / speed))
    map_height = even(int(height * max(18, min(45, options.map_height_percent)) / 100)) if options.include_route_map else 0
    track_points = read_track_points(pulled)
    if len(track_points) < 2:
        map_height = 0
    cues = load_filtered_cues(pulled, options)
    list_file = pulled.root / "concat.txt"
    list_file.write_text("".join(f"file {shlex.quote(str(video))}\n" for video in pulled.videos), encoding="utf-8")
    output = pulled.root / f"{safe_name(pulled.trip)}-mac-share-{datetime.now().strftime('%Y%m%d-%H%M%S')}.mp4"
    fps = normalized_fps(options.fps)
    output_width = width
    output_height = height + map_height
    decode_filter = ",".join(
        [
            f"[0:v]trim=start={start_ms / 1000:.3f}:duration={source_duration_ms / 1000:.3f}",
            f"setpts=(PTS-STARTPTS)/{speed:.3f}",
            f"fps={fps}",
            f"scale={width}:{height}",
            "format=rgb24[out]",
        ]
    )
    decode_command = [
        "ffmpeg",
        "-v",
        "error",
        "-hide_banner",
        "-f",
        "concat",
        "-safe",
        "0",
        "-i",
        str(list_file),
        "-filter_complex",
        decode_filter,
        "-map",
        "[out]",
        "-f",
        "rawvideo",
        "pipe:1",
    ]
    encode_command = [
        "ffmpeg",
        "-y",
        "-v",
        "error",
        "-hide_banner",
        "-f",
        "rawvideo",
        "-pix_fmt",
        "rgb24",
        "-s",
        f"{output_width}x{output_height}",
        "-r",
        str(fps),
        "-i",
        "pipe:0",
        "-an",
        "-c:v",
        "h264_videotoolbox",
        "-b:v",
        bitrate(output_width, output_height, fps),
        "-pix_fmt",
        "yuv420p",
        str(output),
    ]
    progress(45, "解码原视频并绘制字幕/轨迹")
    decode = subprocess.Popen(decode_command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    encode = subprocess.Popen(encode_command, stdin=subprocess.PIPE, stderr=subprocess.PIPE)
    assert decode.stdout is not None
    assert encode.stdin is not None
    frame_size = width * height * 3
    total_frames = max(1, math.ceil(output_duration_ms * fps / 1000))
    font = load_font(max(22, int(width * 0.038)))
    frame_index = 0
    try:
        while True:
            raw = decode.stdout.read(frame_size)
            if len(raw) < frame_size:
                break
            output_elapsed = frame_index * 1000 / fps
            source_elapsed = min(end_ms - 1, int(start_ms + output_elapsed * speed))
            frame = Image.frombytes("RGB", (width, height), raw)
            composed = compose_pillow_frame(
                frame=frame,
                output_width=output_width,
                output_height=output_height,
                map_height=map_height,
                subtitle=cue_text_at(cues, source_elapsed),
                track_points=track_points,
                elapsed_ms=source_elapsed,
                map_scale_percent=options.map_scale_percent,
                font=font,
            )
            encode.stdin.write(composed.tobytes())
            frame_index += 1
            if frame_index % fps == 0 or frame_index >= total_frames:
                progress(45 + min(45, frame_index * 45 // total_frames), "使用 macOS VideoToolbox 合成视频")
        encode.stdin.close()
        decode_stderr = decode.stderr.read().decode("utf-8", errors="ignore") if decode.stderr else ""
        encode_stderr = encode.stderr.read().decode("utf-8", errors="ignore") if encode.stderr else ""
        decode_code = decode.wait(timeout=60)
        encode_code = encode.wait(timeout=60)
        if decode_code != 0:
            raise RuntimeError(f"视频解码失败\n{decode_stderr}")
        if encode_code != 0:
            raise RuntimeError(f"视频编码失败\n{encode_stderr}")
    finally:
        if decode.poll() is None:
            decode.kill()
        if encode.poll() is None:
            encode.kill()
    progress(92, "导出完成")
    return output


def load_filtered_cues(pulled: PulledTrip, options: ExportOptions) -> list[Cue]:
    cues: list[Cue] = []
    for index, video in enumerate(pulled.videos):
        subtitle = video.with_suffix(".srt")
        if not subtitle.exists():
            continue
        for cue in parse_srt(subtitle, pulled.offsets_ms[index]):
            lines = [line for line in cue.text.splitlines() if keep_subtitle_line(line, options)]
            if lines:
                cues.append(Cue(cue.start_ms, cue.end_ms, "\n".join(lines)))
    return cues


def cue_text_at(cues: list[Cue], elapsed_ms: int) -> str | None:
    for cue in cues:
        if cue.start_ms <= elapsed_ms < cue.end_ms:
            return cue.text
    return None


def load_font(size: int) -> Any:
    assert ImageFont is not None
    candidates = [
        "/System/Library/Fonts/PingFang.ttc",
        "/System/Library/Fonts/Hiragino Sans GB.ttc",
        "/System/Library/Fonts/STHeiti Light.ttc",
        "/Library/Fonts/Arial Unicode.ttf",
    ]
    for font in candidates:
        if Path(font).exists():
            try:
                return ImageFont.truetype(font, size)
            except Exception:
                pass
    return ImageFont.load_default()


def hex_to_rgb(value: str, fallback: tuple[int, int, int] = (255, 255, 255)) -> tuple[int, int, int]:
    text = (value or "").strip().lstrip("#")
    if len(text) == 3:
        text = "".join(character * 2 for character in text)
    if len(text) != 6:
        return fallback
    try:
        return (int(text[0:2], 16), int(text[2:4], 16), int(text[4:6], 16))
    except ValueError:
        return fallback


def with_alpha(rgb: tuple[int, int, int], opacity_percent: int) -> tuple[int, int, int, int]:
    alpha = int(255 * max(0, min(100, opacity_percent)) / 100)
    return (rgb[0], rgb[1], rgb[2], alpha)


def cue_field_values(text: str) -> dict[str, str]:
    """把 CarSpeed SRT 的单行文本拆成独立字段，供排版层单独摆放。"""
    values: dict[str, str] = {}
    for line in text.replace("\n", "  |  ").split("  |  "):
        stripped = line.strip()
        if not stripped:
            continue
        category = subtitle_category(stripped)
        if category == "waitTime":
            values["waitTime"] = stripped
        elif category == "time":
            values["time"] = stripped
        elif category == "speed":
            values["speed"] = stripped
        elif category == "gps":
            values["coordinates"] = stripped
        elif category == "satellites":
            values["satellites"] = stripped
        elif category == "focus":
            values["focus"] = stripped
        elif category == "video":
            values["videoMode"] = stripped
        else:
            values.setdefault("address", stripped)
    return values


def render_overlay_frame(
    layout: TripLayout,
    values: dict[str, str],
    speed_kmh: float,
    width: int,
    height: int,
    elapsed_ms: int = 0,
    track_points: list["TrackPoint"] | None = None,
) -> Any:
    """按排版设置渲染一帧透明叠加层；透明区域会让下层视频透出来。"""
    assert Image is not None
    assert ImageDraw is not None
    assert ImageFont is not None
    layer = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    if layout.hud.enabled:
        draw_hud(draw, layout.hud, values, elapsed_ms, track_points or [], width, height)
    gauge = layout.gauge
    if gauge.enabled:
        draw_gauge(draw, gauge, speed_kmh, width, height)
    bar = layout.info_bar
    if bar is not None and bar.enabled:
        draw_info_bar(draw, layout.info_bar, values, speed_kmh, width, height)
    for item in layout.fields:
        if not item.enabled:
            continue
        # 信息栏关闭时，独立字段也要照常绘制；只有信息栏真的开着才交给它显示。
        if bar is not None and bar.enabled and item.key in bar.fields:
            continue
        text = values.get(item.key)
        if item.key == "speed" and not text:
            text = f"{speed_kmh:.1f} km/h"
        if not text:
            continue
        draw_text_field(draw, item, text, width, height)
    return layer


_TRACK_PROGRESS_CACHE: dict[tuple[int, int, float, float], tuple[list[float], float]] = {}


def track_progress(points: list[TrackPoint], elapsed_ms: int) -> tuple[float, float, float]:
    """返回（已行驶米数、总米数、进度 0..1）。

    用相邻 GPS 点做球面近似累计，和轨迹回放里的里程口径一致。
    """
    if len(points) < 2:
        return 0.0, 0.0, 0.0
    cache_key = (len(points), points[0].elapsed_ms, points[0].latitude, points[-1].longitude)
    cached = _TRACK_PROGRESS_CACHE.get(cache_key)
    if cached is None or len(cached[0]) != len(points):
        cumulative = [0.0]
        for index in range(1, len(points)):
            cumulative.append(
                cumulative[-1] + haversine_meters(
                    points[index - 1].latitude, points[index - 1].longitude,
                    points[index].latitude, points[index].longitude,
                )
            )
        cached = (cumulative, cumulative[-1])
        _TRACK_PROGRESS_CACHE.clear()
        _TRACK_PROGRESS_CACHE[cache_key] = cached
    cumulative, total = cached
    current_index = last_played_index(points, elapsed_ms)
    travelled = cumulative[min(current_index, len(cumulative) - 1)]
    if current_index < len(points) - 1 and total > 0:
        a, b = points[current_index], points[current_index + 1]
        segment = cumulative[current_index + 1] - cumulative[current_index]
        span = max(1, b.elapsed_ms - a.elapsed_ms)
        ratio = max(0.0, min(1.0, (elapsed_ms - a.elapsed_ms) / span))
        travelled += segment * ratio
    return travelled, total, (travelled / total if total > 0 else 0.0)


def haversine_meters(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    radius = 6_371_000.0
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    d_phi = phi2 - phi1
    d_lambda = math.radians(lon2 - lon1)
    a = math.sin(d_phi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(d_lambda / 2) ** 2
    return 2 * radius * math.asin(min(1.0, math.sqrt(a)))


def draw_hud(
    draw: Any,
    hud: HudLayout,
    values: dict[str, str],
    elapsed_ms: int,
    track_points: list[TrackPoint],
    width: int,
    height: int,
) -> None:
    """左上角 HUD：时刻、驾驶时长、里程进度三行。"""
    time_text = hud_time_text(values, elapsed_ms, hud.time_format_24h, hud.show_date)
    duration_text = hud_duration_text(elapsed_ms)
    travelled, total, ratio = track_progress(track_points, elapsed_ms)
    if hud.show_distance and total > 0:
        progress_text = f"{ratio * 100:.1f}%   {format_distance_m(travelled)} / {format_distance_m(total)}"
    else:
        progress_text = f"{ratio * 100:.1f}%"

    rows: list[tuple[str, Any, tuple[int, int, int]]] = [
        (time_text, load_font(max(10, hud.time_size)), hex_to_rgb(hud.text_color)),
    ]
    if hud.show_duration:
        rows.append((duration_text, load_font(max(9, hud.duration_size)), hex_to_rgb(hud.progress_color)))
    rows.append((progress_text, load_font(max(9, hud.progress_size)), hex_to_rgb(hud.progress_color)))

    measured = []
    for text, font, color in rows:
        box = draw.textbbox((0, 0), text, font=font)
        measured.append((text, font, color, box, box[2] - box[0], box[3] - box[1]))
    bar_width = hud.progress_bar_width if hud.progress_bar else 0
    content_w = max([item[4] for item in measured] + [bar_width])
    gap = max(4, hud.progress_size // 3)
    content_h = sum(item[5] for item in measured) + gap * (len(measured) - 1)
    if hud.progress_bar:
        content_h += gap + hud.progress_bar_height
    pad = max(10, hud.time_size // 3)
    left = int(hud.x * width)
    top = int(hud.y * height)

    if hud.background:
        draw.rounded_rectangle(
            (left - pad, top - pad, left + content_w + pad, top + content_h + pad),
            radius=pad,
            fill=with_alpha(hex_to_rgb(hud.background_color, (11, 20, 16)), hud.background_opacity),
        )
    y = top
    for text, font, color, box, _, row_h in measured:
        draw.text((left - box[0], y - box[1]), text, font=font, fill=with_alpha(color, hud.opacity))
        y += row_h + gap
    if hud.progress_bar:
        bar_y = y
        progress_color = with_alpha(hex_to_rgb(hud.progress_color), hud.opacity)
        track = with_alpha(hex_to_rgb(hud.progress_bar_track_color), int(hud.opacity * 0.35))
        draw.rounded_rectangle(
            (left, bar_y, left + bar_width, bar_y + hud.progress_bar_height),
            radius=max(1, hud.progress_bar_height // 2),
            fill=track,
        )
        filled = max(0, int(bar_width * max(0.0, min(1.0, ratio))))
        if filled > 0:
            draw.rounded_rectangle(
                (left, bar_y, left + filled, bar_y + hud.progress_bar_height),
                radius=max(1, hud.progress_bar_height // 2),
                fill=progress_color,
            )


def hud_time_text(values: dict[str, str], elapsed_ms: int, use_24h: bool, show_date: bool = True) -> str:
    raw = values.get("time")
    if raw:
        match = re.search(r"(\d{4}-\d{2}-\d{2})\s+(\d{2}):(\d{2}):(\d{2})", raw)
        if match:
            date, hour, minute, second = match.groups()
            if use_24h:
                clock = f"{hour}:{minute}:{second}"
            else:
                suffix = "AM" if int(hour) < 12 else "PM"
                hour12 = int(hour) % 12 or 12
                clock = f"{hour12:02d}:{minute}:{second} {suffix}"
            return f"{date} {clock}" if show_date else clock
    seconds = max(0, elapsed_ms // 1000)
    clock = f"{seconds // 3600:02d}:{(seconds % 3600) // 60:02d}:{seconds % 60:02d}"
    return clock


def hud_duration_text(elapsed_ms: int) -> str:
    """驾驶时长：时分秒，超过 99 小时也不截断。"""
    seconds = max(0, int(elapsed_ms // 1000))
    hours = seconds // 3600
    return f"{hours:02d}:{(seconds % 3600) // 60:02d}:{seconds % 60:02d}"


def format_distance_m(meters: float) -> str:
    if meters >= 1000:
        return f"{meters / 1000:.1f} km"
    return f"{int(meters)} m"


def draw_info_bar(
    draw: Any,
    bar: InfoBarLayout,
    values: dict[str, str],
    speed_kmh: float,
    width: int,
    height: int,
) -> None:
    """底部纯白信息栏：白底 + 深色文字，模仿 Android App 播放器样式。"""
    font = load_font(max(10, bar.text_size))
    entries: list[str] = []
    for key in bar.fields:
        text = values.get(key)
        if key == "speed" and not text:
            text = f"{speed_kmh:.1f} km/h"
        if text:
            entries.append(text)
    if not entries:
        return
    separator = "   ·   "
    line = separator.join(entries)
    left, top, right, bottom = draw.textbbox((0, 0), line, font=font)
    text_width = right - left
    text_height = bottom - top
    bar_height = text_height + bar.padding * 2
    bar_top = height - bar_height
    draw.rectangle((0, bar_top, width, height), fill=with_alpha(hex_to_rgb(bar.background_color, (255, 255, 255)), bar.opacity))
    text_x = bar.padding - left
    text_y = bar_top + bar.padding - top
    draw.text((text_x, text_y), line, font=font, fill=with_alpha(hex_to_rgb(bar.text_color, (23, 32, 30)), 100))


def draw_text_field(
    draw: Any,
    item: TextFieldLayout,
    text: str,
    width: int,
    height: int,
) -> None:
    font = load_font(max(8, item.size))
    left, top, right, bottom = draw.textbbox((0, 0), text, font=font)
    text_width = right - left
    text_height = bottom - top
    center_x = item.x * width
    anchor_y = item.y * height
    padding = item.padding
    box_width = text_width + padding * 2
    box_height = text_height + padding * 2
    box_left = center_x - box_width / 2
    box_top = anchor_y - box_height / 2
    if item.background:
        draw.rounded_rectangle(
            (box_left, box_top, box_left + box_width, box_top + box_height),
            radius=item.radius,
            fill=with_alpha(hex_to_rgb(item.background_color, (0, 0, 0)), item.background_opacity),
        )
    text_x = box_left + padding - left
    text_y = box_top + padding - top
    if item.stroke and item.stroke_width > 0:
        stroke_rgb = hex_to_rgb(item.stroke_color, (0, 0, 0))
        draw.text(
            (text_x, text_y),
            text,
            font=font,
            fill=with_alpha(hex_to_rgb(item.color), item.opacity),
            stroke_width=item.stroke_width,
            stroke_fill=with_alpha(stroke_rgb, item.opacity),
        )
    else:
        draw.text((text_x, text_y), text, font=font, fill=with_alpha(hex_to_rgb(item.color), item.opacity))


def draw_gauge(
    draw: Any,
    gauge: GaugeLayout,
    speed_kmh: float,
    width: int,
    height: int,
) -> None:
    """按配置选择圆形表盘或横向速度条。"""
    if gauge.style == "bar":
        draw_gauge_bar(draw, gauge, speed_kmh, width, height)
        return
    radius = gauge.radius_percent * min(width, height)
    center_x = gauge.x * width
    center_y = gauge.y * height
    box = (center_x - radius, center_y - radius, center_x + radius, center_y + radius)
    background_alpha = int(255 * (max(0, min(100, gauge.opacity)) / 100) * 0.9)
    background_rgb = hex_to_rgb(gauge.background_color, (11, 20, 16))
    draw.ellipse(box, fill=(background_rgb[0], background_rgb[1], background_rgb[2], background_alpha))

    ratio = max(0.0, min(1.0, speed_kmh / max(1.0, float(gauge.max_speed))))
    arc_rgb = hex_to_rgb(gauge.arc_color, (228, 107, 46))
    arc_width = max(3, int(radius * 0.12))
    draw.arc(
        box,
        start=140,
        end=400,
        fill=(arc_rgb[0], arc_rgb[1], arc_rgb[2], 90),
        width=arc_width,
    )
    draw.arc(
        box,
        start=140,
        end=140 + int(260 * ratio),
        fill=with_alpha(arc_rgb, 100),
        width=arc_width,
    )

    value_font = load_font(max(10, int(radius * gauge.value_size_percent)))
    value_text = f"{speed_kmh:.0f}"
    unit_font = load_font(max(8, int(radius * gauge.unit_size_percent)))
    unit_text = "km/h"
    v_left, v_top, v_right, v_bottom = draw.textbbox((0, 0), value_text, font=value_font)
    u_left, u_top, u_right, u_bottom = draw.textbbox((0, 0), unit_text, font=unit_font)
    value_size = (v_right - v_left, v_bottom - v_top)
    unit_size = (u_right - u_left, u_bottom - u_top)
    block_height = value_size[1] + unit_size[1] + radius * 0.06
    value_x = center_x - value_size[0] / 2 - v_left
    value_y = center_y - block_height / 2 - v_top
    draw.text((value_x, value_y), value_text, font=value_font, fill=with_alpha(hex_to_rgb(gauge.value_color), 100))
    unit_x = center_x - unit_size[0] / 2 - u_left
    unit_y = value_y + value_size[1] + radius * 0.06 - u_top
    draw.text((unit_x, unit_y), unit_text, font=unit_font, fill=with_alpha(hex_to_rgb(gauge.unit_color), 100))


def draw_gauge_bar(
    draw: Any,
    gauge: GaugeLayout,
    speed_kmh: float,
    width: int,
    height: int,
) -> None:
    """地平线风格横向速度条：分段填充 + 左侧大号数字 + 右侧单位。"""
    bar_w = max(120, gauge.bar_width)
    bar_h = max(8, gauge.bar_height)
    gap = max(2, bar_h // 6)
    segments = max(4, gauge.bar_segments)
    seg_w = max(2.0, (bar_w - gap * (segments - 1)) / segments)
    center_x = gauge.x * width
    center_y = gauge.y * height
    left = center_x - bar_w / 2
    top = center_y - bar_h / 2

    value_font = load_font(max(12, int(bar_h * 1.55)))
    unit_font = load_font(max(9, int(bar_h * 0.66)))
    value_text = f"{speed_kmh:.0f}"
    unit_text = "km/h"
    value_box = draw.textbbox((0, 0), value_text, font=value_font)
    unit_box = draw.textbbox((0, 0), unit_text, font=unit_font)
    value_w = value_box[2] - value_box[0]
    unit_w = unit_box[2] - unit_box[0]
    label_gap = max(6, bar_h // 4)
    label_w = value_w + label_gap + unit_w
    padding = max(10, bar_h // 2)

    track_rgb = hex_to_rgb(gauge.background_color, (11, 20, 16))
    draw.rounded_rectangle(
        (
            left - padding,
            top - padding,
            left + bar_w + padding,
            top + bar_h + padding,
        ),
        radius=int(bar_h * 0.9),
        fill=(track_rgb[0], track_rgb[1], track_rgb[2], int(255 * gauge.opacity / 100 * 0.55)),
    )
    ratio = max(0.0, min(1.0, speed_kmh / max(1.0, float(gauge.max_speed))))
    lit_segments = ratio * segments
    arc_rgb = hex_to_rgb(gauge.arc_color, (228, 107, 46))
    empty_rgb = hex_to_rgb(gauge.unit_color, (159, 179, 171))
    for index in range(segments):
        x0 = left + index * (seg_w + gap)
        x1 = x0 + seg_w
        fill_amount = max(0.0, min(1.0, lit_segments - index))
        draw.rounded_rectangle(
            (x0, top, x1, top + bar_h),
            radius=max(1, bar_h // 3),
            fill=with_alpha(empty_rgb, int(gauge.opacity * 0.22)),
        )
        if fill_amount > 0:
            draw.rounded_rectangle(
                (x0, top, x0 + (x1 - x0) * fill_amount, top + bar_h),
                radius=max(1, bar_h // 3),
                fill=with_alpha(arc_rgb, gauge.opacity),
            )

    label_x = left - padding - label_w
    value_y = center_y - (value_box[3] - value_box[1]) / 2 - value_box[1]
    draw.text(
        (label_x - value_box[0], value_y),
        value_text,
        font=value_font,
        fill=with_alpha(hex_to_rgb(gauge.value_color), gauge.opacity),
    )
    unit_y = value_y + (value_box[3] - value_box[1]) + label_gap / 2 - unit_box[1]
    draw.text(
        (label_x - unit_box[0], unit_y),
        unit_text,
        font=unit_font,
        fill=with_alpha(hex_to_rgb(gauge.unit_color), gauge.opacity),
    )


def layout_cues(pulled: PulledTrip) -> list[Cue]:
    cues: list[Cue] = []
    for index, video in enumerate(pulled.videos):
        subtitle = video.with_suffix(".srt")
        if not subtitle.exists():
            continue
        for cue in parse_srt(subtitle, pulled.offsets_ms[index]):
            cues.append(Cue(cue.start_ms, cue.end_ms, cue.text))
    return sorted(cues, key=lambda cue: cue.start_ms)


def generate_map_layer_video(
    pulled: PulledTrip,
    track_points: list[TrackPoint],
    progress: Callable[[int, str], None] | None = None,
    layout: TripLayout | None = None,
) -> Path | None:
    """把轨迹地图渲染成独立的透明图层，导入 FilmCraft 后可自行缩放和摆位。

    layout 由调用方传入本趟行程解析好的画布，避免这里重新读取 layout.json
    拿到未经降级的尺寸、和序列对不上。
    """
    assert Image is not None
    if layout is None:
        layout = load_layout()
    if not layout.map_layer.enabled or len(track_points) < 2:
        return None
    width, height = layout.width, layout.height
    fps = 1
    total_duration_ms = max(1, pulled.total_duration_ms)
    total_frames = max(1, math.ceil(total_duration_ms * fps / 1000))
    out_path = pulled.root / "carspeed-map.mov"
    command = [
        "ffmpeg", "-y", "-v", "error", "-hide_banner",
        "-f", "rawvideo", "-pix_fmt", "rgba",
        "-s", f"{width}x{height}", "-r", str(fps), "-i", "pipe:0",
        "-c:v", "prores_ks", "-profile:v", "4444",
        "-pix_fmt", "yuva444p10le", "-alpha_bits", "8",
        str(out_path),
    ]
    process = subprocess.Popen(command, stdin=subprocess.PIPE, stderr=subprocess.PIPE)
    assert process.stdin is not None
    cues = layout_cues(pulled)
    try:
        for frame_index in range(total_frames):
            elapsed_ms = frame_index * 1000 / fps
            layer = render_map_layer_frame(layout, track_points, elapsed_ms, width, height, cue_values_at(cues, elapsed_ms) or {})
            process.stdin.write(layer.tobytes())
            if progress and frame_index % fps == 0:
                percent = 40 + int(50 * frame_index / max(1, total_frames))
                progress(min(92, percent), f"渲染地图图层 {frame_index}/{total_frames}")
    finally:
        process.stdin.close()
        stderr = process.stderr.read().decode("utf-8", errors="ignore") if process.stderr else ""
        code = process.wait(timeout=1800)
    if code != 0 or not out_path.is_file() or out_path.stat().st_size <= 0:
        raise RuntimeError(f"地图图层编码失败\n{stderr}")
    return out_path


def render_map_layer_frame(
    layout: TripLayout,
    points: list[TrackPoint],
    elapsed_ms: int,
    width: int,
    height: int,
    values: dict[str, str] | None = None,
) -> Any:
    """地平线风格圆形小地图：整帧透明，只有右下角的圆盘有内容。"""
    assert Image is not None
    assert ImageDraw is not None
    layer = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    config = layout.map_layer
    if len(points) < 2:
        return layer
    diameter = max(80, int(min(width, height) * config.size_percent))
    center_x = int(config.x * width)
    center_y = int(config.y * height)
    radius = diameter / 2
    left = center_x - diameter // 2
    top = center_y - diameter // 2

    # 圆形内的地图画布：底图 + 轨迹 + 箭头，最后统一用圆形蒙版贴回整帧。
    circle = Image.new("RGBA", (diameter, diameter), (0, 0, 0, 0))
    draw_map_satellite_background(circle, config, points, elapsed_ms)
    draw_map_circle_content(circle, config, points, elapsed_ms, diameter)

    circle_mask = Image.new("L", (diameter, diameter), 0)
    ImageDraw.Draw(circle_mask).ellipse((0, 0, diameter - 1, diameter - 1), fill=255)
    layer.paste(circle, (left, top), circle_mask)

    ring = ImageDraw.Draw(layer)
    ring_width = max(2, diameter // 90)
    ring.ellipse(
        (left, top, left + diameter, top + diameter),
        outline=with_alpha((255, 255, 255), 210),
        width=ring_width,
    )
    if config.show_scale_bar:
        draw_map_scale_bar(ring, config, left, top, diameter)
    if config.show_speed_label:
        font = load_font(max(10, diameter // 13))
        label = f"{point_at(points, elapsed_ms).speed_kmh:.0f} km/h"
        box = ring.textbbox((0, 0), label, font=font)
        text_width = box[2] - box[0]
        text_height = box[3] - box[1]
        text_x = left + (diameter - text_width) / 2 - box[0]
        text_y = top + diameter - text_height - diameter * 0.11
        ring.rounded_rectangle(
            (
                left + diameter * 0.5 - text_width / 2 - diameter * 0.05,
                text_y + box[1] - diameter * 0.035,
                left + diameter * 0.5 + text_width / 2 + diameter * 0.05,
                text_y + box[3] + diameter * 0.035,
            ),
            radius=int(diameter * 0.05),
            fill=(0, 0, 0, 120),
        )
        ring.text((text_x, text_y), label, font=font, fill=with_alpha(hex_to_rgb(config.label_color), 255))
    if config.show_coordinates:
        coordinate_text = (values or {}).get("coordinates")
        if not coordinate_text and points:
            current = point_at(points, elapsed_ms)
            coordinate_text = f"{current.latitude:.5f}°N {current.longitude:.5f}°E"
        if coordinate_text:
            draw_map_coordinates(ring, config, coordinate_text, left, top, diameter)
    return layer


def draw_map_coordinates(
    draw: Any,
    config: MapLayerLayout,
    text: str,
    circle_left: int,
    circle_top: int,
    diameter: int,
) -> None:
    """把经纬度放在圆盘正下方，宽度不够时按圆盘宽度折行。"""
    normalized = text.replace(",", " ").replace("  ", " ").strip()
    match = re.search(r"(-?\d+(?:\.\d+)?)\s*°?\s*([NS])?\s+(-?\d+(?:\.\d+)?)\s*°?\s*([EW])?", normalized)
    if match:
        lat, lat_hemi, lon, lon_hemi = match.groups()
        normalized = f"{abs(float(lat)):.5f}°{lat_hemi or ('S' if float(lat) < 0 else 'N')}  {abs(float(lon)):.5f}°{lon_hemi or ('W' if float(lon) < 0 else 'E')}"
    font = load_font(max(9, config.coordinate_size))
    box = draw.textbbox((0, 0), normalized, font=font)
    text_w = box[2] - box[0]
    text_h = box[3] - box[1]
    center_x = circle_left + diameter / 2
    text_x = center_x - text_w / 2 - box[0]
    y = circle_top + diameter + max(4, diameter * 0.03)
    draw.rounded_rectangle(
        (
            center_x - text_w / 2 - diameter * 0.035,
            y + box[1] - diameter * 0.018,
            center_x + text_w / 2 + diameter * 0.035,
            y + box[3] + diameter * 0.018,
        ),
        radius=int(diameter * 0.03),
        fill=(0, 0, 0, 120),
    )
    draw.text((text_x, y), normalized, font=font, fill=with_alpha(hex_to_rgb(config.coordinate_color), 255))


def map_projection(config: MapLayerLayout, current: TrackPoint, diameter: int, heading_deg: float | None) -> Callable[[TrackPoint], tuple[float, float]]:
    """把经纬度投影到圆盘像素坐标。

    圆盘按真实米制比例尺换算：config.scale_meters 是从圆心到圆边的地面距离。
    开启随航向旋转时，车头方向固定朝上，相当于把世界坐标转 -heading。
    """
    meters_per_degree_lat = 111_320.0
    meters_per_degree_lon = max(1.0, 111_320.0 * math.cos(math.radians(current.latitude)))
    radius_px = diameter / 2
    px_per_meter = radius_px / max(1, config.scale_meters)
    rotation = math.radians(heading_deg or 0.0) if config.rotate_with_heading else 0.0
    cos_r, sin_r = math.cos(-rotation), math.sin(-rotation)

    def project(point: TrackPoint) -> tuple[float, float]:
        east = (point.longitude - current.longitude) * meters_per_degree_lon * px_per_meter
        north = (point.latitude - current.latitude) * meters_per_degree_lat * px_per_meter
        x = east * cos_r - north * sin_r
        y = east * sin_r + north * cos_r
        return radius_px + x, radius_px - y

    return project


def route_heading_at(points: list[TrackPoint], elapsed_ms: int) -> float | None:
    """用当前位置前后两点估一个航向角，正北为 0°，顺时针为正。"""
    index = last_played_index(points, elapsed_ms)
    start = max(0, min(index, len(points) - 2))
    a, b = points[start], points[start + 1]
    east = (b.longitude - a.longitude) * math.cos(math.radians(a.latitude))
    north = b.latitude - a.latitude
    if abs(east) < 1e-9 and abs(north) < 1e-9:
        return None
    return math.degrees(math.atan2(east, north))


def draw_map_satellite_background(circle: Any, config: MapLayerLayout, points: list[TrackPoint], elapsed_ms: int) -> None:
    """离线卫星风底图：基于轨迹包围盒生成一张固定纹理，再贴进圆盘。

    没有网络时无法拉真实瓦片，这里用 NumPy 一次性生成地形纹理（深色植被、
    细碎地块、道路带），整趟行程复用同一张底图，既稳定又快。
    """
    tile = satellite_base_tile(config, points, circle.width)
    circle.alpha_composite(tile)


_SAT_TILE_CACHE: dict[tuple[Any, ...], Any] = {}


def satellite_base_tile(config: MapLayerLayout, points: list[TrackPoint], diameter: int) -> Any:
    """按轨迹范围和尺寸缓存底图纹理；同一趟行程的所有帧共用一张。"""
    import numpy as np

    min_lat = min(p.latitude for p in points)
    max_lat = max(p.latitude for p in points)
    min_lon = min(p.longitude for p in points)
    max_lon = max(p.longitude for p in points)
    key = (round(min_lat, 5), round(max_lat, 5), round(min_lon, 5), round(max_lon, 5), diameter, config.background_color)
    cached = _SAT_TILE_CACHE.get(key)
    if cached is not None:
        return cached

    base = np.array(hex_to_rgb(config.background_color, (29, 42, 36)), dtype=np.float32)
    yy, xx = np.mgrid[0:diameter, 0:diameter].astype(np.float32)
    u = xx / max(1, diameter - 1)
    v = yy / max(1, diameter - 1)
    seed = ((min_lat * 1e4 + min_lon * 1e4) % 1000) / 1000.0
    # 多频叠加：低频决定大块植被，高频模拟建筑和树冠颗粒。
    field = (
        np.sin((u * 5.1 + seed) * math.tau) * 0.35
        + np.cos((v * 6.7 - seed) * math.tau) * 0.30
        + np.sin((u * 17.0 + v * 13.0) * math.tau) * 0.20
        + np.cos((u * 41.0 - v * 37.0) * math.tau) * 0.15
    )
    grain = (field * 26.0)[..., None]
    rgb = np.clip(base + grain + np.array([0.0, 5.0, 1.0], dtype=np.float32), 0, 255)
    # 叠两条暗一些的道路带，让卫星图有可读的道路结构。
    road = (np.abs(np.sin((u * 1.8 + v * 0.6) * math.pi)) < 0.035)
    rgb[road] = np.clip(rgb[road] * 0.72 + 34, 0, 255)
    alpha = np.full((diameter, diameter, 1), 255, dtype=np.uint8)
    array = np.concatenate([rgb.astype(np.uint8), alpha], axis=2)
    tile = Image.fromarray(array, mode="RGBA")
    # 只保留最近一趟行程的底图，避免长时间运行时缓存膨胀。
    if len(_SAT_TILE_CACHE) > 4:
        _SAT_TILE_CACHE.clear()
    _SAT_TILE_CACHE[key] = tile
    return tile


def draw_map_circle_content(circle: Any, config: MapLayerLayout, points: list[TrackPoint], elapsed_ms: int, diameter: int) -> None:
    """圆盘内的轨迹、已行驶部分和车头箭头。"""
    assert ImageDraw is not None
    draw = ImageDraw.Draw(circle)
    current = point_at(points, elapsed_ms)
    heading = route_heading_at(points, elapsed_ms)
    project = map_projection(config, current, diameter, heading)
    route_xy = [project(point) for point in points]
    draw.line(route_xy, fill=with_alpha(hex_to_rgb(config.route_color, (127, 183, 255)), 235), width=max(3, diameter // 70), joint="curve")
    played = max(1, min(len(points) - 1, last_played_index(points, elapsed_ms)))
    draw.line(route_xy[: played + 1], fill=with_alpha(hex_to_rgb(config.played_color, (255, 255, 255)), 250), width=max(2, diameter // 90), joint="curve")
    draw_heading_arrow(draw, diameter / 2, diameter / 2, diameter, config.marker_color)


def draw_heading_arrow(draw: Any, center_x: float, center_y: float, diameter: int, color_hex: str) -> None:
    """车头箭头：始终画在圆心并朝上，因为地图已经按航向旋转。"""
    size = max(8.0, diameter * 0.06)
    color = with_alpha(hex_to_rgb(color_hex, (255, 176, 32)), 255)
    outline = (18, 18, 18, 235)
    # 箭身 + 尖头分两笔画，保证在实际分辨率下仍是清晰的箭头轮廓。
    shaft_half = size * 0.42
    draw.polygon(
        [
            (center_x, center_y - size * 1.35),
            (center_x - size * 0.85, center_y - size * 0.10),
            (center_x + size * 0.85, center_y - size * 0.10),
        ],
        fill=color,
        outline=outline,
    )
    draw.rectangle(
        (
            center_x - shaft_half,
            center_y - size * 0.20,
            center_x + shaft_half,
            center_y + size * 1.05,
        ),
        fill=color,
        outline=outline,
    )


def draw_map_scale_bar(draw: Any, config: MapLayerLayout, left: int, top: int, diameter: int) -> None:
    """圆盘底部的比例尺，长度按当前米制比例尺换算。"""
    target_meters = config.scale_meters * 0.36
    for nice in (25, 50, 100, 200, 500, 1000, 2000):
        if target_meters <= nice:
            meters = nice
            break
    else:
        meters = 2000
    length_px = (meters / max(1, config.scale_meters)) * (diameter / 2)
    x0 = left + diameter * 0.5 - length_px / 2
    x1 = x0 + length_px
    y = top + diameter - diameter * 0.045
    color = with_alpha(hex_to_rgb(config.scale_bar_color, (255, 255, 255)), 220)
    draw.line((x0, y, x1, y), fill=color, width=max(2, diameter // 140))
    tick = max(3, diameter // 80)
    draw.line((x0, y - tick, x0, y + tick), fill=color, width=max(2, diameter // 140))
    draw.line((x1, y - tick, x1, y + tick), fill=color, width=max(2, diameter // 140))
    font = load_font(max(9, diameter // 28))
    label = f"{meters}m"
    box = draw.textbbox((0, 0), label, font=font)
    draw.text(((x0 + x1) / 2 - (box[2] - box[0]) / 2, y - tick - (box[3] - box[1])), label, font=font, fill=color)


def route_scale_for(points: list[TrackPoint], width: int, height: int) -> float:
    """按经纬度跨度把整条轨迹缩放到卡片里，留一点边距。"""
    min_lat = min(point.latitude for point in points)
    max_lat = max(point.latitude for point in points)
    min_lon = min(point.longitude for point in points)
    max_lon = min(point.longitude for point in points)
    lat_span = max(0.000001, max_lat - min_lat)
    lon_span = max(0.000001, max_lon - min_lon)
    return min(width / lon_span, height / lat_span) * 0.98


def cue_values_at(cues: list[Cue], elapsed_ms: int) -> dict[str, str] | None:
    # 字幕是按时长顺序排列的，用二分找当前区间，避免整段视频逐帧线性扫描。
    starts = _cues_starts(cues)
    index = bisect.bisect_right(starts, elapsed_ms) - 1 if starts else -1
    if index >= 0:
        cue = cues[index]
        if cue.start_ms <= elapsed_ms < cue.end_ms:
            return cue_field_values(cue.text)
    return None


_CUES_STARTS_CACHE: dict[tuple[int, int, int], list[int]] = {}


def _cues_starts(cues: list[Cue]) -> list[int]:
    if not cues:
        return []
    key = (len(cues), cues[0].start_ms, cues[-1].end_ms)
    cached = _CUES_STARTS_CACHE.get(key)
    if cached is not None:
        return cached
    starts = [cue.start_ms for cue in cues]
    if len(_CUES_STARTS_CACHE) > 8:
        _CUES_STARTS_CACHE.clear()
    _CUES_STARTS_CACHE[key] = starts
    return starts


def track_speed_at(points: list[TrackPoint], elapsed_ms: int) -> float:
    if not points:
        return 0.0
    closest = min(points, key=lambda point: abs(point.elapsed_ms - elapsed_ms))
    return float(closest.speed_kmh)


def generate_overlay_video(
    pulled: PulledTrip,
    track_points: list[TrackPoint],
    progress: Callable[[int, str], None] | None = None,
    layout: TripLayout | None = None,
) -> Path:
    """按排版设置渲染整段透明叠加层，供 FilmCraft 放在 V2 轨道。

    layout 由调用方传入本趟行程解析好的画布，保证叠加层和序列同尺寸。
    """
    assert Image is not None
    if layout is None:
        layout = load_layout()
    width, height = layout.width, layout.height
    # CarSpeed 字幕本身就是每秒一条，排版层按 1fps 渲染即可，还能把渲染耗时降到 1/30。
    fps = 1
    total_duration_ms = max(1, pulled.total_duration_ms)
    total_frames = max(1, math.ceil(total_duration_ms * fps / 1000))
    cues = layout_cues(pulled)
    out_path = pulled.root / "carspeed-overlay.mov"
    command = [
        "ffmpeg", "-y", "-v", "error", "-hide_banner",
        "-f", "rawvideo", "-pix_fmt", "rgba",
        "-s", f"{width}x{height}", "-r", str(fps), "-i", "pipe:0",
        "-c:v", "prores_ks", "-profile:v", "4444",
        "-pix_fmt", "yuva444p10le", "-alpha_bits", "8",
        str(out_path),
    ]
    process = subprocess.Popen(command, stdin=subprocess.PIPE, stderr=subprocess.PIPE)
    assert process.stdin is not None
    try:
        for frame_index in range(total_frames):
            elapsed_ms = frame_index * 1000 / fps
            values = cue_values_at(cues, elapsed_ms) or {}
            speed_kmh = extract_speed_kmh(values.get("speed")) or track_speed_at(track_points, elapsed_ms)
            layer = render_overlay_frame(layout, values, speed_kmh, width, height, int(elapsed_ms), track_points)
            process.stdin.write(layer.tobytes())
            if progress and frame_index % fps == 0:
                percent = 40 + int(50 * frame_index / max(1, total_frames))
                progress(min(92, percent), f"渲染排版层 {frame_index}/{total_frames}")
    finally:
        process.stdin.close()
        stderr = process.stderr.read().decode("utf-8", errors="ignore") if process.stderr else ""
        code = process.wait(timeout=1800)
    if code != 0 or not out_path.is_file() or out_path.stat().st_size <= 0:
        raise RuntimeError(f"透明排版层编码失败\n{stderr}")
    return out_path


def compose_pillow_frame(
    frame: Any,
    output_width: int,
    output_height: int,
    map_height: int,
    subtitle: str | None,
    track_points: list[TrackPoint],
    elapsed_ms: int,
    map_scale_percent: int,
    font: Any,
) -> Any:
    assert Image is not None
    assert ImageDraw is not None
    if map_height > 0:
        canvas = Image.new("RGB", (output_width, output_height), (0, 0, 0))
        canvas.paste(frame, (0, 0))
    else:
        canvas = frame.copy()
    draw = ImageDraw.Draw(canvas)
    if subtitle:
        draw_pillow_subtitle(draw, subtitle, output_width, output_height - map_height, font)
    if map_height > 0:
        draw_pillow_route(draw, output_width, output_height - map_height, map_height, track_points, elapsed_ms, map_scale_percent, font)
    return canvas


def draw_pillow_subtitle(draw: Any, subtitle: str, width: int, video_height: int, font: Any) -> None:
    lines = [line.strip() for line in subtitle.splitlines() if line.strip()][-4:]
    if not lines:
        return
    padding = max(14, int(width * 0.022))
    line_gap = max(6, int(padding * 0.45))
    text_boxes = [draw.textbbox((0, 0), line[:42], font=font) for line in lines]
    text_width = min(width - padding * 4, max(box[2] - box[0] for box in text_boxes))
    text_height = sum(box[3] - box[1] for box in text_boxes) + line_gap * (len(lines) - 1)
    left = padding
    top = max(padding, video_height - text_height - padding * 2)
    right = min(width - padding, left + text_width + padding * 2)
    bottom = min(video_height - padding, top + text_height + padding * 2)
    draw.rounded_rectangle((left, top, right, bottom), radius=padding, fill=(0, 0, 0, 170))
    y = top + padding
    for line, box in zip(lines, text_boxes):
        draw.text((left + padding, y), line[:42], font=font, fill=(255, 255, 255))
        y += (box[3] - box[1]) + line_gap


def draw_pillow_route(
    draw: Any,
    width: int,
    top: int,
    height: int,
    points: list[TrackPoint],
    elapsed_ms: int,
    scale_percent: int,
    font: Any,
) -> None:
    draw.rectangle((0, top, width, top + height), fill=(15, 24, 34))
    if len(points) < 2:
        draw.text((max(16, width * 0.08), top + height * 0.42), "暂无 GPS 轨迹", font=font, fill=(255, 255, 255))
        return
    pad = max(16, int(height * 0.16))
    min_lat = min(point.latitude for point in points)
    max_lat = max(point.latitude for point in points)
    min_lon = min(point.longitude for point in points)
    max_lon = max(point.longitude for point in points)
    lat_span = max(0.000001, max_lat - min_lat)
    lon_span = max(0.000001, max_lon - min_lon)
    fit_scale = min((width - pad * 2) / lon_span, (height - pad * 2) / lat_span)
    route_scale = fit_scale * max(0.6, min(2.2, scale_percent / 100.0))
    center_lat = (min_lat + max_lat) / 2
    center_lon = (min_lon + max_lon) / 2

    def xy(point: TrackPoint) -> tuple[int, int]:
        x = int(width / 2 + (point.longitude - center_lon) * route_scale)
        y = int(top + height / 2 - (point.latitude - center_lat) * route_scale)
        return x, y

    route_xy = [xy(point) for point in points]
    if len(route_xy) >= 2:
        draw.line(route_xy, fill=(70, 128, 170), width=max(4, height // 40), joint="curve")
    played = max(1, min(len(points) - 1, last_played_index(points, elapsed_ms)))
    draw.line(route_xy[: played + 1], fill=(245, 245, 245), width=max(3, height // 55), joint="curve")
    current = point_at(points, elapsed_ms)
    cx, cy = xy(current)
    radius = max(7, height // 22)
    draw.ellipse((cx - radius, cy - radius, cx + radius, cy + radius), fill=(79, 214, 151), outline=(255, 255, 255), width=2)
    label = f"{current.speed_kmh:.1f} km/h  比例 {scale_percent}%"
    draw.text((pad, top + height - pad), label, font=font, fill=(255, 255, 255))


def normalized_range(total_ms: int, options: ExportOptions) -> tuple[int, int]:
    start = max(0, min(options.start_ms, max(0, total_ms - 1)))
    requested_end = options.end_ms if options.end_ms is not None else total_ms
    end = max(start + 1, min(requested_end, total_ms))
    return start, end


def normalized_speed(value: float) -> float:
    return max(0.25, min(16.0, float(value or 1.0)))


def normalized_fps(value: int) -> int:
    return max(5, min(30, int(value or DEFAULT_FPS)))


def bitrate(width: int, height: int, fps: int) -> str:
    value = max(2_000_000, min(14_000_000, int(width * height * fps * 0.18)))
    return str(value)


def escape_filter_path(path: Path) -> str:
    text = str(path).replace("\\", "\\\\").replace(":", "\\:").replace("'", "\\'")
    return f"'{text}'"


def even(value: int) -> int:
    value = max(2, value)
    return value if value % 2 == 0 else value - 1


def safe_name(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]+", "_", value).strip("._") or "carspeed"


def parse_options(data: dict[str, Any]) -> ExportOptions:
    fields = data.get("subtitleFields") or {}
    return ExportOptions(
        trip=str(data.get("trip") or ""),
        start_ms=int(float(data.get("startMs") or 0)),
        end_ms=int(float(data["endMs"])) if data.get("endMs") is not None else None,
        playback_speed=float(data.get("playbackSpeed") or 1.0),
        include_route_map=bool(data.get("includeRouteMap", True)),
        map_height_percent=int(data.get("mapHeightPercent") or 28),
        map_scale_percent=int(data.get("mapScalePercent") or 100),
        fps=int(data.get("fps") or DEFAULT_FPS),
        subtitle_time=bool(fields.get("time", True)),
        subtitle_speed=bool(fields.get("speed", True)),
        subtitle_gps=bool(fields.get("gps", True)),
        subtitle_address=bool(fields.get("address", True)),
        subtitle_focus=bool(fields.get("focus", True)),
        subtitle_video_mode=bool(fields.get("videoMode", True)),
    )


def start_export_job(options: ExportOptions, workspace: Path) -> Job:
    if not options.trip:
        raise RuntimeError("缺少 trip")
    job = JOBS.create()
    job_root = workspace / job.id
    job_root.mkdir(parents=True, exist_ok=True)

    def progress(percent: int, message: str) -> None:
        JOBS.update(job.id, status="running", progress=max(0, min(99, percent)), message=message)

    def worker() -> None:
        try:
            progress(1, "检查 adb 和 ffmpeg")
            tools = check_tools()
            missing = [name for name, path in tools.items() if path == "missing"]
            if missing:
                raise RuntimeError("缺少工具：" + ", ".join(missing))
            if not adb_devices():
                raise RuntimeError("没有连接可用 Android 设备")
            pulled = pull_trip(options.trip, job_root, progress)
            output = compose_video(pulled, options, progress)
            JOBS.update(job.id, status="done", progress=100, message="导出完成", output=str(output))
        except Exception as error:  # noqa: BLE001
            JOBS.update(job.id, status="failed", progress=100, message="导出失败", error=str(error))

    threading.Thread(target=worker, daemon=True).start()
    return job


def start_studio_job(trip_ids: list[str], workspace: Path, open_projects: bool = True) -> Job:
    cleaned_ids = list(dict.fromkeys(trip_id.strip() for trip_id in trip_ids if trip_id.strip()))
    if not cleaned_ids:
        raise RuntimeError("至少选择一个历史行程")
    cli = filmcraft_cli_path()
    if cli is None:
        raise RuntimeError("未安装 filmcraft-cli")
    if not FILMCRAFT_APP.exists():
        raise RuntimeError("未安装 FilmCraft.app")

    job = JOBS.create()
    job_root = workspace / job.id
    job_root.mkdir(parents=True, exist_ok=True)
    JOBS.update(job.id, items=[{"tripId": trip_id, "status": "queued", "message": "等待处理"} for trip_id in cleaned_ids])

    def update_item(index: int, **values: Any) -> None:
        with JOBS._lock:  # one transaction keeps the JSON response coherent
            current = JOBS._jobs[job.id]
            items = list(current.items or [])
            items[index] = {**items[index], **values}
            current.items = items

    def worker() -> None:
        outputs: list[str] = []
        failures: list[str] = []
        try:
            JOBS.update(job.id, status="running", phase="preparing", progress=1, message="检查设备和 FilmCraft")
            if not adb_devices():
                raise RuntimeError("没有连接可用 Android 设备")
            for index, trip_id in enumerate(cleaned_ids):
                base = index * 100 / len(cleaned_ids)
                span = 100 / len(cleaned_ids)
                update_item(index, status="running", message="准备拉取")

                def progress(percent: int, message: str) -> None:
                    overall = round(base + span * max(0, min(100, percent)) / 100)
                    JOBS.update(job.id, progress=min(99, overall), phase="pulling" if percent < 32 else "filmcraft", message=f"{trip_id}: {message}")
                    update_item(index, status="running", message=message, progress=percent)

                try:
                    pulled = pull_trip(trip_id, job_root / safe_name(trip_id), progress)
                    project_path = pulled.root / f"{safe_name(pulled.title)}.fcproj"
                    manifest = import_trip_to_filmcraft(pulled, cli, project_path, progress)
                    outputs.append(str(project_path))
                    update_item(
                        index,
                        status="done",
                        progress=100,
                        message="项目已生成",
                        project=str(project_path),
                        segments=len(pulled.videos),
                        tracks=len(pulled.tracks),
                        subtitles=len(pulled.subtitles),
                        sequenceId=manifest.get("sequenceId"),
                    )
                    if open_projects:
                        open_filmcraft_project(project_path, cli, progress)
                    progress(100, "项目已生成并在 FilmCraft 中打开")
                    update_item(index, status="done", progress=100, message="项目已生成并在 FilmCraft 中打开")
                except Exception as error:  # noqa: BLE001
                    failures.append(f"{trip_id}: {error}")
                    update_item(index, status="failed", progress=100, message="处理失败", error=str(error))

            if not outputs:
                raise RuntimeError("所有行程都处理失败" + ("\n" + "\n".join(failures) if failures else ""))
            status = "done" if not failures else "partial"
            message = "已生成 FilmCraft 项目" if not failures else f"完成 {len(outputs)} 个，失败 {len(failures)} 个"
            JOBS.update(
                job.id,
                status=status,
                phase="done",
                progress=100,
                message=message,
                output=outputs[0],
                outputs=outputs,
                error="\n".join(failures) if failures else None,
            )
        except Exception as error:  # noqa: BLE001
            JOBS.update(job.id, status="failed", phase="done", progress=100, message="处理失败", error=str(error))

    threading.Thread(target=worker, daemon=True).start()
    return job


class Handler(BaseHTTPRequestHandler):
    workspace: Path = Path.cwd() / "tools" / "macos_video_exporter" / "workspace"
    studio_path: Path = Path(__file__).resolve().parent / "studio.html"

    def do_GET(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        if parsed.path == "/":
            self.send_html(self.studio_path.read_text(encoding="utf-8"))
        elif parsed.path == "/legacy":
            self.send_html(INDEX_HTML)
        elif parsed.path == "/api/health":
            try:
                self.send_json({"tools": check_tools(), "devices": adb_devices()})
            except Exception as error:  # noqa: BLE001
                self.send_json({"error": str(error)}, status=HTTPStatus.SERVICE_UNAVAILABLE)
        elif parsed.path == "/api/trips":
            try:
                self.send_json({"trips": list_trips()})
            except Exception as error:  # noqa: BLE001
                self.send_json({"error": str(error)}, status=HTTPStatus.SERVICE_UNAVAILABLE)
        elif parsed.path.startswith("/api/trips/") and parsed.path.endswith("/thumbnail"):
            trip_id = parsed.path.removeprefix("/api/trips/").removesuffix("/thumbnail").strip("/")
            try:
                thumbnail = remote_thumbnail(trip_id, self.workspace)
                if thumbnail is None:
                    self.send_error(HTTPStatus.NOT_FOUND)
                else:
                    self.send_file(thumbnail, attachment=False)
            except Exception as error:  # noqa: BLE001
                self.send_json({"error": str(error)}, status=HTTPStatus.SERVICE_UNAVAILABLE)
        elif parsed.path == "/api/layout":
            self.send_json({"layout": layout_to_dict(load_layout())})
        elif parsed.path.startswith("/api/trips/") and parsed.path.endswith("/overlay-preview"):
            trip_id = parsed.path.removeprefix("/api/trips/").removesuffix("/overlay-preview").strip("/")
            query = {key: values[0] for key, values in parse_qs(parsed.query).items()}
            try:
                image = overlay_preview_image(trip_id, self.workspace, query)
                self.send_image(image)
            except Exception as error:  # noqa: BLE001
                self.send_json({"error": str(error)}, status=HTTPStatus.SERVICE_UNAVAILABLE)
        elif parsed.path.startswith("/api/jobs/"):
            self.handle_job_get(parsed.path)
        else:
            self.send_error(HTTPStatus.NOT_FOUND)

    def do_POST(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        if parsed.path in {"/api/studio", "/api/export", "/api/reveal", "/api/layout"}:
            length = int(self.headers.get("content-length") or 0)
            try:
                data = json.loads(self.rfile.read(length).decode("utf-8") or "{}")
                if parsed.path == "/api/layout":
                    if not isinstance(data.get("layout"), dict):
                        raise RuntimeError("layout 必须是对象")
                    save_layout(layout_from_dict(data["layout"]))
                    self.send_json({"layout": layout_to_dict(load_layout())})
                elif parsed.path == "/api/studio":
                    trip_ids = data.get("trips")
                    if not isinstance(trip_ids, list):
                        raise RuntimeError("trips 必须是行程 ID 列表")
                    job = start_studio_job(
                        [str(trip_id) for trip_id in trip_ids],
                        self.workspace,
                        bool(data.get("open", True)),
                    )
                    self.send_json(job_to_dict(job), status=HTTPStatus.ACCEPTED)
                elif parsed.path == "/api/export":
                    job = start_export_job(parse_options(data), self.workspace)
                    self.send_json(job_to_dict(job), status=HTTPStatus.ACCEPTED)
                else:
                    path = Path(str(data.get("path") or "")).expanduser().resolve()
                    workspace = self.workspace.resolve()
                    if workspace not in path.parents or not path.exists():
                        raise RuntimeError("只能显示当前工作目录中已生成的项目")
                    run(["open", "-R", str(path)], timeout=30)
                    self.send_json({"ok": True})
            except Exception as error:  # noqa: BLE001
                self.send_json({"error": str(error)}, status=HTTPStatus.BAD_REQUEST)
        else:
            self.send_error(HTTPStatus.NOT_FOUND)

    def handle_job_get(self, path: str) -> None:
        parts = path.strip("/").split("/")
        if len(parts) < 3:
            self.send_error(HTTPStatus.NOT_FOUND)
            return
        job = JOBS.get(parts[2])
        if not job:
            self.send_error(HTTPStatus.NOT_FOUND)
            return
        if len(parts) == 4 and parts[3] == "download":
            if job.status != "done" or not job.output:
                self.send_error(HTTPStatus.CONFLICT)
                return
            self.send_file(Path(job.output))
            return
        self.send_json(job_to_dict(job))

    def send_json(self, payload: dict[str, Any], status: HTTPStatus = HTTPStatus.OK) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("content-type", "application/json; charset=utf-8")
        self.send_header("content-length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def send_html(self, body_text: str) -> None:
        body = body_text.encode("utf-8")
        self.send_response(HTTPStatus.OK)
        self.send_header("content-type", "text/html; charset=utf-8")
        self.send_header("content-length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def send_file(self, path: Path, attachment: bool = True) -> None:
        if not path.exists():
            self.send_error(HTTPStatus.NOT_FOUND)
            return
        content_type = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
        self.send_response(HTTPStatus.OK)
        self.send_header("content-type", content_type)
        self.send_header("content-length", str(path.stat().st_size))
        if attachment:
            self.send_header("content-disposition", f"attachment; filename=\"{path.name}\"")
        else:
            self.send_header("cache-control", "private, max-age=300")
        self.end_headers()
        with path.open("rb") as handle:
            shutil.copyfileobj(handle, self.wfile)

    def send_image(self, image: Any) -> None:
        assert Image is not None
        buffer = io.BytesIO()
        image.save(buffer, format="PNG")
        body = buffer.getvalue()
        self.send_response(HTTPStatus.OK)
        self.send_header("content-type", "image/png")
        self.send_header("content-length", str(len(body)))
        self.send_header("cache-control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format: str, *args: Any) -> None:  # noqa: A002
        print(f"[{datetime.now().strftime('%H:%M:%S')}] {self.address_string()} {format % args}")


def job_to_dict(job: Job) -> dict[str, Any]:
    return {
        "id": job.id,
        "status": job.status,
        "progress": job.progress,
        "message": job.message,
        "phase": job.phase,
        "output": job.output,
        "outputs": job.outputs or ([] if job.output is None else [job.output]),
        "error": job.error,
        "items": job.items or [],
        "downloadUrl": f"/api/jobs/{job.id}/download" if job.status == "done" else None,
    }


SRT_TIME_RE = re.compile(r"(\d{2}):(\d{2}):(\d{2}),(\d{3})\s+-->\s+(\d{2}):(\d{2}):(\d{2}),(\d{3})")


INDEX_HTML = r"""
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>CarSpeed macOS 视频导出</title>
  <style>
    body { margin: 0; font-family: -apple-system, BlinkMacSystemFont, "Helvetica Neue", Arial, sans-serif; background: #f4ead8; color: #1f2a33; }
    main { max-width: 920px; margin: 0 auto; padding: 28px 18px 60px; }
    h1 { margin: 0 0 8px; font-size: 28px; }
    .card { background: rgba(255,255,255,.82); border-radius: 20px; padding: 18px; margin-top: 16px; box-shadow: 0 10px 30px rgba(42,34,20,.08); }
    label { display: block; margin: 12px 0 6px; font-weight: 700; }
    select, input[type="number"] { width: 100%; box-sizing: border-box; padding: 10px 12px; border: 1px solid #d7c9ae; border-radius: 12px; font-size: 15px; }
    input[type="range"] { width: 100%; }
    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 10px; }
    .checks label { font-weight: 500; margin: 6px 0; }
    button { border: 0; border-radius: 14px; padding: 12px 18px; background: #1d4e89; color: white; font-weight: 800; font-size: 15px; cursor: pointer; }
    button.secondary { background: #53606a; }
    button:disabled { opacity: .5; cursor: not-allowed; }
    pre { white-space: pre-wrap; background: #14212b; color: #d8ffe5; border-radius: 14px; padding: 14px; min-height: 80px; }
    .muted { color: #65717a; }
    .row { display: flex; gap: 12px; flex-wrap: wrap; align-items: center; }
  </style>
</head>
<body>
<main>
  <h1>CarSpeed macOS 视频导出</h1>
  <p class="muted">通过 adb 拉取手机原始数据，在 Mac 上使用 ffmpeg h264_videotoolbox 合成分享版 MP4。</p>
  <div class="card">
    <div class="row">
      <button onclick="refreshAll()">刷新设备和录像</button>
      <span id="health" class="muted">未检查</span>
    </div>
  </div>
  <div class="card">
    <label>选择录像</label>
    <select id="trip"></select>
    <div class="grid">
      <div><label>开始秒</label><input id="start" type="number" min="0" value="0"></div>
      <div><label>结束秒（空=到结尾）</label><input id="end" type="number" min="1" placeholder="自动"></div>
      <div><label>播放倍率</label><select id="speed"><option>1</option><option>2</option><option>4</option><option>8</option><option>16</option></select></div>
      <div><label>输出 FPS</label><input id="fps" type="number" min="5" max="30" value="15"></div>
    </div>
  </div>
  <div class="card">
    <label><input id="route" type="checkbox" checked> 底部拼接轨迹图</label>
    <label>轨迹图高度：<span id="mapHeightText">28%</span></label>
    <input id="mapHeight" type="range" min="18" max="45" value="28" oninput="mapHeightText.textContent=this.value+'%'">
    <label>轨迹图比例尺：<span id="mapScaleText">100%</span></label>
    <input id="mapScale" type="range" min="60" max="220" value="100" oninput="mapScaleText.textContent=this.value+'%'">
  </div>
  <div class="card checks">
    <label>字幕显示内容</label>
    <div class="grid">
      <label><input id="subTime" type="checkbox" checked> 时间</label>
      <label><input id="subSpeed" type="checkbox" checked> 速度</label>
      <label><input id="subGps" type="checkbox" checked> GPS 经纬度</label>
      <label><input id="subAddress" type="checkbox" checked> 地理位置</label>
      <label><input id="subFocus" type="checkbox" checked> 对焦状态</label>
      <label><input id="subVideo" type="checkbox" checked> 录像规格</label>
    </div>
  </div>
  <div class="card">
    <div class="row">
      <button onclick="startExport()">开始导出</button>
      <button class="secondary" onclick="downloadLatest()">下载最近结果</button>
    </div>
    <pre id="log">等待操作</pre>
  </div>
</main>
<script>
let latestJob = null;
async function refreshAll() {
  const health = await fetch('/api/health').then(r => r.json());
  document.getElementById('health').textContent = `设备 ${health.devices.join(', ') || '无'}，adb=${health.tools.adb}，ffmpeg=${health.tools.ffmpeg}`;
  const data = await fetch('/api/trips').then(r => r.json());
  const select = document.getElementById('trip');
  select.innerHTML = '';
  data.trips.forEach(trip => {
    const opt = document.createElement('option');
    opt.value = trip.id;
    opt.textContent = `${trip.title} (${trip.segments}段)`;
    select.appendChild(opt);
  });
}
function payload() {
  const endValue = document.getElementById('end').value;
  return {
    trip: document.getElementById('trip').value,
    startMs: Number(document.getElementById('start').value || 0) * 1000,
    endMs: endValue ? Number(endValue) * 1000 : null,
    playbackSpeed: Number(document.getElementById('speed').value || 1),
    fps: Number(document.getElementById('fps').value || 15),
    includeRouteMap: document.getElementById('route').checked,
    mapHeightPercent: Number(document.getElementById('mapHeight').value),
    mapScalePercent: Number(document.getElementById('mapScale').value),
    subtitleFields: {
      time: subTime.checked, speed: subSpeed.checked, gps: subGps.checked,
      address: subAddress.checked, focus: subFocus.checked, videoMode: subVideo.checked
    }
  };
}
async function startExport() {
  const res = await fetch('/api/export', {method: 'POST', headers: {'content-type': 'application/json'}, body: JSON.stringify(payload())});
  const job = await res.json();
  if (job.error) { log.textContent = job.error; return; }
  latestJob = job.id;
  pollJob();
}
async function pollJob() {
  if (!latestJob) return;
  const job = await fetch(`/api/jobs/${latestJob}`).then(r => r.json());
  log.textContent = JSON.stringify(job, null, 2);
  if (job.status === 'running' || job.status === 'queued') setTimeout(pollJob, 1200);
}
function downloadLatest() {
  if (latestJob) location.href = `/api/jobs/${latestJob}/download`;
}
refreshAll();
</script>
</body>
</html>
"""


def main() -> None:
    ensure_tool_path()
    parser = argparse.ArgumentParser(description="CarSpeed Studio macOS 本地服务")
    parser.add_argument("--host", default=DEFAULT_HOST)
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--workspace", default=str(Path(__file__).resolve().parent / "workspace"))
    args = parser.parse_args()
    Handler.workspace = Path(args.workspace).expanduser().resolve()
    Handler.studio_path = Path(__file__).resolve().parent / "studio.html"
    Handler.workspace.mkdir(parents=True, exist_ok=True)
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"CarSpeed Studio 已启动：http://{args.host}:{args.port}")
    print(f"工作目录：{Handler.workspace}")
    server.serve_forever()


if __name__ == "__main__":
    main()
