#!/usr/bin/env python3
"""ADB smoke test: recording continues after CarSpeed is sent to background."""

from __future__ import annotations

import argparse
import json
import subprocess
import time


PACKAGE = "com.thomas.carspeed"
RECEIVER = f"{PACKAGE}/com.thomas.carspeed.DebugCommandReceiver"
ACTION = "com.thomas.carspeed.debug.COMMAND"
DEBUG_DIR = f"/sdcard/Android/data/{PACKAGE}/files/debug"
VIDEO_DIR = f"/sdcard/Android/data/{PACKAGE}/files/Movies/CarSpeed"


def adb(*args: str) -> str:
    return subprocess.check_output(["adb", *args], text=True, stderr=subprocess.STDOUT)


def command(cmd: str) -> None:
    adb("shell", "am", "broadcast", "-n", RECEIVER, "-a", ACTION, "--es", "cmd", cmd)


def read_json(name: str) -> dict:
    return json.loads(adb("shell", "cat", f"{DEBUG_DIR}/{name}"))


def list_videos() -> dict:
    command("list_videos")
    return read_json("videos.json")


def dump_state() -> dict:
    command("dump_state")
    return read_json("state.json")


def remove_group(video_name: str) -> None:
    stem = video_name.removesuffix(".mp4")
    adb(
        "shell",
        "rm",
        "-f",
        f"{VIDEO_DIR}/{stem}.mp4",
        f"{VIDEO_DIR}/{stem}.srt",
        f"{VIDEO_DIR}/{stem}.track.json",
        f"{VIDEO_DIR}/{stem}.favorite",
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--record-seconds", type=int, default=8)
    parser.add_argument("--cleanup", action=argparse.BooleanOptionalAction, default=True)
    args = parser.parse_args()

    adb(
        "shell",
        "am",
        "start",
        "-n",
        f"{PACKAGE}/.CameraRecordActivity",
        "-a",
        "android.intent.action.MAIN",
        "-c",
        "android.intent.category.LAUNCHER",
    )
    time.sleep(1)
    before = {video["name"] for video in list_videos().get("videos", [])}
    time.sleep(1)
    command("start_recording")
    time.sleep(2)
    command("background_app")
    time.sleep(max(1, args.record_seconds))
    state = dump_state()
    still_recording = bool(state.get("recording"))
    command("stop_recording")
    time.sleep(3)
    after_videos = list_videos().get("videos", [])
    new_videos = [video for video in after_videos if video.get("name") not in before]
    if args.cleanup:
        for video in new_videos:
            remove_group(video["name"])

    result = {
        "ok": still_recording and bool(new_videos),
        "recordingWhileBackground": still_recording,
        "newVideos": new_videos,
        "cleanup": args.cleanup,
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
