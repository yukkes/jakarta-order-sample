#!/usr/bin/env python3
"""Record docs/images/demo.gif: a scripted pass through the whole UI.

The GIF is a documentation artefact, so it shows the *interactions* — the pointer
moving, the login form being typed into, a tab being switched, a batch job being
started — rather than a static screenshot dump.

Requirements (developer machine only, not part of the build):

    pip install playwright pillow
    python -m playwright install chromium

The application has to be running with the demo accounts, for example:

    mvn -pl order-quarkus quarkus:dev          # or the packaged quarkus-run.jar
    # admin/admin and user/user exist when order.demo-users=true

Start from a fresh demo database (the app recreates the schema on start) so the
recording shows the seeded data instead of leftovers from earlier test runs.

Then:

    ./scripts/record-demo.py                   # writes docs/images/demo.gif
    ./scripts/record-demo.py --out /tmp/x.gif --width 1000

How it works:
  * Playwright drives Chromium at 1280x800 and the page is screenshot continuously
    (JPEG, as fast as the browser answers — ~30 fps here) while the scenario runs;
  * a pointer overlay is injected with an init script so the recording shows where
    the mouse is and where it clicks (the overlay is drawn with CSSOM, not inline
    styles, so the app's CSP is still intact);
  * frames are resampled onto a fixed 10 fps timeline, and ImageMagick's
    `-layers OptimizePlus` (when available) stores only the changed rectangle per
    frame, which is what keeps the file around 1-2 MB. Without ImageMagick the
    script falls back to Pillow, which is correct but bigger.
"""

from __future__ import annotations

import argparse
import asyncio
import io
import os
import shutil
import subprocess
import sys
import tempfile
import time

from PIL import Image
from playwright.async_api import async_playwright

BASE_URL = os.environ.get("DEMO_BASE_URL", "http://127.0.0.1:8080")
FRAME_MS = 100          # 10 fps, same cadence as the previous recordings
VIEWPORT = (1280, 800)

# Pointer overlay. Injected before any page script runs; drawn through CSSOM so no
# inline `style` attribute ends up in the DOM that the app serves.
CURSOR_JS = r"""
(() => {
  if (window.__demoCursor) return;
  window.__demoCursor = true;
  const base = 'position:fixed;left:0;top:0;z-index:2147483647;pointer-events:none;';
  const mount = () => {
    if (!document.body || document.getElementById('__dc')) return;
    const arrow = document.createElement('div');
    arrow.id = '__dc';
    arrow.setAttribute('aria-hidden', 'true');
    arrow.style.cssText = base + 'width:18px;height:18px;';
    arrow.innerHTML = '<svg width="18" height="18" viewBox="0 0 18 18">'
      + '<path d="M1.5 1 L1.5 13.6 L5.1 10.7 L7.5 16.1 L9.8 15.1 L7.3 9.8 L12.2 9.8 Z" '
      + 'fill="#111827" stroke="#ffffff" stroke-width="1.1" stroke-linejoin="round"/></svg>';
    document.body.appendChild(arrow);

    const ripple = document.createElement('div');
    ripple.id = '__dcr';
    ripple.style.cssText = base + 'width:26px;height:26px;margin:-13px 0 0 -13px;border-radius:50%;'
      + 'background:rgba(3,105,161,.25);border:1px solid rgba(3,105,161,.55);opacity:0;'
      + 'transform:scale(.35);transition:opacity .25s ease-out,transform .25s ease-out;';
    document.body.appendChild(ripple);

    let x = 0, y = 0;
    const place = () => { arrow.style.transform = `translate(${x}px, ${y}px)`; };
    window.addEventListener('mousemove', (e) => { x = e.clientX; y = e.clientY; place(); }, true);
    window.addEventListener('mousedown', () => {
      ripple.style.left = x + 'px';
      ripple.style.top = y + 'px';
      ripple.style.opacity = '1';
      ripple.style.transform = 'scale(1)';
    }, true);
    window.addEventListener('mouseup', () => {
      ripple.style.opacity = '0';
      ripple.style.transform = 'scale(1.7)';
    }, true);
    place();
  };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', mount);
  else mount();
})();
"""

