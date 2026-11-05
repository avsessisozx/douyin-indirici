"""İndirme işini yürüten motor. Arayüz bu motorun durumunu okur."""

from __future__ import annotations

import asyncio
import threading
import time
import traceback
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional

from . import links
from .browser import BrowserError, DouyinBrowser
from .i18n import Msg, msg
from .history import History
from .fetcher import Cancelled, existing_file, fetch, write_bytes, ext_from_content_type
from .media import MediaFile, Post, author_folder, build_post
from .paths import app_data_dir, clean_settings

MODE_SINGLE = "single"
MODE_PROFILE = "profile"
MODE_LOGIN = "login"
MODE_RETRY = "retry"
KNOWN_STOP = 10          # üst üste bu kadar bilinen gönderi görünce "yenilerin sonu" sayılır


def _alert_sound() -> None:
    """Doğrulama (bulmaca) çıkınca dikkat çekmek için kısa bir ses çalar (sadece Windows)."""
    try:
        import sys
        if sys.platform == "win32":
            import winsound
            for freq in (880, 1175, 880):
                winsound.Beep(freq, 180)
    except Exception:
        pass


@dataclass
class Job:
    link: str
    mode: str
    settings: Dict[str, Any]
    ids: List[str] = field(default_factory=list)     # yeniden denenecek gönderi kimlikleri (MODE_RETRY)


