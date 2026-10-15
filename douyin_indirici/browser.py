"""Gerçek bir tarayıcı (Edge/Chrome) penceresi üzerinden Douyin verisini toplar.

Douyin doğrudan program isteklerini imza/doğrulama ile engelliyor. Bu yüzden
sayfayı gerçek bir tarayıcıda açıyoruz ve sayfanın kendi yüklediği gönderi
listelerini (aweme/post, aweme/detail yanıtlarını) yakalıyoruz.
"""

from __future__ import annotations

import asyncio
import json
import os
import re
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional
from urllib.parse import unquote

from . import links, winbrowsers
from .media import aweme_id_of, find_post_dicts

POST_API = "/aweme/v1/web/aweme/post/"
DETAIL_API = "/aweme/v1/web/aweme/detail/"
PROFILE_API = "/aweme/v1/web/user/profile/other/"

_BLOCK_TITLES = ("验证码", "安全验证")
_BLOCK_FRAME_HINTS = ("verifycenter", "captcha", "/verify", "rmc.bytedance.com")
_BLOCK_SELECTORS = "#captcha_container, #captcha-verify-image, .captcha_verify_container, .captcha-verify-container, [class*='captcha-verify'], [id^='captcha_']"


class BrowserError(Exception):
    """Kullanıcıya gösterilecek Türkçe açıklamalı hata."""


def base_url() -> str:
    return os.environ.get("DOUYIN_INDIRICI_BASE", "https://www.douyin.com").rstrip("/")


def _rebase(url: str) -> str:
    """Testlerde gerçek Douyin yerine sahte sunucuya yönlendirmek için."""
    base = base_url()
    if base == "https://www.douyin.com":
        return url
    return re.sub(r"^https?://(www\.)?douyin\.com", base, url)


@dataclass
class ProfileResult:
    author_name: str = ""
    reported_count: int = 0
    collected: int = 0
    reason: str = "end"          # end | limit | cancel | login_wall | private | empty | stalled
    private: bool = False


@dataclass
class _ProfileState:
    items: Dict[str, Dict[str, Any]] = field(default_factory=dict)
    order: List[str] = field(default_factory=list)
    has_more: Optional[int] = None
    responses: int = 0
    author_name: str = ""
    reported_count: int = -1
    private: bool = False
    last_change: float = field(default_factory=time.monotonic)


