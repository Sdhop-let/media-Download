"""REST API 路由。"""
import asyncio
import json
import os
import re
from pathlib import Path

from fastapi import APIRouter, Request
from fastapi.responses import FileResponse, JSONResponse, StreamingResponse

from . import config, db
from .events import bus
from .runtime import pm, tm
from .services import extractors

router = APIRouter(prefix="/api")


# ---------- 任务 ----------

@router.get("/tasks")
def list_tasks(status: str = "", limit: int = 50):
    return {"items": db.list_tasks(status, limit)}


@router.post("/tasks")
async def create_tasks(body: dict):
    urls = [u for u in (body.get("urls") or []) if isinstance(u, str) and u.strip()]
    if not urls:
        return JSONResponse({"error": "没有有效的链接"}, status_code=400)
    return await tm.add_urls(urls)


@router.post("/tasks/parse")
def parse_urls(body: dict):
    out = []
    for u in body.get("urls") or []:
        det = extractors.detect((u or "").strip())
        out.append({"url": u, **({"platform": det["platform"], "kind": det["kind"],
                                  "canonical": det["canonical"]} if det else {"platform": None})})
    return {"items": out}


@router.post("/tasks/{tid}/cancel")
def cancel_task(tid: int):
    return {"ok": tm.cancel(tid)}


@router.post("/tasks/{tid}/retry")
async def retry_task(tid: int):
    return {"ok": await tm.retry(tid)}


@router.delete("/tasks/{tid}")
def delete_task(tid: int):
    tm.cancel(tid)
    db.delete_task(tid)
    bus.publish("task_deleted", {"id": tid})
    return {"ok": True}


@router.post("/tasks/{tid}/open-folder")
def open_task_folder(tid: int):
    os.startfile(config.get("download_dir"))  # noqa Windows
    return {"ok": True}


# ---------- 素材库 ----------

@router.get("/media")
def media(sort: str = "time", platform: str = "", type: str = "", q: str = "",
          author_id: int | None = None, offset: int = 0, limit: int = 60):
    limit = min(max(limit, 1), 200)
    if sort == "author":
        authors = db.list_authors(platform, q, offset, min(limit, 48))
        return {"authors": authors, "has_more": len(authors) == min(limit, 48)}
    items = db.list_media_time(platform, type, q, author_id, offset, limit)
    return {"items": items, "has_more": len(items) == limit}


@router.get("/media/stats")
def media_stats():
    return db.stats()


@router.delete("/media/{mid}")
def media_delete(mid: int):
    rec = db.delete_media(mid)
    if not rec:
        return {"ok": False}
    for key in ("file_path", "thumb_path"):
        p = rec.get(key)
        if p:
            try:
                Path(p).unlink(missing_ok=True)
            except OSError:
                pass
    return {"ok": True}


@router.post("/media/{mid}/open")
def media_open(mid: int, what: str = "folder"):
    rec = db.get_media(mid)
    if not rec:
        return {"ok": False}
    target = rec.get("file_path") or ""
    if what == "file" and target:
        os.startfile(target)  # noqa Windows
    else:
        folder = str(Path(target).parent) if target else config.get("download_dir")
        os.startfile(folder)
    return {"ok": True}


@router.get("/file")
def serve_file(path: str, request: Request):
    """带 Range 支持的本地文件服务（限下载目录内，供 <img>/<video> 预览）。"""
    base = Path(config.get("download_dir")).resolve()
    p = Path(path)
    try:
        p = p.resolve()
    except OSError:
        return JSONResponse({"error": "bad path"}, status_code=400)
    if not str(p).startswith(str(base)) or not p.is_file():
        return JSONResponse({"error": "not found"}, status_code=404)

    mime = {"jpg": "image/jpeg", "jpeg": "image/jpeg", "png": "image/png", "webp": "image/webp",
            "gif": "image/gif", "mp4": "video/mp4", "webm": "video/webm", "mov": "video/quicktime",
            "m4v": "video/x-m4v"}.get(p.suffix.lstrip(".").lower(), "application/octet-stream")
    size = p.stat().st_size
    rng = request.headers.get("range")
    if rng:
        m = re.match(r"bytes=(\d*)-(\d*)", rng)
        start = int(m.group(1) or 0)
        end = int(m.group(2) or size - 1) if m.group(2) else min(start + 4 * 1024 * 1024, size - 1)
        end = min(end, size - 1)
        fh = open(p, "rb")
        fh.seek(start)
        return StreamingResponse(_iter_fh(fh, end - start + 1), status_code=206,
                                 media_type=mime,
                                 headers={"Content-Range": f"bytes {start}-{end}/{size}",
                                          "Accept-Ranges": "bytes"})
    return FileResponse(p, media_type=mime, headers={"Accept-Ranges": "bytes"})


