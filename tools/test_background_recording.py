#!/usr/bin/env python3
"""ADB smoke test for background dashcam recording and voice control service.

This catches regressions where Activity lifecycle teardown stops voice control, or
where backgrounding the app stops CameraX recording through a stale preview
surface.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import time


PACKAGE = "com.thomas.carspeed"
RECEIVER = f"{PACKAGE}/com.thomas.carspeed.DebugCommandReceiver"
ACTION = "com.thomas.carspeed.debug.COMMAND"
DEBUG_DIR = f"/sdcard/Android/data/{PACKAGE}/files/debug"
VIDEO_DIR = "/sdcard/Movies/CarSpeed"


def adb(*args: str) -> str:
    try:
        return subprocess.check_output(["adb", *args], text=True, stderr=subprocess.STDOUT)
    except subprocess.CalledProcessError as error:
        command_text = " ".join(["adb", *args])
        raise RuntimeError(f"ADB command failed: {command_text}\n{error.output}") from error


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


def assert_condition(condition: bool, message: str, state: dict | None = None) -> None:
    if condition:
        return
    details = ""
    if state is not None:
        details = "\n" + json.dumps(state, ensure_ascii=False, indent=2)
    raise AssertionError(f"{message}{details}")


def assert_voice_listening(state: dict, expected_recording: bool) -> None:
    voice = state.get("voicePhoto", {})
    assert_condition(state.get("recording") is expected_recording, "unexpected recording state", state)
    assert_condition(state.get("dashcamServiceAlive") is True, "DashcamService is not alive", state)
    assert_condition(voice.get("preferredEngine") == "vosk", "voice engine is not Vosk", state)
    assert_condition(voice.get("recognizerOwner") == "service", "voice recognizer is not service-owned", state)
    assert_condition(voice.get("recognizerListening") is True, "voice recognizer is not listening", state)
    assert_condition(voice.get("serviceListening") is True, "service voice flag is false", state)


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
    command("stop_recording")
    time.sleep(1)

    idle_foreground = dump_state()
    assert_voice_listening(idle_foreground, expected_recording=False)

    command("background_app")
    time.sleep(3)
    idle_background = dump_state()
    assert_voice_listening(idle_background, expected_recording=False)

    before = {video["name"] for video in list_videos().get("videos", [])}
    time.sleep(1)
    command("start_recording")
    time.sleep(2)
    recording_started = dump_state()
    assert_voice_listening(recording_started, expected_recording=True)

    command("background_app")
    time.sleep(max(1, args.record_seconds))
    state = dump_state()
    assert_voice_listening(state, expected_recording=True)
    command("stop_recording")
    time.sleep(3)
    after_videos = list_videos().get("videos", [])
    new_videos = [video for video in after_videos if video.get("name") not in before]
    if args.cleanup:
        for video in new_videos:
            remove_group(video["name"])

    result = {
        "ok": bool(new_videos),
        "idleForegroundVoice": idle_foreground.get("voicePhoto", {}),
        "idleBackgroundVoice": idle_background.get("voicePhoto", {}),
        "recordingStartedVoice": recording_started.get("voicePhoto", {}),
        "recordingWhileBackground": state.get("recording"),
        "recordingBackgroundVoice": state.get("voicePhoto", {}),
        "newVideos": new_videos,
        "cleanup": args.cleanup,
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
