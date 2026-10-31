"""用 headless Chrome 把覆盖层渲染成透明 PNG。

为什么不用 PIL 直接画：设计稿依赖 backdrop-filter（毛玻璃）、clip-path
（异形车标）、drop-shadow（发光）、background-clip: text（渐变数字）这些
CSS 特性，PIL 一个都做不了。与其用近似手法画个"像但不一样"的东西，
不如让浏览器自己把同一份 CSS 渲染出来——这样样式永远和设计稿一致。

流程：模板 + 数据 → 临时 HTML → Chrome 截图（透明背景）→ PIL 打开。
"""
from __future__ import annotations

import html as html_module
import math
import shutil
import subprocess
import tempfile
import threading
from pathlib import Path
from typing import Any

TEMPLATE_PATH = Path(__file__).resolve().parent / "overlay_template.html"
FONT_DIR = TEMPLATE_PATH.parent / "assets" / "fonts"
_TEMPLATE_CACHE: str | None = None
_SHARED_RENDER_DIR: Path | None = None
_RENDER_DIR_LOCK = threading.Lock()

# 参考稿用的两套字体。无头浏览器打开本地 HTML 时拉不到 Google Fonts，
# 必须用随仓库带的字体文件，否则数字和斜体会掉回系统字体。
FONT_FACES = (
    ("Chakra Petch", 500, "normal", "ChakraPetch-Medium.ttf"),
    ("Chakra Petch", 700, "normal", "ChakraPetch-Bold.ttf"),
    ("Chakra Petch", 500, "italic", "ChakraPetch-MediumItalic.ttf"),
    ("Chakra Petch", 700, "italic", "ChakraPetch-BoldItalic.ttf"),
    ("Orbitron", 500, "normal", "Orbitron-Medium.ttf"),
    ("Orbitron", 700, "normal", "Orbitron-Bold.ttf"),
    ("Orbitron", 900, "normal", "Orbitron-Black.ttf"),
)

# Chrome 常见安装位置，按顺序探测。
CHROME_CANDIDATES = (
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "/Applications/Chromium.app/Contents/MacOS/Chromium",
    "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
    "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser",
    "google-chrome",
    "chromium",
    "chromium-browser",
)


def find_chrome() -> str | None:
    """按候选顺序查找本机可用的 Chromium 系浏览器可执行文件。"""
    for candidate in CHROME_CANDIDATES:
        if candidate.startswith("/"):
            if Path(candidate).is_file():
                return candidate
        else:
            found = shutil.which(candidate)
            if found:
                return found
    return None


def _ensure_shared_render_dir() -> Path:
    """初始化并返回复用的渲染工作目录，避免每帧重复拷贝字体文件。"""
    global _SHARED_RENDER_DIR
    with _RENDER_DIR_LOCK:
        if _SHARED_RENDER_DIR is None or not _SHARED_RENDER_DIR.is_dir():
            _SHARED_RENDER_DIR = Path(tempfile.mkdtemp(prefix="carspeed-overlay-shared-"))
            fonts_dir = _SHARED_RENDER_DIR / "fonts"
            fonts_dir.mkdir(parents=True, exist_ok=True)
            for _, _, _, filename in FONT_FACES:
                source = FONT_DIR / filename
                target = fonts_dir / filename
                if source.is_file() and not target.is_file():
                    shutil.copy(source, target)
        return _SHARED_RENDER_DIR


def render_html_screenshot(
    html_path: Path,
    out_path: Path,
    width: int,
    height: int,
    chrome: str,
    virtual_time_budget_ms: int = 60,
) -> None:
    """截一张带透明背景的整页 PNG（启用 macOS Metal GPU 硬件光栅化加速）。"""
    profile_dir = html_path.parent / "profile"
    profile_dir.mkdir(parents=True, exist_ok=True)
    command = [
        chrome,
        "--headless",
        f"--user-data-dir={profile_dir}",
        "--hide-scrollbars",
        "--enable-gpu",
        "--ignore-gpu-blocklist",
        "--no-first-run",
        "--no-default-browser-check",
        "--disable-extensions",
        "--force-device-scale-factor=1",
        "--allow-file-access-from-files",
        # 关键：背景透明，否则截出来是白底，叠到视频上会盖住画面。
        "--default-background-color=00000000",
        f"--window-size={width},{height}",
        f"--screenshot={out_path}",
        # 等字体和布局稳定；内置字体 + 本地文件，不需要等网络。
        f"--virtual-time-budget={max(50, int(virtual_time_budget_ms))}",
        f"file://{html_path}",
    ]
    last_err = ""
    for _attempt in range(2):
        try:
            result = subprocess.run(command, capture_output=True, timeout=25)
            if out_path.is_file() and out_path.stat().st_size > 0:
                return
            last_err = (result.stderr or result.stdout or b"").decode("utf-8", "ignore").strip()
        except subprocess.TimeoutExpired:
            last_err = "Chrome 截图超时，自动重试"
    if not out_path.is_file():
        raise RuntimeError(f"Chrome 渲染失败：{last_err[:400]}")