def _iter_fh(fh, remaining, chunk=1 << 18):
    try:
        while remaining > 0:
            n = min(chunk, remaining)
            data = fh.read(n)
            if not data:
                break
            remaining -= len(data)
            yield data
    finally:
        fh.close()


# ---------- 代理 ----------

@router.get("/proxy/status")
def proxy_status():
    return pm.snapshot()


@router.get("/proxy/log")
def proxy_log(limit: int = 50):
    return {"items": db.get_proxy_log(limit)}


@router.post("/proxy/detect")
async def proxy_detect():
    asyncio.get_running_loop().create_task(pm.detect_and_test("手动检测"))
    return {"started": True}


@router.post("/proxy/test")
async def proxy_test():
    async def _job():
        if pm.candidates:
            pm.testing = True
            pm._publish()
            try:
                await pm.test_all()
                pm._pick_best()
                db.add_proxy_log("测速", f"手动测速完成，最优 {_label_best(pm)}")
            finally:
                pm.testing = False
                pm._last_full = 0.0
                pm._publish()
        else:
            await pm.detect_and_test("手动测速")
    asyncio.get_running_loop().create_task(_job())
    return {"started": True}


def _label_best(pm_):
    b = pm_.best
    return "直连" if (not b or b.get("type") == "direct") else f"{b['type']}://{b['host']}:{b['port']}"


@router.post("/proxy/mode")
def proxy_mode(body: dict):
    mode = body.get("mode")
    if mode not in ("auto", "manual", "direct"):
        return JSONResponse({"error": "mode 无效"}, status_code=400)
    pm.apply_mode(mode, body.get("manual_proxy"))
    if mode == "auto" and not pm.candidates:
        asyncio.get_running_loop().create_task(pm.detect_and_test("切回自动"))
    return {"ok": True, **pm.snapshot()}


@router.post("/proxy/optimize")
def proxy_optimize(body: dict):
    pm.set_auto_optimize(body.get("enabled"), body.get("interval_min"))
    return {"ok": True, **pm.snapshot()}


# ---------- 设置 ----------

@router.get("/settings")
def get_settings():
    s = config.get()
    s["gallery_dl_version"] = _gdl_version()
    return s


def _gdl_version():
    try:
        import gallery_dl
        return gallery_dl.version.__version__
    except Exception:
        return "?"


@router.put("/settings")
def put_settings(body: dict):
    patch = {k: v for k, v in (body or {}).items() if k in config.DEFAULTS}
    if "download_dir" in patch:
        try:
            Path(patch["download_dir"]).mkdir(parents=True, exist_ok=True)
        except OSError as e:
            return JSONResponse({"error": f"目录不可用：{e}"}, status_code=400)
    if "max_concurrency" in patch:
        patch["max_concurrency"] = min(max(int(patch["max_concurrency"]), 1), 8)
    if "test_interval_min" in patch:
        patch["test_interval_min"] = min(max(int(patch["test_interval_min"]), 1), 120)
    config.set_values(patch)
    return config.get()


# ---------- SSE 事件流 ----------

@router.get("/events")
async def events(request: Request):
    q = bus.subscribe()

    async def gen():
        try:
            yield f"data: {json.dumps({'type': 'hello', 'data': {}}, ensure_ascii=False)}\n\n"
            while True:
                if await request.is_disconnected():
                    break
                try:
                    msg = await asyncio.wait_for(q.get(), timeout=15)
                    yield f"data: {json.dumps(msg, ensure_ascii=False)}\n\n"
                except asyncio.TimeoutError:
                    yield ": ping\n\n"
        finally:
            bus.unsubscribe(q)

    return StreamingResponse(gen(), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})
