#!/usr/bin/env python3
"""macOS 导出服务的纯函数自检。

这些规则最容易被改坏又不容易在真机上碰到：字幕时间轴拼接、字幕文本压缩、
CLI 返回解析、排版配置往返。全部离线运行，不需要手机、FilmCraft 或素材。

    python3 tools/test_macos_exporter.py
"""

from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

SERVER = Path(__file__).resolve().parent / "macos_video_exporter" / "server.py"

failures: list[str] = []


def check(name: str, actual, expected) -> None:
    if actual != expected:
        failures.append(f"{name}: 期望 {expected!r}，实际 {actual!r}")


def load_server():
    spec = importlib.util.spec_from_file_location("carspeed_export_server", SERVER)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    sys.modules["carspeed_export_server"] = module
    spec.loader.exec_module(module)
    return module


SRT_LINE = (
    "2026-10-29 19:51:56  |  40.0 km/h  |  静止等待：00:00:00  |  "
    "30.30408°N 120.06519°E  |  GPS卫星：21  |  杭州市西湖区五常港路  |  "
    "对焦 0.07D(14m) · MF  |  4K 3840x2160 · 30fps"
)


RADAR_LINE = (
    "2026-10-29 20:01:56  |  87.0 km/h  |  静止等待：00:01:34  |  "
    "30.31879°N 119.96840°E  |  GPS卫星：36  |  测速 80kph×10  |  "
    "杭州市余杭区202县道  |  对焦 0.07D(14m) · MF  |  4K 3840x2160 · 30fps"
)


def test_radar_line_keeps_address(server) -> None:
    # 雷达提示曾经落进 else 分支，把真正的地址挤掉，地址栏最后显示成一串测速提示。
    values = server.cue_field_values(RADAR_LINE)
    check("雷达行地址", values.get("address"), "杭州市余杭区202县道")
    check("雷达行雷达字段", values.get("radar"), "测速 80kph×10")
    check("雷达行速度", values.get("speed"), "87.0 km/h")
    check("雷达行坐标", values.get("coordinates"), "30.31879°N 119.96840°E")


def test_caption_text(server) -> None:
    # 只保留时间和速度：地址、坐标、对焦、录像规格在画面上各有归属，不该再挤进字幕。
    text = server.caption_text(SRT_LINE)
    check("caption_text 保留时间", "2026-10-29 19:51:56" in text, True)
    check("caption_text 保留速度", "40.0 km/h" in text, True)
    check("caption_text 去掉地址", "杭州市西湖区五常港路" in text, False)
    check("caption_text 去掉坐标", "30.30408" in text, False)
    check("caption_text 长度", len(text) <= 40, True)
    # 解析不出字段时不能吞掉原文。
    check("caption_text 兜底", server.caption_text("随便一段话"), "随便一段话")


def test_timeline_cues(server) -> None:
    cues = [
        server.Cue(0, 1000, "a"),
        server.Cue(1000, 2000, "b"),
        server.Cue(1500, 2500, "c"),
        server.Cue(2600, 2600, "空"),
    ]
    trimmed = server._trim_cues(cues)
    check("裁剪后条数", len(trimmed), 3)
    check("首条起点", trimmed[0].start_ms, 0)
    # FilmCraft 拒绝任何时间相交的字幕，相邻两条之间必须留出空隙。
    for index in range(len(trimmed) - 1):
        gap = trimmed[index + 1].start_ms - trimmed[index].end_ms
        check(f"第 {index} 条间隙", gap >= server.CAPTION_GAP_MS, True)
    check("零长度被丢弃", all(cue.end_ms > cue.start_ms for cue in trimmed), True)


def test_parse_cli_json(server) -> None:
    # captions.setStyle 成功时只打印 null，不能被当成解析失败。
    check("null", server.parse_cli_json("null"), None)
    check("true", server.parse_cli_json("true"), True)
    check("空输出", server.parse_cli_json("   "), None)
    check("对象", server.parse_cli_json('{"track": 7}'), {"track": 7})
    check("带前缀", server.parse_cli_json('saved\n{"ok": true}'), {"ok": True})
    try:
        server.parse_cli_json("完全不是 JSON")
    except RuntimeError:
        pass
    else:
        failures.append("parse_cli_json 应当对非 JSON 抛错")


def test_layout_roundtrip(server) -> None:
    layout = server.default_layout()
    data = server.layout_to_dict(layout)
    restored = server.layout_from_dict(data)
    check("字幕样式往返", restored.caption, layout.caption)
    check("画布往返", (restored.width, restored.height), (layout.width, layout.height))
    check("地图开关往返", restored.map_layer.show_coordinates, layout.map_layer.show_coordinates)

    # 越界值要被夹回合法区间，不能写进配置。
    patched = server.layout_from_dict({"caption": {"size": 9999, "margin": -5}})
    check("字号上限", patched.caption.size <= 96, True)
    check("留白下限", patched.caption.margin >= 0, True)


def test_layout_save_keeps_newer_revision(server) -> None:
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "layout.json"
        server._LAYOUT_WRITE_REVISION = 0
        newer = server.default_layout()
        newer.hud.time_size = 48
        server.save_layout(newer, path, revision=20)
        older = server.default_layout()
        older.hud.time_size = 13
        server.save_layout(older, path, revision=19)
        loaded = server.load_layout(path)
        check("旧保存不能盖住新的面板大小", loaded.hud.time_size, 48)
        server._LAYOUT_WRITE_REVISION = 0


def test_captions_disabled(server) -> None:
    layout = server.default_layout()
    layout.caption.enabled = False
    # 关闭字幕时不建轨，直接返回 None（不调用任何 CLI）。
    result = server.import_caption_track(None, Path("/nonexistent"), Path("/tmp/x"), lambda *_: None, layout=layout)
    check("关闭字幕不建轨", result, None)


