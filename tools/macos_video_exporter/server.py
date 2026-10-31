#!/usr/bin/env python3
"""CarSpeed Studio macOS 本地服务。

首选流程是浏览手机历史行程、拉取原始素材，并用 FilmCraft CLI 生成可直接剪辑的 .fcproj。
旧的 ffmpeg/Pillow 成片接口保留，作为轨迹和字幕扁平化输出的基础。
"""

from __future__ import annotations

import argparse
import base64
import bisect
import gc
import hashlib
import io
import json
import mimetypes
import math
import os
import re
import shlex
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
import uuid
import dataclasses
from collections import deque
from concurrent.futures import Future, ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from functools import lru_cache
from datetime import datetime
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Callable
from urllib.parse import parse_qs, quote, unquote, urlparse
from urllib.request import Request, urlopen

try:
    from PIL import Image, ImageDraw, ImageFilter, ImageFont
except ImportError:  # pragma: no cover - 运行时通过 /api/health 暴露
    Image = None
    ImageDraw = None
    ImageFilter = None
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
DEFAULT_FPS = 60
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
# 手机没连上时的演示行程：用一条杭州市区的固定轨迹，让排版和地图浮窗能直接看出效果。
# 没有它的话 Studio 只剩一句「手机中没有找到 CarSpeed 历史行程」，调参数全靠想象。
DEMO_TRIP_ID = "demo-hangzhou"
# 延安路 → 凤起路 → 中河高架 一带，足够画出有转弯的轨迹，地图浮窗也不会是一根直线。
DEMO_ROUTE_LATLON = (
    (30.25820, 120.16150),
    (30.25960, 120.16280),
    (30.26110, 120.16410),
    (30.26240, 120.16560),
    (30.26320, 120.16740),
    (30.26360, 120.16950),
    (30.26340, 120.17170),
    (30.26260, 120.17380),
    (30.26120, 120.17550),
    (30.25960, 120.17680),
    (30.25800, 120.17760),
    (30.25640, 120.17800),
    (30.25480, 120.17780),
)
DEMO_SEGMENT_SECONDS = 2.0
DEMO_SPEED_KMH = 42.0
TRIP_THUMBNAIL_NAME = "carspeed-trip-thumbnail.jpg"
THUMBNAIL_CACHE_LOCK = threading.Lock()
LAYOUT_CACHE_LOCK = threading.Lock()
MEM_CACHE_LOCK = threading.Lock()
LAYOUT_WRITE_LOCK = threading.Lock()
# 客户端每次保存带一个递增序号。后发出的请求先写完时，早先那份不能再盖回去。
_LAYOUT_WRITE_REVISION = 0
DEFAULT_LAYOUT_PATH = Path(__file__).resolve().parent / "layout.json"
DEFAULT_LAYOUT_PRESETS_PATH = Path(__file__).resolve().parent / "layout_presets.json"
AMAP_DISK_TILE_DIR = Path(__file__).resolve().parent / "workspace" / "amap-tiles"
_AMAP_TILE_FETCH_POOL = ThreadPoolExecutor(max_workers=24)
_AMAP_SUBTILE_FETCH_POOL = ThreadPoolExecutor(max_workers=24)
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
    playback_speed: float | None = None
    include_route_map: bool = True
    map_height_percent: int = 28
    map_scale_percent: int = 100
    fps: int | None = None
    stabilize: bool | None = None
    stabilize_strength: int | None = None
    stabilize_crop_percent: int | None = None
    intro_map: bool | None = None
    intro_satellite_seconds: int | None = None
    intro_road_seconds: int | None = None
    intro_scale_meters: int | None = None
    privacy_mode: bool | None = None
    privacy_trim_meters: int | None = None
    export_resolution: str | None = None
    export_width: int | None = None
    export_height: int | None = None
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
class TrackRadar:
    """轨迹 JSON 中的测速雷达点（坐标已是高德 GCJ-02 坐标系）。"""
    latitude: float
    longitude: float
    speed_limit_kmh: int = 0
    camera_type: int = 0


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
    kind: str = "export"
    trip: str = ""
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
    # bar：Forza 弧形；aero：战斗机极简 HUD；gt：GT 点阵遥测；circular：旧圆形表盘。
    style: str = "bar"
    x: float = 0.845
    y: float = 0.70
    radius_percent: float = 0.155
    bar_width: int = 420
    bar_height: int = 34
    bar_segments: int = 28
    opacity: int = 100
    background_color: str = "#0b1410"
    arc_color: str = "#ff5e00"
    value_color: str = "#00f2ff"
    unit_color: str = "#9fb3ab"
    max_speed: int = 300
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
    """参考稿的圆角矩形地图。浏览器覆盖层里已经画了它；没有 Chrome 时才单独出 PIL 地图轨。"""
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
    marker_color: str = "#00f2ff"
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
    coordinate_size: int = 11
    coordinate_color: str = "#ffffff"
    # 周期性「10s 全程轨迹图 + 50s 固定比例尺局部图」平滑缩放参数（无论是否快进/倍速均按成片时间周期切换）
    cycle_overview_enabled: bool = True
    overview_seconds: int = 10
    detail_seconds: int = 50


@dataclass
class HudLayout:
    """左上角 HUD：年月日时分秒、驾驶时长、里程进度。"""
    enabled: bool = True
    x: float = 0.055
    y: float = 0.075
    opacity: int = 100
    time_size: int = 13
    duration_size: int = 30
    progress_size: int = 30
    time_format_24h: bool = True
    show_date: bool = False
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
class StabilizeLayout:
    """车载视频防抖参数：利用 Vision 亚像素运动估计 + Metal GPU 双线性重采样滤除车载支架高频颠簸。"""
    enabled: bool = True
    strength: int = 65
    crop_percent: int = 6


@dataclass
class IntroMapLayout:
    """片头全屏高德地图配置：默认在视频开头插入 3 秒全屏卫星轨迹图 + 3 秒全屏道路轨迹图，scale_meters=0 表示完整行程轨迹。"""
    enabled: bool = True
    satellite_seconds: int = 3
    road_seconds: int = 3
    scale_meters: int = 0


@dataclass
class PrivacyLayout:
    """家庭隐私保护模式配置：默认开启，从有有效 GPS 信号起算，自动截断开头与结尾各 1000m（1km）的视频与轨迹，防止暴露家庭/目的地住址。"""
    enabled: bool = True
    trim_meters: int = 1000


@dataclass
class TripLayout:
    width: int = 1920
    height: int = 1080
    fps: int = DEFAULT_FPS
    playback_speed: float = 1.0
    # layout_mode: "float"（悬浮窗模式）或 "split"（上视频下地图手机竖屏拼接模式）
    layout_mode: str = "float"
    split_video_percent: int = 38
    # export_resolution: "source" | "2160p" | "1440p" | "1080p" | "720p" | "custom"
    export_resolution: str = "source"
    fields: list[TextFieldLayout] = field(default_factory=list)
    gauge: GaugeLayout = field(default_factory=GaugeLayout)
    info_bar: InfoBarLayout = field(default_factory=InfoBarLayout)
    map_layer: MapLayerLayout = field(default_factory=MapLayerLayout)
    hud: HudLayout = field(default_factory=HudLayout)
    caption: CaptionLayout = field(default_factory=CaptionLayout)
    stabilize: StabilizeLayout = field(default_factory=StabilizeLayout)
    intro_map: IntroMapLayout = field(default_factory=IntroMapLayout)
    privacy: PrivacyLayout = field(default_factory=PrivacyLayout)

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

    def active_export_for_trip(self, trip_id: str) -> Job | None:
        """查找指定行程当前正在运行或排队中的视频导出任务，防止重复并发导出互抢资源。"""
        with self._lock:
            for job in self._jobs.values():
                if job.kind == "export" and job.trip == trip_id and job.status in {"queued", "running"}:
                    return job
        return None

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
        "address": (0.15, 0.56, 34, False),
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
            # 左下角地图卡片已自带斜切地址徽章，默认不再单独开启悬浮地址框避免重复遮挡。
            enabled=enabled and key not in bar_fields,
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


