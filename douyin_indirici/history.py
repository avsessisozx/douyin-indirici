"""İndirme geçmişi: hangi profilden hangi gönderilerin indiğini ve profilin daha önce tamamen taranıp
taranmadığını tutar. Android'deki History.kt ile aynı mantık. Dosya: <program verisi>/gecmis.sqlite3

Geçmiş Douyin hesabına değil indirilen profile (sec_uid) bağlıdır. Böylece indirilen klasör silinse ya da
taşınsa bile, aylar sonra bile program neyin indiğini bilir ve profilin sadece yeni gönderilerine bakabilir.
"""

from __future__ import annotations

import sqlite3
import threading
import time
from pathlib import Path
from typing import Any, Dict, List, Optional, Set

from .paths import app_data_dir

_SCHEMA = """
CREATE TABLE IF NOT EXISTS downloaded (
    sec_uid  TEXT NOT NULL,
    aweme_id TEXT NOT NULL,
    author   TEXT,
    at       INTEGER NOT NULL,
    PRIMARY KEY (sec_uid, aweme_id)
);
CREATE TABLE IF NOT EXISTS scanned (
    sec_uid TEXT PRIMARY KEY,
    author  TEXT,
    at      INTEGER NOT NULL
);
"""


def history_path() -> Path:
    return app_data_dir() / "gecmis.sqlite3"


class History:
    def __init__(self, path: Optional[Path] = None) -> None:
        self.path = path or history_path()
        self._lock = threading.Lock()
        self._db = sqlite3.connect(str(self.path), timeout=30, check_same_thread=False)
        self._db.executescript(_SCHEMA)
        self._db.commit()

    def close(self) -> None:
        with self._lock:
            try:
                self._db.close()
            except Exception:
                pass

    # ------------------------------------------------------------ indirilenler
    def known(self, sec_uid: str) -> Set[str]:
        """Bu profilden daha önce tamamen inmiş gönderilerin kimlikleri."""
        if not sec_uid:
            return set()
        with self._lock:
            rows = self._db.execute("SELECT aweme_id FROM downloaded WHERE sec_uid=?", (sec_uid,)).fetchall()
        return {r[0] for r in rows}

    def mark(self, sec_uid: str, aweme_id: str, author: str = "") -> None:
        if not sec_uid or not aweme_id:
            return
        with self._lock:
            self._db.execute(
                "INSERT OR REPLACE INTO downloaded (sec_uid, aweme_id, author, at) VALUES (?,?,?,?)",
                (sec_uid, aweme_id, author, int(time.time())))
            self._db.commit()

    def count(self) -> int:
        with self._lock:
            return int(self._db.execute("SELECT COUNT(*) FROM downloaded").fetchone()[0])

    def profiles(self) -> List[Dict[str, Any]]:
        """İndirilen profillerin özeti (en son indirilen başta)."""
        with self._lock:
            rows = self._db.execute(
                "SELECT sec_uid, MAX(author), COUNT(*), MAX(at) FROM downloaded "
                "GROUP BY sec_uid ORDER BY MAX(at) DESC").fetchall()
        return [{"sec_uid": r[0], "author": r[1] or "", "count": int(r[2]), "last": int(r[3] or 0)} for r in rows]

    def clear(self) -> int:
        """Bütün geçmişi siler (dosyalara dokunmaz). Silinen gönderi kaydı sayısını döndürür."""
        with self._lock:
            n = int(self._db.execute("SELECT COUNT(*) FROM downloaded").fetchone()[0])
            self._db.execute("DELETE FROM downloaded")
            self._db.execute("DELETE FROM scanned")
            self._db.commit()
        return n

    # ------------------------------------------------------------ profil taraması
    def was_scanned(self, sec_uid: str) -> bool:
        """Profil daha önce sonuna kadar taranmışsa True (o zaman sadece yenilere bakılabilir)."""
        if not sec_uid:
            return False
        with self._lock:
            return self._db.execute("SELECT 1 FROM scanned WHERE sec_uid=?", (sec_uid,)).fetchone() is not None

    def set_scanned(self, sec_uid: str, author: str = "") -> None:
        if not sec_uid:
            return
        with self._lock:
            self._db.execute("INSERT OR REPLACE INTO scanned (sec_uid, author, at) VALUES (?,?,?)",
                             (sec_uid, author, int(time.time())))
            self._db.commit()
