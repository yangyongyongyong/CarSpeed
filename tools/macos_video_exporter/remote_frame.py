"""从手机上的 MP4 抽一帧预览，不把整段录像拉到电脑。

行车文件的索引（moov）在末尾，体积只有几十 KB。先读索引算出这一帧在文件里的字节位置，
再用 adb 按区间把关键帧到目标帧的数据取回来，本地解码。1GB 以上的录像也能在一秒内出图。
"""

from __future__ import annotations

import shlex
import struct
import subprocess
import threading
from dataclasses import dataclass
from pathlib import Path


_ADB_LOCK = threading.Lock()
_CACHE_LOCK = threading.Lock()
_INDEX_CACHE: dict[str, tuple[int, "VideoIndex"]] = {}
_GOP_CACHE: dict[tuple[str, int], tuple[int, int, bytes]] = {}


@dataclass
class VideoIndex:
    timescale: int
    sample_count: int
    sizes: list[int]
    prefix: list[int]
    sync_samples: list[int]
    time_entries: list[tuple[int, int]]
    chunk_offsets: list[int]
    samples_per_chunk: list[int]
    avcc: bytes
    nal_length: int


def sample_index_for_ticks(entries: list[tuple[int, int]], target: int, sample_count: int) -> int:
    """stts（每段样本数、时长）里，目标 tick 落在第几个样本。"""
    if sample_count <= 0:
        return 0
    acc = 0
    index = 0
    for count, delta in entries:
        if count <= 0:
            continue
        if delta <= 0:
            index += count
            continue
        span = count * delta
        if acc + span > target:
            index += max(0, (target - acc) // delta)
            break
        acc += span
        index += count
    else:
        index = sample_count - 1
    return max(0, min(sample_count - 1, index))


def previous_sync(sync_samples: list[int], sample: int) -> int:
    """不大于 sample 的最近关键帧。sync 已是 0 起算。"""
    key = 0
    for item in sync_samples:
        if item <= sample:
            key = item
        else:
            break
    return key


def avcc_and_samples_to_annexb(avcc: bytes, samples: bytes) -> bytes:
    """avcC 参数集 + AVCC 长度前缀样本，转成 ffmpeg 能直接吃的 Annex-B。"""
    if len(avcc) < 7:
        raise RuntimeError("avcC 太短")
    nal_length = (avcc[4] & 3) + 1
    cursor = 5
    sps_count = avcc[cursor] & 31
    cursor += 1
    out = bytearray()
    for _ in range(sps_count):
        if cursor + 2 > len(avcc):
            break
        length = struct.unpack(">H", avcc[cursor : cursor + 2])[0]
        cursor += 2
        out += b"\x00\x00\x00\x01" + avcc[cursor : cursor + length]
        cursor += length
    if cursor >= len(avcc):
        raise RuntimeError("avcC 缺少 PPS")
    pps_count = avcc[cursor]
    cursor += 1
    for _ in range(pps_count):
        if cursor + 2 > len(avcc):
            break
        length = struct.unpack(">H", avcc[cursor : cursor + 2])[0]
        cursor += 2
        out += b"\x00\x00\x00\x01" + avcc[cursor : cursor + length]
        cursor += length

    pos = 0
    while pos + nal_length <= len(samples):
        length = int.from_bytes(samples[pos : pos + nal_length], "big")
        pos += nal_length
        if length <= 0 or pos + length > len(samples):
            break
        out += b"\x00\x00\x00\x01" + samples[pos : pos + length]
        pos += length
    return bytes(out)


def _boxes(data: bytes):
    """遍历 MP4 box 二进制块，产出 (box_type, payload)。"""
    pos = 0
    while pos + 8 <= len(data):
        size, box_type = struct.unpack(">I4s", data[pos : pos + 8])
        header = 8
        if size == 1:
            if pos + 16 > len(data):
                break
            size = struct.unpack(">Q", data[pos + 8 : pos + 16])[0]
            header = 16
        elif size == 0:
            size = len(data) - pos
        if size < header or pos + size > len(data):
            break
        yield box_type, data[pos + header : pos + size]
        pos += size


def _find(data: bytes, path: tuple[bytes, ...]) -> bytes | None:
    """按路径递归查找指定的 MP4 子 box。"""
    if not path:
        return data
    for box_type, payload in _boxes(data):
        if box_type == path[0]:
            found = _find(payload, path[1:])
            if found is not None:
                return found
    return None


def _fullbox_body(payload: bytes) -> bytes:
    """跳过 FullBox 的 4 字节 version/flags 头，返回数据体。"""
    return payload[4:] if len(payload) >= 4 else b""


def _u32_count(body: bytes) -> tuple[int, bytes]:
    """读取 FullBox 开头的 32 位条目计数及剩余字节。"""
    if len(body) < 4:
        return 0, b""
    count = struct.unpack(">I", body[:4])[0]
    return count, body[4:]


def parse_video_index(moov_box: bytes) -> VideoIndex:
    """解析带 8 字节头的 moov，抽出视频轨的样本表和 avcC。"""
    moov_payload = None
    for box_type, payload in _boxes(moov_box):
        if box_type == b"moov":
            moov_payload = payload
            break
    if moov_payload is None:
        raise RuntimeError("没有 moov")

    trak = None
    for box_type, payload in _boxes(moov_payload):
        if box_type != b"trak":
            continue
        handler = _find(payload, (b"mdia", b"hdlr"))
        if handler and b"vide" in handler[:16]:
            trak = payload
            break
    if trak is None:
        raise RuntimeError("没有视频轨")

    mdhd = _find(trak, (b"mdia", b"mdhd"))
    stbl = _find(trak, (b"mdia", b"minf", b"stbl"))
    if mdhd is None or stbl is None:
        raise RuntimeError("视频轨缺少样本表")
    version = mdhd[0]
    if version == 1:
        timescale = struct.unpack(">I", mdhd[20:24])[0]
    else:
        timescale = struct.unpack(">I", mdhd[12:16])[0]

    stts = _find(stbl, (b"stts",))
    stss = _find(stbl, (b"stss",))
    stsz = _find(stbl, (b"stsz",))
    stsc = _find(stbl, (b"stsc",))
    stco = _find(stbl, (b"stco",))
    co64 = _find(stbl, (b"co64",))
    stsd = _find(stbl, (b"stsd",))
    if not all((stts, stsz, stsc, stsd)) or (stco is None and co64 is None):
        raise RuntimeError("样本表不完整")

    time_body = _fullbox_body(stts)
    time_count, time_rest = _u32_count(time_body)
    time_entries = [
        (count, delta)
        for count, delta in struct.iter_unpack(">II", time_rest[: time_count * 8])
    ]

    sync_samples = [0]
    if stss:
        sync_body = _fullbox_body(stss)
        sync_count, sync_rest = _u32_count(sync_body)
        if sync_count:
            sync_samples = [item - 1 for item in struct.unpack(f">{sync_count}I", sync_rest[: sync_count * 4])]

    size_body = _fullbox_body(stsz)
    sample_size, sample_count = struct.unpack(">II", size_body[:8])
    if sample_size:
        sizes = [sample_size] * sample_count
    else:
        sizes = list(struct.unpack(f">{sample_count}I", size_body[8 : 8 + sample_count * 4]))
    prefix = [0]
    running = 0
    for size in sizes:
        running += size
        prefix.append(running)

    sc_body = _fullbox_body(stsc)
    sc_count, sc_rest = _u32_count(sc_body)
    sc_entries = list(struct.iter_unpack(">III", sc_rest[: sc_count * 12]))

    if co64 is not None:
        chunk_body = _fullbox_body(co64)
        chunk_count, chunk_rest = _u32_count(chunk_body)
        chunk_offsets = list(struct.unpack(f">{chunk_count}Q", chunk_rest[: chunk_count * 8]))
    else:
        chunk_body = _fullbox_body(stco)
        chunk_count, chunk_rest = _u32_count(chunk_body)
        chunk_offsets = list(struct.unpack(f">{chunk_count}I", chunk_rest[: chunk_count * 4]))

    samples_per_chunk: list[int] = []
    for index, (first_chunk, per_chunk, _desc) in enumerate(sc_entries):
        next_first = sc_entries[index + 1][0] if index + 1 < len(sc_entries) else len(chunk_offsets) + 1
        samples_per_chunk.extend([per_chunk] * max(0, next_first - first_chunk))

    avcc_at = stsd.find(b"avcC")
    if avcc_at < 4:
        raise RuntimeError("预览只支持 H.264 录像")
    avcc_size = struct.unpack(">I", stsd[avcc_at - 4 : avcc_at])[0]
    avcc = stsd[avcc_at + 4 : avcc_at - 4 + avcc_size]
    nal_length = (avcc[4] & 3) + 1 if len(avcc) > 4 else 4
    return VideoIndex(
        timescale=max(1, timescale),
        sample_count=sample_count,
        sizes=sizes,
        prefix=prefix,
        sync_samples=sync_samples,
        time_entries=time_entries,
        chunk_offsets=chunk_offsets,
        samples_per_chunk=samples_per_chunk or [sample_count],
        avcc=avcc,
        nal_length=nal_length,
    )


def sample_span(index: VideoIndex, offset_ms: int) -> tuple[int, int, int, int]:
    """返回关键帧序号、目标样本序号、文件偏移、需要读取的字节数。"""
    ticks = max(0, int(offset_ms)) * index.timescale // 1000
    sample = sample_index_for_ticks(index.time_entries, ticks, index.sample_count)
    key = previous_sync(index.sync_samples, sample)
    start = _sample_offset(index, key)
    end = _sample_offset(index, sample) + (index.sizes[sample] if sample < len(index.sizes) else 0)
    length = max(0, end - start)
    return key, sample, start, length


def extract_video_samples(raw_span: bytes, index: VideoIndex, key: int, sample: int, span_start: int) -> bytes:
    """从包含交织音频 chunk 的原始文件区间中仅抽出 key..sample 的连续视频样本字节。"""
    if len(index.chunk_offsets) <= 1:
        expected = index.prefix[sample + 1] - index.prefix[key]
        return raw_span[:expected]
    out = bytearray()
    for current in range(key, sample + 1):
        offset = _sample_offset(index, current) - span_start
        size = index.sizes[current] if current < len(index.sizes) else 0
        if offset < 0 or size <= 0 or offset + size > len(raw_span):
            break
        out += raw_span[offset : offset + size]
    return bytes(out)


def _sample_offset(index: VideoIndex, sample: int) -> int:
    """计算第 sample 个视频帧在 MP4 文件中的字节偏移量。"""
    if len(index.chunk_offsets) == 1:
        return index.chunk_offsets[0] + index.prefix[sample]
    cursor = 0
    for chunk_index, per_chunk in enumerate(index.samples_per_chunk):
        if cursor + per_chunk > sample:
            local = sample - cursor
            return index.chunk_offsets[chunk_index] + (
                index.prefix[cursor + local] - index.prefix[cursor]
            )
        cursor += per_chunk
    raise RuntimeError("样本超出分块表")


def _adb(*args: str, timeout: int = 30) -> subprocess.CompletedProcess[bytes]:
    """加锁执行 adb 命令，避免多个预览请求并发抢占 USB 通道。"""
    with _ADB_LOCK:
        return subprocess.run(["adb", *args], capture_output=True, check=False, timeout=timeout)


def remote_file_size(path: str) -> int:
    """通过 adb stat 读取手机端录像文件的字节大小。"""
    result = _adb("shell", f"stat -c %s {shlex.quote(path)}", timeout=20)
    text = result.stdout.decode("utf-8", "replace").strip()
    if result.returncode != 0 or not text.isdigit():
        detail = result.stderr.decode("utf-8", "replace").strip()
        raise RuntimeError(detail or "读不到录像大小")
    return int(text)


def read_remote_bytes(path: str, start: int, length: int) -> bytes:
    """按字节区间从手机端文件读取指定长度的数据块。"""
    if length <= 0:
        return b""
    block = 65536
    skip = start // block
    lead = start - skip * block
    count = (lead + length + block - 1) // block
    script = (
        f"dd if={shlex.quote(path)} bs={block} skip={skip} count={count} 2>/dev/null"
    )
    result = _adb("exec-out", script, timeout=40)
    data = result.stdout[lead : lead + length]
    if len(data) < length:
        raise RuntimeError(f"录像区间只读到 {len(data)}/{length} 字节")
    return data


def _load_index(path: str) -> VideoIndex:
    """读取并缓存手机端 MP4 尾部的 moov 视频轨索引。"""
    size = remote_file_size(path)
    with _CACHE_LOCK:
        cached = _INDEX_CACHE.get(path)
    if cached and cached[0] == size:
        return cached[1]
    window = 8 * 1024 * 1024
    tail = read_remote_bytes(path, max(0, size - window), min(window, size))
    moov_at = tail.rfind(b"moov")
    if moov_at < 4:
        raise RuntimeError("录像末尾没有索引")
    atom_size = struct.unpack(">I", tail[moov_at - 4 : moov_at])[0]
    if atom_size < 8 or moov_at - 4 + atom_size > len(tail):
        raise RuntimeError("录像索引不完整")
    moov = tail[moov_at - 4 : moov_at - 4 + atom_size]
    index = parse_video_index(moov)
    with _CACHE_LOCK:
        if len(_INDEX_CACHE) > 6:
            _INDEX_CACHE.clear()
        _INDEX_CACHE[path] = (size, index)
    return index


def _gop_bytes(path: str, index: VideoIndex, key: int, sample: int, start: int, length: int) -> bytes:
    """读取并缓存当前 GOP 的文件字节区间，并剔除中间交织的音频 chunk。"""
    with _CACHE_LOCK:
        cached = _GOP_CACHE.get((path, key))
    if cached and cached[1] == start and cached[0] >= sample and len(cached[2]) >= length:
        return extract_video_samples(cached[2][:length], index, key, sample, start)
    if cached and cached[1] == start and len(cached[2]) < length:
        extra = read_remote_bytes(path, start + len(cached[2]), length - len(cached[2]))
        data = cached[2] + extra
    else:
        data = read_remote_bytes(path, start, length)
    with _CACHE_LOCK:
        if len(_GOP_CACHE) > 4:
            _GOP_CACHE.clear()
        _GOP_CACHE[(path, key)] = (sample, start, data)
    return extract_video_samples(data, index, key, sample, start)


def decode_h264_jpeg(annex: bytes, frame_index: int, max_width: int) -> bytes:
    """解码 Annex-B，取出指定帧并缩到预览宽度。失败时退回第一帧（关键帧）。"""
    frame_index = max(0, frame_index)
    attempts = [
        f"select=eq(n\\,{frame_index}),scale={max_width}:-2",
        f"scale={max_width}:-2",
    ]
    error = ""
    for index, video_filter in enumerate(attempts):
        command = [
            "ffmpeg", "-v", "error", "-hide_banner",
            "-f", "h264", "-i", "pipe:0",
            "-vf", video_filter,
            "-frames:v", "1",
            "-f", "image2pipe", "-vcodec", "mjpeg", "pipe:1",
        ]
        result = subprocess.run(command, input=annex, capture_output=True, check=False, timeout=25)
        if result.returncode == 0 and result.stdout:
            return result.stdout
        error = result.stderr.decode("utf-8", "replace").strip()
        if index == 0 and frame_index == 0:
            break
    raise RuntimeError(error or "解码预览帧失败")


def frame_jpeg(
    remote_path: str,
    offset_ms: int,
    *,
    max_width: int = 1280,
    cache_dir: Path | None = None,
) -> bytes:
    """抽远程录像在 offset_ms 附近的一帧 JPEG。同一秒会落在磁盘缓存里。"""
    slot = max(0, int(offset_ms) // 500) * 500
    cache_file = None
    if cache_dir is not None:
        digest = __import__("hashlib").sha1(remote_path.encode("utf-8")).hexdigest()[:16]
        cache_file = cache_dir / digest / f"{slot}.jpg"
        if cache_file.is_file() and cache_file.stat().st_size > 0:
            return cache_file.read_bytes()
    index = _load_index(remote_path)
    key, sample, start, length = sample_span(index, slot)
    payload = _gop_bytes(remote_path, index, key, sample, start, length)
    annex = avcc_and_samples_to_annexb(index.avcc, payload)
    jpeg = decode_h264_jpeg(annex, sample - key, max_width)
    if cache_file is not None:
        cache_file.parent.mkdir(parents=True, exist_ok=True)
        temporary = cache_file.with_suffix(".tmp")
        temporary.write_bytes(jpeg)
        temporary.replace(cache_file)
    return jpeg