def test_overlay_matches_reference() -> None:
    """覆盖层 DOM 和字号比例要跟参考稿一致，不能再被拉成另一套。"""
    import math

    exporter = SERVER.parent
    if str(exporter) not in sys.path:
        sys.path.insert(0, str(exporter))
    import overlay_render
    import overlay_widgets

    check("静止弧", overlay_widgets.forza_dash_offset(0), 471.0)
    check("满弧", overlay_widgets.forza_dash_offset(1), 118.0)
    check("地图比例", round(overlay_widgets.REFERENCE_MAP_WIDTH / overlay_widgets.REFERENCE_MAP_HEIGHT, 3), round(220 / 140, 3))
    width, height, ui = overlay_widgets.map_card_size(0.22, 1920, 1080)
    check("地图高度", height, 0.22 * 1080)
    check("地图宽度", round(width / height, 3), round(220 / 140, 3))
    check("地图缩放", round(ui, 4), round(height / 140, 4))
    check("计时原稿", overlay_widgets.timing_ui(13, 1080), 1.0)

    gauge = type("Gauge", (), {"enabled": True, "style": "gt", "x": 0.8, "y": 0.7, "radius_percent": 0.16, "opacity": 100, "max_speed": 160})()
    gt = overlay_widgets.build_speed_widget(gauge, 0, 1920, 1080)
    check("GT 共 9 格", gt.count('class="gt-block'), 9)
    check("零速不点亮", "gt-block fill" in gt, False)
    gauge.style = "aero"
    aero = overlay_widgets.build_speed_widget(gauge, 160, 1920, 1080)
    check("Aero 7 格", aero.count('class="tick'), 7)
    check("满速全亮", aero.count("tick active"), 7)
    gauge.style = "bar"
    forza = overlay_widgets.build_speed_widget(gauge, 0, 1920, 1080)
    check("Forza 空弧", "stroke-dashoffset:471.0" in forza, True)

    map_html = overlay_widgets.build_map_widget(
        type("Map", (), {"enabled": True, "x": 0.15, "y": 0.72, "size_percent": 0.22, "opacity": 100})(),
        "杭州市上城区",
        "M 10 80 L 90 20",
        90,
        20,
        32,
        1920,
        1080,
    )
    check("地图铺满卡片", 'preserveAspectRatio="none"' in map_html, True)
    check("地图有裁切层", "map-plot" in map_html, True)
    template = (exporter / "overlay_template.html").read_text(encoding="utf-8")
    check("地图不再留黑边", "height: 84%" not in template, True)
    check("默认卫星标记", 'data-map-style="satellite"' in map_html, True)
    road_map = overlay_widgets.build_map_widget(
        type("Map", (), {"enabled": True, "x": 0.15, "y": 0.72, "size_percent": 0.22, "opacity": 100, "map_style": "road"})(),
        "测试",
        "M 10 80 L 90 20",
        90,
        20,
        32,
        1920,
        1080,
        map_image="data:image/jpeg;base64,xx",
    )
    check("道路图标记", 'data-map-style="road"' in road_map and "is-road" in road_map, True)
    check(
        "有底图时用高德图",
        "map-photo" in overlay_widgets.build_map_widget(
            type("Map", (), {"enabled": True, "x": 0.15, "y": 0.72, "size_percent": 0.22, "opacity": 100})(),
            "测试",
            "M 10 80 L 90 20",
            90,
            20,
            32,
            1920,
            1080,
            map_image="data:image/jpeg;base64,xx",
        ),
        True,
    )
    # 东西大道：向东约 1 公里，南北只偏 20 米。短轴不能被拉成满幅。
    class _Point:
        def __init__(self, lat: float, lon: float) -> None:
            self.latitude = lat
            self.longitude = lon
    road = [
        _Point(30.30 + (0.00018 if index == 4 else 0.0), 120.10 + index * 0.0001)
        for index in range(20)
    ]
    road_path, _mx, _my, _angle = overlay_widgets.route_polyline(road)
    xs: list[float] = []
    ys: list[float] = []
    for token in road_path.replace("M", " ").split("L"):
        x_text, y_text = token.split()
        xs.append(float(x_text))
        ys.append(float(y_text))
    check("东西向保持比例", max(ys) - min(ys) < (max(xs) - min(xs)) / 5, True)
    frame = overlay_widgets.route_mercator_frame(road)
    _, _ox, _oy, span_x, span_y = frame
    aspect = overlay_widgets.REFERENCE_MAP_WIDTH / overlay_widgets.REFERENCE_MAP_HEIGHT
    check("墨卡托窗对齐卡片", round(span_x / span_y, 4), round(aspect, 4))
    step = 1e-4
    check("等距不变形", round(step / span_x * 220, 5), round(step / span_y * 140, 5))
    check("车标三角定位", "rotate(32deg)" in map_html, True)
    check("轨迹按像素描边", 'vector-effect="non-scaling-stroke"' in map_html, True)
    check("轨迹不再用 viewBox 线宽", "stroke-width: 2.8" not in template, True)
    # 杭州附近 GPS 转 GCJ-02 大约偏 500 米；不转的话橙线会整段离开高架。
    gcj_lat, gcj_lon = overlay_widgets.wgs84_to_gcj02(30.31, 120.02)
    offset_m = math.hypot((gcj_lat - 30.31) * 111_320.0, (gcj_lon - 120.02) * 111_320.0 * math.cos(math.radians(30.31)))
    check("国内坐标要纠偏", 200 < offset_m < 900, True)
    same_lat, same_lon = overlay_widgets.wgs84_to_gcj02(48.8, 2.3)
    check("境外坐标不改", (round(same_lat, 6), round(same_lon, 6)), (48.8, 2.3))
    class _Scaled:
        def __init__(self, lat: float, lon: float, elapsed: int) -> None:
            self.latitude = lat
            self.longitude = lon
            self.elapsed_ms = elapsed
    here = _Scaled(30.30, 120.10, 0)
    away = _Scaled(30.35, 120.20, 10_000)
    scaled = overlay_widgets.route_mercator_frame([here, away], scale_meters=500, center=here)
    assert scaled is not None
    _subset, _ox, _oy, scaled_x, scaled_y = scaled
    ground = 40075016.686 * math.cos(math.radians(30.30))
    check("比例尺决定窗口高度", abs(scaled_y * ground - 1000) < 5, True)
    check("比例尺窗口不变形", round(scaled_x / scaled_y, 4), round(aspect, 4))
    _scaled_path, marker_x, marker_y, _scaled_angle = overlay_widgets.route_polyline(
        [here, away], scale_meters=500, center=here
    )
    check("车标落在比例尺中心", abs(marker_x - 50) < 0.2 and abs(marker_y - 50) < 0.2, True)

    html = overlay_render.build_overlay_html(
        canvas_width=1920,
        canvas_height=1080,
        accent_color="#ff5e00",
        widgets_html=forza + map_html,
        values={"uiScale": "999"},
    )
    check("本地 Orbitron", "fonts/Orbitron-Black.ttf" in html, True)
    check("本地 Chakra", "fonts/ChakraPetch-BoldItalic.ttf" in html, True)
    check("斜切徽章", "skewX(-12deg)" in html, True)
    check("车标三角", "polygon(50% 0%, 0% 100%, 50% 75%, 100% 100%)" in html, True)
    check("主题色不被字幕冲掉", "--primary-color: #ff5e00" in html, True)
    check("缩放不被字幕冲掉", "--ui: 999" in html, False)
    check("覆盖层留出录像底", 'id="video-plate"' in html, True)
    for _, _, _, filename in overlay_render.FONT_FACES:
        check(f"字体文件 {filename}", (overlay_render.FONT_DIR / filename).is_file(), True)


