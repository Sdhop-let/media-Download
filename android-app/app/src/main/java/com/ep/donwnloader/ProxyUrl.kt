package com.ep.donwnloader

import java.net.URLEncoder

/** 统一代理链接解析 / 校验 / 规范化。
 *
 *  支持：
 *  - scheme：http:// https:// socks5:// socks5h:// socks://（统一为 http 或 socks5）；
 *    无 scheme 时按 http 处理（requireScheme=true 的场景除外）
 *  - 认证：http://user:pass@host:port（SOCKS5 认证 Java Proxy 不支持，解析通过但连接时忽略）
 *  - IPv6：socks5://[::1]:7890（方括号形式）
 *  - 容错：尾斜杠 / 路径截断、缺省端口补全（http→80、https→443、socks5→1080）、包裹引号、首尾空白
 *
 *  解析失败返回 null——调用方必须给出明确错误提示，不再静默丢弃。 */
object ProxyUrl {

    data class Parsed(
        val scheme: String,     // 规范化后："http" 或 "socks5"
        val host: String,       // 不含方括号
        val port: Int,
        val user: String = "",
        val pass: String = "",
        val rawScheme: String = "",  // 用户原始 scheme（未识别时为 ""），日志用
    ) {
        /** Java Proxy 只分 SOCKS / HTTP；https 代理（CONNECT 隧道）同样走 HTTP 类型。 */
        val isSocks: Boolean get() = scheme == "socks5"

        /** 对外展示（不泄露认证信息）。 */
        fun display(): String = "$scheme://$host:$port"

        /** 完整链接（含 URL 编码后的认证，仅用于实际连接）。 */
        fun url(): String {
            if (user.isBlank()) return display()
            val cred = buildString {
                append(URLEncoder.encode(user, "UTF-8"))
                if (pass.isNotBlank()) append(":").append(URLEncoder.encode(pass, "UTF-8"))
            }
            return "$scheme://$cred@$host:$port"
        }
    }

    private val SCHEME_RE = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://")
    private val SUPPORTED = mapOf(
        "http" to "http", "https" to "http",
        "socks5" to "socks5", "socks5h" to "socks5", "socks" to "socks5",
    )
    private val DEFAULT_PORTS = mapOf("http" to 80, "https" to 443, "socks5" to 1080)

    const val HINT = "示例：http://127.0.0.1:7897 · socks5://1.2.3.4:1080 · http://user:pass@host:port · IPv6 用 [::1]:7890"

    /**
     * @param requireScheme true 时无 scheme 直接判无效（用于逐行解析订阅 / 配置，
     *                      避免把 "proxies:"、"port: 1080" 这类普通行误判成代理链接）
     */
    fun parse(raw: String?, requireScheme: Boolean = false): Parsed? {
        if (raw.isNullOrBlank()) return null
        var t = raw.trim().trim('"', '\'', '<', '>')
        if (t.isBlank()) return null

        val sm = SCHEME_RE.find(t)
        val rawScheme = sm?.groupValues?.get(1)?.lowercase() ?: ""
        if (sm == null && requireScheme) return null
        val scheme = SUPPORTED[rawScheme] ?: if (rawScheme.isBlank()) "http" else return null
        var rest = if (sm != null) t.substring(sm.value.length) else t
        rest = rest.substringBefore('/')   // 去路径与尾斜杠

        // 认证段：取最后一个 @，密码里含 @ 也不拆错
        var user = ""
        var pass = ""
        val at = rest.lastIndexOf('@')
        if (at >= 0) {
            val cred = rest.substring(0, at)
            rest = rest.substring(at + 1)
            val ci = cred.indexOf(':')
            if (ci >= 0) { user = cred.substring(0, ci); pass = cred.substring(ci + 1) }
            else user = cred
        }

        // host / port：IPv6 方括号优先，普通 host:port 用最后一个冒号拆
        val host: String
        val port: Int?
        if (rest.startsWith("[")) {
            val close = rest.indexOf(']')
            if (close < 0) return null
            host = rest.substring(1, close)
            val after = rest.substring(close + 1)
            port = if (after.startsWith(":")) after.substring(1).toIntOrNull() else null
        } else {
            val ci = rest.lastIndexOf(':')
            if (ci >= 0) { host = rest.substring(0, ci); port = rest.substring(ci + 1).toIntOrNull() }
            else { host = rest; port = null }
        }
        if (host.isBlank()) return null
        val p = port ?: DEFAULT_PORTS[scheme] ?: return null
        if (p !in 1..65535) return null
        return Parsed(scheme, host, p, user.trim(), pass, rawScheme)
    }
}
