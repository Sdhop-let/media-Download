"""URL 解析与元数据提取。

提取策略：
- Twitter 单条推文 → fxtwitter API（免登录）
- Bluesky 帖子/主页 → 公共 API（免登录），图片直接构造 getBlob 直链；视频标记走 gallery-dl
- Instagram、Twitter 主页、未识别 URL → gallery-dl 通道（Instagram 需 Cookies）
"""
import re
from urllib.parse import urlparse

import httpx

from ..db import _dt_to_iso as _dt

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

BSKY_PUBLIC = "https://public.api.bsky.app"
BSKY_BLOB = "https://bsky.social/xrpc/com.atproto.sync.getBlob"


class ExtractError(Exception):
    pass


RE_TW_STATUS = re.compile(r"(?:twitter\.com|x\.com)/([A-Za-z0-9_]{1,15})/status(?:es)?/(\d+)")
RE_TW_STATUS_I = re.compile(r"(?:twitter\.com|x\.com)/i/web?/status/(\d+)")
RE_IG_POST = re.compile(r"instagram\.com/(?:[^/?#]+/)?(?:p|reel|reels|tv)/([\w-]+)")
RE_BSKY_POST = re.compile(r"bsky\.app/profile/([\w.\-]+)/post/([a-z0-9]+)", re.I)

TW_RESERVED = {"i", "home", "explore", "notifications", "messages", "settings", "search",
               "intent", "compose", "hashtag", "privacy", "tos", "login", "register",
               "account", "settings", "help", "download"}
IG_RESERVED = {"p", "reel", "reels", "tv", "explore", "accounts", "stories", "direct",
               "about", "developer", "legal", "graphql", "api"}


def _profile_from_path(url: str, host: str, reserved: set, pattern=r"^[A-Za-z0-9_.\-]+$") -> str | None:
    try:
        u = urlparse(url if "//" in url else "https://" + url)
    except Exception:
        return None
    if host not in (u.netloc or "").lower():
        return None
    segs = [s for s in (u.path or "").strip("/").split("/") if s]
    if len(segs) != 1 or segs[0].lower() in reserved or not re.match(pattern, segs[0]):
        return None
    return segs[0]


def detect(url: str) -> dict | None:
    url = (url or "").strip()
    m = RE_TW_STATUS.search(url)
    if m:
        h, tid = m.groups()
        return {"platform": "twitter", "kind": "post", "handle": h, "post_id": tid,
                "canonical": f"https://x.com/{h}/status/{tid}"}
    m = RE_TW_STATUS_I.search(url)
    if m:
        return {"platform": "twitter", "kind": "post", "handle": None, "post_id": m.group(1),
                "canonical": f"https://x.com/i/status/{m.group(1)}"}
    m = RE_IG_POST.search(url)
    if m:
        return {"platform": "instagram", "kind": "post", "handle": None, "post_id": m.group(1),
                "canonical": f"https://www.instagram.com/p/{m.group(1)}/"}
    m = RE_BSKY_POST.search(url)
    if m:
        h, rid = m.groups()
        return {"platform": "bluesky", "kind": "post", "handle": h, "post_id": rid,
                "canonical": f"https://bsky.app/profile/{h}/post/{rid}"}
    h = _profile_from_path(url, "x.com", TW_RESERVED) or _profile_from_path(url, "twitter.com", TW_RESERVED, r"^[A-Za-z0-9_]{1,15}$")
    if h:
        return {"platform": "twitter", "kind": "profile", "handle": h, "post_id": None,
                "canonical": f"https://x.com/{h}"}
    h = _profile_from_path(url, "instagram.com", IG_RESERVED, r"^[A-Za-z0-9_.]{1,30}$")
    if h:
        return {"platform": "instagram", "kind": "profile", "handle": h, "post_id": None,
                "canonical": f"https://www.instagram.com/{h}/"}
    try:
        u = urlparse(url if "//" in url else "https://" + url)
    except Exception:
        u = None
    if u and "bsky.app" in (u.netloc or "").lower():
        segs = [s for s in (u.path or "").strip("/").split("/") if s]
        if len(segs) == 2 and segs[0].lower() == "profile" and re.match(r"^[\w.\-]+$", segs[1]):
            h = segs[1]
            return {"platform": "bluesky", "kind": "profile", "handle": h, "post_id": None,
                    "canonical": f"https://bsky.app/profile/{h}"}
    return None


def _mkpost(platform, post_id, post_url, created, text, author, media) -> dict:
    return {"platform": platform, "post_id": str(post_id), "post_url": post_url,
            "created_at": created or "", "text": (text or "")[:1000],
            "author": author, "media": media}


# ---------------- Twitter（fxtwitter） ----------------

