package com.ep.donwnloader

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

// ---------------- 数据结构 ----------------

data class MediaRef(
    val index: Int, val type: String, val url: String, val ext: String,
    val width: Int, val height: Int, val thumbUrl: String,
)

data class AuthorMeta(
    val handle: String, val name: String, val avatarUrl: String, val profileUrl: String,
    /** 平台稳定用户 ID（X id_str / IG pk / BSky DID）。handle 可变（改名），UID 永不改变。 */
    val uid: String = "",
)

data class PostMeta(
    val platform: String, val postId: String, val postUrl: String,
    val createdAt: String, val text: String, val author: AuthorMeta, val media: List<MediaRef>,
)

data class Detected(
    val platform: String, val kind: String, val handle: String?, val postId: String?,
    val canonical: String,
)

data class Plan(
    val platform: String, val kind: String,
    val posts: List<PostMeta> = emptyList(),
    val error: String? = null,
    val note: String = "",
)

class ExtractError(msg: String) : Exception(msg)

// ---------------- URL 识别 ----------------

object Extractors {
    private val TW_STATUS = Regex("(?:twitter\\.com|x\\.com)/([A-Za-z0-9_]{1,15})/status(?:es)?/(\\d+)", RegexOption.IGNORE_CASE)
    private val TW_STATUS_I = Regex("(?:twitter\\.com|x\\.com)/i/web?/status/(\\d+)", RegexOption.IGNORE_CASE)
    private val IG_POST = Regex("instagram\\.com/(?:[^/?#]+/)?(?:p|reel|reels|tv)/([\\w-]+)", RegexOption.IGNORE_CASE)
    private val BSKY_POST = Regex("bsky\\.app/profile/([\\w.\\-]+)/post/([a-z0-9]+)", RegexOption.IGNORE_CASE)

    private val TW_RESERVED = setOf("i", "home", "explore", "notifications", "messages", "settings",
        "search", "intent", "compose", "hashtag", "privacy", "tos", "login", "register",
        "account", "help", "download")
    private val IG_RESERVED = setOf("p", "reel", "reels", "tv", "explore", "accounts", "stories",
        "direct", "about", "developer", "legal", "graphql", "api")

    fun detect(rawUrl: String): Detected? {
        val url = rawUrl.trim()
        TW_STATUS.find(url)?.let { m ->
            val (h, tid) = m.destructured
            return Detected("twitter", "post", h, tid, "https://x.com/$h/status/$tid")
        }
        TW_STATUS_I.find(url)?.let { m ->
            return Detected("twitter", "post", null, m.groupValues[1], "https://x.com/i/status/${m.groupValues[1]}")
        }
        IG_POST.find(url)?.let { m ->
            return Detected("instagram", "post", null, m.groupValues[1], "https://www.instagram.com/p/${m.groupValues[1]}/")
        }
        BSKY_POST.find(url)?.let { m ->
            val (h, rid) = m.destructured
            return Detected("bluesky", "post", h, rid, "https://bsky.app/profile/$h/post/$rid")
        }
        // 主页识别
        val noScheme = url.let { u ->
            if (u.startsWith("https://", true) || u.startsWith("http://", true)) u.substringAfter("://", u) else u
        }
        val host = noScheme.substringBefore('/').removePrefix("www.").lowercase()
        val path = noScheme.substringAfter('/').substringBefore('?').substringBefore('#').trim('/')
        val segs = path.split('/').filter { it.isNotBlank() }
        when {
            host.endsWith("bsky.app") &&
                segs.size == 2 && segs[0].equals("profile", true) &&
                Regex("^[\\w.\\-]+$").matches(segs[1]) ->
                return Detected("bluesky", "profile", segs[1], null, "https://bsky.app/profile/${segs[1]}")
            segs.size == 1 && path.isNotEmpty() -> {
                when {
                    host.endsWith("x.com") || host.endsWith("twitter.com") -> {
                        if (path.lowercase() !in TW_RESERVED && Regex("^[A-Za-z0-9_]{1,15}$").matches(path))
                            return Detected("twitter", "profile", path, null, "https://x.com/$path")
                    }
                    host.endsWith("instagram.com") -> {
                        if (path.lowercase() !in IG_RESERVED && Regex("^[A-Za-z0-9_.]{1,30}$").matches(path))
                            return Detected("instagram", "profile", path, null, "https://www.instagram.com/$path/")
                    }
                    host.endsWith("bsky.app") -> {
                        if (path.lowercase() != "profile" && Regex("^[\\w.\\-]+$").matches(path))
                            return Detected("bluesky", "profile", path, null, "https://bsky.app/profile/$path")
                    }
                }
            }
        }
        return null
    }

