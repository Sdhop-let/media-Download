package com.ep.donwnloader

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Cookie 容器登录页：内嵌 WebView 打开平台登录流，登录成功后从系统 CookieStore
 * 抓取完整登录态（含 HttpOnly）自动填入 prefs，随后清空容器并关闭。
 *
 * 实现说明：传统 View 体系（非 Compose AndroidView）——真机排查发现 Compose
 * 管线下 x.com 布局 percentage 全链塌 0（html 100%/vh 计算为 0px 而
 * window.innerHeight 正常，CDP 实测），IG 布局不受影响；换 View 体系验证渲染。
 *
 * 限制（已知且有意为之）：
 *  - WebView 走系统网络，App 内置代理路由不作用于此页，需 Clash / VPN 全局接管。
 *  - Google / Apple 第三方登录会被 Google 拦截，仅账号密码登录。
 */
class CookieLoginActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PLATFORM = "platform"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var webView: WebView? = null
    private var statusText: TextView? = null
    private var statusDot: View? = null
    private var topBar: LinearLayout? = null
    private var bottomBar: FrameLayout? = null
    private var done = false
    private var dark = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 注意顺序：必须在 super.onCreate 之后（此前的调用曾致 WebView 合成器
        // 输入 hit-test 与视口状态损坏——JS 可聚焦但一切真实触摸无效）
        enableEdgeToEdge()
        val platform = intent?.getStringExtra(EXTRA_PLATFORM) ?: "twitter"
        dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        lateinit var wvRef: WebView
        val root = buildUi(platform) { wvRef = it }
        setContentView(root)
        applyWindowInsets(root)

        setupCaptureWebView(wvRef, platform, startUrl(platform))
        webView = wvRef
        startPolling(platform)
    }

    private fun startUrl(platform: String) = when (platform) {
        "twitter" -> "https://x.com/i/flow/login"
        else -> "https://www.instagram.com/accounts/login/"
    }

    // ---------------- UI 构建（传统 View） ----------------

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun primary() = if (dark) Color.parseColor("#85B7EB") else Color.parseColor("#185FA5")
    private fun okColor() = Color.parseColor("#2E9E5B")
    private fun bg() = if (dark) Color.parseColor("#121316") else Color.WHITE
    private fun textMain() = if (dark) Color.parseColor("#E6E8EC") else Color.parseColor("#17181C")
    private fun textFaint() = if (dark) Color.parseColor("#8A8D94") else Color.parseColor("#9AA0A8")

    private fun pill(text: String, color: Int, radiusDp: Float = 100f): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(color)
            textSize = 11f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(withAlpha(color, 0.16f))
                cornerRadius = dp(100).toFloat() * radiusDp / 100f
            }
            setPadding(dp(10), dp(3), dp(10), dp(3))
        }
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    private fun buildUi(platform: String, onWebview: (WebView) -> Unit): FrameLayout {
        val title = TextView(this).apply {
            text = if (platform == "twitter") "X 登录并抓取 Cookie" else "Instagram 登录并抓取 Cookie"
            setTextColor(textMain())
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val subtitle = TextView(this).apply {
            text = "登录成功后自动填入，无需手动复制"
            setTextColor(textFaint())
            textSize = 10f
        }
        statusDot = View(this).apply {
            setBackgroundColor(primary())
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply { gravity = Gravity.CENTER_VERTICAL }
        }
        statusText = TextView(this).apply {
            text = if (platform == "twitter") "等待登录 x.com…" else "等待登录 Instagram…"
        }
        val bottomPill = pill(statusText!!.text.toString(), primary())

        val close = TextView(this).apply {
            text = "✕"
            setTextColor(textFaint())
            textSize = 14f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(withAlpha(if (dark) Color.WHITE else Color.BLACK, 0.08f))
                cornerRadius = dp(15).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply { gravity = Gravity.CENTER_VERTICAL }
            setOnClickListener { finish() }
        }

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(bg())
            setPadding(dp(12), dp(6), dp(16), dp(6))
            addView(close)
            addView(LinearLayout(this@CookieLoginActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(10)
                }
                addView(title)
                addView(subtitle)
            })
            addView(statusDot)
        }

        val webview = WebView(this)
        onWebview(webview)

        bottomBar = FrameLayout(this).apply {
            setBackgroundColor(bg())
            setPadding(0, dp(10), 0, dp(10))
            addView(bottomPill, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER))
        }

        // WebView 全屏从 (0,0) 起、顶栏/底栏悬浮覆盖——WebView 的位置在任何 insets
        // 变化下都不移动，Chromium hit-test 坐标系与 View 层保持一致（实测关键）。
        return FrameLayout(this).apply {
            setBackgroundColor(bg())
            addView(webview, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(topBar, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP))
            addView(bottomBar, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM))
        }
    }

    private fun applyWindowInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            topBar?.setPadding(dp(12), bars.top + dp(6), dp(16), dp(6))
            bottomBar?.setPadding(0, dp(10), 0, bars.bottom + dp(10))
            insets
        }
    }

    private fun setStatus(text: String, color: Int) {
        statusText?.text = text
        statusDot?.setBackgroundColor(color)
        val pill = (statusText?.parent as? FrameLayout)?.getChildAt(0) as? TextView ?: return
        pill.text = text
        pill.setTextColor(color)
        (pill.background as? GradientDrawable)?.setColor(withAlpha(color, 0.16f))
    }

    // ---------------- 抓取轮询 ----------------

    private fun startPolling(platform: String) {
        scope.launch {
            val cm = CookieManager.getInstance()
            while (isActive && !done) {
                delay(900)
                val raw = when (platform) {
                    "twitter" -> cm.getCookie("https://x.com")
                        ?: cm.getCookie("https://twitter.com") ?: ""
                    else -> cm.getCookie("https://www.instagram.com")
                        ?: cm.getCookie("https://instagram.com") ?: ""
                }
                if (captureCookie(platform, raw)) {
                    done = true
                    setStatus("Cookie 已自动填入设置", okColor())
                    Store.cookieEpoch.intValue++
                    runCatching { cm.removeAllCookies(null); cm.flush() }
                    delay(1400)
                    finish()
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        webView?.destroySafely()
        webView = null
        super.onDestroy()
    }
}

/** X 用桌面 UA：真机对照（CDP）显示此 WebView 下 percentage 视口单位解析异常，
 *  桌面版渲染路径曾是唯一完整渲染样本；cookie 与 UA 无关，抓取不受影响。 */
private const val DESKTOP_UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")

@SuppressLint("SetJavaScriptEnabled")
private fun setupCaptureWebView(w: WebView, platform: String, startUrl: String) {
    val dbg = (w.context.applicationInfo.flags and
        android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    WebView.setWebContentsDebuggingEnabled(dbg)
    w.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        userAgentString = DESKTOP_UA
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        javaScriptCanOpenWindowsAutomatically = true
    }
    val cm = CookieManager.getInstance()
    cm.setAcceptCookie(true)
    cm.setAcceptThirdPartyCookies(w, true)
    w.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val u = request.url
            if (u.scheme == "http" || u.scheme == "https") return false
            return try {
                view.context.startActivity(Intent(Intent.ACTION_VIEW, u)); true
            } catch (_: Exception) { true }
        }

        override fun onReceivedError(view: WebView, req: WebResourceRequest,
                                     err: WebResourceError) {
            if (req.isForMainFrame)
                android.util.Log.e("CookieWebView", "mainFrameErr ${err.description} ${req.url}")
        }

        override fun onPageFinished(view: WebView, url: String) {
            // 布局塌缩兜底（Compose 时代遗留保险，正常环境条件不命中零影响）
            repairLayout(view)
            view.postDelayed({ repairLayout(view) }, 1200)
            // ⚠️ 不要 hideSoftInputFromWindow 收键盘：autofocus 的 input 已持有焦点，
            // 强收后用户再点同一输入框时 WebView 不重新请求 IME → 键盘永不弹（实测踩坑）
        }
    }
    w.webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
            android.util.Log.w("CookieWebView", "console[${m.messageLevel()}] ${m.message()} @${m.sourceId()}:${m.lineNumber()}")
            return true
        }
    }
    w.loadUrl(startUrl)
}

