#!/usr/bin/env python3
"""ADB visual freeze checker for CarSpeed camera preview.

It samples screenshots, crops the PreviewView bounds reported by uiautomator,
and hashes raw crop pixels. Identical hashes across all samples means the
rendered preview is probably frozen.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import struct
import subprocess
import sys
import time
import zlib
from typing import cast


def adb(*args: str, binary: bool = False) -> bytes | str:
    output = subprocess.check_output(["adb", *args])
    return output if binary else output.decode("utf-8", errors="replace")


def dump_ui() -> str:
    adb("shell", "uiautomator", "dump", "/sdcard/carspeed-freeze-ui.xml")
    return cast(str, adb("shell", "cat", "/sdcard/carspeed-freeze-ui.xml"))


def find_bounds(xml: str, target_id: str) -> tuple[int, int, int, int]:
    pattern = rf'resource-id="com\.thomas\.carspeed:id/{re.escape(target_id)}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    match = re.search(pattern, xml)
    if not match:
        raise RuntimeError(f"cannot find bounds for {target_id}")
    return tuple(int(v) for v in match.groups())  # type: ignore[return-value]


def paeth(a: int, b: int, c: int) -> int:
    p = a + b - c
    pa = abs(p - a)
    pb = abs(p - b)
    pc = abs(p - c)
    if pa <= pb and pa <= pc:
        return a
    if pb <= pc:
        return b
    return c


def decode_png(raw_png: bytes) -> tuple[int, int, int, bytes]:
    raw_png = raw_png.replace(b"\r\n", b"\n") if not raw_png.startswith(b"\x89PNG") else raw_png
    if not raw_png.startswith(b"\x89PNG\r\n\x1a\n"):
        raise RuntimeError("screenshot is not a PNG")

    offset = 8
    width = height = color_type = None
    idat = bytearray()
    while offset < len(raw_png):
        length = struct.unpack(">I", raw_png[offset : offset + 4])[0]
        chunk_type = raw_png[offset + 4 : offset + 8]
        data = raw_png[offset + 8 : offset + 8 + length]
        offset += 12 + length
        if chunk_type == b"IHDR":
            width, height, bit_depth, color_type, _, _, _ = struct.unpack(">IIBBBBB", data)
            if bit_depth != 8 or color_type not in (2, 6):
                raise RuntimeError(f"unsupported PNG format bit_depth={bit_depth} color_type={color_type}")
        elif chunk_type == b"IDAT":
            idat.extend(data)
        elif chunk_type == b"IEND":
            break

    if width is None or height is None or color_type is None:
        raise RuntimeError("invalid PNG")

    channels = 4 if color_type == 6 else 3
    stride = width * channels
    inflated = zlib.decompress(bytes(idat))
    rows = bytearray(height * stride)
    src = 0
    for y in range(height):
        filter_type = inflated[src]
        src += 1
        row = bytearray(inflated[src : src + stride])
        src += stride
        prev = rows[(y - 1) * stride : y * stride] if y > 0 else bytearray(stride)
        for x in range(stride):
            left = row[x - channels] if x >= channels else 0
            up = prev[x]
            up_left = prev[x - channels] if x >= channels else 0
            if filter_type == 1:
                row[x] = (row[x] + left) & 0xFF
            elif filter_type == 2:
                row[x] = (row[x] + up) & 0xFF
            elif filter_type == 3:
                row[x] = (row[x] + ((left + up) // 2)) & 0xFF
            elif filter_type == 4:
                row[x] = (row[x] + paeth(left, up, up_left)) & 0xFF
            elif filter_type != 0:
                raise RuntimeError(f"unsupported PNG filter {filter_type}")
        rows[y * stride : (y + 1) * stride] = row
    return width, height, channels, bytes(rows)


def crop_hash(pixels: bytes, width: int, height: int, channels: int, bounds: tuple[int, int, int, int]) -> str:
    left, top, right, bottom = bounds
    left = max(0, min(width - 1, left))
    right = max(left + 1, min(width, right))
    top = max(0, min(height - 1, top))
    bottom = max(top + 1, min(height, bottom))
    digest = hashlib.sha256()
    stride = width * channels
    for y in range(top, bottom):
        start = y * stride + left * channels
        end = y * stride + right * channels
        digest.update(pixels[start:end])
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", choices=("previewView", "fullscreenPreviewView"), default="previewView")
    parser.add_argument("--samples", type=int, default=4)
    parser.add_argument("--interval", type=float, default=0.8)
    args = parser.parse_args()

    xml = dump_ui()
    bounds = find_bounds(xml, args.target)
    hashes: list[str] = []
    for _ in range(args.samples):
        png = adb("exec-out", "screencap", "-p", binary=True)
        width, height, channels, pixels = decode_png(png)  # type: ignore[arg-type]
        hashes.append(crop_hash(pixels, width, height, channels, bounds))
        time.sleep(args.interval)

    unique_count = len(set(hashes))
    result = {
        "target": args.target,
        "bounds": bounds,
        "samples": args.samples,
        "uniqueHashes": unique_count,
        "frozenLikely": unique_count == 1,
        "hashes": hashes,
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 2 if unique_count == 1 else 0


if __name__ == "__main__":
    sys.exit(main())
