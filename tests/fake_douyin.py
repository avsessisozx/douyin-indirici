"""Testler için sahte Douyin sunucusu.

Gerçek Douyin sayfalarının davranışını taklit eder:
  * /user/<sec_uid>    sayfa açılınca ve kaydırdıkça aweme/post isteği atar
  * /video/<id>        veriyi sayfaya gömülü RENDER_DATA ile verir (istek atmaz)
  * /note/<id>         aweme/detail isteği atar
  * /captcha-user/...  önce "验证码" başlıklı doğrulama sayfası gösterir
  * /media/...         video/resim dosyaları (biri 403 döner: ayna değiştirme testi)
"""

from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, quote, urlparse

from tests import samples

PROFILE_OK = "MS4wLjABAAAAprofile_ok_000000000001"
PROFILE_WALL = "MS4wLjABAAAAprofile_wall_0000000001"
PROFILE_PRIVATE = "MS4wLjABAAAAprofile_private_000001"
PROFILE_CAPTCHA = "MS4wLjABAAAAprofile_captcha_000001"
SSR_VIDEO_ID = "7400000000000000003"
NOTE_ID = "7400000000000000002"
PAGE_SIZE = 6
TOTAL_POSTS = 14


class FakeDouyin:
    def __init__(self, host: str = "127.0.0.1", port: int = 0, public_base: str = "") -> None:
        """public_base: istemcinin sunucuya ulaştığı adres (Android emülatöründe http://10.0.2.2:PORT)."""
        self.server = ThreadingHTTPServer((host, port), self._handler())
        self.port = self.server.server_address[1]
        self.base = public_base.rstrip("/") or f"http://127.0.0.1:{self.port}"
        self.media_hits = {}
        self.captcha_passed = False

    def start(self):
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        return self

    def stop(self):
        self.server.shutdown()

    # ------------------------------------------------------------ veri
    def posts_for(self, sec_uid: str):
        media = f"{self.base}/media"
        posts = []
        for i in range(TOTAL_POSTS):
            aid = f"74100000000000{i:05d}"
            if i == 3:
                item = samples.api_gallery(aid, base=media, author="Sahte Kişi", sec_uid=sec_uid)
            else:
                item = samples.api_video(aid, base=media, desc=f"Video {i} #etiket", author="Sahte Kişi",
                                         sec_uid=sec_uid, create_time=1717200000 + i * 86400)
                if i == 5:
                    # ilk ayna 403 verir, ikinci çalışır
                    item["video"]["bit_rate"][1]["play_addr"]["url_list"] = [
                        f"{media}/forbidden_{aid}.mp4", f"{media}/h264_1080_ok_{aid}.mp4"]
            posts.append(item)
        return posts

    def _handler(self):
        fake = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def send(self, code, body, ctype):
                if isinstance(body, str):
                    body = body.encode("utf-8")
                self.send_response(code)
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self):
                u = urlparse(self.path)
                q = {k: v[0] for k, v in parse_qs(u.query).items()}
                path = u.path

                if path.startswith("/media/"):
                    name = path.rsplit("/", 1)[1]
                    fake.media_hits[name] = fake.media_hits.get(name, 0) + 1
                    if self.headers.get("Referer") != "https://www.douyin.com/":
                        return self.send(403, "no referer", "text/plain")
                    if name.startswith("forbidden"):
                        return self.send(403, "forbidden", "text/plain")
                    if name.endswith(".mp4"):
                        return self.send(200, b"\x00\x00\x00\x18ftypmp42" + name.encode() * 50, "video/mp4")
                    if name.endswith(".jpeg") or "jpeg" in name:
                        return self.send(200, b"\xff\xd8\xff" + name.encode(), "image/jpeg")
                    if name.endswith(".png"):
                        return self.send(200, b"\x89PNG" + name.encode(), "image/png")
                    if name.endswith(".webp"):
                        return self.send(200, b"RIFFxxxxWEBP" + name.encode(), "image/webp")
                    return self.send(404, "nope", "text/plain")

                if path == "/aweme/v1/web/aweme/post/":
                    sec = q.get("sec_user_id", "")
                    cursor = int(q.get("max_cursor", "0") or 0)
                    if sec == PROFILE_WALL and cursor > 0:
                        return self.send(200, json.dumps({"aweme_list": [], "has_more": 1, "max_cursor": cursor}),
                                         "application/json")
                    posts = fake.posts_for(sec)
                    chunk = posts[cursor:cursor + PAGE_SIZE]
                    nxt = cursor + len(chunk)
                    body = {"aweme_list": chunk, "has_more": 1 if nxt < len(posts) else 0, "max_cursor": nxt}
                    return self.send(200, json.dumps(body), "application/json")

                if path == "/aweme/v1/web/user/profile/other/":
                    sec = q.get("sec_user_id", "")
                    user = {"nickname": "Sahte Kişi", "aweme_count": TOTAL_POSTS, "secret": 0}
                    if sec == PROFILE_PRIVATE:
                        user = {"nickname": "Gizli", "aweme_count": 5, "secret": 1}
                    return self.send(200, json.dumps({"user": user}), "application/json")

                if path == "/aweme/v1/web/aweme/detail/":
                    aid = q.get("aweme_id", "")
                    item = samples.api_gallery(aid, base=f"{fake.base}/media", author="Not Sahibi",
                                               sec_uid=PROFILE_OK)
                    return self.send(200, json.dumps({"aweme_detail": item}), "application/json")

                if path.startswith("/user/"):
                    sec = path.split("/")[2]
                    if sec == PROFILE_CAPTCHA and not fake.captcha_passed:
                        return self.send(200, CAPTCHA_PAGE, "text/html; charset=utf-8")
                    real = PROFILE_OK if sec == PROFILE_CAPTCHA else sec
                    return self.send(200, PROFILE_PAGE.replace("__SEC__", real), "text/html; charset=utf-8")

                if path == "/captcha-pass":
                    fake.captcha_passed = True
                    return self.send(200, "{}", "application/json")

                if path.startswith("/video/"):
                    aid = path.split("/")[2]
                    data = {"app": {"videoDetail": samples.ssr_video(aid, base=f"{fake.base}/media")}}
                    rd = quote(json.dumps(data))
                    page = f"<html><head><title>video</title></head><body><script id=\"RENDER_DATA\" type=\"application/json\">{rd}</script><p>video</p></body></html>"
                    return self.send(200, page, "text/html; charset=utf-8")

                if path.startswith("/note/"):
                    aid = path.split("/")[2]
                    page = f"<html><head><title>note</title></head><body><script>fetch('/aweme/v1/web/aweme/detail/?aweme_id={aid}')</script></body></html>"
                    return self.send(200, page, "text/html; charset=utf-8")

                return self.send(404, "not found", "text/plain")

        return H