    // ---------------- fxtwitter（X 单条推文，免登录） ----------------

    /** X 作者资料（存量巡检回填用）：api.fxtwitter.com/{handle} → user.id/name/avatar_url（头像是共享导入作者的关键补齐项）。 */
    data class XUserProfile(val uid: String, val name: String, val avatarUrl: String)

    fun twitterProfile(handle: String, http: Http): XUserProfile {
        val u = http.getJson("https://api.fxtwitter.com/$handle").optJSONObject("user")
            ?: throw ExtractError("X 用户 $handle 获取失败")
        val id = u.optString("id", "")
        if (id.isBlank()) throw ExtractError("X 用户 $handle 获取失败")
        // fxtwitter 给的是 _normal（48px）变体，素材库显示必然糊——统一升级 _400x400 高清变体
        val avatar = u.optString("avatar_url", "").replace("_normal.", "_400x400.")
        return XUserProfile(id, u.optString("name", ""), avatar)
    }

    fun fxtwitterPost(handle: String, tid: String, http: Http): PostMeta {
        val body = http.getJson("https://api.fxtwitter.com/$handle/status/$tid")
        val code = body.optInt("code", -1)
        val tweet = body.optJSONObject("tweet") ?: throw ExtractError(
            body.optString("message").ifBlank { "推文获取失败（code=$code）" })
        val a = tweet.optJSONObject("author") ?: JSONObject()
        val h = a.optString("screen_name", handle)
        val author = AuthorMeta(
            handle = h, name = a.optString("name", h),
            avatarUrl = a.optString("avatar_url", ""),
            profileUrl = "https://x.com/$h",
            uid = a.optString("id", ""))
        val media = mutableListOf<MediaRef>()
        val all = tweet.optJSONObject("media")?.optJSONArray("all")
        if (all != null) {
            for (i in 0 until all.length()) {
                val m = all.optJSONObject(i) ?: continue
                val type = m.optString("type")
                var url = m.optString("url")
                if (url.isBlank()) continue
                if (type == "photo") {
                    val ext = m.optString("format").ifBlank { urlExt(url) ?: "jpg" }
                    media.add(MediaRef(i, "photo", url, ext, m.optInt("width"), m.optInt("height"),
                        url))
                } else {
                    var ext = urlExt(url) ?: "mp4"
                    if (url.endsWith(".m3u8")) {
                        val formats = m.optJSONArray("formats")
                        var found = false
                        if (formats != null) for (j in 0 until formats.length()) {
                            val f = formats.optJSONObject(j) ?: continue
                            val fu = f.optString("url")
                            if (fu.endsWith(".mp4")) { url = fu; ext = "mp4"; found = true; break }
                        }
                        if (!found) throw ExtractError("该推文视频仅提供 HLS 流，暂不支持")
                    }
                    media.add(MediaRef(i, "video", url, ext, m.optInt("width"), m.optInt("height"),
                        m.optString("thumbnail_url")))
                }
            }
        }
        if (media.isEmpty()) throw ExtractError("该推文没有图片或视频")
        return PostMeta(
            platform = "twitter", postId = tweet.optString("id", tid),
            postUrl = tweet.optString("url", "https://x.com/$h/status/$tid"),
            createdAt = toIso(tweet.optJSONObject("created_timestamp")?.toString()
                ?: tweet.optString("created_at")),
            text = tweet.optString("text"), author = author, media = media)
    }

    // ---------------- Bluesky（公共 API，免登录） ----------------

    private val didCache = ConcurrentHashMap<String, String>()

    fun bskyDid(handle: String, http: Http): String {
        didCache[handle]?.let { return it }
        val j = http.getJson(
            "https://public.api.bsky.app/xrpc/com.atproto.identity.resolveHandle?handle=$handle")
        val did = j.optString("did")
        if (did.isBlank()) throw ExtractError("无法解析 Bluesky 用户 $handle")
        didCache[handle] = did
        return did
    }

