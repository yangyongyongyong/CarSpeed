"""紧凑组件图集（Packed Widget Atlas）原生高速渲染引擎。

针对视频导出（compose_studio_overlay_video）的性能瓶颈深度优化：
1. 消除每秒冷启动 Chrome 进程（180s 视频原本启动 180 次无头浏览器）。
2. 消除全屏 1920x1080/3840x2160 空透明像素（85% 为 rgba(0,0,0,0)）的 PNG 压缩落盘、
   解压、管道传输与全屏 4K lanczos 放大。
3. 直接在内存中基于仓库内置的 Orbitron / ChakraPetch / PingFang 字体及高德地图内存 PIL.Image
   绘制已启用的小组件（左上 HUD、左下地图、右下码表及可选信息栏/独立字段），
   并拼装为一张紧凑的 RGBA 图集（Atlas），供 FFmpeg 通过 split + crop（零拷贝指针偏移）+
   局部区域 overlay 叠加到硬件解码的 4K 视频上。
"""
from __future__ import annotations

import dataclasses
import math
import re
from functools import lru_cache
from pathlib import Path
from typing import Any

from PIL import Image, ImageDraw, ImageEnhance, ImageFont

import overlay_render
import overlay_widgets

FONT_DIR = overlay_render.FONT_DIR

CJK_FONT_CANDIDATES = (
    "/System/Library/Fonts/PingFang.ttc",
    "/System/Library/Fonts/Hiragino Sans GB.ttc",
    "/System/Library/Fonts/STHeiti Medium.ttc",
    "/System/Library/Fonts/STHeiti Light.ttc",
    "/Library/Fonts/Arial Unicode.ttf",
)


@dataclasses.dataclass(frozen=True)
class AtlasRegion:
    """紧凑图集中的单个局部覆盖层区域描述。"""

    name: str
    screen_x: int
    screen_y: int
    width: int
    height: int
    atlas_x: int
    atlas_y: int


@dataclasses.dataclass(frozen=True)
class AtlasPlan:
    """紧凑图集整体布局与 FFmpeg 局部叠加计划。"""

    canvas_width: int
    canvas_height: int
    atlas_width: int
    atlas_height: int
    regions: tuple[AtlasRegion, ...]


def _even(value: int, minimum: int = 2) -> int:
    """将整数向上规整为不小于 minimum 的偶数，满足 FFmpeg 滤镜对齐要求。"""
    num = max(minimum, int(value))
    return num if num % 2 == 0 else num + 1


def _clamp_box(
    x: int,
    y: int,
    w: int,
    h: int,
    canvas_w: int,
    canvas_h: int,
) -> tuple[int, int, int, int]:
    """将小组件屏幕包围盒钳位在画布范围内，并保证宽高为偶数。"""
    w = min(_even(w, 16), _even(canvas_w, 16))
    h = min(_even(h, 16), _even(canvas_h, 16))
    sx = max(0, min(canvas_w - w, int(x)))
    sy = max(0, min(canvas_h - h, int(y)))
    sx -= sx % 2
    sy -= sy % 2
    return sx, sy, w, h


@lru_cache(maxsize=128)
def load_latin_font(filename: str, size: int) -> ImageFont.FreeTypeFont:
    """加载仓库自带的 Orbitron / ChakraPetch TTF 字体并缓存。"""
    px = max(8, int(round(size)))
    path = FONT_DIR / filename
    if path.is_file():
        try:
            return ImageFont.truetype(str(path), px)
        except Exception:  # noqa: BLE001
            pass
    return load_cjk_font(px)


@lru_cache(maxsize=128)
def load_cjk_font(size: int) -> ImageFont.FreeTypeFont:
    """加载 macOS 系统中文字体（PingFang 等）并缓存。"""
    px = max(8, int(round(size)))
    for candidate in CJK_FONT_CANDIDATES:
        if Path(candidate).exists():
            try:
                return ImageFont.truetype(candidate, px)
            except Exception:  # noqa: BLE001
                continue
    return ImageFont.load_default()  # type: ignore[return-value]


def _hex_rgb(value: str | None, fallback: tuple[int, int, int] = (255, 94, 0)) -> tuple[int, int, int]:
    """将十六进制颜色字符串解析为 RGB 三元组。"""
    text = (value or "").strip().lstrip("#")
    if len(text) == 3:
        text = "".join(ch * 2 for ch in text)
    if len(text) != 6:
        return fallback
    try:
        return (int(text[0:2], 16), int(text[2:4], 16), int(text[4:6], 16))
    except ValueError:
        return fallback


def _rgba(rgb: tuple[int, int, int], alpha: float) -> tuple[int, int, int, int]:
    """组合 RGB 与 0..255 的透明度通道。"""
    return (rgb[0], rgb[1], rgb[2], max(0, min(255, int(round(alpha)))))


def _draw_skewed_text(
    canvas: Image.Image,
    xy: tuple[float, float],
    text: str,
    font: Any,
    fill: tuple[int, int, int, int],
    *,
    skew_deg: float = -10.0,
    align_center: bool = False,
) -> tuple[int, int]:
    """使用仿射变换绘制高抗锯齿斜体/斜切文字，返回文字块宽高。"""
    if not text:
        return (0, 0)
    dummy = ImageDraw.Draw(Image.new("RGBA", (1, 1)))
    bbox = dummy.textbbox((0, 0), text, font=font)
    w = max(1, bbox[2] - bbox[0])
    h = max(1, bbox[3] - bbox[1])
    k = math.tan(math.radians(abs(skew_deg)))
    pad_x = int(math.ceil(h * k)) + 6
    pad_y = 6
    layer_w = w + pad_x * 2
    layer_h = h + pad_y * 2
    layer = Image.new("RGBA", (layer_w, layer_h), (0, 0, 0, 0))
    ImageDraw.Draw(layer).text((pad_x - bbox[0], pad_y - bbox[1]), text, font=font, fill=fill)
    if abs(skew_deg) > 0.1:
        shear = math.tan(math.radians(-skew_deg))
        # 以文字垂直中心为轴做水平斜切 x_in = x_out - shear * (y - cy)
        cy = layer_h / 2.0
        layer = layer.transform(
            (layer_w, layer_h),
            Image.AFFINE,
            (1.0, -shear, shear * cy, 0.0, 1.0, 0.0),
            resample=Image.BICUBIC,
        )
    dest_x = int(round(xy[0] - (layer_w / 2.0 if align_center else pad_x)))
    dest_y = int(round(xy[1] - (layer_h / 2.0 if align_center else pad_y)))
    canvas.alpha_composite(layer, (dest_x, dest_y))
    return (w, h)


