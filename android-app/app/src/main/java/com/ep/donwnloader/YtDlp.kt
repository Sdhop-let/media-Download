package com.ep.donwnloader

import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** T8.8：内嵌 yt-dlp 桥。
 *
 *  触发场景（TaskManager 原生提取 ExtractError 后降级）：
 *  - X 单推受限内容（fxtwitter 404 / 无权限）
 *  - X 主页批量（syndication 限流，需 auth_token+ct0）
 *  - Instagram 帖子/主页（移动 API 失效，需 sessionid）
 *
 *  Bluesky 公共 API 稳定，不走此通道。
 */
object YtDlp {

    /** 懒启动 Python 运行时（Python.start 全进程只能调一次，幂等）。 */
    fun ensure() {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(Store.appContext))
        }
    }

    /** App 启动后台预热：解压 stdlib + import yt-dlp，首次任务降级时不再卡 10 秒。 */
    fun warmup() {
        try {
            ensure()
            Python.getInstance().getModule("engine")
            android.util.Log.i("YtDlp", "warmup ok: python started")
        } catch (t: Throwable) {
            android.util.Log.e("YtDlp", "warmup failed", t)
        }
    }

    /** 原生提取失败后的降级入口。不适用/未配 Cookie 返回 null（沿用原错误）。 */
    fun fallbackPlan(url: String): Plan? {
        val det = Extractors.detect(url) ?: return null
        if (det.platform != "twitter" && det.platform != "instagram") return null
        // 主页批量没有 Cookie 必然失败，直接放弃省时间
        if (det.platform == "twitter" && det.kind == "profile" &&
            Store.prefs.twCookieHeader().isBlank()) return null
        if (det.platform == "instagram" && Store.prefs.igCookie.isBlank()) return null
        return try {
            ensure()
            val route = Store.proxy.currentRoute()   // null=直连；yt-dlp 支持 http/socks5
            val cookieFile = writeCookies(det.platform)
            val playlistEnd = if (det.kind == "profile") 30 else 0
            val json = Python.getInstance().getModule("engine")
                .callAttr("extract", det.canonical,
                    cookieFile?.absolutePath ?: "", route ?: "", playlistEnd)
                .toString()
            parsePlan(json, det.platform, det.kind)
        } catch (e: Throwable) {
            android.util.Log.e("YtDlp", "fallback failed for $url", e)
            Plan(det.platform, det.kind, error = e.message ?: e.toString())
        }
    }

    // ---------- Cookies → Netscape cookies.txt ----------

    private fun writeCookies(platform: String): File? {
        val lines = mutableListOf("# Netscape HTTP Cookie File", "")
        fun line(domain: String, name: String, value: String) {
            if (value.isNotBlank()) lines += "$domain\tTRUE\t/\tTRUE\t2000000000\t$name\t$value"
        }
        when (platform) {
            "twitter" -> {
                if (Store.prefs.twCookieHeader().isBlank()) return null
                // extractor 可能命中 x.com 或 twitter.com 域，两个都写
                line(".x.com", "auth_token", Store.prefs.twAuthToken.trim())
                line(".x.com", "ct0", Store.prefs.twCt0.trim())
                line(".twitter.com", "auth_token", Store.prefs.twAuthToken.trim())
                line(".twitter.com", "ct0", Store.prefs.twCt0.trim())
            }
            "instagram" -> {
                val sid = Store.prefs.igCookie.trim()
                    .removePrefix("sessionid=").substringBefore(';').trim()
                if (sid.isBlank()) return null
                line(".instagram.com", "sessionid", sid)
            }
            else -> return null
        }
        val f = File(Store.appContext.cacheDir, "yt_cookies_$platform.txt")
        f.writeText(lines.joinToString("\n") + "\n")
        return f
    }

    // ---------- engine.py JSON → Plan ----------

    private fun parsePlan(json: String, platform: String, kind: String): Plan {
        val j = JSONObject(json)
        val err = j.optString("error", "")
        if (err.isNotBlank()) return Plan(platform, kind, error = err)
        val posts = mutableListOf<PostMeta>()
        val arr: JSONArray = j.optJSONArray("posts") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val media = mutableListOf<MediaRef>()
            val ma: JSONArray = p.optJSONArray("media") ?: JSONArray()
            for (k in 0 until ma.length()) {
                val m = ma.optJSONObject(k) ?: continue
                val u = m.optString("url")
                if (u.isBlank()) continue
                media.add(MediaRef(
                    index = media.size,
                    type = m.optString("type", "photo"),
                    url = u,
                    ext = m.optString("ext", "jpg"),
                    width = m.optInt("width"), height = m.optInt("height"),
                    thumbUrl = m.optString("thumb")))
            }
            if (media.isEmpty()) continue
            val a = p.optJSONObject("author") ?: JSONObject()
            val handle = a.optString("handle")
            posts.add(PostMeta(
                platform = platform,
                postId = p.optString("postId"),
                postUrl = p.optString("postUrl"),
                createdAt = p.optString("createdAt"),
                text = p.optString("text"),
                author = AuthorMeta(
                    handle = handle,
                    name = a.optString("name", handle),
                    avatarUrl = a.optString("avatar"),
                    profileUrl = when (platform) {
                        "twitter" -> "https://x.com/$handle"
                        "instagram" -> "https://www.instagram.com/$handle/"
                        else -> ""
                    }),
                media = media))
        }
        if (posts.isEmpty()) return Plan(platform, kind, error = "yt-dlp 未解析出可下载媒体")
        return Plan(platform, kind, posts = posts)
    }
}
