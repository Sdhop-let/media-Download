"""yt-dlp 提取引擎（Chaquopy 内嵌）。

供 Kotlin 侧经 Chaquopy 调用：
    Python.getInstance().getModule("engine").callAttr(
        "extract", url, cookieFile, proxy, playlistEnd)  ->  JSON 字符串

输出格式：
    {"error": "..."}                                   失败时
    {"posts": [{                                       成功时
        "postId": "...", "postUrl": "...",
        "createdAt": "2026-01-01T00:00:00Z", "text": "...",
        "author": {"handle": "...", "name": "...", "avatar": "...", "profileUrl": "..."},
        "media": [{"index": 0, "type": "photo|video", "url": "http直链",
                   "ext": "jpg|mp4", "width": 0, "height": 0, "thumb": "..."}]
    }]}
"""
import datetime
import hashlib
import json
import re

from yt_dlp import YoutubeDL

IMG_EXTS = {"jpg", "jpeg", "png", "webp", "gif"}
_URL_ID = re.compile(r"/(?:status(?:es)?/(\d+)|/(?:p|reel|reels|tv)/([\w-]+))")


def _iso(ts):
    """unix 秒 → ISO8601 UTC（Kotlin 侧 PostMeta.createdAt 同款格式）。"""
    if not ts:
        return ""
    try:
        return datetime.datetime.fromtimestamp(
            int(ts), datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    except Exception:
        return ""


def _is_direct(f):
    """只要 http(s) 渐进式直链：拒绝 HLS/DASH（现有下载器无解复用内核）。"""
    fu = f.get("url") or ""
    if not fu.startswith("http"):
        return False
    proto = f.get("protocol") or ""
    if "m3u8" in proto or "dash" in proto:
        return False
    if fu.endswith(".m3u8") or fu.endswith(".mpd"):
        return False
    return True


def _pick_media(entry):
    """从单个 entry 挑出一条最佳直链媒体；无直链返回 None。"""
    ext = (entry.get("ext") or "").lower()
    url = entry.get("url") or ""
    # X 图片推文：entry 本身就是图片直链
    if ext in IMG_EXTS and url.startswith("http"):
        return {"type": "photo", "url": url, "ext": "jpg" if ext == "jpeg" else ext,
                "width": entry.get("width") or 0, "height": entry.get("height") or 0,
                "thumb": url}
    fmts = entry.get("formats") or []
    cands = [f for f in fmts if _is_direct(f)]
    # 图片帖（Instagram 等）：直链 formats 里找图片
    for f in cands:
        fe = (f.get("ext") or "").lower()
        if fe in IMG_EXTS:
            return {"type": "photo", "url": f["url"],
                    "ext": "jpg" if fe == "jpeg" else fe,
                    "width": f.get("width") or 0, "height": f.get("height") or 0,
                    "thumb": entry.get("thumbnail") or ""}
    # 视频帖：音视频合并的渐进式 mp4 优先，其次按分辨率/码率
    vids = [f for f in cands
            if (f.get("vcodec") or "none") != "none" or (f.get("acodec") or "none") != "none"]
    if not vids:
        return None

    def score(f):
        both = ((f.get("vcodec") or "none") != "none"
                and (f.get("acodec") or "none") != "none")
        return (1 if both else 0, f.get("height") or 0, f.get("tbr") or 0)

    best = max(vids, key=score)
    fext = (best.get("ext") or "mp4").lower()
    if not re.match(r"^[a-z0-9]{2,5}$", fext):
        fext = "mp4"
    return {"type": "video", "url": best["url"], "ext": fext,
            "width": best.get("width") or entry.get("width") or 0,
            "height": best.get("height") or entry.get("height") or 0,
            "thumb": entry.get("thumbnail") or ""}


def _walk(entry, out):
    """递归展开 playlist（X 单推多图 = playlist of entries）。"""
    if not isinstance(entry, dict):
        return
    if entry.get("_type") == "playlist":
        for e in entry.get("entries") or []:
            _walk(e, out)
    else:
        m = _pick_media(entry)
        if m:
            out.append((entry, m))


def _author(entry):
    handle = (entry.get("uploader_id") or entry.get("uploader")
              or entry.get("channel_id") or entry.get("channel") or "")
    name = entry.get("uploader") or entry.get("channel") or handle
    av = ""
    for th in entry.get("thumbnails") or []:
        u = th.get("url") or ""
        if u:
            av = u  # thumbnails 一般从小到大，取最后一个
    return {"handle": str(handle), "name": str(name), "avatar": av, "profileUrl": ""}


def _post_id(entry, page_url):
    wu = entry.get("webpage_url") or page_url
    m = re.search(r"/status(?:es)?/(\d+)", wu)
    if m:
        return m.group(1)
    m = re.search(r"/(?:p|reel|reels|tv)/([\w-]+)", wu)
    if m:
        return m.group(1)
    raw = entry.get("id") or entry.get("display_id")
    if raw:
        return str(raw)
    return hashlib.md5(str(wu).encode("utf-8")).hexdigest()[:12]


def extract(url, cookie_file="", proxy="", playlist_end=0):
    """主入口：提取 → 规范化 JSON 字符串。任何异常都收敛为 {"error": ...}。"""
    opts = {
        "quiet": True, "no_warnings": True, "skip_download": True,
        "noprogress": True, "socket_timeout": 20, "retries": 2,
        "nocheckcertificate": True, "extractor_retries": 1,
    }
    if cookie_file:
        opts["cookiefile"] = cookie_file
    if proxy:
        opts["proxy"] = proxy
    try:
        pe = int(playlist_end or 0)
        if pe > 0:
            opts["playlistend"] = pe  # 主页批量限条数，避免全量抓取
    except Exception:
        pass
    try:
        with YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)
        pairs = []
        _walk(info, pairs)
        seen = set()
        posts = {}
        order = []
        for entry, m in pairs:
            if m["url"] in seen:
                continue
            seen.add(m["url"])
            wu = entry.get("webpage_url") or url
            pid = _post_id(entry, url)
            if pid not in posts:
                posts[pid] = {
                    "postId": pid, "postUrl": wu,
                    "createdAt": _iso(entry.get("timestamp")),
                    "text": entry.get("description") or "",
                    "author": _author(entry), "media": [],
                }
                order.append(pid)
            m = dict(m)
            m["index"] = len(posts[pid]["media"])
            posts[pid]["media"].append(m)
        if not order:
            return json.dumps({"error": "yt-dlp 未解析出可下载的媒体直链"},
                              ensure_ascii=False)
        return json.dumps({"posts": [posts[pid] for pid in order]},
                          ensure_ascii=False)
    except Exception as e:
        msg = str(e) or e.__class__.__name__
        return json.dumps({"error": "yt-dlp: " + msg}, ensure_ascii=False)
