package com.ep.donwnloader

import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Root 代理模块探测层：挖掘系统级代理模块（Magisk / KernelSU / APatch）为本 App 提供的代理能力。
 *
 * 适用模块（root 用户常见）：
 *  - NetProxy-Magisk（Xray 透明代理，dokodemo-door 入站，配置在 /data/adb/modules/netproxy/）
 *  - Box for Root / box_for_magisk（clash / mihomo / sing-box / xray 多内核，配置在 /data/adb/box/）
 *  - Clash for Magisk 及各类 sing-box / xray 系统模块
 *
 * 三条探测路径（合并为一次 su 会话，输出分段解析）：
 *  L1 监听端口枚举：ss/netstat -tlnp → LISTEN 端口 + 进程名，按已知代理进程名关联；
 *  L2 模块配置直读：/data/adb/box/ 下多内核配置与 /data/adb/modules/netproxy/ 下 Xray 配置，
 *     提取显式入站端口（clash yaml 的 mixed-port/socks-port；sing-box/xray json 的 inbounds）；
 *  L3 模块枚举：ls /data/adb/modules，按关键词识别疑似代理模块。
 *
 * 透明代理识别（sing-box tun / xray dokodemo-door / clash redir-port|tproxy-port）：
 *  此模式下 iptables 已全局接管 TCP —— 直连流量本身就是代理流量，无需显式配置，
 *  状态行提示「直连即代理」。
 *
 * 安全与降级：
 *  - 非 root 设备：su 不存在 → IOException 静默降级 suAvailable=false，零影响；
 *  - 未授权（Magisk 授权弹窗超时 / 拒绝）：静默降级，15 分钟静默期内不再尝试（避免反复触发授权弹窗）；
 *  - 成功结果缓存 10 分钟：周期复测与失败重试循环不会频繁起 su 进程。
 */
object RootProxy {

    /** 显式入站：可被 App 直连使用的本地代理口（proto 空 = 类型未知，交由 classify 探测判型）。 */
    data class Inbound(val host: String, val port: Int, val proto: String, val src: String)

    data class Result(
        val suAvailable: Boolean,
        val inbounds: List<Inbound> = emptyList(),
        /** 疑似代理模块名（/data/adb/modules 关键词匹配）。 */
        val modules: List<String> = emptyList(),
        /** 透明代理证据：直连流量已被模块接管（tun / dokodemo-door / redir / tproxy）。 */
        val transparent: List<String> = emptyList(),
    )

    private const val CACHE_MS = 10 * 60_000L        // 成功结果缓存
    private const val FAIL_RETRY_MS = 15 * 60_000L   // su 失败静默期（防反复触发授权弹窗）
    private const val SU_TIMEOUT_MS = 8_000L

    @Volatile private var cached: Result? = null
    @Volatile private var cachedAt = 0L
    @Volatile private var lastFailAt = 0L

    /** 已知代理进程名（对进程名做 contains 匹配）。 */
    private val PROXY_PROCS = listOf(
        "clash", "mihomo", "sing-box", "singbox", "xray", "v2ray", "v2fly",
        "privoxy", "redsocks", "tun2socks", "hev-socks", "netproxy",
        "gost", "brook", "hysteria", "trojan", "naive", "leaf",
        "ss-local", "sslocal", "ssr-local", "shadowtls", "dae", "neko", "tuic",
    )

    /** 模块名关键词（/data/adb/modules 枚举过滤）。 */
    private val MODULE_HINTS = listOf(
        "netproxy", "box", "clash", "mihomo", "sing", "xray", "v2ray",
        "proxy", "shadowsocks", "neko", "leaf", "dae", "tun",
    )

    /** 已知模块配置文件 / 目录 glob（su 会话统一 cat，每文件限 64KB）。 */
    private val CONFIG_GLOBS = listOf(
        "/data/adb/box/clash/config.yaml",
        "/data/adb/box/mihomo/config.yaml",
        "/data/adb/box/sing-box/config.json",
        "/data/adb/box/xray/config.json",
        "/data/adb/modules/netproxy/config/xray/confdir/*.json",
        "/data/adb/modules/netproxy/config/singbox/confdir/*.json",
        "/data/adb/modules/netproxy/xraycore/config/*.json",
        "/data/adb/modules/netproxy/XrayCore/Config/*.json",
        "/data/adb/modules/*/clash/config.yaml",
        "/data/adb/modules/*/sing-box/config.json",
        "/data/adb/clash/config.yaml",
    )