async def fxtwitter_post(handle: str, tid: str, client: httpx.AsyncClient) -> dict:
    r = await client.get(f"https://api.fxtwitter.com/{handle}/status/{tid}",
                         headers={"User-Agent": UA})
    try:
        j = r.json()
    except Exception:
        raise ExtractError(f"fxtwitter 响应异常（HTTP {r.status_code}）")
    tweet = j.get("tweet") or {}
    if j.get("code") != 200 or not tweet:
        raise ExtractError(f"推文获取失败：{j.get('message') or j.get('code')}")
    a = tweet.get("author") or {}
    h = a.get("screen_name") or handle
    author = {"handle": h, "name": a.get("name") or h,
              "avatar_url": a.get("avatar_url") or "",
              "profile_url": f"https://x.com/{h}"}
    media, items = [], (tweet.get("media") or {}).get("all") or []
    for i, m in enumerate(items):
        t = m.get("type")
        url = m.get("url") or ""
        if not url:
            continue
        if t == "photo":
            mtype, ext = "photo", (m.get("format") or _url_ext(url) or "jpg")
        else:  # video / gif
            mtype, ext = "video", (_url_ext(url) or "mp4")
            if url.endswith(".m3u8"):  # 找 mp4 变体
                for f in m.get("formats") or []:
                    if (f.get("url") or "").endswith(".mp4"):
                        url = f["url"]
                        break
                else:
                    raise ExtractError("该推文视频仅提供 HLS 流，请配置 Cookies 后重试")
        media.append({"index": i, "type": mtype, "url": url, "ext": ext,
                      "width": m.get("width") or 0, "height": m.get("height") or 0,
                      "thumb_url": m.get("thumbnail_url") or (url if t == "photo" else "")})
    if not media:
        raise ExtractError("该推文没有图片或视频")
    return _mkpost("twitter", tweet.get("id") or tid,
                   tweet.get("url") or f"https://x.com/{h}/status/{tid}",
                   _dt(tweet.get("created_timestamp") or tweet.get("created_at")),
                   tweet.get("text") or "", author, media)


# ---------------- Bluesky（公共 API） ----------------

def _bsky_embed_media(did: str, embed: dict) -> tuple[list[dict], bool]:
    """返回 (媒体列表, 需要gallery_dl)。只处理图片；视频走 gallery-dl。"""
    etype = str(embed.get("$type") or embed.get("type") or "").split("#")[0]
    if etype.endswith("recordWithMedia"):
        embed = embed.get("media") or {}
        etype = str(embed.get("$type") or "").split("#")[0]
    media, need_gdl = [], False
    if etype.endswith("embed.images"):
        for i, im in enumerate(embed.get("images") or []):
            img = im.get("image") or {}
            ref = ((img.get("ref") or {}).get("$link")) or img.get("cid")
            # AppView 视图给现成 fullsize/thumb CDN 链接；record 里才是 blob ref，做兜底
            url = im.get("fullsize") or (f"{BSKY_BLOB}?did={did}&cid={ref}" if ref else "")
            if not url:
                continue
            mime = img.get("mimeType") or ""
            ext = _bsky_ext(url) or (mime.split("/")[-1].replace("jpeg", "jpg") if mime else "jpg")
            ar = im.get("aspectRatio") or {}
            media.append({"index": i, "type": "photo", "url": url, "ext": ext,
                          "width": ar.get("width") or 0, "height": ar.get("height") or 0,
                          "thumb_url": im.get("thumb") or ""})
    elif etype.endswith("embed.video"):
        need_gdl = True
    return media, need_gdl


def _bsky_ext(url: str) -> str:
    m = re.search(r"@(\w{3,5})$", url)
    if not m:
        return ""
    return {"jpeg": "jpg"}.get(m.group(1).lower(), m.group(1).lower())


def _bsky_post_from_view(pv: dict) -> tuple[dict | None, bool]:
    rec = pv.get("record") or {}
    a = pv.get("author") or {}
    handle = a.get("handle") or ""
    rid = (pv.get("uri") or "").rsplit("/", 1)[-1] or pv.get("cid")
    media, need_gdl = _bsky_embed_media(a.get("did") or "", pv.get("embed") or {})
    if not media and not need_gdl:
        return None, False
    post = _mkpost(
        "bluesky", rid, f"https://bsky.app/profile/{handle}/post/{rid}",
        _dt(rec.get("createdAt") or pv.get("indexedAt")),
        rec.get("text") or "",
        {"handle": handle, "name": a.get("displayName") or handle,
         "avatar_url": a.get("avatar") or "", "profile_url": f"https://bsky.app/profile/{handle}"},
        media)
    return post, need_gdl


