package com.ep.donwnloader

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okio.Buffer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Locale
import java.util.concurrent.TimeUnit

/** 全局单例容器。 */
object Store {
    lateinit var appContext: Context
    lateinit var db: Db
    lateinit var prefs: Prefs
    lateinit var proxy: ProxyManager
    lateinit var tasks: TaskManager
    lateinit var imageLoader: coil.ImageLoader
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 系统分享进来的待下载链接（下载页自动填充；用状态以便前台新分享即时生效） */
    var pendingShare: String? by androidx.compose.runtime.mutableStateOf<String?>(null)

    /** 媒体库数据版本号：媒体入库/恢复/登记的所有路径自增，素材库观察它即时重查刷新。 */
    var mediaVersion: Int by androidx.compose.runtime.mutableStateOf(0)

    /** 下载页输入框草稿（跨标签页保留） */
    val draftInput = androidx.compose.runtime.mutableStateOf("")

    /** 主题模式："system" / "light" / "dark"（运行时状态，供 UI 观察） */
    var themeMode by androidx.compose.runtime.mutableStateOf("system")

    /** Material You 动态取色开关（运行时状态，供 UI 即时观察） */
    var dynamicColor by androidx.compose.runtime.mutableStateOf(false)

    /** 取色作用域："accent" / "full"（运行时状态） */
    var dynamicScope by androidx.compose.runtime.mutableStateOf("accent")

    /** 自定义主题种子色 "#RRGGBB"；"" = 默认（运行时状态） */
    var accentSeed by androidx.compose.runtime.mutableStateOf("")

    /** 壁纸取色版本号：壁纸 colors 变化（静态/live 壁纸）时自增，驱动莫奈取色实时重算 */
    val monetEpoch = androidx.compose.runtime.mutableIntStateOf(0)

    /** 底部当前 tab（0 下载 / 1 素材库 / 2 代理 / 3 设置）；空状态跨页引导跳转用 */
    var selectedTab by androidx.compose.runtime.mutableIntStateOf(0)

    /** Cookie 抓取版本号：CookieLoginActivity 捕获成功后自增，
     *  设置页以它为 remember key 重读 prefs（跨 Activity 刷新输入框与状态徽章）。 */
    val cookieEpoch = androidx.compose.runtime.mutableIntStateOf(0)

    /** 种子色 → Compose Color；空 / 非法返回 null（用默认深蓝）。 */
    fun accentSeedColor(): androidx.compose.ui.graphics.Color? {
        if (accentSeed.isBlank()) return null
        return try {
            androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(accentSeed))
        } catch (e: Exception) { null }
    }

    private var inited = false

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        appContext = ctx.applicationContext
        db = Db(appContext)
        prefs = Prefs(appContext)
        themeMode = prefs.themeMode
        dynamicColor = prefs.dynamicColor
        dynamicScope = prefs.dynamicScope
        accentSeed = prefs.accentSeed
        proxy = ProxyManager()
        tasks = TaskManager()
        tasks.start()
        proxy.start()
        // mirror 退役迁移（root 直读改造：镜像路径条目改指原路径）+ 物化缓存清理
        scope.launch(Dispatchers.IO) {
            runCatching { tasks.migrateMirrorPaths() }
            runCatching { ContentAccess.sweep() }
        }
        OptimizeWorker.schedule(appContext)
        // 远程图片（头像等）也走当前优选路由；磁盘缓存让重启后头像/封面免网络秒出
        imageLoader = coil.ImageLoader.Builder(appContext)
            .callFactory { request -> Net.client(proxy.currentRoute()).newCall(request) }
            .crossfade(true)
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(java.io.File(appContext.cacheDir, "image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024)
                    .build()
            }
            .build()
        // T8.8：后台预热内嵌 Python + yt-dlp（首次降级提取不再卡启动开销）
        scope.launch(Dispatchers.IO) { runCatching { YtDlp.warmup() } }
    }

    fun openUrl(url: String) {
        if (url.isBlank()) return
        runCatching {
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                .let { appContext.startActivity(it) }
        }
    }
}

