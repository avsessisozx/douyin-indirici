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
PROFILE_CAPTCHA_SLOW = "MS4wLjABAAAAprofile_captcha_slow01"   # bulmaca 15 sn sonra çözülür
PROFILE_POPUP = "MS4wLjABAAAAprofile_popup_00000001"           # gerçek Douyin gibi giriş penceresi çıkar
PROFILE_POPUP_TEXT = "MS4wLjABAAAAprofile_popup_text_001"      # bilinen kimlikler yok: pencere sadece yazısından tanınır
PROFILE_FLAKY = "MS4wLjABAAAAprofile_flaky_0000000001"         # bir videosu /flaky-ok çağrılana kadar inmez
PROFILE_HIST = "MS4wLjABAAAAprofile_hist_00000000001"          # geçmiş testleri için
FLAKY_PREFIX = "74200000000000"

# Gerçek Douyin sayfalarında hep bulunan, doğrulama SAYILMAMASI gereken görünmez öğeler.
# (1.1.0'da Android bunları bulmaca sanıp ekranı kapatmıştı.)
DECOYS = (
    '<iframe src="/verifycenter/captcha/v2" style="display:none"></iframe>'
    '<iframe src="/verify/sdk" width="0" height="0" style="border:0"></iframe>'
    '<div id="captcha_container" style="width:0;height:0;overflow:hidden"></div>'
    '<div class="captcha-verify-container" style="visibility:hidden;width:300px;height:200px"></div>'
)
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
        self.flaky_ok = False
        self.popup_closed = 0

    def start(self):
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        return self

    def stop(self):
        self.server.shutdown()

    # ------------------------------------------------------------ veri
    def flaky_item(self, aid: str, i: int):
        media = f"{self.base}/media"
        item = samples.api_video(aid, base=media, desc=f"Video {i} #etiket", author="Dalgalı Kişi",
                                 sec_uid=PROFILE_FLAKY, create_time=1717200000 + i * 86400)
        if i == 2:
            url = [f"{media}/flaky_{aid}.mp4"]
            for br in item["video"]["bit_rate"]:
                br["play_addr"]["url_list"] = list(url)
            item["video"]["play_addr"]["url_list"] = list(url)
            item["video"]["download_addr"]["url_list"] = list(url)
        return item

    def posts_for(self, sec_uid: str):
        media = f"{self.base}/media"
        posts = []
        if sec_uid == PROFILE_FLAKY:
            for i in range(5):
                aid = f"{FLAKY_PREFIX}{i:05d}"
                posts.append(self.flaky_item(aid, i))
            return posts
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
                    if name.startswith("flaky") and not fake.flaky_ok:
                        return self.send(403, "flaky", "text/plain")
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
                    if aid.startswith(FLAKY_PREFIX):
                        item = fake.flaky_item(aid, int(aid[len(FLAKY_PREFIX):] or 0))
                        return self.send(200, json.dumps({"aweme_detail": item}), "application/json")
                    item = samples.api_gallery(aid, base=f"{fake.base}/media", author="Not Sahibi",
                                               sec_uid=PROFILE_OK)
                    return self.send(200, json.dumps({"aweme_detail": item}), "application/json")

                if path == "/flaky-ok":
                    fake.flaky_ok = True
                    return self.send(200, "{}", "application/json")

                if path == "/popup-closed":
                    fake.popup_closed += 1
                    return self.send(200, "{}", "application/json")

                if path.startswith("/user/"):
                    sec = path.split("/")[2]
                    if sec in (PROFILE_POPUP, PROFILE_POPUP_TEXT):
                        popup = POPUP_ID if sec == PROFILE_POPUP else POPUP_PLAIN
                        return self.send(200, PROFILE_PAGE.replace("__SEC__", PROFILE_OK).replace(
                            "__DECOYS__", DECOYS + popup), "text/html; charset=utf-8")
                    if sec in (PROFILE_CAPTCHA, PROFILE_CAPTCHA_SLOW) and not fake.captcha_passed:
                        delay = "15000" if sec == PROFILE_CAPTCHA_SLOW else "3000"
                        return self.send(200, CAPTCHA_PAGE.replace("__DELAY__", delay), "text/html; charset=utf-8")
                    real = PROFILE_OK if sec in (PROFILE_CAPTCHA, PROFILE_CAPTCHA_SLOW) else sec
                    return self.send(200, PROFILE_PAGE.replace("__SEC__", real).replace("__DECOYS__", DECOYS),
                                     "text/html; charset=utf-8")

                if path == "/captcha-reset":
                    fake.captcha_passed = False
                    return self.send(200, "{}", "application/json")

                if path == "/captcha-pass":
                    fake.captcha_passed = True
                    return self.send(200, "{}", "application/json")

                if path.startswith("/video/") and path.split("/")[2].startswith(FLAKY_PREFIX):
                    aid = path.split("/")[2]
                    page = f"<html><head><title>video</title></head><body><script>fetch('/aweme/v1/web/aweme/detail/?aweme_id={aid}')</script>{DECOYS}</body></html>"
                    return self.send(200, page, "text/html; charset=utf-8")

                if path.startswith("/video/"):
                    aid = path.split("/")[2]
                    data = {"app": {"videoDetail": samples.ssr_video(aid, base=f"{fake.base}/media")}}
                    rd = quote(json.dumps(data))
                    page = f"<html><head><title>video</title></head><body><script id=\"RENDER_DATA\" type=\"application/json\">{rd}</script><p>video</p>{DECOYS}</body></html>"
                    return self.send(200, page, "text/html; charset=utf-8")

                if path.startswith("/note/"):
                    aid = path.split("/")[2]
                    page = f"<html><head><title>note</title></head><body><script>fetch('/aweme/v1/web/aweme/detail/?aweme_id={aid}')</script>{DECOYS}</body></html>"
                    return self.send(200, page, "text/html; charset=utf-8")

                return self.send(404, "not found", "text/plain")

        return H


