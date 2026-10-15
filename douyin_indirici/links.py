"""Kullanıcının yapıştırdığı metinden Douyin linkini bulur ve türünü anlar.

Desteklenen biçimler:
  - https://www.douyin.com/user/<sec_uid>                 → profil
  - https://www.douyin.com/user/<sec_uid>?modal_id=<id>   → gönderi (+ sahibinin profili)
  - https://www.douyin.com/video/<id>                     → gönderi
  - https://www.douyin.com/note/<id>  (/slides/, /gallery/) → resimli gönderi
  - https://www.douyin.com/?recommend=1&modal_id=<id>     → akışta açılmış gönderi
  - https://www.douyin.com/jingxuan?modal_id=<id>         → akışta açılmış gönderi
  - https://v.douyin.com/xxxx/                            → kısa link (açılınca anlaşılır)
  - https://www.iesdouyin.com/share/video/<id>            → paylaşım linki
  - Uygulamadan kopyalanan Çince paylaşım metni (içindeki link bulunur)
  - Sadece gönderi numarası (19 haneli sayı)
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Optional
from urllib.parse import parse_qs, urlparse

PROFILE = "profile"
POST = "post"
SHORT = "short"

_URL_RE = re.compile(r"(?:https?://)?(?:[a-z0-9-]+\.)*(?:douyin|iesdouyin)\.com[^\s，。！？、\"'<>]*", re.I)
_ID_RE = re.compile(r"^\d{15,22}$")
_SEC_UID_RE = re.compile(r"^[A-Za-z0-9_\-]{20,}$")


@dataclass
class ParsedLink:
    kind: Optional[str]          # PROFILE / POST / SHORT / None
    url: str = ""
    aweme_id: str = ""
    sec_uid: str = ""
    error: str = ""

    @property
    def ok(self) -> bool:
        return self.kind is not None

    def describe(self) -> str:
        if self.kind == PROFILE:
            return "Profil linki algılandı."
        if self.kind == POST:
            return "Tek gönderi linki algılandı."
        if self.kind == SHORT:
            return "Kısa link algılandı. Açıldığında video mu profil mi olduğu anlaşılacak."
        return self.error


def _first_query(qs: dict, key: str) -> str:
    values = qs.get(key) or []
    value = values[0].strip() if values else ""
    return value


def parse_link(text: str) -> ParsedLink:
    text = (text or "").strip()
    if not text:
        return ParsedLink(None, error="Önce bir Douyin linki yapıştır.")

    if _ID_RE.match(text):
        return ParsedLink(POST, url=f"https://www.douyin.com/video/{text}", aweme_id=text)

    match = _URL_RE.search(text)
    if not match:
        if "tiktok.com" in text.lower():
            return ParsedLink(None, error="Bu bir TikTok linki. Bu program sadece Douyin (douyin.com) linkleriyle çalışır.")
        return ParsedLink(None, error="Metinde Douyin linki bulunamadı. Adres çubuğundaki linki kopyalayıp yapıştır.")

    url = match.group(0).rstrip(".,;)]}")
    if not url.lower().startswith("http"):
        url = "https://" + url
    parsed = urlparse(url)
    host = (parsed.hostname or "").lower()
    path = parsed.path or "/"
    qs = parse_qs(parsed.query)

    if host in ("v.douyin.com", "v.iesdouyin.com"):
        if len(path.strip("/")) < 3:
            return ParsedLink(None, error="Kısa link eksik görünüyor. Linkin tamamını kopyala.")
        return ParsedLink(SHORT, url=url)

    if host == "live.douyin.com" or "/live/" in path or path.startswith("/follow/live"):
        return ParsedLink(None, error="Bu bir canlı yayın linki. Canlı yayınlar indirilemiyor.")

    modal_id = _first_query(qs, "modal_id")
    if modal_id and not modal_id.isdigit():
        modal_id = ""

    m = re.match(r"^/(?:share/)?user/([^/?#]+)", path)
    if m:
        sec_uid = m.group(1)
        if sec_uid == "self":
            if modal_id:
                return ParsedLink(POST, url=f"https://www.douyin.com/video/{modal_id}", aweme_id=modal_id)
            return ParsedLink(None, error="Bu senin kendi profil sayfan. İndirmek istediğin kişinin profil linkini yapıştır.")
        if not _SEC_UID_RE.match(sec_uid):
            return ParsedLink(None, error="Profil linki eksik ya da bozuk görünüyor. Linkin tamamını kopyala.")
        if modal_id:
            return ParsedLink(POST, url=f"https://www.douyin.com/video/{modal_id}", aweme_id=modal_id, sec_uid=sec_uid)
        return ParsedLink(PROFILE, url=f"https://www.douyin.com/user/{sec_uid}", sec_uid=sec_uid)

    m = re.match(r"^/(?:share/)?(video|note|slides|gallery)/(\d+)", path)
    if m:
        aweme_id = m.group(2)
        kind_path = "video" if m.group(1) == "video" else "note"
        return ParsedLink(POST, url=f"https://www.douyin.com/{kind_path}/{aweme_id}", aweme_id=aweme_id)

    if modal_id:
        return ParsedLink(POST, url=f"https://www.douyin.com/video/{modal_id}", aweme_id=modal_id)

    for key in ("aweme_id", "vid", "item_id"):
        value = _first_query(qs, key)
        if value.isdigit() and len(value) >= 15:
            return ParsedLink(POST, url=f"https://www.douyin.com/video/{value}", aweme_id=value)

    if re.match(r"^/(?:collection|mix)/", path):
        return ParsedLink(None, error="Bu bir koleksiyon (合集) linki. Şimdilik profil ve tek gönderi linkleri destekleniyor.")
    if path.startswith("/music/"):
        return ParsedLink(None, error="Bu bir müzik sayfası linki. Şimdilik profil ve tek gönderi linkleri destekleniyor.")

    return ParsedLink(
        None,
        error="Bu linkte belirli bir video ya da profil yok. Videoyu ya da profili açıp adres çubuğundaki linki kopyala.",
    )


_POST_PATH_RE = re.compile(r"/(?:share/)?(?:video|note|slides|gallery)/(\d+)")
_USER_PATH_RE = re.compile(r"/(?:share/)?user/([A-Za-z0-9_\-]{20,})")


def classify_final_url(url: str) -> ParsedLink:
    """Kısa link açıldıktan sonra tarayıcının vardığı adresi yorumlar."""
    parsed = parse_link(url)
    if parsed.ok and parsed.kind != SHORT:
        return parsed
    m = _POST_PATH_RE.search(url)
    if m:
        return ParsedLink(POST, url=url, aweme_id=m.group(1))
    m = _USER_PATH_RE.search(url)
    if m:
        return ParsedLink(PROFILE, url=url, sec_uid=m.group(1))
    return ParsedLink(None, error="Kısa link açıldı ama bir video ya da profile gitmedi.")