def render_overlay_png_to_path(
    html_text: str,
    out_path: Path,
    width: int,
    height: int,
    chrome: str | None = None,
) -> bool:
    """直接将覆盖层 HTML 渲染为指定路径的透明 PNG 文件，省去 Python 二次解码与内存拷贝。"""
    chrome = chrome or find_chrome()
    if chrome is None:
        return False
    shared_dir = _ensure_shared_render_dir()
    with tempfile.TemporaryDirectory(prefix="frame-", dir=shared_dir) as frame_dir:
        frame_path = Path(frame_dir)
        fonts_link = frame_path / "fonts"
        if not fonts_link.exists():
            try:
                fonts_link.symlink_to(shared_dir / "fonts", target_is_directory=True)
            except OSError:
                shutil.copytree(shared_dir / "fonts", fonts_link, dirs_exist_ok=True)
        html_path = frame_path / "overlay.html"
        html_path.write_text(html_text, encoding="utf-8")
        render_html_screenshot(html_path, out_path, width, height, chrome)
    return out_path.is_file() and out_path.stat().st_size > 0


def font_face_css(base: str = "fonts/") -> str:
    """把参考稿的 Chakra Petch / Orbitron 指到字体文件。

    Chrome 截图用临时目录里的 fonts/。编辑器页面用 /assets/fonts/，
    否则 srcdoc 里的相对路径会指到工作室页面，字体又掉回去。
    """
    rules: list[str] = []
    for family, weight, style, filename in FONT_FACES:
        rules.append(
            "@font-face {"
            f" font-family: '{family}';"
            f" font-weight: {weight};"
            f" font-style: {style};"
            " font-display: block;"
            f" src: url('{base}{filename}') format('truetype');"
            " }"
        )
    return "\n".join(rules)


def build_overlay_html(
    *,
    canvas_width: int,
    canvas_height: int,
    accent_color: str,
    widgets_html: str,
    values: dict[str, str] | None = None,
    font_base: str = "fonts/",
) -> str:
    """根据画布尺寸、主题色与组件 HTML 组装完整覆盖层页面。"""
    # 字幕字段已经写进 widgets_html。不能再按字段名替换模板，
    # 否则某条字幕刚好叫 uiScale 就会把缩放系数冲掉。
    del values
    global _TEMPLATE_CACHE
    if _TEMPLATE_CACHE is None:
        _TEMPLATE_CACHE = TEMPLATE_PATH.read_text(encoding="utf-8")
    template = _TEMPLATE_CACHE
    ui_scale = max(0.5, canvas_height / 1080)
    replacements = {
        "canvasWidth": str(canvas_width),
        "canvasHeight": str(canvas_height),
        "accentColor": accent_color,
        "widgets": widgets_html,
        "fontFaces": font_face_css(font_base),
        "uiScale": f"{ui_scale:.4f}",
    }
    rendered = template
    for key, value in replacements.items():
        rendered = rendered.replace("{{" + key + "}}", value)
    return rendered


def render_overlay_png(
    html_text: str,
    width: int,
    height: int,
    chrome: str | None = None,
) -> Any:
    """把 HTML 渲染成 RGBA 图；没有 Chrome 时返回 None，调用方退回 PIL 绘制。"""
    from PIL import Image  # 局部导入，保持模块可独立测试

    chrome = chrome or find_chrome()
    if chrome is None:
        return None
    shared_dir = _ensure_shared_render_dir()
    with tempfile.TemporaryDirectory(prefix="frame-", dir=shared_dir) as frame_dir:
        frame_path = Path(frame_dir)
        fonts_link = frame_path / "fonts"
        if not fonts_link.exists():
            try:
                fonts_link.symlink_to(shared_dir / "fonts", target_is_directory=True)
            except OSError:
                shutil.copytree(shared_dir / "fonts", fonts_link, dirs_exist_ok=True)
        html_path = frame_path / "overlay.html"
        png_path = frame_path / "overlay.png"
        html_path.write_text(html_text, encoding="utf-8")
        render_html_screenshot(html_path, png_path, width, height, chrome)
        with Image.open(png_path) as handle:
            return handle.convert("RGBA").copy()


def escape(text: str | None) -> str:
    """转义 HTML 文本与属性值中的特殊字符。"""
    return html_module.escape(text or "", quote=True)


def format_distance(meters: float) -> str:
    """把米数格式化为易读的 m 或 km 字符串。"""
    if meters >= 1000:
        return f"{meters / 1000:.1f} km"
    return f"{int(meters)} m"


def build_progress_bar(current_ms: int, total_ms: int) -> str:
    """生成进度条内部填充条的 HTML。"""
    ratio = 0.0 if total_ms <= 0 else max(0.0, min(1.0, current_ms / total_ms))
    return f'<div class="progress-bar" style="width:{ratio * 100:.1f}%"></div>'
