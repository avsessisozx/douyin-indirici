"""Dosyaları parça parça indirir (sadece Python'un kendi kütüphaneleriyle)."""

from __future__ import annotations

import os
import ssl
import re
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Dict, List, Optional

from .i18n import Msg, msg

CHUNK = 256 * 1024
TIMEOUT = 30
MIN_CHUNKED = 1024 * 1024        # bundan küçük dosyalar tek bağlantıyla inmeye değer
MIN_SEGMENT = 512 * 1024         # bir parça en az bu kadar olsun

_CONTENT_EXT = {
    "image/jpeg": ".jpg",
    "image/jpg": ".jpg",
    "image/png": ".png",
    "image/webp": ".webp",
    "image/heic": ".heic",
    "image/heif": ".heic",
    "image/avif": ".avif",
    "image/gif": ".gif",
    "video/mp4": ".mp4",
}
KNOWN_EXTS = (".mp4", ".jpg", ".jpeg", ".png", ".webp", ".heic", ".avif", ".gif")


class Cancelled(Exception):
    pass


@dataclass
class FetchResult:
    ok: bool
    path: Optional[Path] = None
    size: int = 0
    error: Optional[Msg] = None
    status: int = 0          # son HTTP durum kodu (403 ise tarayıcıyla denenebilir)


def existing_file(stem_path: Path, ext: str = "") -> Optional[Path]:
    """Daha önce inmiş (boş olmayan) dosyayı bulur."""
    candidates = [stem_path.with_name(stem_path.name + ext)] if ext else [
        stem_path.with_name(stem_path.name + e) for e in KNOWN_EXTS
    ]
    for path in candidates:
        try:
            if path.is_file() and path.stat().st_size > 0:
                return path
        except OSError:
            continue
    return None


def ext_from_content_type(content_type: str, fallback: str) -> str:
    base = (content_type or "").split(";")[0].strip().lower()
    return _CONTENT_EXT.get(base, fallback)


def _ssl_context() -> ssl.SSLContext:
    return ssl.create_default_context()


def fetch(
    urls: List[str],
    stem_path: Path,
    *,
    ext: str,
    fallback_ext: str,
    headers: Dict[str, str],
    cancel: threading.Event,
    on_progress: Optional[Callable[[int, int], None]] = None,
    attempts_per_url: int = 2,
    chunks: int = 1,
) -> FetchResult:
    """URL listesini sırayla dener; ilk başarılı indirmeyi kaydeder.

    chunks > 1 ise dosya bu kadar parçaya bölünüp aynı anda çekilir (uzak sunucularda tek bağlantı
    yavaş kalır). Sunucu parçalı istek desteklemiyorsa ya da bir parça inmezse tek bağlantıya döner.
    Adres sırası değişmez: liste kalite/biçim tercihini taşıyor (ör. H.264 önce, WebP sonra).
    """
    stem_path.parent.mkdir(parents=True, exist_ok=True)
    last = FetchResult(False, error=msg("fetch.no_url"))
    ctx = _ssl_context()
    for url in urls:
        for _attempt in range(attempts_per_url):
            if cancel.is_set():
                raise Cancelled()
            result = None
            if chunks > 1:
                result = _fetch_chunked(url, stem_path, ext, fallback_ext, headers, cancel, on_progress, ctx, chunks)
            if result is None:
                result = _fetch_one(url, stem_path, ext, fallback_ext, headers, cancel, on_progress, ctx)
            if result.ok:
                return result
            last = result
            if result.status in (403, 404, 410):
                break  # bu adres işe yaramıyor, sonrakine geç
    return last


@dataclass
class _Probe:
    ok: bool
    total: int = 0
    ranged: bool = False
    content_type: str = ""
    latency: float = 1e9


def _probe(url: str, headers: Dict[str, str], ctx, timeout: float = 15) -> _Probe:
    """1 baytlık parçalı istek: sunucu parçalı indirmeyi destekliyor mu, dosya ne kadar büyük, ne kadar hızlı yanıt veriyor."""
    request = urllib.request.Request(url, headers={**headers, "Range": "bytes=0-0"})
    started = time.monotonic()
    try:
        with urllib.request.urlopen(request, timeout=timeout, context=ctx) as response:
            status = getattr(response, "status", 200)
            content_type = response.headers.get("Content-Type", "")
            latency = time.monotonic() - started
            if content_type.lower().startswith(("text/html", "application/json", "text/plain")):
                return _Probe(False, latency=latency)
            if status == 206:
                m = re.search(r"/(\d+)\s*$", response.headers.get("Content-Range", ""))
                if m:
                    return _Probe(True, int(m.group(1)), True, content_type, latency)
            return _Probe(True, int(response.headers.get("Content-Length") or 0), False, content_type, latency)
    except Exception:
        return _Probe(False)


