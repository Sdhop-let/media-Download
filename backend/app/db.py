"""SQLite 数据层（WAL）。表：authors / posts / media / tasks / proxy_candidates / proxy_log。"""
import sqlite3
import threading
from datetime import datetime, timezone
from pathlib import Path

from . import config

_conn: sqlite3.Connection | None = None
_lock = threading.RLock()

SCHEMA = """
CREATE TABLE IF NOT EXISTS authors(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  platform TEXT NOT NULL, handle TEXT NOT NULL,
  name TEXT DEFAULT '', avatar_url TEXT DEFAULT '', profile_url TEXT DEFAULT '',
  UNIQUE(platform, handle)
);
CREATE TABLE IF NOT EXISTS posts(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  platform TEXT NOT NULL, post_id TEXT NOT NULL,
  post_url TEXT DEFAULT '', author_id INTEGER, created_at TEXT DEFAULT '', text TEXT DEFAULT '',
  UNIQUE(platform, post_id)
);
CREATE TABLE IF NOT EXISTS media(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  post_row_id INTEGER NOT NULL,
  media_index INTEGER NOT NULL DEFAULT 0,
  media_type TEXT DEFAULT 'photo',
  file_path TEXT DEFAULT '', thumb_path TEXT DEFAULT '',
  ext TEXT DEFAULT '', file_size INTEGER DEFAULT 0,
  width INTEGER DEFAULT 0, height INTEGER DEFAULT 0,
  source_url TEXT DEFAULT '', downloaded_at TEXT DEFAULT '',
  UNIQUE(post_row_id, media_index)
);
CREATE TABLE IF NOT EXISTS tasks(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  url TEXT NOT NULL, platform TEXT DEFAULT '', kind TEXT DEFAULT '',
  status TEXT DEFAULT 'queued', error TEXT DEFAULT '', message TEXT DEFAULT '',
  total_files INTEGER DEFAULT 0, done_files INTEGER DEFAULT 0, skipped INTEGER DEFAULT 0,
  downloaded_bytes INTEGER DEFAULT 0,
  created_at TEXT DEFAULT (datetime('now','localtime')),
  finished_at TEXT DEFAULT ''
);
CREATE TABLE IF NOT EXISTS proxy_candidates(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  type TEXT NOT NULL, host TEXT NOT NULL, port INTEGER NOT NULL,
  source TEXT DEFAULT '', latency_ms REAL, speed_mbps REAL,
  score REAL DEFAULT 0, alive INTEGER DEFAULT 0, last_tested TEXT DEFAULT '',
  UNIQUE(type, host, port)
);
CREATE TABLE IF NOT EXISTS proxy_log(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ts TEXT DEFAULT (datetime('now','localtime')),
  event TEXT, detail TEXT
);
"""


def connect() -> sqlite3.Connection:
    global _conn
    with _lock:
        if _conn is None:
            Path(config.DB_PATH).parent.mkdir(parents=True, exist_ok=True)
            _conn = sqlite3.connect(config.DB_PATH, check_same_thread=False)
            _conn.row_factory = sqlite3.Row
            _conn.execute("PRAGMA journal_mode=WAL")
            _conn.executescript(SCHEMA)
            _conn.commit()
        return _conn