/** 轻量设置持久化。 */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 加密存储：敏感凭据（登录态/Cookie/Clash 配置）写入 Keystore 主密钥保护的
     *  EncryptedSharedPreferences。该库已标记 @Deprecated，但功能正确，加抑制。 */
    @Suppress("DEPRECATION")
    private val secure: SharedPreferences = try {
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            ctx,
            "settings_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (t: Throwable) {
        // 密钥库/加密不可用时降级为独立明文文件，至少与旧 sp 隔离
        Log.w("Prefs", "EncryptedSharedPreferences 初始化失败，降级明文存储", t)
        ctx.getSharedPreferences("settings_secure_fb", Context.MODE_PRIVATE)
    }

    var proxyMode: String
        get() = sp.getString("proxy_mode", "auto") ?: "auto"
        set(v) = sp.edit().putString("proxy_mode", v).apply()

    var manualProxy: String
        get() = sp.getString("manual_proxy", "") ?: ""
        set(v) = sp.edit().putString("manual_proxy", v).apply()

    var autoOptimize: Boolean
        get() = sp.getBoolean("auto_optimize", true)
        set(v) = sp.edit().putBoolean("auto_optimize", v).apply()

    /** 后台全量测速周期（分钟）。审计 P0：默认配置后台流量过大，由 10 分钟放宽到 30 分钟。 */
    var intervalMin: Int
        get() = sp.getInt("interval_min", 30)
        set(v) = sp.edit().putInt("interval_min", v.coerceIn(1, 120)).apply()

    var maxBskyPosts: Int
        get() = sp.getInt("max_bsky_posts", 60)
        set(v) = sp.edit().putInt("max_bsky_posts", v.coerceIn(10, 200)).apply()

    /** Instagram 会话 Cookie（至少含 sessionid=...），供移动 API 提取使用。
     *  内存缓存 + 加密存储双层；敏感凭据不落旧明文 sp。 */
    private var _igCookie: String = secure.getString("ig_cookie", "") ?: ""
    var igCookie: String
        get() = _igCookie
        set(v) { _igCookie = v; secure.edit().putString("ig_cookie", v).apply() }

    /** 系统分享后自动开始下载（关闭则仅捕获进收件箱） */
    var shareAutoDownload: Boolean
        get() = sp.getBoolean("share_auto_download", true)
        set(v) = sp.edit().putBoolean("share_auto_download", v).apply()

    /** 底部胶囊通知：后台下载完成后在屏幕底部悬浮提示（需悬浮窗权限，无权限时退回系统通知） */
    var capsuleNotify: Boolean
        get() = sp.getBoolean("capsule_notify", true)
        set(v) = sp.edit().putBoolean("capsule_notify", v).apply()

    /** 共享同步目录：上次「同步其他目录」成功的外部目录，下次进页预填（增量共享引用） */
    var syncDir: String
        get() = sp.getString("sync_dir", "") ?: ""
        set(v) = sp.edit().putString("sync_dir", v).apply()

    /** Clash 配置导入原文（http/socks5 节点参与候选，随每次检测重建）。
     *  内存缓存 + 加密存储双层；敏感凭据不落旧明文 sp。 */
    private var _clashImport: String = secure.getString("clash_import", "") ?: ""
    var clashImport: String
        get() = _clashImport
        set(v) { _clashImport = v; secure.edit().putString("clash_import", v).apply() }

    /** X / Twitter 登录 Cookie 双值：受限内容与主页解析用。
     *  内存缓存 + 加密存储双层；敏感凭据不落旧明文 sp。 */
    private var _twAuthToken: String = secure.getString("tw_auth_token", "") ?: ""
    var twAuthToken: String
        get() = _twAuthToken
        set(v) { _twAuthToken = v; secure.edit().putString("tw_auth_token", v).apply() }

    private var _twCt0: String = secure.getString("tw_ct0", "") ?: ""
    var twCt0: String
        get() = _twCt0
        set(v) { _twCt0 = v; secure.edit().putString("tw_ct0", v).apply() }

    /** 一次性迁移：旧明文 sp 中的敏感凭据迁到加密 secure。
     *  若旧 sp 非空且 secure 为空则写入 secure 并更新内存缓存；
     *  无论是否迁移，都从旧 sp 删除这四个键，确保 secure 为唯一权威存储。 */
    init {
        val keys = listOf("tw_auth_token", "tw_ct0", "ig_cookie", "clash_import")
        for (k in keys) {
            val plain = sp.getString(k, "") ?: ""
            if (plain.isNotBlank() && secure.getString(k, "").isNullOrBlank()) {
                secure.edit().putString(k, plain).apply()
                when (k) {
                    "tw_auth_token" -> _twAuthToken = plain
                    "tw_ct0" -> _twCt0 = plain
                    "ig_cookie" -> _igCookie = plain
                    "clash_import" -> _clashImport = plain
                }
            }
            // 无论迁移与否，旧明文凭据一律清除
            sp.edit().remove(k).apply()
        }
    }

    /** Material You 动态取色（Android 12+） */
    var dynamicColor: Boolean
        get() = sp.getBoolean("dynamic_color", false)
        set(v) = sp.edit().putBoolean("dynamic_color", v).apply()

    /** 主题模式："system" / "light" / "dark" */
    var themeMode: String
        get() = sp.getString("theme_mode", "system") ?: "system"
        set(v) = sp.edit().putString("theme_mode", v).apply()

    /** 取色作用域："accent" 仅主色 / "full" 全局跟随（背景渐变与光斑同步派生） */
    var dynamicScope: String
        get() = sp.getString("dynamic_scope", "accent") ?: "accent"
        set(v) = sp.edit().putString("dynamic_scope", v).apply()

    /** 自定义主题种子色 "#RRGGBB"；"" = 默认深蓝。莫奈取色开启时不生效。 */
    var accentSeed: String
        get() = sp.getString("accent_seed", "") ?: ""
        set(v) = sp.edit().putString("accent_seed", v).apply()

    /** X Cookie 拼接为请求头（两个值都有才算配置好） */
    fun twCookieHeader(): String =
        if (twAuthToken.isNotBlank() && twCt0.isNotBlank())
            "auth_token=${twAuthToken.trim()}; ct0=${twCt0.trim()}" else ""
}