PROFILE_PAGE = """<!doctype html><html><head><meta charset="utf-8"><title>Sahte Kişi的抖音 - 抖音</title>
<style>body{margin:0} .tile{height:420px;border-bottom:1px solid #ccc}</style></head><body>
<div id="list"></div><div id="sentinel" style="height:10px"></div>__DECOYS__
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

# Gerçek Douyin'in giriş penceresinin sadeleştirilmiş hali (1 sn sonra çıkar; sağ üstteki X imleci "pointer").
# Kimlikler gerçek sayfadan alındı; sınıf adları gerçekte sürekli değiştiği için burada rastgele.
_POPUP_BODY = """<div %ATTRS% style="position:fixed;left:40px;top:120px;width:700px;height:480px;background:#fff;z-index:9999;display:none">
  <div style="position:relative;height:80px"><div style="padding:24px;font-size:20px">登录后免费畅享高清视频</div>
    <div class="x9fK2" style="position:absolute;right:24px;top:20px;width:36px;height:36px;cursor:pointer"
         onclick="this.closest('[data-popup]').remove(); fetch('/popup-closed')"><svg width="24" height="24"><path d="M4 4L20 20M20 4L4 20" stroke="#000"/></svg></div></div>
  <div>扫码登录 · 验证码登录</div></div>
<script>setTimeout(() => { const p = document.querySelector('[data-popup]'); if (p) p.style.display = 'block'; }, 1000);</script>"""
POPUP_ID = _POPUP_BODY.replace("%ATTRS%", 'id="login-panel-new" data-popup="1"')
POPUP_PLAIN = _POPUP_BODY.replace("%ATTRS%", 'class="zq81Pf" data-popup="1"')

CAPTCHA_PAGE = """<!doctype html><html><head><meta charset="utf-8"><title>验证码中间页</title></head><body>
<div id="captcha_container" style="width:300px;height:200px;background:#eee">doğrulama</div>
<script>
setTimeout(async () => { await fetch('/captcha-pass'); location.reload(); }, __DELAY__);
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