    /** 处理 embed（视图响应 $type 带 #view 后缀；图片给 fullsize/thumb CDN 直链）。
     *  视频：视图无直链，但 record.embed.video 里是原始 mp4 blob，可经 getBlob 免登录直下。 */
    private fun embedMedia(
        did: String, embed: JSONObject?, recordEmbed: JSONObject?,
    ): Pair<List<MediaRef>, Boolean> {
        val media = mutableListOf<MediaRef>()
        var needVideo = false
        var e = embed
        var etype = e?.optString("\$type", "") ?: ""
        var rec = recordEmbed
        if (etype.contains("recordWithMedia")) {
            e = e?.optJSONObject("media")
            rec = rec?.optJSONObject("media")
            etype = e?.optString("\$type", "") ?: ""
        }
        etype = etype.substringBefore('#')
        when {
            etype.endsWith("embed.images") -> {
                val images = e?.optJSONArray("images")
                if (images != null) for (i in 0 until images.length()) {
                    val im = images.optJSONObject(i) ?: continue
                    var url = im.optString("fullsize")
                    if (url.isBlank()) {
                        val img = im.optJSONObject("image")
                        val ref = img?.optJSONObject("ref")?.optString("\$link") ?: img?.optString("cid")
                        if (!ref.isNullOrBlank()) url = "https://bsky.social/xrpc/com.atproto.sync.getBlob?did=$did&cid=$ref"
                    }
                    if (url.isBlank()) continue
                    val ar = im.optJSONObject("aspectRatio")
                    media.add(MediaRef(i, "photo", url, bskyExt(url) ?: "jpg",
                        ar?.optInt("width") ?: 0, ar?.optInt("height") ?: 0, im.optString("thumb")))
                }
            }
            etype.endsWith("embed.video") -> {
                val recVideo = rec?.optJSONObject("video")
                val ref = recVideo?.optJSONObject("ref")?.optString("\$link") ?: recVideo?.optString("cid")
                if (!ref.isNullOrBlank()) {
                    val ar = e?.optJSONObject("aspectRatio") ?: recVideo?.optJSONObject("aspectRatio")
                    val mime = recVideo?.optString("mimeType", "video/mp4") ?: "video/mp4"
                    media.add(MediaRef(0, "video",
                        "https://bsky.social/xrpc/com.atproto.sync.getBlob?did=$did&cid=$ref",
                        mime.substringAfter('/').ifBlank { "mp4" },
                        ar?.optInt("width") ?: 0, ar?.optInt("height") ?: 0,
                        e?.optString("thumbnail") ?: ""))
                } else {
                    needVideo = true
                }
            }
        }
        return media to needVideo
    }

    private fun bskyExt(url: String): String? {
        val m = Regex("@(\\w{3,5})$").find(url) ?: return null
        return if (m.groupValues[1].equals("jpeg", true)) "jpg" else m.groupValues[1].lowercase()
    }

    private fun postFromView(pv: JSONObject): Pair<PostMeta?, Boolean> {
        val record = pv.optJSONObject("record") ?: JSONObject()
        val a = pv.optJSONObject("author") ?: JSONObject()
        val handle = a.optString("handle")
        val uri = pv.optString("uri")
        val rid = uri.substringAfterLast('/').ifBlank { pv.optString("cid") }
        val (media, needVideo) = embedMedia(
            a.optString("did"), pv.optJSONObject("embed"), record.optJSONObject("embed"))
        if (media.isEmpty() && !needVideo) return null to false
        val post = PostMeta(
            platform = "bluesky", postId = rid,
            postUrl = "https://bsky.app/profile/$handle/post/$rid",
            createdAt = toIso(record.optString("createdAt").ifBlank { pv.optString("indexedAt") }),
            text = record.optString("text"),
            author = AuthorMeta(handle, a.optString("displayName", handle),
                a.optString("avatar"), "https://bsky.app/profile/$handle",
                a.optString("did", "")),
            media = media)
        return post to needVideo
    }

    fun bskyPost(handle: String, rid: String, http: Http): PostMeta {
        val did = bskyDid(handle, http)
        val j = http.getJson(
            "https://public.api.bsky.app/xrpc/app.bsky.feed.getPostThread?uri=at://$did/app.bsky.feed.post/$rid&depth=0")
        val pv = j.optJSONObject("thread")?.optJSONObject("post")
            ?: throw ExtractError("Bluesky 帖子不存在或不可见")
        val (post, needVideo) = postFromView(pv)
        if (needVideo) throw ExtractError("该视频无法获取直链（可能已被平台移除）")
        if (post == null || post.media.isEmpty()) throw ExtractError("该帖子没有图片或视频")
        return post
    }

