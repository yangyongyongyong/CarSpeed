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


def test_captions_disabled(server) -> None:
    layout = server.default_layout()
    layout.caption.enabled = False
    # 关闭字幕时不建轨，直接返回 None（不调用任何 CLI）。
    result = server.import_caption_track(None, Path("/nonexistent"), Path("/tmp/x"), lambda *_: None, layout=layout)
    check("关闭字幕不建轨", result, None)


def main() -> int:
    server = load_server()
    test_caption_text(server)
    test_radar_line_keeps_address(server)
    test_timeline_cues(server)
    test_parse_cli_json(server)
    test_layout_roundtrip(server)
    test_captions_disabled(server)
    if failures:
        print(f"失败 {len(failures)} 项：")
        for item in failures:
            print("  -", item)
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
