package com.ep.donwnloader

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/** 代理候选检测 + 测速 + 持续优选（Android 版）。 */
class ProxyManager {
    val cands = MutableStateFlow<List<ProxyCand>>(emptyList())
    val testing = MutableStateFlow(false)
    val logs = MutableStateFlow<List<ProxyLogItem>>(emptyList())
    val toast = MutableStateFlow<String?>(null)
    /** 最近一次连接失败原因（供页面错误横幅展示）；null = 无错误。 */
    val lastError = MutableStateFlow<String?>(null)
    /** 网络环境状态行（Root 模块发现 / 透明代理接管 / VPN 活跃）；null = 无可展示信息。 */
    val envStatus = MutableStateFlow<String?>(null)
    @Volatile private var lastEnvLog: String? = null

    @Volatile private var best: ProxyCand? = null
    @Volatile private var lastFullMs = 0L
    @Volatile private var lastMeteredLog = false

    fun start() {
        Store.scope.launch(Dispatchers.IO) { detectAndTest("启动检测") }
        Store.scope.launch(Dispatchers.IO) { optimizeLoop() }
    }

    /** 当前网络是否计费（移动数据等）；判断失败按非计费处理。 */
    fun meteredNetwork(): Boolean = try {
        val cm = Store.appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    } catch (_: Exception) { false }

    // ---------- 路由 ----------

    fun currentRoute(): String? = when (Store.prefs.proxyMode) {
        "direct" -> null
        "manual" -> Store.prefs.manualProxy.trim().ifBlank { null }
        else -> best?.url()
    }

    /** 备用路由：直连失败换最优代理；代理失败换直连。 */
    fun alternateRoute(): String? {
        if (currentRoute() == null) {
            return cands.value.firstOrNull { it.alive && it.type != "direct" }?.url()
        }
        return null
    }

    fun bestLabel(): String = best?.label() ?: "—"
    fun bestLatency(): Double? = best?.latencyMs
    fun bestSpeed(): Double? = best?.speedMbps

    fun setMode(mode: String, manual: String? = null) {
        Store.prefs.proxyMode = mode
        if (manual != null) Store.prefs.manualProxy = manual
        Store.db.addProxyLog("模式", "切换为 $mode" + (manual?.let { "（$it）" } ?: ""))
        publish()
        when (mode) {
            "manual" -> if (manual != null) Store.scope.launch(Dispatchers.IO) { verifyManual(manual) }
            "auto" -> Store.scope.launch(Dispatchers.IO) { detectAndTest("模式切换后检测") }
            else -> lastError.value = null   // 直连：清除错误横幅
        }
    }

    /** 手动代理即时连通性验证：成功清错误 + 报延迟，失败设错误横幅 + 提示。 */
    private suspend fun verifyManual(manual: String) {
        testing.value = true
        publish()
        try {
            val ms = Net.probe(manual, "https://cp.cloudflare.com/generate_204", 6)
            if (ms == null) {
                lastError.value = "手动代理连接失败：$manual"
                Store.db.addProxyLog("错误", "手动代理不可达：$manual")
                toast.value = "手动代理连接失败，请检查地址或端口"
            } else {
                lastError.value = null
                Store.db.addProxyLog("检测", "手动代理已生效，延迟 ${ms.toInt()}ms")
                toast.value = "手动代理已生效，延迟 ${ms.toInt()}ms"
            }
        } finally {
            testing.value = false
            publish()
        }
    }

    // ---------- 检测 ----------

    /** Android 候选来源：手动 > 直连基线 > 系统全局代理 > Root 模块入站 > 端口扫描（本机 + 模拟器宿主 10.0.2.2）> Clash 导入。
     *  手动代理经 ProxyUrl 统一解析（scheme/认证/IPv6/缺省端口）；解析失败写日志并置错误横幅。
     *  Root 模块探测（su）：显式入站（http/socks/mixed）参与优选；透明代理模式仅记状态（直连即代理）。 */
    private suspend fun detect(): List<ProxyCand> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, ProxyCand>()
        val manual = Store.prefs.manualProxy.trim()
        if (manual.isNotBlank()) {
            val p = ProxyUrl.parse(manual)
            if (p == null) {
                Store.db.addProxyLog("错误", "手动代理无法解析：$manual（${ProxyUrl.HINT}）")
                lastError.value = "手动代理格式无效，请检查地址与端口"
            } else {
                found[p.display()] = ProxyCand(
                    0, p.scheme, p.host, p.port, "手动", null, null, 0.0, false, "",
                    auth = if (p.user.isBlank()) "" else "${p.user}:${p.pass}")
            }
        }
        found["direct"] = ProxyCand(0, "direct", "", 0, "直连", null, null, 0.0, false, "")