    /** 进程名拿不到（netstat 无 -p）时，落在已知代理端口表内的端口也收。 */
    private val KNOWN_PORTS = setOf(7897, 7890, 7891, 7892, 2080, 10809, 10808, 1080, 8080, 8888, 8118, 3128)

    /** 对外入口：带缓存的探测。 */
    fun probe(): Result {
        val now = System.currentTimeMillis()
        cached?.let { if (now - cachedAt < CACHE_MS) return it }
        if (now - lastFailAt < FAIL_RETRY_MS) return Result(false)
        val r = runSuProbe()
        if (r.suAvailable) { cached = r; cachedAt = now } else { lastFailAt = now }
        return r
    }

    // ---------- su 会话 ----------

    private fun runSuProbe(): Result {
        val script = buildString {
            append("echo ===SS===; (ss -tlnp 2>/dev/null || netstat -tlnp 2>/dev/null || netstat -tln 2>/dev/null);")
            append("echo ===MODS===; ls /data/adb/modules 2>/dev/null;")
            append("echo ===FILES===; for f in")
            CONFIG_GLOBS.forEach { append(" $it") }
            append("; do [ -f \"\$f\" ] && echo \"---FILE:\$f\" && head -c 65536 \"\$f\" && echo; done;")
            append("echo ===END===")
        }
        val raw = suExec(script) ?: return Result(false)
        if (!raw.contains("===END===")) return Result(false)   // 授权弹窗超时 / 输出被截断

        val inbounds = mutableListOf<Inbound>()
        val transparent = mutableListOf<String>()
        parseListen(slice(raw, "===SS===", "===MODS==="), inbounds)
        parseConfigFiles(slice(raw, "===FILES===", "===END==="), inbounds, transparent)
        val modules = slice(raw, "===MODS===", "===FILES===").lines()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && MODULE_HINTS.any { k -> it.contains(k) } }
            .distinct()
        return Result(true, inbounds.distinctBy { it.host to it.port }, modules, transparent.distinct())
    }

    /** su -c 执行；非 root（su 不存在）抛 IOException → null；授权弹窗挂起超时 → null。 */
    private fun suExec(cmd: String): String? = try {
        val p = ProcessBuilder("su", "-c", cmd).start()
        var text: String? = null
        val reader = Thread { text = runCatching { p.inputStream.bufferedReader().readText() }.getOrNull() }
        reader.isDaemon = true
        reader.start()                       // 并行读流，防 stdout 缓冲满死锁
        val ok = p.waitFor(SU_TIMEOUT_MS, TimeUnit.MILLISECONDS)   // 挂起（授权弹窗）时超时返回 false
        runCatching { p.destroy() }
        reader.join(1000)                    // destroy 关流后给读线程 1s 收尾
        if (ok) text else null
    } catch (_: Exception) {
        null
    }

    private fun slice(raw: String, start: String, end: String): String {
        val i = raw.indexOf(start); if (i < 0) return ""
        val from = i + start.length
        val j = raw.indexOf(end, from)
        return if (j < 0) raw.substring(from) else raw.substring(from, j)
    }

    // ---------- L1：监听端口枚举 ----------

    private val ADDR_PORT_RE = Regex("""^(?:\d{1,3}(?:\.\d{1,3}){3}|\*|\[[0-9a-fA-F:]+\]|[0-9a-fA-F:]+):(\d{1,5})$""")
    private val PROC_USERS_RE = Regex("""users:\(\("([^"]+)""")       // ss -p：users:(("mihomo",pid=...
    private val PROC_TAIL_RE = Regex("""(\d+)/([A-Za-z0-9._-]+)\s*$""") // toybox netstat：1234/mihomo

    private fun parseListen(sec: String, out: MutableList<Inbound>) {
        sec.lines().forEach { rawLine ->
            val line = rawLine.trim()
            if (!line.startsWith("tcp")) return@forEach
            var port = -1
            for (c in line.split(Regex("\\s+"))) {
                val m = ADDR_PORT_RE.find(c)
                if (m != null) { port = m.groupValues[1].toIntOrNull() ?: -1; break }
            }
            if (port !in 1..65535) return@forEach
            val proc = (PROC_USERS_RE.find(line)?.groupValues?.get(1)
                ?: PROC_TAIL_RE.find(line)?.groupValues?.get(2))?.lowercase().orEmpty()
            val hit = PROXY_PROCS.firstOrNull { proc.contains(it) }
            when {
                hit != null -> out.add(Inbound("127.0.0.1", port, "", hit))     // proto 未知 → classify 判型
                proc.isEmpty() && port in KNOWN_PORTS -> out.add(Inbound("127.0.0.1", port, "", ""))
            }
        }
    }

    // ---------- L2：模块配置直读 ----------

    private fun parseConfigFiles(sec: String, inbounds: MutableList<Inbound>, transparent: MutableList<String>) {
        var curPath = ""
        val buf = StringBuilder()
        fun flush() {
            val text = buf.toString().trim()
            if (curPath.isNotBlank() && text.isNotBlank()) {
                runCatching {
                    if (curPath.endsWith(".yaml") || curPath.endsWith(".yml")) parseClashYaml(text, curPath, inbounds, transparent)
                    else parseJsonConfig(text, curPath, inbounds, transparent)
                }
            }
            buf.setLength(0)
        }
        sec.lines().forEach { line ->
            if (line.startsWith("---FILE:")) { flush(); curPath = line.removePrefix("---FILE:").trim() }
            else buf.appendLine(line)
        }
        flush()
    }

    private fun srcOf(path: String): String = when {
        path.contains("/netproxy/") -> "netproxy"
        path.contains("/box/") -> "box"
        path.contains("/clash") -> "clash"
        else -> "root模块"
    }

    private val CLASH_PORT_RE = Regex("""(?m)^(mixed-port|socks-port|http-port|port|redir-port|tproxy-port)\s*:\s*(\d+)""")

    private fun parseClashYaml(text: String, path: String, inbounds: MutableList<Inbound>, transparent: MutableList<String>) {
        val src = srcOf(path)
        // 顶层端口字段在 proxies: 之前；截断避免误匹配节点列表内缩进的 port
        val head = text.substringBefore("\nproxies:").substringBefore("proxies:")
        CLASH_PORT_RE.findAll(head).forEach { m ->
            val port = m.groupValues[2].toIntOrNull() ?: return@forEach
            when (m.groupValues[1]) {
                "socks-port" -> inbounds.add(Inbound("127.0.0.1", port, "socks5", src))
                "mixed-port", "http-port", "port" -> inbounds.add(Inbound("127.0.0.1", port, "http", src))
                "redir-port", "tproxy-port" -> transparent.add("$src ${m.groupValues[1]}=$port（透明接管）")
            }
        }
    }

    /** sing-box（type/listen_port/listen）与 xray/v2ray（protocol/port/listen）的 inbounds。 */
    private fun parseJsonConfig(text: String, path: String, inbounds: MutableList<Inbound>, transparent: MutableList<String>) {
        val src = srcOf(path)
        val arr = JSONObject(text).optJSONArray("inbounds") ?: return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val type = o.optString("type", o.optString("protocol", "")).lowercase()
            val port = o.optString("listen_port").toIntOrNull()
                ?: o.optString("port").toIntOrNull() ?: -1
            val listen = o.optString("listen", o.optString("listen_address", "")).trim().lowercase()
            val host = when {
                listen.isBlank() || listen in setOf("0.0.0.0", "::", "[::]", "*") -> "127.0.0.1"
                listen.startsWith("[") -> listen.removePrefix("[").substringBefore("]")
                else -> listen
            }
            when (type) {
                "mixed", "http" -> if (port in 1..65535) inbounds.add(Inbound(host, port, "http", src))
                "socks" -> if (port in 1..65535) inbounds.add(Inbound(host, port, "socks5", src))
                "tun" -> transparent.add("$src tun（透明接管）")
                "dokodemo-door" -> transparent.add("$src dokodemo-door（透明接管）")
            }
        }
    }
}