def test_remote_frame_index() -> None:
    exporter = SERVER.parent
    if str(exporter) not in sys.path:
        sys.path.insert(0, str(exporter))
    import remote_frame
    import struct

    index = remote_frame.sample_index_for_ticks([(10, 3000)], 9000, 10)
    check("第 3 个样本", index, 3)
    check("关键帧回退", remote_frame.previous_sync([0, 60, 120], 143), 120)
    sps = b"\x67\x64\x00"
    pps = b"\x68\xee"
    avcc = bytes([1, 0x64, 0, 0x1F, 0xFF, 0xE1]) + struct.pack(">H", len(sps)) + sps + bytes([1]) + struct.pack(">H", len(pps)) + pps
    nal = b"\x65\x88"
    annex = remote_frame.avcc_and_samples_to_annexb(avcc, struct.pack(">I", len(nal)) + nal)
    check("参数集进码流", annex.startswith(b"\x00\x00\x00\x01" + sps), True)
    check("样本进码流", annex.endswith(b"\x00\x00\x00\x01" + nal), True)


def test_local_library_status(server) -> None:
    import tempfile

    segment = server.RemoteSegment(
        name="a.mp4",
        public_path="/sdcard/a.mp4",
        track_path="/sdcard/a.track.json",
        subtitle_path="/sdcard/a.srt",
        plate_path=None,
        bytes=1000,
        companion_bytes=20,
        has_track=True,
        has_subtitle=True,
        has_plate=False,
    )
    trip = server.RemoteTrip(
        id="20261030-093910",
        title="测试",
        started_at_ms=0,
        segments=[segment],
        segment_count=1,
        track_count=1,
        subtitle_count=1,
        total_bytes=1000,
        companion_bytes=20,
        thumbnail_path=None,
    )
    with tempfile.TemporaryDirectory() as tmp:
        workspace = Path(tmp)
        missing = server.trip_local_summary(workspace, trip)
        check("没拉下来不算在电脑", missing["ready"], False)
        check("没拉下来不算半截", missing["partial"], False)
        job_copy = workspace / "job" / trip.id / trip.id / "raw"
        job_copy.mkdir(parents=True)
        (job_copy / "a.mp4").write_bytes(b"x" * 1000)
        (job_copy / "a.srt").write_bytes(b"srt")
        (job_copy / "a.track.json").write_bytes(b"{}")
        ready = server.trip_local_summary(workspace, trip)
        check("项目目录里的完整文件算已在电脑", ready["ready"], True)
        check("能找到项目目录里的录像", server.best_local_file(workspace, trip.id, "a.mp4") is not None, True)
        short = workspace / "library" / trip.id / "raw"
        short.mkdir(parents=True)
        (short / "a.mp4").write_bytes(b"x" * 1000)
        (short / "a.srt").write_bytes(b"srt")
        # 轨迹还没到，资料库这份视频是完整的，但 sidecar 缺一份，不能算全部就绪。
        (job_copy / "a.track.json").unlink()
        partial = server.trip_local_summary(workspace, trip)
        check("缺轨迹不算全部就绪", partial["ready"], False)
        check("有录像算传输过", partial["partial"], True)
        check("优先用资料库", server.best_local_file(workspace, trip.id, "a.mp4"), short / "a.mp4")


def test_library_trips_without_phone(server) -> None:
    import tempfile

    original = server.remote_inventory
    server.remote_inventory = lambda _paths: {}
    try:
        with tempfile.TemporaryDirectory() as tmp:
            workspace = Path(tmp)
            empty = server.list_trips(workspace)
            check("资料库是空的才用演示行程", [item["id"] for item in empty], ["demo-hangzhou"])
            newest = workspace / "library" / "20261030-093910" / "raw"
            older = workspace / "library" / "20261029-195154" / "raw"
            newest.mkdir(parents=True)
            older.mkdir(parents=True)
            (newest / "carspeed-20261030-093912.mp4").write_bytes(b"x" * 1200)
            (newest / "carspeed-20261030-094912.mp4").write_bytes(b"y" * 1200)
            (newest / "carspeed-20261030-093912.track.json").write_text(
                '{"points":[{"elapsedMs":5,"timeMs":1000,"latitude":30.34,"longitude":119.98,"speedKmh":40}]}',
                encoding="utf-8",
            )
            (newest / "carspeed-20261030-093912.srt").write_text(
                "1\n00:00:00,000 --> 00:00:01,000\n40.0 km/h\n",
                encoding="utf-8",
            )
            (older / "carspeed-20261029-195156.mp4").write_bytes(b"z" * 1200)
            trips = server.list_trips(workspace)
            check(
                "断手机后两趟都在",
                [item["id"] for item in trips],
                ["20261030-093910", "20261029-195154"],
            )
            check("不再用演示行程盖住已下载的", "demo-hangzhou" in {item["id"] for item in trips}, False)
            check("已下载的行程标记在电脑", trips[0]["local"]["ready"], True)
            opened = server.open_trip("20261030-093910", workspace)
            check("离线能打开资料库行程", opened.segment_count, 2)
            check("离线能读到轨迹", len(server.preview_track_points(opened, workspace)), 1)
    finally:
        server.remote_inventory = original


