"""Program verisinin ve ayarların nerede tutulduğu."""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path
from typing import Any, Dict

from .media import CONTENT_ALL, QUALITY_CHOICES, QUALITY_HIGHEST

LIMIT_CHOICES = (0, 10, 20, 50, 100)


def app_data_dir() -> Path:
    override = os.environ.get("DOUYIN_INDIRICI_DATA")
    if override:
        base = Path(override)
    elif sys.platform == "win32":
        base = Path(os.environ.get("LOCALAPPDATA") or Path.home() / "AppData" / "Local") / "DouyinIndirici"
    else:
        base = Path.home() / ".douyin-indirici"
    base.mkdir(parents=True, exist_ok=True)
    return base


def default_download_dir() -> Path:
    if sys.platform == "win32":
        # Kullanıcının gerçek "İndirilenler" klasörü (başka sürücüye taşınmış olsa bile).
        try:
            import ctypes

            path_ptr = ctypes.c_wchar_p()
            fid = _GUID_DOWNLOADS()
            if ctypes.windll.shell32.SHGetKnownFolderPath(ctypes.byref(fid), 0, None, ctypes.byref(path_ptr)) == 0:
                value = path_ptr.value
                ctypes.windll.ole32.CoTaskMemFree(path_ptr)
                if value:
                    return Path(value) / "Douyin"
        except Exception:
            pass
    return Path.home() / "Downloads" / "Douyin"


def _GUID_DOWNLOADS():
    import ctypes

    class GUID(ctypes.Structure):
        _fields_ = [("Data1", ctypes.c_uint32), ("Data2", ctypes.c_uint16),
                    ("Data3", ctypes.c_uint16), ("Data4", ctypes.c_ubyte * 8)]

    # FOLDERID_Downloads = {374DE290-123F-4565-9164-39C4925E467B}
    return GUID(0x374DE290, 0x123F, 0x4565, (ctypes.c_ubyte * 8)(0x91, 0x64, 0x39, 0xC4, 0x92, 0x5E, 0x46, 0x7B))


DEFAULT_SETTINGS: Dict[str, Any] = {
    "out_dir": "",
    "content": CONTENT_ALL,
    "quality": QUALITY_HIGHEST,
    "compat": True,
    "limit": 0,
    "lang": "auto",
}


def settings_path() -> Path:
    return app_data_dir() / "ayarlar.json"


def load_settings() -> Dict[str, Any]:
    settings = dict(DEFAULT_SETTINGS)
    try:
        stored = json.loads(settings_path().read_text(encoding="utf-8"))
        if isinstance(stored, dict):
            settings.update({k: stored[k] for k in DEFAULT_SETTINGS if k in stored})
    except (OSError, ValueError):
        pass
    return clean_settings(settings)


def clean_settings(settings: Dict[str, Any]) -> Dict[str, Any]:
    out = dict(DEFAULT_SETTINGS)
    out_dir = str(settings.get("out_dir") or "").strip()
    out["out_dir"] = out_dir or str(default_download_dir())
    if settings.get("content") in ("all", "video", "image"):
        out["content"] = settings["content"]
    if settings.get("quality") in QUALITY_CHOICES:
        out["quality"] = settings["quality"]
    out["compat"] = bool(settings.get("compat", True))
    try:
        limit = int(settings.get("limit") or 0)
    except (TypeError, ValueError):
        limit = 0
    out["limit"] = max(0, min(limit, 100000))
    lang = str(settings.get("lang") or "auto")
    from .i18n import available_languages
    out["lang"] = lang if lang in {item["code"] for item in available_languages()} else "auto"
    return out


def save_settings(settings: Dict[str, Any]) -> None:
    try:
        settings_path().write_text(json.dumps(clean_settings(settings), ensure_ascii=False, indent=2), encoding="utf-8")
    except OSError:
        pass
