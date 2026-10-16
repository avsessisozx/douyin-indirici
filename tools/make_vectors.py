"""Python ve Kotlin sürümlerinin birlikte kullandığı test vakalarını üretir.

Çıktı: tests/vectors/*.json. Python testleri bu dosyaların Python koduyla
birebir uyduğunu, Android testleri de Kotlin koduyla uyduğunu doğrular.
Böylece iki sürüm aynı linki aynı şekilde tanır, aynı kaliteyi seçer,
dosyaları aynı adla kaydeder.

Kullanım: python tools/make_vectors.py
"""

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from douyin_indirici import links, media, naming  # noqa: E402
from tests import samples  # noqa: E402

OUT = ROOT / "tests" / "vectors"
SEC = "MS4wLjABAAAA6O7EZyfDRYXxJrUTpf91K3tmB4rBROkAw-nYMfld8ss"

LINK_INPUTS = [
    f"https://www.douyin.com/user/{SEC}?from_tab_name=main",
    f"https://www.douyin.com/user/{SEC}",
    f"https://www.douyin.com/user/{SEC}?modal_id=7412345678901234567",
    "https://www.douyin.com/video/7412345678901234567",
    "https://www.douyin.com/note/7412345678901234568",
    "https://www.douyin.com/slides/7412345678901234568",
    "https://www.douyin.com/?recommend=1&modal_id=7412345678901234569",
    "https://www.douyin.com/jingxuan?modal_id=7412345678901234560",
    "https://www.douyin.com/search/cat?aid=1&modal_id=7412345678901234561&type=general",
    "www.douyin.com/video/7412345678901234567",
    "douyin.com/video/7412345678901234567?previous_page=app",
    "7.94 复制打开抖音，看看【某某的作品】风景很美 # 旅行 https://v.douyin.com/iABcDeF/ 8@5.com :9pm",
    "https://v.douyin.com/iABcDeF/",
    "https://v.douyin.com/",
    "https://www.iesdouyin.com/share/video/7412345678901234567/?region=CN",
    f"https://www.iesdouyin.com/share/user/{SEC}",
    "7412345678901234567",
    "",
    "   ",
    "merhaba",
    "https://www.tiktok.com/@x/video/1",
    "https://www.douyin.com/",
    "https://www.douyin.com/jingxuan",
    "https://live.douyin.com/123456",
    "https://www.douyin.com/follow/live/123456",
    "https://www.douyin.com/user/self",
    "https://www.douyin.com/user/self?modal_id=7412345678901234567",
    "https://www.douyin.com/user/abc",
    "https://www.douyin.com/collection/7412345678901234567",
    "https://www.douyin.com/music/7412345678901234567",
    "https://www.douyin.com/discover?aweme_id=7412345678901234562",
    "https://www.douyin.com/video/7412345678901234567).",
]

FINAL_URLS = [
    "https://www.douyin.com/video/7412345678901234567?previous_page=x",
    "https://www.douyin.com/note/7412345678901234567",
    f"https://www.iesdouyin.com/share/user/{SEC}?x=1",
    f"https://www.douyin.com/user/{SEC}",
    "https://www.douyin.com/",
]

SANITIZE = [
    ['a<b>:c"/d\\e|f?g*h', 60],
    ["CON", 60],
    ["com3.txt", 60],
    ["  ...  ", 60],
    ["x‮yz\nq", 60],
    ["a" * 300, 40],
    ["  trailing dots...  ", 60],
    ["中文 标题 😀 emoji", 60],
    ["tab\there", 60],
    ["", 60],
    [None, 60],
]

TITLES = ["Güzel manzara #travel #china", "#风景 #photo", "", "   ", "Sadece metin", "#a#b c #d", "a" * 100]


def link_case(text):
    p = links.parse_link(text)
    return {"input": text, "kind": p.kind, "url": p.url, "aweme_id": p.aweme_id, "sec_uid": p.sec_uid,
            "error": p.error.key if p.error else None, "describe": p.describe().key}