    fun bskyProfile(handle: String, http: Http, maxPosts: Int): List<PostMeta> {
        val posts = mutableListOf<PostMeta>()
        var cursor: String? = null
        var fetched = 0
        while (fetched < maxPosts) {
            var url = "https://public.api.bsky.app/xrpc/app.bsky.feed.getAuthorFeed" +
                "?actor=$handle&limit=${minOf(50, maxPosts - fetched)}&filter=posts_with_media"
            // 服务端返回的分页 token 未编码拼接可被注入参数，需编码后拼接
            if (cursor != null) url += "&cursor=" + java.net.URLEncoder.encode(cursor, "UTF-8")
            val j = http.getJson(url)
            val feed = j.optJSONArray("feed") ?: break
            for (i in 0 until feed.length()) {
                val item = feed.optJSONObject(i) ?: continue
                if (item.has("reason")) continue  // 跳过转发
                val (post, needVideo) = postFromView(item.optJSONObject("post") ?: JSONObject())
                if (post != null && post.media.isNotEmpty()) posts.add(post)
                fetched++
            }
            cursor = j.optString("cursor").ifBlank { null }
            if (cursor == null || feed.length() == 0) break
        }
        if (posts.isEmpty()) throw ExtractError("该主页（最近动态中）没有图片或视频")
        return posts
    }

    // ---------------- Instagram（移动 API，需 sessionid Cookie，实验性） ----------------

    private const val IG_APP_ID = "936619743392459"
    private const val IG_UA = "Instagram 275.0.0.27.98 Android (33/13; 420dpi; 1080x2400; samsung; SM-G991B; o1s; exynos2100; en_US; 458229234)"

    private fun igHeaders(cookie: String) = mapOf(
        "User-Agent" to IG_UA,
        "X-IG-App-ID" to IG_APP_ID,
        "Cookie" to cookie,
    )

    /** 供存量巡检（TaskManager.backfillAuthorUids）构造 IG 请求头。 */
    fun igUserAgent() = IG_UA
    fun igAppId() = IG_APP_ID

    /** IG shortcode → media id；含字母表外字符返回 null（调用方给明确错误，不静默错算）。 */
    private fun shortcodeToId(sc: String): Long? {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        var id = 0L
        for (ch in sc) {
            val v = alphabet.indexOf(ch)
            if (v < 0) return null
            id = id * 64 + v
        }
        return id
    }

    private fun igMediaRef(m: JSONObject, idx: Int): MediaRef? {
        val vv = m.optJSONArray("video_versions")?.optJSONObject(0)
        if (vv != null) {
            val img = m.optJSONObject("image_versions2")?.optJSONArray("candidates")?.optJSONObject(0)
            return MediaRef(idx, "video", vv.optString("url"), "mp4",
                vv.optInt("width"), vv.optInt("height"), img?.optString("url") ?: "")
        }
        val cand = m.optJSONObject("image_versions2")?.optJSONArray("candidates")?.optJSONObject(0)
            ?: return null
        return MediaRef(idx, "photo", cand.optString("url"), "jpg",
            cand.optInt("width"), cand.optInt("height"), "")
    }

    private fun igItemToPost(item: JSONObject): PostMeta? {
        val user = item.optJSONObject("user") ?: JSONObject()
        val handle = user.optString("username")
        val code = item.optString("code")
        val media = mutableListOf<MediaRef>()
        val carousel = item.optJSONArray("carousel_media")
        if (carousel != null) {
            for (i in 0 until carousel.length()) {
                igMediaRef(carousel.optJSONObject(i) ?: continue, i)?.let { media.add(it) }
            }
        } else {
            igMediaRef(item, 0)?.let { media.add(it) }
        }
        if (media.isEmpty() || handle.isBlank()) return null
        val cap = item.optJSONObject("caption")
        return PostMeta(
            platform = "instagram", postId = item.optString("id", code),
            postUrl = "https://www.instagram.com/p/$code/",
            createdAt = toIso(item.optLong("taken_at").toString()),
            text = cap?.optString("text") ?: "",
            author = AuthorMeta(handle, user.optString("full_name", handle),
                user.optString("profile_pic_url"), "https://www.instagram.com/$handle/",
                user.optString("pk").ifBlank { user.optString("id", "") }),
            media = media)
    }

    fun instagramPost(shortcode: String, http: Http, cookie: String): PostMeta {
        if (cookie.isBlank()) throw ExtractError("需要 Instagram Cookies：在设置页填入浏览器导出的 sessionid")
        val mid = shortcodeToId(shortcode) ?: throw ExtractError("无效的 Instagram 帖子链接（shortcode 含非法字符）")
        val j = http.getJson("https://i.instagram.com/api/v1/media/$mid/info/", igHeaders(cookie))
        val item = j.optJSONArray("items")?.optJSONObject(0)
            ?: throw ExtractError("帖子获取失败（Cookies 可能过期或帖子不可见）")
        return igItemToPost(item) ?: throw ExtractError("该帖没有图片或视频")
    }

