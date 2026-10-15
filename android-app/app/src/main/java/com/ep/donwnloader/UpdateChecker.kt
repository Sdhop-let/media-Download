package com.ep.donwnloader

import org.json.JSONObject

/** GitHub Release 信息（更新检测结果）。 */
data class ReleaseInfo(
    val tag: String,          // 版本号，如 v1.0
    val name: String,         // Release 标题
    val notes: String,        // 更新内容（已清理为可读纯文本）
    val publishedAt: String,  // 发布时间 ISO8601
    val apkUrl: String,       // 附件 APK 直链（无 APK 附件则空）
    val pageUrl: String,      // Release 页面
)

/**
 * 更新检测 —— GitHub Releases（Sdhop-let/media-Download）。
 *
 * 走 App 已有的 Http(route)，因此自动跟随「代理优选」路由；调用方负责
 * 失败时回退直连重试（注意不要写成 listOfNotNull(cur, null)：cur 为 null
 * 时会被过滤成空表，导致一个请求都不发就报失败）。
 */
object UpdateChecker {

    const val REPO_URL = "https://github.com/Sdhop-let/media-Download"

    private const val API =
        "https://api.github.com/repos/Sdhop-let/media-Download/releases/latest"

    /** 拉取最新 release；失败（网络 / 限流 / 仓库无 release）抛异常，由调用方兜提示。 */
    fun check(route: String?): ReleaseInfo {
        val j = Http(route).getJson(API, mapOf("Accept" to "application/vnd.github+json"))
        val tag = j.optString("tag_name").trim()
        if (tag.isBlank()) throw Exception("仓库没有可识别的版本号")
        return ReleaseInfo(
            tag = tag,
            name = j.optString("name").trim().ifBlank { tag },
            notes = cleanNotes(j.optString("body")),
            publishedAt = j.optString("published_at").trim(),
            apkUrl = firstApkUrl(j),
            pageUrl = j.optString("html_url").trim().ifBlank { REPO_URL },
        )
    }

    /** 从 assets 里挑第一个 .apk 附件直链；没有则返回空串。 */
    private fun firstApkUrl(j: JSONObject): String {
        val assets = j.optJSONArray("assets") ?: return ""
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            if (a.optString("name").lowercase().endsWith(".apk")) {
                return a.optString("browser_download_url").trim()
            }
        }
        return ""
    }

    /** 版本号比较：>0 表示 a 比 b 新。支持 v 前缀与多段数字（1.2.3）；无法解析的段按 0。 */
    fun compare(a: String, b: String): Int {
        fun parts(s: String): List<Int> = s.trim()
            .removePrefix("v").removePrefix("V")
            .split('.')
            .map { Regex("^\\d+").find(it.trim())?.value?.toIntOrNull() ?: 0 }
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }

    /**
     * Markdown release notes → 可读纯文本：
     * 删图片；链接只留文字；表格分隔线/水平线整行丢弃；表格列换「 · 」；
     * 去反引号、加粗、标题符号；连续空行折叠为一个。
     */
    fun cleanNotes(md: String?): String {
        if (md.isNullOrBlank()) return ""
        val out = StringBuilder()
        var blank = 0
        for (raw in md.lines()) {
            var s = raw
            s = Regex("!\\[[^]]*\\]\\([^)]*\\)").replace(s, "")      // 图片
            s = Regex("\\[([^]]*)\\]\\([^)]*\\)").replace(s, "$1")   // 链接只留文字
            if (Regex("[|:\\-\\s]+").matches(s)) continue           // 表格分隔线 / 水平线
            s = Regex("\\s*\\|\\s*").replace(s, " · ")               // 表格列
            s = s.replace("`", "").replace("**", "").replace("__", "")
            s = s.trimStart('#', ' ')
            if (s.isBlank()) {
                blank++
                if (blank > 1) continue
            } else blank = 0
            out.append(s.trimEnd(' ', '·', '|')).append('\n')
        }
        return out.toString().trim().take(4000)
    }
}
