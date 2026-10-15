"""Programın giriş noktası: sunucuyu başlatır ve arayüz penceresini açar."""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.request
import webbrowser
from pathlib import Path
from typing import Optional

from . import __version__
from .paths import app_data_dir
from .server import AppState, start_server

HEARTBEAT_TIMEOUT = 45


def say(text: str) -> None:
    """Konsol yoksa ya da Türkçe karakter yazamıyorsa sessizce geç."""
    try:
        print(text, flush=True)
    except Exception:
        pass


def _instance_file() -> Path:
    return app_data_dir() / "calisan.json"


def _existing_instance() -> Optional[int]:
    try:
        port = int(json.loads(_instance_file().read_text(encoding="utf-8"))["port"])
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/ping", timeout=2) as resp:
            if json.loads(resp.read()).get("app") == "douyin-indirici":
                return port
    except Exception:
        return None
    return None


def find_edge() -> Optional[str]:
    if sys.platform != "win32":
        return None
    candidates = []
    for env in ("PROGRAMFILES(X86)", "PROGRAMFILES", "LOCALAPPDATA"):
        base = os.environ.get(env)
        if base:
            candidates.append(Path(base) / "Microsoft" / "Edge" / "Application" / "msedge.exe")
    for path in candidates:
        if path.is_file():
            return str(path)
    return shutil.which("msedge")


def open_window(url: str) -> Optional[subprocess.Popen]:
    """Arayüzü uygulama penceresi gibi (adres çubuğu olmadan) açar."""
    edge = find_edge()
    if edge:
        profile = app_data_dir() / "arayuz-penceresi"
        try:
            return subprocess.Popen([
                edge, f"--app={url}", f"--user-data-dir={profile}", "--window-size=1000,900",
                "--no-first-run", "--no-default-browser-check", "--disable-features=Translate",
            ])
        except OSError:
            pass
    webbrowser.open(url)
    return None


def self_test() -> int:
    """Derlenmiş programın çalıştığını doğrular (GitHub derlemesinde kullanılır)."""
    import asyncio
    import threading

    from .browser import DouyinBrowser

    state = AppState()
    server = start_server(state)
    with urllib.request.urlopen(f"http://127.0.0.1:{state.port}/", timeout=5) as resp:
        html = resp.read().decode("utf-8")
    assert "Douyin İndirici" in html, "arayüz yüklenemedi"

    async def check_browser() -> str:
        os.environ.setdefault("DOUYIN_INDIRICI_HEADLESS", "1")
        async with DouyinBrowser(app_data_dir(), log=lambda *a: None, cancel=threading.Event(),
                                 on_captcha=lambda v: None) as browser:
            return browser.channel_name

    name = asyncio.run(check_browser())
    server.shutdown()
    say(f"SELF-TEST OK: arayüz + tarayıcı ({name})")
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(prog="DouyinIndirici")
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--no-window", action="store_true")
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--version", action="version", version=__version__)
    args = parser.parse_args(argv)

    if args.self_test:
        # Pencereli derlemede hata kutusu açılıp beklemesin: sonucu dosyaya yaz.
        report = Path(os.environ.get("DOUYIN_INDIRICI_SELFTEST_OUT", "selftest.txt"))
        try:
            code = self_test()
            report.write_text("OK", encoding="utf-8")
            return code
        except BaseException:
            import traceback
            report.write_text("FAIL\n" + traceback.format_exc(), encoding="utf-8")
            return 1

    existing = _existing_instance()
    if existing and not args.no_window:
        open_window(f"http://127.0.0.1:{existing}/")
        return 0

    state = AppState()
    server = start_server(state, args.port)
    url = f"http://127.0.0.1:{state.port}/"
    try:
        _instance_file().write_text(json.dumps({"port": state.port, "pid": os.getpid()}), encoding="utf-8")
    except OSError:
        pass
    say(f"Douyin İndirici çalışıyor: {url}")

    window = None if args.no_window else open_window(url)
    started = time.monotonic()
    try:
        while True:
            time.sleep(1)
            if args.no_window:
                continue
            if window is not None:
                if window.poll() is not None:
                    if time.monotonic() - started < 8:
                        # Edge pencereyi zaten açık bir kopyaya devretti; nabızla izlemeye geç.
                        window = None
                        continue
                    break  # pencere kapatıldı
            else:
                last = state.last_poll or started
                if time.monotonic() - last > HEARTBEAT_TIMEOUT and not state.engine.running:
                    break
    except KeyboardInterrupt:
        pass
    finally:
        state.engine.stop()
        state.engine.wait(15)
        server.shutdown()
        try:
            _instance_file().unlink()
        except OSError:
            pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
