"""Douyin yanıtlarının yapısını taklit eden örnek veriler (testler için)."""


def api_video(aweme_id="7400000000000000001", base="https://v.example.com", desc="Güzel manzara #travel #china",
              author="测试作者", sec_uid="MS4wLjABAAAAtest_sec_uid_0001", create_time=1717200000):
    return {
        "aweme_id": aweme_id,
        "desc": desc,
        "create_time": create_time,
        "aweme_type": 0,
        "author": {"nickname": author, "sec_uid": sec_uid},
        "video": {
            "width": 1080, "height": 1920,
            "play_addr": {"uri": "v0", "url_list": [f"{base}/play_default_{aweme_id}.mp4"], "width": 720, "height": 1280},
            "download_addr": {"url_list": [f"{base}/playwm_{aweme_id}.mp4"]},
            "bit_rate": [
                {"gear_name": "normal_1080_0", "bit_rate": 2500000, "is_h265": 1,
                 "play_addr": {"url_list": [f"{base}/h265_1080_{aweme_id}.mp4"], "width": 1080, "height": 1920, "data_size": 5000}},
                {"gear_name": "normal_1080_0", "bit_rate": 3000000, "is_h265": 0,
                 "play_addr": {"url_list": [f"{base}/h264_1080_{aweme_id}.mp4", f"{base}/h264_1080_mirror_{aweme_id}.mp4"],
                               "width": 1080, "height": 1920, "data_size": 6000}},
                {"gear_name": "normal_720_0", "bit_rate": 1500000, "is_h265": 0,
                 "play_addr": {"url_list": [f"{base}/h264_720_{aweme_id}.mp4"], "width": 720, "height": 1280}},
                {"gear_name": "low_540_0", "bit_rate": 800000, "is_h265": 0,
                 "play_addr": {"url_list": [f"{base}/h264_540_{aweme_id}.mp4"], "width": 540, "height": 960}},
            ],
        },
    }


def api_gallery(aweme_id="7400000000000000002", base="https://p.example.com", author="测试作者",
                sec_uid="MS4wLjABAAAAtest_sec_uid_0001"):
    return {
        "aweme_id": aweme_id,
        "desc": "#风景 #photo",
        "create_time": 1717300000,
        "aweme_type": 68,
        "author": {"nickname": author, "sec_uid": sec_uid},
        "video": {"play_addr": {"url_list": [f"{base}/music_bg_{aweme_id}.mp3"]}},
        "images": [
            {"width": 1080, "height": 1440,
             "url_list": [f"{base}/img1~tplv-dy-aweme-images:q75.webp", f"{base}/img1~tplv-dy-aweme-images:q75.jpeg"],
             "download_url_list": [f"{base}/img1~tplv-dy-water.jpeg"]},
            {"width": 1080, "height": 1440,
             "url_list": [f"{base}/img2~noop.jpeg"],
             "video": {"bit_rate": [{"bit_rate": 900000, "play_addr": {"url_list": [f"{base}/live2.mp4"], "width": 720, "height": 960}}]}},
            {"width": 1080, "height": 1440, "url_list": [f"{base}/img3.png"]},
        ],
    }


def ssr_video(aweme_id="7400000000000000003", base="https://v.example.com"):
    """Sayfaya gömülü (RENDER_DATA) camelCase biçim."""
    return {
        "awemeId": aweme_id,
        "desc": "SSR video",
        "createTime": 1717400000,
        "authorInfo": {"nickname": "ignored"},
        "author": {"nickname": "SSR作者", "secUid": "MS4wLjABAAAAssr_sec_uid_000001"},
        "video": {
            "width": 1080, "height": 1920,
            "playAddr": [{"src": f"//{base.split('://')[1]}/ssr_default_{aweme_id}.mp4"}],
            "bitRateList": [
                {"bitRate": 2000000, "isH265": 0, "width": 1080, "height": 1920,
                 "playAddr": [{"src": f"{base}/ssr_1080_{aweme_id}.mp4"}, {"src": f"{base}/ssr_1080_b_{aweme_id}.mp4"}]},
                {"bitRate": 900000, "isH265": 0, "width": 720, "height": 1280,
                 "playAddr": [{"src": f"{base}/ssr_720_{aweme_id}.mp4"}]},
            ],
        },
    }