FRAME_S = FRAME_MS / 1000


class Recorder:
    """Captures one frame per 100 ms tick, sequentially.

    Taking screenshots concurrently with page actions starves the capture connection
    (Playwright serialises both on the same session), so the scenario drives a fixed
    10 fps clock instead: act, then tick. Mouse movement and typing are split across
    ticks so they show up as motion in the recording.
    """

    def __init__(self, page) -> None:
        self.page = page
        self.frames: list[bytes] = []
        self.pointer = (VIEWPORT[0] / 2, VIEWPORT[1] - 40)

    async def tick(self, count: int = 1) -> None:
        for _ in range(count):
            started = time.monotonic()
            try:
                self.frames.append(await self.page.screenshot(type="jpeg", quality=92))
            except Exception:  # a navigation tears the frame down: keep the old one
                if self.frames:
                    self.frames.append(self.frames[-1])
            remaining = FRAME_S - (time.monotonic() - started)
            if remaining > 0:
                await self.page.wait_for_timeout(int(remaining * 1000))

    async def center(self, locator) -> tuple[float, float]:
        box = await locator.bounding_box()
        if box is None:
            raise RuntimeError(f"element not visible: {locator}")
        return box["x"] + box["width"] / 2, box["y"] + box["height"] / 2

    async def glide(self, locator, ticks: int = 9) -> tuple[float, float]:
        x1, y1 = await self.center(locator)
        x0, y0 = self.pointer
        for step in range(1, ticks + 1):
            await self.page.mouse.move(x0 + (x1 - x0) * step / ticks, y0 + (y1 - y0) * step / ticks)
            await self.tick()
        self.pointer = (x1, y1)
        return x1, y1

    async def click(self, locator, ticks: int = 9) -> None:
        x, y = await self.glide(locator, ticks)
        await self.tick(1)
        await self.page.mouse.click(x, y)

    async def type_text(self, text: str, per_char: int = 1) -> None:
        for char in text:
            await self.page.keyboard.type(char)
            await self.tick(per_char)


async def record(out_dir: str) -> tuple[str, int, float]:
    """Runs the scenario at a fixed 10 fps and writes the PNG frames."""
    async with async_playwright() as p:
        browser = await p.chromium.launch()
        context = await browser.new_context(
            viewport={"width": VIEWPORT[0], "height": VIEWPORT[1]},
            device_scale_factor=1,
            color_scheme="light",
        )
        await context.add_init_script(CURSOR_JS)
        page = await context.new_page()
        rec = Recorder(page)
        await scenario(rec)
        await browser.close()

    if not rec.frames:
        raise RuntimeError("no frames captured")
    for stale in os.listdir(out_dir):
        os.remove(os.path.join(out_dir, stale))
    for i, data in enumerate(rec.frames):
        Image.open(io.BytesIO(data)).convert("RGB").save(os.path.join(out_dir, f"f{i:04d}.png"))
    return out_dir, len(rec.frames), len(rec.frames) * FRAME_S