class Engine:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._thread: Optional[threading.Thread] = None
        self._cancel = threading.Event()
        self._pause = threading.Event()
        self._known: set = set()
        self._job_sec_uid = ""
        self._sound = False
        self.history: Optional[History] = None
        self._failed: Dict[str, Dict[str, Any]] = {}     # inemeyen gönderiler: kimlik -> {"n": dosya sayısı, "name": ad}
        self._reset()

    # ------------------------------------------------------------ durum
    def _reset(self) -> None:
        self.running = False
        self.phase = "idle"            # idle | starting | collecting | downloading | done | error | stopped
        self.mode = ""
        self.captcha = False
        self.login_wall = False
        self.author = ""
        self.folder = ""
        self.found_posts = 0
        self.posts_done = 0
        self.files_total = 0
        self.files_done = 0
        self.files_skipped = 0
        self.files_failed = 0
        self.posts_skipped = 0
        self.posts_known = 0
        self._known = set()
        self._job_sec_uid = ""
        self.current = ""
        self.current_bytes = 0
        self.current_total = 0
        self.collecting = False
        self.summary: Optional[Msg] = None
        self.started_at = 0.0
        self.logs: List[Dict[str, Any]] = []
        self.log_offset = 0

    def log(self, level: str, message: Any) -> None:
        """Kayda bir satır ekler. message bir Msg (çevrilir) ya da ham metin (hata ayrıntısı) olabilir."""
        entry: Dict[str, Any] = {"t": time.strftime("%H:%M:%S"), "level": level}
        if isinstance(message, Msg):
            entry["msg"] = message.to_json()
        else:
            entry["text"] = str(message)
        with self._lock:
            self.logs.append(entry)
            if len(self.logs) > 2000:
                drop = len(self.logs) - 1500
                self.logs = self.logs[drop:]
                self.log_offset += drop

    def snapshot(self, since: int = 0) -> Dict[str, Any]:
        with self._lock:
            start = max(0, since - self.log_offset)
            return {
                "running": self.running,
                "phase": self.phase,
                "mode": self.mode,
                "captcha": self.captcha,
                "login_wall": self.login_wall,
                "author": self.author,
                "folder": self.folder,
                "found_posts": self.found_posts,
                "posts_done": self.posts_done,
                "posts_skipped": self.posts_skipped,
                "posts_known": self.posts_known,
                "paused": self._pause.is_set(),
                "failed_posts": len(self._failed),
                "collecting": self.collecting,
                "files_total": self.files_total,
                "files_done": self.files_done,
                "files_skipped": self.files_skipped,
                "files_failed": self.files_failed,
                "current": self.current,
                "current_bytes": self.current_bytes,
                "current_total": self.current_total,
                "summary": self.summary.to_json() if self.summary else None,
                "logs": self.logs[start:],
                "log_next": self.log_offset + len(self.logs),
            }

    # ------------------------------------------------------------ kontrol
    def start(self, job: Job) -> Optional[Msg]:
        with self._lock:
            if self.running:
                return msg("engine.already_running")
        if job.mode not in (MODE_LOGIN, MODE_RETRY):
            parsed = links.parse_link(job.link)
            if not parsed.ok:
                return parsed.error
            if job.mode == MODE_SINGLE and parsed.kind == links.PROFILE:
                return msg("engine.single_needs_post")
        if job.mode == MODE_RETRY:
            job.ids = list(self._failed.keys())
            if not job.ids:
                return msg("engine.retry_none")
        else:
            self._failed = {}
        logs_offset = self.log_offset + len(self.logs)
        self._reset()
        self.log_offset = logs_offset
        self._cancel = threading.Event()
        self._pause = threading.Event()
        self.running = True
        self.phase = "starting"
        self.mode = job.mode
        self.started_at = time.monotonic()
        self._thread = threading.Thread(target=self._thread_main, args=(job,), daemon=True)
        self._thread.start()
        return None

    def stop(self) -> None:
        if self.running:
            self.log("warn", msg("engine.stopping"))
            self._cancel.set()

    def pause(self) -> None:
        """İşi duraklatır: o an inen dosya biter, yenisi başlamaz."""
        if self.running and not self._pause.is_set():
            self._pause.set()
            self.log("warn", msg("engine.paused"))

    def resume(self) -> None:
        if self._pause.is_set():
            self._pause.clear()
            self.log("info", msg("engine.resumed"))

    def is_paused(self) -> bool:
        return self._pause.is_set() and not self._cancel.is_set()

    async def _wait_pause(self) -> None:
        while self._pause.is_set() and not self._cancel.is_set():
            await asyncio.sleep(0.3)

    def wait(self, timeout: float = 10) -> None:
        thread = self._thread
        if thread is not None:
            thread.join(timeout)

    def _thread_main(self, job: Job) -> None:
        try:
            asyncio.run(self._run(job))
        except Exception as exc:  # beklenmeyen hata — kullanıcıya göster
            self.log("error", msg("engine.unexpected", error=str(exc)))
            self.log("debug", traceback.format_exc())
            self.phase = "error"
            self.summary = msg("engine.unexpected_summary")
        finally:
            self.running = False
            self._pause.clear()
            self.captcha = False
            self.collecting = False
            self.current = ""

    # ------------------------------------------------------------ iş akışı
    async def _run(self, job: Job) -> None:
        settings = clean_settings(job.settings)
        self._sound = bool(settings.get("sound", True))
        if job.mode == MODE_SINGLE:
            settings["content"] = "all"   # tek gönderide ne varsa onu indir
            settings["limit"] = 0
        try:
            async with DouyinBrowser(app_data_dir(), log=self.log, cancel=self._cancel,
                                     on_captcha=self._set_captcha, is_paused=self.is_paused) as browser:
                if job.mode == MODE_LOGIN:
                    await browser.login_session()
                    self.phase = "done"
                    self.summary = msg("engine.login_done")
                    self.log("ok", self.summary)
                    return
                self.history = self._open_history(settings)
                try:
                    if job.mode == MODE_RETRY:
                        await self._retry_job(browser, job, settings)
                    else:
                        await self._download_job(browser, job, settings)
                finally:
                    if self.history is not None:
                        self.history.close()
                        self.history = None
        except BrowserError as exc:
            if self._cancel.is_set():
                self._finish_stopped()
            else:
                self.phase = "error"
                self.summary = exc.message
                self.log("error", exc.message)
        except (asyncio.CancelledError, Cancelled):
            self._finish_stopped()

    def _open_history(self, settings: Dict[str, Any]) -> Optional[History]:
        if not settings.get("history", True):
            return None
        try:
            return History()
        except Exception as exc:        # geçmiş dosyası açılamazsa geçmişsiz devam et
            self.log("warn", msg("engine.history_failed", error=str(exc)))
            return None

    def _finish_stopped(self) -> None:
        self.phase = "stopped"
        self.summary = msg("engine.stopped", n=self.files_done)
        self.log("warn", self.summary)

    def _set_captcha(self, value: bool) -> None:
        if value and not self.captcha and self._sound:
            threading.Thread(target=_alert_sound, daemon=True).start()
        self.captcha = value

    async def _download_job(self, browser: DouyinBrowser, job: Job, settings: Dict[str, Any]) -> None:
        parsed = await browser.resolve(links.parse_link(job.link))
        out_root = Path(settings["out_dir"]).expanduser()
        try:
            out_root.mkdir(parents=True, exist_ok=True)
        except OSError as exc:
            raise BrowserError(msg("engine.folder_failed", path=str(out_root), error=str(exc)))

        queue: asyncio.Queue = asyncio.Queue()
        downloads = DownloadPool(self, browser, out_root, settings, queue)
        workers = [asyncio.ensure_future(downloads.worker()) for _ in range(settings["parallel"])]

        try:
            if job.mode == MODE_SINGLE:
                if parsed.kind == links.PROFILE:
                    raise BrowserError(msg("engine.short_to_profile"))
                self.phase = "collecting"
                item = await browser.get_post(parsed.aweme_id, parsed.url)
                self._enqueue(queue, [item], settings)
            else:
                sec_uid = parsed.sec_uid
                if parsed.kind == links.POST and not sec_uid:
                    self.phase = "collecting"
                    self.log("info", msg("engine.finding_owner"))
                    item = await browser.get_post(parsed.aweme_id, parsed.url)
                    sec_uid = str((item.get("author") or {}).get("sec_uid") or "")
                    if not sec_uid:
                        raise BrowserError(msg("engine.owner_not_found"))
                    name = str((item.get("author") or {}).get("nickname") or "")
                    if name:
                        self.log("info", msg("engine.profile_name", name=name))
                self.phase = "collecting"
                self.collecting = True
                self._known = set()
                self._job_sec_uid = sec_uid
                stop_known = 0
                if self.history is not None and sec_uid:
                    self._known = self.history.known(sec_uid)
                    if self._known:
                        self.log("info", msg("engine.history_known", n=len(self._known)))
                        if self.history.was_scanned(sec_uid):
                            stop_known = KNOWN_STOP
                result = await browser.collect_profile(
                    sec_uid,
                    limit=settings["limit"],
                    on_items=lambda items: self._enqueue(queue, items, settings),
                    known_ids=self._known,
                    stop_after_known=stop_known,
                )
                self.collecting = False
                if result.author_name and not self.author:
                    self.author = result.author_name
                self._report_profile(result)

            for _ in workers:
                queue.put_nowait(None)
            self.phase = "downloading"
            await asyncio.gather(*workers)
        except BaseException:
            for w in workers:
                w.cancel()
            await asyncio.gather(*workers, return_exceptions=True)
            raise

        if self._cancel.is_set():
            self._finish_stopped()
            return
        if self._failed:
            self.log("info", msg("engine.retrying", n=len(self._failed)))
            await self._retry_pass(browser, out_root, settings)
            if self._cancel.is_set():
                self._finish_stopped()
                return
        if self.posts_known:
            self.log("info", msg("engine.history_skipped", n=self.posts_known))
        self.phase = "done"
        self.summary = msg("summary", done=self.files_done, skipped=self.files_skipped,
                           failed=self.files_failed, unmatched=self.posts_skipped)
        if self.files_done == 0 and self.files_skipped == 0:
            self.phase = "error" if self.files_failed or self.found_posts == 0 else "done"
        self.log("ok" if self.phase == "done" else "error", self.summary)

    async def _retry_job(self, browser: DouyinBrowser, job: Job, settings: Dict[str, Any]) -> None:
        """"İnemeyenleri tekrar dene": önceki işte inemeyen gönderileri baştan alıp indirir."""
        out_root = Path(settings["out_dir"]).expanduser()
        out_root.mkdir(parents=True, exist_ok=True)
        self.phase = "downloading"
        self.log("info", msg("engine.retrying", n=len(job.ids)))
        await self._retry_pass(browser, out_root, settings, only=job.ids)
        if self._cancel.is_set():
            self._finish_stopped()
            return
        self.phase = "done"
        self.summary = msg("summary", done=self.files_done, skipped=self.files_skipped,
                           failed=self.files_failed, unmatched=self.posts_skipped)
        self.log("ok" if not self._failed else "error", self.summary)

    async def _retry_pass(self, browser: DouyinBrowser, out_root: Path, settings: Dict[str, Any],
                          only: Optional[List[str]] = None) -> None:
        """İnemeyen gönderilerin sayfasını yeniden açıp (adresler eskimiş olabilir) bir kez daha indirir."""
        ids = list(only if only is not None else self._failed.keys())
        queue: asyncio.Queue = asyncio.Queue()
        pool = DownloadPool(self, browser, out_root, settings, queue)
        workers = [asyncio.ensure_future(pool.worker()) for _ in range(settings["parallel"])]
        try:
            for aweme_id in ids:
                if self._cancel.is_set():
                    break
                await self._wait_pause()
                try:
                    item = await browser.get_post(aweme_id, quiet=True)
                except BrowserError as exc:
                    self.log("warn", msg("engine.download_failed", name=aweme_id, error=exc.message))
                    continue
                post = build_post(item, content="all", quality=settings["quality"], compat=settings["compat"])
                if not post.aweme_id or post.skipped_reason:
                    continue
                prev = self._failed.pop(aweme_id, None)
                if prev:
                    self.files_failed = max(0, self.files_failed - int(prev.get("n", 0)))
                if not prev:
                    self.files_total += len(post.files)
                queue.put_nowait(post)
            for _ in workers:
                queue.put_nowait(None)
            await asyncio.gather(*workers)
        except BaseException:
            for w in workers:
                w.cancel()
            await asyncio.gather(*workers, return_exceptions=True)
            raise

    def _report_profile(self, result) -> None:
        if result.reason == "private":
            raise BrowserError(msg("engine.private"))
        if result.reason == "empty":
            raise BrowserError(msg("engine.empty"))
        if result.reason == "stalled" and result.collected == 0:
            raise BrowserError(msg("engine.no_list"))
        if result.reason == "login_wall":
            self.login_wall = True
            if result.reported_count:
                self.log("warn", msg("engine.login_wall_total", n=result.collected, total=result.reported_count))
            else:
                self.log("warn", msg("engine.login_wall", n=result.collected))
        elif result.reason == "limit":
            self.log("info", msg("engine.limit_reached", n=result.collected))
        elif result.reason == "up_to_date":
            self.log("ok", msg("engine.up_to_date", n=result.collected))
        elif result.reason in ("end", "stalled"):
            if result.reason == "end" and self.history is not None and result.sec_uid:
                self.history.set_scanned(result.sec_uid, result.author_name or self.author)
            if result.reported_count:
                self.log("ok", msg("engine.profile_scanned_total", n=result.collected, total=result.reported_count))
            else:
                self.log("ok", msg("engine.profile_scanned", n=result.collected))

    def _enqueue(self, queue: asyncio.Queue, items: List[Dict[str, Any]], settings: Dict[str, Any]) -> None:
        for item in items:
            post = build_post(item, content=settings["content"], quality=settings["quality"], compat=settings["compat"])
            if not post.aweme_id:
                continue
            self.found_posts += 1
            if not self.author and post.author_name:
                self.author = post.author_name
            if post.aweme_id in self._known:
                self.posts_known += 1
                continue
            if post.skipped_reason:
                self.posts_skipped += 1
                self.log("skip", msg("engine.skipped_post", reason=post.skipped_reason, name=post.stem))
                continue
            self.files_total += len(post.files)
            queue.put_nowait(post)


