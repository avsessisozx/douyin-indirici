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
        # Testler aynı veri klasörünü paylaşır; geçmiş açık kalırsa önceki testte inen gönderiler atlanır.
        base = {"out_dir": str(out), "content": "all", "quality": "highest", "compat": True, "limit": 0,
                "history": False}
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
                text = line.get("text") or json.dumps(line.get("msg"), ensure_ascii=False)
                print(f"  [{line['level']}] {text}")
        return snap, out

    def assert_no_false_captcha(self, snap):
        keys = [(l.get("msg") or {}).get("key") for l in snap["logs"]]
        self.assertNotIn("browser.captcha", keys, "görünmez öğeler doğrulama sanıldı")
        self.assertFalse(snap["captcha"])

    def files(self, out):
        return sorted(str(p.relative_to(out)).replace("\\", "/") for p in out.rglob("*") if p.is_file())

    def test_profile_all(self):
        snap, out = self.run_job(f"https://www.douyin.com/user/{fake_douyin.PROFILE_OK}", "profile")
        self.assertEqual(snap["phase"], "done", snap["summary"])
        self.assert_no_false_captcha(snap)
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
        urllib.request.urlopen(self.fake.base + "/captcha-reset").read()
        snap, out = self.run_job(f"https://www.douyin.com/user/{fake_douyin.PROFILE_CAPTCHA}", "profile", limit=3)
        self.assertEqual(snap["phase"], "done", snap["summary"])
        self.assertTrue(any((l.get("msg") or {}).get("key") == "browser.captcha" for l in snap["logs"]))
        self.assertEqual(snap["found_posts"], 3)

    def test_single_video_embedded(self):
        snap, out = self.run_job(f"https://www.douyin.com/video/{fake_douyin.SSR_VIDEO_ID}", "single")
        self.assertEqual(snap["phase"], "done", snap["summary"])
        self.assert_no_false_captcha(snap)
        files = self.files(out)
        self.assertEqual(len(files), 1, files)
        self.assertTrue(files[0].startswith("SSR作者/") and files[0].endswith(f"_SSR video_{fake_douyin.SSR_VIDEO_ID}.mp4"))

    def test_single_note_via_detail_api(self):
        snap, out = self.run_job(f"https://www.douyin.com/note/{fake_douyin.NOTE_ID}", "single", content="video")
        self.assertEqual(snap["phase"], "done", snap["summary"])
        self.assert_no_false_captcha(snap)
        files = self.files(out)
        self.assertEqual(len([f for f in files if f.endswith((".jpg", ".png"))]), 3, files)

    def test_post_link_profile_mode_uses_owner(self):
        snap, out = self.run_job(f"https://www.douyin.com/note/{fake_douyin.NOTE_ID}", "profile", limit=2)
        self.assertEqual(snap["phase"], "done", snap["summary"])
        self.assertEqual(snap["found_posts"], 2)

    # ------------------------------------------------------------ geçmiş
    def test_history_skips_known_and_stops_early(self):
        link = f"https://www.douyin.com/user/{fake_douyin.PROFILE_HIST}"
        snap, out = self.run_job(link, "profile", history=True)
        self.assertEqual(snap["phase"], "done", snap["summary"])
        self.assertEqual(snap["files_done"], 17)      # 13 video + 3 resim + 1 hareketli
        # İkinci çalışma başka klasöre: hiçbir şey inmemeli, tarama da erken bitmeli
        snap2, out2 = self.run_job(link, "profile", history=True)
        self.assertEqual(snap2["phase"], "done", snap2["summary"])
        self.assertEqual(snap2["files_done"], 0)
        self.assertEqual(snap2["posts_known"], snap2["found_posts"])
        self.assertEqual(self.files(out2), [])
        keys = [(l.get("msg") or {}).get("key") for l in snap2["logs"]]
        self.assertIn("engine.history_known", keys)
        self.assertIn("engine.up_to_date", keys)
        # Geçmiş temizlenince hepsi yine iner
        from douyin_indirici.history import History
        h = History()
        self.assertGreater(h.clear(), 0)
        h.close()
        snap3, out3 = self.run_job(link, "profile", history=True)
        self.assertEqual(snap3["files_done"], snap["files_done"])

    def test_history_off_downloads_again(self):
        link = f"https://www.douyin.com/user/{fake_douyin.PROFILE_OK}"
        snap, _ = self.run_job(link, "profile", history=False, limit=3)
        snap2, _ = self.run_job(link, "profile", history=False, limit=3)
        self.assertEqual(snap["files_done"], 3)
        self.assertEqual(snap2["files_done"], 3)
        self.assertEqual(snap2["posts_known"], 0)

    # ------------------------------------------------------------ duraklat / tekrar dene
    def _start_engine(self, link, mode, **settings):
        from douyin_indirici.engine import Engine, Job
        out = Path(self.tmp.name) / f"out_{time.monotonic_ns()}"
        engine = Engine()
        base = {"out_dir": str(out), "content": "all", "quality": "highest", "compat": True, "limit": 0,
                "history": False}
        base.update(settings)
        self.assertIsNone(engine.start(Job(link, mode, base)))
        return engine, out

    def _wait(self, engine, timeout=120):
        deadline = time.monotonic() + timeout
        while engine.running and time.monotonic() < deadline:
            time.sleep(0.2)
        self.assertFalse(engine.running, "iş zamanında bitmedi")

    def test_pause_and_resume(self):
        engine, out = self._start_engine(f"https://www.douyin.com/user/{fake_douyin.PROFILE_OK}", "profile")
        time.sleep(0.4)
        engine.pause()
        self.assertTrue(engine.snapshot()["paused"])
        time.sleep(4)                       # duraklatılmışken ilerlememeli
        first = engine.snapshot()
        time.sleep(2)
        second = engine.snapshot()
        self.assertTrue(second["running"] and second["paused"])
        self.assertEqual((first["found_posts"], first["files_done"]), (second["found_posts"], second["files_done"]))
        self.assertLess(second["files_done"], fake_douyin.TOTAL_POSTS)
        engine.resume()
        self._wait(engine)
        snap = engine.snapshot()
        self.assertEqual(snap["phase"], "done", snap["summary"])
        self.assertFalse(snap["paused"])
        self.assertEqual(snap["found_posts"], fake_douyin.TOTAL_POSTS)
        self.assertEqual(snap["files_failed"], 0)

    def test_stop_while_paused(self):
        engine, out = self._start_engine(f"https://www.douyin.com/user/{fake_douyin.PROFILE_OK}", "profile")
        time.sleep(0.4)
        engine.pause()
        time.sleep(1)
        engine.stop()
        self._wait(engine, 30)
        self.assertEqual(engine.snapshot()["phase"], "stopped")

    def test_retry_failed(self):
        from douyin_indirici.engine import MODE_RETRY
        self.fake.flaky_ok = False
        link = f"https://www.douyin.com/user/{fake_douyin.PROFILE_FLAKY}"
        engine, out = self._start_engine(link, "profile")
        self._wait(engine)
        snap = engine.snapshot()
        self.assertEqual(snap["files_done"], 4, snap["summary"])
        self.assertEqual(snap["files_failed"], 1)
        self.assertEqual(snap["failed_posts"], 1)
        keys = [(l.get("msg") or {}).get("key") for l in snap["logs"]]
        self.assertIn("engine.retrying", keys)           # bir kez kendiliğinden denendi
        # Sunucu düzelince düğmeyle tekrar dene
        urllib.request.urlopen(self.fake.base + "/flaky-ok").read()
        from douyin_indirici.engine import Job
        self.assertIsNone(engine.start(Job("", MODE_RETRY, {"out_dir": str(out), "content": "all", "quality": "highest",
                                                           "compat": True, "limit": 0, "history": False})))
        self._wait(engine)
        snap2 = engine.snapshot()
        self.assertEqual(snap2["phase"], "done", snap2["summary"])
        self.assertEqual(snap2["failed_posts"], 0)
        self.assertEqual(snap2["files_failed"], 0)
        self.assertEqual(len([f for f in self.files(out) if f.endswith(".mp4")]), 5)

    def test_retry_with_nothing_failed(self):
        from douyin_indirici.engine import Engine, Job, MODE_RETRY
        error = Engine().start(Job("", MODE_RETRY, {"out_dir": self.tmp.name}))
        self.assertEqual(error.key, "engine.retry_none")

    # ------------------------------------------------------------ giriş penceresi
    def _popup_case(self, sec):
        before = self.fake.popup_closed
        snap, out = self.run_job(f"https://www.douyin.com/user/{sec}", "profile")
        self.assertEqual(snap["phase"], "done", snap["summary"])
        self.assertEqual(snap["files_done"], 17)
        self.assertGreater(self.fake.popup_closed, before, "giriş penceresi kapatılmadı")
        keys = [(l.get("msg") or {}).get("key") for l in snap["logs"]]
        self.assertIn("browser.popup_closed", keys)

    def test_login_popup_closed_by_id(self):
        self._popup_case(fake_douyin.PROFILE_POPUP)

    def test_login_popup_closed_by_text(self):
        self._popup_case(fake_douyin.PROFILE_POPUP_TEXT)

    def test_report_and_settings_endpoints(self):
        from douyin_indirici import report
        snap, _ = self.run_job(f"https://www.douyin.com/user/{fake_douyin.PROFILE_OK}", "profile", limit=2)
        text = report.build([("Sürüm", "x")], {"out_dir": "C:/gizli", "sessionid": "ABC123"},
                            {**snap, "logs": snap["logs"] + [{"t": "0", "level": "info", "text": "sessionid=ZZZ9 ttwid=QQ"}]},
                            "tr")
        self.assertIn("Douyin İndirici raporu", text)
        self.assertNotIn("C:/gizli", text)
        self.assertNotIn("ABC123", text)
        self.assertNotIn("ZZZ9", text)
        self.assertIn("files_done: 2", text)

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
            self.assertIn('data-i18n="ui.start"', html)
            strings = json.loads(urllib.request.urlopen(base + "/api/strings").read())
            self.assertIn(strings["lang"], ("tr", "en"))
            self.assertTrue(strings["strings"]["ui.start"])

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