        // —— 系统全局代理（Settings.Global.HTTP_PROXY，免权限；Wi-Fi 代理 / 部分全局工具会写入）——
        try {
            val raw = Settings.Global.getString(
                Store.appContext.contentResolver, Settings.Global.HTTP_PROXY)?.trim()
            if (!raw.isNullOrBlank()) {
                val first = raw.split(",", " ").firstOrNull { it.contains(':') }
                val p = first?.let { ProxyUrl.parse(if (it.contains("://")) it else "http://$it") }
                if (p != null) found[p.display()] =
                    ProxyCand(0, p.scheme, p.host, p.port, "系统代理", null, null, 0.0, false, "")
            }
        } catch (_: Exception) {
        }

        // —— Root 代理模块探测（无 root 静默跳过；显式入站判型后参与优选）——
        val rp = RootProxy.probe()
        if (rp.suAvailable) {
            val taken = found.values.map { it.host to it.port }.toSet()
            rp.inbounds.forEach { ib ->
                if ((ib.host to ib.port) in taken) return@forEach
                // proto 已知（配置直读）直接用；未知（端口枚举）经 classify 判型，探不通则弃
                val type = ib.proto.ifBlank { classify(ib.host, ib.port) ?: return@forEach }
                found["root://${ib.host}:${ib.port}"] = ProxyCand(
                    0, type, ib.host, ib.port,
                    if (ib.src.isBlank()) "Root" else "Root·${ib.src.take(9)}", null, null, 0.0, false, "")
            }
        }
        publishEnv(rp)