def test_preview_media_range(server) -> None:
    import tempfile

    check("没有 Range 就是整文件", server.parse_byte_range(None, 1000), (0, 999))
    check("闭区间", server.parse_byte_range("bytes=0-99", 1000), (0, 99))
    check("后半段", server.parse_byte_range("bytes=100-", 1000), (100, 999))
    check("末尾 50 字节", server.parse_byte_range("bytes=-50", 1000), (950, 999))
    check("起点越界", server.parse_byte_range("bytes=5000-", 1000), None)
    original = server.remote_inventory
    server.remote_inventory = lambda _paths: {}
    try:
        with tempfile.TemporaryDirectory() as tmp:
            workspace = Path(tmp)
            raw = workspace / "library" / "20261030-093910" / "raw"
            raw.mkdir(parents=True)
            video = raw / "carspeed-20261030-093912.mp4"
            video.write_bytes(b"x" * 1500)
            payload = server.preview_media_payload("20261030-093910", workspace, 1.5)
            check("本地录像直接播", payload["source"], "local")
            check("从这一秒开始", payload["startMs"], 1500)
            check("地址指向这段录像", payload["url"].endswith("/carspeed-20261030-093912.mp4"), True)
            found = server.library_video_file(workspace, "20261030-093910", "carspeed-20261030-093912.mp4")
            check("只打开资料库里的录像", found, video)
            check("拒绝别的文件名", server.library_video_file(workspace, "20261030-093910", "../secret.mp4"), None)
            check("拒绝预览路径", server.preview_media_clip(workspace, "../secret.mp4"), None)
            check("坏录像切不出短片", server.build_fast_preview(workspace, video, 0, 2), None)
            check("进度条能接着播一分钟", server.preview_clip_seconds(90), 60.0)
            check("太短的片段按一秒", server.preview_clip_seconds(0.2), 1.0)
            srt = raw / "carspeed-20261030-093912.srt"
            srt.write_text(
                "1\n00:00:00,000 --> 00:00:01,000\n2026-10-30 09:39:12  |  10.0 km/h\n\n"
                "2\n00:00:01,000 --> 00:00:02,000\n2026-10-30 09:39:13  |  30.0 km/h\n\n"
                "3\n00:00:02,000 --> 00:00:03,000\n2026-10-30 09:39:14  |  50.0 km/h\n\n",
                encoding="utf-8",
            )
            remote = server.open_trip("20261030-093910", workspace)
            series = server.speed_series(remote, workspace)
            check("速度点一个不丢", series, [(0, 10.0), (1000, 30.0), (2000, 50.0)])
            check("样本之间插值", server.speed_at(series, 500), 20.0)
            check("落在样本上就是原值", server.speed_at(series, 1000), 30.0)
            _values, shown = server.sample_trip_values(remote, workspace, 500)
            check("预览速度用插值", shown, 20.0)
    finally:
        server.remote_inventory = original


def test_degree_coordinates(server) -> None:
    degree = server.SRT_COORDINATE_RE.search("30.304080°N 120.165600°E")
    comma = server.SRT_COORDINATE_RE.search("30.30408,120.06519")
    check("度分纬度", None if degree is None else degree.group("lat"), "30.304080")
    check("度分经度", None if degree is None else degree.group("lon"), "120.165600")
    check("逗号经度", None if comma is None else comma.group("lon"), "120.06519")


def test_join_track_segments(server) -> None:
    first = [
        server.TrackPoint(5, 1_000, 30.34, 119.98, 40.0),
        server.TrackPoint(598_000, 599_000, 30.30, 120.06, 40.0),
    ]
    second = [
        server.TrackPoint(8, 600_000, 30.30, 120.06, 30.0),
        server.TrackPoint(20_000, 620_000, 30.29, 120.06, 30.0),
    ]
    joined = server.join_track_segments([first, second])
    check("两段都保留", len(joined), 4)
    check("后一段接在后面", joined[2].latitude, 30.30)
    check("后一段时间不倒回", joined[2].elapsed_ms > joined[1].elapsed_ms, True)
    check("单段时间轴不动", server.join_track_segments([first])[0].elapsed_ms, 5)


def test_extract_preview_clip(server) -> None:
    import subprocess
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        video = Path(tmp) / "clip.mp4"
        result = subprocess.run(
            [
                "ffmpeg", "-y", "-v", "error",
                "-f", "lavfi", "-i", "testsrc=size=320x180:rate=30",
                "-t", "1", "-pix_fmt", "yuv420p", str(video),
            ],
            capture_output=True,
            check=False,
        )
        if result.returncode != 0 or not video.is_file():
            failures.append(f"无法生成测试录像: {result.stderr.decode('utf-8', 'replace')[:200]}")
            return
        frames = server.extract_preview_clip(video, 0, 1000, 8, 160)
        if len(frames) < 6:
            failures.append(f"预览片段帧数不足: {len(frames)}")
            return
        if not frames[0].startswith(b"\xff\xd8"):
            failures.append("预览片段不是 JPEG")
        demo = server.preview_clip_payload("demo-hangzhou", Path(tmp), 0, 10, 8)
        check("演示行程不抽录像", demo["source"], "demo")
        check("演示行程没有帧", demo["frames"], [])