def save_layout(layout: TripLayout, path: Path | None = None, revision: int | None = None) -> Path:
    """把排版写进 layout.json；先写临时文件再替换，避免写到一半进程被杀导致文件坏掉。

    revision 比已写入的更旧时直接跳过。拖动和右键改大小会连着发几次保存，
    慢的那次若后到达，会把刚改的面板大小盖回旧值。
    """
    global _LAYOUT_WRITE_REVISION
    target = path or DEFAULT_LAYOUT_PATH
    with LAYOUT_WRITE_LOCK:
        if revision is not None and revision < _LAYOUT_WRITE_REVISION:
            return target
        if revision is not None:
            _LAYOUT_WRITE_REVISION = revision
        target.parent.mkdir(parents=True, exist_ok=True)
        payload = json.dumps(layout_to_dict(layout), ensure_ascii=False, indent=2)
        tmp = target.with_suffix(target.suffix + ".tmp")
        tmp.write_text(payload, encoding="utf-8")
        tmp.replace(target)
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
        style=str(gauge_raw.get("style") or "bar"),        x=_clamp_unit(gauge_raw.get("x"), 0.78),
        y=_clamp_unit(gauge_raw.get("y"), 0.80),
        radius_percent=max(0.05, min(0.45, float(gauge_raw.get("radiusPercent") or 0.16))),
        bar_width=_clamp_int(gauge_raw.get("barWidth"), 120, 1800, 420),
        bar_height=_clamp_int(gauge_raw.get("barHeight"), 8, 160, 34),
        bar_segments=_clamp_int(gauge_raw.get("barSegments"), 4, 80, 28),
        opacity=_clamp_int(gauge_raw.get("opacity"), 0, 100, 100),
        background_color=str(gauge_raw.get("backgroundColor") or "#0b1410"),
        arc_color=str(gauge_raw.get("arcColor") or "#ff5e00"),
        value_color=str(gauge_raw.get("valueColor") or "#00f2ff"),
        unit_color=str(gauge_raw.get("unitColor") or "#9fb3ab"),
        max_speed=_clamp_int(gauge_raw.get("maxSpeed"), 20, 400, 300),
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
        coordinate_size=_clamp_int(map_raw.get("coordinateSize"), 8, 24, 11),
        coordinate_color=str(map_raw.get("coordinateColor") or "#ffffff"),
        cycle_overview_enabled=bool(map_raw.get("cycleOverviewEnabled", True)),
        overview_seconds=_clamp_int(map_raw.get("overviewSeconds"), 2, 120, 10),
        detail_seconds=_clamp_int(map_raw.get("detailSeconds"), 5, 300, 50),
    )
    hud_raw = data.get("hud") if isinstance(data.get("hud"), dict) else {}
    hud = HudLayout(
        enabled=bool(hud_raw.get("enabled", True)),
        x=_clamp_unit(hud_raw.get("x"), 0.055),
        y=_clamp_unit(hud_raw.get("y"), 0.075),
        opacity=_clamp_int(hud_raw.get("opacity"), 0, 100, 100),
        time_size=_clamp_int(hud_raw.get("timeSize"), 12, 160, 13),
        duration_size=_clamp_int(hud_raw.get("durationSize"), 10, 140, 30),
        progress_size=_clamp_int(hud_raw.get("progressSize"), 10, 140, 30),
        time_format_24h=bool(hud_raw.get("timeFormat24h", True)),
        show_date=bool(hud_raw.get("showDate", False)),
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
    stab_raw = data.get("stabilize") if isinstance(data.get("stabilize"), dict) else {}
    stabilize = StabilizeLayout(
        enabled=bool(stab_raw.get("enabled", True)),
        strength=_clamp_int(stab_raw.get("strength"), 10, 95, 65),
        crop_percent=_clamp_int(stab_raw.get("cropPercent"), 3, 15, 6),
    )
    intro_raw = data.get("introMap") if isinstance(data.get("introMap"), dict) else {}
    intro_map = IntroMapLayout(
        enabled=bool(intro_raw.get("enabled", True)),
        satellite_seconds=_clamp_int(intro_raw.get("satelliteSeconds"), 0, 15, 3),
        road_seconds=_clamp_int(intro_raw.get("roadSeconds"), 0, 15, 3),
        scale_meters=_clamp_int(intro_raw.get("scaleMeters"), 0, 20000, 0),
    )
    privacy_raw = data.get("privacy") if isinstance(data.get("privacy"), dict) else {}
    privacy = PrivacyLayout(
        enabled=bool(privacy_raw.get("enabled", True)),
        trim_meters=_clamp_int(privacy_raw.get("trimMeters"), 0, 10000, 1000),
    )
    raw_speed = data.get("playbackSpeed")
    try:
        parsed_speed = max(0.25, min(16.0, float(raw_speed))) if raw_speed is not None else 1.0
    except (TypeError, ValueError):
        parsed_speed = 1.0
    raw_mode = str(data.get("layoutMode") or "float").strip().lower()
    layout_mode = "split" if raw_mode == "split" else "float"
    split_video_percent = _clamp_int(data.get("splitVideoPercent"), 25, 65, 38)
    raw_res = str(data.get("exportResolution") or "source").strip().lower()
    export_resolution = raw_res if raw_res in {"source", "2160p", "1440p", "1080p", "720p", "custom"} else "source"
    return TripLayout(
        width=_clamp_int(data.get("width"), 320, 7680, base.width),
        height=_clamp_int(data.get("height"), 240, 4320, base.height),
        fps=_clamp_int(data.get("fps"), 5, 120, DEFAULT_FPS),
        playback_speed=parsed_speed,
        layout_mode=layout_mode,
        split_video_percent=split_video_percent,
        export_resolution=export_resolution,
        fields=fields,
        gauge=gauge,
        info_bar=info_bar,
        map_layer=map_layer,
        hud=hud,
        caption=caption,
        stabilize=stabilize,
        intro_map=intro_map,
        privacy=privacy,
    )


def layout_to_dict(layout: TripLayout) -> dict[str, Any]:
    return {
        "width": layout.width,
        "height": layout.height,
        "fps": layout.fps,
        "playbackSpeed": layout.playback_speed,
        "layoutMode": getattr(layout, "layout_mode", "float") or "float",
        "splitVideoPercent": _clamp_int(getattr(layout, "split_video_percent", 38), 25, 65, 38),
        "exportResolution": str(getattr(layout, "export_resolution", "source") or "source"),
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
            "cycleOverviewEnabled": bool(getattr(layout.map_layer, "cycle_overview_enabled", True)),
            "overviewSeconds": _clamp_int(getattr(layout.map_layer, "overview_seconds", 10), 2, 120, 10),
            "detailSeconds": _clamp_int(getattr(layout.map_layer, "detail_seconds", 50), 5, 300, 50),
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
        "stabilize": {
            "enabled": layout.stabilize.enabled,
            "strength": layout.stabilize.strength,
            "cropPercent": layout.stabilize.crop_percent,
        },
        "introMap": {
            "enabled": layout.intro_map.enabled,
            "satelliteSeconds": layout.intro_map.satellite_seconds,
            "roadSeconds": layout.intro_map.road_seconds,
            "scaleMeters": layout.intro_map.scale_meters,
        },
        "privacy": {
            "enabled": bool(getattr(getattr(layout, "privacy", None), "enabled", True)),
            "trimMeters": _clamp_int(getattr(getattr(layout, "privacy", None), "trim_meters", 1000), 0, 10000, 1000),
        },
    }


def default_portrait_layout() -> TripLayout:
    """生成针对竖屏（9:16，1080x1920）优化的默认排版配置。"""
    portrait = default_layout()
    portrait.width = 1080
    portrait.height = 1920
    portrait.hud.x = 0.06
    portrait.hud.y = 0.045
    portrait.hud.time_size = 11
    portrait.hud.duration_size = 26
    portrait.hud.progress_size = 26
    portrait.map_layer.size_percent = 0.19
    portrait.map_layer.x = 0.25
    portrait.map_layer.y = 0.80
    portrait.gauge.radius_percent = 0.14
    portrait.gauge.x = 0.76
    portrait.gauge.y = 0.81
    return portrait


def default_split_portrait_layout() -> TripLayout:
    """生成「上视频、下地图」手机竖屏拼接模式（9:16，1080x1920）默认排版配置。

    上方 38% 区域展示车载视频并仅保留速度码表；下方 62% 铺满高德地图（路线+闪动当前位置圈+最高速度点+测速雷达+里程胶囊），
    隐藏左上角 HUD、白底信息栏及独立文字字段。
    """
    split_layout = default_portrait_layout()
    split_layout.layout_mode = "split"
    split_layout.split_video_percent = 38
    split_layout.hud.enabled = False
    split_layout.info_bar.enabled = False
    for f in split_layout.fields:
        f.enabled = False
    split_layout.gauge.enabled = True
    split_layout.gauge.radius_percent = 0.135
    split_layout.gauge.x = 0.80
    split_layout.gauge.y = 0.25
    split_layout.map_layer.enabled = True
    split_layout.map_layer.scale_meters = 260
    split_layout.map_layer.cycle_overview_enabled = True
    split_layout.map_layer.overview_seconds = 10
    split_layout.map_layer.detail_seconds = 50
    return split_layout


def layout_orientation(layout_or_dict: TripLayout | dict[str, Any]) -> str:
    """判断排版画布方向：高大于宽为竖屏 portrait，其余为横屏 landscape。"""
    if isinstance(layout_or_dict, TripLayout):
        w, h = layout_or_dict.width, layout_or_dict.height
    else:
        w = _clamp_int(layout_or_dict.get("width"), 320, 7680, 1920)
        h = _clamp_int(layout_or_dict.get("height"), 240, 4320, 1080)
    return "portrait" if h > w else "landscape"


def builtin_layout_presets() -> list[dict[str, Any]]:
    """返回内置的横屏（16:9）、竖屏悬浮窗（9:16）与上视频下地图竖屏（9:16）默认排版预设列表。"""
    landscape = default_layout()
    portrait = default_portrait_layout()
    split_portrait = default_split_portrait_layout()
    return [
        {
            "name": "默认横屏 (16:9)",
            "orientation": "landscape",
            "builtin": True,
            "updatedAt": "",
            "layout": layout_to_dict(landscape),
        },
        {
            "name": "默认竖屏 (9:16)",
            "orientation": "portrait",
            "builtin": True,
            "updatedAt": "",
            "layout": layout_to_dict(portrait),
        },
        {
            "name": "上视频下地图竖屏 (9:16)",
            "orientation": "portrait",
            "builtin": True,
            "updatedAt": "",
            "layout": layout_to_dict(split_portrait),
        },
    ]


def _read_custom_presets(path: Path | None = None) -> list[dict[str, Any]]:
    """从磁盘读取用户保存的自定义命名排版预设列表。"""
    target = path or DEFAULT_LAYOUT_PRESETS_PATH
    if not target.is_file():
        return []
    try:
        raw = json.loads(target.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError):
        return []
    items = raw.get("presets") if isinstance(raw, dict) else (raw if isinstance(raw, list) else [])
    if not isinstance(items, list):
        return []
    result: list[dict[str, Any]] = []
    for entry in items:
        if not isinstance(entry, dict):
            continue
        name = str(entry.get("name") or "").strip()
        raw_layout = entry.get("layout")
        if not name or not isinstance(raw_layout, dict):
            continue
        parsed_layout = layout_from_dict(raw_layout)
        result.append(
            {
                "name": name,
                "orientation": layout_orientation(parsed_layout),
                "builtin": False,
                "updatedAt": str(entry.get("updatedAt") or ""),
                "layout": layout_to_dict(parsed_layout),
            }
        )
    return result


def _write_custom_presets(presets: list[dict[str, Any]], path: Path | None = None) -> Path:
    """将用户自定义排版预设列表原子写入磁盘文件。"""
    target = path or DEFAULT_LAYOUT_PRESETS_PATH
    with LAYOUT_WRITE_LOCK:
        target.parent.mkdir(parents=True, exist_ok=True)
        payload = json.dumps({"presets": presets}, ensure_ascii=False, indent=2)
        tmp = target.with_suffix(target.suffix + ".tmp")
        tmp.write_text(payload, encoding="utf-8")
        tmp.replace(target)
    return target


def load_layout_presets(path: Path | None = None) -> list[dict[str, Any]]:
    """合并内置横竖屏预设与用户自定义命名预设并返回（同名时用户自定义预设覆盖内置预设值）。"""
    builtins = builtin_layout_presets()
    customs = _read_custom_presets(path)
    custom_by_name = {item["name"]: item for item in customs}
    merged: list[dict[str, Any]] = []
    seen: set[str] = set()
    for item in builtins:
        name = item["name"]
        if name in custom_by_name:
            override = dict(custom_by_name[name])
            override["builtin"] = True
            merged.append(override)
        else:
            merged.append(item)
        seen.add(name)
    for item in customs:
        if item["name"] not in seen:
            merged.append(item)
            seen.add(item["name"])
    return merged


def save_layout_preset(
    name: str,
    layout: TripLayout,
    path: Path | None = None,
    layout_path: Path | None = None,
) -> list[dict[str, Any]]:
    """按配置名称保存排版预设到 layout_presets.json，并同步更新当前生效的 layout.json。"""
    clean_name = (name or "").strip()
    if not clean_name:
        raise RuntimeError("配置名称不能为空")
    if len(clean_name) > 64:
        clean_name = clean_name[:64]
    customs = _read_custom_presets(path)
    now_str = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    entry = {
        "name": clean_name,
        "orientation": layout_orientation(layout),
        "builtin": False,
        "updatedAt": now_str,
        "layout": layout_to_dict(layout),
    }
    updated = False
    for idx, existing in enumerate(customs):
        if existing["name"] == clean_name:
            customs[idx] = entry
            updated = True
            break
    if not updated:
        customs.append(entry)
    _write_custom_presets(customs, path)
    save_layout(layout, path=layout_path)
    return load_layout_presets(path)


def activate_layout_preset(
    name: str,
    path: Path | None = None,
    layout_path: Path | None = None,
) -> TripLayout:
    """按名称加载指定排版预设并写入当前生效的 layout.json。"""
    clean_name = (name or "").strip()
    if not clean_name:
        raise RuntimeError("请选择要加载的配置名称")
    presets = load_layout_presets(path)
    matched = next((item for item in presets if item["name"] == clean_name), None)
    if matched is None:
        raise RuntimeError(f"未找到名为「{clean_name}」的配置方案")
    layout = layout_from_dict(matched["layout"])
    save_layout(layout, path=layout_path)
    return layout


def delete_layout_preset(name: str, path: Path | None = None) -> list[dict[str, Any]]:
    """按名称删除用户自定义排版预设（若同名覆盖了内置预设，则恢复为内置默认）。"""
    clean_name = (name or "").strip()
    if not clean_name:
        raise RuntimeError("配置名称不能为空")
    customs = _read_custom_presets(path)
    filtered = [item for item in customs if item["name"] != clean_name]
    _write_custom_presets(filtered, path)
    return load_layout_presets(path)


def run(command: list[str], timeout: int | None = None) -> str:
    if command and command[0] == "adb":
        ensure_tool_path()
    result = subprocess.run(command, check=False, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
    if result.returncode != 0:
        detail = (result.stderr or result.stdout).strip()
        raise RuntimeError(f"命令失败: {' '.join(command)}\n{detail}")
    return result.stdout


def _start_stderr_drain(process: subprocess.Popen[Any]) -> tuple[list[bytes], threading.Thread | None]:
    """启动后台线程持续消费子进程 stderr，防止 ffmpeg 日志写满 OS 管道缓冲区导致死锁。"""
    chunks: list[bytes] = []
    stream = process.stderr
    if stream is None:
        return chunks, None

    def _reader() -> None:
        try:
            while True:
                data = stream.read(4096)
                if not data:
                    break
                chunks.append(data if isinstance(data, bytes) else str(data).encode("utf-8", "ignore"))
        except Exception:  # noqa: BLE001
            pass

    thread = threading.Thread(target=_reader, daemon=True)
    thread.start()
    return chunks, thread


def _finish_stderr_drain(chunks: list[bytes], thread: threading.Thread | None) -> str:
    """等待 stderr 消费线程结束并返回完整错误文本。"""
    if thread is not None:
        thread.join(timeout=5.0)
    return b"".join(chunks).decode("utf-8", errors="ignore")


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
    os.environ.setdefault("ANDROID_ADB_SERVER_PORT", "5038")
    candidates = [
        ANDROID_ADB.parent,
        Path("~/platform-tools/platform-tools").expanduser(),
        Path("~/platform-tools").expanduser(),
        Path("/opt/homebrew/bin"),
        Path("/usr/local/bin"),
        Path("~/.local/bin").expanduser(),
    ]
    existing = [str(path) for path in candidates if path.is_dir()]
    current = os.environ.get("PATH", "")
    parts = current.split(os.pathsep) if current else []
    additions = [path for path in existing if path not in parts]
    if additions:
        os.environ["PATH"] = os.pathsep.join(additions + parts)


def adb_devices() -> list[str]:
    """获取当前已连接的 Android 设备列表；若本机 adb daemon 异常或未连接设备则返回空列表。"""
    try:
        output = run(["adb", "devices"], timeout=3)
    except Exception:  # noqa: BLE001 - 手机未连接或 adb 守护进程端口冲突时降级为离线模式
        return []
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
    if is_demo_trip(trip_id):
        # 演示行程不碰 adb：没装手机、没授权也能渲染预览。
        return demo_remote_trip()
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


def is_demo_trip(trip_id: str) -> bool:
    return trip_id == DEMO_TRIP_ID


def demo_track_points() -> list[TrackPoint]:
    """演示行程的轨迹点：杭州市区一段固定路线，带真实加速峰值以便预览最高速度标记。"""
    points: list[TrackPoint] = []
    started_ms = int(datetime.now().timestamp() * 1000) - int(len(DEMO_ROUTE_LATLON) * DEMO_SEGMENT_SECONDS * 1000)
    for index, (latitude, longitude) in enumerate(DEMO_ROUTE_LATLON):
        elapsed_ms = int(index * DEMO_SEGMENT_SECONDS * 1000)
        # 在中段制造一个明显的速度峰值，使地图最高速度玫红点可直观预览
        spd = DEMO_SPEED_KMH + (26.0 if index == len(DEMO_ROUTE_LATLON) // 2 else (8.0 * math.sin(index * 0.7)))
        points.append(
            TrackPoint(
                elapsed_ms=elapsed_ms,
                time_ms=started_ms + elapsed_ms,
                latitude=latitude,
                longitude=longitude,
                speed_kmh=round(max(12.0, spd), 1),
            )
        )
    return points


def demo_track_radars() -> list[TrackRadar]:
    """演示行程的测速雷达列表（高德 GCJ-02 坐标），用于预览测速摄像头图标与限速胶囊。"""
    if not DEMO_ROUTE_LATLON:
        return []
    try:
        import overlay_widgets
        lat1, lon1 = DEMO_ROUTE_LATLON[min(2, len(DEMO_ROUTE_LATLON) - 1)]
        lat2, lon2 = DEMO_ROUTE_LATLON[min(len(DEMO_ROUTE_LATLON) - 2, max(0, len(DEMO_ROUTE_LATLON) * 2 // 3))]
        g1 = overlay_widgets.wgs84_to_gcj02(lat1, lon1)
        g2 = overlay_widgets.wgs84_to_gcj02(lat2, lon2)
        return [
            TrackRadar(latitude=g1[0], longitude=g1[1], speed_limit_kmh=80, camera_type=0),
            TrackRadar(latitude=g2[0], longitude=g2[1], speed_limit_kmh=60, camera_type=1),
        ]
    except Exception:  # noqa: BLE001
        return []


def demo_remote_trip() -> RemoteTrip:
    """把演示轨迹包成和真行程一样的结构，下游渲染逻辑不用为它开分支。"""
    started_at_ms = int(datetime.now().timestamp() * 1000)
    segments = [
        RemoteSegment(
            name=f"carspeed-demo-{index:02d}.mp4",
            public_path=f"{PUBLIC_REMOTE_ROOT}/{DEMO_TRIP_ID}/carspeed-demo-{index:02d}.mp4",
            track_path=None,
            subtitle_path=None,
            plate_path=None,
            bytes=0,
            companion_bytes=0,
            # 演示行程的轨迹和字幕由 demo_track_points / demo_trip_values 直接供给，
            # 这里标 False 是为了不被「有没有伴生文件」的判断当成坏数据。
            has_track=False,
            has_subtitle=False,
            has_plate=False,
        )
        for index in range(1, 3)
    ]
    return RemoteTrip(
        id=DEMO_TRIP_ID,
        title=f"{datetime.fromtimestamp(started_at_ms / 1000).strftime('%Y-%m-%d %H:%M:%S')} · 杭州演示",
        started_at_ms=started_at_ms,
        segments=segments,
        segment_count=len(segments),
        track_count=1,
        subtitle_count=1,
        total_bytes=0,
        companion_bytes=0,
        thumbnail_path=None,
    )


def library_raw(workspace: Path, trip_id: str) -> Path:
    """用户主动拉下来的素材放这里，不跟某一次「生成项目」绑在一起。"""
    return workspace / "library" / safe_name(trip_id) / "raw"


def _file_size(path: Path | None) -> int:
    if path is None:
        return 0
    try:
        return path.stat().st_size
    except OSError:
        return 0


def iter_local_copies(workspace: Path, trip_id: str, filename: str):
    """本机上这份文件的所有副本：资料库，以及生成项目时拉下来的目录。"""
    safe = safe_name(trip_id)
    library = library_raw(workspace, trip_id) / filename
    if library.is_file():
        yield library
    # 生成项目的路径是 workspace/<任务>/<行程>/<行程>/raw。
    yield from workspace.glob(f"*/{safe}/{safe}/raw/{filename}")
    yield from workspace.glob(f"*/{safe}/*/raw/{filename}")


def best_local_file(workspace: Path, trip_id: str, filename: str) -> Path | None:
    best: Path | None = None
    best_size = 0
    for candidate in iter_local_copies(workspace, trip_id, filename):
        size = _file_size(candidate)
        if size > best_size:
            best, best_size = candidate, size
    return best


def resolve_local_sidecar(remote: RemoteTrip, workspace: Path, remote_path: str | None) -> Path | None:
    """轨迹和字幕：资料库里已经有就直接读，没有再向手机要。"""
    if not remote_path:
        return None
    direct = Path(remote_path)
    if direct.is_file() and direct.stat().st_size > 0:
        return direct
    cached = best_local_file(workspace, remote.id, direct.name)
    if cached is not None:
        return cached
    return adb_pull_cached(remote_path, workspace)


def video_is_complete(path: Path | None, remote_size: int) -> bool:
    size = _file_size(path)
    if size <= 0:
        return False
    if remote_size > 0:
        return size >= remote_size
    return size >= 1_000_000


def trip_local_summary(workspace: Path, trip: RemoteTrip) -> dict[str, Any]:
    """这趟行程的录像、字幕、轨迹有没有已经在电脑上。"""
    if is_demo_trip(trip.id):
        return {
            "ready": False,
            "partial": False,
            "demo": True,
            "videoReady": 0,
            "videoTotal": 0,
            "sidecarReady": 0,
            "sidecarTotal": 0,
            "localBytes": 0,
            "remoteBytes": 0,
            "path": None,
        }
    video_ready = 0
    sidecar_ready = 0
    sidecar_total = 0
    local_bytes = 0
    partial = False
    reveal: str | None = None
    for segment in trip.segments:
        video = best_local_file(workspace, trip.id, segment.name)
        video_size = _file_size(video)
        local_bytes += video_size
        if video_is_complete(video, segment.bytes):
            video_ready += 1
            reveal = reveal or str(video.parent if video else "")
        elif video_size > 0:
            partial = True
        for remote_path in (segment.track_path, segment.subtitle_path, segment.plate_path):
            if not remote_path:
                continue
            sidecar_total += 1
            sidecar = best_local_file(workspace, trip.id, Path(remote_path).name)
            sidecar_size = _file_size(sidecar)
            local_bytes += sidecar_size
            if sidecar_size > 0:
                sidecar_ready += 1
                reveal = reveal or str(sidecar.parent if sidecar else "")
    video_total = len(trip.segments)
    ready = video_total > 0 and video_ready == video_total and sidecar_ready == sidecar_total
    if not ready and (video_ready > 0 or sidecar_ready > 0):
        partial = True
    library = library_raw(workspace, trip.id)
    if library.is_dir() and any(library.iterdir()):
        reveal = str(library)
    map_offline = trip_offline_map_status(workspace, trip)
    src_w, src_h = 0, 0
    first_local_video = cached_trip_video(trip.id, workspace)
    if first_local_video is not None:
        try:
            src_w, src_h = probe_size_cached(first_local_video)
        except Exception:  # noqa: BLE001
            src_w, src_h = 0, 0
    return {
        "ready": ready,
        "partial": partial and not ready,
        "demo": False,
        "videoReady": video_ready,
        "videoTotal": video_total,
        "sidecarReady": sidecar_ready,
        "sidecarTotal": sidecar_total,
        "localBytes": local_bytes,
        "remoteBytes": trip.total_bytes + trip.companion_bytes,
        "path": reveal,
        "mapReady": bool(map_offline.get("ready")),
        "mapCachedTiles": int(map_offline.get("cachedTiles") or 0),
        "mapTotalTiles": int(map_offline.get("totalTiles") or 0),
        "sourceWidth": src_w,
        "sourceHeight": src_h,
    }


def list_library_trip_ids(workspace: Path) -> list[str]:
    """资料库里已经有录像的行程。手机拔掉之后这些仍然要留在列表里。"""
    root = workspace / "library"
    if not root.is_dir():
        return []
    found: list[str] = []
    for child in root.iterdir():
        if not child.is_dir() or TRIP_FOLDER_RE.match(child.name) is None:
            continue
        raw = child / "raw"
        if not raw.is_dir():
            continue
        if any(path.is_file() and path.suffix.lower() in NATIVE_VIDEO_EXTENSIONS for path in raw.iterdir()):
            found.append(child.name)
    return sorted(found, reverse=True)


def scan_library_trip(workspace: Path, trip_id: str) -> RemoteTrip | None:
    """用资料库里的录像和伴生文件拼出行程，字段和手机扫描结果同一套。"""
    raw = library_raw(workspace, trip_id)
    if not raw.is_dir():
        return None
    videos = sorted(
        path for path in raw.iterdir()
        if path.is_file() and path.suffix.lower() in NATIVE_VIDEO_EXTENSIONS
    )
    if not videos:
        return None
    segments: list[RemoteSegment] = []
    for video in videos:
        stem = segment_stem(video.name)
        sidecars: dict[str, Path] = {}
        for suffix in (".track.json", ".srt", ".plate.json"):
            candidate = raw / f"{stem}{suffix}"
            if candidate.is_file() and candidate.stat().st_size > 0:
                sidecars[suffix] = candidate
        track = sidecars.get(".track.json")
        subtitle = sidecars.get(".srt")
        plate = sidecars.get(".plate.json")
        segments.append(
            RemoteSegment(
                name=video.name,
                public_path=str(video),
                track_path=str(track) if track else None,
                subtitle_path=str(subtitle) if subtitle else None,
                plate_path=str(plate) if plate else None,
                bytes=video.stat().st_size,
                companion_bytes=sum(path.stat().st_size for path in sidecars.values()),
                has_track=track is not None,
                has_subtitle=subtitle is not None,
                has_plate=plate is not None,
            )
        )
    match = TRIP_FOLDER_RE.match(trip_id)
    started_at_ms = parse_compact_time_ms(f"{match.group(1)}-{match.group(2)}") if match else 0
    date_label = datetime.fromtimestamp(started_at_ms / 1000).strftime("%Y-%m-%d %H:%M:%S") if started_at_ms else trip_id
    thumbnail = raw / TRIP_THUMBNAIL_NAME
    return RemoteTrip(
        id=trip_id,
        title=date_label,
        started_at_ms=started_at_ms,
        segments=segments,
        segment_count=len(segments),
        track_count=sum(1 for segment in segments if segment.has_track),
        subtitle_count=sum(1 for segment in segments if segment.has_subtitle),
        total_bytes=sum(segment.bytes for segment in segments),
        companion_bytes=sum(segment.companion_bytes for segment in segments),
        thumbnail_path=str(thumbnail) if thumbnail.is_file() else None,
    )


def open_trip(trip_id: str, workspace: Path | None = None) -> RemoteTrip:
    """优先使用本机资料库已下载完成的行程副本；缺失时再扫描手机清单。"""
    if is_demo_trip(trip_id):
        return demo_remote_trip()
    if workspace is not None:
        local = scan_library_trip(workspace, trip_id)
        if local is not None:
            return local
    try:
        return scan_remote_trip(trip_id)
    except Exception:  # noqa: BLE001 - 手机不在或这趟已经不在手机上
        if workspace is not None:
            local = scan_library_trip(workspace, trip_id)
            if local is not None:
                return local
        raise


def list_trips(workspace: Path | None = None) -> list[dict[str, Any]]:
    trips: list[RemoteTrip] = []
    try:
        if adb_devices():
            inventory = remote_inventory(ALL_REMOTE_ROOTS)
            trips = [scan_remote_trip(trip_id, inventory) for trip_id in list_remote_trip_ids_from_inventory(inventory)]
    except Exception:  # noqa: BLE001 - 没连手机/没授权时只看资料库
        trips = []
    seen = {trip.id for trip in trips}
    if workspace is not None:
        for trip_id in list_library_trip_ids(workspace):
            if trip_id in seen:
                continue
            local = scan_library_trip(workspace, trip_id)
            if local is not None:
                trips.append(local)
    # 一台真行程都没有时挂一条杭州演示行程，保证排版预览和地图浮窗有东西可看。
    # 资料库里已经有下载好的行程时不再用演示行程把它们盖掉。
    if not trips:
        trips = [demo_remote_trip()]
    else:
        trips.sort(key=lambda trip: trip.started_at_ms, reverse=True)
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
            "thumbnailUrl": (
                f"/api/trips/{trip.id}/thumbnail"
                if (trip.thumbnail_path or (workspace is not None and cached_trip_video(trip.id, workspace) is not None))
                else None
            ),
            "local": trip_local_summary(workspace, trip) if workspace is not None else None,
        }
        for trip in trips
    ]


def remote_thumbnail(trip_id: str, workspace: Path, remote: RemoteTrip | None = None) -> Path | None:
    if remote is None:
        try:
            remote = open_trip(trip_id, workspace)
        except Exception:  # noqa: BLE001 - 没有缩略图时封面改用本地录像抽帧
            return None
    remote_path = remote.thumbnail_path
    if not remote_path:
        return None
    direct = Path(remote_path)
    if direct.is_file():
        return direct
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


def cached_trip_video(trip_id: str, workspace: Path) -> Path | None:
    """在已拉取的工作区里找这趟行程缓存下来的第一段视频。

    Studio 的排版预览需要一帧真实画面当底图。手机缩略图只有新版 App 才会生成，
    而只要用户之前导出过这趟行程，完整录像就已经在本机了，直接抽帧比再要一张缩略图可靠。
    """
    # workspace 下可能留着多条历史 job 的同名副本，其中有些是中途失败留下的半截文件。
    # 按体积挑最大的那段，别让一个空壳目录挡住真正能用的素材。
    best: Path | None = None
    best_size = 0
    safe = safe_name(trip_id)
    candidates = list((workspace / "library" / safe / "raw").glob("*.mp4"))
    candidates += list(workspace.glob(f"*/{safe}/{safe}/raw/*.mp4"))
    candidates += list(workspace.glob(f"*/{safe}/*/raw/*.mp4"))
    for candidate in candidates:
        size = _file_size(candidate)
        # 小于 1MB 基本是半截文件或占位，抽帧多半失败。
        if size < 1_000_000:
            continue
        if size > best_size:
            best, best_size = candidate, size
    return best


@lru_cache(maxsize=64)
def _extract_frame_png(video: str, offset_ms: int, mtime_ns: int) -> bytes:
    """抽一帧并缓存：拖动预览时间轴时同一时刻会被反复请求。"""
    command = [
        "ffmpeg", "-v", "error", "-hide_banner",
        # -ss 放在 -i 之前是关键帧对齐的快速定位，4K 长片也能在几十毫秒内出帧；
        # 放在后面就要从头解码到该时刻，拖动时间轴会明显卡顿。
        "-ss", f"{max(0, offset_ms) / 1000.0:.3f}",
        "-i", video,
        "-frames:v", "1",
        "-vf", "scale=1280:-2",
        "-f", "image2pipe", "-vcodec", "png",
        "pipe:1",
    ]
    return subprocess.run(command, capture_output=True, check=False).stdout


def video_frame(video: Path, offset_ms: int, width: int, height: int) -> Any:
    """按时间点从本地视频抽一帧，缩放并居中裁切到画布尺寸。"""
    assert Image is not None
    raw = _extract_frame_png(str(video), max(0, offset_ms), video.stat().st_mtime_ns)
    if not raw:
        raise RuntimeError("抽取预览帧失败")
    with Image.open(io.BytesIO(raw)) as handle:
        frame = handle.convert("RGB")
    # 等比放大到铺满画布，再居中裁切，避免拉伸变形；和 FilmCraft 的 fitToFrame 观感一致。
    scale = max(width / frame.width, height / frame.height)
    resized = frame.resize(
        (max(width, round(frame.width * scale)), max(height, round(frame.height * scale))),
        Image.LANCZOS,
    )
    left = (resized.width - width) // 2
    top = (resized.height - height) // 2
    return resized.crop((left, top, left + width, top + height))


def extract_preview_clip(
    video: Path,
    start_ms: int,
    duration_ms: int,
    fps: int = 8,
    max_width: int = 960,
) -> list[bytes]:
    """一次 ffmpeg 抽出一小段预览 JPEG。

    播放如果逐帧起进程，再把整张画布和覆盖层 HTML 来回传，
    单帧往往要将近 1 秒，看起来就像一秒一帧。
    """
    fps = max(4, min(12, int(fps)))
    duration_ms = max(200, min(12_000, int(duration_ms)))
    max_width = max(320, min(1280, int(max_width)))
    with tempfile.TemporaryDirectory(prefix="carspeed-clip-") as tmp:
        pattern = str(Path(tmp) / "%04d.jpg")
        command = [
            "ffmpeg", "-y", "-v", "error", "-hide_banner",
            "-ss", f"{max(0, start_ms) / 1000.0:.3f}",
            "-i", str(video),
            "-t", f"{duration_ms / 1000.0:.3f}",
            "-an",
            "-vf", f"fps={fps},scale={max_width}:-2",
            "-q:v", "6",
            pattern,
        ]
        subprocess.run(command, capture_output=True, check=False, timeout=45)
        frames: list[bytes] = []
        for path in sorted(Path(tmp).glob("*.jpg")):
            data = path.read_bytes()
            if data:
                frames.append(data)
        return frames


def preview_clip_payload(
    trip_id: str,
    workspace: Path,
    start_seconds: float,
    seconds: float,
    fps: int = 8,
) -> dict[str, Any]:
    """给排版编辑器一次返回一小段本地画面。手机上的录像抽不出连贯片段。"""
    if is_demo_trip(trip_id):
        return {"source": "demo", "fps": 2, "frames": []}
    start_ms = max(0, int(float(start_seconds) * 1000))
    duration_ms = max(200, min(10_000, int(float(seconds) * 1000)))
    rate = max(4, min(12, int(fps)))
    video: Path | None = None
    local_ms = start_ms
    remote: RemoteTrip | None = None
    try:
        remote = open_trip(trip_id, workspace)
    except Exception:  # noqa: BLE001 - 手机不在时仍可播已经拉下来的录像
        remote = None
    if remote is not None and remote.segments:
        try:
            segment, local_ms = preview_segment_at(remote, start_ms)
            candidate = best_local_file(workspace, trip_id, segment.name)
            if video_is_complete(candidate, segment.bytes):
                video = candidate
        except Exception:  # noqa: BLE001 - 段定位失败就退回任意一份完整缓存
            video = None
    if video is None:
        video = cached_trip_video(trip_id, workspace)
        local_ms = start_ms
    if video is None:
        return {"source": "phone", "fps": 1, "frames": []}
    raw_frames = extract_preview_clip(video, local_ms, duration_ms, rate)
    if not raw_frames:
        raise RuntimeError("本地录像这一段抽不出画面")
    encoded = [
        "data:image/jpeg;base64," + base64.b64encode(blob).decode("ascii")
        for blob in raw_frames
    ]
    return {"source": "local", "fps": rate, "frames": encoded}


def library_video_file(workspace: Path, trip_id: str, filename: str) -> Path | None:
    """只允许资料库里这一趟行程自己的录像，避免用路径读到别的文件。"""
    name = Path(filename).name
    if not name or name != filename or name.startswith("."):
        return None
    if Path(name).suffix.lower() not in NATIVE_VIDEO_EXTENSIONS:
        return None
    path = best_local_file(workspace, trip_id, name)
    if path is None or not path.is_file():
        return None
    return path


_PREVIEW_MEDIA_NAME = re.compile(r"^[0-9a-f]{20}\.mp4$")
_PREVIEW_MEDIA_LOCKS: dict[str, threading.Lock] = {}
_PREVIEW_MEDIA_GUARD = threading.Lock()


def preview_media_clip(workspace: Path, name: str) -> Path | None:
    """只打开预览缓存里的短片，文件名必须是摘要，不能带路径。"""
    if _PREVIEW_MEDIA_NAME.fullmatch(name or "") is None:
        return None
    root = (workspace / "preview-media").resolve()
    path = (root / name).resolve()
    if path.parent != root or not path.is_file():
        return None
    return path


def clip_lead_ms(video: Path, start_ms: int) -> int:
    """复制出来的短片从关键帧起头。返回第 0 秒比请求的时间早了多少毫秒。"""
    start = max(0, start_ms) / 1000.0
    window_start = max(0.0, start - 4.0)
    command = [
        "ffprobe", "-v", "error",
        "-select_streams", "v:0",
        "-read_intervals", f"{window_start:.3f}%{start + 0.05:.3f}",
        "-show_entries", "packet=pts_time,flags",
        "-of", "csv=p=0",
        str(video),
    ]
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=8, check=False)
    except (OSError, subprocess.TimeoutExpired):
        return 0
    keyframes: list[float] = []
    for line in result.stdout.splitlines():
        parts = line.split(",")
        if len(parts) < 2 or "K" not in parts[1]:
            continue
        try:
            pts = float(parts[0])
        except ValueError:
            continue
        if pts <= start + 0.001:
            keyframes.append(pts)
    if not keyframes:
        return 0
    return max(0, int(round((start - keyframes[-1]) * 1000)))


def preview_clip_seconds(seconds: float) -> float:
    """预览短片的长度。拖进度条要能接着往前播，不能只锁在 10 秒里。"""
    return max(1.0, min(60.0, float(seconds)))


def build_fast_preview(workspace: Path, video: Path, start_ms: int, seconds: float) -> tuple[Path, int] | None:
    """切出一段索引在文件头的短片。

    手机录像的索引在 1GB 以后。浏览器要播整份文件，得先把索引读到，
    看起来就是卡住、掉帧。短片按原编码复制，帧率还是录像自己的。
    """
    duration_ms = int(preview_clip_seconds(seconds) * 1000)
    start_ms = max(0, int(start_ms))
    stamp = video.stat()
    digest = hashlib.sha1(
        f"{video}|{stamp.st_mtime_ns}|{stamp.st_size}|{start_ms}|{duration_ms}".encode()
    ).hexdigest()[:20]
    folder = workspace / "preview-media"
    folder.mkdir(parents=True, exist_ok=True)
    target = folder / f"{digest}.mp4"
    lead_path = target.with_name(target.stem + ".lead")
    with _PREVIEW_MEDIA_GUARD:
        lock = _PREVIEW_MEDIA_LOCKS.setdefault(digest, threading.Lock())
    with lock:
        if target.is_file() and target.stat().st_size > 1024:
            lead_ms = 0
            if lead_path.is_file():
                try:
                    lead_ms = max(0, int(lead_path.read_text(encoding="utf-8").strip() or "0"))
                except ValueError:
                    lead_ms = clip_lead_ms(video, start_ms)
            else:
                lead_ms = clip_lead_ms(video, start_ms)
                lead_path.write_text(str(lead_ms), encoding="utf-8")
            return target, lead_ms
        temporary = folder / f".{digest}.{uuid.uuid4().hex[:8]}.mp4"
        command = [
            "ffmpeg", "-y", "-v", "error", "-hide_banner",
            "-ss", f"{start_ms / 1000.0:.3f}",
            "-i", str(video),
            "-t", f"{duration_ms / 1000.0:.3f}",
            "-an",
            "-c", "copy",
            "-movflags", "+faststart",
            "-reset_timestamps", "1",
            str(temporary),
        ]
        try:
            subprocess.run(command, capture_output=True, check=False, timeout=40)
        except (OSError, subprocess.TimeoutExpired):
            temporary.unlink(missing_ok=True)
            return None
        if not temporary.is_file() or temporary.stat().st_size < 1024:
            temporary.unlink(missing_ok=True)
            return None
        lead_ms = clip_lead_ms(video, start_ms)
        lead_path.write_text(str(lead_ms), encoding="utf-8")
        temporary.replace(target)
        return target, lead_ms


def preview_media_payload(
    trip_id: str,
    workspace: Path,
    start_seconds: float,
    clip_seconds: float | None = None,
) -> dict[str, Any]:
    """本地录像交给浏览器播，按文件自己的帧率走，不再抽成几帧 JPEG。"""
    if is_demo_trip(trip_id):
        return {"source": "demo"}
    try:
        remote = open_trip(trip_id, workspace)
        segment, local_ms = preview_segment_at(remote, max(0, int(float(start_seconds) * 1000)), workspace)
    except Exception:  # noqa: BLE001 - 手机不在或这段对不上时退回抽帧
        return {"source": "phone"}
    video = best_local_file(workspace, trip_id, segment.name)
    if not video_is_complete(video, segment.bytes):
        return {"source": "phone"}
    if clip_seconds:
        built = build_fast_preview(workspace, video, int(local_ms), clip_seconds)
        if built is not None:
            clip, lead_ms = built
            return {
                "source": "local",
                "url": f"/api/preview-media/{clip.name}",
                "startMs": 0,
                # 短片比请求的时间早 leadMs，表盘要按画面真正的时间走。
                "leadMs": lead_ms,
            }
    return {
        "source": "local",
        "url": f"/api/trips/{quote(trip_id, safe='')}/video/{quote(video.name, safe='')}",
        "startMs": int(local_ms),
    }


def parse_byte_range(header: str | None, size: int) -> tuple[int, int] | None:
    """解析 Range 头，返回闭区间。没有头就表示整文件。解析不了返回 None。"""
    if size <= 0:
        return None
    if not header:
        return 0, size - 1
    match = re.fullmatch(r"bytes=(\d*)-(\d*)", header.strip())
    if match is None:
        return None
    start_text, end_text = match.group(1), match.group(2)
    if start_text == "" and end_text == "":
        return None
    if start_text == "":
        length = int(end_text)
        if length <= 0:
            return None
        return max(0, size - length), size - 1
    start = int(start_text)
    end = int(end_text) if end_text else size - 1
    if start >= size or end < start:
        return None
    return start, min(end, size - 1)


def preview_segment_at(
    remote: RemoteTrip,
    elapsed_ms: int,
    workspace: Path | None = None,
) -> tuple[RemoteSegment, int]:
    """时间轴上的毫秒落在哪一段录像，以及段内偏移。"""
    if not remote.segments:
        raise RuntimeError("行程没有录像")
    durations = segment_cached_durations_ms(remote, workspace)
    cursor = 0
    last_idx = len(remote.segments) - 1
    for index, (segment, duration) in enumerate(zip(remote.segments, durations)):
        if index == last_idx and duration <= 0:
            return segment, max(0, elapsed_ms - cursor)
        if duration > 0 and elapsed_ms < cursor + duration:
            return segment, max(0, elapsed_ms - cursor)
        if duration > 0:
            cursor += duration
    return remote.segments[-1], max(0, elapsed_ms - cursor)


def _cover_image(image: Any, width: int, height: int) -> Any:
    scale = max(width / image.width, height / image.height)
    resized = image.resize(
        (max(width, round(image.width * scale)), max(height, round(image.height * scale))),
        Image.LANCZOS,
    )
    left = (resized.width - width) // 2
    top = (resized.height - height) // 2
    return resized.crop((left, top, left + width, top + height))


def preview_background(
    trip_id: str,
    workspace: Path,
    remote: RemoteTrip,
    sample_ms: int,
    width: int,
    height: int,
) -> tuple[Any, str]:
    """预览底图：本地录像 > 手机上按偏移抽的一帧 > 缩略图 > 纯色。

    返回 (图, 说明)。说明非空表示没拿到真实画面，调用方可以提示。
    """
    assert Image is not None
    if is_demo_trip(trip_id):
        # 演示行程没有录像，用同一条轨迹的高德底图当背景，
        # 比纯色块更能看出字幕落在画面上的观感。
        return demo_background(width, height), ""
    if remote.segments:
        try:
            segment, local_ms = preview_segment_at(remote, sample_ms, workspace)
            local_video = best_local_file(workspace, trip_id, segment.name)
            if video_is_complete(local_video, segment.bytes):
                return video_frame(local_video, local_ms, width, height), ""
        except Exception:  # noqa: BLE001 - 本地文件坏了再向手机要
            pass
    video = cached_trip_video(trip_id, workspace)
    if video is not None:
        try:
            return video_frame(video, sample_ms, width, height), ""
        except Exception:  # noqa: BLE001 - 抽帧失败就继续往下退回
            pass
    if remote.segments:
        try:
            import sys
            root = Path(__file__).resolve().parent
            if str(root) not in sys.path:
                sys.path.insert(0, str(root))
            import remote_frame

            segment, local_ms = preview_segment_at(remote, sample_ms, workspace)
            jpeg = remote_frame.frame_jpeg(
                segment.public_path,
                local_ms,
                max_width=min(1280, width),
                cache_dir=workspace / "frame-cache",
            )
            with Image.open(io.BytesIO(jpeg)) as handle:
                return _cover_image(handle.convert("RGB"), width, height), ""
        except Exception as exc:  # noqa: BLE001 - 手机抽帧失败再退缩略图
            print(f"预览抽帧失败: {exc}", flush=True)
    thumbnail = remote_thumbnail(trip_id, workspace, remote)
    if thumbnail is not None:
        try:
            with Image.open(thumbnail) as handle:
                return _cover_image(handle.convert("RGB"), width, height), ""
        except Exception:  # noqa: BLE001 - 缩略图损坏时退回纯色底
            pass
    return Image.new("RGB", (width, height), (14, 20, 18)), "没能从手机抽出这一帧，预览暂时是黑底"


_DURATION_CACHE: dict[tuple[str, int, int], int] = {}


def cached_probe_duration_ms(video: Path) -> int:
    """带缓存地探测本地视频时长（毫秒），避免频繁调用 ffprobe。"""
    stat = video.stat()
    key = (str(video), stat.st_mtime_ns, stat.st_size)
    with MEM_CACHE_LOCK:
        cached = _DURATION_CACHE.get(key)
        if cached is not None:
            return cached
    duration = probe_duration_ms(video)
    with MEM_CACHE_LOCK:
        if len(_DURATION_CACHE) > 32:
            _DURATION_CACHE.clear()
        _DURATION_CACHE[key] = duration
    return duration


def preview_duration_ms(trip_id: str, workspace: Path) -> int:
    """该行程可供预览的总时长：各段录像相加，缺录像时退回轨迹末点。"""
    if is_demo_trip(trip_id):
        return max((point.elapsed_ms for point in demo_track_points()), default=0)
    total = 0
    try:
        remote = open_trip(trip_id, workspace)
    except Exception:  # noqa: BLE001 - 清单拿不到就退回单文件
        remote = None
    if remote is not None:
        for segment in remote.segments:
            video = best_local_file(workspace, trip_id, segment.name)
            if not video_is_complete(video, segment.bytes):
                continue
            try:
                total += cached_probe_duration_ms(video)
            except Exception:  # noqa: BLE001 - 这一段读不出时长就跳过
                continue
    if total > 0:
        return total
    video = cached_trip_video(trip_id, workspace)
    if video is not None:
        try:
            return max(1, cached_probe_duration_ms(video))
        except Exception:  # noqa: BLE001
            pass
    try:
        points = preview_track_points(open_trip(trip_id, workspace), workspace)
    except Exception:  # noqa: BLE001
        return 0
    return max((point.elapsed_ms for point in points), default=0)


def overlay_preview_image(trip_id: str, workspace: Path, query: dict[str, str]) -> Any:
    """渲染“真实画面 + 排版叠加层”的预览，尺寸与排版画布一致。"""
    assert Image is not None
    # 前端会把正在编辑、还没保存的排版传给 layout 参数。
    # 只读磁盘上的 layout.json 的话，用户拖了滑块却要等保存后才看到效果。
    layout = load_layout()
    draft = query.get("layout")
    if draft:
        try:
            layout = layout_from_dict(json.loads(draft))
        except (json.JSONDecodeError, TypeError, ValueError):
            pass
    remote = open_trip(trip_id, workspace)
    sample_ms = max(0, int(float(query.get("t") or 0) * 1000))
    values, speed_kmh = sample_trip_values(remote, workspace, sample_ms)
    width, height = layout.width, layout.height

    background, _note = preview_background(trip_id, workspace, remote, sample_ms, width, height)
    map_points = preview_track_points(remote, workspace)
    overlay = render_overlay_frame(layout, values, speed_kmh, width, height, sample_ms, map_points)
    composed = Image.alpha_composite(background.convert("RGBA"), overlay)
    # 浏览器覆盖层里已经有参考稿那张地图。再叠 PIL 地图，预览上会有两块。
    if map_points and not browser_overlay_active():
        map_layer = render_map_layer_frame(layout, map_points, sample_ms, width, height, values)
        composed = Image.alpha_composite(composed, map_layer)
    return composed.convert("RGB")


def overlay_preview_live(
    trip_id: str,
    workspace: Path,
    query: dict[str, str],
    *,
    include_background: bool = True,
    include_html: bool = True,
) -> dict[str, Any]:
    """编辑器预览：底图和覆盖层 HTML 分开给。拖动时只改 HTML 里的位置，不用重拍整页。"""
    assert Image is not None
    layout = load_layout()
    draft = query.get("layout")
    if draft:
        try:
            layout = layout_from_dict(json.loads(draft))
        except (json.JSONDecodeError, TypeError, ValueError):
            pass
    remote = open_trip(trip_id, workspace)
    sample_ms = max(0, int(float(query.get("t") or 0) * 1000))
    width, height = layout.width, layout.height
    src_w, src_h = detect_trip_source_size(trip_id, workspace, remote)
    exp_w, exp_h = resolve_export_dimensions(src_w, src_h, layout)
    html = ""
    # 播放时只要新的一帧底图。重做覆盖层会再扫一遍字幕和地图，iframe 也会整页重载。
    map_points: list[TrackPoint] = []
    map_radars: list[TrackRadar] = []
    map_frame_payload: dict[str, float] | None = None
    track_payload: list[list[float]] = []
    radars_payload: list[list[float]] = []
    max_speed_payload: list[float] | None = None
    cues_payload: list[dict[str, Any]] = []
    total_distance_m = 0.0
    priv_cfg = getattr(layout, "privacy", None) or PrivacyLayout()
    priv_enabled = bool(getattr(priv_cfg, "enabled", True))
    priv_meters = max(0, min(10000, int(getattr(priv_cfg, "trim_meters", 1000) or 1000)))
    full_dur_ms = preview_duration_ms(trip_id, workspace) if include_html else 0
    priv_start_ms = 0
    priv_end_ms = full_dur_ms
    if include_html:
        raw_map_points = preview_track_points(remote, workspace)
        if priv_enabled and priv_meters > 0 and len(raw_map_points) >= 2:
            priv_start_ms, priv_end_ms, map_points = compute_privacy_trim_bounds(
                raw_map_points,
                full_dur_ms or (raw_map_points[-1].elapsed_ms + 1000),
                enabled=True,
                trim_meters=float(priv_meters),
            )
            if sample_ms < priv_start_ms or sample_ms > priv_end_ms:
                sample_ms = max(priv_start_ms, min(priv_end_ms, sample_ms))
        else:
            map_points = raw_map_points
        values, speed_kmh = sample_trip_values(remote, workspace, sample_ms)
        map_radars = preview_track_radars(remote, workspace)
        html = overlay_html_for(
            layout,
            values,
            speed_kmh,
            width,
            height,
            sample_ms,
            map_points,
            font_base="/assets/fonts/",
            track_radars=map_radars,
        ) or ""
        try:
            import overlay_widgets
            map_center = point_at(map_points, sample_ms) if len(map_points) >= 2 else None
            is_split = getattr(layout, "layout_mode", "float") == "split"
            split_pct = max(25, min(65, int(getattr(layout, "split_video_percent", 38) or 38)))
            if is_split:
                map_aspect = float(width) / max(1.0, float(height) * (100.0 - split_pct) / 100.0)
            else:
                mw, mh, _ = overlay_widgets.map_card_size(layout.map_layer.size_percent, width, height)
                map_aspect = mw / max(1.0, mh)
            cycle_on = bool(getattr(layout.map_layer, "cycle_overview_enabled", True))
            ov_sec = float(getattr(layout.map_layer, "overview_seconds", 10) or 10)
            dt_sec = float(getattr(layout.map_layer, "detail_seconds", 50) or 50)
            out_sec = (sample_ms / 1000.0) / max(0.25, float(getattr(layout, "playback_speed", 1.0) or 1.0))
            ov_blend = (
                overlay_widgets.cycle_overview_blend_factor(out_sec, ov_sec, dt_sec)
                if (cycle_on and layout.map_layer.scale_meters > 0)
                else (1.0 if layout.map_layer.scale_meters <= 0 else 0.0)
            )
            frame = overlay_widgets.route_mercator_frame(
                map_points,
                scale_meters=layout.map_layer.scale_meters,
                center=map_center,
                aspect=map_aspect,
                overview_blend=ov_blend,
            )
            full_frame = overlay_widgets.route_mercator_frame(
                map_points,
                scale_meters=0,
                center=None,
                aspect=map_aspect,
                overview_blend=1.0,
            )
            if frame is not None:
                _, ox, oy, sx, sy = frame
                fox, foy, fsx, fsy = (full_frame[1], full_frame[2], full_frame[3], full_frame[4]) if full_frame else (ox, oy, sx, sy)
                map_frame_payload = {
                    "originX": ox,
                    "originY": oy,
                    "spanX": sx,
                    "spanY": sy,
                    "fullOriginX": fox,
                    "fullOriginY": foy,
                    "fullSpanX": fsx,
                    "fullSpanY": fsy,
                    "aspect": map_aspect,
                    "overviewBlend": round(ov_blend, 4),
                    "scaleMeters": float(layout.map_layer.scale_meters or 0),
                }
            for pt in map_points:
                mx, my = overlay_widgets.amap_mercator(pt.latitude, pt.longitude)
                track_payload.append([
                    int(pt.elapsed_ms),
                    mx,
                    my,
                    round(pt.latitude, 6),
                    round(pt.longitude, 6),
                    round(float(pt.speed_kmh), 2),
                ])
            peak_pt = overlay_widgets.max_speed_point(map_points)
            if peak_pt is not None:
                pmx, pmy = overlay_widgets.amap_mercator(peak_pt.latitude, peak_pt.longitude)
                max_speed_payload = [pmx, pmy, round(float(peak_pt.speed_kmh), 1)]
            for r_lat, r_lon, r_spd in overlay_widgets.deduplicate_radars(map_radars):
                rmx, rmy = overlay_widgets.mercator_unit(r_lat, r_lon)
                radars_payload.append([rmx, rmy, int(r_spd)])
        except Exception:  # noqa: BLE001
            pass
        _, total_distance_m, _ = track_progress(map_points, sample_ms)
        for cue in trip_cues(remote, workspace):
            fv = cue_field_values(cue.text)
            cues_payload.append({
                "startMs": cue.start_ms,
                "endMs": cue.end_ms,
                "time": fv.get("time") or "",
                "address": fv.get("address") or "",
                "coordinates": fv.get("coordinates") or "",
                "satellites": fv.get("satellites") or "",
                "focus": fv.get("focus") or "",
                "videoMode": fv.get("videoMode") or "",
                "waitTime": fv.get("waitTime") or "",
                "speed": fv.get("speed") or "",
            })
    payload: dict[str, Any] = {
        "width": width,
        "height": height,
        "sourceWidth": src_w,
        "sourceHeight": src_h,
        "exportWidth": exp_w,
        "exportHeight": exp_h,
        "exportResolution": getattr(layout, "export_resolution", "source") or "source",
        "durationMs": preview_duration_ms(trip_id, workspace) if include_html else 0,
        "privacyStartMs": priv_start_ms,
        "privacyEndMs": priv_end_ms,
        "privacyEnabled": priv_enabled,
        "privacyTrimMeters": priv_meters,
        "html": html,
        # 播放时表盘按这条曲线走，不再停在预览那一帧。点就是字幕里的全部速度。
        "speeds": [[ms, speed] for ms, speed in speed_series(remote, workspace)],
        "mapFrame": map_frame_payload,
        "trackPoints": track_payload,
        "radars": radars_payload,
        "maxSpeedPoint": max_speed_payload,
        "cues": cues_payload,
        "totalDistanceMeters": round(total_distance_m, 1),
    }
    if include_background:
        background, note = preview_background(trip_id, workspace, remote, sample_ms, width, height)
        buffer = io.BytesIO()
        background.convert("RGB").save(buffer, format="JPEG", quality=72)
        payload["background"] = "data:image/jpeg;base64," + base64.b64encode(buffer.getvalue()).decode("ascii")
        if note:
            payload["backgroundNote"] = note
    return payload


def demo_background(width: int, height: int) -> Any:
    """演示行程的预览底图：深色路网背景。

    颜色必须跟参考稿一致（#0f141c 深蓝黑 + 斜向网格）。之前用浅灰占位，
    半透明的深色面板叠上去会被冲淡成白雾，橙色的发光也完全出不来。
    HUD 这套视觉本来就是给深色行车画面设计的。
    """
    assert Image is not None
    cache_key = (width, height)
    with MEM_CACHE_LOCK:
        cached = _DEMO_BACKGROUND_CACHE.get(cache_key)
        if cached is not None:
            return cached
    canvas = Image.new("RGB", (width, height), (15, 20, 28))
    draw = ImageDraw.Draw(canvas)
    # 中心径向亮区，参考稿是 radial-gradient(circle at 50% 40%, #2b3a4a, #151b23)
    glow = Image.new("RGB", (width, height), (21, 27, 35))
    glow_draw = ImageDraw.Draw(glow)
    cx, cy = width / 2, height * 0.4
    for step in range(28, 0, -1):
        ratio = step / 28
        rx, ry = width * 0.62 * ratio, height * 0.72 * ratio
        shade = int(43 - 22 * ratio)
        glow_draw.ellipse((cx - rx, cy - ry, cx + rx, cy + ry), fill=(shade, shade + 15, shade + 26))
    canvas = Image.blend(canvas, glow, 0.85)
    draw = ImageDraw.Draw(canvas)
    # 斜向网格：参考稿用 perspective 造的透视地面，这里用斜线近似。
    grid = max(24, int(min(width, height) / 22))
    for offset in range(-height, width + height, grid):
        draw.line((offset, height, offset + height * 0.9, height * 0.38), fill=(30, 38, 48), width=1)
    for index in range(1, 14):
        y = height * 0.38 + (height * 0.62) * (index / 14) ** 1.6
        draw.line((0, y, width, y), fill=(30, 38, 48), width=1)
    with MEM_CACHE_LOCK:
        if len(_DEMO_BACKGROUND_CACHE) > 3:
            _DEMO_BACKGROUND_CACHE.clear()
        _DEMO_BACKGROUND_CACHE[cache_key] = canvas
    return canvas


def join_track_segments(groups: list[list[TrackPoint]]) -> list[TrackPoint]:
    """按录像顺序接上各段轨迹。

    每段的 elapsedMs 都从 0 起算。混在一起按毫秒排序时，后一段的开头会插进前一段，
    地图上就会拉出横跨整张图的弦。
    """
    ordered: list[TrackPoint] = []
    cursor = 0
    for group in groups:
        if not group:
            continue
        segment = sorted(group, key=lambda point: point.elapsed_ms)
        if ordered:
            delta = cursor - segment[0].elapsed_ms
            if delta:
                segment = [
                    TrackPoint(
                        elapsed_ms=point.elapsed_ms + delta,
                        time_ms=point.time_ms,
                        latitude=point.latitude,
                        longitude=point.longitude,
                        speed_kmh=point.speed_kmh,
                    )
                    for point in segment
                ]
        ordered.extend(segment)
        cursor = segment[-1].elapsed_ms + 1
    return ordered


def preview_track_points(remote: RemoteTrip, workspace: Path) -> list[TrackPoint]:
    """预览用轨迹：从已缓存的 SRT/轨迹里取，够画整条路线即可。"""
    if is_demo_trip(remote.id):
        return demo_track_points()
    groups: list[list[TrackPoint]] = []
    for segment in remote.segments:
        path = segment.track_path or segment.subtitle_path
        if not path:
            continue
        local = resolve_local_sidecar(remote, workspace, path)
        if local is None:
            continue
        group: list[TrackPoint] = []
        if local.suffix == ".json":
            try:
                data = json.loads(local.read_text(encoding="utf-8", errors="ignore"))
            except json.JSONDecodeError:
                continue
            for item in data.get("points", []):
                lat, lon = item.get("latitude"), item.get("longitude")
                if not isinstance(lat, (int, float)) or not isinstance(lon, (int, float)):
                    continue
                group.append(
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
                group.append(
                    TrackPoint(
                        elapsed_ms=cue.start_ms,
                        time_ms=0,
                        latitude=float(match.group("lat")),
                        longitude=float(match.group("lon")),
                        speed_kmh=speed,
                    )
                )
                elapsed = cue.start_ms
        if group:
            groups.append(group)
    return join_track_segments(groups)


def preview_track_radars(remote: RemoteTrip, workspace: Path) -> list[TrackRadar]:
    """从已缓存的 `.track.json` 读取行程测速雷达点列表（高德 GCJ-02 坐标）。"""
    if is_demo_trip(remote.id):
        return demo_track_radars()
    radars: list[TrackRadar] = []
    for segment in remote.segments:
        if not segment.track_path:
            continue
        local = resolve_local_sidecar(remote, workspace, segment.track_path)
        if local is None or local.suffix != ".json":
            continue
        try:
            data = json.loads(local.read_text(encoding="utf-8", errors="ignore"))
        except json.JSONDecodeError:
            continue
        for item in data.get("radars", []) or []:
            if not isinstance(item, dict):
                continue
            lat, lon = item.get("latitude"), item.get("longitude")
            if not isinstance(lat, (int, float)) or not isinstance(lon, (int, float)):
                continue
            radars.append(
                TrackRadar(
                    latitude=float(lat),
                    longitude=float(lon),
                    speed_limit_kmh=int(item.get("speedLimitKmh", 0) or 0),
                    camera_type=int(item.get("cameraType", 0) or 0),
                )
            )
    return radars


def demo_trip_values(sample_ms: int) -> tuple[dict[str, str], float]:
    """演示行程的字段值：坐标跟着轨迹走，速度做一点起伏，看着才像在开车。"""
    points = demo_track_points()
    point = point_at(points, sample_ms)
    # 用位置算一个 0.75~1.25 的倍数，速度表指针会动，但不是随机跳。
    phase = (sample_ms / 1000.0) * 0.6
    speed_kmh = round(DEMO_SPEED_KMH * (1.0 + 0.25 * math.sin(phase)), 1)
    moved = datetime.fromtimestamp(point.time_ms / 1000)
    return {
        "time": moved.strftime("%Y-%m-%d %H:%M:%S"),
        "speed": f"{speed_kmh:.1f} km/h",
        "satellites": "GPS卫星：19",
        "coordinates": f"{point.latitude:.5f},{point.longitude:.5f}",
        "address": "杭州市上城区 · 中河高架",
        "focus": "对焦 0.07D(14m) · MF",
        "videoMode": "4K 3840x2160 · 30fps",
        "waitTime": "静止等待：00:00:00",
    }, speed_kmh


_SPEED_SERIES_CACHE: dict[tuple[Any, ...], list[tuple[int, float]]] = {}


def trip_cues(remote: RemoteTrip, workspace: Path) -> list[Cue]:
    """行程时间轴上的全部字幕。后一段按片长接在前一段后面，一条不丢。"""
    cues: list[Cue] = []
    for segment in remote.segments:
        subtitle_path = segment.subtitle_path
        if not subtitle_path:
            continue
        local = resolve_local_sidecar(remote, workspace, subtitle_path)
        if local is None:
            continue
        stem = segment_stem(Path(subtitle_path).name)
        offset_ms = segment_offset_ms(remote, segment, stem, workspace)
        cues.extend(parse_srt(local, offset_ms))
    cues.sort(key=lambda cue: cue.start_ms)
    return cues


def speed_series(remote: RemoteTrip, workspace: Path) -> list[tuple[int, float]]:
    """每个字幕点的 GPS 速度，按时间排好。不抽稀。"""
    if is_demo_trip(remote.id):
        return []
    stamps: list[tuple[str, int, int]] = []
    for segment in remote.segments:
        subtitle_path = segment.subtitle_path
        if not subtitle_path:
            continue
        local = resolve_local_sidecar(remote, workspace, subtitle_path)
        if local is None:
            continue
        stat = local.stat()
        stamps.append((str(local), stat.st_mtime_ns, stat.st_size))
    key = (remote.id, tuple(stamps))
    with MEM_CACHE_LOCK:
        cached = _SPEED_SERIES_CACHE.get(key)
        if cached is not None:
            return cached
    series: list[tuple[int, float]] = []
    for cue in trip_cues(remote, workspace):
        speed = extract_speed_kmh(cue.text)
        if series and series[-1][0] == cue.start_ms:
            series[-1] = (cue.start_ms, speed)
        else:
            series.append((cue.start_ms, speed))
    with MEM_CACHE_LOCK:
        if len(_SPEED_SERIES_CACHE) > 8:
            _SPEED_SERIES_CACHE.clear()
        _SPEED_SERIES_CACHE[key] = series
    return series


def speed_at(series: list[tuple[int, float]], elapsed_ms: int) -> float | None:
    """两点之间按时间线性插值。落在样本上时就是记录值本身。"""
    if not series:
        return None
    if elapsed_ms <= series[0][0]:
        return series[0][1]
    if elapsed_ms >= series[-1][0]:
        return series[-1][1]
    lo = 0
    hi = len(series) - 1
    while lo + 1 < hi:
        mid = (lo + hi) // 2
        if series[mid][0] <= elapsed_ms:
            lo = mid
        else:
            hi = mid
    left_ms, left_speed = series[lo]
    right_ms, right_speed = series[hi]
    span = max(1, right_ms - left_ms)
    ratio = (elapsed_ms - left_ms) / span
    return left_speed + (right_speed - left_speed) * ratio


def sample_trip_values(
    remote: RemoteTrip,
    workspace: Path,
    sample_ms: int,
) -> tuple[dict[str, str], float]:
    """按时间取出该时刻的字段值和速度；没有字幕时给一组可预览的示例值。"""
    if is_demo_trip(remote.id):
        return demo_trip_values(sample_ms)
    cues = trip_cues(remote, workspace)
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
    chosen = next((cue for cue in cues if cue.end_ms > sample_ms), cues[-1])
    values = cue_field_values(chosen.text)
    # 字幕大约一秒一条。整秒拿着开头那个数，加速时表盘会比车慢一拍。
    # 相邻样本之间插值，样本一个不丢。
    interpolated = speed_at(speed_series(remote, workspace), sample_ms)
    if interpolated is None:
        speed_kmh = extract_speed_kmh(values.get("speed"))
    else:
        speed_kmh = interpolated
        values["speed"] = f"{speed_kmh:.1f} km/h"
    return values, speed_kmh


def extract_speed_kmh(text: str | None) -> float:
    if not text:
        return 0.0
    match = SRT_SPEED_RE.search(text)
    return float(match.group("speed")) if match else 0.0


def segment_offset_ms(
    remote: RemoteTrip,
    segment: RemoteSegment,
    stem: str,
    workspace: Path | None = None,
) -> int:
    """复用首段时长推算时间轴偏移；拿不到时长就按 0 处理。"""
    index = remote.segments.index(segment)
    if index == 0:
        return 0
    durations = segment_cached_durations_ms(remote, workspace)
    if len(durations) != len(remote.segments):
        return 0
    return sum(durations[:index])


def adb_pull_cached(remote_path: str, workspace: Path) -> Path | None:
    cache_dir = workspace / "srt-cache"
    digest = hashlib.sha1(remote_path.encode("utf-8")).hexdigest()[:20]
    # 后缀必须跟远端一致：调用方靠 .json / .srt 区分轨迹和字幕，
    # 早先统一写死 .srt，导致 .track.json 被当成字幕后解析不出任何轨迹点。
    suffix = Path(remote_path).suffix or ".bin"
    local = cache_dir / f"{digest}{suffix}"
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


def segment_cached_durations_ms(
    remote: RemoteTrip,
    workspace: Path | None = None,
) -> list[int]:
    """用相邻分段的录像起始时间推算每段时长，避免为预览下完整个视频。"""
    starts = [VIDEO_STAMP_RE.search(segment.name) for segment in remote.segments]
    stamps = [
        parse_compact_time_ms(f"{match.group(1)}-{match.group(2)}") if match else 0
        for match in starts
    ]
    durations: list[int] = []
    for index, stamp in enumerate(stamps):
        probed = 0
        if workspace is not None:
            seg = remote.segments[index]
            local_video = best_local_file(workspace, remote.id, seg.name)
            if video_is_complete(local_video, seg.bytes) and local_video is not None:
                try:
                    probed = max(0, cached_probe_duration_ms(local_video))
                except Exception:  # noqa: BLE001
                    probed = 0
        if probed > 0:
            durations.append(probed)
            continue
        next_stamp = stamps[index + 1] if index + 1 < len(stamps) else 0
        durations.append(next_stamp - stamp if next_stamp > stamp else 0)
    return durations


def _reuse_or_copy_local_file(source: Path, target: Path) -> None:
    """优先使用硬链接复用本机已有完整文件，跨文件系统时退回复制。"""
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists():
        return
    try:
        os.link(source, target)
    except OSError:
        shutil.copy2(source, target)


def pull_trip(
    trip: str,
    work_root: Path,
    progress: Callable[[int, str], None],
    workspace: Path | None = None,
) -> PulledTrip:
    """准备行程素材目录：优先复用资料库已有副本，缺失时再通过 adb 拉取。"""
    inferred_workspace = workspace
    if inferred_workspace is None:
        for candidate in (work_root.parent, work_root.parent.parent):
            if (candidate / "library").is_dir():
                inferred_workspace = candidate
                break
    remote = open_trip(trip, inferred_workspace)
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
            (segment.public_path, videos, segment.bytes),
            (segment.track_path, tracks, 0),
            (segment.subtitle_path, subtitles, 0),
            (segment.plate_path, plates, 0),
        ]
        for remote_path, destination, expected_bytes in file_jobs:
            if not remote_path:
                continue
            local = raw_dir / Path(remote_path).name
            if local.exists() and local.stat().st_size > 0:
                destination.append(local)
                pulled += 1
                continue
            if inferred_workspace is not None:
                cached = best_local_file(inferred_workspace, trip, local.name)
                ready = (
                    video_is_complete(cached, expected_bytes)
                    if expected_bytes > 0
                    else _file_size(cached) > 0
                )
                if cached is not None and ready:
                    _reuse_or_copy_local_file(cached, local)
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


# 字幕里的坐标有两种写法：「30.30408,120.06519」和「30.304080°N 120.165600°E」。
# 只认逗号时，手机上的 °N/°E 会被整段丢掉，或者误配到别的数字上。
SRT_COORDINATE_RE = re.compile(
    r"(?P<lat>-?\d{1,3}\.\d{4,})\s*°?\s*[NSns]?\s*[, ]\s*(?P<lon>-?\d{1,3}\.\d{4,})\s*°?\s*[EWew]?"
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


@lru_cache(maxsize=256)
def _probe_size_by_stat(path_str: str, mtime_ns: int, size_bytes: int) -> tuple[int, int]:
    """按文件路径与 stat 指纹缓存视频宽高，避免每次刷新列表重复调用 ffprobe。"""
    return probe_size(Path(path_str))


def probe_size_cached(video: Path) -> tuple[int, int]:
    """带文件指纹缓存的快速视频分辨率探测。"""
    st = video.stat()
    return _probe_size_by_stat(str(video.resolve()), st.st_mtime_ns, st.st_size)


def detect_trip_source_size(trip_id: str, workspace: Path | None, remote: RemoteTrip | None = None) -> tuple[int, int]:
    """探测某趟行程原视频的真实分辨率 `(src_width, src_height)`。

    1. 优先从本地已下载或已缓存的 MP4 视频流调用 `probe_size_cached` 精确读取；
    2. 若视频仍在手机未拉取，则从字幕的 `videoMode` 行（如 `3840x2160` / `4K` / `1080p`）解析；
    3. 兜底返回 `(1920, 1080)`。
    """
    if workspace is not None and not is_demo_trip(trip_id):
        local_video = cached_trip_video(trip_id, workspace)
        if local_video is not None and local_video.is_file():
            try:
                return probe_size_cached(local_video)
            except Exception:  # noqa: BLE001
                pass
    if remote is not None and workspace is not None:
        try:
            for cue in trip_cues(remote, workspace)[:10]:
                m = re.search(r"(\d{3,4})\s*[xX×]\s*(\d{3,4})", cue.text)
                if m:
                    return even(int(m.group(1))), even(int(m.group(2)))
                if "4k" in cue.text.lower() or "2160p" in cue.text.lower():
                    return (3840, 2160)
        except Exception:  # noqa: BLE001
            pass
    return (1920, 1080)


def resolve_export_dimensions(
    src_width: int,
    src_height: int,
    layout: TripLayout,
    options: ExportOptions | None = None,
) -> tuple[int, int]:
    """根据原视频分辨率、当前画布模式/方向以及用户选择的导出分辨率档位，计算最终导出宽高 `(width, height)`。"""
    layout_mode = str(getattr(layout, "layout_mode", "float") or "float")
    cfg_w = int(getattr(layout, "width", 1920) or 1920)
    cfg_h = int(getattr(layout, "height", 1080) or 1080)
    is_portrait = (layout_mode == "split") or (cfg_h > cfg_w)

    if options is not None and options.export_width and options.export_height:
        ew = even(max(320, min(7680, int(options.export_width))))
        eh = even(max(240, min(7680, int(options.export_height))))
        if layout_mode == "split" and ew > eh:
            ew, eh = eh, ew
        return ew, eh

    tier = (
        str(options.export_resolution).strip().lower()
        if (options is not None and options.export_resolution)
        else str(getattr(layout, "export_resolution", "source") or "source").strip().lower()
    )
    tier_short_map = {
        "2160p": 2160,
        "4k": 2160,
        "1440p": 1440,
        "2k": 1440,
        "1080p": 1080,
        "720p": 720,
    }
    if tier == "custom":
        ew = even(max(320, min(7680, cfg_w)))
        eh = even(max(240, min(7680, cfg_h)))
        if layout_mode == "split" and ew > eh:
            ew, eh = eh, ew
        return ew, eh
    if tier in tier_short_map:
        short_side = tier_short_map[tier]
        long_side = even(int(round(short_side * 16.0 / 9.0)))
        return (short_side, long_side) if is_portrait else (long_side, short_side)
    # tier == "source"：自动对齐原视频清晰度档位
    src_short = max(360, min(src_width, src_height))
    src_long = max(640, max(src_width, src_height))
    if layout_mode == "split":
        short_side = even(src_short)
        long_side = even(int(round(short_side * 16.0 / 9.0)))
        return (short_side, long_side)
    if is_portrait != (src_height > src_width):
        return (even(src_short), even(src_long)) if is_portrait else (even(src_long), even(src_short))
    return even(src_width), even(src_height)
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
                    "track=V1", "audio=true",
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

    # 浏览器覆盖层已经包含地图卡片，单独的 PIL 地图轨会再画一块对不上的样式。
    map_path = None if browser_overlay_active() else generate_map_layer_video(pulled, track_points, progress, layout=layout)
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
    """根据导出选项判断某行字幕是否保留。"""
    category = subtitle_category(line.strip())
    return {
        "time": options.subtitle_time,
        "speed": options.subtitle_speed,
        "gps": options.subtitle_gps,
        "address": options.subtitle_address,
        "focus": options.subtitle_focus,
        "video": options.subtitle_video_mode,
        "waitTime": options.subtitle_time,
        "satellites": options.subtitle_gps,
        "radar": options.subtitle_speed,
    }.get(category, True)


def subtitle_category(text: str) -> str:
    if re.search(r"\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}", text):
        return "time"
    # 雷达提示形如「测速 80kph×10」「区间测速 100kph×4」，必须排在速度之前判断，
    # 否则会被当成普通速度行，地址栏最终显示成一串雷达提示。
    if "测速" in text or "雷达" in text:
        return "radar"
    if "km/h" in text.lower() or "kph" in text.lower() or "mph" in text.lower():
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


def read_track_radars(pulled: PulledTrip) -> list[TrackRadar]:
    """从拉取到本地的 `.track.json` 文件集合中读取全部测速雷达点（GCJ-02 坐标）。"""
    radars: list[TrackRadar] = []
    track_by_name = {path.name: path for path in pulled.tracks}
    for video in pulled.videos:
        track = track_by_name.get(video.with_suffix(".track.json").name)
        if not track:
            continue
        try:
            data = json.loads(track.read_text(encoding="utf-8", errors="ignore"))
        except json.JSONDecodeError:
            continue
        for item in data.get("radars", []) or []:
            if not isinstance(item, dict):
                continue
            lat, lon = item.get("latitude"), item.get("longitude")
            if not isinstance(lat, (int, float)) or not isinstance(lon, (int, float)):
                continue
            radars.append(
                TrackRadar(
                    latitude=float(lat),
                    longitude=float(lon),
                    speed_limit_kmh=int(item.get("speedLimitKmh", 0) or 0),
                    camera_type=int(item.get("cameraType", 0) or 0),
                )
            )
    return radars


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
    err_chunks, err_thread = _start_stderr_drain(process)
    try:
        for frame_index in range(frames):
            output_elapsed = frame_index * 1000 / fps
            source_elapsed = min(end_ms - 1, int(start_ms + output_elapsed * speed))
            process.stdin.write(draw_route_frame(width, height, track_points, source_elapsed, options.map_scale_percent))
            if frame_index % fps == 0:
                progress(28 + frame_index * 18 // frames, "生成底部轨迹图")
        process.stdin.close()
        code = process.wait(timeout=300)
        stderr = _finish_stderr_drain(err_chunks, err_thread)
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
    """合成带仪表盘/地图/字幕覆盖层的 MP4 视频；有浏览器渲染环境时优先输出 Studio 样式。"""
    if Image is None or ImageDraw is None or ImageFont is None:
        raise RuntimeError("缺少 Pillow：请执行 python3 -m pip install pillow")
    if browser_overlay_active():
        return compose_studio_overlay_video(pulled, options, progress)
    progress(25, "准备字幕与轨迹")
    width, height = probe_size(pulled.videos[0])
    raw_track_points = read_track_points(pulled)
    start_ms, end_ms, track_points = resolve_export_range_and_track(
        pulled.total_duration_ms,
        options,
        raw_track_points,
        load_layout(),
    )
    source_duration_ms = max(1, end_ms - start_ms)
    speed = normalized_speed(options.playback_speed)
    output_duration_ms = max(1, math.ceil(source_duration_ms / speed))
    map_height = even(int(height * max(18, min(45, options.map_height_percent)) / 100)) if options.include_route_map else 0
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
        "-realtime",
        "0",
        "-prio_speed",
        "1",
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
    dec_chunks, dec_thread = _start_stderr_drain(decode)
    enc_chunks, enc_thread = _start_stderr_drain(encode)
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
        decode_code = decode.wait(timeout=60)
        encode_code = encode.wait(timeout=60)
        decode_stderr = _finish_stderr_drain(dec_chunks, dec_thread)
        encode_stderr = _finish_stderr_drain(enc_chunks, enc_thread)
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


METAL_COMPOSITOR_SOURCE = Path(__file__).resolve().parent / "metal_compositor.swift"
METAL_COMPOSITOR_BIN = Path(__file__).resolve().parent / "bin" / "carspeed_metal_compositor"
_METAL_BUILD_LOCK = threading.Lock()


def ensure_metal_compositor_bin() -> Path | None:
    """按需编译并返回 macOS Metal 3 + VideoToolbox 零拷贝原生合成引擎可执行文件路径。"""
    if sys.platform != "darwin":
        return None
    with _METAL_BUILD_LOCK:
        if (
            METAL_COMPOSITOR_BIN.is_file()
            and os.access(METAL_COMPOSITOR_BIN, os.X_OK)
            and (
                not METAL_COMPOSITOR_SOURCE.is_file()
                or METAL_COMPOSITOR_BIN.stat().st_mtime >= METAL_COMPOSITOR_SOURCE.stat().st_mtime
            )
        ):
            return METAL_COMPOSITOR_BIN
        if not METAL_COMPOSITOR_SOURCE.is_file():
            return None
        swiftc = shutil.which("swiftc")
        if not swiftc:
            return None
        METAL_COMPOSITOR_BIN.parent.mkdir(parents=True, exist_ok=True)
        tmp_bin = METAL_COMPOSITOR_BIN.with_suffix(".tmp")
        res = subprocess.run(
            [swiftc, "-O", str(METAL_COMPOSITOR_SOURCE), "-o", str(tmp_bin)],
            capture_output=True,
            text=True,
            timeout=90,
            check=False,
        )
        if res.returncode == 0 and tmp_bin.is_file():
            tmp_bin.replace(METAL_COMPOSITOR_BIN)
            return METAL_COMPOSITOR_BIN
        tmp_bin.unlink(missing_ok=True)
        return None


def compose_studio_overlay_video(
    pulled: PulledTrip,
    options: ExportOptions,
    progress: Callable[[int, str], None],
) -> Path:
    """按当前 Studio 排版合成导出 MP4（Metal 3 零拷贝 NV12 原地着色器 + VideoToolbox + 多路异构并发加速）。"""
    import overlay_atlas

    progress(25, "准备 Studio 排版层与轨迹")
    layout = load_layout()
    src_width, src_height = probe_size(pulled.videos[0])
    layout_mode = str(getattr(layout, "layout_mode", "float") or "float")
    split_pct = max(25, min(65, int(getattr(layout, "split_video_percent", 38) or 38)))
    width, height = resolve_export_dimensions(src_width, src_height, layout, options)
    if layout_mode == "split":
        video_dst_x = 0
        video_dst_y = 0
        video_dst_w = width
        video_dst_h = even(max(160, min(height - 160, int(round(height * split_pct / 100.0)))))
    else:
        video_dst_x = 0
        video_dst_y = 0
        video_dst_w = width
        video_dst_h = height
    raw_track_points = read_track_points(pulled)
    start_ms, end_ms, track_points = resolve_export_range_and_track(
        pulled.total_duration_ms,
        options,
        raw_track_points,
        layout,
    )
    source_duration_ms = max(1, end_ms - start_ms)
    raw_opt_speed = options.playback_speed if options.playback_speed is not None else getattr(layout, "playback_speed", 1.0)
    speed = normalized_speed(raw_opt_speed)
    output_duration_ms = max(1, math.ceil(source_duration_ms / speed))
    raw_opt_fps = options.fps if options.fps is not None else getattr(layout, "fps", DEFAULT_FPS)
    fps = normalized_fps(raw_opt_fps)
    track_radars = read_track_radars(pulled)
    cues = layout_cues(pulled)
    list_file = pulled.root / "concat.txt"
    list_file.write_text("".join(f"file {shlex.quote(str(video))}\n" for video in pulled.videos), encoding="utf-8")
    output = pulled.root / f"{safe_name(pulled.trip)}-mac-share-{datetime.now().strftime('%Y%m%d-%H%M%S')}.mp4"

    overlay_seconds = max(1, math.ceil(output_duration_ms / 1000))
    # 覆盖层采用 5 fps 平滑刷新（每 200ms 一帧），让卫星底图、路线平移与速度表动画连贯顺滑且车标永久居中
    atlas_fps = 5.0
    total_atlas_frames = max(1, math.ceil(output_duration_ms * atlas_fps / 1000.0))
    # 当导出分辨率大于 1080p（如 4K 2160x3840 竖屏）时，Atlas 覆盖层按最高 1080p 基准渲染，
    # 再由 Metal GPU 着色器按比例无损叠加到目标画面，使单帧 Atlas 体积降低 75%（6.4MB vs 25.6MB）
    atlas_scale = 2 if min(width, height) >= 2160 else 1
    atlas_canvas_w = even(max(320, width // atlas_scale))
    atlas_canvas_h = even(max(240, height // atlas_scale))
    renderer = overlay_atlas.NativeStudioAtlasRenderer(layout, atlas_canvas_w, atlas_canvas_h)
    plan = renderer.plan

    # 先并发预热首帧与中间帧的高德瓦片缓存，避免主循环首次网络 IO 阻塞
    if layout_mode == "split":
        card_w = atlas_canvas_w
        card_h = max(160, atlas_canvas_h - (video_dst_h // atlas_scale))
    else:
        map_w, map_h, _ = overlay_atlas.overlay_widgets.map_card_size(
            float(getattr(layout.map_layer, "size_percent", 0.22) or 0.22),
            atlas_canvas_w,
            atlas_canvas_h,
        )
        card_w = max(96, int(round(map_w)))
        card_h = max(64, int(round(map_h)))
    map_style = str(getattr(layout.map_layer, "map_style", "") or "satellite")
    map_scale = float(getattr(layout.map_layer, "scale_meters", 0) or 0)
    map_aspect = float(card_w) / max(1.0, float(card_h))

    def _warm_tile(sec_idx: int) -> None:
        """预热指定秒数对应的高德地图瓦片到内存缓存。"""
        if not getattr(layout.map_layer, "enabled", True) or len(track_points) < 2:
            return
        src_ms = min(end_ms - 1, int(start_ms + sec_idx * 1000 * speed))
        center_pt = point_at(track_points, src_ms)
        blends = [0.0]
        if bool(getattr(layout.map_layer, "cycle_overview_enabled", True)) and map_scale > 0:
            ov_sec = float(getattr(layout.map_layer, "overview_seconds", 10) or 10)
            dt_sec = float(getattr(layout.map_layer, "detail_seconds", 50) or 50)
            cur_blend = overlay_atlas.overlay_widgets.cycle_overview_blend_factor(
                float(sec_idx), ov_sec, dt_sec
            )
            blends = sorted({0.0, 1.0, round(cur_blend, 2)})
        for b_val in blends:
            amap_card_base_map(
                track_points,
                card_w,
                card_h,
                map_style,
                scale_meters=map_scale,
                center=center_pt,
                aspect=map_aspect,
                overview_blend=b_val,
            )

    progress(30, f"预热 {overlay_seconds} 秒高德地图瓦片与紧凑图集")
    warm_step = max(8, min(20, overlay_seconds // 12 or 8))
    warm_indices = sorted(set(range(0, overlay_seconds, warm_step)) | {max(0, overlay_seconds - 1)})
    with ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(_warm_tile, warm_indices))

    worker_local = threading.local()

    def _render_atlas_at_frame(frame_idx: int) -> bytes:
        """在工作线程局部渲染器上渲染指定覆盖层帧索引的紧凑图集 RGBA 原始字节流。"""
        local_renderer = getattr(worker_local, "renderer", None)
        if local_renderer is None:
            local_renderer = overlay_atlas.NativeStudioAtlasRenderer(layout, atlas_canvas_w, atlas_canvas_h)
            worker_local.renderer = local_renderer
        out_ms = int(round(frame_idx * 1000.0 / atlas_fps))
        src_ms = min(end_ms - 1, int(round(start_ms + out_ms * speed)))
        values = cue_values_at(cues, src_ms) or {}
        speed_kmh = extract_speed_kmh(values.get("speed")) or track_speed_at(track_points, src_ms)
        travelled, total_dist, ratio = track_progress(track_points, src_ms)
        telemetry = {
            "duration": hud_duration_text(max(0, src_ms - start_ms)),
            "distance": (
                f"{format_distance_m(travelled)} / {format_distance_m(total_dist)}"
                if (layout.hud.show_distance or layout_mode == "split") and total_dist > 0
                else "—"
            ),
        }
        atlas_img = local_renderer.render_atlas_frame(
            values=values,
            telemetry=telemetry,
            progress_ratio=ratio,
            speed_kmh=speed_kmh,
            elapsed_ms=src_ms,
            track_points=track_points,
            amap_base_fn=amap_card_base_map,
            point_at_fn=point_at,
            radars=track_radars,
            output_elapsed_sec=out_ms / 1000.0,
        )
        return atlas_img.tobytes()

    metal_bin = ensure_metal_compositor_bin()
    b_str = bitrate(width, height, fps)
    bitrate_bps = int(float(b_str[:-1]) * 1_000_000) if b_str.endswith("M") else 14_000_000
    stab_enabled = (
        bool(options.stabilize)
        if options.stabilize is not None
        else bool(getattr(layout.stabilize, "enabled", True))
    )
    stab_strength = (
        max(10, min(95, int(options.stabilize_strength)))
        if options.stabilize_strength is not None
        else max(10, min(95, int(getattr(layout.stabilize, "strength", 65))))
    )
    stab_crop = (
        max(3, min(15, int(options.stabilize_crop_percent)))
        if options.stabilize_crop_percent is not None
        else max(3, min(15, int(getattr(layout.stabilize, "crop_percent", 6))))
    )
    intro_cfg = getattr(layout, "intro_map", None) or IntroMapLayout()
    intro_enabled = (
        bool(options.intro_map)
        if options.intro_map is not None
        else bool(getattr(intro_cfg, "enabled", True))
    )
    intro_sat_sec = (
        max(0, min(15, int(options.intro_satellite_seconds)))
        if options.intro_satellite_seconds is not None
        else max(0, min(15, int(getattr(intro_cfg, "satellite_seconds", 3))))
    )
    intro_road_sec = (
        max(0, min(15, int(options.intro_road_seconds)))
        if options.intro_road_seconds is not None
        else max(0, min(15, int(getattr(intro_cfg, "road_seconds", 3))))
    )
    intro_scale_m = (
        max(0, min(20000, int(options.intro_scale_meters)))
        if options.intro_scale_meters is not None
        else max(0, min(20000, int(getattr(intro_cfg, "scale_meters", 0))))
    )

    intro_slides_payload: list[dict[str, Any]] = []
    if intro_enabled and len(track_points) >= 2 and (intro_sat_sec > 0 or intro_road_sec > 0):
        progress(33, "生成片头 3s 全屏高德卫星轨迹图与 3s 全屏道路轨迹图")
        _, total_dist_m, _ = track_progress(track_points, end_ms)
        dist_label = f"全程 {format_distance_m(total_dist_m)}" if total_dist_m > 0 else ""
        dur_label = f"时长 {hud_duration_text(source_duration_ms)}"
        slide_specs: list[tuple[str, int]] = []
        if intro_sat_sec > 0:
            slide_specs.append(("satellite", intro_sat_sec))
        if intro_road_sec > 0:
            slide_specs.append(("road", intro_road_sec))

        def _build_intro_slide(spec: tuple[str, int]) -> dict[str, Any]:
            """渲染单张全屏片头轨迹图（卫星或道路）并落盘为 PNG 供合成器读取。"""
            s_style, s_sec = spec
            img = overlay_atlas.render_fullscreen_intro_map_frame(
                layout=layout,
                points=track_points,
                width=width,
                height=height,
                map_style=s_style,
                scale_meters=float(intro_scale_m),
                amap_base_fn=amap_card_base_map,
                title_text=pulled.title or pulled.trip,
                distance_text=dist_label,
                duration_text=dur_label,
                radars=track_radars,
            )
            slide_path = pulled.root / f"intro_map_{s_style}.png"
            img.save(slide_path, format="PNG")
            return {"imagePath": str(slide_path), "durationSec": float(s_sec)}

        with ThreadPoolExecutor(max_workers=2) as intro_pool:
            intro_slides_payload = list(intro_pool.map(_build_intro_slide, slide_specs))

    if metal_bin is not None:
        gc.collect()
        patches_payload = [
            {
                "name": r.name,
                "screenX": r.screen_x * atlas_scale,
                "screenY": r.screen_y * atlas_scale,
                "width": r.width * atlas_scale,
                "height": r.height * atlas_scale,
                "atlasX": r.atlas_x,
                "atlasY": r.atlas_y,
                "srcWidth": r.width,
                "srcHeight": r.height,
            }
            for r in plan.regions
        ]
        segments_payload = [{"path": str(v), "durationMs": d} for v, d in zip(pulled.videos, pulled.durations_ms)]
        # 启动最多 4 路并行 Metal 3 GPU 分片（配合 1080p 紧凑图集 + 有界 2 帧在途窗口），使 GPU 达到 95%~100% 满载且内存严格 < 1GB
        num_shards = 4 if output_duration_ms >= 20_000 else (2 if output_duration_ms >= 8_000 else 1)
        frames_per_shard = max(1, math.ceil(total_atlas_frames / num_shards))
        shard_specs: list[tuple[int, int, int, int, int, Path]] = []
        for s_idx in range(num_shards):
            f_start = s_idx * frames_per_shard
            if f_start >= total_atlas_frames:
                break
            f_end = min(total_atlas_frames, (s_idx + 1) * frames_per_shard)
            s_start_ms = start_ms + int(round((f_start / atlas_fps) * 1000.0 * speed))
            if s_idx == num_shards - 1 or f_end >= total_atlas_frames:
                s_end_ms = end_ms
            else:
                s_end_ms = min(end_ms, start_ms + int(round((f_end / atlas_fps) * 1000.0 * speed)))
            s_dur_ms = max(1, s_end_ms - s_start_ms)
            s_out = output if len(shard_specs) == 0 and num_shards == 1 else (pulled.root / f"metal_shard_{s_idx}.mp4")
            shard_specs.append((s_idx, f_start, f_end, s_start_ms, s_dur_ms, s_out))

        progress(36, f"启动 {len(shard_specs)} 路并行 Metal 3 全 GPU 异步管线 (36-Tap Lanczos-3 防抖 + 25-Tap 细节增强 + 60fps 亚帧插值)")
        completed_frames = 0
        prog_lock = threading.Lock()

        def _run_metal_shard(spec: tuple[int, int, int, int, int, Path]) -> Path:
            """运行单路 Metal 3 GPU 异步合成子进程并流式喂入对应时间片的图集帧。"""
            nonlocal completed_frames
            s_idx, f_start, f_end, s_start_ms, s_dur_ms, s_out = spec
            cfg_path = pulled.root / f"metal_job_shard_{s_idx}.json"
            cfg_payload = {
                "segments": segments_payload,
                "introSlides": intro_slides_payload if s_idx == 0 else [],
                "startMs": s_start_ms,
                "sourceDurationMs": s_dur_ms,
                "playbackSpeed": speed,
                "fps": fps,
                "atlasFps": atlas_fps,
                "bitrate": bitrate_bps,
                "codec": "hevc",
                "stabilize": stab_enabled,
                "stabilizeStrength": stab_strength,
                "stabilizeCropPercent": stab_crop,
                "outputWidth": width,
                "outputHeight": height,
                "videoDstX": video_dst_x,
                "videoDstY": video_dst_y,
                "videoDstW": video_dst_w,
                "videoDstH": video_dst_h,
                "atlasWidth": plan.atlas_width,
                "atlasHeight": plan.atlas_height,
                "patches": patches_payload,
                "outputPath": str(s_out),
            }
            cfg_path.write_text(json.dumps(cfg_payload, ensure_ascii=False), encoding="utf-8")
            proc = subprocess.Popen([str(metal_bin), str(cfg_path)], stdin=subprocess.PIPE, stderr=subprocess.PIPE)
            assert proc.stdin is not None
            err_chunks, err_thread = _start_stderr_drain(proc)
            try:
                workers = 2
                max_in_flight = 2
                with ThreadPoolExecutor(max_workers=workers) as render_pool:
                    it = iter(range(f_start, f_end))
                    pending: deque[Future[bytes]] = deque()
                    for _ in range(max_in_flight):
                        nxt = next(it, None)
                        if nxt is None:
                            break
                        pending.append(render_pool.submit(_render_atlas_at_frame, nxt))
                    while pending:
                        fut = pending.popleft()
                        nxt = next(it, None)
                        if nxt is not None:
                            pending.append(render_pool.submit(_render_atlas_at_frame, nxt))
                        frame_bytes = fut.result()
                        try:
                            proc.stdin.write(frame_bytes)
                        except BrokenPipeError:
                            pending.clear()
                            break
                        del frame_bytes
                        with prog_lock:
                            completed_frames += 1
                            cur_done = completed_frames
                        if cur_done % max(1, int(atlas_fps * 3)) == 0 or cur_done == total_atlas_frames:
                            done_sec = min(overlay_seconds, int(cur_done / atlas_fps) + 1)
                            progress(
                                36 + int(cur_done * 52 / total_atlas_frames),
                                f"Metal 3 全 GPU 异步满载合成 ({done_sec}/{overlay_seconds}s)",
                            )
                try:
                    proc.stdin.close()
                except BrokenPipeError:
                    pass
                code = proc.wait(timeout=900)
                stderr = _finish_stderr_drain(err_chunks, err_thread)
                if code != 0 or not s_out.is_file() or s_out.stat().st_size <= 0:
                    raise RuntimeError(f"Metal 分片 {s_idx} 导出失败\n{stderr}")
                return s_out
            finally:
                gc.collect()
                if proc.poll() is None:
                    proc.kill()

        with ThreadPoolExecutor(max_workers=len(shard_specs)) as shard_pool:
            shard_outputs = list(shard_pool.map(_run_metal_shard, shard_specs))

        if len(shard_outputs) > 1:
            progress(89, "无缝拼接多路 GPU 硬件编码分片")
            concat_list = pulled.root / "metal_shards_concat.txt"
            concat_list.write_text(
                "".join(f"file {shlex.quote(str(p))}\n" for p in shard_outputs),
                encoding="utf-8",
            )
            stitch_cmd = [
                "ffmpeg", "-y", "-v", "error", "-hide_banner",
                "-f", "concat", "-safe", "0",
                "-i", str(concat_list),
                "-c", "copy",
                "-movflags", "+faststart",
                str(output),
            ]
            res = subprocess.run(stitch_cmd, capture_output=True, text=True, timeout=120, check=False)
            if res.returncode != 0 or not output.is_file() or output.stat().st_size <= 0:
                raise RuntimeError(f"分片无损拼接失败\n{res.stderr}")
            for p in shard_outputs:
                p.unlink(missing_ok=True)
        progress(92, "导出完成")
        return output

    filter_complex = overlay_atlas.build_ffmpeg_atlas_filter(
        plan,
        speed=speed,
        fps=fps,
        source_width=width,
        source_height=height,
        target_width=width,
        target_height=height,
        stabilize=stab_enabled,
        stabilize_crop_percent=stab_crop,
    )
    command = [
        "ffmpeg",
        "-y",
        "-v",
        "error",
        "-hide_banner",
        "-hwaccel",
        "videotoolbox",
        "-ss",
        f"{start_ms / 1000.0:.3f}",
        "-t",
        f"{source_duration_ms / 1000.0:.3f}",
        "-f",
        "concat",
        "-safe",
        "0",
        "-i",
        str(list_file),
        "-f",
        "rawvideo",
        "-pix_fmt",
        "rgba",
        "-s",
        f"{plan.atlas_width}x{plan.atlas_height}",
        "-r",
        f"{atlas_fps:g}",
        "-i",
        "pipe:0",
        "-filter_complex",
        filter_complex,
        "-map",
        "[out]",
        "-an",
        "-t",
        f"{output_duration_ms / 1000.0:.3f}",
        "-c:v",
        "h264_videotoolbox",
        "-realtime",
        "0",
        "-prio_speed",
        "1",
        "-b:v",
        bitrate(width, height, fps),
        "-pix_fmt",
        "yuv420p",
        "-movflags",
        "+faststart",
        str(output),
    ]
    progress(36, "启动 macOS VideoToolbox 硬件编解码与紧凑图集直通管线")
    process = subprocess.Popen(command, stdin=subprocess.PIPE, stderr=subprocess.PIPE)
    assert process.stdin is not None
    err_chunks, err_thread = _start_stderr_drain(process)
    last_bytes: bytes | None = None
    try:
        with ThreadPoolExecutor(max_workers=3) as render_pool:
            it = iter(range(total_atlas_frames))
            pending: deque[Future[bytes]] = deque()
            for _ in range(4):
                nxt = next(it, None)
                if nxt is None:
                    break
                pending.append(render_pool.submit(_render_atlas_at_frame, nxt))
            frame_idx = 0
            while pending:
                fut = pending.popleft()
                nxt = next(it, None)
                if nxt is not None:
                    pending.append(render_pool.submit(_render_atlas_at_frame, nxt))
                frame_bytes = fut.result()
                last_bytes = frame_bytes
                process.stdin.write(last_bytes)
                if frame_idx % max(1, int(atlas_fps * 5)) == 0 or frame_idx == total_atlas_frames - 1:
                    done_sec = min(overlay_seconds, int(frame_idx / atlas_fps) + 1)
                    progress(
                        36 + int((frame_idx + 1) * 52 / total_atlas_frames),
                        f"macOS VideoToolbox 硬件合成 ({done_sec}/{overlay_seconds}s)",
                    )
                frame_idx += 1
        # 多写一帧尾帧确保 eof_action=repeat 在整段末尾稳定覆盖
        if last_bytes is not None:
            process.stdin.write(last_bytes)
        process.stdin.close()
        code = process.wait(timeout=900)
        stderr = _finish_stderr_drain(err_chunks, err_thread)
        if code != 0 or not output.is_file() or output.stat().st_size <= 0:
            raise RuntimeError(f"VideoToolbox 导出失败\n{stderr}")
    finally:
        if process.poll() is None:
            process.kill()
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


def _hud_canvas(draw: Any) -> Any:
    """取 draw 对象背后的 RGBA 图层。

    glass_panel / skewed_text 需要把半透明像素贴回去，而 ImageDraw 本身
    不支持 alpha_composite，只能拿到它绑定的 Image。
    """
    return draw._image  # noqa: SLF001 - Pillow 没有公开的取法


def _text_with_shadow(
    canvas: Any,
    xy: tuple[float, float],
    text: str,
    font: Any,
    fill: tuple[int, int, int, int],
    *,
    shadow_alpha: int = 150,
    offset: int = 2,
) -> None:
    """带投影的文字：深色偏移一层再画正文，浅色画面上也立得住。"""
    assert ImageDraw is not None
    draw = ImageDraw.Draw(canvas)
    x, y = int(xy[0]), int(xy[1])
    draw.text(
        (x + offset, y + offset),
        text,
        font=font,
        fill=(0, 0, 0, max(0, min(255, shadow_alpha))),
    )
    draw.text((x, y), text, font=font, fill=fill)


def with_alpha(rgb: tuple[int, int, int], opacity_percent: int) -> tuple[int, int, int, int]:
    alpha = int(255 * max(0, min(100, opacity_percent)) / 100)
    return (rgb[0], rgb[1], rgb[2], alpha)


# ---------------------------------------------------------------------------
# 地平线风格的公共绘制件
#
# 参考稿的观感来自四件事：深色半透明玻璃底、左侧一条高饱和色带、
# 斜切（skew）过的文字、以及所有发光都用「先画亮再高斯模糊」做出来。
# PIL 没有 CSS 的 backdrop-filter，用带 alpha 的圆角矩形加一层叠色近似。
# ---------------------------------------------------------------------------

def hex_with_alpha(value: str, fallback: tuple[int, int, int], opacity_percent: int) -> tuple[int, int, int, int]:
    return with_alpha(hex_to_rgb(value, fallback), opacity_percent)


def glow_layer(size: tuple[int, int], rgb: tuple[int, int, int], blur: int) -> Any:
    """做发光：返回一张模糊过的纯色层，调用方按 screen 方式叠上去。"""
    assert Image is not None and ImageFilter is not None
    layer = Image.new("RGBA", size, (rgb[0], rgb[1], rgb[2], 255))
    return layer.filter(ImageFilter.GaussianBlur(max(1, blur)))


def composite_glow(canvas: Any, mask_source: Any, rgb: tuple[int, int, int], blur: int, strength: float) -> None:
    """把 mask_source 中不透明的部分当作发光形状，模糊后叠加到 canvas。"""
    assert Image is not None and ImageFilter is not None
    if strength <= 0:
        return
    alpha = mask_source.getchannel("A")
    tint = Image.new("RGBA", canvas.size, (rgb[0], rgb[1], rgb[2], 0))
    tint.putalpha(alpha.point(lambda value: int(value * strength)))
    canvas.alpha_composite(tint.filter(ImageFilter.GaussianBlur(max(1, blur))))


def glass_panel(
    canvas: Any,
    box: tuple[float, float, float, float],
    *,
    fill: tuple[int, int, int, int],
    radius: float = 10,
    border: tuple[int, int, int, int] | None = None,
    accent: tuple[int, int, int, int] | None = None,
    accent_width: int = 4,
    corners: tuple[bool, bool, bool, bool] | None = None,
    shadow: bool = True,
) -> None:
    """深色玻璃面板：投影 → 半透明底 → 细边框 → 左侧高亮色带。"""
    assert Image is not None and ImageDraw is not None
    left, top, right, bottom = box
    if shadow:
        shadow_layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
        ImageDraw.Draw(shadow_layer).rounded_rectangle(
            (left + 4, top + 10, right + 4, bottom + 12),
            radius=radius,
            fill=(0, 0, 0, 120),
            corners=corners,
        )
        canvas.alpha_composite(shadow_layer.filter(ImageFilter.GaussianBlur(12)))
    draw = ImageDraw.Draw(canvas)
    draw.rounded_rectangle(box, radius=radius, fill=fill, corners=corners)
    if border is not None:
        draw.rounded_rectangle(box, radius=radius, outline=border, width=1, corners=corners)
    if accent is not None and accent_width > 0:
        # 左侧竖条：参考稿用 border-left 做「赛道边线」，这里画成独立圆角条。
        draw.rounded_rectangle(
            (left, top, left + accent_width, bottom),
            radius=min(accent_width, radius),
            fill=accent,
        )


def skewed_text(
    canvas: Any,
    xy: tuple[float, float],
    text: str,
    font: Any,
    fill: tuple[int, int, int, int],
    *,
    skew: float = -0.18,
    anchor: str | None = None,
) -> tuple[int, int, int, int]:
    """把文字横向斜切，模拟参考稿里的斜体/赛道字体。

    PIL 没有斜切变换，做法是先渲染到独立图层，再按行横向偏移拼回去。
    返回占用区域的 bbox，方便调用方继续排版。
    """
    assert Image is not None and ImageDraw is not None
    probe = ImageDraw.Draw(Image.new("RGBA", (1, 1)))
    left, top, right, bottom = probe.textbbox((0, 0), text, font=font)
    width = max(1, right - left)
    height = max(1, bottom - top)
    pad = int(abs(skew) * height) + 2
    glyph = Image.new("RGBA", (width + pad * 2, height + pad * 2), (0, 0, 0, 0))
    ImageDraw.Draw(glyph).text((pad - left, pad - top), text, font=font, fill=fill)
    # 逐行横向位移：上方行右移，下方行左移，形成斜切效果。
    sheared = Image.new("RGBA", glyph.size, (0, 0, 0, 0))
    for row in range(glyph.height):
        offset = int((0.5 - row / max(1, glyph.height - 1)) * abs(skew) * height * 2)
        if skew > 0:
            offset = -offset
        sheared.alpha_composite(glyph.crop((0, row, glyph.width, row + 1)), (offset, row))
    x, y = xy
    if anchor and "r" in anchor:
        x -= sheared.width
    if anchor and "m" in anchor:
        y -= sheared.height / 2
    canvas.alpha_composite(sheared, (int(x), int(y)))
    return (int(x), int(y), int(x) + sheared.width, int(y) + sheared.height)


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
        elif category == "radar":
            values["radar"] = stripped
        else:
            # 地址只取第一段非结构化的内容；雷达提示等已知字段已经在上面各归各位，
            # 早先它们会落到这个 else 里把真正的地址挤掉。
            values.setdefault("address", stripped)
    return values


def overlay_html_for(
    layout: TripLayout,
    values: dict[str, str],
    speed_kmh: float,
    width: int,
    height: int,
    elapsed_ms: int,
    track_points: list["TrackPoint"],
    font_base: str = "fonts/",
    *,
    track_radars: list["TrackRadar"] | None = None,
    output_elapsed_sec: float | None = None,
) -> str | None:
    """拼出和成片同一份的覆盖层 HTML。编辑器直接拿它来拖，不必等 Chrome 截图。"""
    try:
        import overlay_render
        import overlay_widgets
    except Exception:  # noqa: BLE001
        return None
    travelled, total, ratio = track_progress(track_points, elapsed_ms)
    layout_mode = str(getattr(layout, "layout_mode", "float") or "float")
    is_split = layout_mode == "split"
    split_pct = max(25, min(65, int(getattr(layout, "split_video_percent", 38) or 38)))
    distance_str = (
        f"{format_distance_m(travelled)} / {format_distance_m(total)}"
        if (layout.hud.show_distance or is_split) and total > 0
        else "—"
    )
    telemetry = {
        "duration": hud_duration_text(elapsed_ms),
        "distance": distance_str,
    }
    map_center = point_at(track_points, elapsed_ms) if len(track_points) >= 2 else None
    map_scale = layout.map_layer.scale_meters
    # 底图铺满整张卡片；宽高比和橙色轨迹同一扇墨卡托窗，半径跟右键比例尺走。
    map_width, map_height, _ = overlay_widgets.map_card_size(layout.map_layer.size_percent, width, height)
    if is_split:
        map_width = float(width)
        map_height = max(120.0, float(height) * (100.0 - split_pct) / 100.0)
    map_aspect = float(map_width) / max(1.0, float(map_height))
    cycle_on = bool(getattr(layout.map_layer, "cycle_overview_enabled", True))
    ov_sec = float(getattr(layout.map_layer, "overview_seconds", 10) or 10)
    dt_sec = float(getattr(layout.map_layer, "detail_seconds", 50) or 50)
    playback_spd = max(0.25, float(getattr(layout, "playback_speed", 1.0) or 1.0))
    out_sec = float(output_elapsed_sec) if output_elapsed_sec is not None else (max(0, elapsed_ms) / 1000.0 / playback_spd)
    overview_blend = (
        overlay_widgets.cycle_overview_blend_factor(out_sec, ov_sec, dt_sec)
        if (cycle_on and map_scale > 0)
        else (1.0 if map_scale <= 0 else 0.0)
    )
    path, marker_x, marker_y, marker_angle = overlay_widgets.route_polyline(
        track_points,
        scale_meters=map_scale,
        center=map_center,
        aspect=map_aspect,
        overview_blend=overview_blend,
    )
    played_path = overlay_widgets.route_played_polyline(
        track_points,
        elapsed_ms,
        scale_meters=map_scale,
        center=map_center,
        aspect=map_aspect,
        overview_blend=overview_blend,
    )
    overlays_html = ""
    frame_tuple = overlay_widgets.route_mercator_frame(
        track_points,
        scale_meters=map_scale,
        center=map_center,
        aspect=map_aspect,
        overview_blend=overview_blend,
    )
    if frame_tuple is not None:
        _, ox, oy, sx, sy = frame_tuple
        overlays_html = overlay_widgets.build_map_overlays_html(
            track_points,
            track_radars,
            ox,
            oy,
            sx,
            sy,
            compact_radar=(overview_blend >= 0.45 or map_scale <= 0 or map_scale >= 3000),
        )
    map_image = amap_card_data_url(
        track_points,
        max(96, int(round(map_width))),
        max(96, int(round(map_height))),
        layout.map_layer.map_style or "satellite",
        scale_meters=map_scale,
        center=map_center,
        aspect=map_aspect,
        overview_blend=overview_blend,
    )
    display_values = dict(values)
    if display_values.get("time"):
        display_values["time"] = overlay_widgets._format_hud_timestamp(
            display_values["time"],
            use_24h=bool(getattr(layout.hud, "time_format_24h", True)),
            show_date=bool(getattr(layout.hud, "show_date", False)),
        )
    if is_split:
        # 上视频下地图模式：上方视频区只保留速度显示，隐藏 HUD、底部白栏与独立文字字段
        widgets = "".join(
            [
                overlay_widgets.build_map_widget(
                    layout.map_layer,
                    display_values.get("address") or "",
                    path,
                    marker_x,
                    marker_y,
                    marker_angle,
                    width,
                    height,
                    map_image=map_image,
                    played_polyline=played_path,
                    coordinates=display_values.get("coordinates") or "",
                    layout_mode="split",
                    split_video_percent=split_pct,
                    overlays_html=overlays_html,
                    distance_text=distance_str,
                    speed_kmh=speed_kmh,
                    overview_blend=overview_blend,
                ),
                overlay_widgets.build_speed_widget(layout.gauge, speed_kmh, width, height),
            ]
        )
    else:
        widgets = "".join(
            [
                overlay_widgets.build_timing_widget(layout.hud, display_values, telemetry, ratio, height, width),
                overlay_widgets.build_map_widget(
                    layout.map_layer,
                    display_values.get("address") or "",
                    path,
                    marker_x,
                    marker_y,
                    marker_angle,
                    width,
                    height,
                    map_image=map_image,
                    played_polyline=played_path,
                    coordinates=display_values.get("coordinates") or "",
                    layout_mode="float",
                    split_video_percent=split_pct,
                    overlays_html=overlays_html,
                    distance_text=distance_str,
                    speed_kmh=speed_kmh,
                    overview_blend=overview_blend,
                ),
                overlay_widgets.build_speed_widget(layout.gauge, speed_kmh, width, height),
                overlay_widgets.build_info_bar_widget(layout.info_bar, display_values, speed_kmh),
                overlay_widgets.build_field_widgets(layout.fields, display_values, speed_kmh, info_bar=layout.info_bar),
            ]
        )
    if not widgets.strip():
        return None
    return overlay_render.build_overlay_html(
        canvas_width=width,
        canvas_height=height,
        accent_color=layout.gauge.arc_color or "#ff5e00",
        widgets_html=widgets,
        values=display_values,
        font_base=font_base,
    )


def render_overlay_with_browser(
    layout: TripLayout,
    values: dict[str, str],
    speed_kmh: float,
    width: int,
    height: int,
    elapsed_ms: int,
    track_points: list["TrackPoint"],
) -> Any | None:
    """用 headless Chrome 渲染覆盖层；没有浏览器时返回 None。"""
    if not BROWSER_OVERLAY_ENABLED:
        return None
    try:
        import overlay_render
    except Exception:  # noqa: BLE001 - 模块缺失就退回 PIL
        return None
    html_text = overlay_html_for(layout, values, speed_kmh, width, height, elapsed_ms, track_points)
    if not html_text:
        return Image.new("RGBA", (width, height), (0, 0, 0, 0))
    return overlay_render.render_overlay_png(html_text, width, height)


# 浏览器渲染是主路径；改 False 可强制回到 PIL 绘制做对比。
BROWSER_OVERLAY_ENABLED = True


def browser_overlay_active() -> bool:
    """浏览器排版层已经带上地图、计时栏和速度表。这时再叠 PIL 地图会重出一块。"""
    if not BROWSER_OVERLAY_ENABLED:
        return False
    try:
        import overlay_render
    except Exception:  # noqa: BLE001
        return False
    return overlay_render.find_chrome() is not None


def render_overlay_frame(
    layout: TripLayout,
    values: dict[str, str],
    speed_kmh: float,
    width: int,
    height: int,
    elapsed_ms: int = 0,
    track_points: list["TrackPoint"] | None = None,
) -> Any:
    """按排版设置渲染一帧透明叠加层；透明区域会让下层视频透出来。

    优先走浏览器渲染（overlay_render / overlay_widgets）：参考设计稿依赖
    backdrop-filter、clip-path、drop-shadow、渐变文字这些 CSS 特性，
    PIL 一个都复刻不了。只有找不到 Chrome 时才退回下面这套 PIL 绘制。
    """
    assert Image is not None
    assert ImageDraw is not None
    assert ImageFont is not None

    browser_layer = render_overlay_with_browser(
        layout, values, speed_kmh, width, height, elapsed_ms, track_points or []
    )
    if browser_layer is not None:
        return browser_layer

    layer = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    if layout.hud.enabled:
        draw_hud(draw, layout.hud, values, elapsed_ms, track_points or [], width, height)
    gauge = layout.gauge
    if gauge.enabled:
        draw_gauge(draw, gauge, speed_kmh, width, height, layer)
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
    with MEM_CACHE_LOCK:
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
        with MEM_CACHE_LOCK:
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


def compute_privacy_trim_bounds(
    points: list[TrackPoint],
    total_duration_ms: int,
    *,
    enabled: bool = True,
    trim_meters: float = 1000.0,
) -> tuple[int, int, list[TrackPoint]]:
    """根据家庭隐私模式参数，从有有效 GPS 信号的点起算，截断开头与结尾各 `trim_meters`（默认 1000m = 1km）的视频时间窗与轨迹点。

    返回 `(privacy_start_ms, privacy_end_ms, trimmed_track_points)`。
    这样无论是导出视频的正片画面、底部/悬浮地图的路线起止点，还是片头 6s 全屏轨迹总览图，
    都不会出现首尾 1km 的家庭住址或目的地位置。
    """
    total_ms = max(1, int(total_duration_ms))
    valid_pts = [
        pt
        for pt in points
        if isinstance(pt.latitude, (int, float))
        and isinstance(pt.longitude, (int, float))
        and abs(pt.latitude) > 0.01
        and abs(pt.longitude) > 0.01
    ]
    if not enabled or float(trim_meters or 0) <= 0 or len(valid_pts) < 2:
        return (0, total_ms, list(points))
    trim_m = float(trim_meters)
    cumulative = [0.0]
    for idx in range(1, len(valid_pts)):
        d = haversine_meters(
            valid_pts[idx - 1].latitude,
            valid_pts[idx - 1].longitude,
            valid_pts[idx].latitude,
            valid_pts[idx].longitude,
        )
        cumulative.append(cumulative[-1] + d)
    total_dist = cumulative[-1]
    # 若整趟行程不足 2 * trim_m + 300m，按比例缩减首尾截断距离，保留中间至少 25% 行程，避免整段被截空
    effective_trim = trim_m
    if total_dist <= trim_m * 2.0 + 300.0:
        effective_trim = max(0.0, total_dist * 0.35)
    if effective_trim <= 1.0 or total_dist <= 10.0:
        return (0, total_ms, list(points))

    start_target_m = effective_trim
    end_target_m = max(start_target_m + 10.0, total_dist - effective_trim)

    def _interp_point_at_distance(target_m: float) -> TrackPoint:
        if target_m <= 0.0:
            return valid_pts[0]
        if target_m >= total_dist:
            return valid_pts[-1]
        hi = bisect.bisect_left(cumulative, target_m)
        hi = max(1, min(len(valid_pts) - 1, hi))
        lo = hi - 1
        seg_m = max(1e-6, cumulative[hi] - cumulative[lo])
        ratio = max(0.0, min(1.0, (target_m - cumulative[lo]) / seg_m))
        p0, p1 = valid_pts[lo], valid_pts[hi]
        return TrackPoint(
            elapsed_ms=int(round(p0.elapsed_ms + (p1.elapsed_ms - p0.elapsed_ms) * ratio)),
            time_ms=int(round(p0.time_ms + (p1.time_ms - p0.time_ms) * ratio)),
            latitude=p0.latitude + (p1.latitude - p0.latitude) * ratio,
            longitude=p0.longitude + (p1.longitude - p0.longitude) * ratio,
            speed_kmh=p0.speed_kmh + (p1.speed_kmh - p0.speed_kmh) * ratio,
        )

    p_start = _interp_point_at_distance(start_target_m)
    p_end = _interp_point_at_distance(end_target_m)
    priv_start_ms = max(0, min(total_ms - 1000, int(p_start.elapsed_ms)))
    priv_end_ms = max(priv_start_ms + 1000, min(total_ms, int(p_end.elapsed_ms)))

    middle_pts = [
        pt for pt in valid_pts if priv_start_ms < pt.elapsed_ms < priv_end_ms
    ]
    trimmed_pts = [p_start] + middle_pts + [p_end]
    return (priv_start_ms, priv_end_ms, trimmed_pts)


def resolve_export_range_and_track(
    total_ms: int,
    options: ExportOptions,
    raw_track_points: list[TrackPoint],
    layout: TripLayout | None = None,
) -> tuple[int, int, list[TrackPoint]]:
    """统一计算导出视频的实际起止毫秒 `(start_ms, end_ms)` 与脱敏后的轨迹点列表。

    当开启隐私模式（默认开启，截断首尾各 2km）时：
    1. 自动将有效范围收缩在 `[privacy_start_ms, privacy_end_ms]` 内（从有 GPS 信号起算跳过前 2km，并在结尾前 2km 处截止）；
    2. 若用户额外传了相对时长窗口（如 `start_ms=0, end_ms=120000` 请求从有效起点开始导出 30s×4x），
       自动以 `privacy_start_ms` 为基准向后截取并钳位在 `privacy_end_ms` 以内；
    3. 轨迹点同步裁剪为 `[privacy_start_ms, privacy_end_ms]` 区间，防止地图路线泄漏家庭起点与终点。
    """
    priv_cfg = getattr(layout, "privacy", None) if layout is not None else None
    priv_enabled = (
        bool(options.privacy_mode)
        if options.privacy_mode is not None
        else (bool(getattr(priv_cfg, "enabled", True)) if priv_cfg is not None else True)
    )
    priv_meters = (
        max(0, min(10000, int(options.privacy_trim_meters)))
        if options.privacy_trim_meters is not None
        else (max(0, min(10000, int(getattr(priv_cfg, "trim_meters", 1000)))) if priv_cfg is not None else 1000)
    )
    if not priv_enabled or priv_meters <= 0 or len(raw_track_points) < 2:
        s_ms, e_ms = normalized_range(total_ms, options)
        return s_ms, e_ms, raw_track_points

    p_start_ms, p_end_ms, trimmed_pts = compute_privacy_trim_bounds(
        raw_track_points,
        total_ms,
        enabled=True,
        trim_meters=float(priv_meters),
    )
    req_start = int(options.start_ms or 0)
    req_end = options.end_ms
    if req_start < p_start_ms:
        # 当请求从 0（或早于隐私起点）开始且指定了截取窗口长度时，顺延到隐私安全起点之后截取相同时长
        if req_end is not None and req_end < total_ms:
            requested_span = max(1000, int(req_end) - req_start)
            start_ms = p_start_ms
            end_ms = min(p_end_ms, start_ms + requested_span)
        else:
            start_ms = p_start_ms
            end_ms = p_end_ms
    else:
        start_ms = max(p_start_ms, min(req_start, max(p_start_ms, p_end_ms - 1000)))
        end_ms = min(p_end_ms, int(req_end)) if req_end is not None else p_end_ms
        end_ms = max(start_ms + 1000, end_ms)
    return start_ms, end_ms, trimmed_pts
def draw_hud(
    draw: Any,
    hud: HudLayout,
    values: dict[str, str],
    elapsed_ms: int,
    track_points: list[TrackPoint],
    width: int,
    height: int,
) -> None:
    """左上角 HUD：地平线风格的赛事计时板。

    结构参考赛道 overlay：左边一条高亮色带，顶行是「REC + 时间戳」，
    中间一条里程进度条，底部三个斜体遥测数字（耗时 / 行程 / 进度）。
    """
    time_text = hud_time_text(values, elapsed_ms, hud.time_format_24h, hud.show_date)
    duration_text = hud_duration_text(elapsed_ms)
    travelled, total, ratio = track_progress(track_points, elapsed_ms)
    if hud.show_distance and total > 0:
        distance_text = f"{format_distance_m(travelled)} / {format_distance_m(total)}"
    else:
        distance_text = "—"
    progress_text = f"{ratio * 100:.1f}%"

    accent_rgb = hex_to_rgb(hud.progress_color, (255, 94, 0))
    text_rgb = hex_to_rgb(hud.text_color, (255, 255, 255))
    panel_rgb = hex_to_rgb(hud.background_color, (13, 17, 23))

    time_font = load_font(max(10, hud.time_size))
    stamp_font = load_font(max(11, int(hud.time_size * 0.40)))
    # 右上角时间戳用稍大一号的字，和 REC 同一行。
    stamp_font_big_pre = load_font(max(12, int(hud.time_size * 0.47)))
    value_font = load_font(max(10, int(hud.duration_size * 0.84)))
    label_font = load_font(max(9, int(hud.duration_size * 0.84)))

    pad_x = max(14, int(hud.time_size * 0.66))
    pad_y = max(12, int(hud.time_size * 0.42))
    gap = max(5, hud.progress_size // 4)
    measure = ImageDraw.Draw(Image.new("RGBA", (1, 1)))
    bar_height = max(3, hud.progress_bar_height)
    left = int(hud.x * width)
    top = int(hud.y * height)
    # 面板宽度必须能装下「耗时 00:42:18  行程 509 m / 2.4 km  进度 20.9%」整行，
    # 否则三组遥测会溢出面板边框。按实际文字宽度算，再留出内边距。
    stamp_font_w = measure.textbbox((0, 0), time_text, font=stamp_font_big_pre)[2]
    telemetry_w = 0
    for label, value in (
        ("耗时", duration_text),
        ("行程", distance_text),
        ("进度", progress_text),
    ):
        telemetry_w += measure.textbbox((0, 0), label, font=label_font)[2]
        telemetry_w += measure.textbbox((0, 0), value, font=value_font)[2] + gap * 2
    head_w = pad_x * 2 + max(stamp_font_w, int(hud.time_size * 7.2))
    panel_w = max(
        hud.progress_bar_width if hud.progress_bar else 0,
        int(hud.time_size * 9.4),
        452,
        int(telemetry_w * 1.06),
        int(head_w),
    )
    row_h = max(time_font.size, stamp_font_big_pre.size)
    content_h = row_h + gap * 2.1 + bar_height + gap * 1.7 + value_font.size

    # 面板按内容撑开，保证投影和玻璃底能包住所有文字。
    scale = min(1.0, max(0.0, hud.opacity / 100.0))
    canvas = _hud_canvas(draw)
    if hud.background:
        glass_panel(
            canvas,
            (left - pad_x, top - pad_y, left + panel_w + pad_x, top + content_h + pad_y),
            fill=(panel_rgb[0], panel_rgb[1], panel_rgb[2], int(232 * scale)),
            radius=max(6, hud.time_size // 3),
            border=(255, 255, 255, int(38 * scale)),
            accent=hex_with_alpha(hud.progress_color, (255, 94, 0), int(235 * scale)),
            accent_width=max(3, hud.time_size // 9),
            corners=(True, True, True, True),
        )
    painter = ImageDraw.Draw(canvas)

    # REC 圆点 + 时间戳（参考稿的顶行）。
    dot_r = max(3, hud.time_size // 12)
    dot_cy = int(top + dot_r + 2)
    painter.ellipse(
        (left, dot_cy - dot_r, left + dot_r * 2, dot_cy + dot_r),
        fill=(255, 51, 75, int(255 * scale)),
    )
    skewed_text(
        canvas,
        (left + dot_r * 2 + gap * 1.6, top),
        "REC",
        stamp_font,
        (203, 213, 225, int(255 * scale)),
        skew=-0.12,
    )
    # 时间戳靠右对齐，和 REC 同一行（参考稿的 timing-header 是 space-between）。
    stamp_font_big = stamp_font_big_pre
    stamp_w = painter.textbbox((0, 0), time_text, font=stamp_font_big)[2]
    skewed_text(
        canvas,
        (left + panel_w - stamp_w * 1.08, top),
        time_text,
        stamp_font_big,
        (215, 222, 232, int(255 * scale)),
        skew=-0.10,
    )
    cursor_y = top + stamp_font_big.size + gap * 1.4

    # 参考稿的时间戳就在 REC 同一行右侧，不再单独放一行大字。
    cursor_y = max(cursor_y, top + time_font.size * 0.55)

    # 里程进度条：底槽 + 渐亮的前景 + 外发光。
    if hud.progress_bar:
        bar_left = left
        bar_right = left + panel_w
        track = with_alpha(hex_to_rgb(hud.progress_bar_track_color, (255, 255, 255)), 30)
        painter.rounded_rectangle(
            (bar_left, cursor_y, bar_right, cursor_y + bar_height),
            radius=max(1, bar_height // 2),
            fill=(track[0], track[1], track[2], int(track[3] * scale)),
        )
        filled = max(0, int((bar_right - bar_left) * max(0.0, min(1.0, ratio))))
        if filled > 0:
            bar_layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
            bar_draw = ImageDraw.Draw(bar_layer)
            # 参考稿是 #ffaa00 → 主题色的横向渐变，逐列插值比纯色更有速度感。
            warm = (255, 170, 0)
            for column in range(filled):
                blend = column / max(1, filled - 1)
                rgb = (
                    int(warm[0] + (accent_rgb[0] - warm[0]) * blend),
                    int(warm[1] + (accent_rgb[1] - warm[1]) * blend),
                    int(warm[2] + (accent_rgb[2] - warm[2]) * blend),
                )
                bar_draw.line(
                    (bar_left + column, cursor_y, bar_left + column, cursor_y + bar_height),
                    fill=(rgb[0], rgb[1], rgb[2], int(255 * scale)),
                )
            # 两端补圆角，别让渐变条变成方头。
            radius_px = max(1, bar_height // 2)
            for cx, fill_x in (
                (bar_left + radius_px, (0, 0, radius_px * 2, bar_height)),
            ):
                mask = Image.new("L", (radius_px * 2, bar_height), 0)
                ImageDraw.Draw(mask).ellipse((0, 0, radius_px * 2 - 1, bar_height - 1), fill=255)
                bar_layer.paste(bar_layer.crop(fill_x), (int(cx - radius_px), int(cursor_y)), mask)
            composite_glow(canvas, bar_layer, accent_rgb, blur=max(2, bar_height), strength=0.55 * scale)
            canvas.alpha_composite(bar_layer)
        cursor_y += bar_height + gap

    # 底部三个遥测数字：标签暗、数值亮，进度用高亮色收尾。
    # 参考稿是「标签 数值」同一行，标签灰、数值亮，三组横向排开。
    # 之前上下堆叠又按等分列宽排，长数值会跨过列边界压到一起。
    columns: list[tuple[str, str, tuple[int, int, int]]] = [
        ("耗时", duration_text, text_rgb),
    ]
    if hud.show_distance:
        columns.append(("行程", distance_text, text_rgb))
    columns.append(("进度", progress_text, accent_rgb))

    measure = ImageDraw.Draw(Image.new("RGBA", (1, 1)))
    # 先量每组「标签 + 空格 + 数值」的总宽，再按剩余空间均分间隔，
    # 这样无论数值多长都不会互相压到。
    groups: list[tuple[str, str, int, int, tuple[int, int, int]]] = []
    for label, value, color in columns:
        label_w = measure.textbbox((0, 0), label, font=label_font)[2]
        value_w = measure.textbbox((0, 0), value, font=value_font)[2]
        inline_gap = max(3, gap // 2)
        groups.append((label, value, label_w, value_w, color))
    total_w = sum(label_w + inline_gap + value_w for _, _, label_w, value_w, _ in groups)
    free = max(0, panel_w - total_w)
    separator = free / max(1, len(groups)) if len(groups) > 1 else 0
    inline_gap = max(3, gap // 2)
    column_x = float(left)
    for label, value, label_w, value_w, color in groups:
        baseline = cursor_y + (value_font.size - label_font.size) * 0.35
        skewed_text(
            canvas,
            (column_x, baseline),
            label,
            label_font,
            (148, 163, 184, int(255 * scale)),
            skew=-0.12,
        )
        # 参考稿的数值是粗体；PIL 没有 weight 参数，用同色描边加粗笔画。
        value_layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
        value_draw = ImageDraw.Draw(value_layer)
        weight = max(1, int(value_font.size * 0.055))
        value_draw.text(
            (column_x + label_w + inline_gap * 1.4, cursor_y),
            value,
            font=value_font,
            fill=(color[0], color[1], color[2], int(255 * scale)),
            stroke_width=weight,
            stroke_fill=(color[0], color[1], color[2], int(255 * scale)),
        )
        canvas.alpha_composite(value_layer)
        column_x += label_w + inline_gap * 1.4 + value_w + separator


def hud_time_text(values: dict[str, str], elapsed_ms: int, use_24h: bool, show_date: bool = False) -> str:
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
    canvas = _layer_of(draw, None)
    if item.background and canvas is not None:
        # 和 HUD 同一套玻璃底：深色半透明、圆角、细白边、左侧色带。
        # 参考稿里所有浮在画面上的块都用这个语言，字段不该是例外。
        accent_rgb = hex_to_rgb(item.color, (255, 94, 0))
        glass_panel(
            canvas,
            (box_left, box_top, box_left + box_width, box_top + box_height),
            fill=with_alpha(hex_to_rgb(item.background_color, (13, 17, 23)), item.background_opacity),
            radius=min(item.radius, box_height / 2),
            border=(255, 255, 255, int(30 * item.opacity / 100)),
            accent=with_alpha(accent_rgb, int(200 * item.opacity / 100)),
            accent_width=max(2, int(box_height * 0.09)),
            corners=(False, True, False, True),
        )
    elif item.background:
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
    layer: Any = None,
) -> None:
    """按配置选择速度表的三种样式。

    对应参考稿的三套：
      bar      → Forza Apex，外圈弧线 + 巨型数字 + 挡位胶囊
      circular → Aero HUD，左侧竖排刻度 + 无底盘巨字
      gt       → GT Telemetry，玻璃面板 + 点阵条 + 遥测表头
    """
    if gauge.style == "bar":
        draw_gauge_bar(draw, gauge, speed_kmh, width, height, layer)
        return
    if gauge.style == "aero":
        draw_gauge_aero(draw, gauge, speed_kmh, width, height, layer)
        return
    if gauge.style == "gt":
        draw_gauge_gt(draw, gauge, speed_kmh, width, height, layer)
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


def draw_gauge_aero(
    draw: Any,
    gauge: GaugeLayout,
    speed_kmh: float,
    width: int,
    height: int,
    layer: Any = None,
) -> None:
    """样式二：战斗机全息 HUD（参考稿 style-aero）。

    没有任何底盘，左侧一列会随速度点亮的横条，右边是巨大的白色速度数字，
    单位用高亮色写在下面。极端克制，靠字重和发光撑气场。
    """
    assert Image is not None
    canvas = _layer_of(draw, layer)
    if canvas is None:
        draw_gauge_bar(draw, gauge, speed_kmh, width, height, layer)
        return
    radius = max(56.0, gauge.radius_percent * min(width, height) * 1.15)
    center_x = gauge.x * width
    center_y = gauge.y * height
    opacity = max(0, min(100, gauge.opacity)) / 100.0
    accent = hex_to_rgb(gauge.arc_color, (255, 94, 0))
    ratio = max(0.0, min(1.0, speed_kmh / max(1.0, float(gauge.max_speed))))

    # 左侧刻度梯：7 段，按比例点亮；点亮的长条更宽、带发光。
    ticks = 7
    lit = int(round(ratio * ticks))
    tick_h = max(3, int(radius * 0.045))
    tick_gap = max(4, int(radius * 0.058))
    ladder_x = center_x - radius * 0.95
    ladder_bottom = center_y + (ticks * (tick_h + tick_gap)) / 2
    for index in range(ticks):
        y = ladder_bottom - (index + 1) * (tick_h + tick_gap)
        active = index < lit
        tick_w = radius * (0.34 if active else 0.17)
        bar = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
        ImageDraw.Draw(bar).rectangle(
            (ladder_x, y, ladder_x + tick_w, y + tick_h),
            fill=with_alpha(accent if active else (255, 255, 255), int((255 if active else 55) * opacity)),
        )
        if active:
            composite_glow(canvas, bar, accent, blur=max(2, tick_h), strength=0.8 * opacity)
        canvas.alpha_composite(bar)

    # 巨型数字 + 单位：整块带斜切和辉光。
    value_font = load_font(max(28, int(radius * 0.78)))
    unit_font = load_font(max(10, int(radius * 0.16)))
    number_layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    probe = ImageDraw.Draw(Image.new("RGBA", (1, 1)))
    text = f"{speed_kmh:.0f}"
    box = probe.textbbox((0, 0), text, font=value_font)
    text_w, text_h = box[2] - box[0], box[3] - box[1]
    skewed_text(
        number_layer,
        (center_x - text_w * 0.32, center_y - text_h / 2),
        text,
        value_font,
        with_alpha(hex_to_rgb(gauge.value_color, (255, 255, 255)), int(255 * opacity)),
        skew=-0.14,
    )
    composite_glow(number_layer, number_layer, accent, blur=max(4, int(radius * 0.10)), strength=0.55 * opacity)
    canvas.alpha_composite(number_layer)

    unit_text = "KM/H  HUD"
    u_box = probe.textbbox((0, 0), unit_text, font=unit_font)
    skewed_text(
        canvas,
        (center_x - text_w * 0.32, center_y + text_h * 0.55),
        unit_text,
        unit_font,
        with_alpha(accent, int(255 * opacity)),
        skew=-0.14,
    )


def draw_gauge_gt(
    draw: Any,
    gauge: GaugeLayout,
    speed_kmh: float,
    width: int,
    height: int,
    layer: Any = None,
) -> None:
    """样式三：GT 赛道遥测屏（参考稿 style-gt）。

    一块斜切的玻璃面板：顶行遥测表头、左侧大号速度数字、底部点阵速度条。
    """
    assert Image is not None
    canvas = _layer_of(draw, layer)
    if canvas is None:
        draw_gauge_bar(draw, gauge, speed_kmh, width, height, layer)
        return
    radius = max(56.0, gauge.radius_percent * min(width, height) * 1.15)
    center_x = gauge.x * width
    center_y = gauge.y * height
    opacity = max(0, min(100, gauge.opacity)) / 100.0
    accent = hex_to_rgb(gauge.arc_color, (255, 94, 0))
    ratio = max(0.0, min(1.0, speed_kmh / max(1.0, float(gauge.max_speed))))

    panel_w = radius * 1.9
    panel_h = radius * 1.15
    left = center_x - panel_w / 2
    top = center_y - panel_h / 2
    glass_panel(
        canvas,
        (left, top, left + panel_w, top + panel_h),
        fill=with_alpha(hex_to_rgb(gauge.background_color, (13, 17, 23)), int(220 * opacity)),
        radius=max(6, radius * 0.10),
        border=(255, 255, 255, int(40 * opacity)),
        corners=(False, True, False, True),
    )
    painter = ImageDraw.Draw(canvas)

    # 顶行：TELEMETRY + 实时百分比
    header_font = load_font(max(9, int(radius * 0.11)))
    painter.text(
        (left + radius * 0.14, top + radius * 0.10),
        "TELEMETRY",
        font=header_font,
        fill=with_alpha((100, 116, 139), int(255 * opacity)),
    )
    pct_text = f"{ratio * 100:3.0f}%"
    p_box = painter.textbbox((0, 0), pct_text, font=header_font)
    painter.text(
        (left + panel_w - radius * 0.14 - (p_box[2] - p_box[0]), top + radius * 0.10),
        pct_text,
        font=header_font,
        fill=with_alpha(accent, int(255 * opacity)),
    )

    # 大号速度数字
    value_font = load_font(max(24, int(radius * 0.52)))
    skewed_text(
        canvas,
        (left + radius * 0.13, top + radius * 0.22),
        f"{speed_kmh:.0f}",
        value_font,
        with_alpha(hex_to_rgb(gauge.value_color, (255, 255, 255)), int(255 * opacity)),
        skew=-0.13,
    )

    # 底部点阵：参考稿是 9 格，按比例填橙色块。
    blocks = 9
    filled = int(round(ratio * blocks))
    block_gap = max(2, int(radius * 0.022))
    block_h = max(6, int(radius * 0.075))
    block_w = max(3, int(radius * 0.042))
    bars_y = top + panel_h - radius * 0.16 - block_h
    bars_x = left + radius * 0.14
    blocks_layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    blocks_draw = ImageDraw.Draw(blocks_layer)
    for index in range(blocks):
        x = bars_x + index * (block_w + block_gap)
        active = index < filled
        blocks_draw.rectangle(
            (x, bars_y, x + block_w, bars_y + block_h),
            fill=with_alpha(accent if active else (255, 255, 255), int((255 if active else 28) * opacity)),
        )
    composite_glow(canvas, blocks_layer, accent, blur=max(2, block_h // 2), strength=0.6 * opacity)
    canvas.alpha_composite(blocks_layer)

    # 右侧单位
    unit_font = load_font(max(9, int(radius * 0.115)))
    painter.text(
        (left + panel_w - radius * 0.14 - block_w * 4, bars_y - radius * 0.15),
        "KM/H",
        font=unit_font,
        fill=with_alpha(hex_to_rgb(gauge.unit_color, (148, 163, 184)), int(200 * opacity)),
    )


def draw_gauge_bar(
    draw: Any,
    gauge: GaugeLayout,
    speed_kmh: float,
    width: int,
    height: int,
    layer: Any = None,
) -> None:
    """速度表盘：参考《极限竞速：地平线》的 HUD 语言。

    地平线的速度表不是进度条，而是一圈带渐变的弧：左侧起弧、随速度点亮，
    弧尾有一小段高亮描边，中间是极粗的无衬线数字，单位压在数字下沿。
    这里按同样的层次来画：半透明底盘 → 底弧 → 渐变主弧 → 尾端高光 →
    外沿刻度 → 大号数字 → 单位。
    """
    assert Image is not None
    radius = max(56.0, gauge.radius_percent * min(width, height) * 1.15)
    center_x = gauge.x * width
    center_y = gauge.y * height
    opacity = max(0, min(100, gauge.opacity))
    accent = hex_to_rgb(gauge.arc_color, (228, 107, 46))
    track = hex_to_rgb(gauge.unit_color, (150, 168, 160))

    # 起止角：地平线的表盘从左下扫到右下，留出正下方给单位。
    start_angle, sweep = 145.0, 250.0
    ratio = max(0.0, min(1.0, speed_kmh / max(1.0, float(gauge.max_speed))))

    canvas_for_caps = _layer_of(draw, layer)
    # 底盘：径向渐变不好用 PIL 画，用三层同心圆近似出「玻璃底」的深度。
    dish = Image.new("RGBA", (int(radius * 2.4), int(radius * 2.4)), (0, 0, 0, 0))
    dish_draw = ImageDraw.Draw(dish)
    dc = dish.width / 2
    for step in range(14, 0, -1):
        rr = radius * (0.78 + step * 0.030)
        alpha = int(opacity * 0.14 * (15 - step) / 14 * 3)
        dish_draw.ellipse((dc - rr, dc - rr, dc + rr, dc + rr), fill=(4, 8, 14, min(210, alpha)))
    if layer is not None:
        layer.alpha_composite(dish, (int(center_x - dc), int(center_y - dc)))

    arc_width = max(8, int(radius * 0.167))
    box = (center_x - radius, center_y - radius, center_x + radius, center_y + radius)
    # 底弧
    draw.arc(box, start=start_angle, end=start_angle + sweep,
             fill=(255, 255, 255, int(20 * opacity / 100)), width=arc_width)
    # 主弧：参考稿是纯色亮橙，不分段渐变；用圆头端点收尾。
    steps = 120
    lit = int(steps * ratio)
    if lit > 0:
        arc_end = start_angle + sweep * max(0.0, min(1.0, ratio))
        draw.arc(box, start=start_angle, end=arc_end,
                 fill=with_alpha(accent, opacity), width=arc_width)
        # 圆头：在起止两端各补一个圆，模拟 stroke-linecap: round。
        if canvas_for_caps is not None:
            cap_r = arc_width / 2
            for angle_deg in (start_angle, arc_end):
                rad = math.radians(angle_deg)
                cx = center_x + radius * math.cos(rad)
                cy = center_y + radius * math.sin(rad)
                ImageDraw.Draw(canvas_for_caps).ellipse(
                    (cx - cap_r, cy - cap_r, cx + cap_r, cy + cap_r),
                    fill=with_alpha(accent, opacity),
                )

    # 整圈弧的柔和外发光，对应参考稿的 drop-shadow(0 0 10px var(--primary-color))。
    if lit > 0 and canvas_for_caps is not None:
        glow_arc = Image.new("RGBA", canvas_for_caps.size, (0, 0, 0, 0))
        ImageDraw.Draw(glow_arc).arc(
            box, start=start_angle, end=arc_end,
            fill=with_alpha(accent, opacity), width=arc_width,
        )
        blurred = glow_arc.filter(ImageFilter.GaussianBlur(max(4, arc_width // 2)))
        canvas_for_caps.alpha_composite(blurred)
        canvas_for_caps.alpha_composite(glow_arc)

    # 大号速度数字：倾斜体感靠更粗的字重和更紧的字距，PIL 没有字距参数，
    # 用足够大的字号撑满表盘中心即可。
    canvas = _layer_of(draw, layer)
    value_font = load_font(max(20, int(radius * 0.62)))
    unit_font = load_font(max(9, int(radius * 0.15)))
    value_text = f"{speed_kmh:.0f}"
    value_rgb = hex_to_rgb(gauge.value_color, (255, 255, 255))
    if canvas is not None:
        # 数字用斜切 + 发光，和参考稿的轨道字体一致。
        probe = ImageDraw.Draw(Image.new("RGBA", (1, 1)))
        v_box = probe.textbbox((0, 0), value_text, font=value_font)
        v_w = v_box[2] - v_box[0]
        v_h = v_box[3] - v_box[1]
        # 参考稿：数字是上白下灰的竖向渐变 + 投影，不是纯白。
        mask = Image.new("L", canvas.size, 0)
        skewed_text(
            mask,
            (center_x - v_w / 2 - abs(-0.14) * v_h, center_y - v_h / 2 - radius * 0.10),
            value_text,
            value_font,
            255,
            skew=-0.14,
        )
        glyph_box = mask.getbbox()
        grad = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
        if glyph_box:
            gx0, gy0, gx1, gy1 = glyph_box
            gd = ImageDraw.Draw(grad)
            span = max(1, gy1 - gy0)
            for row in range(gy0, gy1):
                ratio_row = (row - gy0) / span
                shade = int(255 - 95 * max(0.0, (ratio_row - 0.35) / 0.65))
                gd.line(
                    (gx0, row, gx1, row),
                    fill=(shade, shade, int(shade * 0.99), int(255 * opacity / 100)),
                )
            grad.putalpha(Image.composite(grad.getchannel("A"), Image.new("L", canvas.size, 0), mask))
        # 投影
        shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
        shadow.putalpha(mask.point(lambda value: int(value * 0.55)))
        canvas.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(max(4, int(radius * 0.05)))), (0, int(radius * 0.02)))
        canvas.alpha_composite(grad)
    else:
        fallback_box = value_font.getbbox(value_text)
        draw.text(
            (center_x - (fallback_box[2] - fallback_box[0]) / 2, center_y),
            value_text,
            font=value_font,
            fill=with_alpha(value_rgb, opacity),
        )
    unit_text = "KM/H"
    u_left, u_top, u_right, u_bottom = draw.textbbox((0, 0), unit_text, font=unit_font)
    draw.text(
        (center_x - (u_right - u_left) / 2 - u_left, center_y + radius * 0.34 - u_top),
        unit_text,
        font=unit_font,
        fill=with_alpha(track, int(opacity * 0.85)),
    )
    # 参考稿底部的圆形胶囊（挡位/Min/Max），这里放上速度上限做收尾。
    pill_text = f"MAX {gauge.max_speed}"
    pill_font = load_font(max(9, int(radius * 0.13)))
    p_box = draw.textbbox((0, 0), pill_text, font=pill_font)
    p_w, p_h = p_box[2] - p_box[0], p_box[3] - p_box[1]
    pill_x = center_x
    pill_y = center_y + radius * 0.52
    pill_pad_x, pill_pad_y = p_h * 0.55, p_h * 0.22
    draw.rounded_rectangle(
        (pill_x - p_w / 2 - pill_pad_x, pill_y - pill_pad_y,
         pill_x + p_w / 2 + pill_pad_x, pill_y + p_h + pill_pad_y),
        radius=(p_h + pill_pad_y * 2) / 2,
        fill=(255, 255, 255, int(30 * opacity / 100)),
    )
    draw.text(
        (pill_x - p_w / 2 - p_box[0], pill_y - p_box[1]),
        pill_text,
        font=pill_font,
        fill=with_alpha(accent, int(opacity * 0.95)),
    )


def _layer_of(draw: Any, layer: Any) -> Any:
    """尽量拿到可 alpha 合成的图层；拿不到就返回 None，调用方退回普通绘制。"""
    if layer is not None:
        return layer
    return getattr(draw, "_image", None)


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
    err_chunks, err_thread = _start_stderr_drain(process)
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
        code = process.wait(timeout=1800)
        stderr = _finish_stderr_drain(err_chunks, err_thread)
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
    """地图浮窗：参考稿的暗色矩形卡片。

    参考稿是 220x140 的圆角矩形卡片（不是圆形），左上角顶着一个斜切的
    地址徽章，卡片里是暗色矢量地图 + 橙色发光轨迹 + 白色车标。
    """
    assert Image is not None
    assert ImageDraw is not None
    layer = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    config = layout.map_layer
    if len(points) < 2:
        return layer

    # 卡片尺寸：短边百分比是「高度」的口径，宽度按参考稿 220:140 的比例。
    card_h = max(80, int(min(width, height) * config.size_percent))
    card_w = int(card_h * 1.571)
    center_x = int(config.x * width)
    center_y = int(config.y * height)
    left = center_x - card_w // 2
    top = center_y - card_h // 2
    radius = max(6, card_h // 12)
    # 参考稿的轨迹和徽章指示条都是标准赛道橙 #ff5e00。
    # markerColor 默认是琥珀黄 #ffb020，跟着它会把整条线拖成土黄。
    accent = (255, 94, 0)
    # 参考稿的卡片是近乎纯黑，不跟 background_color 的深绿走，否则和橙线对比不足。
    dark = (10, 14, 20)

    # 卡片本体：深色玻璃 + 极细白边 + 投影。
    card = Image.new("RGBA", (card_w, card_h), (0, 0, 0, 0))
    card_draw = ImageDraw.Draw(card)
    card_draw.rounded_rectangle(
        (0, 0, card_w - 1, card_h - 1),
        radius=radius,
        fill=(dark[0], dark[1], dark[2], 218),
        corners=(True, True, True, False),
    )

    # 高德底图铺满整张卡片；拉不到瓦片时退回点阵。
    map_center = point_at(points, elapsed_ms)
    amap = amap_card_base_map(
        points,
        card_w,
        card_h,
        config.map_style or "satellite",
        scale_meters=config.scale_meters,
        center=map_center,
    )
    if amap is not None:
        card.alpha_composite(amap, (0, 0))
        card.alpha_composite(Image.new("RGBA", (card_w, card_h), (8, 12, 18, 70)), (0, 0))
    else:
        dots_step = max(10, card_h // 14)
        for gx in range(dots_step, card_w, dots_step):
            for gy in range(dots_step, card_h, dots_step):
                card_draw.point((gx, gy), fill=(255, 255, 255, 16))

    # 轨迹：和浏览器覆盖层同一套、跟卡片同比例的墨卡托窗口。
    try:
        import overlay_widgets
        frame = overlay_widgets.route_mercator_frame(
            points, scale_meters=config.scale_meters, center=point_at(points, elapsed_ms)
        )
    except Exception:  # noqa: BLE001
        frame = None
    if frame is None:
        return layer
    _, origin_x, origin_y, span_x, span_y = frame

    def project(point: TrackPoint) -> tuple[float, float]:
        import overlay_widgets as ow
        mx, my = ow.amap_mercator(point.latitude, point.longitude)
        return (
            (mx - origin_x) / span_x * card_w,
            (my - origin_y) / span_y * card_h,
        )

    route_xy = [project(point) for point in points]
    # 参考稿里整条轨迹都是亮的橙色光带，只靠线宽而不是亮度区分已行驶段。
    # 线宽跟覆盖层一样按卡片高度走：1.75px * (card_h/140)。再加一圈很轻的描边，避免糊成带子。
    line_w = max(2, int(round(card_h * 1.75 / 140)))
    card_draw.line(route_xy, fill=(accent[0], accent[1], accent[2], 235), width=line_w, joint="curve")
    glow_line = Image.new("RGBA", (card_w, card_h), (0, 0, 0, 0))
    ImageDraw.Draw(glow_line).line(
        route_xy,
        fill=(accent[0], accent[1], accent[2], 180),
        width=line_w,
        joint="curve",
    )
    card.alpha_composite(glow_line.filter(ImageFilter.GaussianBlur(max(1, line_w // 2))))
    card.alpha_composite(glow_line)

    # 车标：参考稿的白色多边形箭头 + 白色辉光。
    marker = project(point_at(points, elapsed_ms))
    # 参考稿的车标是纯白小三角，不是橙色大箭头。
    draw_heading_arrow(
        card,
        marker[0],
        marker[1],
        card_h * 0.55,
        "#ffffff",
        0.0 if config.rotate_with_heading else (route_heading_at(points, elapsed_ms) or 0.0),
    )

    # 卡片裁成圆角矩形
    mask = Image.new("L", (card_w, card_h), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, card_w - 1, card_h - 1), radius=radius, fill=255, corners=(True, True, True, False)
    )
    layer.paste(card, (left, top), mask)

    # 外边框：细白线 + 顶部一段橙色高亮
    frame = Image.new("RGBA", layer.size, (0, 0, 0, 0))
    frame_draw = ImageDraw.Draw(frame)
    frame_draw.rounded_rectangle(
        (left, top, left + card_w, top + card_h),
        radius=radius,
        outline=(255, 255, 255, 38),
        width=1,
        corners=(True, True, True, False),
    )
    layer.alpha_composite(frame)

    # 左上角斜切徽章：地址文字，压在卡片顶边上（参考稿 .map-badge）。
    badge_text = (values or {}).get("address") or ""
    if badge_text:
        badge_font = load_font(max(10, int(card_h * 0.10)))
        probe = ImageDraw.Draw(Image.new("RGBA", (1, 1)))
        text_box = probe.textbbox((0, 0), badge_text, font=badge_font)
        text_w = text_box[2] - text_box[0]
        text_h = text_box[3] - text_box[1]
        badge_h = int(text_h * 1.75)
        badge_w = int(text_w * 1.10 + badge_h * 1.6)
        badge_x = left + int(card_w * 0.03)
        # 参考稿的徽章底边和卡片顶边重叠 1px，视觉上是连体的一块。
        badge_y = top - badge_h + max(1, radius // 2)
        badge = Image.new("RGBA", layer.size, (0, 0, 0, 0))
        badge_draw = ImageDraw.Draw(badge)
        # 斜切：左右两边各向内收，做出参考稿的 skewX(-12deg) 观感。
        skew = int(badge_h * 0.30)
        badge_draw.polygon(
            [
                (badge_x + skew, badge_y),
                (badge_x + badge_w, badge_y),
                (badge_x + badge_w, badge_y + badge_h + radius),
                (badge_x, badge_y + badge_h + radius),
            ],
            fill=(dark[0], dark[1], dark[2], 245),
        )
        # 左侧橙色指示条
        indicator_w = max(3, int(badge_h * 0.14))
        badge_draw.rectangle(
            (badge_x + int(badge_h * 0.34), badge_y + badge_h * 0.30,
             badge_x + int(badge_h * 0.34) + indicator_w, badge_y + badge_h * 0.70),
            fill=(accent[0], accent[1], accent[2], 255),
        )
        skewed_text(
            badge,
            (badge_x + badge_h * 0.62, badge_y + (badge_h - text_h) / 2 - text_box[1]),
            badge_text,
            badge_font,
            (255, 255, 255, 240),
            skew=-0.10,
        )
        layer.alpha_composite(badge)

    ring = ImageDraw.Draw(layer)
    if config.show_scale_bar:
        draw_map_scale_bar_rect(ring, config, left, top, card_w, card_h)
    if config.show_speed_label and False:  # 参考稿地图卡片内不显示速度胶囊
        font = load_font(max(10, int(card_h * 0.10)))
        label = f"{point_at(points, elapsed_ms).speed_kmh:.0f} km/h"
        box = ring.textbbox((0, 0), label, font=font)
        text_width = box[2] - box[0]
        text_height = box[3] - box[1]
        pill_w = text_width + card_h * 0.10
        pill_h = text_height + card_h * 0.055
        pill_x = left + card_w - pill_w - card_h * 0.05
        pill_y = top + card_h - pill_h - card_h * 0.05
        pill = Image.new("RGBA", layer.size, (0, 0, 0, 0))
        ImageDraw.Draw(pill).rounded_rectangle(
            (pill_x, pill_y, pill_x + pill_w, pill_y + pill_h),
            radius=pill_h / 2,
            fill=(13, 17, 23, 215),
        )
        layer.alpha_composite(pill)
        ring.text(
            (pill_x + card_h * 0.05 - box[0], pill_y + (pill_h - text_height) / 2 - box[1]),
            label,
            font=font,
            fill=with_alpha(hex_to_rgb(config.label_color, (255, 255, 255)), 255),
        )
    if config.show_coordinates:
        coordinate_text = (values or {}).get("coordinates")
        if not coordinate_text and points:
            current = point_at(points, elapsed_ms)
            coordinate_text = f"{current.latitude:.5f}°N {current.longitude:.5f}°E"
        if coordinate_text:
            draw_map_coordinates_rect(ring, config, coordinate_text, left, top, card_w, card_h)
    return layer


def draw_map_scale_bar_rect(
    draw: Any, config: MapLayerLayout, left: int, top: int, card_w: int, card_h: int
) -> None:
    """矩形卡片底部的比例尺。"""
    target_meters = config.scale_meters * 0.36
    for nice in (25, 50, 100, 200, 500, 1000, 2000):
        if target_meters <= nice:
            meters = nice
            break
    else:
        meters = 2000
    length_px = (meters / max(1, config.scale_meters)) * (card_w * 0.30)
    x0 = left + card_w * 0.05
    x1 = x0 + length_px
    y = top + card_h * 0.93
    color = with_alpha(hex_to_rgb(config.scale_bar_color, (255, 255, 255)), 200)
    width = max(2, card_h // 70)
    draw.line((x0, y, x1, y), fill=color, width=width)
    tick = max(3, card_h // 45)
    draw.line((x0, y - tick, x0, y + tick), fill=color, width=width)
    draw.line((x1, y - tick, x1, y + tick), fill=color, width=width)


def draw_map_coordinates_rect(
    draw: Any, config: MapLayerLayout, text: str,
    left: int, top: int, card_w: int, card_h: int,
) -> None:
    """经纬度放在卡片正下方。"""
    normalized = text.replace(",", " ").strip()
    match = re.search(
        r"(-?\d+(?:\.\d+)?)\s*°?\s*([NS])?\s+(-?\d+(?:\.\d+)?)\s*°?\s*([EW])?", normalized
    )
    if match:
        lat, lat_hemi, lon, lon_hemi = match.groups()
        normalized = (
            f"{abs(float(lat)):.5f}°{lat_hemi or ('S' if float(lat) < 0 else 'N')}  "
            f"{abs(float(lon)):.5f}°{lon_hemi or ('W' if float(lon) < 0 else 'E')}"
        )
    font = load_font(max(9, config.coordinate_size))
    box = draw.textbbox((0, 0), normalized, font=font)
    text_w = box[2] - box[0]
    text_h = box[3] - box[1]
    center_x = left + card_w / 2
    y = top + card_h + max(4, card_h * 0.04)
    draw.rounded_rectangle(
        (center_x - text_w / 2 - card_h * 0.04, y + box[1] - card_h * 0.02,
         center_x + text_w / 2 + card_h * 0.04, y + box[3] + card_h * 0.02),
        radius=int(card_h * 0.035),
        fill=(13, 17, 23, 205),
    )
    draw.text(
        (center_x - text_w / 2 - box[0], y),
        normalized,
        font=font,
        fill=with_alpha(hex_to_rgb(config.coordinate_color, (255, 255, 255)), 235),
    )


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
    import overlay_widgets

    origin_lat, origin_lon = overlay_widgets.wgs84_to_gcj02(current.latitude, current.longitude)
    meters_per_degree_lat = 111_320.0
    meters_per_degree_lon = max(1.0, 111_320.0 * math.cos(math.radians(origin_lat)))
    radius_px = diameter / 2
    px_per_meter = radius_px / max(1, config.scale_meters)
    rotation = math.radians(heading_deg or 0.0) if config.rotate_with_heading else 0.0
    cos_r, sin_r = math.cos(-rotation), math.sin(-rotation)

    def project(point: TrackPoint) -> tuple[float, float]:
        lat, lon = overlay_widgets.wgs84_to_gcj02(point.latitude, point.longitude)
        east = (lon - origin_lon) * meters_per_degree_lon * px_per_meter
        north = (lat - origin_lat) * meters_per_degree_lat * px_per_meter
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


def amap_tile_xy(latitude: float, longitude: float, zoom: int) -> tuple[float, float]:
    """GPS 经纬度 -> 高德瓦片坐标（可能带小数）。先转 GCJ-02，和路网对齐。"""
    import overlay_widgets

    latitude, longitude = overlay_widgets.wgs84_to_gcj02(latitude, longitude)
    lat_rad = math.radians(latitude)
    n = 2 ** zoom
    x = (longitude + 180.0) / 360.0 * n
    y = (1.0 - math.log(math.tan(lat_rad) + 1.0 / math.cos(lat_rad)) / math.pi) / 2.0 * n
    return x, y


def _amap_tile_disk_path(zoom: int, x: int, y: int, style: str, ext: str = "bin") -> Path:
    """返回高德地图瓦片在本地磁盘上的持久化缓存路径。"""
    safe_style = re.sub(r"[^a-zA-Z0-9_-]", "_", str(style or "road"))
    return AMAP_DISK_TILE_DIR / safe_style / str(int(zoom)) / str(int(x)) / f"{int(y)}.{ext}"


def is_amap_tile_cached_on_disk(zoom: int, x: int, y: int, style: str = "satellite") -> bool:
    """检查指定缩放层级与坐标的高德合成瓦片是否已离线缓存到本机磁盘。"""
    style_key = "road" if str(style) == "road" else "satellite"
    composed_path = _amap_tile_disk_path(zoom, x, y, f"composed_{style_key}", "jpg")
    return composed_path.is_file() and composed_path.stat().st_size > 128


def collect_trip_amap_tiles(
    points: list[TrackPoint],
    *,
    layout: LayoutConfig | None = None,
) -> list[tuple[int, int, int]]:
    """根据行程 GPS 轨迹计算全程总览、周期性对数缩放过渡与局部跟随所需的全部高德瓦片坐标集合 `(zoom, x, y)`。"""
    if len(points) < 2:
        return []
    import overlay_widgets

    tile_set: set[tuple[int, int, int]] = set()
    mid_lat = sum(p.latitude for p in points) / len(points)
    meters_per_unit = 40075016.686 * math.cos(math.radians(mid_lat))
    meters_per_px_full = meters_per_unit / 256.0

    def _add_frame_tiles(frame: tuple[Any, float, float, float, float] | None, ref_width: int) -> None:
        if frame is None:
            return
        _, ox, oy, sx, sy = frame
        target_mpp = (sx * meters_per_unit) / max(1, ref_width)
        z = max(12, min(18, int(round(math.log2(meters_per_px_full / max(1e-12, target_mpp))))))
        n = 2 ** z
        fx, fy = int(math.floor(ox * n)), int(math.floor(oy * n))
        lx, ly = int(math.floor((ox + sx) * n)), int(math.floor((oy + sy) * n))
        if (lx - fx + 1) * (ly - fy + 1) > 64:
            z = max(12, z - 1)
            n = 2 ** z
            fx, fy = int(math.floor(ox * n)), int(math.floor(oy * n))
            lx, ly = int(math.floor((ox + sx) * n)), int(math.floor((oy + sy) * n))
        for tx in range(fx, lx + 1):
            for ty in range(fy, ly + 1):
                if 0 <= ty < n:
                    tile_set.add((z, tx % n, ty))

    # 1. 全程总览窗口（覆盖 split 竖屏下地图、float 悬浮卡片、横竖屏全屏片头）
    for aspect, ref_w in (
        (1080.0 / 1190.0, 1080),
        (1080.0 / 1190.0, 2160),
        (220.0 / 140.0, 480),
        (220.0 / 140.0, 840),
        (16.0 / 9.0, 1920),
        (9.0 / 16.0, 1080),
    ):
        full_frame = overlay_widgets.route_mercator_frame(
            points,
            scale_meters=0,
            center=None,
            aspect=aspect,
            overview_blend=1.0,
        )
        _add_frame_tiles(full_frame, ref_w)

    # 2. 沿轨迹走廊多比例尺瓦片（覆盖 z=12..17，包括 10s:50s 周期缩放过渡与局部跟随）
    sampled: list[TrackPoint] = [points[0]]
    for pt in points[1:]:
        if abs(pt.latitude - sampled[-1].latitude) + abs(pt.longitude - sampled[-1].longitude) >= 0.00038:
            sampled.append(pt)
    if sampled[-1] is not points[-1]:
        sampled.append(points[-1])

    for z in range(12, 18):
        n = 2 ** z
        pad = 2 if z <= 15 else 1
        for pt in sampled:
            tx, ty = amap_tile_xy(pt.latitude, pt.longitude, z)
            ix, iy = int(tx), int(ty)
            for dx in range(-pad, pad + 1):
                for dy in range(-pad, pad + 1):
                    ny = iy + dy
                    if 0 <= ny < n:
                        tile_set.add((z, (ix + dx) % n, ny))

    # 3. 结合当前配置的精确局部窗口抽样
    cfg_scale = float(getattr(layout.map_layer, "scale_meters", 1020) or 1020) if layout else 1020.0
    if cfg_scale > 0:
        step = max(1, len(sampled) // 24)
        for pt in sampled[::step]:
            for blend in (0.0, 0.25, 0.5, 0.75, 1.0):
                fr = overlay_widgets.route_mercator_frame(
                    points,
                    scale_meters=cfg_scale,
                    center=pt,
                    aspect=1080.0 / 1190.0,
                    overview_blend=blend,
                )
                _add_frame_tiles(fr, 1080)
                _add_frame_tiles(fr, 2160)

    return sorted(tile_set)


def trip_offline_map_status(workspace: Path, trip: RemoteTrip) -> dict[str, Any]:
    """检查某趟行程的离线卫星图/道路图瓦片是否已下载到本机磁盘。"""
    marker = library_raw(workspace, trip.id) / ".amap_offline_manifest.json"
    if marker.is_file():
        try:
            info = json.loads(marker.read_text(encoding="utf-8", errors="ignore"))
            total = int(info.get("totalTiles") or 0)
            if total > 0:
                return {
                    "ready": True,
                    "cachedTiles": total,
                    "totalTiles": total,
                    "updatedAt": info.get("updatedAt") or "",
                }
        except Exception:  # noqa: BLE001
            pass
    pts = preview_track_points(trip, workspace)
    if len(pts) < 2:
        return {"ready": False, "cachedTiles": 0, "totalTiles": 0}
    coords = collect_trip_amap_tiles(pts)
    if not coords:
        return {"ready": False, "cachedTiles": 0, "totalTiles": 0}
    # 抽样检查卫星合成瓦片是否已落盘，避免每次 /api/trips 扫上千个文件
    sample_coords = coords[:: max(1, len(coords) // 24)]
    hit = sum(1 for z, x, y in sample_coords if is_amap_tile_cached_on_disk(z, x, y, "satellite"))
    ready = hit == len(sample_coords) and len(sample_coords) > 0
    est_cached = len(coords) if ready else int(round(len(coords) * hit / max(1, len(sample_coords))))
    return {
        "ready": ready,
        "cachedTiles": est_cached,
        "totalTiles": len(coords),
    }


def download_trip_offline_map_tiles(
    trip_id: str,
    workspace: Path,
    *,
    styles: tuple[str, ...] = ("satellite", "road"),
    on_progress: Callable[[int, int, str], None] | None = None,
) -> dict[str, Any]:
    """离线批量下载指定行程的全部高德卫星图（含路网与中文地名注记合成）及道路图瓦片到本机磁盘。"""
    remote = open_trip(trip_id, workspace)
    points = preview_track_points(remote, workspace)
    if len(points) < 2:
        return {"tripId": trip_id, "totalTiles": 0, "downloadedTiles": 0, "ready": False}
    layout = load_layout()
    coords = collect_trip_amap_tiles(points, layout=layout)
    tasks: list[tuple[int, int, int, str]] = []
    for style in styles:
        for z, x, y in coords:
            tasks.append((z, x, y, style))
    total = len(tasks)
    if total == 0:
        return {"tripId": trip_id, "totalTiles": 0, "downloadedTiles": 0, "ready": False}
    done = 0
    downloaded = 0
    lock = threading.Lock()

    def _fetch_one(item: tuple[int, int, int, str]) -> bool:
        nonlocal done, downloaded
        z, x, y, st = item
        ok = False
        try:
            _decode_amap_tile_rgb(z, x, y, st)
            ok = True
        except Exception:  # noqa: BLE001
            ok = False
        with lock:
            done += 1
            if ok:
                downloaded += 1
            cur_done = done
        if on_progress is not None and (cur_done == total or cur_done % 8 == 0):
            on_progress(cur_done, total, f"正在离线缓存高德卫星/道路瓦片 ({cur_done}/{total})")
        return ok

    with ThreadPoolExecutor(max_workers=16) as pool:
        list(pool.map(_fetch_one, tasks))

    raw_dir = library_raw(workspace, trip_id)
    if raw_dir.is_dir():
        marker = raw_dir / ".amap_offline_manifest.json"
        try:
            marker.write_text(
                json.dumps(
                    {
                        "tripId": trip_id,
                        "totalTiles": len(coords),
                        "styles": list(styles),
                        "updatedAt": datetime.now().isoformat(timespec="seconds"),
                    },
                    ensure_ascii=False,
                    indent=2,
                ),
                encoding="utf-8",
            )
        except OSError:
            pass
    return {
        "tripId": trip_id,
        "totalTiles": len(coords),
        "downloadedTiles": downloaded,
        "ready": downloaded >= int(total * 0.9),
    }
@lru_cache(maxsize=256)
def _download_amap_tile(zoom: int, x: int, y: int, style: str = "road") -> bytes:
    """拉一张高德原始瓦片；优先命中本地磁盘持久化缓存，缺失时从高德拉取并原子落盘。"""
    disk_path = _amap_tile_disk_path(zoom, x, y, f"raw_{style}", "tile")
    try:
        if disk_path.is_file() and disk_path.stat().st_size > 64:
            return disk_path.read_bytes()
    except OSError:
        pass
    # style=6 是卫星影像，style=8 是路网。卫星图才是用户说的「高德地图」观感。
    if style == "satellite":
        host = f"https://webst0{(x % 4) + 1}.is.autonavi.com"
        url = f"{host}/appmaptile?style=6&x={x}&y={y}&z={zoom}"
    elif style == "satellite_roads":
        # wprd0*.is.autonavi.com 的 style=8&ltype=2 仅包含透明路网线条（不含文字）
        host = f"https://wprd0{(x % 4) + 1}.is.autonavi.com"
        url = f"{host}/appmaptile?lang=zh_cn&size=1&scale=1&style=8&ltype=2&x={x}&y={y}&z={zoom}"
    elif style == "satellite_text":
        # wprd0*.is.autonavi.com 的 style=8&ltype=4 仅包含透明地名与路名文字注记（不含路网线）
        host = f"https://wprd0{(x % 4) + 1}.is.autonavi.com"
        url = f"{host}/appmaptile?lang=zh_cn&size=1&scale=1&style=8&ltype=4&x={x}&y={y}&z={zoom}"
    elif style == "satellite_labels":
        # webst0*.is.autonavi.com 的 style=8 是专为卫星底图配套的透明路网+地名注记图层（RGBA PNG）
        host = f"https://webst0{(x % 4) + 1}.is.autonavi.com"
        url = f"{host}/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={zoom}"
    else:
        host = f"https://webrd0{(x % 4) + 1}.is.autonavi.com"
        url = f"{host}/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={zoom}"
    request = Request(url, headers={"User-Agent": "CarSpeedStudio/1.0", "Referer": "https://www.amap.com/"})
    with urlopen(request, timeout=15) as response:
        payload = response.read()
    if payload and len(payload) > 64:
        try:
            disk_path.parent.mkdir(parents=True, exist_ok=True)
            tmp_path = disk_path.with_suffix(f".{os.getpid()}.{threading.get_ident()}.tmp")
            tmp_path.write_bytes(payload)
            tmp_path.replace(disk_path)
        except OSError:
            pass
    return payload


_SAT_BASE_R_LUT = [max(0, min(255, int(round(i * 0.78 + 8 * 0.12)))) for i in range(256)]
_SAT_BASE_G_LUT = [max(0, min(255, int(round(i * 0.78 + 12 * 0.12)))) for i in range(256)]
_SAT_BASE_B_LUT = [max(0, min(255, int(round(i * 0.78 + 18 * 0.12)))) for i in range(256)]
_SAT_ROAD_ALPHA_LUT = [int(round(i * 0.45)) for i in range(256)]


_SAT_LABEL_INV_LUT = [
    255 if i <= 148 else (12 if i >= 228 else int(round(255 - (i - 148) / 80.0 * (255 - 12))))
    for i in range(256)
]
_SAT_LABEL_KEEP_LUT = [
    12 if i <= 50 else (255 if i >= 165 else int(round(12 + (i - 50) / 115.0 * (255 - 12))))
    for i in range(256)
]
_SAT_LABEL_ALPHA_LUT = [min(255, int(round(i * 1.26))) for i in range(256)]


@lru_cache(maxsize=128)
def _decode_amap_satellite_label_rgba(zoom: int, x: int, y: int) -> Any:
    """拉取高德 ltype=4 纯文字注记瓦片并自适应转换为高对比「纯白字芯 + 深黑描边」RGBA 图层。

    高德注记层中：
    - 普通 POI / 道路名采用「浅灰字芯(#939698) + 白描边(#ffffff)」，在卫星图上白边会糊成白块导致字芯看不清；
    - 村落名(如龚家斗)本身已是「亮色字芯 + 黑描边(L<45)」。
    通过局部最小亮度检测自动区分两类标签，统一输出「纯白字芯 + 深黑描边」。
    """
    assert Image is not None
    label_raw = _download_amap_tile(zoom, x, y, "satellite_text")
    with Image.open(io.BytesIO(label_raw)) as l_handle:
        label_rgba = l_handle.convert("RGBA")
    _, _, _, la = label_rgba.split()
    lum = label_rgba.convert("L")
    inv_c = lum.point(_SAT_LABEL_INV_LUT)
    if ImageFilter is not None:
        white_bg = Image.new("L", label_rgba.size, 255)
        opaque_mask = la.point([255 if i > 40 else 0 for i in range(256)])
        lum_opaque = Image.composite(lum, white_bg, opaque_mask)
        local_min = lum_opaque.filter(ImageFilter.MinFilter(7))
        dark_halo_mask = local_min.point([255 if i < 45 else 0 for i in range(256)])
        keep_c = lum.point(_SAT_LABEL_KEEP_LUT)
        final_c = Image.composite(keep_c, inv_c, dark_halo_mask)
    else:
        final_c = inv_c
    return Image.merge("RGBA", (final_c, final_c, final_c, la.point(_SAT_LABEL_ALPHA_LUT)))


@lru_cache(maxsize=256)
def _decode_amap_tile_rgb(zoom: int, x: int, y: int, style: str = "road") -> Any:
    """拉取并解码单块高德瓦片为 RGB Image 对象并持久化缓存；卫星模式分层叠加半透明路网(ltype=2)与白字黑边高对比地名注记(ltype=4)。"""
    assert Image is not None
    style_key = "road" if str(style) == "road" else "satellite"
    composed_disk = _amap_tile_disk_path(zoom, x, y, f"composed_{style_key}", "jpg")
    try:
        if composed_disk.is_file() and composed_disk.stat().st_size > 128:
            with Image.open(composed_disk) as cached_img:
                return cached_img.convert("RGB")
    except Exception:  # noqa: BLE001
        pass
    if style_key == "satellite":
        # 使用独立的子瓦片线程池 _AMAP_SUBTILE_FETCH_POOL 并发拉取卫星底图、半透明路网与文字注记，
        # 避免在外层 _AMAP_TILE_FETCH_POOL 满载时发生嵌套 submit 线程饥饿死锁
        f_sat = _AMAP_SUBTILE_FETCH_POOL.submit(_download_amap_tile, zoom, x, y, "satellite")
        f_road = _AMAP_SUBTILE_FETCH_POOL.submit(_download_amap_tile, zoom, x, y, "satellite_roads")
        f_text = _AMAP_SUBTILE_FETCH_POOL.submit(_download_amap_tile, zoom, x, y, "satellite_text")
        raw = f_sat.result(timeout=18)
    else:
        raw = _download_amap_tile(zoom, x, y, style_key)
    with Image.open(io.BytesIO(raw)) as handle:
        if handle.mode == "P" and "transparency" in handle.info:
            base_rgba = handle.convert("RGBA")
        elif style_key == "satellite":
            base_rgba = handle.convert("RGBA")
        else:
            out_road = handle.convert("RGB")
            try:
                composed_disk.parent.mkdir(parents=True, exist_ok=True)
                tmp_jpg = composed_disk.with_suffix(f".{os.getpid()}.{threading.get_ident()}.tmp")
                out_road.save(tmp_jpg, format="JPEG", quality=95, subsampling=0)
                tmp_jpg.replace(composed_disk)
            except Exception:  # noqa: BLE001
                pass
            return out_road
    if style_key == "satellite":
        # 1. 只压暗底层纯卫星影像（style=6），不压暗上层地名文字
        br, bg, bb, ba = base_rgba.split()
        base_rgba = Image.merge(
            "RGBA",
            (
                br.point(_SAT_BASE_R_LUT),
                bg.point(_SAT_BASE_G_LUT),
                bb.point(_SAT_BASE_B_LUT),
                ba,
            ),
        )
        # 2. 叠加柔和半透明路网线条（ltype=2，避免粗白路网压住卫星细节和文字）
        try:
            road_raw = f_road.result(timeout=15)
            with Image.open(io.BytesIO(road_raw)) as r_handle:
                road_rgba = r_handle.convert("RGBA")
            rr, rg, rb, ra = road_rgba.split()
            road_rgba = Image.merge("RGBA", (rr, rg, rb, ra.point(_SAT_ROAD_ALPHA_LUT)))
            base_rgba.alpha_composite(road_rgba)
        except Exception:  # noqa: BLE001
            pass
        # 3. 叠加纯文字注记层（ltype=4），转换为高对比「纯白字芯 + 深色描边」
        try:
            _ = f_text.result(timeout=15)
            label_rgba = _decode_amap_satellite_label_rgba(zoom, x, y)
            base_rgba.alpha_composite(label_rgba)
        except Exception:  # noqa: BLE001 - 注记层失败时保留纯卫星底图
            pass
    out_rgb = base_rgba.convert("RGB")
    if ImageFilter is not None:
        out_rgb = out_rgb.filter(ImageFilter.UnsharpMask(radius=0.75, percent=85, threshold=2))
    try:
        composed_disk.parent.mkdir(parents=True, exist_ok=True)
        tmp_jpg = composed_disk.with_suffix(f".{os.getpid()}.{threading.get_ident()}.tmp")
        out_rgb.save(tmp_jpg, format="JPEG", quality=95, subsampling=0)
        tmp_jpg.replace(composed_disk)
    except Exception:  # noqa: BLE001
        pass
    return out_rgb


def amap_base_map(config: MapLayerLayout, points: list[TrackPoint], diameter: int) -> Any | None:
    """按轨迹范围拼一张真实高德底图，返回直径见方的 RGBA 图。

    直接用高德瓦片而不是自造纹理：假纹理无论怎么调都只是噪点，
    看不出路网走向，导航观感完全出不来。瓦片按 Web 墨卡托拼好后
    再按米制比例裁切，才能和 map_projection 的轨迹对齐。
    """
    assert Image is not None
    if diameter <= 0:
        return None
    lat = sum(p.latitude for p in points) / len(points)
    lon = sum(p.longitude for p in points) / len(points)
    # 选一个瓦片分辨率合适的层级：目标是一块瓦片覆盖的地面距离
    # 大约等于圆盘半径，再往高层级就只是放大噪点。
    scale_m = max(60, config.scale_meters)
    diameter = max(64, diameter)
    # 标准 Web 墨卡托：zoom z 下 156543.03392*cos(lat)/2^z 就是每像素米数。
    # 目标是让圆盘直径刚好覆盖 2*scale_m，地图细节和视野才匹配。
    meters_per_px_full = 156543.03392 * math.cos(math.radians(lat))
    target_mpp = (2 * scale_m) / diameter
    zoom = int(round(math.log2(meters_per_px_full / max(1e-9, target_mpp))))
    zoom = max(12, min(18, zoom))

    meters_per_px = meters_per_px_full / (2 ** zoom)
    # 多拼一圈，随航向旋转时圆盘边缘不露底。
    radius_px = scale_m / meters_per_px
    need = int(radius_px * 2 * 1.4) + 256
    # 限制单次拼接的瓦片数，避免 zoom 或比例尺异常时拉爆网络。
    need = min(need, 256 + 256 * 9)
    center_x, center_y = amap_tile_xy(lat, lon, zoom)
    center_px = (center_x * 256.0, center_y * 256.0)
    origin_px = (center_px[0] - need / 2, center_px[1] - need / 2)

    canvas = Image.new("RGB", (need, need), (238, 240, 235))
    first_x = int(math.floor(origin_px[0] / 256))
    first_y = int(math.floor(origin_px[1] / 256))
    last_x = int(math.floor((origin_px[0] + need) / 256))
    last_y = int(math.floor((origin_px[1] + need) / 256))
    limit = 2 ** zoom
    tile_coords: list[tuple[int, int, int]] = []
    for tile_x in range(first_x, last_x + 1):
        for tile_y in range(first_y, last_y + 1):
            if tile_y < 0 or tile_y >= limit:
                continue
            tile_coords.append((tile_x, tile_y, tile_x % limit))
    style_for_base = config.map_style or "satellite"
    futs = [
        (tx, ty, _AMAP_TILE_FETCH_POOL.submit(_decode_amap_tile_rgb, zoom, wx, ty, style_for_base))
        for tx, ty, wx in tile_coords
    ]
    fetched = 0
    for tile_x, tile_y, fut in futs:
        try:
            tile = fut.result(timeout=20)
        except Exception:  # noqa: BLE001 - 单块失败就留底色，不影响整体
            continue
        canvas.paste(tile, (int(tile_x * 256 - origin_px[0]), int(tile_y * 256 - origin_px[1])))
        fetched += 1
    if fetched == 0:
        return None

    # 圆盘中心落在 need 画布的正中，裁出直径见方即可。
    center_in_canvas = (center_px[0] - origin_px[0], center_px[1] - origin_px[1])
    box = (
        int(center_in_canvas[0] - diameter / 2),
        int(center_in_canvas[1] - diameter / 2),
        int(center_in_canvas[0] + diameter / 2),
        int(center_in_canvas[1] + diameter / 2),
    )
    cropped = canvas.crop(box)
    if cropped.size != (diameter, diameter):
        cropped = cropped.resize((diameter, diameter), Image.LANCZOS)
    return cropped.convert("RGBA")


_AMAP_TILE_CACHE: dict[tuple[Any, ...], Any] = {}
_AMAP_GRID_CANVAS_CACHE: dict[tuple[Any, ...], Any] = {}
_AMAP_CARD_DATA_URL_CACHE: dict[tuple[Any, ...], str] = {}
# 演示行程的预览底图按画面尺寸缓存，拖排版滑块时不用每次重拼瓦片。
_DEMO_BACKGROUND_CACHE: dict[tuple[Any, ...], Any] = {}


def amap_card_base_map(
    points: list[TrackPoint],
    width: int,
    height: int,
    map_style: str = "satellite",
    *,
    scale_meters: float = 0,
    center: TrackPoint | None = None,
    aspect: float | None = None,
    overview_blend: float = 0.0,
) -> Any | None:
    """按轨迹墨卡托裁切一张和卡片同比例的高德底图，和 route_polyline 对齐。"""
    assert Image is not None
    width = int(width)
    height = int(height)
    if width <= 0 or height <= 0 or len(points) < 2:
        return None
    try:
        import overlay_widgets
    except Exception:  # noqa: BLE001
        return None
    eff_aspect = aspect if (aspect and aspect > 0.05) else (float(width) / max(1.0, float(height)))
    frame = overlay_widgets.route_mercator_frame(
        points,
        scale_meters=scale_meters,
        center=center,
        aspect=eff_aspect,
        overview_blend=overview_blend,
    )
    if frame is None:
        return None
    _, origin_x, origin_y, span_x, span_y = frame
    # 整张图刚好装下这扇窗口，再选合适的瓦片层级。
    mid_lat = sum(point.latitude for point in points) / len(points)
    meters_per_unit = 40075016.686 * math.cos(math.radians(mid_lat))
    meters_per_px_full = meters_per_unit / 256.0
    target_mpp = (span_x * meters_per_unit) / max(1, width)
    zoom = int(round(math.log2(meters_per_px_full / max(1e-12, target_mpp))))
    zoom = max(12, min(18, zoom))
    n = 2 ** zoom
    left = origin_x * n
    top = origin_y * n
    right = (origin_x + span_x) * n
    bottom = (origin_y + span_y) * n
    first_x = int(math.floor(left))
    first_y = int(math.floor(top))
    last_x = int(math.floor(right))
    last_y = int(math.floor(bottom))
    # 限制瓦片数，避免一次预览拉爆。
    if (last_x - first_x + 1) * (last_y - first_y + 1) > 64:
        zoom = max(12, zoom - 1)
        n = 2 ** zoom
        left = origin_x * n
        top = origin_y * n
        right = (origin_x + span_x) * n
        bottom = (origin_y + span_y) * n
        first_x = int(math.floor(left))
        first_y = int(math.floor(top))
        last_x = int(math.floor(right))
        last_y = int(math.floor(bottom))

    style_key = map_style if map_style in {"satellite", "road"} else "satellite"
    grid_key = (zoom, first_x, first_y, last_x, last_y, style_key)
    with MEM_CACHE_LOCK:
        canvas = _AMAP_GRID_CANVAS_CACHE.get(grid_key)
    if canvas is None:
        canvas_w = (last_x - first_x + 1) * 256
        canvas_h = (last_y - first_y + 1) * 256
        built = Image.new("RGB", (canvas_w, canvas_h), (28, 34, 42))
        grid_coords: list[tuple[int, int, int]] = []
        for tile_x in range(first_x, last_x + 1):
            for tile_y in range(first_y, last_y + 1):
                if tile_y < 0 or tile_y >= n:
                    continue
                grid_coords.append((tile_x, tile_y, tile_x % n))
        futs = [
            (tx, ty, _AMAP_TILE_FETCH_POOL.submit(_decode_amap_tile_rgb, zoom, wx, ty, style_key))
            for tx, ty, wx in grid_coords
        ]
        fetched = 0
        for tile_x, tile_y, fut in futs:
            try:
                tile = fut.result(timeout=20)
            except Exception:  # noqa: BLE001
                continue
            built.paste(tile, ((tile_x - first_x) * 256, (tile_y - first_y) * 256))
            fetched += 1
        if fetched == 0:
            return None
        with MEM_CACHE_LOCK:
            if len(_AMAP_GRID_CANVAS_CACHE) > 16:
                _AMAP_GRID_CANVAS_CACHE.clear()
            _AMAP_GRID_CANVAS_CACHE[grid_key] = built
        canvas = built
    # 使用 SIMD 加速的亚像素浮点 box + BICUBIC 三次卷积重采样裁切，使 center_pt 零误差落在卡片正中心
    x0 = (left - first_x) * 256.0
    y0 = (top - first_y) * 256.0
    x1 = (right - first_x) * 256.0
    y1 = (bottom - first_y) * 256.0
    resampled = canvas.resize(
        (width, height),
        resample=Image.BICUBIC,
        box=(x0, y0, x1, y1),
    )
    return resampled.convert("RGBA")


def amap_card_data_url(
    points: list[TrackPoint],
    width: int,
    height: int,
    map_style: str = "satellite",
    *,
    scale_meters: float = 0,
    center: TrackPoint | None = None,
    aspect: float | None = None,
    overview_blend: float = 0.0,
) -> str:
    """把高德卡片底图编码成 data URL，给浏览器覆盖层直接引用。"""
    if len(points) < 2:
        return ""
    w_key = 0 if overview_blend <= 1e-3 else (1000 if overview_blend >= 0.999 else int(round(overview_blend * 200)))
    key = (
        round(points[0].latitude, 4),
        round(points[0].longitude, 4),
        round(points[-1].latitude, 4),
        round(points[-1].longitude, 4),
        len(points),
        round(center.latitude, 6) if (center is not None and w_key < 1000) else 0,
        round(center.longitude, 6) if (center is not None and w_key < 1000) else 0,
        int(scale_meters or 0),
        int(width),
        int(height),
        map_style,
        w_key,
    )
    with MEM_CACHE_LOCK:
        cached = _AMAP_CARD_DATA_URL_CACHE.get(key)
        if cached is not None:
            return cached
    image = amap_card_base_map(
        points,
        width,
        height,
        map_style,
        scale_meters=scale_meters,
        center=center,
        aspect=aspect,
        overview_blend=overview_blend,
    )
    if image is None:
        return ""
    buffer = io.BytesIO()
    image.convert("RGB").save(buffer, format="JPEG", quality=94, subsampling=0)
    data_url = "data:image/jpeg;base64," + base64.b64encode(buffer.getvalue()).decode("ascii")
    with MEM_CACHE_LOCK:
        if len(_AMAP_CARD_DATA_URL_CACHE) > 12:
            _AMAP_CARD_DATA_URL_CACHE.clear()
        _AMAP_CARD_DATA_URL_CACHE[key] = data_url
    return data_url


def draw_map_satellite_background(
    circle: Any,
    config: MapLayerLayout,
    points: list[TrackPoint],
    elapsed_ms: int,
    heading_deg: float | None = None,
) -> None:
    """圆盘底图：优先用真实高德地图瓦片，无网络时退回本地纹理。

    随航向旋转开启时，轨迹已经在 map_projection 里转过 -heading，
    底图必须同角度反向旋转才能对得上；否则路网和车辆位置会错开。
    """
    diameter = circle.width
    key = (
        round(sum(p.latitude for p in points) / max(1, len(points)), 4),
        round(sum(p.longitude for p in points) / max(1, len(points)), 4),
        diameter,
        config.scale_meters,
        config.map_style or "satellite",
    )
    with MEM_CACHE_LOCK:
        cached = _AMAP_TILE_CACHE.get(key)
    if cached is None:
        cached = amap_base_map(config, points, diameter)
        if cached is None:
            # 拉不到瓦片时才用本地纹理兜底，保证离线也能出片。
            tile = satellite_base_tile(config, points, circle.width)
            circle.alpha_composite(tile)
            return
        with MEM_CACHE_LOCK:
            if len(_AMAP_TILE_CACHE) > 8:
                _AMAP_TILE_CACHE.clear()
            _AMAP_TILE_CACHE[key] = cached
    base = cached
    if config.rotate_with_heading and heading_deg:
        # 底图要按 -heading 旋转，和 map_projection 里对轨迹做的是同一个变换；
        # 放大到 √2 倍再转，避免旋转后四角露出空白。
        big = int(diameter * 1.45)
        scaled = cached.resize((big, big), Image.LANCZOS)
        rotated = scaled.rotate(heading_deg, resample=Image.BICUBIC, expand=False)
        offset = (big - diameter) // 2
        base = rotated.crop((offset, offset, offset + diameter, offset + diameter))
    circle.alpha_composite(base)
    # 压暗一层，让轨迹和白字在浅色底图上依然清晰。
    circle.alpha_composite(Image.new("RGBA", circle.size, (10, 16, 22, 46)))


_SAT_TILE_CACHE: dict[tuple[Any, ...], Any] = {}


def satellite_base_tile(config: MapLayerLayout, points: list[TrackPoint], diameter: int) -> Any:
    """按轨迹范围和尺寸缓存底图纹理；同一趟行程的所有帧共用一张。"""
    import numpy as np

    min_lat = min(p.latitude for p in points)
    max_lat = max(p.latitude for p in points)
    min_lon = min(p.longitude for p in points)
    max_lon = max(p.longitude for p in points)
    key = (round(min_lat, 5), round(max_lat, 5), round(min_lon, 5), round(max_lon, 5), diameter, config.background_color)
    with MEM_CACHE_LOCK:
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
    # 再压一层低频明暗，像卫星图里的地块与树丛；不用直线纹理——
    # 之前的等距斜纹在 237px 的圆盘上像渲染故障，而不是地图。
    patch = np.sin((u * 3.7 + seed) * math.tau) * np.cos((v * 2.9 - seed) * math.tau) * 12.0
    rgb = np.clip(rgb + patch[..., None], 0, 255)
    alpha = np.full((diameter, diameter, 1), 255, dtype=np.uint8)
    array = np.concatenate([rgb.astype(np.uint8), alpha], axis=2)
    tile = Image.fromarray(array, mode="RGBA")
    # 只保留最近一趟行程的底图，避免长时间运行时缓存膨胀。
    with MEM_CACHE_LOCK:
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
    # 高德底图是浅色的：先铺一条深色描边再画亮色轨迹，
    # 否则浅蓝线压在白底道路上几乎看不见。
    route_width = max(3, diameter // 70)
    draw.line(route_xy, fill=(20, 30, 42, 190), width=route_width + max(2, diameter // 110), joint="curve")
    draw.line(route_xy, fill=with_alpha(hex_to_rgb(config.route_color, (59, 130, 246)), 240), width=route_width, joint="curve")
    played = max(1, min(len(points) - 1, last_played_index(points, elapsed_ms)))
    draw.line(route_xy[: played + 1], fill=(20, 30, 42, 200), width=max(2, diameter // 90) + max(2, diameter // 120), joint="curve")
    draw.line(route_xy[: played + 1], fill=with_alpha(hex_to_rgb(config.played_color, (255, 255, 255)), 255), width=max(2, diameter // 90), joint="curve")
    draw_heading_arrow(
        circle,
        diameter / 2,
        diameter / 2,
        diameter,
        config.marker_color,
        # 地图不跟航向旋转时，车头方向得由箭头自己表达，否则永远指北。
        0.0 if config.rotate_with_heading else (heading or 0.0),
    )


def draw_heading_arrow(
    target: Any,
    center_x: float,
    center_y: float,
    diameter: int,
    color_hex: str,
    heading_deg: float = 0.0,
) -> None:
    """车头箭头，heading_deg 是相对屏幕正上方的旋转角（顺时针）。

    造型参考主流导航 App：钝角三角箭头 + 尾部凹口，白色描边加一层柔和投影。
    之前用「三角形叠一个矩形」拼出来，接缝处轮廓发硬，缩到几十像素像一块色块。
    """
    assert Image is not None
    size = max(10.0, diameter * 0.085)
    color = with_alpha(hex_to_rgb(color_hex, (59, 130, 246)), 255)

    pad = int(size * 1.6)
    canvas_size = int(size * 4 + pad)
    sprite = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
    sprite_draw = ImageDraw.Draw(sprite)
    mid = canvas_size / 2

    # 箭头轮廓（朝上）：顶点、两翼、尾部凹口，整体是一个七边形。
    def arrow_polygon(scale: float, dy: float = 0.0) -> list[tuple[float, float]]:
        return [
            (mid, mid - size * 1.55 * scale + dy),
            (mid + size * 0.95 * scale, mid + size * 0.35 * scale + dy),
            (mid + size * 0.55 * scale, mid + size * 0.28 * scale + dy),
            (mid + size * 0.42 * scale, mid + size * 1.20 * scale + dy),
            (mid - size * 0.42 * scale, mid + size * 1.20 * scale + dy),
            (mid - size * 0.55 * scale, mid + size * 0.28 * scale + dy),
            (mid - size * 0.95 * scale, mid + size * 0.35 * scale + dy),
        ]

    shadow = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).polygon(arrow_polygon(1.06, size * 0.16), fill=(0, 0, 0, 110))
    sprite.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(size * 0.22)))

    # 白色描边：先画大一号的白底，再叠彩色内芯。
    sprite_draw.polygon(arrow_polygon(1.0), fill=(255, 255, 255, 245))
    sprite_draw.polygon(arrow_polygon(0.78), fill=color)

    # 中心高光，让箭头有立体感而不是一块塑料色。
    highlight = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
    ImageDraw.Draw(highlight).polygon(
        [
            (mid, mid - size * 1.05),
            (mid + size * 0.34, mid - size * 0.05),
            (mid - size * 0.34, mid - size * 0.05),
        ],
        fill=(255, 255, 255, 90),
    )
    sprite.alpha_composite(highlight)

    if heading_deg:
        # PIL 的 rotate 是逆时针，航向角是顺时针，所以要取负。
        sprite = sprite.rotate(-heading_deg, resample=Image.BICUBIC, expand=False)

    # 参考稿的车标带一圈白色辉光，压在卫星底图上才醒目。
    halo = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
    ImageDraw.Draw(halo).polygon(arrow_polygon(0.80), fill=(255, 255, 255, 255))
    if heading_deg:
        halo = halo.rotate(-heading_deg, resample=Image.BICUBIC, expand=False)
    tinted = Image.new("RGBA", (canvas_size, canvas_size), (255, 255, 255, 0))
    tinted.putalpha(halo.getchannel("A").point(lambda value: int(value * 0.75)))
    glow = tinted.filter(ImageFilter.GaussianBlur(size * 0.42))
    target.alpha_composite(glow, (int(center_x - canvas_size / 2), int(center_y - canvas_size / 2)))
    target.alpha_composite(sprite, (int(center_x - canvas_size / 2), int(center_y - canvas_size / 2)))


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
    max_lon = max(point.longitude for point in points)
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


_CUES_STARTS_CACHE: dict[tuple[Any, ...], list[int]] = {}


def _cues_starts(cues: list[Cue]) -> list[int]:
    """提取并缓存字幕起始毫秒列表，供二分查找快速定位。"""
    if not cues:
        return []
    mid = cues[len(cues) // 2]
    key = (len(cues), cues[0].start_ms, cues[-1].end_ms, mid.start_ms, cues[0].text[:24], cues[-1].text[:24])
    with MEM_CACHE_LOCK:
        cached = _CUES_STARTS_CACHE.get(key)
        if cached is not None:
            return cached
    starts = [cue.start_ms for cue in cues]
    with MEM_CACHE_LOCK:
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
    err_chunks, err_thread = _start_stderr_drain(process)
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
        code = process.wait(timeout=1800)
        stderr = _finish_stderr_drain(err_chunks, err_thread)
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
    return max(5, min(120, int(value or DEFAULT_FPS)))


def bitrate(width: int, height: int, fps: int) -> str:
    value = max(2_000_000, min(28_000_000, int(width * height * min(fps, 60) * 0.16)))
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
        playback_speed=float(data["playbackSpeed"]) if data.get("playbackSpeed") is not None else None,
        include_route_map=bool(data.get("includeRouteMap", True)),
        map_height_percent=int(data.get("mapHeightPercent") or 28),
        map_scale_percent=int(data.get("mapScalePercent") or 100),
        fps=int(float(data["fps"])) if data.get("fps") is not None else None,
        stabilize=bool(data["stabilize"]) if data.get("stabilize") is not None else None,
        stabilize_strength=int(float(data["stabilizeStrength"])) if data.get("stabilizeStrength") is not None else None,
        stabilize_crop_percent=int(float(data["stabilizeCropPercent"])) if data.get("stabilizeCropPercent") is not None else None,
        intro_map=bool(data["introMap"]) if data.get("introMap") is not None else None,
        intro_satellite_seconds=int(float(data["introSatelliteSeconds"])) if data.get("introSatelliteSeconds") is not None else None,
        intro_road_seconds=int(float(data["introRoadSeconds"])) if data.get("introRoadSeconds") is not None else None,
        intro_scale_meters=int(float(data["introScaleMeters"])) if data.get("introScaleMeters") is not None else None,
        privacy_mode=bool(data["privacyMode"]) if data.get("privacyMode") is not None else None,
        privacy_trim_meters=int(float(data["privacyTrimMeters"])) if data.get("privacyTrimMeters") is not None else None,
        export_resolution=str(data["exportResolution"]).strip().lower() if data.get("exportResolution") is not None else None,
        export_width=int(float(data["exportWidth"])) if data.get("exportWidth") is not None else None,
        export_height=int(float(data["exportHeight"])) if data.get("exportHeight") is not None else None,
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
    existing = JOBS.active_export_for_trip(options.trip)
    if existing is not None:
        return existing
    job = JOBS.create()
    JOBS.update(job.id, kind="export", trip=options.trip)
    job_root = workspace / job.id
    job_root.mkdir(parents=True, exist_ok=True)

    def progress(percent: int, message: str) -> None:
        JOBS.update(job.id, status="running", progress=max(0, min(99, percent)), message=message)

    def worker() -> None:
        try:
            progress(1, "检查 adb 和 ffmpeg")
            tools = check_tools()
            local_trip = scan_library_trip(workspace, options.trip)
            required_tools = ["ffmpeg", "ffprobe", "pillow"]
            if local_trip is None:
                required_tools.insert(0, "adb")
            missing = [name for name in required_tools if tools.get(name) == "missing"]
            if missing:
                raise RuntimeError("缺少工具：" + ", ".join(missing))
            if local_trip is None and not adb_devices():
                raise RuntimeError("没有连接可用 Android 设备")
            pulled = pull_trip(options.trip, job_root, progress, workspace=workspace)
            output = compose_video(pulled, options, progress)
            JOBS.update(job.id, status="done", progress=100, message="导出完成", output=str(output))
        except Exception as error:  # noqa: BLE001
            JOBS.update(job.id, status="failed", progress=100, message="导出失败", error=str(error))

    threading.Thread(target=worker, daemon=True).start()
    return job


def recover_job_from_workspace(job_id: str, workspace: Path) -> Job | None:
    """从磁盘工作目录恢复已完成或中断的历史任务状态，避免服务重启后前端轮询卡死或下载 404。"""
    if not re.fullmatch(r"[0-9a-fA-F]{8,32}", job_id or ""):
        return None
    job_dir = workspace / job_id
    if not job_dir.is_dir():
        return None
    mp4_files = sorted(
        (p for p in job_dir.rglob("*-mac-share-*.mp4") if p.is_file() and p.stat().st_size > 0),
        key=lambda p: p.stat().st_mtime,
        reverse=True,
    )
    if mp4_files:
        best = mp4_files[0]
        trip_id = best.parent.name if best.parent != job_dir else ""
        recovered = Job(
            id=job_id,
            status="done",
            progress=100,
            message="导出完成",
            phase="done",
            kind="export",
            trip=trip_id,
            output=str(best),
            outputs=[str(p) for p in mp4_files],
        )
        with JOBS._lock:
            JOBS._jobs[job_id] = recovered
        return recovered
    fcproj_files = sorted(
        (p for p in job_dir.rglob("*.fcproj") if p.exists()),
        key=lambda p: p.stat().st_mtime,
        reverse=True,
    )
    if fcproj_files:
        best_proj = fcproj_files[0]
        trip_id = best_proj.parent.name if best_proj.parent != job_dir else ""
        recovered = Job(
            id=job_id,
            status="done",
            progress=100,
            message="已生成 FilmCraft 项目",
            phase="done",
            kind="studio",
            trip=trip_id,
            output=str(best_proj),
            outputs=[str(p) for p in fcproj_files],
        )
        with JOBS._lock:
            JOBS._jobs[job_id] = recovered
        return recovered
    return Job(
        id=job_id,
        status="failed",
        progress=100,
        message="任务已中断（服务已重启，请重新点击导出 MP4）",
        phase="done",
        kind="export",
        error="任务已中断（服务已重启，请重新点击导出 MP4）",
    )


_CACHE_GUARD = threading.Lock()
_CACHE_JOBS: dict[str, dict[str, Any]] = {}


def cache_job(job_id: str) -> dict[str, Any] | None:
    with _CACHE_GUARD:
        job = _CACHE_JOBS.get(job_id)
        return dict(job) if job else None


def _update_cache_job(job_id: str, **values: Any) -> None:
    with _CACHE_GUARD:
        current = _CACHE_JOBS.get(job_id)
        if current is not None:
            current.update(values)


def _adb_pull_file(remote_path: str, local: Path, on_bytes: Callable[[int], None], timeout_s: int = 1800) -> None:
    """拉到临时文件，完成后再换上正式名字，避免半截文件被当成已完成。"""
    local.parent.mkdir(parents=True, exist_ok=True)
    temporary = local.with_name(local.name + ".partial")
    temporary.unlink(missing_ok=True)
    # 不读管道：adb pull 的进度文本留在管道里会把子进程堵死。文件大小自己看。
    process = subprocess.Popen(
        ["adb", "pull", remote_path, str(temporary)],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    started = time.time()
    try:
        while process.poll() is None:
            if time.time() - started > timeout_s:
                process.kill()
                raise RuntimeError(f"拉取超时：{local.name}")
            on_bytes(_file_size(temporary))
            time.sleep(0.4)
        if process.returncode != 0:
            raise RuntimeError(f"拉取失败：{local.name}")
        temporary.replace(local)
    finally:
        if process.poll() is None:
            process.kill()
        temporary.unlink(missing_ok=True)


def start_library_pull(trip_ids: list[str], workspace: Path) -> dict[str, Any]:
    """把选中行程的录像、字幕、轨迹拉到 workspace/library，不生成 FilmCraft 项目。"""
    cleaned = list(dict.fromkeys(trip_id.strip() for trip_id in trip_ids if str(trip_id).strip()))
    if not cleaned:
        raise RuntimeError("至少选择一个行程")
    if any(is_demo_trip(trip_id) for trip_id in cleaned):
        raise RuntimeError("演示行程没有手机文件，不用拉到电脑")
    with _CACHE_GUARD:
        for job in _CACHE_JOBS.values():
            if job.get("status") == "running":
                raise RuntimeError("已经有传输在进行，等它完成再拉下一趟")
    ensure_tool_path()
    if not adb_devices():
        raise RuntimeError("没有连接可用 Android 设备")

    job_id = uuid.uuid4().hex[:12]
    job = {
        "id": job_id,
        "status": "running",
        "progress": 0,
        "bytesDone": 0,
        "bytesTotal": 0,
        "message": "准备从手机读取文件",
        "error": None,
        "trips": cleaned,
        "path": None,
    }
    with _CACHE_GUARD:
        _CACHE_JOBS[job_id] = job
        if len(_CACHE_JOBS) > 12:
            done_ids = [key for key, value in _CACHE_JOBS.items() if value.get("status") != "running" and key != job_id]
            for key in done_ids[:-8]:
                _CACHE_JOBS.pop(key, None)

    def worker() -> None:
        try:
            remotes = [scan_remote_trip(trip_id) for trip_id in cleaned]
            files: list[tuple[str, str, str, int]] = []
            for remote in remotes:
                for segment in remote.segments:
                    files.append((remote.id, segment.public_path, segment.name, segment.bytes))
                    for remote_path in (segment.track_path, segment.subtitle_path, segment.plate_path):
                        if remote_path:
                            files.append((remote.id, remote_path, Path(remote_path).name, 0))
            pending = []
            for trip_id, remote_path, name, expected in files:
                existing = best_local_file(workspace, trip_id, name)
                complete = video_is_complete(existing, expected) if expected > 0 else _file_size(existing) > 0
                if complete:
                    continue
                pending.append((trip_id, remote_path, name, expected))
            if not pending:
                for trip_id in cleaned:
                    _update_cache_job(
                        job_id,
                        message=f"正在补齐 {trip_id} 离线高德卫星图与道路图瓦片…",
                        progress=90,
                    )
                    download_trip_offline_map_tiles(
                        trip_id,
                        workspace,
                        on_progress=lambda cur_d, tot_d, msg, tid=trip_id: _update_cache_job(
                            job_id,
                            message=f"{tid}: {msg}",
                            progress=min(99, 80 + int(cur_d * 19 / max(1, tot_d))),
                        ),
                    )
                reveal = str(library_raw(workspace, cleaned[0]))
                _update_cache_job(job_id, status="done", progress=100, message="视频与离线卫星图均已在电脑上", path=reveal)
                return
            total_bytes = max(1, sum(max(expected, 1) for _trip, _remote, _name, expected in pending))
            done_bytes = 0
            _update_cache_job(job_id, bytesTotal=total_bytes, bytesDone=0, progress=0)
            for index, (trip_id, remote_path, name, expected) in enumerate(pending, start=1):
                destination = library_raw(workspace, trip_id) / name
                _update_cache_job(
                    job_id,
                    message=f"正在传输 {name}（{index}/{len(pending)}）",
                    bytesDone=done_bytes,
                    bytesTotal=total_bytes,
                    progress=min(99, round(done_bytes * 100 / total_bytes)),
                )

                def on_bytes(current: int, base: int = done_bytes, weight: int = max(expected, 1)) -> None:
                    ratio = min(weight, current if expected > 0 else weight)
                    moved = base + ratio
                    _update_cache_job(
                        job_id,
                        bytesDone=moved,
                        bytesTotal=total_bytes,
                        progress=min(99, round(moved * 100 / total_bytes)),
                    )

                _adb_pull_file(remote_path, destination, on_bytes)
                done_bytes += max(expected, 1)
            # 视频与轨迹文件拉取完毕后，自动离线下载该行程全路线高德卫星图与道路图瓦片，杜绝播放/导出白屏
            for trip_id in cleaned:
                _update_cache_job(
                    job_id,
                    message=f"正在离线下载 {trip_id} 高德卫星图与道路图瓦片…",
                    progress=96,
                )
                download_trip_offline_map_tiles(
                    trip_id,
                    workspace,
                    on_progress=lambda cur_d, tot_d, msg, tid=trip_id: _update_cache_job(
                        job_id,
                        message=f"{tid}: {msg}",
                        progress=min(99, 95 + int(cur_d * 4 / max(1, tot_d))),
                    ),
                )
            reveal = str(library_raw(workspace, cleaned[0]))
            _update_cache_job(job_id, status="done", progress=100, message="视频与离线卫星图已拉到电脑，播放与导出零白屏", path=reveal)
        except Exception as error:  # noqa: BLE001 - 线程里的失败要写回任务，不能丢掉
            _update_cache_job(job_id, status="failed", progress=100, message="传输失败", error=str(error))

    threading.Thread(target=worker, daemon=True).start()
    return dict(job)


def start_offline_map_download(trip_ids: list[str], workspace: Path) -> dict[str, Any]:
    """为已在电脑或手机上的行程单独触发高德卫星图+道路图全路线离线瓦片下载任务。"""
    cleaned = list(dict.fromkeys(str(trip_id).strip() for trip_id in trip_ids if str(trip_id).strip()))
    if not cleaned:
        raise RuntimeError("至少选择一个行程")
    with _CACHE_GUARD:
        for job in _CACHE_JOBS.values():
            if job.get("status") == "running":
                raise RuntimeError("已经有离线缓存任务在进行，请稍候")
    job_id = uuid.uuid4().hex[:12]
    job = {
        "id": job_id,
        "status": "running",
        "progress": 0,
        "bytesDone": 0,
        "bytesTotal": 0,
        "message": "正在计算行程轨迹覆盖的卫星图瓦片…",
        "error": None,
        "trips": cleaned,
        "path": None,
    }
    with _CACHE_GUARD:
        _CACHE_JOBS[job_id] = job

    def worker() -> None:
        try:
            total_trips = len(cleaned)
            total_downloaded = 0
            for idx, trip_id in enumerate(cleaned):
                def on_prog(cur_d: int, tot_d: int, msg: str, i: int = idx, tid: str = trip_id) -> None:
                    pct = int(((i + cur_d / max(1, tot_d)) / max(1, total_trips)) * 100)
                    _update_cache_job(
                        job_id,
                        progress=min(99, max(1, pct)),
                        message=f"{tid}: {msg}",
                    )
                res = download_trip_offline_map_tiles(trip_id, workspace, on_progress=on_prog)
                total_downloaded += int(res.get("downloadedTiles") or 0)
            reveal = str(AMAP_DISK_TILE_DIR)
            _update_cache_job(
                job_id,
                status="done",
                progress=100,
                message=f"已离线缓存 {total_downloaded} 张高德卫星/道路瓦片，播放与导出零白屏",
                path=reveal,
            )
        except Exception as error:  # noqa: BLE001
            _update_cache_job(job_id, status="failed", progress=100, message="卫星图离线下载失败", error=str(error))

    threading.Thread(target=worker, daemon=True).start()
    return dict(job)


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
    JOBS.update(job.id, kind="studio", trip=cleaned_ids[0])
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
            need_adb = any(scan_library_trip(workspace, trip_id) is None for trip_id in cleaned_ids)
            if need_adb and not adb_devices():
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
                    pulled = pull_trip(trip_id, job_root / safe_name(trip_id), progress, workspace=workspace)
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
    MAX_BODY_BYTES: int = 16 * 1024 * 1024

    def _check_local_request(self) -> bool:
        """校验 Host / Origin 头，防范跨站请求伪造与 DNS Rebinding 攻击。"""
        host = (self.headers.get("Host") or "").strip().lower()
        if host:
            host_name = host.split(":")[0]
            if host_name not in {"127.0.0.1", "localhost", "[::1]"}:
                self.send_json({"error": "非法 Host 请求头"}, status=HTTPStatus.FORBIDDEN)
                return False
        origin = (self.headers.get("Origin") or "").strip().lower()
        if origin and origin != "null":
            parsed_origin = urlparse(origin)
            origin_host = (parsed_origin.hostname or "").lower()
            if origin_host not in {"127.0.0.1", "localhost", "::1"}:
                self.send_json({"error": "非法 Origin 来源"}, status=HTTPStatus.FORBIDDEN)
                return False
        return True

    def do_HEAD(self) -> None:  # noqa: N802
        """支持浏览器 <video> 预检或静态资源的 HEAD 请求。"""
        if not self._check_local_request():
            return
        parsed = urlparse(self.path)
        if parsed.path.startswith("/api/preview-media/"):
            name = unquote(parsed.path.removeprefix("/api/preview-media/")).strip("/")
            clip = preview_media_clip(self.workspace, name)
            if clip is None:
                self.send_error(HTTPStatus.NOT_FOUND)
            else:
                self.send_byte_range(clip, send_body=False)
        elif parsed.path.startswith("/api/trips/") and "/video/" in parsed.path:
            rest = unquote(parsed.path.removeprefix("/api/trips/"))
            trip_id, _, filename = rest.partition("/video/")
            video = library_video_file(self.workspace, trip_id, filename)
            if video is None:
                self.send_error(HTTPStatus.NOT_FOUND)
            else:
                self.send_byte_range(video, send_body=False)
        else:
            self.send_response(HTTPStatus.OK)
            self.end_headers()

    def do_GET(self) -> None:  # noqa: N802
        if not self._check_local_request():
            return
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
                self.send_json({"trips": list_trips(self.workspace)})
            except Exception as error:  # noqa: BLE001
                self.send_json({"error": str(error)}, status=HTTPStatus.SERVICE_UNAVAILABLE)
        elif parsed.path.startswith("/api/cache/"):
            job_id = parsed.path.removeprefix("/api/cache/").strip("/")
            job = cache_job(job_id)
            if job is None:
                self.send_json({"error": "找不到这次传输"}, status=HTTPStatus.NOT_FOUND)
            else:
                self.send_json(job)
        elif parsed.path.startswith("/api/preview-media/"):
            name = unquote(parsed.path.removeprefix("/api/preview-media/")).strip("/")
            clip = preview_media_clip(self.workspace, name)
            if clip is None:
                self.send_error(HTTPStatus.NOT_FOUND)
            else:
                self.send_byte_range(clip)
        elif parsed.path.startswith("/api/trips/") and "/video/" in parsed.path:
            rest = unquote(parsed.path.removeprefix("/api/trips/"))
            trip_id, _, filename = rest.partition("/video/")
            video = library_video_file(self.workspace, trip_id, filename)
            if video is None:
                self.send_error(HTTPStatus.NOT_FOUND)
            else:
                self.send_byte_range(video)
        elif parsed.path.startswith("/api/trips/") and parsed.path.endswith("/thumbnail"):
            trip_id = parsed.path.removeprefix("/api/trips/").removesuffix("/thumbnail").strip("/")
            try:
                if is_demo_trip(trip_id):
                    # 演示行程没有手机缩略图，直接用演示底图裁一张当封面。
                    self.send_image(demo_background(640, 360))
                    return
                thumbnail = remote_thumbnail(trip_id, self.workspace)
                if thumbnail is not None:
                    self.send_file(thumbnail, attachment=False)
                else:
                    # 手机端还没生成缩略图（旧版 App）时，用本机已缓存录像抽一帧顶上，
                    # 别让列表只剩一张「GPS CarSpeed」占位图。
                    video = cached_trip_video(trip_id, self.workspace)
                    if video is None:
                        self.send_error(HTTPStatus.NOT_FOUND)
                    else:
                        self.send_image(video_frame(video, 60_000, 640, 360))
            except Exception as error:  # noqa: BLE001
                self.send_json({"error": str(error)}, status=HTTPStatus.SERVICE_UNAVAILABLE)
        elif parsed.path.startswith("/assets/fonts/"):
            # 只允许字体目录里的文件名，避免用 .. 读到别的文件。
            name = Path(parsed.path).name
            font = Path(__file__).resolve().parent / "assets" / "fonts" / name
            if font.is_file():
                self.send_response(HTTPStatus.OK)
                self.send_header("content-type", "font/ttf")
                self.send_header("content-length", str(font.stat().st_size))
                self.send_header("cache-control", "public, max-age=86400")
                self.end_headers()
                with font.open("rb") as handle:
                    shutil.copyfileobj(handle, self.wfile)
            else:
                self.send_error(HTTPStatus.NOT_FOUND)
        elif parsed.path == "/api/layout":
            self.send_json({"layout": layout_to_dict(load_layout()), "presets": load_layout_presets()})
        elif parsed.path == "/api/layout-presets":
            self.send_json({"presets": load_layout_presets()})
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
        if not self._check_local_request():
            return
        parsed = urlparse(self.path)
        if parsed.path in {"/api/studio", "/api/export", "/api/reveal", "/api/layout", "/api/layout-presets", "/api/preview", "/api/preview-live", "/api/preview-clip", "/api/preview-media", "/api/cache", "/api/map-cache"}:
            length = int(self.headers.get("content-length") or 0)
            if length < 0 or length > self.MAX_BODY_BYTES:
                self.send_json({"error": "请求体大小超出限制"}, status=HTTPStatus.REQUEST_ENTITY_TOO_LARGE)
                return
            try:
                data = json.loads(self.rfile.read(length).decode("utf-8") or "{}")
                if parsed.path == "/api/layout":
                    if not isinstance(data.get("layout"), dict):
                        raise RuntimeError("layout 必须是对象")
                    revision = data.get("revision")
                    try:
                        revision = int(revision) if revision is not None else None
                    except (TypeError, ValueError):
                        revision = None
                    save_layout(layout_from_dict(data["layout"]), revision=revision)
                    self.send_json({"layout": layout_to_dict(load_layout()), "presets": load_layout_presets()})
                elif parsed.path == "/api/layout-presets":
                    action = str(data.get("action") or "save").strip().lower()
                    preset_name = str(data.get("name") or "").strip()
                    if action == "save":
                        raw_layout = data.get("layout")
                        target_layout = layout_from_dict(raw_layout) if isinstance(raw_layout, dict) else load_layout()
                        presets = save_layout_preset(preset_name, target_layout)
                        self.send_json({
                            "presets": presets,
                            "layout": layout_to_dict(load_layout()),
                            "activePreset": preset_name,
                        })
                    elif action == "load":
                        loaded = activate_layout_preset(preset_name)
                        self.send_json({
                            "presets": load_layout_presets(),
                            "layout": layout_to_dict(loaded),
                            "activePreset": preset_name,
                        })
                    elif action == "delete":
                        presets = delete_layout_preset(preset_name)
                        self.send_json({"presets": presets})
                    else:
                        raise RuntimeError(f"不支持的预设操作：{action}")
                elif parsed.path == "/api/preview":
                    # 用 POST 而不是 GET 参数：排版 JSON 有 4KB 上下，塞进 URL 会被浏览器截断。
                    trip_id = str(data.get("trip") or "")
                    if not trip_id:
                        raise RuntimeError("trip 不能为空")
                    query = {
                        "t": str(data.get("t") or 0),
                        "layout": json.dumps(data["layout"]) if isinstance(data.get("layout"), dict) else "",
                    }
                    image = overlay_preview_image(trip_id, self.workspace, query)
                    # 把行程总时长回给前端，排版编辑器据此设置预览时间轴的上限；
                    # 之前写死在 120 秒，一小时的行程只能预览开头两分钟。
                    duration_ms = preview_duration_ms(trip_id, self.workspace)
                    self.send_image(image, {"x-trip-duration-ms": str(duration_ms)})
                elif parsed.path == "/api/preview-live":
                    trip_id = str(data.get("trip") or "")
                    if not trip_id:
                        raise RuntimeError("trip 不能为空")
                    query = {
                        "t": str(data.get("t") or 0),
                        "layout": json.dumps(data["layout"]) if isinstance(data.get("layout"), dict) else "",
                    }
                    payload = overlay_preview_live(
                        trip_id,
                        self.workspace,
                        query,
                        include_background=not bool(data.get("htmlOnly")),
                        include_html=not bool(data.get("backgroundOnly")),
                    )
                    self.send_json(payload)
                elif parsed.path == "/api/preview-media":
                    trip_id = str(data.get("trip") or "")
                    if not trip_id:
                        raise RuntimeError("trip 不能为空")
                    self.send_json(preview_media_payload(
                        trip_id,
                        self.workspace,
                        float(data.get("t") or 0),
                        float(data.get("seconds") or 10),
                    ))
                elif parsed.path == "/api/preview-clip":
                    trip_id = str(data.get("trip") or "")
                    if not trip_id:
                        raise RuntimeError("trip 不能为空")
                    payload = preview_clip_payload(
                        trip_id,
                        self.workspace,
                        float(data.get("t") or 0),
                        float(data.get("seconds") or 10),
                        int(data.get("fps") or 8),
                    )
                    self.send_json(payload)
                elif parsed.path == "/api/cache":
                    raw_ids = data.get("trips")
                    if isinstance(raw_ids, str):
                        raw_ids = [raw_ids]
                    if not isinstance(raw_ids, list):
                        raise RuntimeError("trips 必须是行程 ID 列表")
                    job = start_library_pull([str(trip_id) for trip_id in raw_ids], self.workspace)
                    self.send_json(job, status=HTTPStatus.ACCEPTED)
                elif parsed.path == "/api/map-cache":
                    raw_ids = data.get("trips")
                    if isinstance(raw_ids, str):
                        raw_ids = [raw_ids]
                    if not isinstance(raw_ids, list) or not raw_ids:
                        fallback_trip = str(data.get("trip") or "").strip()
                        raw_ids = [fallback_trip] if fallback_trip else []
                    job = start_offline_map_download([str(trip_id) for trip_id in raw_ids], self.workspace)
                    self.send_json(job, status=HTTPStatus.ACCEPTED)
                elif parsed.path == "/api/studio":
                    trip_ids = data.get("trips")
                    if not isinstance(trip_ids, list):
                        raise RuntimeError("trips 必须是行程 ID 列表")
                    # 演示行程只有预览用的合成轨迹，没有真实素材可生成项目。
                    # 让它混进去会让任务在 adb pull 阶段失败，不如当场说清楚。
                    if any(is_demo_trip(str(trip_id)) for trip_id in trip_ids):
                        raise RuntimeError("杭州演示行程只用于预览效果，不能生成项目；请连接手机选择真实行程")
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
        job = JOBS.get(parts[2]) or recover_job_from_workspace(parts[2], self.workspace)
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

    def send_byte_range(self, path: Path, send_body: bool = True) -> None:
        """按 Range 吐录像。浏览器要能拖到某一秒，不能把整份文件先下完。"""
        size = path.stat().st_size
        span = parse_byte_range(self.headers.get("Range"), size)
        if span is None:
            self.send_response(HTTPStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
            self.send_header("content-range", f"bytes */{size}")
            self.end_headers()
            return
        start, end = span
        length = end - start + 1
        partial = self.headers.get("Range") is not None
        self.send_response(HTTPStatus.PARTIAL_CONTENT if partial else HTTPStatus.OK)
        self.send_header("content-type", "video/mp4")
        self.send_header("accept-ranges", "bytes")
        self.send_header("content-length", str(length))
        if partial:
            self.send_header("content-range", f"bytes {start}-{end}/{size}")
        self.send_header("cache-control", "private, max-age=3600")
        self.end_headers()
        if not send_body:
            return
        try:
            with path.open("rb") as handle:
                handle.seek(start)
                remaining = length
                while remaining > 0:
                    chunk = handle.read(min(256 * 1024, remaining))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    remaining -= len(chunk)
        except (BrokenPipeError, ConnectionResetError):
            return

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

    def send_image(self, image: Any, extra_headers: dict[str, str] | None = None) -> None:
        assert Image is not None
        buffer = io.BytesIO()
        image.save(buffer, format="PNG")
        body = buffer.getvalue()
        self.send_response(HTTPStatus.OK)
        self.send_header("content-type", "image/png")
        self.send_header("content-length", str(len(body)))
        self.send_header("cache-control", "no-store")
        for key, value in (extra_headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format: str, *args: Any) -> None:  # noqa: A002
        print(f"[{datetime.now().strftime('%H:%M:%S')}] {self.address_string()} {format % args}")


def job_to_dict(job: Job) -> dict[str, Any]:
    return {
        "id": job.id,
        "kind": job.kind,
        "trip": job.trip,
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
      <div><label>播放倍率</label><select id="speed"><option>0.5</option><option selected>1</option><option>1.5</option><option>2</option><option>4</option><option>8</option><option>16</option></select></div>
      <div><label>输出 FPS</label><input id="fps" type="number" min="5" max="120" value="60"></div>
    </div>
  </div>
  <div class="card">
    <label><input id="route" type="checkbox" checked> 底部拼接轨迹图</label>
    <label><input id="stabilize" type="checkbox" checked> 启用视频防抖（Vision + Metal GPU 亚像素防抖）</label>
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
    fps: Number(document.getElementById('fps').value || 60),
    stabilize: document.getElementById('stabilize').checked,
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


def daemonize_process(log_path: Path) -> None:
    """通过双 fork 与 setsid 脱离控制终端，确保后台常驻服务不会随终端会话结束而被回收。"""
    if os.fork() > 0:
        os._exit(0)
    os.setsid()
    if os.fork() > 0:
        os._exit(0)
    sys.stdout.flush()
    sys.stderr.flush()
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with open(os.devnull, "rb", 0) as devnull_in, open(log_path, "ab", 0) as log_out:
        os.dup2(devnull_in.fileno(), sys.stdin.fileno())
        os.dup2(log_out.fileno(), sys.stdout.fileno())
        os.dup2(log_out.fileno(), sys.stderr.fileno())


def main() -> None:
    ensure_tool_path()
    try:
        signal.signal(signal.SIGHUP, signal.SIG_IGN)
    except Exception:  # noqa: BLE001
        pass
    parser = argparse.ArgumentParser(description="CarSpeed Studio macOS 本地服务")
    parser.add_argument("--host", default=DEFAULT_HOST)
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--workspace", default=str(Path(__file__).resolve().parent / "workspace"))
    parser.add_argument("--daemon", action="store_true", help="脱离控制终端常驻后台运行")
    parser.add_argument("--log-file", default="/tmp/carspeed_server.log", help="后台常驻日志路径")
    args = parser.parse_args()
    if args.daemon:
        daemonize_process(Path(args.log_file).expanduser().resolve())
    Handler.workspace = Path(args.workspace).expanduser().resolve()
    Handler.studio_path = Path(__file__).resolve().parent / "studio.html"
    Handler.workspace.mkdir(parents=True, exist_ok=True)
    ThreadingHTTPServer.allow_reuse_address = True
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"CarSpeed Studio 已启动：http://{args.host}:{args.port}")
    print(f"工作目录：{Handler.workspace}")
    server.serve_forever()


if __name__ == "__main__":
    main()