class DownloadPool:
    def __init__(self, engine: Engine, browser: DouyinBrowser, out_root: Path,
                 settings: Dict[str, Any], queue: asyncio.Queue) -> None:
        self.engine = engine
        self.browser = browser
        self.out_root = out_root
        self.settings = settings
        self.queue = queue
        self.headers = {
            "User-Agent": browser.user_agent or "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                                                "(KHTML, like Gecko) Chrome/130.0 Safari/537.36 Edg/130.0",
            "Referer": "https://www.douyin.com/",
            "Accept": "*/*",
        }

    async def worker(self) -> None:
        while True:
            post: Optional[Post] = await self.queue.get()
            if post is None:
                return
            if self.engine._cancel.is_set():
                continue
            await self.engine._wait_pause()
            await self._download_post(post)

    async def _download_post(self, post: Post) -> None:
        eng = self.engine
        folder = self.out_root / author_folder(post.author_name or eng.author, post.author_sec_uid)
        if not eng.folder:
            eng.folder = str(folder)
        results = []
        for media in post.files:
            if eng._cancel.is_set():
                return
            await eng._wait_pause()
            results.append(await self._download_file(folder, media))
        eng.posts_done += 1
        failed_n = sum(1 for r in results if r == "failed")
        if failed_n:
            eng._failed[post.aweme_id] = {"n": failed_n, "name": post.stem}
        elif results:
            eng._failed.pop(post.aweme_id, None)
            if eng.history is not None:
                try:
                    eng.history.mark(eng._job_sec_uid or post.author_sec_uid, post.aweme_id, post.author_name)
                except Exception:
                    pass
        if results and all(r != "failed" for r in results):
            if any(r == "downloaded" for r in results):
                key = "engine.downloaded_gallery" if post.is_gallery else "engine.downloaded_video"
            else:
                key = "engine.already_had"
            eng.log("ok", msg(key, name=post.stem))

    async def _download_file(self, folder: Path, media: MediaFile) -> str:
        """Sonuç: "downloaded", "skipped" (zaten vardı) ya da "failed"."""
        eng = self.engine
        stem_path = folder / Path(media.rel_stem)
        if existing_file(stem_path, media.ext):
            eng.files_skipped += 1
            return "skipped"
        eng.current = stem_path.name
        eng.current_bytes = 0
        eng.current_total = media.expected_size

        def progress(done: int, total: int) -> None:
            eng.current_bytes = done
            eng.current_total = total or media.expected_size

        fallback_ext = ".mp4" if media.kind in ("video", "live") else ".jpg"
        result = await asyncio.to_thread(
            fetch, media.urls, stem_path, ext=media.ext, fallback_ext=fallback_ext,
            headers=self.headers, cancel=eng._cancel, on_progress=progress, chunks=self.settings["chunks"],
        )
        if not result.ok and not eng._cancel.is_set() and result.status in (403, 0, 401):
            for url in media.urls[:3]:
                got = await self.browser.download_via_browser(url, self.headers)
                if got:
                    body, content_type = got
                    ext = media.ext or ext_from_content_type(content_type, fallback_ext)
                    result = await asyncio.to_thread(write_bytes, body, stem_path, ext)
                    break
        if result.ok:
            eng.files_done += 1
            return "downloaded"
        if eng._cancel.is_set():
            return "failed"
        eng.files_failed += 1
        eng.log("error", msg("engine.download_failed", name=stem_path.name, error=result.error or ""))
        return "failed"
