import os
import sys
import tempfile
import threading
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from douyin_indirici import links, media, naming, paths  # noqa: E402
from douyin_indirici.fetcher import existing_file  # noqa: E402
from tests import samples  # noqa: E402

SEC = "MS4wLjABAAAA6O7EZyfDRYXxJrUTpf91K3tmB4rBROkAw-nYMfld8ss"


class LinkTests(unittest.TestCase):
    def k(self, text):
        return links.parse_link(text)

    def test_profile(self):
        p = self.k(f"https://www.douyin.com/user/{SEC}?from_tab_name=main")
        self.assertEqual((p.kind, p.sec_uid), (links.PROFILE, SEC))

    def test_profile_with_modal_is_post_with_owner(self):
        p = self.k(f"https://www.douyin.com/user/{SEC}?modal_id=7412345678901234567")
        self.assertEqual((p.kind, p.aweme_id, p.sec_uid), (links.POST, "7412345678901234567", SEC))

    def test_video_note_and_feed(self):
        self.assertEqual(self.k("https://www.douyin.com/video/7412345678901234567").aweme_id, "7412345678901234567")
        n = self.k("https://www.douyin.com/note/7412345678901234568")
        self.assertEqual((n.kind, n.url), (links.POST, "https://www.douyin.com/note/7412345678901234568"))
        self.assertEqual(self.k("https://www.douyin.com/?recommend=1&modal_id=7412345678901234569").aweme_id,
                         "7412345678901234569")
        self.assertEqual(self.k("https://www.douyin.com/jingxuan?modal_id=7412345678901234560").kind, links.POST)
        self.assertEqual(self.k("www.douyin.com/video/7412345678901234567").kind, links.POST)

    def test_share_text_short_link(self):
        text = "7.94 复制打开抖音，看看【某某的作品】风景很美 # 旅行 https://v.douyin.com/iABcDeF/ 8@5.com :9pm"
        p = self.k(text)
        self.assertEqual((p.kind, p.url), (links.SHORT, "https://v.douyin.com/iABcDeF/"))

    def test_iesdouyin_share(self):
        self.assertEqual(self.k("https://www.iesdouyin.com/share/video/7412345678901234567/?region=CN").kind, links.POST)

    def test_errors(self):
        for text in ["", "merhaba", "https://www.tiktok.com/@x/video/1", "https://www.douyin.com/",
                     "https://live.douyin.com/123456", "https://www.douyin.com/user/self",
                     "https://www.douyin.com/collection/7412345678901234567"]:
            p = self.k(text)
            self.assertFalse(p.ok, text)
            self.assertTrue(p.error, text)

    def test_bare_id(self):
        self.assertEqual(self.k("7412345678901234567").kind, links.POST)

    def test_final_url(self):
        self.assertEqual(links.classify_final_url("https://www.douyin.com/video/7412345678901234567?previous_page=x").kind,
                         links.POST)
        self.assertEqual(links.classify_final_url(f"https://www.iesdouyin.com/share/user/{SEC}").kind, links.PROFILE)


class NamingTests(unittest.TestCase):
    def test_sanitize(self):
        self.assertEqual(naming.sanitize('a<b>:c"/d\\e|f?g*h'), "a b c d e f g h")
        self.assertEqual(naming.sanitize("CON"), "CON_")
        self.assertEqual(naming.sanitize("  ...  "), "adsiz")
        self.assertEqual(naming.sanitize("x‮yz\nq"), "xyz q")
        self.assertLessEqual(len(naming.sanitize("a" * 300, max_len=40)), 40)

    def test_title(self):
        self.assertEqual(naming.title_from_desc("Güzel manzara #travel #china"), "Güzel manzara")
        self.assertEqual(naming.title_from_desc("#风景 #photo"), "风景 photo")
        self.assertEqual(naming.title_from_desc(""), "gonderi")

    def test_stem(self):
        stem = naming.post_stem("123", "hello", 1717200000)
        self.assertTrue(stem.endswith("_hello_123"))
        self.assertRegex(stem, r"^\d{4}-\d{2}-\d{2}_")
        self.assertTrue(naming.post_stem("1", "x", None).startswith("tarihsiz_"))