def _fetch_chunked(url, stem_path, ext, fallback_ext, headers, cancel, on_progress, ctx, chunks):
    """Dosyayı parçalara bölüp aynı anda indirir. Uygun değilse ya da bir parça inmezse None döner (tek bağlantı denenir)."""
    probe = _probe(url, headers, ctx)
    if not (probe.ok and probe.ranged and probe.total >= MIN_CHUNKED):
        return None
    n = max(1, min(chunks, probe.total // MIN_SEGMENT))
    if n < 2:
        return None
    total = probe.total
    size = -(-total // n)
    spans = [(i * size, min(total, (i + 1) * size) - 1) for i in range(n) if i * size < total]
    part = stem_path.with_name(stem_path.name + ".part")
    final_ext = ext or ext_from_content_type(probe.content_type, fallback_ext)
    lock = threading.Lock()
    done = [0]
    failed = threading.Event()

    def segment(span) -> bool:
        start, end = span
        request = urllib.request.Request(url, headers={**headers, "Range": f"bytes={start}-{end}"})
        try:
            with urllib.request.urlopen(request, timeout=TIMEOUT, context=ctx) as response:
                if getattr(response, "status", 200) != 206:
                    return False
                pos = start
                with open(part, "r+b") as fh:
                    fh.seek(start)
                    while pos <= end:
                        if cancel.is_set() or failed.is_set():
                            return False
                        chunk = response.read(min(CHUNK, end - pos + 1))
                        if not chunk:
                            return False
                        fh.write(chunk)
                        pos += len(chunk)
                        with lock:
                            done[0] += len(chunk)
                            if on_progress:
                                on_progress(done[0], total)
            return pos == end + 1
        except Exception:
            return False

    try:
        with open(part, "wb") as fh:
            fh.truncate(total)
        def run(span) -> bool:
            ok = segment(span)
            if not ok:
                failed.set()        # bir parça inmediyse diğerleri de boşuna sürmesin
            return ok

        with ThreadPoolExecutor(max_workers=len(spans)) as pool:
            results = list(pool.map(run, spans))
        if cancel.is_set():
            _remove(part)
            raise Cancelled()
        if failed.is_set() or not all(results) or done[0] != total:
            _remove(part)
            return None
        final = stem_path.with_name(stem_path.name + final_ext)
        os.replace(part, final)
        return FetchResult(True, final, total, status=206)
    except Cancelled:
        raise
    except OSError as exc:
        _remove(part)
        return FetchResult(False, error=msg("fetch.write", error=str(exc)))


def _fetch_one(url, stem_path, ext, fallback_ext, headers, cancel, on_progress, ctx) -> FetchResult:
    part = stem_path.with_name(stem_path.name + ".part")
    request = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT, context=ctx) as response:
            status = getattr(response, "status", 200)
            content_type = response.headers.get("Content-Type", "")
            if content_type.lower().startswith(("text/html", "application/json", "text/plain")):
                return FetchResult(False, error=msg("fetch.html_page"), status=403)
            total = int(response.headers.get("Content-Length") or 0)
            final_ext = ext or ext_from_content_type(content_type, fallback_ext)
            done = 0
            with open(part, "wb") as fh:
                while True:
                    if cancel.is_set():
                        raise Cancelled()
                    chunk = response.read(CHUNK)
                    if not chunk:
                        break
                    fh.write(chunk)
                    done += len(chunk)
                    if on_progress:
                        on_progress(done, total)
            if done == 0:
                _remove(part)
                return FetchResult(False, error=msg("fetch.empty"), status=status)
            if total and done < total:
                _remove(part)
                return FetchResult(False, error=msg("fetch.incomplete"), status=status)
            final = stem_path.with_name(stem_path.name + final_ext)
            os.replace(part, final)
            return FetchResult(True, final, done, status=status)
    except Cancelled:
        _remove(part)
        raise
    except urllib.error.HTTPError as exc:
        _remove(part)
        return FetchResult(False, error=msg("fetch.http", code=exc.code), status=exc.code)
    except (urllib.error.URLError, TimeoutError, ConnectionError, ssl.SSLError) as exc:
        _remove(part)
        reason = getattr(exc, "reason", exc)
        return FetchResult(False, error=msg("fetch.connection", reason=str(reason)))
    except OSError as exc:
        _remove(part)
        return FetchResult(False, error=msg("fetch.write", error=str(exc)))


def write_bytes(data: bytes, stem_path: Path, ext: str) -> FetchResult:
    stem_path.parent.mkdir(parents=True, exist_ok=True)
    final = stem_path.with_name(stem_path.name + ext)
    part = stem_path.with_name(stem_path.name + ".part")
    try:
        with open(part, "wb") as fh:
            fh.write(data)
        os.replace(part, final)
    except OSError as exc:
        _remove(part)
        return FetchResult(False, error=msg("fetch.write", error=str(exc)))
    return FetchResult(True, final, len(data))


def _remove(path: Path) -> None:
    try:
        path.unlink()
    except OSError:
        pass
