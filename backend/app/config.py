"""全局配置：持久化在 data/settings.json，其余为派生路径。"""
import json
import threading
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DATA_DIR = ROOT / "data"
DB_PATH = DATA_DIR / "app.db"
SETTINGS_PATH = DATA_DIR / "settings.json"

DEFAULTS = {
    "download_dir": str(DATA_DIR / "downloads"),
    "proxy_mode": "auto",          # auto=检测+自动优选 | manual=手动 | direct=直连
    "manual_proxy": "",
    "auto_optimize": True,
    "test_interval_min": 10,
    "max_concurrency": 2,
    "profile_max_posts": 60,
    "cookies_twitter": "",
    "cookies_instagram": "",
    "scan_ports": [7897, 7890, 7891, 2080, 10808, 10809, 1080, 8080, 8888],
}

_lock = threading.Lock()
_cache: dict | None = None


def _load() -> dict:
    global _cache
    if _cache is None:
        merged = dict(DEFAULTS)
        try:
            merged.update(json.loads(SETTINGS_PATH.read_text("utf-8")))
        except Exception:
            pass
        _cache = merged
    return _cache


def get(key: str | None = None):
    with _lock:
        s = _load()
        return dict(s) if key is None else s.get(key, DEFAULTS.get(key))


def set_values(patch: dict) -> dict:
    global _cache
    with _lock:
        s = _load()
        for k, v in patch.items():
            if k in DEFAULTS:
                s[k] = v
        _cache = dict(s)
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        SETTINGS_PATH.write_text(json.dumps(s, ensure_ascii=False, indent=2), "utf-8")
        return dict(s)


def download_dir() -> Path:
    p = Path(get("download_dir"))
    p.mkdir(parents=True, exist_ok=True)
    return p