class MediaTests(unittest.TestCase):
    def test_quality_selection_compat(self):
        video = samples.api_video()["video"]
        best = media.choose_video_urls(video, "highest", compat=True)
        self.assertFalse(best.h265)
        self.assertIn("h264_1080", best.urls[0])
        self.assertIn("h264_1080_mirror", best.urls[1])
        self.assertTrue(any("play_default" in u for u in best.urls), "yedek adres eklenmeli")
        self.assertFalse(any("playwm" in u for u in best.urls), "filigranlı adres olmamalı")

    def test_quality_selection_no_compat_keeps_best(self):
        video = samples.api_video()["video"]
        best = media.choose_video_urls(video, "highest", compat=False)
        self.assertEqual(best.short_edge, 1080)

    def test_quality_targets(self):
        video = samples.api_video()["video"]
        self.assertIn("h264_720", media.choose_video_urls(video, "720p").urls[0])
        self.assertIn("h264_540", media.choose_video_urls(video, "540p").urls[0])
        self.assertIn("h264_540", media.choose_video_urls(video, "lowest").urls[0])

    def test_fallback_without_bitrate(self):
        video = {"play_addr": {"url_list": ["https://x/a.mp4"]}, "width": 720, "height": 1280}
        self.assertEqual(media.choose_video_urls(video).urls, ["https://x/a.mp4"])
        self.assertIsNone(media.choose_video_urls({}))

    def test_build_video_post(self):
        post = media.build_post(samples.api_video())
        self.assertFalse(post.is_gallery)
        self.assertEqual(len(post.files), 1)
        self.assertEqual(post.files[0].ext, ".mp4")
        self.assertTrue(post.files[0].rel_stem.endswith("_Güzel manzara_7400000000000000001"))
        self.assertEqual(post.author_name, "测试作者")

    def test_build_gallery_post(self):
        post = media.build_post(samples.api_gallery())
        self.assertTrue(post.is_gallery)
        kinds = [f.kind for f in post.files]
        self.assertEqual(kinds, ["image", "image", "live", "image"])
        first = post.files[0]
        self.assertTrue(first.urls[0].endswith(".jpeg"), "jpeg webp'den önce gelmeli")
        self.assertFalse(any("water" in u for u in first.urls))
        self.assertTrue(first.rel_stem.endswith("/01"))
        self.assertEqual(post.files[2].rel_stem.split("/")[-1], "02_hareketli")

    def test_content_filter(self):
        self.assertTrue(media.build_post(samples.api_gallery(), content="video").skipped_reason)
        self.assertTrue(media.build_post(samples.api_video(), content="image").skipped_reason)
        self.assertFalse(media.build_post(samples.api_video(), content="video").skipped_reason)

    def test_ssr_camelcase(self):
        item = samples.ssr_video()
        post = media.build_post(item)
        self.assertEqual(post.aweme_id, "7400000000000000003")
        self.assertEqual(post.author_sec_uid, "MS4wLjABAAAAssr_sec_uid_000001")
        urls = post.files[0].urls
        self.assertIn("ssr_1080_", urls[0])
        self.assertTrue(all(u.startswith("https://") for u in urls))
        self.assertTrue(any("ssr_default" in u for u in urls))

    def test_find_post_dicts(self):
        tree = {"app": {"videoDetail": samples.ssr_video()}, "other": [{"awemeId": "1"}]}
        found = list(media.find_post_dicts(tree, "7400000000000000003"))
        self.assertEqual(len(found), 1)

    def test_deleted_video(self):
        item = samples.api_video()
        item["video"] = {}
        self.assertEqual(media.build_post(item).skipped_reason.key, "skip.no_video")


class MiscTests(unittest.TestCase):
    def test_settings_roundtrip(self):
        with tempfile.TemporaryDirectory() as tmp:
            os.environ["DOUYIN_INDIRICI_DATA"] = tmp
            try:
                s = paths.load_settings()
                self.assertTrue(s["out_dir"])
                paths.save_settings({**s, "quality": "720p", "limit": "20", "content": "nope"})
                s2 = paths.load_settings()
                self.assertEqual((s2["quality"], s2["limit"], s2["content"]), ("720p", 20, "all"))
            finally:
                del os.environ["DOUYIN_INDIRICI_DATA"]

    def test_existing_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            stem = Path(tmp) / "a" / "01"
            self.assertIsNone(existing_file(stem))
            stem.parent.mkdir()
            (stem.parent / "01.webp").write_bytes(b"x")
            self.assertIsNotNone(existing_file(stem))
            self.assertIsNone(existing_file(stem, ".mp4"))