/** 简单 HTTP 辅助：绑定某条网络路由。 */
class Http(val route: String?) {
    val client: OkHttpClient = Net.client(route)

    /** 流式读取响应体并限制上限（默认调用方给 32MB）；超限抛 ExtractError，
     *  防止无界 body.string() 吃爆内存。 */
    private fun readLimited(body: okhttp3.ResponseBody, maxBytes: Long): String {
        val src = body.source()
        val out = okio.Buffer()
        var total = 0L
        while (true) {
            val n = src.read(out, 8192L)
            if (n == -1L) break
            total += n
            if (total > maxBytes) throw ExtractError("响应体超过上限")
        }
        return out.readUtf8()
    }

    fun getJson(url: String, headers: Map<String, String> = emptyMap()): JSONObject {
        val b = okhttp3.Request.Builder().url(url).header("User-Agent", UA).get()
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { resp ->
            val body = resp.body ?: throw ExtractError("HTTP ${resp.code}: 空响应")
            val text = readLimited(body, 32L * 1024 * 1024)
            if (!resp.isSuccessful) throw ExtractError("HTTP ${resp.code}")
            return JSONObject(text)
        }
    }

    fun getText(url: String, headers: Map<String, String> = emptyMap()): String {
        val b = okhttp3.Request.Builder().url(url).header("User-Agent", UA).get()
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { resp ->
            val body = resp.body ?: return ""
            val text = readLimited(body, 32L * 1024 * 1024)
            if (!resp.isSuccessful) throw ExtractError("HTTP ${resp.code}")
            return text
        }
    }

    companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
    }
}

