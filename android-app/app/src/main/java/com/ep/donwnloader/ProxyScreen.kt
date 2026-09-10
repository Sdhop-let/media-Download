package com.ep.donwnloader

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyScreen() {
    val ctx = LocalContext.current
    val cands by Store.proxy.cands.collectAsState()
    val testing by Store.proxy.testing.collectAsState()
    val logs by Store.proxy.logs.collectAsState()
    val lastError by Store.proxy.lastError.collectAsState()
    val envStatus by Store.proxy.envStatus.collectAsState()
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(Store.prefs.proxyMode) }
    var manual by remember { mutableStateOf(Store.prefs.manualProxy) }
    var autoOpt by remember { mutableStateOf(Store.prefs.autoOptimize) }
    var interval by remember { mutableStateOf(Store.prefs.intervalMin.toString()) }
    var logsOpen by remember { mutableStateOf(false) }
    var pasteOpen by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }
    var urlOpen by remember { mutableStateOf(false) }
    var urlText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { Store.proxy.refreshLogs() }

    // 从文件选择器读入 Clash 配置并导入
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val text = runCatching {
                    ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                }.getOrNull().orEmpty()
                if (text.isNotBlank()) Store.proxy.importClash(text)
                else Store.proxy.toast.value = "文件为空或无法读取"
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 6.dp, 16.dp, 116.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("代理", textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold, fontSize = 20.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp))
        }
        lastError?.let { err ->
            item {
                Box(
                    Modifier.fillMaxWidth()
                        .background(ErrColor.copy(alpha = 0.13f), RoundedCornerShape(18.dp))
                        .border(1.dp, ErrColor.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⚠ ", color = ErrColor, fontSize = 14.sp)
                        Text(err, style = MaterialTheme.typography.bodySmall,
                            color = if (LocalMonet.current.isDark) Color(0xFFF0A6A6) else Color(0xFFB03A34),
                            modifier = Modifier.weight(1f))
                        GlassButton("重试", onClick = {
                            scope.launch(Dispatchers.IO) {
                                if (Store.prefs.proxyMode == "manual") {
                                    val m = Store.prefs.manualProxy.trim()
                                    if (m.isNotBlank()) Store.proxy.detectAndTest("手动重试")
                                } else {
                                    Store.proxy.detectAndTest("错误重试")
                                }
                            }
                        }, kind = ButtonKind.Ghost, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
        item {
            GlassCard {
                Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("当前使用", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(Store.proxy.bestLabel(),
                        fontSize = 19.sp, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center, maxLines = 2)
                    val lat = Store.proxy.bestLatency()
                    val spd = Store.proxy.bestSpeed()
                    Spacer(Modifier.height(3.dp))
                    Text(
                        when (mode) {
                            "direct" -> "直连模式"
                            "manual" -> "手动指定" +
                                (lat?.let { " · 延迟 ${it.toInt()}ms" } ?: "")
                            else -> "自动优选" +
                                (if (lat != null) " · 延迟 ${lat.toInt()}ms" else "") +
                                (spd?.let { " · ${"%.1f".format(it)} Mbps" } ?: "")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    if (testing) {
                        Spacer(Modifier.height(8.dp))
                        IosProgress(pct = null, modifier = Modifier.fillMaxWidth())
                    }
                    // 网络环境状态行：Root 模块发现 / 透明代理接管 / VPN 活跃（无可展示信息时不占位）
                    envStatus?.let { env ->
                        Spacer(Modifier.height(6.dp))
                        Text("◈ $env", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(10.dp))
                    IosSegmented(
                        options = listOf("自动优选", "手动", "直连"),
                        selected = when (mode) { "manual" -> 1; "direct" -> 2; else -> 0 },
                        onSelect = { i ->
                            val v = when (i) { 1 -> "manual"; 2 -> "direct"; else -> "auto" }
                            mode = v
                            Store.proxy.setMode(v, if (v == "manual") manual else null)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (mode == "manual") {
                        Spacer(Modifier.height(8.dp))
                        GlassTextField(
                            value = manual,
                            onValueChange = { manual = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = "http://127.0.0.1:7897 或 socks5://10.0.2.2:7890",
                            singleLine = true,
                        )
                        // 即时格式校验：解析成功显示规范化地址，失败给出示例
                        val parsed = ProxyUrl.parse(manual)
                        if (manual.isNotBlank()) {
                            Text(
                                if (parsed != null) "✓ 格式有效 → ${parsed.display()}" +
                                    (if (parsed.user.isNotBlank()) "（含认证，SOCKS5 认证暂不支持）" else "")
                                else "✗ 无法解析 · ${ProxyUrl.HINT}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (parsed != null) OkColor else ErrColor,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        GlassButton("应用", onClick = { Store.proxy.setMode("manual", manual) },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassButton("重新检测", onClick = {
                            scope.launch(Dispatchers.IO) { Store.proxy.detectAndTest("手动检测") }
                        }, enabled = !testing, modifier = Modifier.weight(1f))
                        GlassButton("立即测速", onClick = {
                            scope.launch(Dispatchers.IO) { Store.proxy.testAll() }
                        }, enabled = !testing, kind = ButtonKind.Ghost,
                            modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        item {
            GlassCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("自动优选", fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium)
                            Text("周期复测候选，显著更优时自动切换",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IosSwitch(checked = autoOpt, onChange = {
                            autoOpt = it
                            Store.prefs.autoOptimize = it
                            Store.prefs.intervalMin = interval.toIntOrNull() ?: 10
                        })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("复测间隔", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        GlassTextField(
                            value = interval,
                            onValueChange = { interval = it.filter { ch -> ch.isDigit() } },
                            modifier = Modifier.width(64.dp),
                            singleLine = true,
                        )
                        Text("分钟（含导入节点，改后开关一次生效）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        item {
            GlassCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column {
                        Text("导入 Clash 配置", fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium)
                        Text("解析配置中的代理节点：http/socks5 直接入列参与自动优选；ss/vmess/trojan 等加密协议需本地内核，暂不支持",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GlassButton("选择文件", onClick = { filePicker.launch("*/*") },
                            modifier = Modifier.weight(1f))
                        GlassButton("粘贴配置", onClick = { pasteOpen = true },
                            kind = ButtonKind.Ghost, modifier = Modifier.weight(1f))
                        GlassButton("URL 导入", onClick = { urlOpen = true },
                            kind = ButtonKind.Ghost, modifier = Modifier.weight(1f))
                    }
                    if (Store.prefs.clashImport.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("已导入配置（每次检测自动重建候选）",
                                style = MaterialTheme.typography.labelSmall,
                                color = OkColor, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                scope.launch(Dispatchers.IO) { Store.proxy.clearImport() }
                            }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("清除") }
                        }
                    }
                }
            }
        }

        item { Text("候选代理（评分 = 速度 ÷（1 + 延迟/300））", fontWeight = FontWeight.Bold,
            fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp)) }

        item {
            GlassCard {
                Column(Modifier.padding(vertical = 4.dp)) {
                    if (cands.isEmpty()) {
                        Text(if (testing) "正在检测与测速…" else "尚未检测，点击「重新检测」",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(14.dp))
                    }
                    cands.forEach { c ->
                        val isBest = Store.proxy.bestLabel() == c.label() && c.alive
                        // Root 模块来源："Root·模块名" 拆两段——来源列只显 Root，模块名进副行防折行
                        val rootMod = if (c.source.startsWith("Root·")) c.source.removePrefix("Root·") else null
                        Row(Modifier.fillMaxWidth().padding(10.dp, 7.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(if (rootMod != null) "Root" else c.source,
                                style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(74.dp),
                                color = if (rootMod != null) OkColor else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if (c.type == "direct") "—" else c.type.uppercase(),
                                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                                color = if (c.type == "socks5") WarnColor else OkColor,
                                modifier = Modifier.width(52.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.label(), style = MaterialTheme.typography.bodySmall)
                                Text(
                                    (rootMod?.let { "$it · " } ?: "") +
                                        (c.latencyMs?.let { "${it.toInt()}ms" } ?: "延迟 —") +
                                        (c.speedMbps?.let { " · ${"%.1f".format(it)} Mbps" } ?: " · 速度 —") +
                                        " · 评分 " + (if (c.alive) "%.2f".format(c.score) else "—"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(when {
                                isBest -> "使用中"
                                c.alive -> "可用"
                                c.lastTested.isNotBlank() -> "不可用"
                                else -> "待测"
                            },
                                color = when {
                                    isBest -> OkColor
                                    else -> ios().faint
                                },
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        item {
            GlassCard {
                Column {
                    Row(Modifier.fillMaxWidth()
                        .clickable { logsOpen = !logsOpen }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("事件日志", fontWeight = FontWeight.Bold, fontSize = 14.sp,
                            modifier = Modifier.weight(1f))
                        Text("${logs.size} 条", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text(if (logsOpen) "▾" else "▸", fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    AnimatedVisibility(
                        visible = logsOpen,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (logs.isEmpty()) Text("暂无日志", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            logs.take(30).forEach { l ->
                                Row {
                                    Text(fmtIso(l.ts), style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.width(96.dp))
                                    Text("${l.event}：${l.detail}", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 粘贴 Clash 配置弹层
    if (pasteOpen) {        val c = ios()
        ModalBottomSheet(
            onDismissRequest = { pasteOpen = false },
            containerColor = if (c.isDark) Color(0xF20E1830) else Color(0xF7F4F8FE),
            scrimColor = Color(0x66000000),
        ) {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 30.dp)) {
                Text("粘贴 Clash 配置 / 代理链接", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("支持完整配置文件内容，或每行一个 socks5://host:port、http://host:port",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
                GlassTextField(
                    value = pasteText,
                    onValueChange = { pasteText = it },
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    placeholder = "proxies:\n  - {name: xx, type: socks5, server: 1.2.3.4, port: 1080}\n或 socks5://127.0.0.1:7890",
                    minLines = 5,
                )
                Spacer(Modifier.height(12.dp))
                GlassButton("导入", onClick = {
                    val t = pasteText
                    pasteOpen = false
                    pasteText = ""
                    if (t.isNotBlank()) {
                        scope.launch(Dispatchers.IO) { Store.proxy.importClash(t) }
                    }
                }, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    // URL 导入弹层：拉取订阅/配置内容再解析
    if (urlOpen) {
        val c = ios()
        ModalBottomSheet(
            onDismissRequest = { urlOpen = false },
            containerColor = if (c.isDark) Color(0xF20E1830) else Color(0xF7F4F8FE),
            scrimColor = Color(0x66000000),
        ) {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 30.dp)) {
                Text("URL 导入 Clash 配置", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("输入配置文件或订阅链接，将拉取内容解析（base64 订阅自动解码）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
                GlassTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "https://example.com/clash.yaml",
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                GlassButton("拉取并导入", onClick = {
                    val u = urlText.trim()
                    urlOpen = false
                    urlText = ""
                    if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(u)) {
                        Store.proxy.toast.value = "请输入 http(s) 链接"
                        return@GlassButton
                    }
                    scope.launch(Dispatchers.IO) {
                        // 当前路由失败自动回退直连再试一次（下载通道同款容错）
                        val routes = listOfNotNull(Store.proxy.currentRoute(), null).distinct()
                        var text: String? = null
                        var err: Exception? = null
                        for (r in routes) {
                            try { text = Http(r).getText(u); break }
                            catch (e: Exception) { err = e; Store.db.addProxyLog("错误", "URL 导入失败（${r ?: "直连"}）：${e.message}") }
                        }
                        val t = text
                        if (t == null) {
                            Store.proxy.toast.value = "URL 导入失败：${err?.message ?: "网络不可达"}（当前路由与直连均已尝试）"
                        } else if (t.isBlank()) {
                            Store.proxy.toast.value = "下载配置为空"
                        } else {
                            Store.proxy.importClash(t)
                        }
                    }
                }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
