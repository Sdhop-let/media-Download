package com.ep.donwnloader

/** Clash 配置最小解析：提取 proxies 节点（flow `{...}` 与 block 两种 YAML 风格）+ 裸代理链接。
 *  仅 http/socks5 能被 OkHttp 直接使用；ss/vmess/trojan 等加密协议标记为不可用（需本地内核）。
 *  注意：flow 值内含逗号的名字会被简单切分，仅保证 server/port 等关键字段可靠。 */
object ClashConfig {
    data class Node(val name: String, val type: String, val host: String, val port: Int, val usable: Boolean)

    private val USABLE = setOf("http", "socks5")
    /** 快速筛选用：行首带受支持 scheme 即认为裸代理链接候选（真解析交给 ProxyUrl）。 */
    private val BARE_HINT_RE = Regex("^(https?|socks5h?)://", RegexOption.IGNORE_CASE)

    fun parse(text: String): List<Node> {
        val t = text.trim()
        var nodes = parseImpl(t)
        // 订阅链接常返回 base64：内容没有 proxies: 且解析为空时尝试解码再解析
        if (nodes.isEmpty() && !t.contains("proxies:")) {
            runCatching {
                val decoded = String(java.util.Base64.getMimeDecoder().decode(t))
                if (decoded.contains("proxies:") || BARE_HINT_RE.containsMatchIn(decoded)) {
                    nodes = parseImpl(decoded)
                }
            }
        }
        return nodes
    }

    private fun parseImpl(text: String): List<Node> {
        val out = mutableListOf<Node>()
        val seen = mutableSetOf<String>()
        // 裸链接行：http(s)/socks5(h)://[user:pass@]host:port[/]，真解析交给 ProxyUrl（容忍 IPv6/认证/尾斜杠）
        text.lines().forEach { raw ->
            val line = raw.trim()
            if (!BARE_HINT_RE.containsMatchIn(line)) return@forEach
            val u = ProxyUrl.parse(line, requireScheme = true) ?: return@forEach
            add(out, seen, u.host, u.scheme, u.port, "链接节点")
        }
        // proxies: 段
        var inProxies = false
        var block = mutableMapOf<String, String>()
        fun flush() {
            if (block.isNotEmpty()) {
                add(out, seen, block["server"] ?: "", block["type"] ?: "",
                    block["port"]?.toIntOrNull() ?: 0, block["name"])
                block = mutableMapOf()
            }
        }
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("proxies:")) { inProxies = true; flush(); continue }
            if (!inProxies) continue
            when {
                line.startsWith("- {") -> {          // flow 单行条目
                    flush()
                    val m = flowMap(line.removePrefix("-").trim().trim('{', '}'))
                    add(out, seen, m["server"] ?: "", m["type"] ?: "",
                        m["port"]?.toIntOrNull() ?: 0, m["name"])
                }
                line.startsWith("-") -> {            // block 条目首行（- name: xx）
                    flush()
                    block.putAll(flowMap(line.removePrefix("-").trim()))
                }
                line.contains(":") -> block.putAll(flowMap(line))
            }
        }
        flush()
        return out.distinctBy { "${it.type}/${it.host}/${it.port}/${it.name}" }
    }

    /** 解析 "k: v, k2: v2" 片段；值容忍成对引号。 */
    private fun flowMap(s: String): Map<String, String> {
        val m = mutableMapOf<String, String>()
        s.split(',').forEach { p ->
            val i = p.indexOf(':')
            if (i > 0) m[p.substring(0, i).trim()] = p.substring(i + 1).trim().trim('"', '\'')
        }
        return m
    }

    private fun add(out: MutableList<Node>, seen: MutableSet<String>,
                    host: String, type: String, port: Int, name: String?) {
        val t = type.lowercase()
        if (host.isBlank() || port !in 1..65535) return
        if (!seen.add("$t/$host/$port")) return
        out.add(Node((name ?: host).trim('"', '\''), t, host, port, t in USABLE))
    }
}