class I18nTests(unittest.TestCase):
    def test_translate_and_fallback(self):
        from douyin_indirici import i18n
        self.assertEqual(i18n.translate("ui.start", "tr"), "İndirmeyi başlat")
        self.assertEqual(i18n.translate("ui.start", "en"), "Start download")
        self.assertEqual(i18n.translate("ui.start", "xx"), "Start download")  # bilinmeyen dil -> İngilizce
        self.assertEqual(i18n.translate("yok.boyle", "tr"), "yok.boyle")
        m = i18n.msg("engine.skipped_post", reason=i18n.msg("skip.no_image"), name="a")
        self.assertEqual(m.render("tr"), "Atlandı (resim adresi bulunamadı): a")
        self.assertEqual(m.to_json()["params"]["reason"]["key"], "skip.no_image")
        self.assertEqual(i18n.translate("summary", "tr", done=3, skipped=2),
                         "Bitti: 3 dosya indirildi, 2 dosya zaten vardı.")
        self.assertEqual(i18n.translate("summary", "en", done=1), "Done: 1 files downloaded.")

    def test_translation_files_complete(self):
        import subprocess
        out = subprocess.run([sys.executable, str(Path(__file__).resolve().parents[1] / "tools" / "check_i18n.py")],
                             capture_output=True, text=True)
        self.assertEqual(out.returncode, 0, out.stdout + out.stderr)


class SharedVectorTests(unittest.TestCase):
    """tests/vectors/*.json: Android (Kotlin) sürümü de aynı dosyalarla test edilir."""

    def load(self, name):
        import json
        return json.loads((Path(__file__).resolve().parent / "vectors" / name).read_text(encoding="utf-8"))

    def test_links(self):
        data = self.load("links.json")
        for case in data["parse"]:
            p = links.parse_link(case["input"])
            got = {"kind": p.kind, "url": p.url, "aweme_id": p.aweme_id, "sec_uid": p.sec_uid,
                   "error": p.error.key if p.error else None, "describe": p.describe().key}
            want = {k: case[k] for k in got}
            self.assertEqual(got, want, case["input"])
        for case in data["final_url"]:
            p = links.classify_final_url(case["input"])
            self.assertEqual((p.kind, p.aweme_id, p.sec_uid), (case["kind"], case["aweme_id"], case["sec_uid"]))

    def test_posts(self):
        for case in self.load("posts.json"):
            for r in case["results"]:
                post = media.build_post(case["item"], content=r["content"], quality=r["quality"], compat=r["compat"])
                got = {
                    "aweme_id": post.aweme_id, "author_name": post.author_name, "author_sec_uid": post.author_sec_uid,
                    "is_gallery": post.is_gallery, "skipped": post.skipped_reason.key if post.skipped_reason else None,
                    "stem_tail": post.stem.split("_", 1)[1],
                    "files": [{"rel_tail": f.rel_stem.split("_", 1)[1], "kind": f.kind, "ext": f.ext,
                               "urls": f.urls, "size": f.expected_size} for f in post.files],
                }
                want = {k: r[k] for k in got}
                self.assertEqual(got, want, f"{case['name']} {r['content']} {r['quality']} {r['compat']}")

    def test_naming(self):
        data = self.load("naming.json")
        for c in data["sanitize"]:
            self.assertEqual(naming.sanitize(c["input"], max_len=c["max_len"]), c["output"], repr(c["input"]))
        for c in data["title"]:
            self.assertEqual(naming.title_from_desc(c["input"]), c["output"])
        for c in data["author_folder"]:
            self.assertEqual(media.author_folder(c["name"], c["sec_uid"]), c["output"])


class CaptchaParityTests(unittest.TestCase):
    def test_block_js_same_on_windows_and_android(self):
        import re
        from douyin_indirici.browser import BLOCK_JS
        kt = (Path(__file__).resolve().parents[1] / "android/app/src/main/java/io/github/avsessisozx/"
              "douyinindirici/WebDouyin.kt").read_text(encoding="utf-8")
        android_js = re.search(r'const val BLOCK_JS = """\n(.*?)"""', kt, re.S).group(1)
        self.assertEqual(BLOCK_JS, android_js)


class BrowserHealthTests(unittest.TestCase):
    def test_elf_check(self):
        from douyin_indirici import winbrowsers
        os.environ["DOUYIN_INDIRICI_CHECK_ELF"] = "1"
        try:
            with tempfile.TemporaryDirectory() as tmp:
                app = Path(tmp) / "Application"
                ver = app / "147.0.3912.86"
                ver.mkdir(parents=True)
                exe = app / "msedge.exe"
                exe.write_bytes(b"MZ")
                self.assertFalse(winbrowsers.browser_healthy(str(exe)), "dll yokken bozuk sayılmalı")
                (ver / "msedge_elf.dll").write_bytes(b"MZ")
                self.assertTrue(winbrowsers.browser_healthy(str(exe)))
                (app / "148.0.1.1").mkdir()
                self.assertFalse(winbrowsers.browser_healthy(str(exe)), "yarım güncelleme klasörü bozuk sayılmalı")
                self.assertFalse(winbrowsers.browser_healthy(None))
                self.assertFalse(winbrowsers.browser_healthy(str(app / "yok.exe")))
        finally:
            del os.environ["DOUYIN_INDIRICI_CHECK_ELF"]


if __name__ == "__main__":
    unittest.main()