    fun instagramProfile(username: String, http: Http, cookie: String, maxPosts: Int): List<PostMeta> {
        if (cookie.isBlank()) throw ExtractError("需要 Instagram Cookies：在设置页填入浏览器导出的 sessionid")
        val uj = http.getJson(
            "https://i.instagram.com/api/v1/users/web_profile_info/?username=$username", igHeaders(cookie))
        val user = uj.optJSONObject("data")?.optJSONObject("user")
            ?: throw ExtractError("用户不存在或 Cookies 已失效")
        val uid = user.optString("id")
        val posts = mutableListOf<PostMeta>()
        var cursor: String? = null
        while (posts.size < maxPosts) {
            var url = "https://i.instagram.com/api/v1/feed/user/$uid/?count=33"
            // 服务端返回的分页 token 未编码拼接可被注入参数，需编码后拼接
            if (cursor != null) url += "&max_id=" + java.net.URLEncoder.encode(cursor, "UTF-8")
            val j = http.getJson(url, igHeaders(cookie))
            val items = j.optJSONArray("items") ?: break
            for (i in 0 until items.length()) {
                igItemToPost(items.optJSONObject(i) ?: continue)?.let { if (it.media.isNotEmpty()) posts.add(it) }
            }
            cursor = j.optString("next_max_id").ifBlank { null }
            if (cursor == null || items.length() == 0) break
        }
        if (posts.isEmpty()) throw ExtractError("该主页没有可下载的媒体（Cookies 可能过期）")
        return posts
    }

    // ---------------- X 主页（syndication 免登录，实验性） ----------------

    fun twitterProfile(handle: String, http: Http, maxTweets: Int): List<PostMeta> {
        val html = http.getText("https://syndication.twitter.com/srv/timeline-profile/screen-name/$handle")
        val ids = Regex("/$handle/status/(\\d+)").findAll(html)
            .map { it.groupValues[1] }.distinct().take(maxTweets).toList()
        if (ids.isEmpty()) throw ExtractError("X 主页获取失败：接口限流或无媒体，稍后重试")
        val posts = ids.mapNotNull { p ->
            runCatching { fxtwitterPost(handle, p, http) }.getOrNull()
        }.filter { it.media.isNotEmpty() }
        if (posts.isEmpty()) throw ExtractError("该主页最近推文没有图片或视频")
        return posts
    }

    // ---------------- 规划 ----------------

    fun plan(url: String, http: Http, maxBskyPosts: Int, igCookie: String): Plan {
        val det = detect(url)
            ?: return Plan("other", "post", error = "暂不支持该链接（支持 X 推文/主页、Instagram、Bluesky）")
        return try {
            when (det.platform) {
                "twitter" ->
                    if (det.kind == "post" && det.handle != null)
                        Plan("twitter", "post", posts = listOf(fxtwitterPost(det.handle, det.postId!!, http)))
                    else if (det.kind == "profile")
                        Plan("twitter", "profile",
                            posts = twitterProfile(det.handle!!, http, 12),
                            note = "实验性：免登录抓取最近推文")
                    else Plan("twitter", det.kind,
                        error = "该链接缺少用户名，请使用形如 x.com/用户名/status/123 的链接")
                "bluesky" ->
                    if (det.kind == "post")
                        Plan("bluesky", "post", posts = listOf(bskyPost(det.handle!!, det.postId!!, http)))
                    else Plan("bluesky", "profile", posts = bskyProfile(det.handle!!, http, maxBskyPosts))
                "instagram" ->
                    if (det.kind == "post")
                        Plan("instagram", "post", posts = listOf(instagramPost(det.postId!!, http, igCookie)))
                    else Plan("instagram", "profile",
                        posts = instagramProfile(det.handle!!, http, igCookie, 60))
                else -> Plan(det.platform, det.kind, error = "暂不支持该平台")
            }
        } catch (e: ExtractError) {
            Plan(det.platform, det.kind, error = e.message)
        }
    }

    private fun urlExt(url: String): String? {
        val path = url.substringBefore('?').substringBefore('#')
        return Regex("\\.(\\w{2,5})$").find(path)?.groupValues?.get(1)?.lowercase()
    }
}