PROFILE_PAGE = """<!doctype html><html><head><meta charset="utf-8"><title>Sahte Kişi的抖音 - 抖音</title>
<style>body{margin:0} .tile{height:420px;border-bottom:1px solid #ccc}</style></head><body>
<div id="list"></div><div id="sentinel" style="height:10px"></div>
<script>
const sec = "__SEC__"; let cursor = 0, more = 1, loading = false;
fetch('/aweme/v1/web/user/profile/other/?sec_user_id=' + sec);
async function load() {
  if (loading || !more) return; loading = true;
  const r = await fetch('/aweme/v1/web/aweme/post/?sec_user_id=' + sec + '&max_cursor=' + cursor + '&count=6');
  const d = await r.json();
  for (const it of d.aweme_list) {
    const a = document.createElement('a'); a.className = 'tile'; a.style.display = 'block';
    a.href = '/video/' + it.aweme_id; a.textContent = it.desc; document.getElementById('list').appendChild(a);
  }
  cursor = d.max_cursor; more = d.has_more; loading = false;
}
load();
window.addEventListener('scroll', () => {
  if (window.innerHeight + window.scrollY >= document.body.scrollHeight - 300) load();
});
</script></body></html>"""

CAPTCHA_PAGE = """<!doctype html><html><head><meta charset="utf-8"><title>验证码中间页</title></head><body>
<div id="captcha_container" style="width:300px;height:200px;background:#eee">doğrulama</div>
<script>
setTimeout(async () => { await fetch('/captcha-pass'); location.reload(); }, 3000);
</script></body></html>"""


if __name__ == "__main__":
    import argparse
    import time

    ap = argparse.ArgumentParser(description="Testler için sahte Douyin sunucusu")
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--public", default="", help="istemcinin kullanacağı adres, ör. http://10.0.2.2:8765")
    args = ap.parse_args()
    fake = FakeDouyin(args.host, args.port, args.public).start()
    print(f"fake douyin on {args.host}:{fake.port} public={fake.base}", flush=True)
    while True:
        time.sleep(3600)