class DouyinBrowser:
    def __init__(
        self,
        data_dir: Path,
        *,
        log: Callable[[str, str], None],
        cancel: threading.Event,
        on_captcha: Callable[[bool], None],
    ) -> None:
        self.data_dir = data_dir
        self.log = log
        self.cancel = cancel
        self.on_captcha = on_captcha
        self._pw = None
        self.context = None
        self.page = None
        self.user_agent = ""
        self.channel_name = ""

    # ------------------------------------------------------------ açılış
    async def __aenter__(self) -> "DouyinBrowser":
        winbrowsers.prepare_playwright_env()
        try:
            from playwright.async_api import async_playwright
        except Exception as exc:  # pragma: no cover
            raise BrowserError(f"Tarayıcı bileşeni yüklenemedi: {exc}")

        self._pw = await async_playwright().start()
        errors: List[str] = []
        for channel in self._channel_order():
            if self.cancel.is_set():
                break
            if await self._try_launch(channel, errors):
                break
            if channel == "chromium" and winbrowsers.bundled_chromium_exe() is None:
                # Kendi tarayıcımız henüz indirilmemiş: indir ve bir kez daha dene.
                ok = await asyncio.to_thread(winbrowsers.install_bundled_chromium, self.log)
                if ok and await self._try_launch(channel, errors):
                    break
        if self.context is None:
            await self._close_pw()
            raise BrowserError(
                "Hiçbir tarayıcı açılamadı. Edge'in kurulumu bozuk olabilir ve yedek tarayıcı da indirilemedi. "
                "İnternet bağlantını kontrol edip programı yeniden aç.\nAyrıntı: " + " | ".join(errors)
            )

        await self.context.add_init_script(
            "Object.defineProperty(navigator, 'webdriver', {get: () => undefined});"
        )
        pages = self.context.pages
        self.page = pages[0] if pages else await self.context.new_page()
        for extra in pages[1:]:
            try:
                await extra.close()
            except Exception:
                pass
        try:
            self.user_agent = await self.page.evaluate("navigator.userAgent")
        except Exception:
            self.user_agent = ""
        self.log("info", f"{self.channel_name} açıldı. İş bitene kadar bu pencereyi kapatma.")
        return self

    def _channel_order(self) -> List[str]:
        wanted = os.environ.get("DOUYIN_INDIRICI_BROWSER", "")
        if wanted:
            return [wanted]
        order: List[str] = []
        if winbrowsers.healthy_edge():
            order.append("msedge")
        elif winbrowsers.find_edge():
            self.log("warn", "Bilgisayardaki Edge'in kurulumu eksik görünüyor (yarım kalmış güncelleme). Başka tarayıcı kullanılacak.")
        if winbrowsers.healthy_chrome():
            order.append("chrome")
        order.append("chromium")
        return order

    async def _try_launch(self, channel: str, errors: List[str]) -> bool:
        headless = os.environ.get("DOUYIN_INDIRICI_HEADLESS") == "1"
        profile_dir = self.data_dir / f"tarayici-{channel or 'chromium'}"
        profile_dir.mkdir(parents=True, exist_ok=True)
        kwargs: Dict[str, Any] = dict(
            headless=headless,
            args=["--disable-blink-features=AutomationControlled", "--no-first-run",
                  "--no-default-browser-check", "--window-size=1100,860"],
            ignore_default_args=["--enable-automation"],
            no_viewport=True,
            locale="zh-CN",
            accept_downloads=False,
            timeout=60000,
        )
        if channel and channel != "chromium":
            kwargs["channel"] = channel
        elif not os.environ.get("DOUYIN_INDIRICI_BROWSER"):
            exe = winbrowsers.bundled_chromium_exe()
            if not exe:
                errors.append("chromium: henüz indirilmedi")
                return False
            kwargs["executable_path"] = exe
        try:
            self.context = await self._pw.chromium.launch_persistent_context(str(profile_dir), **kwargs)
        except Exception as exc:
            text = str(exc)
            errors.append(f"{channel}: {text.splitlines()[0] if text else exc}")
            if "ProcessSingleton" in text or "already in use" in text:
                await self._close_pw()
                raise BrowserError(
                    "Tarayıcı profili şu an başka bir pencerede kullanılıyor. "
                    "Açık kalmış başka bir Douyin İndirici penceresi varsa kapatıp tekrar dene."
                )
            return False
        self.channel_name = {"msedge": "Microsoft Edge", "chrome": "Google Chrome"}.get(channel, "Tarayıcı")
        return True

    async def __aexit__(self, *exc) -> None:
        await self.close()

    async def close(self) -> None:
        if self.context is not None:
            try:
                await self.context.close()
            except Exception:
                pass
            self.context = None
        await self._close_pw()

    async def _close_pw(self) -> None:
        if self._pw is not None:
            try:
                await self._pw.stop()
            except Exception:
                pass
            self._pw = None

    @property
    def closed(self) -> bool:
        return self.page is None or self.page.is_closed()

    def _check_alive(self) -> None:
        if self.closed:
            raise BrowserError("Tarayıcı penceresi kapatıldı. İşlem durduruldu.")

    # ------------------------------------------------------------ doğrulama
    async def _is_blocked(self, page) -> bool:
        try:
            title = await page.title()
        except Exception:
            title = ""
        if any(word in title for word in _BLOCK_TITLES):
            return True
        for frame in page.frames:
            if frame is page.main_frame:
                url = (frame.url or "").lower().split("?")[0]
                if "verifycenter" in url or "/captcha" in url:
                    return True
                continue
            url = (frame.url or "").lower()
            if any(hint in url for hint in _BLOCK_FRAME_HINTS):
                return True
        try:
            return bool(await page.evaluate(
                """(sel) => [...document.querySelectorAll(sel)].some(el => {
                    const r = el.getBoundingClientRect();
                    return r.width > 40 && r.height > 40 && getComputedStyle(el).visibility !== 'hidden';
                })""",
                _BLOCK_SELECTORS,
            ))
        except Exception:
            return False

    async def wait_if_blocked(self, page, timeout: float = 900) -> bool:
        """Doğrulama varsa kullanıcının çözmesini bekler. Doğrulama olduysa True döner."""
        if not await self._is_blocked(page):
            return False
        self.on_captcha(True)
        self.log("warn", "Douyin bir doğrulama istiyor. Açılan tarayıcı penceresinde doğrulamayı tamamla; program bekliyor.")
        try:
            await page.bring_to_front()
        except Exception:
            pass
        start = time.monotonic()
        try:
            while time.monotonic() - start < timeout:
                if self.cancel.is_set():
                    return True
                self._check_alive()
                await asyncio.sleep(1.5)
                if not await self._is_blocked(page):
                    self.log("ok", "Doğrulama tamamlandı, devam ediliyor.")
                    await asyncio.sleep(1.5)
                    return True
            raise BrowserError("Doğrulama 15 dakika içinde tamamlanmadı. Tekrar dene.")
        finally:
            self.on_captcha(False)

    # ------------------------------------------------------------ yardımcılar
    @staticmethod
    async def _json(response) -> Optional[Any]:
        try:
            return await response.json()
        except Exception:
            try:
                return json.loads(await response.text())
            except Exception:
                return None

    async def _embedded_posts(self, page, aweme_id: str) -> List[Dict[str, Any]]:
        """Sayfaya gömülü veriden (RENDER_DATA / _ROUTER_DATA / SSR) gönderiyi çıkarır."""
        try:
            blobs = await page.evaluate(
                """() => {
                    const out = [];
                    const rd = document.getElementById('RENDER_DATA');
                    if (rd) out.push(['uri', rd.textContent]);
                    for (const key of ['_ROUTER_DATA', 'SSR_HYDRATED_DATA', '__INITIAL_STATE__']) {
                        try { if (window[key]) out.push(['json', JSON.stringify(window[key])]); } catch (e) {}
                    }
                    return out;
                }"""
            )
        except Exception:
            return []
        posts: List[Dict[str, Any]] = []
        for kind, text in blobs or []:
            if not text:
                continue
            try:
                data = json.loads(unquote(text) if kind == "uri" else text)
            except Exception:
                continue
            posts.extend(find_post_dicts(data, aweme_id, limit=5))
        return posts

    async def _goto(self, page, url: str) -> None:
        try:
            await page.goto(_rebase(url), wait_until="domcontentloaded", timeout=60000)
        except Exception as exc:
            if self.cancel.is_set():
                return
            if page.is_closed():
                raise BrowserError("Tarayıcı penceresi kapatıldı. İşlem durduruldu.")
            message = str(exc).splitlines()[0]
            if "net::ERR_INTERNET_DISCONNECTED" in message or "ERR_NAME_NOT_RESOLVED" in message:
                raise BrowserError("İnternet bağlantısı yok ya da Douyin'e ulaşılamıyor.")
            if "Timeout" not in message:
                raise BrowserError(f"Sayfa açılamadı: {message}")
            self.log("warn", "Sayfa yavaş açılıyor, mevcut haliyle devam ediliyor.")

    # ------------------------------------------------------------ kısa link
    async def resolve(self, parsed: links.ParsedLink) -> links.ParsedLink:
        """Kısa linki açıp gerçek adresi bulur."""
        if parsed.kind != links.SHORT:
            return parsed
        self._check_alive()
        page = self.page
        self.log("info", "Kısa link açılıyor…")
        await self._goto(page, parsed.url)
        for _ in range(30):
            result = links.classify_final_url(page.url)
            if result.ok:
                return result
            if await self.wait_if_blocked(page):
                continue
            await asyncio.sleep(0.5)
        raise BrowserError("Kısa link bir videoya ya da profile açılmadı. Linkin doğru olduğundan emin ol.")

    # ------------------------------------------------------------ tek gönderi
    async def get_post(self, aweme_id: str, url: str = "", *, page=None, quiet: bool = False) -> Dict[str, Any]:
        self._check_alive()
        page = page or self.page
        found: Dict[str, Any] = {}
        not_found = {"flag": False}
        tasks: List[asyncio.Task] = []

        async def handle(response):
            if DETAIL_API not in response.url and POST_API not in response.url:
                return
            data = await self._json(response)
            if not isinstance(data, dict):
                return
            detail = data.get("aweme_detail")
            if isinstance(detail, dict) and aweme_id_of(detail) == aweme_id:
                found.setdefault("item", detail)
                return
            if DETAIL_API in response.url and data.get("aweme_detail") is None and data.get("filter_detail"):
                not_found["flag"] = True
            for item in data.get("aweme_list") or []:
                if isinstance(item, dict) and aweme_id_of(item) == aweme_id:
                    found.setdefault("item", item)

        def on_response(response):
            tasks.append(asyncio.ensure_future(handle(response)))

        page.on("response", on_response)
        try:
            target = url or f"https://www.douyin.com/video/{aweme_id}"
            if not quiet:
                self.log("info", "Gönderi sayfası açılıyor…")
            await self._goto(page, target)
            deadline = time.monotonic() + 25
            embedded_checked = 0.0
            while time.monotonic() < deadline:
                if self.cancel.is_set():
                    raise asyncio.CancelledError()
                self._check_alive()
                if "item" in found:
                    return found["item"]
                if await self.wait_if_blocked(page):
                    deadline = time.monotonic() + 25
                    await self._goto(page, target)
                    continue
                now = time.monotonic()
                if now - embedded_checked > 2:
                    embedded_checked = now
                    posts = await self._embedded_posts(page, aweme_id)
                    if posts:
                        return posts[0]
                if not_found["flag"]:
                    break
                await asyncio.sleep(0.5)
        finally:
            page.remove_listener("response", on_response)
            if tasks:
                await asyncio.gather(*tasks, return_exceptions=True)
        if "item" in found:
            return found["item"]
        raise BrowserError(
            "Bu gönderinin bilgisi alınamadı. Gönderi silinmiş, gizli ya da sadece giriş yapanlara açık olabilir."
        )

    # ------------------------------------------------------------ profil
    async def collect_profile(
        self,
        sec_uid: str,
        *,
        limit: int,
        on_items: Callable[[List[Dict[str, Any]]], None],
        idle_seconds: Optional[float] = None,
    ) -> ProfileResult:
        if idle_seconds is None:
            idle_seconds = float(os.environ.get("DOUYIN_INDIRICI_IDLE", "25"))
        self._check_alive()
        page = self.page
        st = _ProfileState()
        tasks: List[asyncio.Task] = []

        def accept(items: List[Any]) -> None:
            new = []
            for item in items:
                if not isinstance(item, dict):
                    continue
                aid = aweme_id_of(item)
                if not aid or aid in st.items:
                    continue
                if limit and len(st.order) >= limit:
                    break
                st.items[aid] = item
                st.order.append(aid)
                new.append(item)
            if new:
                st.last_change = time.monotonic()
                if not st.author_name:
                    author = new[0].get("author") if isinstance(new[0].get("author"), dict) else {}
                    st.author_name = str(author.get("nickname") or "")
                on_items(new)

        async def handle(response):
            url = response.url
            if POST_API in url:
                data = await self._json(response)
                if not isinstance(data, dict):
                    return
                st.responses += 1
                if "has_more" in data:
                    try:
                        st.has_more = int(data.get("has_more") or 0)
                    except (TypeError, ValueError):
                        st.has_more = None
                accept(data.get("aweme_list") or [])
            elif PROFILE_API in url:
                data = await self._json(response)
                user = data.get("user") if isinstance(data, dict) else None
                if isinstance(user, dict):
                    st.author_name = str(user.get("nickname") or st.author_name)
                    try:
                        st.reported_count = int(user.get("aweme_count"))
                    except (TypeError, ValueError):
                        pass
                    if user.get("secret") in (1, True) or user.get("private_status") in (1, True):
                        st.private = True

        def on_response(response):
            tasks.append(asyncio.ensure_future(handle(response)))

        page.on("response", on_response)
        result = ProfileResult()
        try:
            self.log("info", "Profil sayfası açılıyor…")
            await self._goto(page, f"https://www.douyin.com/user/{sec_uid}")
            await asyncio.sleep(2)
            if await self.wait_if_blocked(page):
                await self._goto(page, f"https://www.douyin.com/user/{sec_uid}")
                await asyncio.sleep(2)

            # Sayfaya gömülü ilk gönderiler (bazı sürümlerde ilk sayfa böyle gelir).
            if not st.order:
                accept(await self._embedded_posts(page, ""))

            result.reason = await self._scroll_loop(page, st, limit, idle_seconds)

            # Hiç liste yanıtı yakalanamadıysa: sayfadaki bağlantılardan tek tek dene.
            if not st.order and result.reason not in ("cancel", "private", "empty"):
                ids = await self._ids_from_dom(page)
                if ids:
                    self.log("info", f"Liste doğrudan alınamadı; {len(ids)} gönderi tek tek açılacak.")
                    for aid in ids:
                        if self.cancel.is_set() or (limit and len(st.order) >= limit):
                            break
                        try:
                            accept([await self.get_post(aid, quiet=True)])
                        except BrowserError as exc:
                            self.log("warn", f"{aid}: {exc}")
                    result.reason = "cancel" if self.cancel.is_set() else "end"
        finally:
            page.remove_listener("response", on_response)
            if tasks:
                await asyncio.gather(*tasks, return_exceptions=True)

        result.author_name = st.author_name
        result.reported_count = max(st.reported_count, 0)
        result.collected = len(st.order)
        result.private = st.private
        if st.private and not st.order:
            result.reason = "private"
        elif st.reported_count == 0 and not st.order:
            result.reason = "empty"
        return result

    async def _scroll_loop(self, page, st: _ProfileState, limit: int, idle_seconds: float) -> str:
        try:
            size = await page.evaluate("[window.innerWidth, window.innerHeight]")
            await page.mouse.move(size[0] * 0.6, size[1] * 0.7)
        except Exception:
            pass
        while True:
            if self.cancel.is_set():
                return "cancel"
            self._check_alive()
            if limit and len(st.order) >= limit:
                return "limit"
            if st.has_more == 0 and st.responses > 0:
                return "end"
            if st.private and not st.order:
                return "private"
            if st.reported_count == 0 and time.monotonic() - st.last_change > 6:
                return "empty"
            if await self.wait_if_blocked(page):
                st.last_change = time.monotonic()
                continue
            await self._dismiss_popups(page)
            try:
                await page.mouse.wheel(0, 2400)
                await page.evaluate(
                    """() => {
                        window.scrollBy(0, 2400);
                        for (const el of document.querySelectorAll('[data-e2e="user-post-list"], [class*="scroll"], main')) {
                            if (el.scrollHeight > el.clientHeight + 50) el.scrollTop += 2400;
                        }
                    }"""
                )
            except Exception:
                pass
            await asyncio.sleep(1.3)
            if time.monotonic() - st.last_change > idle_seconds:
                if st.has_more == 1:
                    return "login_wall"
                return "stalled" if st.responses == 0 else "end"

    async def _dismiss_popups(self, page) -> None:
        """Giriş yap kutusu gibi kaydırmayı engelleyen pencereleri kapatmayı dener."""
        try:
            await page.evaluate(
                """() => {
                    const sel = ['.dy-account-close', '[class*="login"] [class*="close"]', '[aria-label="关闭"]'];
                    for (const s of sel) for (const el of document.querySelectorAll(s)) {
                        const r = el.getBoundingClientRect();
                        if (r.width > 0 && r.height > 0) el.click();
                    }
                }"""
            )
        except Exception:
            pass

    async def _ids_from_dom(self, page) -> List[str]:
        try:
            hrefs = await page.evaluate(
                "() => [...document.querySelectorAll('a[href]')].map(a => a.getAttribute('href'))"
            )
        except Exception:
            return []
        ids: List[str] = []
        for href in hrefs or []:
            m = re.search(r"/(?:video|note)/(\d{15,22})", href or "")
            if m and m.group(1) not in ids:
                ids.append(m.group(1))
        return ids

    # ------------------------------------------------------------ indirme yedeği
    async def download_via_browser(self, url: str, headers: Dict[str, str]) -> Optional[tuple]:
        """Normal indirme reddedilirse tarayıcının kendi bağlantısıyla dener."""
        if self.context is None:
            return None
        try:
            response = await self.context.request.get(url, headers=headers, timeout=120000)
            if response.ok:
                body = await response.body()
                content_type = response.headers.get("content-type", "")
                if body and not content_type.startswith(("text/", "application/json")):
                    return body, content_type
        except Exception:
            return None
        return None

    # ------------------------------------------------------------ giriş
    async def login_session(self) -> None:
        """Kullanıcının isteğe bağlı olarak Douyin'e giriş yapması için pencereyi açık tutar."""
        page = self.page
        await self._goto(page, "https://www.douyin.com/")
        self.log("info", "Douyin açıldı. Giriş yapabilirsin (isteğe bağlı). Bitince tarayıcı penceresini kapat.")
        while not page.is_closed() and not self.cancel.is_set():
            await asyncio.sleep(1)