/** OkHttp 客户端工厂：按路由构建并缓存复用（连接池/TLS 会话跨请求共享——
 *  Coil callFactory 每张图都来取 client，不缓存则每张图都新建 OkHttpClient 全握手，头像加载巨慢）。
 *  路由统一经 ProxyUrl 解析，支持 http/https/socks5(h)、user:pass@ 认证（HTTP 代理）、IPv6 方括号、缺省端口。 */
object Net {
    private val clients = java.util.concurrent.ConcurrentHashMap<String, OkHttpClient>()

    fun client(route: String?): OkHttpClient =
        clients.getOrPut(route ?: "direct") {
            val b = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
            val p = ProxyUrl.parse(route)
            if (p != null) {
                b.proxy(Proxy(
                    if (p.isSocks) Proxy.Type.SOCKS else Proxy.Type.HTTP,
                    InetSocketAddress(p.host, p.port)))
                // HTTP 代理认证（407 质询时附加 Proxy-Authorization）；SOCKS5 认证 Java Proxy 层不支持
                if (p.user.isNotBlank() && !p.isSocks) {
                    val cred = okhttp3.Credentials.basic(p.user, p.pass)
                    b.proxyAuthenticator { _, resp ->
                        resp.request.newBuilder()
                            .header("Proxy-Authorization", cred).build()
                    }
                }
            }
            b.build()
        }

    /** 经路由请求一个 204 端点，返回毫秒延迟；失败返回 null。 */
    fun probe(route: String?, url: String, timeoutSec: Long): Double? {
        val client = client(route).newBuilder()
            .connectTimeout(timeoutSec, TimeUnit.SECONDS)
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
            .build()
        val req = okhttp3.Request.Builder().url(url)
            .header("User-Agent", Http.UA).get().build()
        val t0 = System.nanoTime()
        return try {
            client.newCall(req).execute().use { resp ->
                resp.body?.close()
                if (resp.code >= 500) null
                else (System.nanoTime() - t0) / 1e6
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 经路由测吞吐（限时截断），返回 Mbps。
     *  主测速源 speed.cloudflare.com 在部分网络下被间歇性阻断（204 小请求能过、大文件 0 字节），
     *  颗粒无收时自动换备用源重试一次，避免评分被单次阻断清零。 */
    fun throughput(route: String?, budgetMs: Long = 5000L): Double {
        val primary = downloadThru(route, "https://speed.cloudflare.com/__down?bytes=10485760", budgetMs)
        if (primary > 0) return primary
        return downloadThru(route, "http://cachefly.cachefly.net/10mb.test", budgetMs)
    }

    private fun downloadThru(route: String?, url: String, budgetMs: Long): Double {
        val client = client(route)
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", Http.UA).get().build()
        val t0 = System.currentTimeMillis()
        var got = 0L
        return try {
            client.newCall(req).execute().use { resp ->
                val src = resp.body?.byteStream() ?: return 0.0
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    got += n
                    if (System.currentTimeMillis() - t0 > budgetMs) break
                }
                src.close()
                val dt = (System.currentTimeMillis() - t0) / 1000.0
                if (dt > 0.2) got / dt / 1e6 else 0.0
            }
        } catch (e: Exception) {
            0.0
        }
    }
}

// ---------------- 展示辅助 ----------------

fun platName(p: String): String = when (p) {
    "twitter" -> "X"
    "instagram" -> "Instagram"
    "bluesky" -> "Bluesky"
    else -> "其他"
}

fun fmtBytes(n: Long): String = when {
    n >= (1L shl 30) -> String.format(Locale.US, "%.2f GB", n.toDouble() / (1L shl 30))
    n >= (1L shl 20) -> String.format(Locale.US, "%.1f MB", n.toDouble() / (1L shl 20))
    n >= (1L shl 10) -> "${n / (1L shl 10)} KB"
    else -> "$n B"
}

fun fmtSpeed(bps: Double): String = fmtBytes(bps.toLong()) + "/s"

fun fmtIso(iso: String): String {
    if (iso.length < 16) return iso.ifBlank { "—" }
    return iso.substring(0, 10) + " " + iso.substring(11, 16)
}