def test_bugfixes_and_forza_gauge(server) -> None:
    """验证 Forza 300kph 表盘钳位/亮色字体、keep_subtitle_line 全类别、route_scale_for、离线复用与遥测联动数据。"""
    import sys
    import tempfile

    root = Path(__file__).resolve().parent / "macos_video_exporter"
    if str(root) not in sys.path:
        sys.path.insert(0, str(root))
    import overlay_widgets

    # 1. Forza 表盘默认 300kph，超过 300kph 时数值显示原值，但弧度停在最右侧（dashoffset=118.0）
    gauge = server.GaugeLayout()
    check("默认表盘最大速度为300", gauge.max_speed, 300)
    check("默认表盘数字颜色为亮青色", gauge.value_color, "#00f2ff")
    html_350 = overlay_widgets.build_speed_widget(gauge, 358.0)
    check("超300kph显示真实数值358", ">358</div>" in html_350, True)
    check("超300kph弧度停在最右侧118.0", "stroke-dashoffset:118.0" in html_350, True)
    check("Forza圆环半径修正为r=75", 'r="75"' in html_350, True)

    # 2. keep_subtitle_line 覆盖 waitTime / satellites / radar 不抛 KeyError
    options = server.ExportOptions(trip="demo-hangzhou")
    check("保留静止等待行", server.keep_subtitle_line("静止等待：00:00:12", options), True)
    check("保留卫星行", server.keep_subtitle_line("GPS卫星：19", options), True)
    check("保留雷达测速行", server.keep_subtitle_line("区间测速 100kph×4", options), True)

    # 3. route_scale_for 经度跨度计算正确
    pts = [
        server.TrackPoint(0, 1000, 30.0, 120.0, 60.0),
        server.TrackPoint(1000, 2000, 30.1, 120.2, 60.0),
    ]
    scale = server.route_scale_for(pts, 200, 100)
    check("route_scale_for按真实经度跨度缩放", 900.0 < scale < 1000.0, True)

    # 4. _cues_starts 缓存键区分相同长度/首尾时间的不同字幕内容
    cues_a = [server.Cue(0, 1000, "2026-10-30 10:00:00 | 30.0 km/h | 路段A"), server.Cue(1000, 2000, "路段A2")]
    cues_b = [server.Cue(0, 1000, "2026-10-30 10:00:00 | 80.0 km/h | 路段B"), server.Cue(200, 2000, "路段B2")]
    check("不同字幕起始时间不串缓存", server._cues_starts(cues_a) != server._cues_starts(cues_b), True)

    # 5. overlay_preview_live 返回前端联动所需的 mapFrame / trackPoints / cues / totalDistanceMeters
    with tempfile.TemporaryDirectory() as tmp:
        live = server.overlay_preview_live("demo-hangzhou", Path(tmp), {"t": "5"}, include_background=False, include_html=True)
        check("preview-live包含mapFrame", isinstance(live.get("mapFrame"), dict), True)
        check("preview-live包含trackPoints", len(live.get("trackPoints") or []) >= 2, True)
        check("preview-live包含totalDistanceMeters", (live.get("totalDistanceMeters") or 0) > 0, True)
        check("地图包含已行驶轨迹路径", "route-played" in (live.get("html") or ""), True)

    # 6. 紧凑图集（Packed Widget Atlas）面积缩减、局部坐标计算与 FFmpeg 滤镜链校验
    import overlay_atlas

    layout_4k = server.default_layout()
    plan = overlay_atlas.build_atlas_plan(layout_4k, 3840, 2160)
    full_area = 3840 * 2160
    atlas_area = plan.atlas_width * plan.atlas_height
    check("紧凑图集面积相比4K全屏减少70%以上", atlas_area < full_area * 0.30, True)
    check("默认紧凑图集包含hud/map/gauge三区域", [r.name for r in plan.regions], ["hud", "map", "gauge"])
    filter_str = overlay_atlas.build_ffmpeg_atlas_filter(
        plan,
        speed=1.0,
        fps=30,
        source_width=3840,
        source_height=2160,
        target_width=3840,
        target_height=2160,
    )
    check("同分辨率1倍速不做全屏scale", "scale=3840:2160" not in filter_str, True)
    check("滤镜使用split+crop局部overlay", "split=3" in filter_str and "crop=" in filter_str and "overlay=x=" in filter_str, True)

    renderer = overlay_atlas.NativeStudioAtlasRenderer(layout_4k, 1920, 1080)
    atlas_img = renderer.render_atlas_frame(
        values={"time": "2026-10-30 09:39:23", "address": "杭州市东西大道", "coordinates": "30.34°N 119.98°E"},
        telemetry={"duration": "00:00:12", "distance": "69 m / 13.4 km"},
        progress_ratio=0.15,
        speed_kmh=88.0,
        elapsed_ms=500,
        track_points=pts,
        amap_base_fn=None,
        point_at_fn=server.point_at,
    )
    check("紧凑图集渲染尺寸与plan一致", atlas_img.size, (renderer.plan.atlas_width, renderer.plan.atlas_height))
    check("整公里速度表命中缓存复用", renderer.render_gauge_tile(renderer.plan.regions[2], 88.2) is renderer.render_gauge_tile(renderer.plan.regions[2], 87.9), True)
    metal_bin = server.ensure_metal_compositor_bin()
    check("macOS Metal 3 零拷贝合成器可执行文件就绪", metal_bin is not None and metal_bin.is_file(), True)

    # 校验局部比例尺（scaleMeters > 0）下车标永久居中（50%, 50%）
    path_full, mx_c, my_c, _ = overlay_widgets.route_polyline(
        pts,
        scale_meters=200.0,
        center=server.point_at(pts, 500),
    )
    played_path = overlay_widgets.route_played_polyline(
        pts,
        500,
        scale_meters=200.0,
        center=server.point_at(pts, 500),
    )
    check("局部比例尺下网页车标永久固定在正中心(50,50)", (mx_c, my_c), (50.0, 50.0))
    check("局部比例尺下已行驶轨迹终点精确连接到正中心", played_path.endswith("50.00 50.00"), True)

    studio_html = (SERVER.parent / "studio.html").read_text(encoding="utf-8")
    check("排版编辑器包含全屏预览拖拽开关", "layoutFullscreenButton" in studio_html and "toggleStageFullscreen" in studio_html, True)
    check("地图比例尺与滑块包含加减步进按钮", "data-quick-map-scale" in studio_html and "data-step-target" in studio_html and "data-menu-step" in studio_html, True)
    map_widget_html = overlay_widgets.build_map_widget(layout_4k.map_layer, "西湖大道", "M 0 0 L 100 100", 50.0, 50.0, 0.0)
    check("地图卡片不再显示道路/卫星文字角标", "map-mode-tag" not in map_widget_html, True)

    # 8. 横屏/竖屏命名配置方案保存、加载与删除校验
    with tempfile.TemporaryDirectory() as tmp:
        presets_path = Path(tmp) / "layout_presets.json"
        active_layout_path = Path(tmp) / "layout.json"
        initial_presets = server.load_layout_presets(presets_path)
        preset_names = [item["name"] for item in initial_presets]
        check("默认包含横屏和竖屏两个内置预设", "默认横屏 (16:9)" in preset_names and "默认竖屏 (9:16)" in preset_names, True)

        portrait_cfg = server.default_portrait_layout()
        portrait_cfg.map_layer.x = 0.28
        portrait_cfg.map_layer.y = 0.78
        portrait_cfg.gauge.max_speed = 260
        saved_list = server.save_layout_preset("竖屏山路模式", portrait_cfg, path=presets_path, layout_path=active_layout_path)
        check("保存命名配置后出现在预设列表", any(item["name"] == "竖屏山路模式" and item["orientation"] == "portrait" for item in saved_list), True)

        # 切换加载回默认横屏，再加载回「竖屏山路模式」验证参数完整还原
        loaded_land = server.activate_layout_preset("默认横屏 (16:9)", path=presets_path, layout_path=active_layout_path)
        check("加载默认横屏后画布为1920x1080", (loaded_land.width, loaded_land.height), (1920, 1080))
        loaded_port = server.activate_layout_preset("竖屏山路模式", path=presets_path, layout_path=active_layout_path)
        check("加载竖屏山路模式后画布为1080x1920且参数还原", (loaded_port.width, loaded_port.height, loaded_port.gauge.max_speed), (1080, 1920, 260))

        after_del = server.delete_layout_preset("竖屏山路模式", path=presets_path)
        check("删除自定义配置后从列表中移除", all(item["name"] != "竖屏山路模式" for item in after_del), True)

    check("前端包含横竖屏切换与配置命名保存/加载控件", "layoutPresetSelect" in studio_html and "layoutPresetSaveButton" in studio_html and "layoutPresetLoadButton" in studio_html and "orientPortraitBtn" in studio_html, True)

    # 9. GPS卫星个数显示、时间戳仅显示时分秒（隐藏年月日防隐私泄露）、经纬度默认显示校验
    default_cfg = server.default_layout()
    check("默认HUD隐藏年月日(showDate=False)", default_cfg.hud.show_date, False)
    check("默认地图开启经纬度显示(showCoordinates=True)", default_cfg.map_layer.show_coordinates, True)
    hud_html = overlay_widgets.build_timing_widget(
        default_cfg.hud,
        {"time": "2026-10-29 19:51:56", "satellites": "GPS卫星：21"},
        {"duration": "00:01:20", "distance": "1.2 km / 5.0 km"},
        0.24,
        1080,
        1920,
    )
    check("HUD仅显示时分秒且不含年月日", "19:51:56" in hud_html and "2026-10-29" not in hud_html, True)
    check("HUD显示GPS卫星颗数徽章", "hud-satellites" in hud_html and ">21<" in hud_html, True)
    map_coords_html = overlay_widgets.build_map_widget(
        default_cfg.map_layer,
        "西湖大道",
        "M 0 0 L 100 100",
        50.0,
        50.0,
        0.0,
        coordinates="30.30408°N 120.06519°E",
    )
    check("地图卡片底部显示实时经纬度胶囊", 'id="map-coords"' in map_coords_html and "30.30408°N 120.06519°E" in map_coords_html, True)

    # 10. 视频防抖（Vision + Metal GPU 亚像素运动防抖）配置与管线校验
    check("默认开启视频防抖(stabilize.enabled=True)", default_cfg.stabilize.enabled, True)
    check("默认防抖强度与边缘裁切比例", (default_cfg.stabilize.strength, default_cfg.stabilize.crop_percent), (65, 6))
    parsed_opts = server.parse_options({"trip": "demo", "stabilize": True, "stabilizeStrength": 80, "stabilizeCropPercent": 8})
    check("ExportOptions支持防抖参数解析", (parsed_opts.stabilize, parsed_opts.stabilize_strength, parsed_opts.stabilize_crop_percent), (True, 80, 8))
    ff_filter = overlay_atlas.build_ffmpeg_atlas_filter(
        renderer.plan,
        speed=1.0,
        fps=15,
        source_width=3840,
        source_height=2160,
        target_width=3840,
        target_height=2160,
        stabilize=True,
        stabilize_crop_percent=6,
    )
    check("FFmpeg回退滤镜链包含deshake防抖", "deshake=" in ff_filter, True)
    check("前端包含视频防抖设置控件", "stab-enabled" in studio_html and "stab-strength" in studio_html and "stab-crop" in studio_html, True)

    # 11. 倍速播放、倍速导出与 FPS 设置（默认 60fps）校验
    check("默认FPS为60", (server.DEFAULT_FPS, default_cfg.fps, default_cfg.playback_speed), (60, 60, 1.0))
    check("normalized_fps支持60与120高帧率", (server.normalized_fps(60), server.normalized_fps(120), server.normalized_fps(None)), (60, 120, 60))
    speed_cfg = server.layout_from_dict({"fps": 60, "playbackSpeed": 4.0})
    check("TripLayout持久化支持fps与playbackSpeed", (speed_cfg.fps, speed_cfg.playback_speed), (60, 4.0))
    check("前端包含倍速播放与FPS选择及一键导出按钮", "layoutSpeedSelect" in studio_html and "layoutFpsSelect" in studio_html and "layoutExportButton" in studio_html, True)

    # 12. 片头全屏地图（默认 3s 高德卫星轨迹图 + 3s 道路轨迹图，默认完整行程轨迹 scaleMeters=0，支持比例尺修改与配置保存）校验
    check(
        "默认开启片头全屏地图(3s卫星+3s道路, scaleMeters=0完整轨迹)",
        (
            default_cfg.intro_map.enabled,
            default_cfg.intro_map.satellite_seconds,
            default_cfg.intro_map.road_seconds,
            default_cfg.intro_map.scale_meters,
        ),
        (True, 3, 3, 0),
    )
    custom_intro_cfg = server.layout_from_dict(
        {
            "introMap": {
                "enabled": True,
                "satelliteSeconds": 4,
                "roadSeconds": 2,
                "scaleMeters": 1200,
            }
        }
    )
    intro_roundtrip = server.layout_to_dict(custom_intro_cfg).get("introMap", {})
    check(
        "introMap配置序列化与反序列化往返一致",
        (
            intro_roundtrip.get("enabled"),
            intro_roundtrip.get("satelliteSeconds"),
            intro_roundtrip.get("roadSeconds"),
            intro_roundtrip.get("scaleMeters"),
        ),
        (True, 4, 2, 1200),
    )
    parsed_intro_opts = server.parse_options(
        {
            "trip": "demo",
            "introMap": True,
            "introSatelliteSeconds": 3,
            "introRoadSeconds": 3,
            "introScaleMeters": 800,
        }
    )
    check(
        "ExportOptions支持片头地图参数解析",
        (
            parsed_intro_opts.intro_map,
            parsed_intro_opts.intro_satellite_seconds,
            parsed_intro_opts.intro_road_seconds,
            parsed_intro_opts.intro_scale_meters,
        ),
        (True, 3, 3, 800),
    )
    sat_intro_img = overlay_atlas.render_fullscreen_intro_map_frame(
        layout=default_cfg,
        points=pts,
        width=640,
        height=360,
        map_style="satellite",
        scale_meters=0.0,
        amap_base_fn=lambda *a, **kw: None,
        title_text="测试行程",
        distance_text="全程 5.2 km",
        duration_text="时长 00:08:30",
    )
    road_intro_img = overlay_atlas.render_fullscreen_intro_map_frame(
        layout=default_cfg,
        points=pts,
        width=640,
        height=360,
        map_style="road",
        scale_meters=600.0,
        amap_base_fn=lambda *a, **kw: None,
        title_text="测试行程",
        distance_text="全程 5.2 km",
        duration_text="时长 00:08:30",
    )
    check("片头全屏卫星图与道路图渲染尺寸正确", (sat_intro_img.size, road_intro_img.size), ((640, 360), (640, 360)))
    check(
        "前端包含片头全屏地图与比例尺设置控件",
        "intro-map-enabled" in studio_html
        and "intro-scale-meters" in studio_html
        and "intro-sat-sec" in studio_html
        and "intro-road-sec" in studio_html,
        True,
    )
    check(
        "卫星图注记层采用白字黑边高对比LUT且不二次压暗",
        (server._SAT_LABEL_INV_LUT[140], server._SAT_LABEL_INV_LUT[250], renderer._sat_r_lut[200]),
        (255, 12, 200),
    )

    # 13. 上视频下地图（split）手机竖屏拼接模式 + 最高速度点 + 测速雷达限速图标 + 10s:50s 周期丝滑缩放校验
    split_cfg = server.default_split_portrait_layout()
    check("默认split模式为9:16竖屏(1080x1920)且视频占38%", (split_cfg.layout_mode, split_cfg.width, split_cfg.height, split_cfg.split_video_percent), ("split", 1080, 1920, 38))
    split_plan = overlay_atlas.build_atlas_plan(split_cfg, 1080, 1920)
    check("split模式Atlas仅包含gauge与map且不含HUD/白底栏/文字字段", [r.name for r in split_plan.regions], ["gauge", "map"])
    map_reg = next(r for r in split_plan.regions if r.name == "map")
    check("split模式地图铺满下半屏", (map_reg.screen_x, map_reg.width, map_reg.screen_y + map_reg.height), (0, 1080, 1920))

    # 校验 10s 全程轨迹图 : 50s 局部当前位置图周期平滑过渡曲线
    w_overview = overlay_widgets.cycle_overview_blend_factor(3.0, 10.0, 50.0)
    w_trans_in = overlay_widgets.cycle_overview_blend_factor(9.1, 10.0, 50.0)
    w_detail = overlay_widgets.cycle_overview_blend_factor(30.0, 10.0, 50.0)
    w_trans_out = overlay_widgets.cycle_overview_blend_factor(59.1, 10.0, 50.0)
    w_next_cycle = overlay_widgets.cycle_overview_blend_factor(63.0, 10.0, 50.0)
    check("周期第3s处于全程总览(w=1.0)", w_overview, 1.0)
    check("周期第9.1s处于平滑推进过渡(0<w<1)", 0.05 < w_trans_in < 0.95, True)
    check("周期第30s处于局部固定比例尺跟随(w=0.0)", w_detail, 0.0)
    check("周期第59.1s处于平滑拉远过渡(0<w<1)", 0.05 < w_trans_out < 0.95, True)
    check("下一周期第63s回到全程总览(w=1.0)", w_next_cycle, 1.0)

    # 对数插值墨卡托窗口连续性校验
    pts_long = [
        server.TrackPoint(0, 1000, 30.20, 120.10, 55.0),
        server.TrackPoint(5000, 6000, 30.25, 120.16, 136.0),
        server.TrackPoint(10000, 11000, 30.32, 120.24, 72.0),
    ]
    f_local = overlay_widgets.route_mercator_frame(pts_long, scale_meters=220, center=pts_long[1], overview_blend=0.0)
    f_mid = overlay_widgets.route_mercator_frame(pts_long, scale_meters=220, center=pts_long[1], overview_blend=0.5)
    f_full = overlay_widgets.route_mercator_frame(pts_long, scale_meters=220, center=pts_long[1], overview_blend=1.0)
    check("平滑缩放中间态跨度严格介于局部与全程之间", f_local is not None and f_mid is not None and f_full is not None and f_local[3] < f_mid[3] < f_full[3], True)

    test_radars = [server.TrackRadar(latitude=30.251, longitude=120.161, speed_limit_kmh=80, camera_type=0)]
    split_html = server.overlay_html_for(
        split_cfg,
        {"time": "2026-10-30 10:00:00", "address": "中河高架", "coordinates": "30.25,120.16"},
        136.0,
        1080,
        1920,
        5000,
        pts_long,
        track_radars=test_radars,
        output_elapsed_sec=25.0,
    ) or ""
    check("split模式HTML包含闪动定位圈、最高速度点、雷达限速胶囊与里程胶囊且不含HUD", (
        "is-split-map" in split_html
        and "car-pulse-marker" in split_html
        and "map-max-speed-dot" in split_html
        and "radar-limit-pill" in split_html
        and "split-distance-pill" in split_html
        and 'id="widget-timing"' not in split_html
    ), True)

    split_renderer = overlay_atlas.NativeStudioAtlasRenderer(split_cfg, 540, 960)
    split_atlas = split_renderer.render_atlas_frame(
        values={"time": "2026-10-30 10:00:00", "address": "中河高架"},
        telemetry={"duration": "00:00:05", "distance": "620 m / 13.2 km"},
        progress_ratio=0.5,
        speed_kmh=136.0,
        elapsed_ms=5000,
        track_points=pts_long,
        amap_base_fn=None,
        point_at_fn=server.point_at,
        radars=test_radars,
        output_elapsed_sec=25.0,
    )
    check("split模式紧凑图集正常渲染", split_atlas.size, (split_renderer.plan.atlas_width, split_renderer.plan.atlas_height))

    # 校验家庭隐私保护模式默认开启且首尾截断 1km
    priv_pts = [
        server.TrackPoint(
            elapsed_ms=i * 1000,
            time_ms=1700000000000 + i * 1000,
            latitude=30.0000 + i * 0.001,  # 约 111.2m / 秒，100 个点共约 11km
            longitude=120.0000,
            speed_kmh=80.0,
        )
        for i in range(101)
    ]
    p_start, p_end, p_trimmed = server.compute_privacy_trim_bounds(
        priv_pts,
        100_000,
        enabled=True,
        trim_meters=1000.0,
    )
    _, trimmed_total_m, _ = server.track_progress(p_trimmed, p_end)
    _, raw_total_m, _ = server.track_progress(priv_pts, 100_000)
    check("隐私模式默认开启", bool(split_cfg.privacy.enabled and split_cfg.privacy.trim_meters == 1000), True)
    check("隐私模式开头截断约1km对应的毫秒数", 8_000 <= p_start <= 10_500, True)
    check("隐私模式结尾截断约1km对应的毫秒数", 89_500 <= p_end <= 92_000, True)
    check("隐私模式裁剪后轨迹总里程减少约2km", abs((raw_total_m - trimmed_total_m) - 2000.0) < 120.0, True)

    # 校验原视频分辨率显示与导出视频分辨率多档位调节（横屏/竖屏/上视频下地图）
    float_land_cfg = server.default_layout()
    float_land_cfg.layout_mode = "float"
    float_land_cfg.width = 1920
    float_land_cfg.height = 1080
    float_land_cfg.export_resolution = "source"
    check("横屏float跟随原片4K保持3840x2160", server.resolve_export_dimensions(3840, 2160, float_land_cfg), (3840, 2160))
    float_land_cfg.export_resolution = "1440p"
    check("横屏float导出2K为2560x1440", server.resolve_export_dimensions(3840, 2160, float_land_cfg), (2560, 1440))
    float_land_cfg.export_resolution = "1080p"
    check("横屏float导出1080p为1920x1080", server.resolve_export_dimensions(3840, 2160, float_land_cfg), (1920, 1080))
    float_land_cfg.export_resolution = "720p"
    check("横屏float导出720p为1280x720", server.resolve_export_dimensions(3840, 2160, float_land_cfg), (1280, 720))

    split_cfg.export_resolution = "source"
    check("竖屏split跟随原片4K为2160x3840", server.resolve_export_dimensions(3840, 2160, split_cfg), (2160, 3840))
    split_cfg.export_resolution = "1440p"
    check("竖屏split导出2K为1440x2560", server.resolve_export_dimensions(3840, 2160, split_cfg), (1440, 2560))
    split_cfg.export_resolution = "1080p"
    check("竖屏split导出1080p为1080x1920", server.resolve_export_dimensions(3840, 2160, split_cfg), (1080, 1920))
    split_cfg.export_resolution = "720p"
    check("竖屏split导出720p为720x1280", server.resolve_export_dimensions(3840, 2160, split_cfg), (720, 1280))


def main() -> int:
    server = load_server()
    test_caption_text(server)
    test_radar_line_keeps_address(server)
    test_timeline_cues(server)
    test_parse_cli_json(server)
    test_layout_roundtrip(server)
    test_layout_save_keeps_newer_revision(server)
    test_captions_disabled(server)
    test_overlay_matches_reference()
    test_remote_frame_index()
    test_local_library_status(server)
    test_library_trips_without_phone(server)
    test_preview_media_range(server)
    test_degree_coordinates(server)
    test_join_track_segments(server)
    test_extract_preview_clip(server)
    test_bugfixes_and_forza_gauge(server)
    if failures:
        print(f"失败 {len(failures)} 项：")
        for item in failures:
            print("  -", item)
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