        val hosts = listOf("127.0.0.1", "10.0.2.2")  // 10.0.2.2 = 模拟器中的宿主机
        val ports = listOf(7897, 7890, 7891, 7892, 2080, 10809, 10808, 1080, 8080, 8888, 8118, 3128)
        val known = found.values.map { it.host to it.port }.toSet()
        val combos = hosts.flatMap { h ->
            ports.filter { p -> (h to p) !in known }.map { h to it }
        }
        // 端口扫描并发探测（单线程串行 18 组合最坏 ~14s，并发后 ≈ 单次 500ms）
        val open = coroutineScope {
            combos.map { (h, p) ->
                async { if (tcpOpen(h, p)) h to p else null }
            }.awaitAll().filterNotNull()
        }
        // 开放端口并发识别协议（http 优先，命中即停）；三元组保留 port
        val classified = coroutineScope {
            open.map { (h, p) ->
                async { classify(h, p)?.let { Triple(h, p, it) } }
            }.awaitAll().filterNotNull()
        }
        classified.forEach { (h, p, type) ->
            found["$type://$h:$p"] = ProxyCand(0, type, h, p, "端口扫描", null, null, 0.0, false, "")
        }
        // Clash 导入节点（http/socks5 可直用；持久化在 prefs，重启与复检均保留）
        ClashConfig.parse(Store.prefs.clashImport).filter { it.usable }.forEach { n ->
            val key = "${n.type}://${n.host}:${n.port}"
            if (key !in found) {
                found[key] = ProxyCand(0, n.type, n.host, n.port, "导入", null, null, 0.0, false, "")
            }
        }
        found.values.toList()
    }

    private fun tcpOpen(host: String, port: Int): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), 500)
            true
        }
    } catch (e: Exception) {
        false
    }

    private fun classify(host: String, port: Int): String? {
        for (t in listOf("http", "socks5")) {
            val ok = Net.probe("$t://$host:$port", "https://cp.cloudflare.com/generate_204", 3)
            if (ok != null) return t
        }
        return null
    }

    /** 网络环境状态：Root 模块发现 / 透明代理接管 / VPN 活跃。驱动 UI 状态行；变化时记入事件日志。 */
    private fun publishEnv(rp: RootProxy.Result) {
        val env = mutableListOf<String>()
        if (rp.suAvailable) {
            val mods = rp.modules.take(3).joinToString("·")
            val head = if (mods.isNotBlank()) "Root 模块 $mods" else "Root"
            env.add(when {
                rp.inbounds.isNotEmpty() && rp.transparent.isNotEmpty() ->
                    "$head：本地入站 ${rp.inbounds.size} 个 · 透明代理接管中（直连即代理）"
                rp.inbounds.isNotEmpty() -> "$head：发现本地入站 ${rp.inbounds.size} 个"
                rp.transparent.isNotEmpty() -> "$head：透明代理接管中（直连即代理）"
                else -> "$head：未发现代理模块或可用入站"
            })
        }
        if (vpnActive()) env.add("VPN 活跃：直连流量已由 VPN/TUN 接管")
        val text = env.ifEmpty { null }?.joinToString("；")
        envStatus.value = text
        if (text != null && text != lastEnvLog) {
            Store.db.addProxyLog("环境", text)
            lastEnvLog = text
        }
    }

    /** VPN（TUN）活跃检测：免权限，遍历网络传输类型。 */
    private fun vpnActive(): Boolean = try {
        val cm = Store.appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.allNetworks.any {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    } catch (_: Exception) {
        false
    }

    // ---------- 测速 ----------

    suspend fun detectAndTest(reason: String, testThroughput: Boolean = true) {
        if (testing.value) return
        testing.value = true
        // 计费网络节流日志：移动数据下切换为按延迟优选，每进入一次计费会话只提示一次
        if (testThroughput) lastMeteredLog = false
        else if (!lastMeteredLog) {
            Store.db.addProxyLog("计费网络", "移动数据下跳过吞吐测速，按延迟优选")
            lastMeteredLog = true
        }
        publish()
        try {
            val found = detect()
            Store.db.clearCandidates()
            val list = found.map { c ->
                c.copy(id = Store.db.upsertCandidate(c.type, c.host, c.port, c.source))
            }
            cands.value = list
            testAll(testThroughput)
            best = cands.value.filter { it.alive }.maxByOrNull { it.score }
            Store.db.addProxyLog("检测",
                "$reason: 发现 ${found.size} 个候选，当前最优 ${bestLabel()}（评分 ${"%.2f".format(best?.score ?: 0.0)}）")
            // 全挂提示 + 错误横幅
            val aliveCnt = cands.value.count { it.alive }
            if (aliveCnt == 0) {
                val msg = when (Store.prefs.proxyMode) {
                    "manual" -> "手动代理不可达：${Store.prefs.manualProxy.ifBlank { "未填写" }}"
                    "auto" -> "未检测到可用代理（含直连），请检查网络或手动配置"
                    else -> "直连网络不可用"
                }
                lastError.value = msg
                toast.value = "$msg，将自动重试"
            } else {
                lastError.value = null
            }
        } finally {
            lastFullMs = System.currentTimeMillis()
            testing.value = false
            publish()
        }
    }

    /** 两阶段并发测速：阶段 1 全候选并发连通探测（快速排除死节点），
     *  阶段 2 仅存活者测速（testThroughput=false 时改为按延迟评分，省移动流量）。
     *  总耗时 ≈ 探测超时 + 吞吐预算，与候选数量基本无关。 */
    suspend fun testAll(testThroughput: Boolean = true) = withContext(Dispatchers.IO) {
        val list = cands.value
        coroutineScope {
            list.map { c -> async { probeCandidate(c) } }.awaitAll()
        }
        val alive = list.filter { it.alive }
        if (testThroughput) {
            coroutineScope {
                alive.map { c -> async { speedCandidate(c) } }.awaitAll()
            }
        } else {
            alive.forEach { latencyScoreCandidate(it) }
        }
        best = cands.value.filter { it.alive }.maxByOrNull { it.score }
        publish()
    }

    /** 阶段 1：一次 204 探测定连通与延迟（5s 超时）。 */
    private fun probeCandidate(c: ProxyCand) {
        val ms = Net.probe(c.url(), "https://cp.cloudflare.com/generate_204", 5)
        if (ms == null) {
            Store.db.updateCandidate(c.id, null, null, 0.0, false)
            c.latencyMs = null; c.speedMbps = null; c.score = 0.0; c.alive = false
        } else {
            c.latencyMs = ms; c.alive = true
            Store.db.updateCandidate(c.id, ms, null, 0.0, true)
        }
    }

    /** 阶段 2：吞吐测量 + 评分（预算 3s）。 */
    private fun speedCandidate(c: ProxyCand) {
        val lat = c.latencyMs ?: return
        val mbps = Net.throughput(c.url(), budgetMs = 3000L)
        val score = if (lat > 0) mbps / (1.0 + lat / 300.0) else 0.0
        Store.db.updateCandidate(c.id, lat, mbps, score, true)
        c.speedMbps = mbps; c.score = score
    }

    /** 计费网络降级：不跑吞吐，仅按延迟评分（1000/(1+lat/300)），排序语义与吞吐分同向。 */
    private fun latencyScoreCandidate(c: ProxyCand) {
        val lat = c.latencyMs ?: return
        val score = 1000.0 / (1.0 + lat / 300.0)
        Store.db.updateCandidate(c.id, lat, null, score, true)
        c.speedMbps = null; c.score = score
    }

    // ---------- 持续优选 ----------

    private suspend fun optimizeLoop() {
        while (true) {
            delay(20_000)
            try {
                val allDown = cands.value.none { it.alive }
                // 全挂：跳过间隔门控，每 20s 积极重试，直到恢复
                if (allDown && Store.prefs.proxyMode != "direct") {
                    if (Store.prefs.proxyMode == "manual") {
                        val manual = Store.prefs.manualProxy.trim()
                        if (manual.isNotBlank()) verifyManual(manual)
                    } else {
                        detectAndTest("失败重试", testThroughput = !meteredNetwork())
                    }
                    continue
                }
                val interval = Store.prefs.intervalMin * 60_000L
                if (System.currentTimeMillis() - lastFullMs < interval) continue
                val prev = bestLabel()
                detectAndTest("周期复测", testThroughput = !meteredNetwork())
                val now = bestLabel()
                if (Store.prefs.autoOptimize && prev != now && best != null) {
                    val msg = "代理优选：自动切换 $prev → $now（评分 ${best?.score ?: 0}）"
                    Store.db.addProxyLog("切换", msg.removePrefix("代理优选："))
                    toast.value = msg
                }
            } catch (e: Exception) {
                Store.db.addProxyLog("错误", e.toString())
            }
        }
    }

    fun refreshLogs() {
        logs.value = Store.db.proxyLogs()
    }

    // ---------- Clash 配置导入 ----------

    /** 导入 Clash 配置：解析节点、持久化、立即复测优选。返回 (可用节点数, 跳过的加密协议数)。 */
    fun importClash(text: String): Pair<Int, Int> {
        val nodes = ClashConfig.parse(text)
        val usable = nodes.count { it.usable }
        val skipped = nodes.size - usable
        Store.prefs.clashImport = text
        Store.db.addProxyLog("导入", "Clash 配置：可用 $usable 个（http/socks5）" +
            if (skipped > 0) "，跳过 $skipped 个加密协议节点" else "")
        toast.value = "已导入 $usable 个代理，正在测速优选…"
        Store.scope.launch(Dispatchers.IO) { detectAndTest("导入配置检测") }
        return usable to skipped
    }

    /** 清除导入的配置并重新检测。 */
    fun clearImport() {
        Store.prefs.clashImport = ""
        Store.db.addProxyLog("导入", "已清除导入配置")
        toast.value = "已清除导入的代理配置"
        Store.scope.launch(Dispatchers.IO) { detectAndTest("清除导入后检测") }
    }

    private fun publish() {
        cands.value = Store.db.candidates()
        refreshLogs()
    }
}