def post_case(name, item):
    out = {"name": name, "item": item, "results": []}
    for content in ("all", "video", "image"):
        for quality in media.QUALITY_CHOICES:
            for compat in (True, False):
                post = media.build_post(item, content=content, quality=quality, compat=compat)
                out["results"].append({
                    "content": content, "quality": quality, "compat": compat,
                    "aweme_id": post.aweme_id, "author_name": post.author_name, "author_sec_uid": post.author_sec_uid,
                    "is_gallery": post.is_gallery,
                    "skipped": post.skipped_reason.key if post.skipped_reason else None,
                    # tarih kısmı saat dilimine bağlı; karşılaştırma ilk "_" sonrasıyla yapılır
                    "stem_tail": post.stem.split("_", 1)[1],
                    "files": [{"rel_tail": f.rel_stem.split("_", 1)[1], "kind": f.kind, "ext": f.ext,
                               "urls": f.urls, "size": f.expected_size} for f in post.files],
                })
    return out


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    data = {
        "parse": [link_case(t) for t in LINK_INPUTS],
        "final_url": [{"input": u, "kind": links.classify_final_url(u).kind,
                       "aweme_id": links.classify_final_url(u).aweme_id,
                       "sec_uid": links.classify_final_url(u).sec_uid} for u in FINAL_URLS],
    }
    (OUT / "links.json").write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")

    deleted = samples.api_video("7400000000000000009")
    deleted["video"] = {}
    note_video = samples.api_video("7400000000000000010")
    note_video["aweme_type"] = 68
    gallery_no_list = {"aweme_id": "7400000000000000011", "aweme_type": 68, "desc": "x", "create_time": 1,
                       "author": {"nickname": "n", "sec_uid": "s"}, "video": {}}
    image_post_info = {"aweme_id": "7400000000000000012", "desc": "ipi", "create_time": 1717200000,
                       "author": {"nickname": "n", "sec_uid": "s"},
                       "image_post_info": {"images": [{"display_image": {"url_list": ["https://p/x.webp", "https://p/x.jpeg"]}}]}}
    watermark_only = {"aweme_id": "7400000000000000013", "desc": "wm", "create_time": 1717200000,
                      "author": {"nickname": "n", "sec_uid": "s"},
                      "images": [{"download_url_list": ["https://p/a~tplv-dy-water.jpeg"]}]}
    no_bitrate = {"aweme_id": "7400000000000000014", "desc": "nb", "create_time": 1717200000,
                  "author": {"nickname": "n", "sec_uid": "s"},
                  "video": {"width": 720, "height": 1280, "play_addr": {"url_list": ["https://v/a.mp4"]},
                            "play_addr_265": {"url_list": ["https://v/a265.mp4"]}}}
    posts = [
        post_case("api_video", samples.api_video()),
        post_case("api_gallery", samples.api_gallery()),
        post_case("ssr_video", samples.ssr_video()),
        post_case("deleted", deleted),
        post_case("note_video", note_video),
        post_case("gallery_no_list", gallery_no_list),
        post_case("image_post_info", image_post_info),
        post_case("watermark_only", watermark_only),
        post_case("no_bitrate", no_bitrate),
    ]
    (OUT / "posts.json").write_text(json.dumps(posts, ensure_ascii=False, indent=1), encoding="utf-8")

    naming_data = {
        "sanitize": [{"input": i, "max_len": m, "output": naming.sanitize(i, max_len=m)} for i, m in SANITIZE],
        "title": [{"input": t, "output": naming.title_from_desc(t)} for t in TITLES],
        "author_folder": [{"name": n, "sec_uid": s, "output": media.author_folder(n, s)}
                          for n, s in [("Ad Soyad", "x"), ("", SEC), ("<>", ""), ("a" * 80, "s")]],
    }
    (OUT / "naming.json").write_text(json.dumps(naming_data, ensure_ascii=False, indent=1), encoding="utf-8")
    print("vectors:", len(data["parse"]), "links,", sum(len(p["results"]) for p in posts), "post results")


if __name__ == "__main__":
    main()