async def scenario(rec: Recorder) -> None:
    page = rec.page
    await page.goto(f"{BASE_URL}/login.html", wait_until="networkidle")
    rec.pointer = (VIEWPORT[0] * 0.72, VIEWPORT[1] * 0.66)
    await rec.tick(3)

    await rec.click(page.locator("#username"), ticks=8)
    await rec.type_text("admin")
    await rec.tick(2)

    await rec.click(page.locator("#password"), ticks=6)
    await rec.type_text("admin")
    await rec.tick(2)

    await rec.click(page.locator("#submit"), ticks=6)
    await page.wait_for_url(f"{BASE_URL}/", timeout=15000)
    await page.wait_for_load_state("networkidle")
    await rec.tick(5)

    # Orders: hover a row, then confirm a DRAFT order. On a database without one
    # (every seeded order already confirmed) create it first.
    await rec.glide(page.locator("#orders-body tbody tr").nth(1), ticks=7)
    await rec.tick(3)
    if await page.locator('button:has-text("確定")').count() == 0:
        await rec.click(page.locator('button[type="submit"]'), ticks=8)
        await rec.tick(5)
    await rec.click(page.locator('button:has-text("確定")').first, ticks=7)
    await rec.tick(6)

    # Products
    await rec.click(page.locator('[data-tab="products"]'), ticks=8)
    await rec.tick(4)
    await rec.glide(page.locator("#products-body tbody tr").nth(1), ticks=6)
    await rec.tick(2)
    await rec.click(page.locator('[data-action="reload-products"]'), ticks=6)
    await rec.tick(4)

    # Invoices
    await rec.click(page.locator('[data-tab="invoices"]'), ticks=7)
    await rec.tick(4)
    await rec.click(page.locator('[data-action="reload-invoices"]'), ticks=6)
    await rec.tick(4)

    # Batch: run the CSV import and watch the execution panel finish
    await rec.click(page.locator('[data-tab="batch"]'), ticks=7)
    await rec.tick(4)
    await rec.click(page.locator('[data-action="start-import-job"]'), ticks=7)
    await rec.tick(18)

    # Back to orders, scroll so the pager and rows are visible, then back up
    await rec.click(page.locator('[data-tab="orders"]'), ticks=7)
    await rec.tick(4)
    await page.mouse.wheel(0, 220)
    await rec.tick(4)
    await rec.glide(page.locator("#orders-body tbody tr").first, ticks=6)
    await rec.tick(3)
    await page.mouse.wheel(0, -220)
    await rec.tick(3)


def build_with_imagemagick(frames_dir: str, out_path: str) -> bool:
    magick = shutil.which("magick") or shutil.which("convert")
    if not magick:
        return False
    cmd = [magick, "-delay", str(FRAME_MS // 10), "-loop", "0",
           os.path.join(frames_dir, "f*.png"),
           "-resize", "1200x750", "-layers", "OptimizePlus", "-colors", "128", out_path]
    try:
        subprocess.run(cmd, check=True, capture_output=True)
    except subprocess.CalledProcessError as error:
        print(f"ImageMagick failed, falling back to Pillow: {error}", file=sys.stderr)
        return False
    return True


def build_with_pillow(frames_dir: str, out_path: str, width: int) -> None:
    images = []
    for name in sorted(os.listdir(frames_dir)):
        image = Image.open(os.path.join(frames_dir, name)).convert("RGB")
        image.thumbnail((width, width), Image.LANCZOS)
        images.append(image)
    height = max(image.height for image in images)
    canvas = []
    for image in images:
        background = Image.new("RGB", (images[0].width, height), "#f8fafc")
        background.paste(image, (0, 0))
        canvas.append(background)
    sample = canvas[:: max(1, len(canvas) // 12)]
    mosaic = Image.new("RGB", (sample[0].width, sample[0].height * len(sample)))
    for i, image in enumerate(sample):
        mosaic.paste(image, (0, i * image.height))
    palette = mosaic.quantize(colors=96, method=Image.MEDIANCUT)
    quantized = [image.quantize(palette=palette, dither=Image.NONE) for image in canvas]
    quantized[0].save(out_path, save_all=True, append_images=quantized[1:],
                      duration=FRAME_MS, loop=0, optimize=True, disposal=2)


def main() -> int:
    parser = argparse.ArgumentParser(description="Record the UI demo GIF.")
    parser.add_argument("--out", default="docs/images/demo.gif")
    parser.add_argument("--width", type=int, default=1200, help="Pillow fallback width")
    parser.add_argument("--keep-frames", action="store_true", help="keep the PNG frames")
    args = parser.parse_args()

    with tempfile.TemporaryDirectory() as tmp:
        frames_dir, count, seconds = asyncio.run(record(tmp))
        out_dir = os.path.dirname(os.path.abspath(args.out))
        os.makedirs(out_dir, exist_ok=True)
        used = "ImageMagick"
        if not build_with_imagemagick(frames_dir, args.out):
            used = "Pillow"
            build_with_pillow(frames_dir, args.out, args.width)
        if args.keep_frames:
            keep = os.path.splitext(args.out)[0] + "-frames"
            shutil.copytree(frames_dir, keep, dirs_exist_ok=True)

    size = os.path.getsize(args.out) / 1024 / 1024
    print(f"{args.out}: {count} frames, {seconds:.1f}s, {size:.2f} MB (via {used})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