private fun WebView.destroySafely() {
    runCatching {
        stopLoading()
        (parent as? ViewGroup)?.removeView(this)
        destroy()
    }
}

private fun hideKeyboard(v: View) {
    val imm = v.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
        as? android.view.inputmethod.InputMethodManager ?: return
    imm.hideSoftInputFromWindow(v.windowToken, 0)
    v.clearFocus()
}

/** 布局塌缩兜底：html/main/layers 实际高度异常（<50px）时用 innerHeight 像素直写。 */
private fun repairLayout(v: WebView) {
    v.evaluateJavascript(
        "(function(){try{" +
        "var d=document.documentElement,b=document.body;" +
        "if(d.getBoundingClientRect().height<50){var h=window.innerHeight+'px';" +
        "d.style.height=h;if(b)b.style.height=h;}" +
        "var m=document.querySelector('main');if(m&&m.getBoundingClientRect().height<50)m.style.height='100%';" +
        "var l=document.querySelector('#layers');if(l&&l.getBoundingClientRect().height<50)l.style.height='100%';" +
        "}catch(e){}})()", null)
}

/** 从 cookie 串提取登录凭据写 prefs；返回是否抓到完整登录态。 */
private fun captureCookie(platform: String, raw: String): Boolean {
    if (raw.isBlank()) return false
    val pairs = raw.split(";").mapNotNull {
        val i = it.indexOf('=')
        if (i <= 0) null else it.substring(0, i).trim() to it.substring(i + 1).trim()
    }
    val map = LinkedHashMap<String, String>()
    for ((k, v) in pairs) map[k] = v   // 重名以后值覆盖（登录刷新场景）
    return when (platform) {
        "twitter" -> {
            val tok = map["auth_token"]
            val ct0 = map["ct0"]
            if (!tok.isNullOrBlank() && !ct0.isNullOrBlank()) {
                Store.prefs.twAuthToken = tok
                Store.prefs.twCt0 = ct0
                true
            } else false
        }
        else -> {
            val sid = map["sessionid"]
            if (!sid.isNullOrBlank()) {
                // 全量串回填：与设置页手动粘贴格式一致（sessionid=…; ds_user_id=…），
                // YtDlp.writeCookies 只取 sessionid，原生移动 API 可用全串。
                Store.prefs.igCookie = map.entries.joinToString("; ") { "${it.key}=${it.value}" }
                true
            } else false
        }
    }
}
