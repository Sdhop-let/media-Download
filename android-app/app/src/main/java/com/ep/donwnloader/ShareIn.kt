package com.ep.donwnloader

import okhttp3.Request

/** 从系统分享文本中提取平台链接（X / Instagram / Bluesky）。 */
object ShareIn {
    private val URL_RE = Regex("https?://[^\\s\\u4e00-\\u9fff]+")

    /** 只在直接识别失败时才走的短链域名，避免对任意链接做跳转请求 */
    private val SHORT_HOSTS = listOf("t.co")

    fun extractLinks(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val urls = URL_RE.findAll(text).map { clean(it.value) }
            .filter { it.isNotBlank() }.distinct().toList()
        val direct = urls.filter { Extractors.detect(it) != null }
        if (direct.isNotEmpty()) return direct
        // 没带协议的裸链接（如 x.com/user/status/123）
        val bare = text.split(Regex("[\\s\\u4e00-\\u9fff、，。！？]+"))
            .map { clean(it) }
            .filter { Extractors.detect(it) != null }
        if (bare.isNotEmpty()) return bare.distinct()
        // 短链（如 X 的 t.co）：跟随一次跳转再识别
        return urls.filter { u -> SHORT_HOSTS.any { host(u).endsWith(it) } }
            .mapNotNull { resolveRedirect(it)?.takeIf { r -> Extractors.detect(r) != null } }
            .distinct()
    }

    private fun host(u: String) = u.substringAfter("//").substringBefore('/')

    /** 去掉 URL 尾部被一并带上的标点。 */
    private fun clean(u: String): String =
        u.trim().trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '》', '」', '』', '）', '…',
            '、', '。', '！', '？', '"', '\'', '”', '’')

    /** 不跟随跳转请求一次，取 Location 头（t.co → x.com）。失败返回 null。 */
    private fun resolveRedirect(url: String): String? = runCatching {
        val client = Net.client(Store.proxy.currentRoute())
            .newBuilder().followRedirects(false).build()
        val req = Request.Builder().url(url).header("User-Agent", Http.UA).get().build()
        client.newCall(req).execute().use { resp ->
            val loc = resp.header("location")?.trim().orEmpty()
            if (loc.startsWith("http")) loc else null
        }
    }.getOrNull()
}
