"""Uçtan uca testler: gerçek tarayıcı + sahte Douyin sunucusu.

Çalıştırmak için Playwright'ın bir tarayıcıya erişebilmesi gerekir:
  * Linux/geliştirme: DOUYIN_INDIRICI_BROWSER=chromium (playwright install chromium)
  * Windows (GitHub derlemesi): DOUYIN_INDIRICI_BROWSER=msedge
"""

import json
import os
import sys
import tempfile
import time
import unittest
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from tests import fake_douyin  # noqa: E402
from tests.fake_douyin import FakeDouyin  # noqa: E402


class IntegrationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fake = FakeDouyin().start()
        cls.tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        os.environ["DOUYIN_INDIRICI_BASE"] = cls.fake.base
        os.environ["DOUYIN_INDIRICI_DATA"] = str(Path(cls.tmp.name) / "data")
        os.environ.setdefault("DOUYIN_INDIRICI_BROWSER", "chromium")
        os.environ["DOUYIN_INDIRICI_HEADLESS"] = "1"
        os.environ["DOUYIN_INDIRICI_IDLE"] = "5"

    @classmethod
    def tearDownClass(cls):
        cls.fake.stop()
        cls.tmp.cleanup()

    def run_job(self, link, mode, timeout=120, **settings):
        from douyin_indirici.engine import Engine, Job

        out = Path(self.tmp.name) / f"out_{time.monotonic_ns()}"
        engine = Engine()
        base = {"out_dir": str(out), "content": "all", "quality": "highest", "compat": True, "limit": 0}
        base.update(settings)
        error = engine.start(Job(link, mode, base))
        self.assertIsNone(error)
        deadline = time.monotonic() + timeout
        while engine.running and time.monotonic() < deadline:
            time.sleep(0.3)
        self.assertFalse(engine.running, "iş zamanında bitmedi")
        snap = engine.snapshot()
        if os.environ.get("TEST_VERBOSE"):
            for line in snap["logs"]:
                print(f"  [{line['level']}] {line['text']}")
        return snap, out

    def files(self, out):
        return sorted(str(p.relative_to(out)).replace("\\", "/") for p in out.rglob("*") if p.is_file())

    def test_profile_all(self):
        snap, out = self.run_job(f"https://www.douyin.com/user/{fake_douyin.PROFILE_OK}", "profile")
        self.assertEqual(snap["phase"], "done", snap["summary"])
        self.assertEqual(snap["found_posts"], fake_douyin.TOTAL_POSTS)
        files = self.files(out)
        videos = [f for f in files if f.endswith(".mp4") and "hareketli" not in f]
        images = [f for f in files if f.endswith((".jpg", ".png", ".webp"))]
        self.assertEqual(len(videos), fake_douyin.TOTAL_POSTS - 1)
        self.assertEqual(len(images), 3)
        self.assertTrue(any("02_hareketli.mp4" in f for f in files))
        self.assertTrue(all(f.startswith("Sahte Kişi/") for f in files), files)
        self.assertFalse(any(f.endswith(".part") for f in files))
        # 403 veren ayna atlanıp ikinci aynadan indi
        self.assertTrue(any(k.startswith("forbidden_") for k in self.fake.media_hits))
        self.assertTrue(any(k.startswith("h264_1080_ok_") for k in self.fake.media_hits))
        # H.265 hiç istenmedi (uyumluluk modu)
        self.assertFalse(any(k.startswith("h265_") for k in self.fake.media_hits))
        gallery = [f for f in images if f.endswith("/01.jpg")]
        self.assertEqual(len(gallery), 1, images)

        # Aynı klasöre tekrar: hepsi "zaten vardı" olmalı
        from douyin_indirici.engine import Engine, Job
        engine = Engine()
        engine.start(Job(f"https://www.douyin.com/user/{fake_douyin.PROFILE_OK}", "profile",
                         {"out_dir": str(out), "content": "all", "quality": "highest", "compat": True, "limit": 0}))
        while engine.running:
            time.sleep(0.3)
        snap2 = engine.snapshot()
        self.assertEqual(snap2["files_done"], 0)
        self.assertEqual(snap2["files_skipped"], snap["files_done"])

    def test_profile_limit_and_filter(self):
        snap, out = self.run_job(f"https://www.douyin.com/user/{fake_douyin.PROFILE_OK}", "profile",
                                 limit=10, content="image")
        self.assertEqual(snap["phase"], "done", snap["summary"])
        self.assertEqual(snap["found_posts"], 10)
        files = self.files(out)
        self.assertFalse(any(f.endswith(".mp4") and "hareketli" not in f for f in files), files)
        self.assertEqual(len([f for f in files if f.endswith((".jpg", ".png"))]), 3)

    def test_profile_login_wall(self):
        snap, out = self.run_job(f"https://www.douyin.com/user/{fake_douyin.PROFILE_WALL}", "profile")
        self.assertTrue(snap["login_wall"])
        self.assertEqual(snap["found_posts"], fake_douyin.PAGE_SIZE)
        self.assertEqual(snap["phase"], "done")

    def test_profile_private(self):
        snap, _ = self.run_job(f"https://www.douyin.com/user/{fake_douyin.PROFILE_PRIVATE}", "profile")
        # Sahte sunucu gizli profil için de liste döndürür; gerçek Douyin döndürmez.
        # Burada önemli olan programın çökmemesi.
        self.assertIn(snap["phase"], ("done", "error"))

    def test_captcha_wait(self):
        snap, out = self.run_job(f"https://www.douyin.com/user/{fake_douyin.PROFILE_CAPTCHA}", "profile", limit=3)
        self.assertEqual(snap["phase"], "done", snap["summary"])
        self.assertTrue(any("doğrulama" in l["text"].lower() for l in snap["logs"]))
        self.assertEqual(snap["found_posts"], 3)

    def test_single_video_embedded(self):
        snap, out = self.run_job(f"https://www.douyin.com/video/{fake_douyin.SSR_VIDEO_ID}", "single")
        self.assertEqual(snap["phase"], "done", snap["summary"])
        files = self.files(out)
        self.assertEqual(len(files), 1, files)
        self.assertTrue(files[0].startswith("SSR作者/") and files[0].endswith(f"_SSR video_{fake_douyin.SSR_VIDEO_ID}.mp4"))

    def test_single_note_via_detail_api(self):
        snap, out = self.run_job(f"https://www.douyin.com/note/{fake_douyin.NOTE_ID}", "single", content="video")
        self.assertEqual(snap["phase"], "done", snap["summary"])
        files = self.files(out)
        self.assertEqual(len([f for f in files if f.endswith((".jpg", ".png"))]), 3, files)

    def test_post_link_profile_mode_uses_owner(self):
        snap, out = self.run_job(f"https://www.douyin.com/note/{fake_douyin.NOTE_ID}", "profile", limit=2)
        self.assertEqual(snap["phase"], "done", snap["summary"])
        self.assertEqual(snap["found_posts"], 2)

    def test_stop(self):
        from douyin_indirici.engine import Engine, Job
        engine = Engine()
        engine.start(Job(f"https://www.douyin.com/user/{fake_douyin.PROFILE_WALL}", "profile",
                         {"out_dir": str(Path(self.tmp.name) / "stop"), "limit": 0}))
        time.sleep(4)
        engine.stop()
        deadline = time.monotonic() + 30
        while engine.running and time.monotonic() < deadline:
            time.sleep(0.2)
        self.assertFalse(engine.running)
        self.assertEqual(engine.snapshot()["phase"], "stopped")

    def test_ui_server(self):
        from douyin_indirici.server import AppState, start_server
        state = AppState()
        server = start_server(state)
        base = f"http://127.0.0.1:{state.port}"
        try:
            html = urllib.request.urlopen(base + "/").read().decode("utf-8")
            self.assertIn("İndirmeyi başlat", html)

            def post(path, body, headers=None):
                req = urllib.request.Request(base + path, data=json.dumps(body).encode(),
                                             headers={"Content-Type": "application/json", **(headers or {})})
                try:
                    with urllib.request.urlopen(req) as r:
                        return r.status, json.loads(r.read())
                except urllib.error.HTTPError as e:
                    return e.code, e.read()

            code, r = post("/api/parse", {"link": f"https://www.douyin.com/user/{fake_douyin.PROFILE_OK}"})
            self.assertEqual((code, r["kind"]), (200, "profile"))
            code, r = post("/api/start", {"link": "çöp", "mode": "single", "settings": {}})
            self.assertEqual(code, 400)
            code, _ = post("/api/stop", {}, {"Origin": "https://evil.example"})
            self.assertEqual(code, 403)
            req = urllib.request.Request(base + "/api/state", headers={"Host": "evil.example"})
            with self.assertRaises(urllib.error.HTTPError):
                urllib.request.urlopen(req)
        finally:
            server.shutdown()


if __name__ == "__main__":
    unittest.main()