def now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _dt_to_iso(v) -> str:
    """把各来源的日期统一成 UTC ISO 字符串（排序需要一致格式）。"""
    if not v:
        return ""
    if isinstance(v, (int, float)):
        try:
            return datetime.fromtimestamp(v, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        except Exception:
            return ""
    s = str(v).strip().replace("Z", "+00:00").replace(" ", "T")
    try:
        dt = datetime.fromisoformat(s)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    except Exception:
        return ""


# ---------- authors / posts / media ----------

def upsert_author(platform, handle, name="", avatar_url="", profile_url="") -> int:
    with _lock:
        c = connect()
        c.execute(
            """INSERT INTO authors(platform, handle, name, avatar_url, profile_url)
               VALUES(?,?,?,?,?)
               ON CONFLICT(platform, handle) DO UPDATE SET
                 name=CASE WHEN excluded.name!='' THEN excluded.name ELSE authors.name END,
                 avatar_url=CASE WHEN excluded.avatar_url!='' THEN excluded.avatar_url ELSE authors.avatar_url END,
                 profile_url=CASE WHEN excluded.profile_url!='' THEN excluded.profile_url ELSE authors.profile_url END""",
            (platform, handle, name or "", avatar_url or "", profile_url or ""),
        )
        row = c.execute("SELECT id FROM authors WHERE platform=? AND handle=?", (platform, handle)).fetchone()
        c.commit()
        return row["id"]


def upsert_post(platform, post_id, post_url, author_id, created_at, text="") -> int:
    with _lock:
        c = connect()
        c.execute(
            """INSERT INTO posts(platform, post_id, post_url, author_id, created_at, text)
               VALUES(?,?,?,?,?,?)
               ON CONFLICT(platform, post_id) DO UPDATE SET
                 post_url=CASE WHEN excluded.post_url!='' THEN excluded.post_url ELSE posts.post_url END,
                 created_at=CASE WHEN excluded.created_at!='' THEN excluded.created_at ELSE posts.created_at END""",
            (platform, str(post_id), post_url or "", author_id, _dt_to_iso(created_at), text or ""),
        )
        row = c.execute("SELECT id FROM posts WHERE platform=? AND post_id=?", (platform, str(post_id))).fetchone()
        c.commit()
        return row["id"]


def add_media(post_row_id, media_index, media_type, file_path, ext, file_size,
              width=0, height=0, source_url="", thumb_path="") -> bool:
    """返回 False 表示该媒体已存在（去重跳过）。"""
    with _lock:
        c = connect()
        cur = c.execute(
            """INSERT OR IGNORE INTO media(post_row_id, media_index, media_type, file_path,
                   thumb_path, ext, file_size, width, height, source_url, downloaded_at)
               VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
            (post_row_id, media_index, media_type, str(file_path), thumb_path or "", ext,
             file_size or 0, width or 0, height or 0, source_url or "", now_iso()),
        )
        c.commit()
        return cur.rowcount > 0


def media_exists(post_row_id, media_index) -> bool:
    with _lock:
        row = connect().execute(
            "SELECT 1 FROM media WHERE post_row_id=? AND media_index=?", (post_row_id, media_index)
        ).fetchone()
        return row is not None


_MEDIA_SELECT = """SELECT m.*, p.platform, p.post_id, p.post_url, p.created_at AS post_time, p.text AS post_text,
       a.handle, a.name AS author_name, a.avatar_url, a.profile_url
FROM media m JOIN posts p ON p.id=m.post_row_id JOIN authors a ON a.id=p.author_id"""


def _filters(platform, mtype, q, author_id, args):
    conds, params = [], []
    if platform:
        conds.append("p.platform=?"); params.append(platform)
    if mtype:
        conds.append("m.media_type=?"); params.append(mtype)
    if author_id:
        conds.append("p.author_id=?"); params.append(author_id)
    if q:
        like = f"%{q}%"
        conds.append("(p.text LIKE ? OR a.handle LIKE ? OR a.name LIKE ?)")
        params += [like, like, like]
    return (" WHERE " + " AND ".join(conds)) if conds else "", params


def list_media_time(platform="", mtype="", q="", author_id=None, offset=0, limit=60):
    where, params = _filters(platform, mtype, q, author_id, [])
    with _lock:
        rows = connect().execute(
            _MEDIA_SELECT + where + " ORDER BY p.created_at DESC, p.id ASC, m.media_index ASC LIMIT ? OFFSET ?",
            params + [limit, offset],
        ).fetchall()
        return [dict(r) for r in rows]


def list_authors(platform="", q="", offset=0, limit=24, media_per_author=12):
    where, params = _filters(platform, "", q, None, [])
    with _lock:
        rows = connect().execute(
            """SELECT a.*, COUNT(m.id) AS media_count, MAX(p.created_at) AS latest_time
               FROM authors a
               JOIN posts p ON p.author_id=a.id JOIN media m ON m.post_row_id=p.id""" + where +
            " GROUP BY a.id ORDER BY latest_time DESC, a.id ASC LIMIT ? OFFSET ?",
            params + [limit, offset],
        ).fetchall()
        out = []
        for r in rows:
            author = dict(r)
            mrows = connect().execute(
                _MEDIA_SELECT + " WHERE p.author_id=? ORDER BY p.created_at DESC, m.media_index ASC LIMIT ?",
                (r["id"], media_per_author),
            ).fetchall()
            author["items"] = [dict(m) for m in mrows]
            out.append(author)
        return out


def get_media(mid):
    with _lock:
        row = connect().execute(_MEDIA_SELECT + " WHERE m.id=?", (mid,)).fetchone()
        return dict(row) if row else None


def delete_media(mid) -> dict | None:
    """删除记录并返回文件路径供调用方删文件。"""
    with _lock:
        c = connect()
        row = c.execute("SELECT file_path, thumb_path FROM media WHERE id=?", (mid,)).fetchone()
        if not row:
            return None
        c.execute("DELETE FROM media WHERE id=?", (mid,))
        c.commit()
        return dict(row)


def stats() -> dict:
    with _lock:
        c = connect()
        by_platform = {r["platform"]: r["n"] for r in c.execute(
            "SELECT p.platform, COUNT(*) AS n FROM media m JOIN posts p ON p.id=m.post_row_id GROUP BY p.platform")}
        total = c.execute("SELECT COUNT(*) AS n, COALESCE(SUM(file_size),0) AS s FROM media").fetchone()
        authors = c.execute("SELECT COUNT(*) AS n FROM authors").fetchone()["n"]
        return {"total": total["n"], "bytes": total["s"], "authors": authors, "by_platform": by_platform}


# ---------- tasks ----------

def insert_task(url, platform="", kind="") -> int:
    with _lock:
        c = connect()
        cur = c.execute("INSERT INTO tasks(url, platform, kind) VALUES(?,?,?)", (url, platform, kind))
        c.commit()
        return cur.lastrowid


def update_task(tid, **fields):
    if not fields:
        return
    with _lock:
        c = connect()
        sets = ", ".join(f"{k}=?" for k in fields)
        c.execute(f"UPDATE tasks SET {sets} WHERE id=?", list(fields.values()) + [tid])
        c.commit()


def get_task(tid):
    with _lock:
        row = connect().execute("SELECT * FROM tasks WHERE id=?", (tid,)).fetchone()
        return dict(row) if row else None


def list_tasks(status="", limit=50):
    where, params = "", []
    if status:
        where = "WHERE status=?"; params = [status]
    with _lock:
        rows = connect().execute(
            f"SELECT * FROM tasks {where} ORDER BY id DESC LIMIT ?", params + [limit]).fetchall()
        return [dict(r) for r in rows]


def delete_task(tid):
    with _lock:
        c = connect()
        c.execute("DELETE FROM tasks WHERE id=?", (tid,))
        c.commit()


def task_running_ids() -> list[int]:
    with _lock:
        rows = connect().execute(
            "SELECT id FROM tasks WHERE status IN ('queued','running')").fetchall()
        return [r["id"] for r in rows]


# ---------- proxy ----------

def upsert_candidate(type_, host, port, source=""):
    with _lock:
        c = connect()
        c.execute(
            """INSERT INTO proxy_candidates(type, host, port, source) VALUES(?,?,?,?)
               ON CONFLICT(type, host, port) DO UPDATE SET source=excluded.source""",
            (type_, host, port, source),
        )
        row = c.execute("SELECT id FROM proxy_candidates WHERE type=? AND host=? AND port=?",
                        (type_, host, port)).fetchone()
        c.commit()
        return row["id"]


def update_candidate(cid, latency_ms=None, speed_mbps=None, score=0, alive=0):
    with _lock:
        c = connect()
        c.execute(
            """UPDATE proxy_candidates SET latency_ms=?, speed_mbps=?, score=?, alive=?, last_tested=?
               WHERE id=?""",
            (latency_ms, speed_mbps, score, alive, now_iso(), cid))
        c.commit()


def set_candidate_score(cid, score, alive):
    with _lock:
        c = connect()
        c.execute("UPDATE proxy_candidates SET score=?, alive=? WHERE id=?", (score, alive, cid))
        c.commit()


def clear_candidates():
    with _lock:
        c = connect()
        c.execute("DELETE FROM proxy_candidates")
        c.commit()


def get_candidates(only_alive=False):
    with _lock:
        sql = "SELECT * FROM proxy_candidates" + (" WHERE alive=1" if only_alive else "") + \
              " ORDER BY score DESC, latency_ms IS NULL, latency_ms ASC"
        return [dict(r) for r in connect().execute(sql).fetchall()]


def add_proxy_log(event, detail=""):
    with _lock:
        c = connect()
        c.execute("INSERT INTO proxy_log(event, detail) VALUES(?,?)", (event, detail))
        c.execute("DELETE FROM proxy_log WHERE id NOT IN (SELECT id FROM proxy_log ORDER BY id DESC LIMIT 200)")
        c.commit()


def get_proxy_log(limit=50):
    with _lock:
        return [dict(r) for r in connect().execute(
            "SELECT * FROM proxy_log ORDER BY id DESC LIMIT ?", (limit,)).fetchall()]
