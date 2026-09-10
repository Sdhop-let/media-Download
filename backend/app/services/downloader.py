"""下载任务管理器：异步队列 + 双通道（自研 httpx 字节级下载 / gallery-dl 子进程）+ 去重入库。"""
import asyncio
import json
import re
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import httpx

from .. import config, db
from ..events import bus
from . import extractors
from .proxy_tester import ProxyManager

UA = extractors.UA
VIDEO_EXTS = {"mp4", "webm", "mov", "m4v", "mkv", "gif"}


class TaskError(Exception):
    pass


class TaskCancelled(Exception):
    pass


def _sanitize(s: str) -> str:
    return re.sub(r'[\\/:*?"<>|\s]+', "_", str(s or "unknown")).strip("._") or "unknown"


def _stamp(iso: str) -> str:
    dt = None
    if iso:
        try:
            dt = datetime.fromisoformat(iso.replace("Z", "+00:00"))
        except Exception:
            dt = None
    if dt is None:
        dt = datetime.now(timezone.utc)
    return dt.astimezone(timezone.utc).strftime("%Y%m%d_%H%M")


class TaskManager:
    def __init__(self, pm: ProxyManager):
        self.pm = pm
        self.queue: asyncio.Queue[int] = asyncio.Queue()
        self.cancel_flags: dict[int, asyncio.Event] = {}
        self.procs: dict[int, asyncio.subprocess.Process] = {}
        self._speed: dict[int, tuple] = {}     # tid -> (t_last, bytes_last, speed)
        self._bytes: dict[int, int] = {}       # tid -> 累计字节（本次会话）
        self._routes: dict[int, tuple] = {}    # tid -> (主路由, 备用路由)
        self._fails: dict[int, int] = {}       # tid -> 失败文件数
        self._workers: list[asyncio.Task] = []

    # ---------- 生命周期 ----------

    async def start(self):
        n = max(1, int(config.get("max_concurrency") or 2))
        self._workers = [asyncio.create_task(self._worker(i), name=f"worker-{i}")
                         for i in range(n)]
        # 应用重启后把上次中断的任务标记为失败
        for tid in db.task_running_ids():
            db.update_task(tid, status="error", error="应用重启，任务中断", finished_at=db.now_iso())

    async def add_urls(self, urls: list[str]) -> dict:
        added, dup = [], []
        for url in urls:
            url = url.strip()
            if not url:
                continue
            det = extractors.detect(url)
            active = {t["url"] for t in db.list_tasks()
                      if t["status"] in ("queued", "running")}
            if url in active:
                dup.append(url)
                continue
            tid = db.insert_task(url, platform=(det or {}).get("platform", "other"),
                                 kind=(det or {}).get("kind", ""))
            self.cancel_flags[tid] = asyncio.Event()
            self.queue.put_nowait(tid)
            added.append({"id": tid, "url": url, "platform": (det or {}).get("platform", "other")})
            bus.publish("task_update", db.get_task(tid))
        return {"added": added, "duplicates": dup}

    def cancel(self, tid: int) -> bool:
        task = db.get_task(tid)
        if not task:
            return False
        if task["status"] == "queued":
            db.update_task(tid, status="canceled", finished_at=db.now_iso())
            bus.publish("task_update", db.get_task(tid))
            return True
        if task["status"] == "running":
            self.cancel_flags[tid].set()
            proc = self.procs.get(tid)
            if proc and proc.returncode is None:
                try:
                    proc.terminate()
                except ProcessLookupError:
                    pass
            return True
        return False

    async def retry(self, tid: int) -> bool:
        task = db.get_task(tid)
        if not task or task["status"] not in ("error", "done", "partial", "canceled"):
            return False
        db.update_task(tid, status="queued", error="", message="", total_files=0,
                       done_files=0, skipped=0, downloaded_bytes=0, finished_at="")
        self.cancel_flags[tid] = asyncio.Event()
        self.queue.put_nowait(tid)
        bus.publish("task_update", db.get_task(tid))
        return True

    # ---------- 工作循环 ----------

    async def _worker(self, wid: int):
        while True:
            tid = await self.queue.get()
            try:
                task = db.get_task(tid)
                if not task or task["status"] != "queued":
                    continue
                await self._run(tid)
            except Exception as e:  # 兜底，绝不让 worker 死掉
                db.update_task(tid, status="error", error=repr(e), finished_at=db.now_iso())
                bus.publish("task_update", db.get_task(tid))
            finally:
                self.queue.task_done()

    async def _run(self, tid: int):
        task = db.get_task(tid)
        url = task["url"]
        db.update_task(tid, status="running")
        self._bytes[tid] = 0
        bus.publish("task_update", db.get_task(tid))
        flag = self.cancel_flags[tid]
        try:
            settings = config.get()
            route = self.pm.proxy_url()
            alt = self.pm.alternate_url()
            self._routes[tid] = (route, alt)
            plan = await self._net(
                lambda c: extractors.plan(url, c, settings), route, alt)
            if plan.get("error"):
                raise TaskError(plan["error"])
            if plan["platform"] == "instagram" and not settings.get("cookies_instagram"):
                raise TaskError("Instagram 需要 Cookies：请用浏览器扩展导出 cookies.txt，并在设置中填入路径")
            total = sum(len(p["media"]) for p in plan["posts"]) + len(plan["gdl_urls"])
            db.update_task(tid, total_files=total, platform=plan["platform"], kind=plan["kind"],
                           message=plan.get("note", ""))
            for post in plan["posts"]:
                if flag.is_set():
                    raise TaskCancelled()
                await self._download_post(tid, post, route)
            for gurl in plan["gdl_urls"]:
                if flag.is_set():
                    raise TaskCancelled()
                await self._run_gallery_dl(tid, gurl, plan["platform"], settings)
            t = db.get_task(tid)
            done, skip = t["done_files"], t["skipped"]
            fails = self._fails.get(tid, 0)
            if done or skip:
                status = "done" if not fails else "partial"
                msg = f"完成：下载 {done} 个文件" + (f"，跳过重复 {skip}" if skip else "") + \
                      (f"，失败 {fails}" if fails else "")
                if t["message"]:
                    msg = f"{msg}（{t['message']}）"
            else:
                status, msg = "error", (f"{fails} 个文件下载失败" if fails else
                                        t["message"] or "没有下载到任何媒体")
            db.update_task(tid, status=status, message=msg, finished_at=db.now_iso())
        except TaskCancelled:
            db.update_task(tid, status="canceled", finished_at=db.now_iso())
        except TaskError as e:
            db.update_task(tid, status="error", error=str(e), finished_at=db.now_iso())
        except Exception as e:
            db.update_task(tid, status="error", error=repr(e), finished_at=db.now_iso())
        finally:
            self._speed.pop(tid, None)
            self._bytes.pop(tid, None)
            self._routes.pop(tid, None)
            self._fails.pop(tid, None)
            self.procs.pop(tid, None)
            bus.publish("task_update", db.get_task(tid))

    # ---------- 网络路由（主路由失败自动换备用） ----------

    @staticmethod
    def _client(route: str | None) -> httpx.AsyncClient:
        return httpx.AsyncClient(
            proxy=route, follow_redirects=True, headers={"User-Agent": UA},
            timeout=httpx.Timeout(30, connect=15))

    async def _net(self, fn, route: str | None, alt: str | None):
        try:
            async with self._client(route) as c:
                return await fn(c)
        except (httpx.ConnectError, httpx.ConnectTimeout, httpx.ProxyError):
            if alt is None:
                raise
            async with self._client(alt) as c:
                return await fn(c)

    # ---------- 自研下载通道（字节级进度） ----------

    async def _download_post(self, tid: int, post: dict, route: str | None):
        a = post["author"]
        author_id = db.upsert_author(post["platform"], a["handle"], a.get("name", ""),
                                     a.get("avatar_url", ""), a.get("profile_url", ""))
        prow = db.upsert_post(post["platform"], post["post_id"], post["post_url"],
                              author_id, post["created_at"], post.get("text", ""))
        for m in post["media"]:
            if self.cancel_flags[tid].is_set():
                raise TaskCancelled()
            if db.media_exists(prow, m["index"]):
                self._bump(tid, skipped=1)
                continue
            dest = self._dest_path(post, m)
            try:
                size = await self._download_file(tid, m["url"], dest, route)
            except Exception as e:
                self._fails[tid] = self._fails.get(tid, 0) + 1
                dest.unlink(missing_ok=True)
                continue
            thumb = await self._make_thumb(dest, m["type"], m.get("thumb_url", ""), route)
            inserted = db.add_media(prow, m["index"], m["type"], dest, m["ext"], size,
                                    m.get("width") or 0, m.get("height") or 0,
                                    m["url"], thumb)
            if not inserted:
                dest.unlink(missing_ok=True)
                self._bump(tid, skipped=1)
            else:
                self._bump(tid, done=1, bytes_n=size)
            bus.publish("task_update", {**db.get_task(tid), "speed": self._speed_of(tid)})

    async def _download_file(self, tid: int, url: str, dest: Path, route: str | None) -> int:
        dest.parent.mkdir(parents=True, exist_ok=True)
        tmp = dest.with_suffix(dest.suffix + ".part")
        route0, alt = self._routes.get(tid, (route, None))
        attempts = [route0] + ([alt] if alt and alt != route0 else [])
        last_exc: Exception | None = None
        for r in attempts:
            for try_i in range(2):  # 每条路由最多试两次（瞬时抖动容错）
                try:
                    async with self._client(r) as client:
                        await self._stream_to(client, url, tmp, tid)
                    size = tmp.stat().st_size
                    if size == 0:
                        raise httpx.RemoteProtocolError("empty body")
                    tmp.rename(dest)
                    if r != route0:  # 记住成功的路由，后续文件直接走它
                        self._routes[tid] = (r, route0)
                    return size
                except (httpx.TransportError, httpx.HTTPStatusError) as e:
                    last_exc = e
                    tmp.unlink(missing_ok=True)
                    if try_i == 0:
                        await asyncio.sleep(0.8)
        raise TaskError(f"下载失败: {last_exc}")

    async def _stream_to(self, client: httpx.AsyncClient, url: str, tmp: Path, tid: int):
        async with client.stream("GET", url) as r:
            r.raise_for_status()
            with open(tmp, "wb") as f:
                async for chunk in r.aiter_bytes(1 << 16):
                    f.write(chunk)
                    self._bytes[tid] = self._bytes.get(tid, 0) + len(chunk)

    def _bump(self, tid: int, done=0, skipped=0, bytes_n=0):
        t = db.get_task(tid)
        db.update_task(tid, done_files=t["done_files"] + done,
                       skipped=t["skipped"] + skipped,
                       downloaded_bytes=t["downloaded_bytes"] + bytes_n)

    def _speed_of(self, tid: int) -> float:
        now, b = time.monotonic(), self._bytes.get(tid, 0)
        t0 = self._speed.get(tid)
        if not t0:
            self._speed[tid] = (now, b, 0.0)
            return 0.0
        pt, pb, ps = t0
        dt = now - pt
        if dt < 0.8:
            return ps
        sp = max(0.0, (b - pb) / dt)
        self._speed[tid] = (now, b, sp)
        return sp

    def _dest_path(self, post: dict, m: dict) -> Path:
        handle = _sanitize(post["author"]["handle"])
        pid = _sanitize(post["post_id"])
        fname = f"{_stamp(post['created_at'])}_{pid}_{m['index']}.{m['ext']}"
        return config.download_dir() / post["platform"] / handle / fname

    async def _make_thumb(self, dest: Path, mtype: str, thumb_url: str, proxy: str | None) -> str:
        if mtype == "photo":
            return ""
        tdir = dest.parent / ".thumbs"
        tdir.mkdir(exist_ok=True)
        out = tdir / (dest.stem + ".jpg")
        if out.exists():
            return str(out)
        try:
            proc = await asyncio.create_subprocess_exec(
                "ffmpeg", "-y", "-loglevel", "error", "-ss", "0.4", "-i", str(dest),
                "-frames:v", "1", "-vf", "scale=520:-2", str(out),
                stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL)
            await asyncio.wait_for(proc.wait(), 25)
            if proc.returncode == 0 and out.exists() and out.stat().st_size > 0:
                return str(out)
        except Exception:
            pass
        out.unlink(missing_ok=True)
        if thumb_url:
            try:
                async with httpx.AsyncClient(proxy=proxy, follow_redirects=True,
                                             headers={"User-Agent": UA}, timeout=30) as c:
                    r = await c.get(thumb_url)
                    r.raise_for_status()
                    out.write_bytes(r.content)
                    return str(out)
            except Exception:
                pass
        return ""

    # ---------- gallery-dl 子进程通道 ----------

    async def _run_gallery_dl(self, tid: int, url: str, platform: str, settings: dict):
        since = time.time()
        base = config.download_dir()
        archive = base / "gdl_archive.txt"
        archive.parent.mkdir(parents=True, exist_ok=True)
        cmd = [sys.executable, "-m", "gallery_dl", "-d", str(base),
               "--write-metadata", "-o", f"download-archive={archive}"]
        proxy = self.pm.proxy_url()
        if proxy:
            cmd += ["--proxy", proxy]
        cookie_map = {"twitter": settings.get("cookies_twitter"),
                      "instagram": settings.get("cookies_instagram")}
        ck = cookie_map.get(platform)
        if ck and Path(ck).exists():
            cmd += ["-C", str(ck)]
        cmd += [url]

        proc = await asyncio.create_subprocess_exec(
            *cmd, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
        self.procs[tid] = proc
        try:
            out, err = await asyncio.wait_for(proc.communicate(), timeout=60 * 30)
        except asyncio.TimeoutError:
            proc.terminate()
            raise TaskError("gallery-dl 执行超时（30 分钟）")
        if self.cancel_flags[tid].is_set():
            raise TaskCancelled()
        if proc.returncode != 0:
            raise TaskError(self._gdl_error((err or b"").decode("utf-8", "ignore")))
        n = await asyncio.get_event_loop().run_in_executor(
            None, self._index_output, tid, platform, since)
        if n == 0:
            db.update_task(tid, message=db.get_task(tid)["message"] or "gallery-dl 未产出新文件")

    @staticmethod
    def _gdl_error(err_text: str) -> str:
        t = err_text or ""
        low = t.lower()
        if "authrequired" in low or "authenticated cookies" in low or "login required" in low:
            return "需要登录 Cookies：请在设置中配置该平台的 cookies.txt（浏览器导出）"
        if "rate limit" in low or "429" in low:
            return "触发平台限流，请稍后再试"
        for line in t.splitlines():
            line = line.strip()
            if "[error]" in line or line.startswith("Traceback") or "Exception" in line:
                return line[:300]
        return t.strip()[:300] or "gallery-dl 执行失败"

    def _index_output(self, tid: int, platform_hint: str, since_ts: float) -> int:
        """扫描 gallery-dl 产物 + 边车元数据 → 入库（含去重）。返回新增文件数。"""
        base = config.download_dir()
        added = 0
        for f in sorted(base.rglob("*")):
            try:
                if not f.is_file() or f.suffix.lower() in (".json", ".part", ".txt"):
                    continue
                if f.parent.name == ".thumbs":
                    continue
                if f.stat().st_mtime < since_ts - 5:
                    continue
                kw = self._read_sidecar(f)
                rec = self._normalize(kw, f, platform_hint)
                a = rec["author"]
                author_id = db.upsert_author(rec["platform"], a["handle"], a.get("name", ""),
                                             a.get("avatar_url", ""), a.get("profile_url", ""))
                prow = db.upsert_post(rec["platform"], rec["post_id"], rec["post_url"],
                                      author_id, rec["created_at"], rec["text"])
                mtype = "video" if rec["ext"] in VIDEO_EXTS else "photo"
                ok = db.add_media(prow, rec["index"], mtype, f, rec["ext"],
                                  f.stat().st_size, rec["width"], rec["height"],
                                  rec["source_url"])
                if ok:
                    added += 1
                    self._bump(tid, done=1, bytes_n=f.stat().st_size)
                    bus.publish("task_update", {**db.get_task(tid), "speed": 0})
                else:
                    self._bump(tid, skipped=1)
            except Exception:
                continue
        return added

    @staticmethod
    def _read_sidecar(f: Path) -> dict:
        for cand in (f.with_name(f.name + ".json"), f.with_suffix(".json")):
            if cand.exists():
                try:
                    return json.loads(cand.read_text("utf-8"))
                except Exception:
                    return {}
        return {}

    @staticmethod
    def _normalize(kw: dict, f: Path, platform_hint: str) -> dict:
        """把各 extractor 千差万别的 kwdict 规范化成入库结构。"""

        def get(*keys, default=""):
            for k in keys:
                v = kw.get(k)
                if v not in (None, ""):
                    return v
            return default

        def dig(d, *keys, default=""):
            cur = d
            for k in keys:
                if not isinstance(cur, dict):
                    return default
                cur = cur.get(k)
            return cur if cur not in (None, "") else default

        category = get("category", default=platform_hint)
        platform = {"twitter": "twitter", "instagram": "instagram",
                    "bluesky": "bluesky"}.get(category, platform_hint or "other")

        if platform == "twitter":
            u = kw.get("user") or {}
            handle = dig(kw, "user", "screen_name") or dig(kw, "user", "name") or f.parent.name
            name = u.get("name") or handle
            avatar = (u.get("profile_image_url_https") or u.get("profile_image_url") or "").replace("http://", "https://")
            profile_url = f"https://x.com/{handle}"
            post_id = get("tweet_id", "id", default=f.stem)
            post_url = get("tweet_url", default=f"https://x.com/{handle}/status/{post_id}")
        elif platform == "instagram":
            u = kw.get("user") or {}
            handle = dig(kw, "user", "username") or f.parent.name
            name = u.get("full_name") or handle
            avatar = ""
            profile_url = f"https://www.instagram.com/{handle}/"
            post_id = get("post_shortcode", "shortcode", "media_id", "id", default=f.stem)
            post_url = get("post_url", default=f"https://www.instagram.com/p/{post_id}/")
        elif platform == "bluesky":
            a = kw.get("author") or {}
            handle = a.get("handle") or f.parent.name
            name = a.get("displayName") or handle
            avatar = a.get("avatar") or ""
            profile_url = f"https://bsky.app/profile/{handle}"
            post_id = get("post_id", "cid", "id", default=f.stem)
            post_url = get("post_url", default=f"https://bsky.app/profile/{handle}/post/{post_id}")
        else:
            handle = f.parent.name
            name, avatar, profile_url = handle, "", ""
            post_id = get("id", default=f.stem)
            post_url = get("url", default="")

        date_raw = get("date", "createdAt", "created_at", "timestamp", "ctime", default="")
        ext = f.suffix.lstrip(".").lower() or "bin"
        num = get("num", "count", default=0)
        try:
            num = int(num)
        except Exception:
            num = 0
        return {
            "platform": platform, "post_id": str(post_id), "post_url": post_url,
            "created_at": db._dt_to_iso(date_raw), "text": str(get("text", "description", "caption"))[:500],
            "author": {"handle": handle, "name": name, "avatar_url": avatar,
                       "profile_url": profile_url},
            "index": num, "ext": ext, "width": int(get("width", default=0) or 0),
            "height": int(get("height", default=0) or 0),
            "source_url": get("url", default=""),
        }
