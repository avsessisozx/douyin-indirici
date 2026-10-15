"""Douyin gönderi verisinden indirilecek dosyaları çıkarır.

Douyin aynı veriyi iki biçimde verebiliyor:
  * Web API yanıtı (aweme/post, aweme/detail): snake_case anahtarlar
    (bit_rate, play_addr, url_list ...)
  * Sayfanın içine gömülü veri (RENDER_DATA): camelCase anahtarlar
    (bitRateList, playAddr: [{src}], urlList ...)
Bu modül ikisini de okur.

Kalite seçimi ve resim sıralama mantığı jiji262/douyin-downloader (MIT)
projesindeki yaklaşımdan uyarlanmıştır.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, Iterable, List, Optional
from urllib.parse import urlparse

from .naming import post_stem, sanitize

QUALITY_HIGHEST = "highest"
QUALITY_LOWEST = "lowest"
QUALITY_CHOICES = (QUALITY_HIGHEST, "1080p", "720p", "540p", QUALITY_LOWEST)

CONTENT_ALL = "all"
CONTENT_VIDEO = "video"
CONTENT_IMAGE = "image"

_WATERMARK_HINTS = ("tplv-dy-water", "dy-water", "owner_watermark", "watermark_image", "watermark=1", "playwm")
_GALLERY_TYPES = {2, 68, 150}


@dataclass
class MediaFile:
    urls: List[str]
    rel_stem: str            # yazar klasörüne göre yol, uzantısız
    kind: str                # "video" | "image" | "live"
    ext: str = ""            # bilinmiyorsa indirirken içerik türünden belirlenir
    expected_size: int = 0


@dataclass
class Post:
    aweme_id: str
    desc: str
    create_time: int
    author_name: str
    author_sec_uid: str
    is_gallery: bool
    files: List[MediaFile] = field(default_factory=list)
    skipped_reason: str = ""

    @property
    def stem(self) -> str:
        return post_stem(self.aweme_id, self.desc, self.create_time)


# ---------------------------------------------------------------- yardımcılar

def _get(d: Any, *keys: str) -> Any:
    if not isinstance(d, dict):
        return None
    for key in keys:
        if key in d and d[key] not in (None, "", [], {}):
            return d[key]
    return None


def _int(value: Any) -> int:
    try:
        return int(float(str(value)))
    except (TypeError, ValueError):
        return 0


def _norm_url(url: str) -> str:
    url = url.strip()
    if url.startswith("//"):
        return "https:" + url
    return url


def urls_of(source: Any) -> List[str]:
    """url_list / urlList / [{src}] / str biçimlerinin hepsinden URL listesi çıkarır."""
    out: List[str] = []
    if isinstance(source, str):
        if source.strip():
            out.append(_norm_url(source))
    elif isinstance(source, dict):
        lst = _get(source, "url_list", "urlList")
        if isinstance(lst, list):
            out.extend(_norm_url(u) for u in lst if isinstance(u, str) and u.strip())
        else:
            single = _get(source, "src", "url")
            if isinstance(single, str):
                out.append(_norm_url(single))
    elif isinstance(source, list):
        for entry in source:
            out.extend(urls_of(entry))
    seen = set()
    result = []
    for u in out:
        if u.startswith("http") and u not in seen:
            seen.add(u)
            result.append(u)
    return result


def is_watermarked(url: str) -> bool:
    low = url.lower()
    return any(h in low for h in _WATERMARK_HINTS)


# ---------------------------------------------------------------- video

@dataclass
class VideoTier:
    urls: List[str]
    width: int
    height: int
    bitrate: int
    h265: bool
    size: int

    @property
    def short_edge(self) -> int:
        if self.width and self.height:
            return min(self.width, self.height)
        return max(self.width, self.height)

    @property
    def pixels(self) -> int:
        return self.width * self.height


def _is_h265(entry: Dict[str, Any], play_addr: Any) -> bool:
    for key in ("is_h265", "isH265", "is_bytevc1", "isBytevc1"):
        if _int(_get(entry, key)) == 1:
            return True
    for key in ("codec_type", "CodecType", "codecType", "gear_name", "gearName", "format"):
        value = str(_get(entry, key) or "").lower()
        if "265" in value or "bytevc1" in value or "hevc" in value:
            return True
    for url in urls_of(play_addr)[:1]:
        low = url.lower()
        if "bytevc1" in low or "h265" in low:
            return True
    return False


def video_tiers(video: Dict[str, Any]) -> List[VideoTier]:
    tiers: List[VideoTier] = []
    entries = _get(video, "bit_rate", "bitRateList")
    if isinstance(entries, list):
        for entry in entries:
            if not isinstance(entry, dict):
                continue
            play_addr = _get(entry, "play_addr", "playAddr")
            urls = [u for u in urls_of(play_addr) if not is_watermarked(u)]
            if not urls:
                continue
            pa = play_addr if isinstance(play_addr, dict) else {}
            tiers.append(VideoTier(
                urls=urls,
                width=_int(_get(pa, "width") or _get(entry, "width")),
                height=_int(_get(pa, "height") or _get(entry, "height")),
                bitrate=_int(_get(entry, "bit_rate", "bitRate")),
                h265=_is_h265(entry, play_addr),
                size=_int(_get(pa, "data_size", "dataSize") or _get(entry, "data_size", "dataSize")),
            ))
    return tiers


def _fallback_tiers(video: Dict[str, Any]) -> List[VideoTier]:
    """bit_rate listesi yoksa video.play_addr gibi tekil adresleri kullan."""
    tiers: List[VideoTier] = []
    width, height = _int(_get(video, "width")), _int(_get(video, "height"))
    for key, h265 in (("play_addr_h264", False), ("play_addr", False), ("playAddr", False),
                      ("playApi", False), ("play_addr_265", True)):
        source = video.get(key) if isinstance(video, dict) else None
        urls = [u for u in urls_of(source) if not is_watermarked(u)]
        if urls:
            pa = source if isinstance(source, dict) else {}
            tiers.append(VideoTier(urls, _int(_get(pa, "width")) or width, _int(_get(pa, "height")) or height,
                                   0, h265, _int(_get(pa, "data_size", "dataSize"))))
    return tiers


def choose_video_urls(video: Dict[str, Any], quality: str = QUALITY_HIGHEST,
                      compat: bool = True) -> Optional[VideoTier]:
    """Seçilen kaliteye en uygun video katmanını döndürür.

    compat=True ise H.264 katmanları tercih edilir (her oynatıcıda açılır);
    hiç H.264 yoksa H.265 kullanılır.
    """
    if not isinstance(video, dict):
        return None
    tiers = video_tiers(video)
    fallback = _fallback_tiers(video)
    pool = tiers or fallback
    if not pool:
        return None
    if compat:
        h264 = [t for t in pool if not t.h265]
        if h264:
            pool = h264

    q = (quality or QUALITY_HIGHEST).lower()
    if q == QUALITY_LOWEST:
        chosen = min(pool, key=lambda t: (t.bitrate or 10**12, t.pixels or 10**12))
    elif q.endswith("p") and q[:-1].isdigit():
        target = int(q[:-1])
        chosen = min(pool, key=lambda t: (abs(t.short_edge - target) if t.short_edge else 10**6,
                                          -t.bitrate, -t.pixels))
    else:
        chosen = max(pool, key=lambda t: (t.pixels, t.bitrate))

    # Seçilen katmanın aynaları bitince, aynı videonun başka bir adresine düş.
    extra: List[str] = []
    for t in fallback:
        if compat and t.h265 and not chosen.h265:
            continue
        extra.extend(t.urls)
    urls = list(dict.fromkeys(chosen.urls + extra))
    return VideoTier(urls, chosen.width, chosen.height, chosen.bitrate, chosen.h265, chosen.size)


# ---------------------------------------------------------------- resimler

def gallery_items(item: Dict[str, Any]) -> List[Dict[str, Any]]:
    info = item.get("image_post_info") if isinstance(item, dict) else None
    if isinstance(info, dict):
        for key in ("images", "image_list"):
            value = info.get(key)
            if isinstance(value, list) and value:
                return [x for x in value if isinstance(x, dict)]
    for key in ("images", "image_list", "imageList"):
        value = item.get(key) if isinstance(item, dict) else None
        if isinstance(value, list) and value:
            return [x for x in value if isinstance(x, dict)]
    return []


def _url_ext(url: str) -> str:
    path = (urlparse(url).path or "").lower()
    for ext in (".jpeg", ".jpg", ".png", ".webp", ".heic", ".avif", ".gif"):
        if ext in path:
            return ext
    return ""


def image_urls(image: Dict[str, Any]) -> List[str]:
    """Bir resim için filigransız, en iyi biçimdeki adresleri sırayla döndürür."""
    ranked: List[tuple] = []
    sources = (
        (_get(image, "watermark_free_download_url_list"), 0),
        (image, 1),
        (_get(image, "origin_image", "originImage"), 1),
        (_get(image, "display_image", "displayImage"), 2),
        (_get(image, "download_url_list", "downloadUrlList"), 5),
        (_get(image, "download_url", "downloadUrl"), 5),
    )
    for source, rank in sources:
        for url in urls_of(source):
            ext = _url_ext(url)
            fmt_rank = {".jpeg": 0, ".jpg": 0, ".png": 0}.get(ext, 1 if ext == ".webp" else 2)
            ranked.append((1 if is_watermarked(url) else 0, rank, fmt_rank, len(ranked), url))
    ranked.sort()
    clean = [r[-1] for r in ranked if r[0] == 0]
    marked = [r[-1] for r in ranked if r[0] == 1]
    return list(dict.fromkeys(clean or marked))


# ---------------------------------------------------------------- gönderi

def author_of(item: Dict[str, Any]) -> tuple:
    author = item.get("author") if isinstance(item, dict) else None
    author = author if isinstance(author, dict) else {}
    name = str(_get(author, "nickname") or "").strip()
    sec_uid = str(_get(author, "sec_uid", "secUid") or "").strip()
    return name, sec_uid


def aweme_id_of(item: Dict[str, Any]) -> str:
    return str(_get(item, "aweme_id", "awemeId") or "").strip()


def is_gallery(item: Dict[str, Any]) -> bool:
    if gallery_items(item):
        return True
    # Resim listesi gelmemiş ama türü resimli gönderi olan ve videosu olmayanlar.
    video = item.get("video") if isinstance(item.get("video"), dict) else {}
    return _int(_get(item, "aweme_type", "awemeType")) in _GALLERY_TYPES and choose_video_urls(video) is None


def build_post(item: Dict[str, Any], *, content: str = CONTENT_ALL,
               quality: str = QUALITY_HIGHEST, compat: bool = True) -> Post:
    aweme_id = aweme_id_of(item)
    name, sec_uid = author_of(item)
    post = Post(
        aweme_id=aweme_id,
        desc=str(_get(item, "desc") or ""),
        create_time=_int(_get(item, "create_time", "createTime")),
        author_name=name,
        author_sec_uid=sec_uid,
        is_gallery=is_gallery(item),
    )
    stem = post.stem

    if post.is_gallery:
        if content == CONTENT_VIDEO:
            post.skipped_reason = "resimli gönderi (sadece video seçili)"
            return post
        images = gallery_items(item)
        width = max(2, len(str(len(images))))
        for index, image in enumerate(images, start=1):
            num = str(index).zfill(width)
            urls = image_urls(image)
            if urls:
                post.files.append(MediaFile(urls, f"{stem}/{num}", "image"))
            live_video = image.get("video") if isinstance(image.get("video"), dict) else None
            if live_video:
                tier = choose_video_urls(live_video, quality, compat)
                if tier:
                    post.files.append(MediaFile(tier.urls, f"{stem}/{num}_hareketli", "live", ".mp4", tier.size))
        if not post.files:
            post.skipped_reason = "resim adresi bulunamadı"
        return post

    if content == CONTENT_IMAGE:
        post.skipped_reason = "video (sadece resim seçili)"
        return post
    video = item.get("video") if isinstance(item.get("video"), dict) else {}
    tier = choose_video_urls(video, quality, compat)
    if tier is None:
        post.skipped_reason = "video adresi bulunamadı (silinmiş, gizli ya da ücretli olabilir)"
        return post
    post.files.append(MediaFile(tier.urls, stem, "video", ".mp4", tier.size))
    return post


def author_folder(name: str, sec_uid: str) -> str:
    return sanitize(name, max_len=40, fallback="") or sanitize(sec_uid[:16], fallback="douyin")


def find_post_dicts(data: Any, aweme_id: str = "", limit: int = 500) -> Iterable[Dict[str, Any]]:
    """Herhangi bir JSON ağacında gönderi gibi görünen sözlükleri bulur."""
    stack = [data]
    seen = 0
    while stack and seen < 200000:
        node = stack.pop()
        seen += 1
        if isinstance(node, dict):
            node_id = aweme_id_of(node)
            if node_id and (not aweme_id or node_id == aweme_id) and (
                isinstance(node.get("video"), dict) or gallery_items(node)
            ):
                yield node
                limit -= 1
                if limit <= 0:
                    return
                continue
            stack.extend(node.values())
        elif isinstance(node, list):
            stack.extend(node)