def build_atlas_plan(layout: Any, canvas_width: int, canvas_height: int) -> AtlasPlan:
    """根据当前 Layout 和目标视频分辨率计算紧凑图集（Atlas）的区域划分。

    - float 悬浮窗模式：只将已启用的组件放入 Atlas，完全剔除中间 85% 的空白透明区域；
    - split 上视频下地图模式：上方视频区仅保留速度码表 gauge，下方铺满高德地图区域，隐藏 HUD、白底信息栏与独立文字字段。
    """
    regions_raw: list[tuple[str, int, int, int, int]] = []
    layout_mode = str(getattr(layout, "layout_mode", "float") or "float")
    if layout_mode == "split":
        split_pct = max(25, min(65, int(getattr(layout, "split_video_percent", 38) or 38)))
        video_h = _even(int(round(canvas_height * split_pct / 100.0)), 16)
        video_h = max(160, min(canvas_height - 160, video_h))
        map_h = _even(canvas_height - video_h, 16)

        gauge = getattr(layout, "gauge", None)
        if gauge is not None and getattr(gauge, "enabled", True):
            size, ui = overlay_widgets.gauge_box_px(
                float(getattr(gauge, "radius_percent", 0.16) or 0.16),
                canvas_width,
                canvas_height,
            )
            pad = int(round(18 * ui))
            style = str(getattr(gauge, "style", "bar") or "bar")
            if style == "gt":
                gw = int(round(260 * ui)) + pad * 2
                gh = int(round(160 * ui)) + pad * 2
            elif style == "aero":
                gw = int(round(280 * ui)) + pad * 2
                gh = int(round(170 * ui)) + pad * 2
            else:
                gw = size + pad * 2
                gh = size + pad * 2
            cx = int(round(float(getattr(gauge, "x", 0.82)) * canvas_width))
            raw_gy = float(getattr(gauge, "y", 0.26))
            # 若 gauge.y 仍在下半屏，自动钳位到上方视频区域内右下角
            if raw_gy * canvas_height > video_h - gh // 2:
                cy = max(gh // 2, video_h - gh // 2 - int(round(12 * ui)))
            else:
                cy = int(round(raw_gy * canvas_height))
            sx, sy, gw, gh = _clamp_box(cx - gw // 2, cy - gh // 2, gw, gh, canvas_width, video_h)
            regions_raw.append(("gauge", sx, sy, gw, gh))

        map_cfg = getattr(layout, "map_layer", None)
        if map_cfg is not None and getattr(map_cfg, "enabled", True):
            sx, sy, mw, mh = _clamp_box(0, video_h, canvas_width, map_h, canvas_width, canvas_height)
            regions_raw.append(("map", sx, sy, mw, mh))

        regions: list[AtlasRegion] = []
        cursor_x = 0
        max_h = 2
        for name, sx, sy, w, h in regions_raw:
            regions.append(
                AtlasRegion(
                    name=name,
                    screen_x=sx,
                    screen_y=sy,
                    width=w,
                    height=h,
                    atlas_x=cursor_x,
                    atlas_y=0,
                )
            )
            cursor_x += w
            max_h = max(max_h, h)
        return AtlasPlan(
            canvas_width=canvas_width,
            canvas_height=canvas_height,
            atlas_width=_even(max(16, cursor_x), 16),
            atlas_height=_even(max(16, max_h), 16),
            regions=tuple(regions),
        )

    # 1. 左上角赛事计时栏 HUD
    hud = getattr(layout, "hud", None)
    if hud is not None and getattr(hud, "enabled", True):
        ui = overlay_widgets.timing_ui(int(getattr(hud, "time_size", 13) or 13), canvas_height, canvas_width)
        pad = int(round(16 * ui))
        w = int(round(396 * ui)) + pad * 2
        h = int(round(104 * ui)) + pad * 2
        sx = int(round(float(getattr(hud, "x", 0.026)) * canvas_width)) - pad
        sy = int(round(float(getattr(hud, "y", 0.037)) * canvas_height)) - pad
        sx, sy, w, h = _clamp_box(sx, sy, w, h, canvas_width, canvas_height)
        regions_raw.append(("hud", sx, sy, w, h))

    # 2. 左下角高德地图卡片
    map_cfg = getattr(layout, "map_layer", None)
    if map_cfg is not None and getattr(map_cfg, "enabled", True):
        map_w, map_h, ui = overlay_widgets.map_card_size(
            float(getattr(map_cfg, "size_percent", 0.22) or 0.22),
            canvas_width,
            canvas_height,
        )
        badge_h = int(round(32 * ui))
        pad = int(round(14 * ui))
        total_w = int(round(map_w)) + pad * 2
        total_h = int(round(map_h)) + badge_h + pad * 2
        cx = int(round(float(getattr(map_cfg, "x", 0.15)) * canvas_width))
        cy = int(round(float(getattr(map_cfg, "y", 0.72)) * canvas_height))
        sx = cx - total_w // 2
        sy = cy - total_h // 2
        sx, sy, total_w, total_h = _clamp_box(sx, sy, total_w, total_h, canvas_width, canvas_height)
        regions_raw.append(("map", sx, sy, total_w, total_h))

    # 3. 右下角速度码表
    gauge = getattr(layout, "gauge", None)
    if gauge is not None and getattr(gauge, "enabled", True):
        size, ui = overlay_widgets.gauge_box_px(
            float(getattr(gauge, "radius_percent", 0.16) or 0.16),
            canvas_width,
            canvas_height,
        )
        pad = int(round(18 * ui))
        style = str(getattr(gauge, "style", "bar") or "bar")
        if style == "gt":
            gw = int(round(260 * ui)) + pad * 2
            gh = int(round(160 * ui)) + pad * 2
        elif style == "aero":
            gw = int(round(280 * ui)) + pad * 2
            gh = int(round(170 * ui)) + pad * 2
        else:
            gw = size + pad * 2
            gh = size + pad * 2
        cx = int(round(float(getattr(gauge, "x", 0.88)) * canvas_width))
        cy = int(round(float(getattr(gauge, "y", 0.76)) * canvas_height))
        sx = cx - gw // 2
        sy = cy - gh // 2
        sx, sy, gw, gh = _clamp_box(sx, sy, gw, gh, canvas_width, canvas_height)
        regions_raw.append(("gauge", sx, sy, gw, gh))

    # 4. 底部信息栏（当启用时）
    info_bar = getattr(layout, "info_bar", None)
    bar_active = bool(info_bar is not None and getattr(info_bar, "enabled", False))
    bar_fields = set(getattr(info_bar, "fields", []) or []) if bar_active else set()
    if bar_active:
        text_size = max(10, int(getattr(info_bar, "text_size", 30) or 30))
        padding = max(4, int(getattr(info_bar, "padding", 14) or 14))
        ui_scale = max(0.5, min(canvas_width, canvas_height) / 1080.0)
        bar_h = _even(int(round((text_size + padding * 2 + 10) * ui_scale)), 24)
        sx, sy, bw, bh = _clamp_box(0, canvas_height - bar_h, canvas_width, bar_h, canvas_width, canvas_height)
        regions_raw.append(("info_bar", sx, sy, bw, bh))

    # 5. 独立文本字段（当用户开启任意独立字段且未被 info_bar 接管时）
    ui_scale = max(0.5, min(canvas_width, canvas_height) / 1080.0)
    for idx, item in enumerate(getattr(layout, "fields", []) or []):
        if not getattr(item, "enabled", False):
            continue
        if bar_active and getattr(item, "key", "") in bar_fields:
            continue
        f_size = max(10, int(getattr(item, "size", 32) or 32))
        fw = int(round(max(240, f_size * 13) * ui_scale))
        fh = int(round((f_size + int(getattr(item, "padding", 12) or 12) * 2 + 16) * ui_scale))
        cx = int(round(float(getattr(item, "x", 0.5)) * canvas_width))
        cy = int(round(float(getattr(item, "y", 0.85)) * canvas_height))
        sx, sy, fw, fh = _clamp_box(cx - fw // 2, cy - fh // 2, fw, fh, canvas_width, canvas_height)
        regions_raw.append((f"field:{idx}", sx, sy, fw, fh))

    # 横向紧凑排布所有启用区域
    regions: list[AtlasRegion] = []
    cursor_x = 0
    max_h = 2
    for name, sx, sy, w, h in regions_raw:
        regions.append(
            AtlasRegion(
                name=name,
                screen_x=sx,
                screen_y=sy,
                width=w,
                height=h,
                atlas_x=cursor_x,
                atlas_y=0,
            )
        )
        cursor_x += w
        max_h = max(max_h, h)

    atlas_w = _even(max(16, cursor_x), 16)
    atlas_h = _even(max(16, max_h), 16)
    return AtlasPlan(
        canvas_width=canvas_width,
        canvas_height=canvas_height,
        atlas_width=atlas_w,
        atlas_height=atlas_h,
        regions=tuple(regions),
    )


def build_ffmpeg_atlas_filter(
    plan: AtlasPlan,
    *,
    speed: float,
    fps: int,
    source_width: int,
    source_height: int,
    target_width: int,
    target_height: int,
    stabilize: bool = False,
    stabilize_crop_percent: int = 6,
) -> str:
    """构建将紧凑图集按局部区域裁剪并叠加到主视频流的 FFmpeg filter_complex 字符串。"""
    base_filters: list[str] = []
    if abs(speed - 1.0) > 1e-4:
        base_filters.append(f"setpts=(PTS-STARTPTS)/{speed:.4f}")
    else:
        base_filters.append("setpts=PTS-STARTPTS")
    base_filters.append(f"fps={fps}")
    if stabilize:
        rx = min(64, max(16, int(round(source_width * max(3, min(15, stabilize_crop_percent)) / 200.0 / 2.0)) * 2))
        ry = min(64, max(16, int(round(source_height * max(3, min(15, stabilize_crop_percent)) / 200.0 / 2.0)) * 2))
        base_filters.append(f"deshake=rx={rx}:ry={ry}:edge=mirror")
    if source_width != target_width or source_height != target_height:
        base_filters.append(f"scale={target_width}:{target_height}")

    if not plan.regions:
        base_filters.append("format=yuv420p")
        return f"[0:v]{','.join(base_filters)}[out]"

    parts: list[str] = [f"[0:v]{','.join(base_filters)}[base0]"]
    count = len(plan.regions)
    if count == 1:
        parts.append("[1:v]null[atlas0]")
    else:
        split_outs = "".join(f"[atlas{idx}]" for idx in range(count))
        parts.append(f"[1:v]split={count}{split_outs}")

    for idx, region in enumerate(plan.regions):
        parts.append(
            f"[atlas{idx}]crop={region.width}:{region.height}:{region.atlas_x}:{region.atlas_y}[ov{idx}]"
        )
        prev_label = f"base{idx}"
        next_label = "out_raw" if idx == count - 1 else f"base{idx + 1}"
        parts.append(
            f"[{prev_label}][ov{idx}]overlay=x={region.screen_x}:y={region.screen_y}:eof_action=repeat:shortest=1[{next_label}]"
        )
    parts.append("[out_raw]format=yuv420p[out]")
    return ";".join(parts)


class NativeStudioAtlasRenderer:
    """Studio 覆盖层紧凑图集原生高速渲染器（带静态底板缓存与整公里速度表缓存）。"""

    def __init__(self, layout: Any, canvas_width: int, canvas_height: int) -> None:
        """初始化图集渲染器及组件级缓存。"""
        self.layout = layout
        self.canvas_width = canvas_width
        self.canvas_height = canvas_height
        self.plan = build_atlas_plan(layout, canvas_width, canvas_height)
        self._region_map = {region.name: region for region in self.plan.regions}
        self._hud_base: Image.Image | None = None
        self._gauge_cache: dict[int, Image.Image] = {}
        self._marker_cache: dict[tuple[int, int, str], Image.Image] = {}
        self._radar_cache: dict[tuple[int, int, bool], Image.Image] = {}
        self._map_mask_cache: dict[tuple[int, int, int], Image.Image] = {}
        # 卫星底图压暗已在单块瓦片解码时仅作用于 style=6 卫星底图，此处保持 1:1 亮度以免把上层白字地名压灰
        self._sat_r_lut = list(range(256))
        self._sat_g_lut = list(range(256))
        self._sat_b_lut = list(range(256))
        self._road_r_lut = [max(0, min(255, int(round(i * 0.937 + 8 * 0.063)))) for i in range(256)]
        self._road_g_lut = [max(0, min(255, int(round(i * 0.937 + 12 * 0.063)))) for i in range(256)]
        self._road_b_lut = [max(0, min(255, int(round(i * 0.937 + 18 * 0.063)))) for i in range(256)]

    def _get_hud_base(self, region: AtlasRegion) -> Image.Image:
        """生成并缓存左上角 HUD 的静态毛玻璃底板、左侧亮色条、REC 徽章与标签文字。"""
        if self._hud_base is not None:
            return self._hud_base
        hud = self.layout.hud
        ui = overlay_widgets.timing_ui(int(getattr(hud, "time_size", 13) or 13), self.canvas_height, self.canvas_width)
        pad = int(round(16 * ui))
        panel_w = region.width - pad * 2
        panel_h = region.height - pad * 2

        base = Image.new("RGBA", (region.width, region.height), (0, 0, 0, 0))
        draw = ImageDraw.Draw(base)
        accent_rgb = _hex_rgb(getattr(hud, "progress_color", None) or getattr(self.layout.gauge, "arc_color", "#ff5e00"))
        opacity = max(0.0, min(1.0, float(getattr(hud, "opacity", 100) or 100) / 100.0))

        if getattr(hud, "background", True):
            radius = max(4, int(round(10 * ui)))
            draw.rounded_rectangle(
                (pad, pad, pad + panel_w - 1, pad + panel_h - 1),
                radius=radius,
                fill=_rgba((13, 17, 23), 188 * opacity),
                outline=_rgba((255, 255, 255), 42 * opacity),
                width=max(1, int(round(1 * ui))),
            )
            bar_w = max(3, int(round(4.5 * ui)))
            draw.rounded_rectangle(
                (pad, pad, pad + bar_w, pad + panel_h - 1),
                radius=2,
                fill=_rgba(accent_rgb, 255 * opacity),
            )

        # REC 红点 + REC 字样
        in_x = pad + int(round(18 * ui))
        in_y = pad + int(round(12 * ui))
        dot_r = max(3, int(round(3.8 * ui)))
        dot_cy = in_y + int(round(8 * ui))
        draw.ellipse(
            (in_x, dot_cy - dot_r, in_x + dot_r * 2, dot_cy + dot_r),
            fill=_rgba((255, 51, 75), 255 * opacity),
        )
        rec_font = load_latin_font("ChakraPetch-Bold.ttf", int(round(12 * ui)))
        draw.text(
            (in_x + dot_r * 2 + int(round(6 * ui)), in_y),
            "REC",
            font=rec_font,
            fill=_rgba((255, 51, 75), 255 * opacity),
        )

        # 进度条底槽
        if getattr(hud, "progress_bar", True):
            track_y = pad + int(round(38 * ui))
            track_h = max(3, int(round(4.5 * ui)))
            track_left = in_x
            track_right = pad + panel_w - int(round(18 * ui))
            draw.rounded_rectangle(
                (track_left, track_y, track_right, track_y + track_h),
                radius=max(1, track_h // 2),
                fill=_rgba((255, 255, 255), 36 * opacity),
            )

        self._hud_base = base
        return base

    def render_hud_tile(
        self,
        region: AtlasRegion,
        values: dict[str, str],
        telemetry: dict[str, str],
        progress_ratio: float,
    ) -> Image.Image:
        """在缓存底板上快速绘制当秒时间戳、进度条和底部三组遥测数值。"""
        hud = self.layout.hud
        ui = overlay_widgets.timing_ui(int(getattr(hud, "time_size", 13) or 13), self.canvas_height, self.canvas_width)
        pad = int(round(16 * ui))
        panel_w = region.width - pad * 2
        in_x = pad + int(round(18 * ui))
        in_y = pad + int(round(12 * ui))
        right_x = pad + panel_w - int(round(18 * ui))
        opacity = max(0.0, min(1.0, float(getattr(hud, "opacity", 100) or 100) / 100.0))
        accent_rgb = _hex_rgb(getattr(hud, "progress_color", None) or getattr(self.layout.gauge, "arc_color", "#ff5e00"))

        tile = self._get_hud_base(region).copy()
        draw = ImageDraw.Draw(tile)

        # 顶栏右侧时间戳
        use_24h = bool(getattr(hud, "time_format_24h", True))
        show_date = bool(getattr(hud, "show_date", False))
        time_text = overlay_widgets._format_hud_timestamp(
            values.get("time") or "", use_24h=use_24h, show_date=show_date
        )
        ts_font = load_latin_font("ChakraPetch-MediumItalic.ttf", int(round(13.5 * ui)))
        ts_box = draw.textbbox((0, 0), time_text, font=ts_font)
        ts_w = ts_box[2] - ts_box[0]
        draw.text(
            (right_x - ts_w, in_y - ts_box[1]),
            time_text,
            font=ts_font,
            fill=_rgba((203, 213, 225), 255 * opacity),
        )

        # 顶栏中部 GPS 卫星颗数徽章
        sat_num = overlay_widgets._extract_satellite_count(values.get("satellites") or "")
        if sat_num:
            sat_font = load_latin_font("ChakraPetch-Bold.ttf", int(round(11.5 * ui)))
            sat_text = f"GPS {sat_num}"
            sb = draw.textbbox((0, 0), sat_text, font=sat_font)
            sw = sb[2] - sb[0] + int(round(12 * ui))
            sh = sb[3] - sb[1] + int(round(4 * ui))
            sx0 = in_x + int(round(52 * ui))
            sy0 = in_y - int(round(1 * ui))
            draw.rounded_rectangle(
                (sx0, sy0, sx0 + sw, sy0 + sh),
                radius=max(2, int(round(4 * ui))),
                fill=_rgba((0, 242, 255), 32 * opacity),
                outline=_rgba((0, 242, 255), 76 * opacity),
            )
            draw.text(
                (sx0 + int(round(6 * ui)) - sb[0], sy0 + int(round(2 * ui)) - sb[1]),
                sat_text,
                font=sat_font,
                fill=_rgba((0, 242, 255), 245 * opacity),
            )

        # 进度条填充
        if getattr(hud, "progress_bar", True):
            track_y = pad + int(round(38 * ui))
            track_h = max(3, int(round(4.5 * ui)))
            track_w = max(1, right_x - in_x)
            fill_w = int(round(track_w * max(0.0, min(1.0, progress_ratio))))
            if fill_w > 0:
                draw.rounded_rectangle(
                    (in_x, track_y, in_x + fill_w, track_y + track_h),
                    radius=max(1, track_h // 2),
                    fill=_rgba(accent_rgb, 255 * opacity),
                )

        # 底栏三组遥测：耗时 / 行程 / 进度
        row_y = pad + int(round(54 * ui))
        lbl_font = load_cjk_font(int(round(12.5 * ui)))
        val_font = load_latin_font("ChakraPetch-Bold.ttf", int(round(13 * ui)))
        items: list[tuple[str, str, tuple[int, int, int]]] = []
        if getattr(hud, "show_duration", True):
            items.append(("耗时 ", telemetry.get("duration", "00:00:00"), (255, 255, 255)))
        if getattr(hud, "show_distance", True):
            items.append(("行程 ", telemetry.get("distance", "—"), (255, 255, 255)))
        items.append(("进度 ", f"{max(0.0, min(1.0, progress_ratio)) * 100:.1f}%", accent_rgb))

        measured: list[tuple[str, str, int, int, tuple[int, int, int]]] = []
        for lbl, val, col in items:
            lb = draw.textbbox((0, 0), lbl, font=lbl_font)
            vb = draw.textbbox((0, 0), val, font=val_font)
            measured.append((lbl, val, lb[2] - lb[0], vb[2] - vb[0], col))
        total_text_w = sum(lw + vw for _, _, lw, vw, _ in measured)
        avail_w = max(0, (right_x - in_x) - total_text_w)
        gap_w = avail_w / max(1, len(measured) - 1) if len(measured) > 1 else 0.0

        cx = float(in_x)
        for lbl, val, lw, vw, col in measured:
            draw.text((int(round(cx)), row_y), lbl, font=lbl_font, fill=_rgba((148, 163, 184), 255 * opacity))
            draw.text((int(round(cx + lw)), row_y), val, font=val_font, fill=_rgba(col, 255 * opacity))
            cx += lw + vw + gap_w
        return tile

    def render_gauge_tile(self, region: AtlasRegion, speed_kmh: float) -> Image.Image:
        """按整公里车速缓存并绘制右下角码表（Forza / Aero / GT / Circular，纯实心锐利字体无边缘渐变）。"""
        speed_int = max(0, int(round(speed_kmh)))
        cached = self._gauge_cache.get(speed_int)
        if cached is not None:
            return cached

        gauge = self.layout.gauge
        size, ui = overlay_widgets.gauge_box_px(
            float(getattr(gauge, "radius_percent", 0.16) or 0.16),
            self.canvas_width,
            self.canvas_height,
        )
        max_speed = max(1, int(getattr(gauge, "max_speed", 300) or 300))
        ratio = max(0.0, min(1.0, speed_kmh / float(max_speed)))
        opacity = max(0.0, min(1.0, float(getattr(gauge, "opacity", 100) or 100) / 100.0))
        accent_rgb = _hex_rgb(getattr(gauge, "arc_color", "#ff5e00"), (255, 94, 0))
        val_hex = overlay_widgets._resolve_gauge_value_color(getattr(gauge, "value_color", None))
        val_rgb = _hex_rgb(val_hex, (0, 242, 255))
        unit_rgb = _hex_rgb(getattr(gauge, "unit_color", "#cbd5e1"), (203, 213, 225))
        style = str(getattr(gauge, "style", "bar") or "bar")

        tile = Image.new("RGBA", (region.width, region.height), (0, 0, 0, 0))
        cx = region.width // 2
        cy = region.height // 2
        number_str = f"{speed_int}"

        if style == "gt":
            draw = ImageDraw.Draw(tile)
            pw = int(round(240 * ui))
            ph = int(round(138 * ui))
            left = cx - pw // 2
            top = cy - ph // 2
            draw.rounded_rectangle(
                (left, top, left + pw, top + ph),
                radius=max(6, int(round(14 * ui))),
                fill=_rgba((13, 17, 23), 195 * opacity),
                outline=_rgba((255, 255, 255), 42 * opacity),
                width=max(1, int(round(1 * ui))),
            )
            hdr_font = load_latin_font("ChakraPetch-Bold.ttf", int(round(11.5 * ui)))
            draw.text((left + int(round(20 * ui)), top + int(round(14 * ui))), "TELEMETRY", font=hdr_font, fill=_rgba((100, 116, 139), 255 * opacity))
            pct_str = f"{ratio * 100:.0f}%"
            pb = draw.textbbox((0, 0), pct_str, font=hdr_font)
            draw.text((left + pw - int(round(20 * ui)) - (pb[2] - pb[0]), top + int(round(14 * ui))), pct_str, font=hdr_font, fill=_rgba(accent_rgb, 255 * opacity))

            num_font = load_latin_font("Orbitron-Black.ttf", int(round(60 * ui)))
            _draw_skewed_text(
                tile,
                (left + int(round(20 * ui)), top + int(round(34 * ui))),
                number_str,
                num_font,
                _rgba(val_rgb, 255 * opacity),
                skew_deg=-10.0,
            )
            filled = int(round(ratio * overlay_widgets.GT_BLOCK_COUNT))
            bw = max(4, int(round(9 * ui)))
            bh = max(6, int(round(14 * ui)))
            bgap = max(2, int(round(4 * ui)))
            bx0 = left + int(round(20 * ui))
            by0 = top + ph - int(round(16 * ui)) - bh
            for idx in range(overlay_widgets.GT_BLOCK_COUNT):
                bx = bx0 + idx * (bw + bgap)
                col = _rgba(accent_rgb, 255 * opacity) if idx < filled else _rgba((255, 255, 255), 32 * opacity)
                draw.rectangle((bx, by0, bx + bw, by0 + bh), fill=col)

        elif style == "aero":
            draw = ImageDraw.Draw(tile)
            active = int(round(ratio * overlay_widgets.AERO_TICK_COUNT))
            th = max(2, int(round(3.5 * ui)))
            tgap = max(3, int(round(6 * ui)))
            lx = cx - int(round(95 * ui))
            ly_bottom = cy + int(round(48 * ui))
            for idx in range(overlay_widgets.AERO_TICK_COUNT):
                ty = ly_bottom - (idx + 1) * (th + tgap)
                is_act = idx < active
                tw = int(round((28 if is_act else 14) * ui))
                col = _rgba(accent_rgb, 255 * opacity) if is_act else _rgba((255, 255, 255), 55 * opacity)
                draw.rectangle((lx, ty, lx + tw, ty + th), fill=col)
            num_font = load_latin_font("Orbitron-Black.ttf", int(round(78 * ui)))
            _draw_skewed_text(
                tile,
                (cx + int(round(18 * ui)), cy - int(round(12 * ui))),
                number_str,
                num_font,
                _rgba(val_rgb, 255 * opacity),
                skew_deg=-12.0,
                align_center=True,
            )
            unit_font = load_latin_font("ChakraPetch-Bold.ttf", int(round(14 * ui)))
            _draw_skewed_text(
                tile,
                (cx + int(round(18 * ui)), cy + int(round(42 * ui))),
                "KM/H HUD",
                unit_font,
                _rgba(accent_rgb, 255 * opacity),
                skew_deg=-12.0,
                align_center=True,
            )

        else:
            # Forza ('bar') 或 'circular'：使用 2x 超采样绘制平滑圆弧与圆头端点
            ss = 2
            arc_layer = Image.new("RGBA", (region.width * ss, region.height * ss), (0, 0, 0, 0))
            arc_draw = ImageDraw.Draw(arc_layer)
            scx = cx * ss
            scy = cy * ss
            if style == "circular":
                r_px = (size / 2.0) * (60.0 / 80.0) * ss
                stroke_w = max(4, int(round(size * (10.0 / 160.0) * ss)))
                start_deg = -90.0
                sweep_deg = 360.0
            else:
                # Forza viewBox 180x180, r=75, stroke=10, rotate(135deg), 270deg 弧
                r_px = (size / 2.0) * (75.0 / 90.0) * ss
                stroke_w = max(4, int(round(size * (10.0 / 180.0) * ss)))
                start_deg = 135.0
                sweep_deg = 270.0

            box = (scx - r_px, scy - r_px, scx + r_px, scy + r_px)
            bg_col = _rgba((15, 23, 42), 174 * opacity)
            arc_draw.arc(box, start=start_deg, end=start_deg + sweep_deg, fill=bg_col, width=stroke_w)
            # 轨道两端圆头
            cap_r = stroke_w / 2.0
            for ang in (start_deg, start_deg + sweep_deg):
                rad = math.radians(ang)
                px = scx + (r_px - cap_r) * math.cos(rad)
                py = scy + (r_px - cap_r) * math.sin(rad)
                arc_draw.ellipse((px - cap_r, py - cap_r, px + cap_r, py + cap_r), fill=bg_col)

            if ratio > 0.002:
                end_deg = start_deg + sweep_deg * ratio
                fg_col = _rgba(accent_rgb, 255 * opacity)
                arc_draw.arc(box, start=start_deg, end=end_deg, fill=fg_col, width=stroke_w)
                for ang in (start_deg, end_deg):
                    rad = math.radians(ang)
                    px = scx + (r_px - cap_r) * math.cos(rad)
                    py = scy + (r_px - cap_r) * math.sin(rad)
                    arc_draw.ellipse((px - cap_r, py - cap_r, px + cap_r, py + cap_r), fill=fg_col)

            tile.alpha_composite(arc_layer.resize((region.width, region.height), Image.LANCZOS))

            # 中心純实心斜体数字（Orbitron-Black，无边缘模糊阴影）
            num_font = load_latin_font("Orbitron-Black.ttf", int(round((64 if style == "circular" else 76) * ui)))
            _draw_skewed_text(
                tile,
                (cx, cy - int(round(16 * ui))),
                number_str,
                num_font,
                _rgba(val_rgb, 255 * opacity),
                skew_deg=-10.0 if style != "circular" else 0.0,
                align_center=True,
            )

            # 单位 KM/H
            unit_font = load_latin_font("ChakraPetch-Bold.ttf", int(round(14 * ui)))
            _draw_skewed_text(
                tile,
                (cx, cy + int(round(28 * ui))),
                "KM/H",
                unit_font,
                _rgba(unit_rgb, 255 * opacity),
                skew_deg=-10.0 if style != "circular" else 0.0,
                align_center=True,
            )

            # Forza 底部 MAX 胶囊
            if style != "circular":
                draw = ImageDraw.Draw(tile)
                pill_text = f"MAX {max_speed}"
                pill_font = load_latin_font("ChakraPetch-BoldItalic.ttf", int(round(13 * ui)))
                pb = draw.textbbox((0, 0), pill_text, font=pill_font)
                pw = (pb[2] - pb[0]) + int(round(24 * ui))
                ph = (pb[3] - pb[1]) + int(round(6 * ui))
                px0 = cx - pw // 2
                py0 = cy + int(round(46 * ui))
                draw.rounded_rectangle(
                    (px0, py0, px0 + pw, py0 + ph),
                    radius=ph // 2,
                    fill=_rgba((13, 17, 23), 235 * opacity),
                    outline=_rgba((255, 255, 255), 62 * opacity),
                    width=max(1, int(round(1 * ui))),
                )
                draw.text(
                    (cx - (pb[2] - pb[0]) // 2 - pb[0], py0 + (ph - (pb[3] - pb[1])) // 2 - pb[1]),
                    pill_text,
                    font=pill_font,
                    fill=_rgba(accent_rgb, 255 * opacity),
                )

        if len(self._gauge_cache) > 64:
            self._gauge_cache.clear()
        self._gauge_cache[speed_int] = tile
        return tile

    def _get_car_marker_sprite(self, ui: float, angle_deg: float, marker_color_hex: str) -> Image.Image:
        """生成并按整度数缓存带白色描边与青色光晕的立体导航车标。"""
        angle_int = int(round(angle_deg)) % 360
        size_px = max(24, int(round(28 * ui)))
        key = (size_px, angle_int, marker_color_hex)
        cached = self._marker_cache.get(key)
        if cached is not None:
            return cached

        ss = 2
        dim = size_px * ss
        sprite = Image.new("RGBA", (dim, dim), (0, 0, 0, 0))
        draw = ImageDraw.Draw(sprite)
        col_rgb = _hex_rgb(marker_color_hex, (0, 242, 255))
        scale = dim / 36.0

        # 外圈柔和光晕底圆
        r_circle = 14.0 * scale
        mid = dim / 2.0
        draw.ellipse((mid - r_circle, mid - r_circle, mid + r_circle, mid + r_circle), fill=_rgba(col_rgb, 65))

        # 导航箭头主多边形 M18 3 L30 30 L18 23.5 L6 30 Z
        outer_pts = [(18 * scale, 2.2 * scale), (31 * scale, 30.8 * scale), (18 * scale, 24.2 * scale), (5 * scale, 30.8 * scale)]
        inner_pts = [(18 * scale, 4.2 * scale), (28.8 * scale, 29.0 * scale), (18 * scale, 23.0 * scale), (7.2 * scale, 29.0 * scale)]
        left_hi = [(18 * scale, 5.6 * scale), (18 * scale, 23.0 * scale), (8.8 * scale, 28.2 * scale)]

        draw.polygon(outer_pts, fill=(255, 255, 255, 255))
        draw.polygon(inner_pts, fill=_rgba(col_rgb, 255))
        draw.polygon(left_hi, fill=(255, 255, 255, 90))

        rotated = sprite.rotate(-angle_int, resample=Image.BICUBIC, expand=False)
        final_sprite = rotated.resize((size_px, size_px), Image.LANCZOS)
        if len(self._marker_cache) > 90:
            self._marker_cache.clear()
        self._marker_cache[key] = final_sprite
        return final_sprite

    def _get_radar_badge_sprite(self, ui: float, speed_limit: int, compact: bool = False) -> Image.Image:
        """生成并缓存手机 App 同款测速雷达摄像头图标与底部限速数字胶囊（参照 ic_speed_radar.xml）。"""
        ui_bucket = max(6, int(round(ui * 10)))
        key = (ui_bucket, int(speed_limit or 0), bool(compact))
        cached = self._radar_cache.get(key)
        if cached is not None:
            return cached

        scale_ui = (ui_bucket / 10.0) * (0.76 if compact else 1.0)
        ss = 2
        cam_d = max(20, int(round(24 * scale_ui))) * ss
        pill_h = (max(12, int(round(13 * scale_ui))) * ss) if speed_limit > 0 else 0
        w = max(cam_d + 8 * ss, int(round(34 * scale_ui)) * ss)
        h = cam_d + pill_h + 4 * ss
        layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        draw = ImageDraw.Draw(layer)
        cx = w / 2.0
        cy = cam_d / 2.0 + 1 * ss
        r_out = cam_d * (13.0 / 28.0)
        r_in = cam_d * (11.0 / 28.0)
        # 深藏青外圈 #152033 + 纯白底圆 #FFFFFF
        draw.ellipse((cx - r_out, cy - r_out, cx + r_out, cy + r_out), fill=(21, 32, 51, 250))
        draw.ellipse((cx - r_in, cy - r_in, cx + r_in, cy + r_in), fill=(255, 255, 255, 255))
        # 摄像头机身 #152033 与蓝色镜头 #2E6BFF
        unit = cam_d / 28.0
        bx0 = cx + (5.5 - 14.0) * unit
        by0 = cy + (10.5 - 14.0) * unit
        bx1 = cx + (17.3 - 14.0) * unit
        by1 = cy + (17.5 - 14.0) * unit
        draw.rounded_rectangle((bx0, by0, bx1, by1), radius=max(2, int(round(2.0 * unit))), fill=(21, 32, 51, 255))
        lx = cx + (11.4 - 14.0) * unit
        ly = cy
        lr = 2.2 * unit
        draw.ellipse((lx - lr, ly - lr, lx + lr, ly + lr), fill=(46, 107, 255, 255))
        # 右侧橙红双雷达电波 #FF5A36
        for arc_r, stroke_u in ((3.6 * unit, 1.7 * unit), (5.8 * unit, 1.5 * unit)):
            ax = cx + (16.5 - 14.0) * unit
            draw.arc(
                (ax - arc_r, cy - arc_r, ax + arc_r, cy + arc_r),
                start=-42,
                end=42,
                fill=(255, 90, 54, 255),
                width=max(2, int(round(stroke_u))),
            )
        # 底部悬挂深藏青圆角限速胶囊
        if speed_limit > 0:
            spd_str = str(int(speed_limit))
            font = load_latin_font("Orbitron-Bold.ttf", max(10, int(round(8.2 * scale_ui * ss))))
            tb = draw.textbbox((0, 0), spd_str, font=font)
            tw = tb[2] - tb[0]
            th = tb[3] - tb[1]
            pw = max(tw + int(round(9 * scale_ui * ss)), int(round(18 * scale_ui * ss)))
            ph = th + int(round(4 * scale_ui * ss))
            px0 = int(round(cx - pw / 2.0))
            py0 = int(round(cy + r_out - 2.5 * scale_ui * ss))
            draw.rounded_rectangle(
                (px0, py0, px0 + pw, py0 + ph),
                radius=max(3, ph // 2),
                fill=(21, 32, 51, 245),
                outline=(255, 255, 255, 180),
                width=max(1, ss // 2),
            )
            draw.text(
                (px0 + (pw - tw) // 2 - tb[0], py0 + (ph - th) // 2 - tb[1]),
                spd_str,
                font=font,
                fill=(255, 255, 255, 255),
            )

        final_img = layer.resize((w // ss, h // ss), Image.LANCZOS)
        if len(self._radar_cache) > 32:
            self._radar_cache.clear()
        self._radar_cache[key] = final_img
        return final_img

    def _draw_pulsing_location_dot(
        self,
        card: Image.Image,
        px_x: int,
        px_y: int,
        ui: float,
        elapsed_ms: int,
    ) -> None:
        """绘制手机 App 同款三色脉冲闪动当前位置圆圈（300ms 切色 + 900ms 扩散环）。"""
        phase_idx = (max(0, int(elapsed_ms)) // 300) % 3
        palette = (
            (0, 229, 255),   # 青色
            (255, 193, 7),   # 琥珀黄
            (255, 64, 129),  # 玫红
        )
        core_rgb = palette[phase_idx]
        pulse_u = ((max(0, int(elapsed_ms)) % 900) / 900.0)
        ring_r = int(round((7.0 + 13.0 * pulse_u) * ui))
        ring_alpha = int(round(135 * (1.0 - pulse_u * 0.78)))
        core_r = max(4, int(round(5.8 * ui)))
        stroke_w = max(2, int(round(2.2 * ui)))

        pad = ring_r + stroke_w + 4
        dim = pad * 2
        overlay = Image.new("RGBA", (dim, dim), (0, 0, 0, 0))
        odraw = ImageDraw.Draw(overlay)
        odraw.ellipse(
            (pad - ring_r, pad - ring_r, pad + ring_r, pad + ring_r),
            fill=(*core_rgb, max(18, ring_alpha)),
        )
        odraw.ellipse(
            (pad - core_r - stroke_w, pad - core_r - stroke_w, pad + core_r + stroke_w, pad + core_r + stroke_w),
            fill=(255, 255, 255, 250),
        )
        odraw.ellipse(
            (pad - core_r, pad - core_r, pad + core_r, pad + core_r),
            fill=(*core_rgb, 255),
        )
        card.alpha_composite(overlay, (px_x - pad, px_y - pad))

    def _draw_max_speed_dot(
        self,
        card: Image.Image,
        px_x: int,
        px_y: int,
        ui: float,
        speed_kmh: float,
    ) -> None:
        """在整条轨迹最高速度所在位置绘制玫红实心圆 (#ff4081) + 纯白描边 + 峰值车速标签。"""
        r_core = max(4, int(round(5.2 * ui)))
        r_out = r_core + max(2, int(round(2.0 * ui)))
        draw = ImageDraw.Draw(card)
        draw.ellipse((px_x - r_out, px_y - r_out, px_x + r_out, px_y + r_out), fill=(255, 255, 255, 250))
        draw.ellipse((px_x - r_core, px_y - r_core, px_x + r_core, px_y + r_core), fill=(255, 64, 129, 255))
        spd_int = int(round(speed_kmh))
        if spd_int > 0:
            font = load_latin_font("Orbitron-Bold.ttf", max(8, int(round(8.0 * ui))))
            label = f"{spd_int}"
            tb = draw.textbbox((0, 0), label, font=font)
            tw = tb[2] - tb[0]
            th = tb[3] - tb[1]
            pw = tw + max(6, int(round(7 * ui)))
            ph = th + max(3, int(round(3 * ui)))
            bx = px_x - pw // 2
            by = px_y - r_out - ph - max(2, int(round(2 * ui)))
            draw.rounded_rectangle(
                (bx, by, bx + pw, by + ph),
                radius=max(3, ph // 2),
                fill=(255, 64, 129, 238),
                outline=(255, 255, 255, 210),
            )
            draw.text((bx + (pw - tw) // 2 - tb[0], by + (ph - th) // 2 - tb[1]), label, font=font, fill=(255, 255, 255, 255))

    def render_map_tile(
        self,
        region: AtlasRegion,
        points: list[Any],
        elapsed_ms: int,
        values: dict[str, str],
        amap_base_fn: Any,
        point_at_fn: Any,
        *,
        radars: list[Any] | None = None,
        telemetry: dict[str, str] | None = None,
        speed_kmh: float = 0.0,
        output_elapsed_sec: float | None = None,
    ) -> Image.Image:
        """直接在内存中绘制高德地图区域（支持悬浮窗 float 模式、上视频下地图 split 竖屏模式及 10s:50s 周期丝滑缩放）。"""
        map_cfg = self.layout.map_layer
        layout_mode = str(getattr(self.layout, "layout_mode", "float") or "float")
        is_split = layout_mode == "split"
        if is_split:
            card_w = max(96, int(region.width))
            card_h = max(96, int(region.height))
            ui = max(0.85, min(self.canvas_width, self.canvas_height) / 1080.0 * 1.25)
            card_left = 0
            card_top = 0
            opacity = 1.0
        else:
            map_w_f, map_h_f, ui = overlay_widgets.map_card_size(
                float(getattr(map_cfg, "size_percent", 0.22) or 0.22),
                self.canvas_width,
                self.canvas_height,
            )
            card_w = max(96, int(round(map_w_f)))
            card_h = max(64, int(round(map_h_f)))
            pad = int(round(14 * ui))
            badge_h = int(round(28 * ui))
            card_left = (region.width - card_w) // 2
            card_top = pad + badge_h
            opacity = max(0.0, min(1.0, float(getattr(map_cfg, "opacity", 100) or 100) / 100.0))

        style = str(getattr(map_cfg, "map_style", "") or "satellite")
        if style not in {"satellite", "road"}:
            style = "satellite"
        scale_meters = float(getattr(map_cfg, "scale_meters", 0) or 0)
        accent_rgb = _hex_rgb(getattr(self.layout.gauge, "arc_color", "#ff5e00"), (255, 94, 0))
        if is_split:
            route_rgb = (79, 214, 151)  # 手机 App 翠绿路线底边 #4fd697
            played_rgb = (255, 255, 255)  # 纯白已走内芯 #ffffff
        else:
            route_rgb = _hex_rgb(getattr(map_cfg, "route_color", None), accent_rgb)
            if getattr(map_cfg, "route_color", "").lower() == "#7fb7ff":
                route_rgb = accent_rgb
            played_rgb = _hex_rgb(getattr(map_cfg, "played_color", "#ffffff"), (255, 255, 255))
        raw_marker = str(getattr(map_cfg, "marker_color", "") or "#00f2ff").strip()
        if raw_marker.lower() in {"#000000", "#000", "#1d2a24", "#0b1410", "#ffb020"}:
            raw_marker = "#00f2ff"

        # 计算 10s 全程轨迹图 : 50s 局部当前位置图的周期丝滑融合因子
        cycle_enabled = bool(getattr(map_cfg, "cycle_overview_enabled", True))
        ov_sec = float(getattr(map_cfg, "overview_seconds", 10) or 10)
        dt_sec = float(getattr(map_cfg, "detail_seconds", 50) or 50)
        playback_spd = max(0.25, float(getattr(self.layout, "playback_speed", 1.0) or 1.0))
        out_sec = float(output_elapsed_sec) if output_elapsed_sec is not None else (max(0, elapsed_ms) / 1000.0 / playback_spd)
        overview_blend = (
            overlay_widgets.cycle_overview_blend_factor(out_sec, ov_sec, dt_sec)
            if (cycle_enabled and scale_meters > 0)
            else (1.0 if scale_meters <= 0 else 0.0)
        )
        card_aspect = float(card_w) / max(1.0, float(card_h))

        tile = Image.new("RGBA", (region.width, region.height), (0, 0, 0, 0))

        # 1. 卡片容器与高德底图（直接复用内存 PIL.Image，0 次 JPEG/Base64 往返）
        card_alpha = 255 if is_split else max(0, min(255, int(round(225 * opacity))))
        center_pt = point_at_fn(points, elapsed_ms) if len(points) >= 2 else None
        base_img = None
        if len(points) >= 2 and amap_base_fn is not None:
            try:
                base_img = amap_base_fn(
                    points,
                    card_w,
                    card_h,
                    style,
                    scale_meters=scale_meters,
                    center=center_pt,
                    aspect=card_aspect,
                    overview_blend=overview_blend,
                )
            except TypeError:
                base_img = amap_base_fn(
                    points,
                    card_w,
                    card_h,
                    style,
                    scale_meters=scale_meters,
                    center=center_pt,
                )
        if base_img is not None:
            if style == "satellite":
                card = base_img
                card.putalpha(card_alpha)
            else:
                a_lut = [card_alpha] * 256
                lut = self._road_r_lut + self._road_g_lut + self._road_b_lut + a_lut
                card = base_img.point(lut)
        else:
            card = Image.new("RGBA", (card_w, card_h), (10, 14, 20, card_alpha))

        # 2. 轨迹线与已行驶轨迹线（与 overlay_widgets 共用同一套墨卡托窗口）
        if len(points) >= 2:
            frame = overlay_widgets.route_mercator_frame(
                points,
                scale_meters=scale_meters,
                center=center_pt,
                aspect=card_aspect,
                overview_blend=overview_blend,
            )
            if frame is not None:
                subset, origin_x, origin_y, span_x, span_y = frame

                if not hasattr(self, "_merc_cache_id") or getattr(self, "_merc_cache_id") != id(points):
                    self._merc_cache_id = id(points)
                    self._merc_coords = [
                        (getattr(pt, "elapsed_ms", 0), *overlay_widgets.amap_mercator(pt.latitude, pt.longitude))
                        for pt in points
                    ]
                    self._peak_point = overlay_widgets.max_speed_point(points)
                if not hasattr(self, "_radars_cache_id") or getattr(self, "_radars_cache_id") != id(radars):
                    self._radars_cache_id = id(radars)
                    self._dedup_radars_merc = [
                        (*overlay_widgets.mercator_unit(r_lat, r_lon), r_spd)
                        for r_lat, r_lon, r_spd in overlay_widgets.deduplicate_radars(radars)
                    ]
                inv_sx = card_w / span_x
                inv_sy = card_h / span_y
                full_xy = [((mx - origin_x) * inv_sx, (my - origin_y) * inv_sy) for _, mx, my in self._merc_coords]
                played_xy = [
                    ((mx - origin_x) * inv_sx, (my - origin_y) * inv_sy)
                    for ems, mx, my in self._merc_coords
                    if ems <= elapsed_ms
                ]
                if center_pt is not None:
                    if scale_meters and scale_meters > 0 and overview_blend <= 1e-4:
                        # 局部比例尺开启时，车标永久严格固定在卡片正中心 (card_w / 2, card_h / 2)
                        center_px_x = card_w / 2.0
                        center_px_y = card_h / 2.0
                        played_xy.append((center_px_x, center_px_y))
                        marker_px_x = card_w // 2
                        marker_px_y = card_h // 2
                    else:
                        cmx, cmy = overlay_widgets.amap_mercator(center_pt.latitude, center_pt.longitude)
                        played_xy.append(((cmx - origin_x) * inv_sx, (cmy - origin_y) * inv_sy))
                        marker_px_x = int(round((cmx - origin_x) * inv_sx))
                        marker_px_y = int(round((cmy - origin_y) * inv_sy))
                else:
                    marker_px_x = int(round(full_xy[-1][0]))
                    marker_px_y = int(round(full_xy[-1][1]))

                c_draw = ImageDraw.Draw(card)
                speed_boost = 1.5 if float(speed_kmh or 0.0) > 180.0 else 1.0
                if is_split:
                    w_full = max(3, int(round(3.8 * ui * speed_boost)))
                    w_played = max(2, int(round(w_full * 0.5)))
                else:
                    w_full = max(2, int(round(2.0 * ui * speed_boost)))
                    w_played = max(2, int(round(2.6 * ui)))
                if len(full_xy) >= 2:
                    c_draw.line(full_xy, fill=(0, 0, 0, 145), width=w_full + 2)
                    c_draw.line(full_xy, fill=_rgba(route_rgb, 245 * opacity), width=w_full)
                if len(played_xy) >= 2:
                    c_draw.line(played_xy, fill=_rgba(played_rgb, 255 * opacity), width=w_played)

                # 2.5 绘制最高速度点与测速雷达图标（与手机 App MapRouteView 一致）
                peak_pt = getattr(self, "_peak_point", None)
                if peak_pt is not None:
                    plat, plon, pspd = overlay_widgets._point_lat_lon_speed(peak_pt)
                    pmx, pmy = overlay_widgets.amap_mercator(plat, plon)
                    ppx = int(round((pmx - origin_x) * inv_sx))
                    ppy = int(round((pmy - origin_y) * inv_sy))
                    if -24 <= ppx <= card_w + 24 and -24 <= ppy <= card_h + 24:
                        self._draw_max_speed_dot(card, ppx, ppy, ui, pspd)

                compact_radar = overview_blend >= 0.45 or scale_meters <= 0 or scale_meters >= 3000
                for rmx, rmy, r_spd in getattr(self, "_dedup_radars_merc", ()):
                    rpx = int(round((rmx - origin_x) * inv_sx))
                    rpy = int(round((rmy - origin_y) * inv_sy))
                    if -32 <= rpx <= card_w + 32 and -32 <= rpy <= card_h + 32:
                        r_sprite = self._get_radar_badge_sprite(ui, r_spd, compact=compact_radar)
                        card.alpha_composite(
                            r_sprite,
                            (rpx - r_sprite.width // 2, rpy - r_sprite.height // 2),
                        )

                # 3. 车标位置：split 模式使用手机 App 闪动圈，float 模式使用立体导航箭头
                if is_split:
                    self._draw_pulsing_location_dot(card, marker_px_x, marker_px_y, ui, elapsed_ms)
                else:
                    idx = max(0, min(len(self._merc_coords) - 2, len(played_xy) - 2))
                    _, ax, ay = self._merc_coords[idx]
                    _, bx, by = self._merc_coords[idx + 1]
                    marker_angle = math.degrees(math.atan2((bx - ax) * inv_sx, (ay - by) * inv_sy))
                    sprite = self._get_car_marker_sprite(ui, marker_angle, raw_marker)
                    card.alpha_composite(
                        sprite,
                        (marker_px_x - sprite.width // 2, marker_px_y - sprite.height // 2),
                    )

        if is_split:
            # split 模式：左上角浅色毛玻璃里程胶囊，无圆角裁切直接铺满下半屏
            dist_str = (telemetry or {}).get("distance", "")
            if dist_str and dist_str != "—":
                c_draw = ImageDraw.Draw(card)
                d_font = load_cjk_font(max(12, int(round(12.5 * ui))))
                full_txt = f"里程  {dist_str}"
                tb = c_draw.textbbox((0, 0), full_txt, font=d_font)
                tw = tb[2] - tb[0]
                th = tb[3] - tb[1]
                pw = tw + int(round(22 * ui))
                ph = th + int(round(11 * ui))
                px0 = int(round(14 * ui))
                py0 = int(round(14 * ui))
                c_draw.rounded_rectangle(
                    (px0, py0, px0 + pw, py0 + ph),
                    radius=ph // 2,
                    fill=(255, 255, 255, 226),
                    outline=(15, 23, 42, 36),
                )
                c_draw.text(
                    (px0 + int(round(11 * ui)) - tb[0], py0 + (ph - th) // 2 - tb[1]),
                    full_txt,
                    font=d_font,
                    fill=(15, 23, 42, 245),
                )
            return card

        # 4. 左下角高德/Apple Maps 风格极简无黑框比例尺（上方居中米数 + 下方带深色描边的细标尺线）
        c_draw = ImageDraw.Draw(card)
        if getattr(map_cfg, "show_scale_bar", False):
            if overview_blend >= 0.75 or scale_meters <= 0:
                s_label = "全程总览"
                bar_pct = 20.0
            else:
                eff_scale = (scale_meters or 220.0) * ((8.0) ** max(0.0, min(1.0, overview_blend)))
                meters, bar_pct = overlay_widgets._nice_scale_meters(eff_scale)
                s_label = f"{meters // 1000} km" if meters >= 1000 else f"{meters} m"
            bar_px = max(20, int(round(bar_pct * card_w / 100.0)))
            s_font = load_cjk_font(int(round(7.8 * ui))) if s_label == "全程总览" else load_latin_font("ChakraPetch-Bold.ttf", int(round(7.8 * ui)))
            sb = c_draw.textbbox((0, 0), s_label, font=s_font)
            tw = sb[2] - sb[0]
            th = sb[3] - sb[1]
            lx0 = int(round(12 * ui))
            lx1 = lx0 + bar_px
            ly = card_h - int(round(8 * ui))
            tick_h = max(2, int(round(3.0 * ui)))
            line_w = max(1, int(round(1.1 * ui)))
            out_w = line_w + 2
            # 先画一层深色描边衬底，再画纯白标尺线，无黑块背景也清晰锐利
            c_draw.line((lx0 - 1, ly, lx1 + 1, ly), fill=(8, 12, 18, 175), width=out_w)
            c_draw.line((lx0, ly - tick_h, lx0, ly), fill=(8, 12, 18, 175), width=out_w)
            c_draw.line((lx1, ly - tick_h, lx1, ly), fill=(8, 12, 18, 175), width=out_w)
            c_draw.line((lx0, ly, lx1, ly), fill=(255, 255, 255, 238), width=line_w)
            c_draw.line((lx0, ly - tick_h, lx0, ly), fill=(255, 255, 255, 238), width=line_w)
            c_draw.line((lx1, ly - tick_h, lx1, ly), fill=(255, 255, 255, 238), width=line_w)
            tx = lx0 + (bar_px - tw) // 2 - sb[0]
            ty = ly - tick_h - th - int(round(1.5 * ui)) - sb[1]
            c_draw.text((tx + 1, ty + 1), s_label, font=s_font, fill=(8, 12, 18, 190))
            c_draw.text(
                (tx, ty),
                s_label,
                font=s_font,
                fill=(255, 255, 255, 242),
                stroke_width=1,
                stroke_fill=(8, 12, 18, 155),
            )

        # 圆角裁切并描边
        radius = max(4, int(round(14 * ui)))
        mask_key = (card_w, card_h, radius)
        mask = self._map_mask_cache.get(mask_key)
        if mask is None:
            mask = Image.new("L", (card_w, card_h), 0)
            ImageDraw.Draw(mask).rounded_rectangle(
                (0, 0, card_w - 1, card_h - 1),
                radius=radius,
                fill=255,
                corners=(False, True, True, True),
            )
            self._map_mask_cache[mask_key] = mask
        tile.paste(card, (card_left, card_top), mask)
        tile_draw = ImageDraw.Draw(tile)
        tile_draw.rounded_rectangle(
            (card_left, card_top, card_left + card_w - 1, card_top + card_h - 1),
            radius=radius,
            outline=_rgba((255, 255, 255), 42 * opacity),
            width=max(1, int(round(1 * ui))),
            corners=(False, True, True, True),
        )

        # 5. 顶部斜切地址徽章（.map-badge）
        address = (values.get("address") or "").strip()
        if address:
            addr_font = load_cjk_font(int(round(13 * ui)))
            max_chars = 16
            shown_addr = address if len(address) <= max_chars else address[: max_chars - 1] + "…"
            ab = tile_draw.textbbox((0, 0), shown_addr, font=addr_font)
            tw = ab[2] - ab[0]
            th = ab[3] - ab[1]
            bh = max(int(round(26 * ui)), th + int(round(10 * ui)))
            bw = min(card_w - int(round(12 * ui)), tw + int(round(34 * ui)))
            bx = card_left + int(round(6 * ui))
            by = card_top - bh + 1
            skew_dx = int(round(bh * 0.22))
            poly = [
                (bx + skew_dx, by),
                (bx + bw + skew_dx, by),
                (bx + bw, by + bh),
                (bx, by + bh),
            ]
            tile_draw.polygon(poly, fill=_rgba((13, 17, 23), 215 * opacity), outline=_rgba((255, 255, 255), 42 * opacity))
            ind_w = max(3, int(round(4 * ui)))
            ind_h = max(8, int(round(12 * ui)))
            ix = bx + skew_dx // 2 + int(round(10 * ui))
            iy = by + (bh - ind_h) // 2
            tile_draw.rectangle((ix, iy, ix + ind_w, iy + ind_h), fill=_rgba(accent_rgb, 255 * opacity))
            tile_draw.text(
                (ix + ind_w + int(round(8 * ui)) - ab[0], by + (bh - th) // 2 - ab[1]),
                shown_addr,
                font=addr_font,
                fill=_rgba((255, 255, 255), 250 * opacity),
            )

        # 6. 地图卡片右下角内嵌精致经纬度水印（与左下角比例尺对称，不额外占用卡片下方高度）
        if getattr(map_cfg, "show_coordinates", False):
            coords = (values.get("coordinates") or "").strip()
            if coords:
                raw_csize = float(getattr(map_cfg, "coordinate_size", 11) or 11)
                coord_px = 8.5 if abs(raw_csize - 26.0) < 0.5 else max(6.5, min(14.0, raw_csize * 0.78))
                c_font = load_cjk_font(max(8, int(round(coord_px * ui))))
                cb = tile_draw.textbbox((0, 0), coords, font=c_font)
                pad_x = max(3, int(round(5.5 * ui)))
                pad_y = max(2, int(round(2.0 * ui)))
                cw = (cb[2] - cb[0]) + pad_x * 2
                ch = (cb[3] - cb[1]) + pad_y * 2
                cx0 = card_left + card_w - cw - max(5, int(round(9 * ui)))
                cy0 = card_top + card_h - ch - max(4, int(round(6 * ui)))
                tile_draw.rounded_rectangle(
                    (cx0, cy0, cx0 + cw, cy0 + ch),
                    radius=max(2, int(round(3 * ui))),
                    fill=(8, 12, 18, int(round(125 * opacity))),
                    outline=(255, 255, 255, int(round(34 * opacity))),
                )
                tile_draw.text(
                    (cx0 + pad_x - cb[0], cy0 + pad_y - cb[1]),
                    coords,
                    font=c_font,
                    fill=_rgba(_hex_rgb(getattr(map_cfg, "coordinate_color", "#e2e8f0"), (226, 232, 240)), 235 * opacity),
                )

        return tile

    def render_info_bar_tile(
        self,
        region: AtlasRegion,
        values: dict[str, str],
        speed_kmh: float,
    ) -> Image.Image:
        """绘制可选底部信息栏小图块。"""
        info_bar = self.layout.info_bar
        tile = Image.new("RGBA", (region.width, region.height), (0, 0, 0, 0))
        entries: list[str] = []
        for key in getattr(info_bar, "fields", []) or []:
            text = values.get(key)
            if key == "speed" and not text:
                text = f"{speed_kmh:.1f} km/h"
            if text:
                entries.append(text)
        if not entries:
            return tile
        ui_scale = max(0.5, min(self.canvas_width, self.canvas_height) / 1080.0)
        font_px = max(12, int(round(int(getattr(info_bar, "text_size", 30) or 30) * ui_scale)))
        pad_px = max(6, int(round(int(getattr(info_bar, "padding", 14) or 14) * ui_scale)))
        bg_rgb = _hex_rgb(getattr(info_bar, "background_color", "#ffffff"), (255, 255, 255))
        fg_rgb = _hex_rgb(getattr(info_bar, "text_color", "#17201e"), (23, 32, 30))
        opacity = max(0.0, min(1.0, float(getattr(info_bar, "opacity", 100) or 100) / 100.0))

        draw = ImageDraw.Draw(tile)
        draw.rectangle((0, 0, region.width, region.height), fill=_rgba(bg_rgb, 255 * opacity))
        font = load_cjk_font(font_px)
        line = "   ·   ".join(entries)
        tb = draw.textbbox((0, 0), line, font=font)
        th = tb[3] - tb[1]
        draw.text((pad_px - tb[0], (region.height - th) // 2 - tb[1]), line, font=font, fill=_rgba(fg_rgb, 255))
        return tile

    def render_field_tile(
        self,
        region: AtlasRegion,
        field_index: int,
        values: dict[str, str],
        speed_kmh: float,
    ) -> Image.Image:
        """绘制可选独立文字字段图块。"""
        fields = getattr(self.layout, "fields", []) or []
        tile = Image.new("RGBA", (region.width, region.height), (0, 0, 0, 0))
        if not (0 <= field_index < len(fields)):
            return tile
        item = fields[field_index]
        text = values.get(getattr(item, "key", ""))
        if getattr(item, "key", "") == "speed" and not text:
            text = f"{speed_kmh:.1f} km/h"
        if not text:
            return tile
        ui_scale = max(0.5, min(self.canvas_width, self.canvas_height) / 1080.0)
        font_px = max(10, int(round(int(getattr(item, "size", 32) or 32) * ui_scale)))
        pad_x = max(6, int(round(12 * ui_scale)))
        pad_y = max(4, int(round(6 * ui_scale)))
        font = load_cjk_font(font_px)
        draw = ImageDraw.Draw(tile)
        tb = draw.textbbox((0, 0), text, font=font)
        tw = tb[2] - tb[0]
        th = tb[3] - tb[1]
        bw = min(region.width - 4, tw + pad_x * 2)
        bh = min(region.height - 4, th + pad_y * 2)
        bx = (region.width - bw) // 2
        by = (region.height - bh) // 2
        opacity = max(0.0, min(1.0, float(getattr(item, "opacity", 100) or 100) / 100.0))
        if getattr(item, "background", True):
            bg_rgb = _hex_rgb(getattr(item, "background_color", "#0d1117"), (13, 17, 23))
            bg_op = max(0.0, min(1.0, float(getattr(item, "background_opacity", 70) or 70) / 100.0))
            draw.rounded_rectangle(
                (bx, by, bx + bw, by + bh),
                radius=max(3, int(round(6 * ui_scale))),
                fill=_rgba(bg_rgb, 255 * bg_op * opacity),
                outline=_rgba((255, 255, 255), 38 * opacity),
            )
            accent_rgb = _hex_rgb(getattr(self.layout.gauge, "arc_color", "#ff5e00"), (255, 94, 0))
            draw.rectangle((bx, by, bx + max(2, int(round(3 * ui_scale))), by + bh), fill=_rgba(accent_rgb, 255 * opacity))
        fg_rgb = _hex_rgb(getattr(item, "color", "#ffffff"), (255, 255, 255))
        draw.text(
            (bx + (bw - tw) // 2 - tb[0], by + (bh - th) // 2 - tb[1]),
            text,
            font=font,
            fill=_rgba(fg_rgb, 255 * opacity),
        )
        return tile

    def render_atlas_frame(
        self,
        *,
        values: dict[str, str],
        telemetry: dict[str, str],
        progress_ratio: float,
        speed_kmh: float,
        elapsed_ms: int,
        track_points: list[Any],
        amap_base_fn: Any,
        point_at_fn: Any,
        radars: list[Any] | None = None,
        output_elapsed_sec: float | None = None,
    ) -> Image.Image:
        """渲染一整帧紧凑图集（Atlas）RGBA 图像。"""
        atlas = Image.new("RGBA", (self.plan.atlas_width, self.plan.atlas_height), (0, 0, 0, 0))
        for region in self.plan.regions:
            if region.name == "hud":
                sub = self.render_hud_tile(region, values, telemetry, progress_ratio)
            elif region.name == "map":
                sub = self.render_map_tile(
                    region,
                    track_points,
                    elapsed_ms,
                    values,
                    amap_base_fn,
                    point_at_fn,
                    radars=radars,
                    telemetry=telemetry,
                    speed_kmh=speed_kmh,
                    output_elapsed_sec=output_elapsed_sec,
                )
            elif region.name == "gauge":
                sub = self.render_gauge_tile(region, speed_kmh)
            elif region.name == "info_bar":
                sub = self.render_info_bar_tile(region, values, speed_kmh)
            elif region.name.startswith("field:"):
                idx = int(region.name.split(":", 1)[1])
                sub = self.render_field_tile(region, idx, values, speed_kmh)
            else:
                continue
            atlas.paste(sub, (region.atlas_x, region.atlas_y))
        return atlas


def render_fullscreen_intro_map_frame(
    *,
    layout: Any,
    points: list[Any],
    width: int,
    height: int,
    map_style: str = "satellite",
    scale_meters: float = 0.0,
    amap_base_fn: Any = None,
    title_text: str = "",
    distance_text: str = "",
    duration_text: str = "",
    radars: list[Any] | None = None,
) -> Image.Image:
    """渲染片头全屏高德轨迹总览帧（支持卫星图 `satellite` 或道路图 `road`，默认 `scale_meters=0` 完整展示全程轨迹）。"""
    width = max(320, int(width))
    height = max(240, int(height))
    ui = max(0.6, min(width, height) / 1080.0)
    style = "road" if str(map_style).lower() == "road" else "satellite"
    aspect = float(width) / float(height)
    center_pt = points[len(points) // 2] if (scale_meters and scale_meters > 0 and len(points) >= 2) else None

    # 按最高 1920 长边拉取高德瓦片再双线性放大至目标画布，避免 4K 全屏超出瓦片上限
    fetch_scale = min(1.0, 1920.0 / float(max(width, height)))
    fetch_w = max(320, int(round(width * fetch_scale)))
    fetch_h = max(240, int(round(height * fetch_scale)))
    base_img = None
    if len(points) >= 2 and amap_base_fn is not None:
        try:
            base_img = amap_base_fn(
                points,
                fetch_w,
                fetch_h,
                style,
                scale_meters=scale_meters,
                center=center_pt,
                aspect=aspect,
            )
        except TypeError:
            base_img = amap_base_fn(
                points,
                fetch_w,
                fetch_h,
                style,
                scale_meters=scale_meters,
                center=center_pt,
            )
    if base_img is not None:
        if base_img.size != (width, height):
            canvas = base_img.resize((width, height), Image.BILINEAR).convert("RGBA")
        else:
            canvas = base_img.convert("RGBA")
    else:
        bg_rgb = (18, 26, 34) if style == "satellite" else (24, 32, 44)
        canvas = Image.new("RGBA", (width, height), (*bg_rgb, 255))

    draw = ImageDraw.Draw(canvas)
    accent_rgb = _hex_rgb(getattr(getattr(layout, "gauge", None), "arc_color", "#ff5e00"), (255, 94, 0))

    # 1. 绘制全程轨迹与起点/终点标记
    frame = overlay_widgets.route_mercator_frame(
        points, scale_meters=scale_meters, center=center_pt, aspect=aspect
    )
    eff_radius_m = float(scale_meters or 0.0)
    if frame is not None and len(points) >= 2:
        _, origin_x, origin_y, span_x, span_y = frame
        mid_lat = sum(pt.latitude for pt in points) / len(points)
        meters_per_unit = 40075016.686 * math.cos(math.radians(mid_lat))
        if eff_radius_m <= 0:
            eff_radius_m = max(40.0, (min(span_x, span_y) * meters_per_unit) / 2.0)
        inv_sx = width / max(1e-12, span_x)
        inv_sy = height / max(1e-12, span_y)
        xy_list = [
            ((mx - origin_x) * inv_sx, (my - origin_y) * inv_sy)
            for mx, my in (overlay_widgets.amap_mercator(pt.latitude, pt.longitude) for pt in points)
        ]
        if len(xy_list) >= 2:
            line_w = max(4, int(round(5.5 * ui)))
            draw.line(xy_list, fill=(8, 12, 18, 210), width=line_w + max(4, int(round(4 * ui))), joint="curve")
            draw.line(xy_list, fill=(*accent_rgb, 250), width=line_w, joint="curve")
            # 起点（绿点）与终点（亮青色/橙色靶点）
            sx0, sy0 = xy_list[0]
            ex0, ey0 = xy_list[-1]
            r_outer = max(7, int(round(10 * ui)))
            r_inner = max(4, int(round(5.5 * ui)))
            draw.ellipse((sx0 - r_outer, sy0 - r_outer, sx0 + r_outer, sy0 + r_outer), fill=(15, 23, 42, 235), outline=(255, 255, 255, 245), width=max(2, int(round(2 * ui))))
            draw.ellipse((sx0 - r_inner, sy0 - r_inner, sx0 + r_inner, sy0 + r_inner), fill=(34, 197, 94, 255))
            draw.ellipse((ex0 - r_outer, ey0 - r_outer, ex0 + r_outer, ey0 + r_outer), fill=(15, 23, 42, 235), outline=(255, 255, 255, 245), width=max(2, int(round(2 * ui))))
            draw.ellipse((ex0 - r_inner, ey0 - r_inner, ex0 + r_inner, ey0 + r_inner), fill=(0, 242, 255, 255))

            # 同步标注最高速度点与测速雷达限速图标
            helper = NativeStudioAtlasRenderer(layout, width, height)
            peak_pt = overlay_widgets.max_speed_point(points)
            if peak_pt is not None:
                plat, plon, pspd = overlay_widgets._point_lat_lon_speed(peak_pt)
                pmx, pmy = overlay_widgets.amap_mercator(plat, plon)
                ppx = int(round((pmx - origin_x) * inv_sx))
                ppy = int(round((pmy - origin_y) * inv_sy))
                if 0 <= ppx <= width and 0 <= ppy <= height:
                    helper._draw_max_speed_dot(canvas, ppx, ppy, ui * 1.15, pspd)
            for r_lat, r_lon, r_spd in overlay_widgets.deduplicate_radars(radars):
                rmx, rmy = overlay_widgets.mercator_unit(r_lat, r_lon)
                rpx = int(round((rmx - origin_x) * inv_sx))
                rpy = int(round((rmy - origin_y) * inv_sy))
                if 0 <= rpx <= width and 0 <= rpy <= height:
                    r_sp = helper._get_radar_badge_sprite(ui * 1.1, r_spd, compact=True)
                    canvas.alpha_composite(r_sp, (rpx - r_sp.width // 2, rpy - r_sp.height // 2))

    # 2. 左上角行程概览信息牌（模式标签 + 行程标题/里程/时长）
    mode_label = "卫星全览 · SATELLITE OVERVIEW" if style == "satellite" else "路网全览 · ROAD OVERVIEW"
    tag_font = load_cjk_font(max(13, int(round(15 * ui))))
    sub_font = load_cjk_font(max(12, int(round(13.5 * ui))))
    clean_title = title_text.strip()
    if clean_title and not bool(getattr(getattr(layout, "hud", None), "show_date", False)):
        # 隐藏年月日，仅保留时分秒，避免片头轨迹图泄露日期隐私
        clean_title = re.sub(r"\b\d{4}[-/]\d{2}[-/]\d{2}\s*", "", clean_title)
        clean_title = re.sub(r"\b\d{8}-(\d{2})(\d{2})(\d{2})\b", r"\1:\2:\3", clean_title).strip()
    meta_parts = [p for p in [clean_title, distance_text.strip(), duration_text.strip()] if p]
    sub_line = "  ·  ".join(meta_parts) if meta_parts else ("全程轨迹总览" if scale_meters <= 0 else f"比例半径 {int(round(scale_meters))} m")
    tb1 = draw.textbbox((0, 0), mode_label, font=tag_font)
    tb2 = draw.textbbox((0, 0), sub_line, font=sub_font)
    box_w = max(tb1[2] - tb1[0], tb2[2] - tb2[0]) + int(round(44 * ui))
    box_h = (tb1[3] - tb1[1]) + (tb2[3] - tb2[1]) + int(round(28 * ui))
    bx = int(round(36 * ui))
    by = int(round(32 * ui))
    draw.rounded_rectangle(
        (bx, by, bx + box_w, by + box_h),
        radius=max(6, int(round(10 * ui))),
        fill=(13, 17, 23, 212),
        outline=(255, 255, 255, 48),
        width=max(1, int(round(1.2 * ui))),
    )
    bar_w = max(4, int(round(5 * ui)))
    draw.rounded_rectangle((bx, by, bx + bar_w, by + box_h), radius=max(2, bar_w // 2), fill=(*accent_rgb, 255))
    tx = bx + int(round(18 * ui))
    ty1 = by + int(round(10 * ui)) - tb1[1]
    draw.text((tx, ty1), mode_label, font=tag_font, fill=(0, 242, 255, 255))
    ty2 = by + int(round(16 * ui)) + (tb1[3] - tb1[1]) - tb2[1]
    draw.text((tx, ty2), sub_line, font=sub_font, fill=(226, 232, 240, 245))

    # 3. 左下角全屏比例尺
    meters, bar_pct = overlay_widgets._nice_scale_meters(eff_radius_m if eff_radius_m > 0 else 500.0)
    s_label = f"{meters // 1000} km" if meters >= 1000 else f"{meters} m"
    bar_px = max(48, int(round(bar_pct * min(width, height) * 0.28 / 100.0)))
    s_font = load_latin_font("ChakraPetch-Bold.ttf", max(12, int(round(14 * ui))))
    sb = draw.textbbox((0, 0), s_label, font=s_font)
    tw = sb[2] - sb[0]
    th = sb[3] - sb[1]
    lx0 = int(round(40 * ui))
    lx1 = lx0 + bar_px
    ly = height - int(round(36 * ui))
    tick_h = max(4, int(round(6 * ui)))
    lw = max(2, int(round(2.2 * ui)))
    draw.line((lx0 - 1, ly, lx1 + 1, ly), fill=(8, 12, 18, 200), width=lw + 2)
    draw.line((lx0, ly - tick_h, lx0, ly), fill=(8, 12, 18, 200), width=lw + 2)
    draw.line((lx1, ly - tick_h, lx1, ly), fill=(8, 12, 18, 200), width=lw + 2)
    draw.line((lx0, ly, lx1, ly), fill=(255, 255, 255, 245), width=lw)
    draw.line((lx0, ly - tick_h, lx0, ly), fill=(255, 255, 255, 245), width=lw)
    draw.line((lx1, ly - tick_h, lx1, ly), fill=(255, 255, 255, 245), width=lw)
    draw.text(
        (lx0 + (bar_px - tw) // 2 - sb[0], ly - tick_h - th - int(round(4 * ui)) - sb[1]),
        s_label,
        font=s_font,
        fill=(255, 255, 255, 248),
        stroke_width=1,
        stroke_fill=(8, 12, 18, 180),
    )
    return canvas.convert("RGB")
