#!/usr/bin/env python3
"""CarSpeed macOS 视频导出服务。

本工具运行在 macOS 上，通过 adb 从已连接的 Android 手机拉取 CarSpeed 原始录像、SRT 字幕和轨迹 JSON，
再调用 ffmpeg 的 h264_videotoolbox 编码器生成带字幕、可选底部轨迹图的分享版 MP4。
"""

from __future__ import annotations

import argparse
import html
import json
import math
import os
import re
import shlex
import shutil
import subprocess
import threading
import time
import uuid
from dataclasses import dataclass
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


PUBLIC_REMOTE_ROOT = "/sdcard/Movies/CarSpeed"
PRIVATE_REMOTE_ROOT = "/sdcard/Android/data/com.thomas.carspeed/files/Movies/CarSpeed"
DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 8765
DEFAULT_FPS = 15


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
    root: Path
    videos: list[Path]
    subtitles: list[Path]
    tracks: list[Path]
    durations_ms: list[int]
    offsets_ms: list[int]
    total_duration_ms: int


@dataclass
class Job:
    id: str
    status: str = "queued"
    progress: int = 0
    message: str = "等待开始"
    output: str | None = None
    error: str | None = None


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


def run(command: list[str], timeout: int | None = None) -> str:
    result = subprocess.run(command, check=False, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
    if result.returncode != 0:
        detail = (result.stderr or result.stdout).strip()
        raise RuntimeError(f"命令失败: {' '.join(command)}\n{detail}")
    return result.stdout


def adb_shell(script: str) -> str:
    return run(["adb", "shell", script], timeout=60)


def check_tools() -> dict[str, str]:
    status: dict[str, str] = {}
    for tool in ("adb", "ffmpeg", "ffprobe"):
        path = shutil.which(tool)
        status[tool] = path or "missing"
    status["pillow"] = "ok" if Image is not None else "missing"
    return status


def adb_devices() -> list[str]:
    output = run(["adb", "devices"], timeout=20)
    devices = []
    for line in output.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            devices.append(parts[0])
    return devices


def list_remote(path: str) -> list[str]:
    quoted = shlex.quote(path)
    output = adb_shell(f"ls -1 {quoted} 2>/dev/null || true")
    return [line.strip() for line in output.splitlines() if line.strip()]


def remote_exists(path: str) -> bool:
    quoted = shlex.quote(path)
    output = adb_shell(f"[ -e {quoted} ] && echo yes || echo no")
    return output.strip() == "yes"


def list_trips() -> list[dict[str, Any]]:
    trips = []
    for name in list_remote(PUBLIC_REMOTE_ROOT):
        if not name.startswith("carspeed-trip-") and not name.endswith(".mp4"):
            continue
        remote = f"{PUBLIC_REMOTE_ROOT}/{name}"
        if name.endswith(".mp4"):
            videos = [name]
        else:
            videos = [child for child in list_remote(remote) if child.lower().endswith(".mp4")]
        if not videos:
            continue
        trips.append({"id": name, "title": name, "segments": len(videos), "videos": videos})
    return sorted(trips, key=lambda item: item["id"], reverse=True)


def pull_trip(trip: str, work_root: Path, progress: Callable[[int, str], None]) -> PulledTrip:
    trip_dir = work_root / safe_name(trip)
    raw_dir = trip_dir / "raw"
    raw_dir.mkdir(parents=True, exist_ok=True)
    public_remote = f"{PUBLIC_REMOTE_ROOT}/{trip}"
    private_remote = f"{PRIVATE_REMOTE_ROOT}/{trip}"

    progress(5, "读取手机录像目录")
    if trip.endswith(".mp4"):
        public_files = [trip]
        public_base = PUBLIC_REMOTE_ROOT
        private_base = PRIVATE_REMOTE_ROOT
    else:
        public_files = list_remote(public_remote)
        public_base = public_remote
        private_base = private_remote
    private_files = list_remote(private_base) if remote_exists(private_base) else []

    videos: list[Path] = []
    subtitles: list[Path] = []
    tracks: list[Path] = []
    needed = [f for f in public_files if f.lower().endswith((".mp4", ".srt", ".track.json", ".plate.json"))]
    needed += [f for f in private_files if f.lower().endswith((".track.json", ".plate.json"))]
    total = max(1, len(needed))
    pulled = 0

    for name in sorted(set(public_files)):
        if not name.lower().endswith((".mp4", ".srt", ".track.json", ".plate.json")):
            continue
        local = raw_dir / name
        progress(8 + pulled * 18 // total, f"拉取 {name}")
        run(["adb", "pull", f"{public_base}/{name}", str(local)], timeout=180)
        pulled += 1
        if name.lower().endswith(".mp4"):
            videos.append(local)
        elif name.lower().endswith(".srt"):
            subtitles.append(local)
        elif name.lower().endswith(".track.json"):
            tracks.append(local)

    for name in sorted(set(private_files)):
        if not name.lower().endswith((".track.json", ".plate.json")):
            continue
        local = raw_dir / name
        if local.exists():
            continue
        progress(8 + pulled * 18 // total, f"拉取 {name}")
        run(["adb", "pull", f"{private_base}/{name}", str(local)], timeout=180)
        pulled += 1
        if name.lower().endswith(".track.json"):
            tracks.append(local)

    videos = sorted(videos, key=lambda path: path.name)
    if not videos:
        raise RuntimeError("手机中没有找到 MP4 录像")
    durations = [probe_duration_ms(video) for video in videos]
    offsets = running_offsets(durations)
    return PulledTrip(
        trip=trip,
        root=trip_dir,
        videos=videos,
        subtitles=sorted(subtitles, key=lambda path: path.name),
        tracks=sorted(set(tracks), key=lambda path: path.name),
        durations_ms=durations,
        offsets_ms=offsets,
        total_duration_ms=max(1, sum(durations)),
    )


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
    if text == "GPS --" or re.search(r"-?\d{1,3}\.\d{4,},\s*-?\d{1,3}\.\d{4,}", text):
        return "gps"
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


class Handler(BaseHTTPRequestHandler):
    workspace: Path = Path.cwd() / "tools" / "macos_video_exporter" / "workspace"

    def do_GET(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        if parsed.path == "/":
            self.send_html(INDEX_HTML)
        elif parsed.path == "/api/health":
            self.send_json({"tools": check_tools(), "devices": adb_devices()})
        elif parsed.path == "/api/trips":
            self.send_json({"trips": list_trips()})
        elif parsed.path.startswith("/api/jobs/"):
            self.handle_job_get(parsed.path)
        else:
            self.send_error(HTTPStatus.NOT_FOUND)

    def do_POST(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        if parsed.path == "/api/export":
            length = int(self.headers.get("content-length") or 0)
            data = json.loads(self.rfile.read(length).decode("utf-8") or "{}")
            try:
                job = start_export_job(parse_options(data), self.workspace)
                self.send_json(job_to_dict(job), status=HTTPStatus.ACCEPTED)
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

    def send_file(self, path: Path) -> None:
        if not path.exists():
            self.send_error(HTTPStatus.NOT_FOUND)
            return
        self.send_response(HTTPStatus.OK)
        self.send_header("content-type", "video/mp4")
        self.send_header("content-length", str(path.stat().st_size))
        self.send_header("content-disposition", f"attachment; filename=\"{path.name}\"")
        self.end_headers()
        with path.open("rb") as handle:
            shutil.copyfileobj(handle, self.wfile)

    def log_message(self, format: str, *args: Any) -> None:  # noqa: A002
        print(f"[{datetime.now().strftime('%H:%M:%S')}] {self.address_string()} {format % args}")


def job_to_dict(job: Job) -> dict[str, Any]:
    return {
        "id": job.id,
        "status": job.status,
        "progress": job.progress,
        "message": job.message,
        "output": job.output,
        "error": job.error,
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
    parser = argparse.ArgumentParser(description="CarSpeed macOS 视频导出服务")
    parser.add_argument("--host", default=DEFAULT_HOST)
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--workspace", default=str(Path(__file__).resolve().parent / "workspace"))
    args = parser.parse_args()
    Handler.workspace = Path(args.workspace).expanduser().resolve()
    Handler.workspace.mkdir(parents=True, exist_ok=True)
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"CarSpeed macOS 视频导出服务已启动：http://{args.host}:{args.port}")
    print(f"工作目录：{Handler.workspace}")
    server.serve_forever()


if __name__ == "__main__":
    main()