async def bsky_post(handle: str, rid: str, client: httpx.AsyncClient) -> dict:
    did = await _bsky_did(handle, client)
    r = await client.get(f"{BSKY_PUBLIC}/xrpc/app.bsky.feed.getPostThread",
                         params={"uri": f"at://{did}/app.bsky.feed.post/{rid}", "depth": 0})
    if r.status_code != 200:
        raise ExtractError(f"Bluesky 帖子获取失败（HTTP {r.status_code}，可能不存在或非公开）")
    thread = r.json().get("thread") or {}
    pv = thread.get("post")
    if not pv:
        raise ExtractError("Bluesky 帖子不存在或不可见")
    post, need_gdl = _bsky_post_from_view(pv)
    if need_gdl:
        raise _GdlRequired(post.post_url if post else f"https://bsky.app/profile/{handle}/post/{rid}")
    if not post:
        raise ExtractError("该帖子没有图片或视频")
    return post


class _GdlRequired(Exception):
    def __init__(self, url):
        self.url = url
        super().__init__("需要 gallery-dl")


async def _bsky_did(handle: str, client: httpx.AsyncClient) -> str:
    r = await client.get(f"{BSKY_PUBLIC}/xrpc/com.atproto.identity.resolveHandle",
                         params={"handle": handle})
    if r.status_code != 200:
        raise ExtractError(f"Bluesky 用户 {handle} 不存在")
    did = r.json().get("did")
    if not did:
        raise ExtractError(f"无法解析 Bluesky 用户 {handle}")
    return did


async def bsky_profile(handle: str, client: httpx.AsyncClient, max_posts=100) -> dict:
    posts, gdl_urls, cursor, fetched = [], [], None, 0
    while fetched < max_posts:
        params = {"actor": handle, "limit": min(50, max_posts - fetched),
                  "filter": "posts_with_media"}
        if cursor:
            params["cursor"] = cursor
        r = await client.get(f"{BSKY_PUBLIC}/xrpc/app.bsky.feed.getAuthorFeed", params=params)
        if r.status_code != 200:
            raise ExtractError(f"Bluesky 主页获取失败（HTTP {r.status_code}）")
        j = r.json()
        for item in j.get("feed") or []:
            if item.get("reason"):
                continue  # 转发不是原创内容
            post, need_gdl = _bsky_post_from_view(item.get("post") or {})
            if need_gdl and post:
                gdl_urls.append(post["post_url"])
            elif post and post["media"]:
                posts.append(post)
            fetched += 1
        cursor = j.get("cursor")
        if not cursor or not j.get("feed"):
            break
    if not posts and not gdl_urls:
        raise ExtractError("该主页（最近动态中）没有图片或视频")
    return {"posts": posts, "gdl_urls": gdl_urls}


# ---------------- 总规划 ----------------

def _url_ext(url: str) -> str:
    path = urlparse(url).path
    m = re.search(r"\.(\w{2,5})$", path)
    return m.group(1).lower() if m else ""


async def plan(url: str, client: httpx.AsyncClient, settings: dict) -> dict:
    """把一条 URL 变成下载计划：直接下载的 posts + 需要 gallery-dl 的 gdl_urls。"""
    base = {"posts": [], "gdl_urls": [], "platform": "other", "kind": "post", "note": ""}
    det = detect(url)
    if not det:
        return {**base, "gdl_urls": [url], "note": "未识别平台，交给 gallery-dl 处理"}

    p, kind, canon = det["platform"], det["kind"], det["canonical"]
    base.update(platform=p, kind=kind)

    if p == "twitter":
        if kind == "post" and det.get("handle"):
            try:
                post = await fxtwitter_post(det["handle"], det["post_id"], client)
                return {**base, "posts": [post]}
            except ExtractError as e:
                if settings.get("cookies_twitter"):
                    return {**base, "gdl_urls": [canon], "note": f"{e}；改用 gallery-dl"}
                return {**base, "error": str(e) + "。提示：单条推文免登录下载，稍后重试；或配置 Twitter Cookies"}
        if kind == "post":  # /i/web/status/ 无 handle
            if not settings.get("cookies_twitter"):
                return {**base, "error": "该链接缺少用户名，需要在设置中配置 Twitter Cookies 才能下载"}
            return {**base, "gdl_urls": [canon]}
        # profile
        if not settings.get("cookies_twitter"):
            return {**base, "error": "Twitter 主页批量下载需要 Cookies：请在设置中导入浏览器导出的 cookies.txt"}
        return {**base, "gdl_urls": [canon]}

    if p == "instagram":
        return {**base, "gdl_urls": [canon]}

    if p == "bluesky":
        try:
            if kind == "post":
                post = await bsky_post(det["handle"], det["post_id"], client)
                return {**base, "posts": [post]}
            return {**base, **await bsky_profile(det["handle"], client,
                                                 max_posts=int(settings.get("profile_max_posts") or 60))}
        except _GdlRequired as e:
            return {**base, "gdl_urls": [e.url], "note": "视频帖子走 gallery-dl 通道"}
        except ExtractError as e:
            return {**base, "error": str(e)}

    return base
