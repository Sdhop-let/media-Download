# 媒体下载器 · 接手文档（Handoff）

> 最后更新：2026-09-06 · Android v1.1（com.ep.donwnloader）
> 配套文档：[TASKS.md](TASKS.md) 是权威任务流水账（M0–M9 全部批次与实测记录），本文档讲"怎么接"。

---

## 1. 项目概览

本项目是一套社交媒体媒体下载器，包含两端：

| 端 | 位置 | 技术栈 | 状态 |
|---|---|---|---|
| **Android App（主力）** | `android-app/` | Kotlin 2.0.21 + Jetpack Compose (BOM 2024.12.01) + AGP 8.7.3 / Gradle 8.9 | 功能完整，持续迭代 |
| PC 桌面版（前身） | 根目录 `backend/` `run_server.py` `start.bat` | Python 3.14 + FastAPI(8765) + SQLite(WAL) + gallery-dl + Vue3（vendored 无构建） | 可用，功能已被 Android 端覆盖大半 |

包名 `com.ep.donwnloader`（用户原始拼写，**不要改**）。APK 产物：`android-app/app/build/outputs/apk/debug/app-debug.apk`。

核心能力：X / Instagram / Bluesky 的图片视频下载，支持**系统分享直达自动下载**、收件箱批量队列、素材库（时间/作者视图）、回收站、应用内播放、代理自动优选与 Clash 配置导入。

---

## 2. Android 架构

### 2.1 总体结构

单 Activity + Compose，**无导航库**。页面切换 = 底部 tab 索引 + 页内布尔子页（配 BackHandler）。

```
MainActivity（SEND/SEND_MULTIPLE 分享入口、剪贴板捕获）
└─ DownloaderApp()                       // 全局 toast 收集、主题解析、tab 切换
   ├─ DownloadScreen   下载页（收件箱 + 任务子页）
   ├─ LibraryScreen    素材库（按时间网格 / 按作者手风琴）
   ├─ ProxyScreen      代理页（检测/测速/Clash 导入/日志）
   └─ SettingsScreen   设置页（Cookie/优选折叠块、存储、同步子页）
```

### 2.2 全局单例 `Store`（Store.kt）

一切全局状态与服务的入口，`MainActivity.onCreate` 里 `Store.init(this)` 幂等初始化：

| 成员 | 作用 |
|---|---|
| `db: Db` | SQLite（v3），全部持久化数据 |
| `prefs: Prefs` | SharedPreferences 设置 |
| `proxy: ProxyManager` | 代理检测/测速/优选循环/Clash 导入 |
| `tasks: TaskManager` | 收件箱捕获、下载队列、任务引擎 |
| `imageLoader: coil.ImageLoader` | **代理感知**的图片加载器（AsyncImage 必须传它） |
| `scope` | 全局协程域（SupervisorJob+Default），后台任务统一跑这里 |
| `pendingShare` / `draftInput` / `themeMode` / `selectedTab` | Compose 全局可观察状态（跨页面共享就放 Store；selectedTab 是底部 tab，空状态跨页引导跳转用） |

### 2.3 关键文件与职责

| 文件 | 职责 |
|---|---|
| `IosUI.kt` | **设计系统全部组件**（见 §3），改样式只动这里 |
| `Extractors.kt` | 平台提取：URL 识别 `detect()` + fxtwitter / Bluesky 公共 API / IG 移动 API / X syndication，统一输出 `Plan(posts[])` |
| `ShareIn.kt` | 分享文本 → 提取链接（混排文本/裸链接/t.co 短链跟随跳转） |
| `TaskManager.kt` | `capture()` 收件箱捕获 → `downloadInbox()` 建任务 → 队列（Channel，2 worker）→ `downloadFile`（路由回退+重试）→ 入库 |
| `YtDlp.kt` | **T8.8 内嵌 yt-dlp 桥**：原生提取失败（plan.error）时降级 Chaquopy Python 引擎；写 Netscape cookies.txt（X auth_token+ct0 / IG sessionid）；fallbackPlan 只处理 twitter/instagram（Bluesky 公共 API 已稳）；Store.init 后台 warmup 预热 |
| `python/engine.py` | yt-dlp 提取引擎（Chaquopy 打进 app.imy）：extract(url, cookieFile, proxy, playlistEnd) → JSON；只挑 http 渐进式直链（拒 HLS/DASH，现有下载器无解复用内核）；主页批量 playlistend 限条 |
| `ProxyManager.kt` | 候选检测（手动>直连>端口扫描>Clash 导入）、测速（延迟×2+吞吐，评分=速度÷(1+延迟/300)）、优选循环（20s tick，全挂 20s 积极重试，间隔门控全量复测+自动切换） |
| `ClashConfig.kt` | Clash YAML 最小解析（proxies flow/block + 裸 `socks5://` 链接 + base64 订阅解码）；**仅 http/socks5 可用**，ss/vmess/trojan 标记跳过（OkHttp 无加密协议内核） |
| `Net` (Store.kt) | OkHttp 工厂：`client(route)` 按路由挂 http/socks5 代理；`probe`/`throughput` 测速 |
| `Db.kt` | SQLiteOpenHelper v3：authors/posts/media(软删 deleted)/tasks/proxy_candidates/proxy_log/inbox(preview_url) |
| `Background.kt` | `DownloadService`（dataSync 前台服务保活）+ `ensureDownloadService` + OptimizeWorker（WorkManager 周期优选） |
| `MonetResolver.kt` | 莫奈取色：免权限壁纸位图多点采样（Celebi 全局量化 + Score 多候选 × 4×4 分块主导色）→ 三 seed 独立 TonalPalette 派生；持久化缓存重启首帧即取色（见 §7.8） |

### 2.4 核心数据流

```
分享(SEND) / 剪贴板( onResume ) / 手动粘贴
        │ ShareIn.extractLinks / Extractors.detect
        ▼
TaskManager.capture ──► inbox 表（captured）──► fetchMeta 异步拉作者/文案/预览图
        │ downloadInbox(ids)
        ▼
tasks 表 + Channel 队列（2 worker，cancelFlags 可取消）
        │ Extractors.plan(url)  ← 主路由失败自动换备用路由
        │   └─ plan.error 且平台为 twitter/instagram → YtDlp.fallbackPlan（内嵌 yt-dlp + cookies.txt + 代理路由）
        ▼
downloadPost：每文件独立容错（每路由试 2 次，记住成功路由 routeMemory）
        │ 文件 → getExternalFilesDir/downloads/{platform}/{handle}/时间_帖子ID_序号.ext
        ▼
media 表（UNIQUE(post_row_id, media_index) 天然去重，跳过计入 skipped）
        │ 完成后回写 inbox 状态 + toast
        ▼
LibraryScreen ← listMediaTime / listAuthors（JOIN authors/posts/media）
```

分享入口细节：`MainActivity.handleSendIntent` → `TaskManager.handleSharePayload` → 有可识别链接就 `capture`，`prefs.shareAutoDownload`（默认开）时直接 `downloadInbox`；无链接则退回 `Store.pendingShare` 填充下载页输入框。

### 2.5 数据库迁移规范

版本号在 `Db` 构造参数（当前 **3**）。新增列：`onUpgrade` 里 `ALTER TABLE ... ADD COLUMN`，**同时**把该列追加到 `CREATE TABLE` 末尾——保证新装与升级两条路径 `SELECT *` 列序一致（`inboxRow` 按下标取列）。参考 `preview_url`（v3）的做法。

---

## 3. 设计理念与设计系统

### 3.1 理念

1. **iOS 26 Liquid Glass × 莫奈深蓝**：所有界面是"玻璃卡片浮在极光渐变上"，不用 Material 默认样式。浅/深双主题（`MonetLight`/`MonetDark`），可选莫奈动态取色（Android 12+ 壁纸取色，`dynamicColor`）。
2. **页面标题规范（已定型，勿破坏）**：每个页面**只有一个居中大标题**（20sp Bold），眉题（MEDIA DOWNLOADER）和副标题一律删除。
3. **悬浮玻璃底栏**：`GlassTabBar`（56dp 胶囊）悬浮在内容上，内容延伸到屏幕底、从胶囊后穿过；每屏 `contentPadding` 底部预留 **100–124dp** 保证能滚出底栏。
4. **统一反馈**：Toast 走 `Store.tasks.toast` / `Store.proxy.toast`（StateFlow，全局收集）；进行中状态用 `IosProgress` 流光条。
5. **语义色**：`OkColor/WarnColor/ErrColor/BsColor/IgColor` + `PlatBadge`/`StatusPill`，平台与状态全app同色。

### 3.2 组件清单（都在 IosUI.kt，UI 只允许用这些）

| 组件 | 用途 | 要点 |
|---|---|---|
| `GlassBackground` | 页面背景 | 极光光斑只在顶部（底部光斑已删，给底栏让位） |
| `GlassCard(topStripe=)` | 一切卡片 | 24dp 圆角玻璃+描边+投影；topStripe 做状态色顶条 |
| `GlassButton(text, onClick, enabled, kind)` | 按钮 | Primary=深蓝渐变 / Ghost=玻璃描边 |
| `GlassTextField` | 输入框 | 18dp 圆角无边框玻璃容器 |
| `IosSegmented` | 分段切换 | 等宽选中渐变 |
| `IosSwitch` | 开关 | 主题化绿色 |
| `IosChip` | 横滑小胶囊 | 素材库旧筛选遗留 |
| `IosProgress(pct)` | 进度条 | pct=null 为不确定态流光扫描 |
| `StatusPill` / `PlatBadge` | 状态/平台徽章 | |
| `EmptyState(icon, title, hint, actionText, onAction, compact)` | 空状态面板 | 轻玻璃容器+渐变圆标锚点+主/副文案+可选操作胶囊；compact 用于页内小节；所有空列表统一用它，禁止裸放文字 |
| `SectionTitle` / `LargeTitle` | 标题 | LargeTitle 已弃用（只留旧引用） |

**页内私有组件模式**（各 Screen 内自建，命名保持一致）：
- `RowScope.FilterChip(label, selected, onClick)`：**一行五等分/四等分**等宽胶囊（下载页、素材库在用），11sp。
- `CollapseRow(title, subtitle, open, onToggle)`：折叠块头 + `▸/▾`。
- `StatCell`：统计块"数字+标签"居中单元格（素材库）。
- `AuthorTab`（已删）/`InboxCard`/`TaskCard`/`TaskRow`/`MediaCell` 等业务卡片。

---

## 4. 新增界面 / 样式如何统一设计

### 4.1 新页面标准骨架（照抄即可）

```kotlin
@Composable
fun XxxScreen() {
    var subPage by remember { mutableStateOf(false) }        // 子页开关（可选）
    BackHandler(enabled = subPage) { subPage = false }       // 子页必须配返回
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 6.dp, 16.dp, 116.dp),  // 底部≥116 给悬浮底栏
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {   // 唯一标题：居中，无眉题无副标题
            Text("页面名", textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold, fontSize = 20.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp))
        }
        // …玻璃卡片 / 筛选行 / 内容
    }
}
```

### 4.2 常用统一模式（都有现成实现，先抄再改）

| 需求 | 模式出处 | 要点 |
|---|---|---|
| 对称头部（左动作/中标题/右动作） | DownloadScreen 头部 | 两侧 `GlassButton(..., modifier = Modifier.width(88.dp))`，标题 `weight(1f)` 居中 |
| 等宽筛选胶囊一排 | DownloadScreen `FilterChip` | `Row(spacedBy 6) { FilterChip(...)×N }`，组件内 `weight(1f)` |
| 折叠块（带过渡动画） | SettingsScreen X Cookie | `CollapseRow` 头 + `AnimatedVisibility(open, enter = expandVertically()+fadeIn(), exit = shrinkVertically()+fadeOut())` |
| 满长胶囊 tab（点入子页） | DownloadScreen「任务与历史」 | `clip(RoundedCornerShape(100.dp)) + c.fill 背景 + c.rim 描边`，右侧计数+▸/▾；子页配 BackHandler |
| 底部弹层菜单 | DownloadScreen 下载菜单 / ProxyScreen 导入 | `ModalBottomSheet(containerColor = if (c.isDark) 0xF20E1830 else 0xF7F4F8FE, scrimColor = 0x66000000)` |
| 批量选择 | DownloadScreen 收件箱 / LibraryScreen 长按删除 | `selectMode` + `remember { mutableStateListOf<Long>() }` + 操作栏「已选 N｜全选｜取消｜主操作」+ BackHandler |
| 两等分按钮 | ProxyScreen 重新检测/立即测速 | `Row { GlassButton(a, Modifier.weight(1f)); GlassButton(b, Modifier.weight(1f)) }` |
| 全屏对话框（图片/视频） | LibraryScreen `ImageViewerDialog`/`PlayerDialog` | **必须** `DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)`，否则非全屏露底（踩过坑） |
| 长按进批量 | LibraryScreen MediaCell | `combinedClickable(onClick, onLongClick)`，`@OptIn(ExperimentalFoundationApi::class)` |

### 4.3 硬性规则

1. **颜色一律走主题**：`ios()`（=LocalMonet）或 MaterialTheme，禁止硬编码 except 弹层容器色等既有常量；暗色分支用 `c.isDark`。
2. **异步图片必须传 `imageLoader = Store.imageLoader`**（否则不走代理路由加载失败）。
3. **网络/DB 一律 `Dispatchers.IO`**（`scope.launch(Dispatchers.IO)` 或 `withContext`），结果写回 StateFlow 驱动重组；全局后台用 `Store.scope`。
4. **用户反馈统一 Toast**：`Store.tasks.toast.value = "…"`，不要自造 Snackbar。
5. **跨页面共享状态放 Store**（Compose mutableStateOf），页内私有才用 remember；注意 tab 切换会销毁 remember（草稿类状态要提升到 Store，如 `draftInput`）。
6. **新增持久化数据**：Db 版本号 +1，onUpgrade 写 ALTER，CREATE 同步补列（见 §2.5）。
7. 新组件先找 IosUI.kt 有没有等价物；没有就加到 IosUI.kt 让全app复用，不要在页面里私造一份通用样式。

---

## 5. 新增 UI 统一布局数值（直接照抄的尺寸）

| 元素 | 数值 |
|---|---|
| 页面左右边距 / 列表 item 间距 | 16dp / 10dp |
| 卡片内边距 | 14dp（紧凑 10–12dp） |
| 大标题 | 20sp Bold 居中，top 2dp bottom 4–6dp |
| 对称头部动作胶囊 | 宽 88dp |
| 底栏胶囊 | 高 56dp、圆角 28dp、内边距 5dp、水平外边距 12dp、bottom 8dp |
| 筛选胶囊 | 竖直 padding 7dp、字号 11sp、行 spacedBy 6dp |
| 满长胶囊 tab | 水平 18dp / 竖直 12dp、字 13.5sp Bold |
| 素材网格 | 按时间 3 列 / 作者内 4 列，间距 6dp，格子圆角 14dp |
| 列表底部 contentPadding | 100–124dp |
| 预览图（收件箱卡） | 头像 42dp 圆 + 预览 64×48dp 圆角 10dp，×N 角标右下 |

---

## 6. 功能完善度清单

✅=完整可用（含实测） ⚠️=可用但实验性/依赖条件 ⬜=未做

| 模块 | 功能 | 状态 |
|---|---|---|
| X/Twitter | 单推下载（fxtwitter 免登录） | ✅ |
| | 主页批量（syndication 实验性） | ⚠️ 免登录不稳定，可配 Cookie |
| | 受限内容 Cookie（auth_token+ct0 手动粘贴） | ✅ |
| Instagram | 帖子/主页（移动 API + sessionid） | ⚠️ 实验性，Cookie 需手动导出、会过期 |
| Bluesky | 帖子/主页/视频（公共 API 免登录，视频走 getBlob） | ✅ |
| 分享直达 | SEND/SEND_MULTIPLE → 捕获 → 自动下载（可关） | ✅ 真机实测（X 主页 13 文件、Bluesky 67 文件） |
| 收件箱 | 捕获/元数据/预览图/状态漏斗/单条与批量下载/**自选批量**/去重/失败重试 | ✅ |
| 下载页 | 粘贴区折叠+剪贴板自动捕获、下载菜单（全部/批量）、任务与历史胶囊子页 | ✅ |
| 素材库 | 按时间网格、按作者手风琴（下载倒序）、统计玻璃块、平台/类型筛选、**长按批量删除**（软删）、详情弹层、全屏图片查看、应用内视频播放（Media3） | ✅ |
| 回收站 | 软删/恢复/彻底删除 | ✅ |
| 本地导入 | 扫描下载目录重登记、同步其他目录（路径验证+判重登记不复制） | ✅ |
| 代理 | 自动检测+端口扫描+手动+直连、测速评分优选、周期复测自动切换、事件日志折叠 | ✅ |
| | Clash 配置导入（文件/粘贴/URL，http/socks5；base64 订阅解码） | ✅ 加密协议节点跳过 |
| 设置 | Cookie 折叠块、分享自动下载开关、主题/莫奈取色、支持范围/关于 | ✅ |
| 账号登入 | ~~浏览器登录回显账号~~ | 已移除（浏览器无法回传 Cookie，不可行） |
| 内嵌 yt-dlp | T8.8 已实施（Chaquopy 16.0.0，APK 55.8MB） | ✅ 降级通道已接，真机待验 |

---

## 7. 做过哪些优化（为什么现在是这个样子）

1. **下载可靠性**：提取与下载双路由回退（主路由失败换备用）；每文件独立容错（单文件失败不毁任务）；每路由 2 次重试 + 成功路由记忆；逐任务中断标记（应用重启不悬挂）。
2. **去重三层**：收件箱按 URL、media 按 (post, index) 唯一约束、本地导入/重扫按 source_url 判重。
3. **代理优选**：候选评分=速度÷(1+延迟/300)；全挂时 20 秒激进重试，正常按间隔全量复测并自动切换（Toast+日志告知）；Clash 导入节点持久化在 prefs，每次检测自动重建候选（`clearCandidates` 不会丢）。
4. **体验**：分享零点击直达下载（可开关）；剪贴板前台自动捕获；收件箱卡片预览图（DB v3 preview_url）；视频抽帧缩略图；图片查看器/播放器全屏 Dialog（修过非全屏 bug）；长按批量删除走回收站（可恢复，不误伤）。
5. **性能/体积**：无导航库、无 DI、无冗余依赖；Coil 单例走代理路由；缩略图与封面本地缓存；PC 端 Vue vendored 无构建。
6. **稳定性教训（已固化进代码）**：Compose tab 切换销毁 remember → 草稿提升到 Store；`detectAndTest` 会清空候选表 → 导入节点放 prefs 每次重建；Dialog 全屏必须关 platformDefaultWidth。
7. **Cookie 容器登录（2026-09-08）**：CookieLoginActivity 内嵌 WebView 登录 X/IG，CookieManager 轮询抓取（HttpOnly 可见），自动转填 prefs 喂 yt-dlp，替代手动 DevTools 抠 cookie。**大坑**：Compose `AndroidView` 承载 WebView 时 x.com 的 percentage/vh 高度全链解析为 0（html{height:100%} computed 0px 而 window.innerHeight 正常，CDP 实测），IG 因布局路径不同幸免——换传统 View 体系（setContentView）同一 WebView 完整渲染，**WebView 容器页一律用 View 体系，勿用 Compose AndroidView**。键盘双坑：登录页 autofocus 自动弹键盘 + adjustResize 压扁页面 → `adjustPan|stateHidden` + onPageFinished 后 600/1800ms 双连收。UA：X/IG 都用桌面 Chrome UA。排查工具：`adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>` + CDP Runtime.evaluate 直查 DOM 布局。
8. **莫奈取色重构（2026-09-09，多点位采样 + 重启即取色 + 模拟器实测修正）**：背景——ColorOS 16 引擎壁纸 `getWallpaperColors` 不可靠；`dynamicColorScheme(ctx)` 在 Android 15+ 依赖系统已生成的 wallpaper color cache，重启后未就绪时返回默认值（"重启后取色失效"）；`getWallpaperFile` 读原图需 MANAGE_EXTERNAL_STORAGE（多数用户不授权，旧深路径实际不可用）。**实测修正（模拟器 Android 15）**：`WallpaperManager.getDrawable()` 抛 `SecurityException: READ_EXTERNAL_STORAGE denied`——**壁纸位图对应用受系统保护，非免权限，勿再用**；免权限取色唯一可靠来源是 `getWallpaperColors()` 三主色（Android 12+ 原生，本身即系统多点采样结果）。改法：①**双档深路径**——有 MANAGE_EXTERNAL_STORAGE：`getWallpaperFile` 原图 → 自研多点采样（全局 `QuantizerCelebi`(128) + `Score.score(…,10)` × 4×4 分块块主导色 → HCT 过滤 chroma≥6/tone 8..94 + hue 15° 分槽去重）；无权限：`getWallpaperColors` 三色直接作三 seed；②**三 seed 120° 均分窗口**——primary=Score 首位（系统取色直觉）；secondary/tertiary 以 primary 为基准 +120°/+240° 目标 hue 的 ±30° 窗口内选 chroma 最高壁纸真实色，窗口空才数学派生（保和谐又鲜活）；③**三 seed 独立派生**——各自 `TonalPalette`（S/T 彩度 ×0.85），比旧 SchemeTonalSpot 单 seed hue 偏移更鲜活；④**持久化缓存 + 门控**——SharedPreferences(`monet_seeds`) 存 `wp_id + 三 seed`，`quickResolve` 主线程首帧读缓存零解码零量化（重启后 ~2.8s 即取色，实测 cold start `source=cache`）；`deepResolve` 每次重采样但**hue 差异均 ≤15° 跳过写盘**（省电）。**验证**：模拟器 Android 15 全链路通（colors3 免权限 → cache 冷启动 → skip write 门控）；JVM 用多彩壁纸（蓝/紫红/橙/绿）跑真实算法：三 seed 命中壁纸真实区域色、hue 间隔 136°/134°/90°（≈120° 均分）、chroma 50-68 鲜活；UI 截图确认蓝紫灰调（该壁纸色系）贯穿状态栏/卡片/胶囊/分段控件。ColorOS 引擎壁纸实机行为仍需真机复核。

---

## 8. 已知限制与待办

- ss/vmess/trojan 代理需完整加密内核，当前只支持 http/socks5（Clash 导入时自动跳过并提示）。
- Instagram 依赖登录 Cookie（设置页「浏览器登录 Instagram 并自动抓取」容器一键获取，或手动粘贴 sessionid），过期需重抓；接口为非官方移动 API（原生提取失败自动降级内嵌 yt-dlp 重试）。
- X 主页批量原生走 syndication 免登录接口，限流时失败；已配 Cookie 时降级 yt-dlp 走登录态抓取（未配 Cookie 不降级，必失败）。X Cookie 可用设置页「浏览器登录 X 并自动抓取」容器一键获取。
- Cookie 容器登录限制：WebView 走系统网络（不走 App 内代理路由，需 Clash/VPN 全局）；Google/Apple 第三方登录被 Google 拦，仅账号密码；X 登录流在个别 WebView 版本上可能有渲染异常（修复逻辑已内置：onPageFinished 延迟 repairLayout + 收键盘）。
- yt-dlp 通道仅取 http 渐进式直链（HLS/DASH 流跳过，与 fxtwitter 同口径）；brotli 为可选依赖未打包（服务器回退 gzip，影响小）；升级 yt-dlp = 重跑手动 pip --target 后把新源码拷进 src/main/python/（见 §9.1）。
- 账号自动登录已评估为不可行（浏览器隔离），如再提需换思路（WebView 注入或账号密码接口）。

---

## 9. 构建与调试（Windows 环境坑全录）

```bash
# 构建（代理按需；依赖走阿里云镜像，wrapper 腾讯镜像）
# ⚠️ build 目录已迁到 D:\AndroidDev\build-epdownloader（layout.buildDirectory），APK 不再在项目内
cd android-app
GRADLE_OPTS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897" \
  ./gradlew.bat assembleDebug
# 产物 D:\AndroidDev\build-epdownloader\app\outputs\apk\debug\app-debug.apk（v1.1 含 Chaquopy 约 55.8MB）

# 安装（覆盖保留数据）
adb install -r D:\AndroidDev\build-epdownloader\app\outputs\apk\debug\app-debug.apk
```

- 原生 adb 在 `D:\AndroidDev\sdk\platform-tools\adb.exe`（MCP 进程的 adb 解析损坏）。
- 中文项目路径已用 `android.overridePathCheck=true` 豁免。
- **Git Bash 调 adb**：含 `/` 的参数（如 `-n pkg/.Activity`）会被 MSYS 路径转换弄坏 → 整条命令用引号串传给 `adb shell "am start …"`。
- ColorOS 真机息屏/锁定时 **SEND 意图会被挂起**，解锁才投递，期间 uiautomator dump 是旧界面——勿误判为 bug；`am start --activity-single-top` 可强制即时投递验证 onNewIntent。
- uiautomator dump 输出路径用 `//sdcard/xx.xml`（双斜杠防转义）；读回 `adb shell "cat //sdcard/xx.xml"`。
- 模拟器 AVD：MemoTest（API 35，易掉线，重启即可）；装 APK 报 `allocateBytes` NPE = /data 满 → `adb shell pm trim-caches 999999999999`。
- 模拟器代理指向宿主机 `10.0.2.2`（ProxyManager 已自动探测）；真机走自身网络/VPN。
- fxtwitter 单推必须 `/{handle}/status/{id}` 路径且带浏览器 UA；部分老推文 404 属外部数据问题。Bluesky 视图 embed 的 `$type` 带 `#view` 后缀，视频经 `getBlob` 免登录直下。
- 提交规范：**不要自动 git commit，留给用户**。

### 9.1 T8.8 Chaquopy 构建专项坑（2026-09-07 血泪实录，改 Python 依赖前必读）

1. **绝对不要给 chaquopy 配 `pip { install(...) }`**：构建期 pip --target 解压数千小文件后，联想安全软件反勒索启发式会把 buildPython venv 的 python.exe **挂死不退出**（jstack 证据：TaskBuilder.execBuildPython 在 ProcessImpl.waitFor 永久等待；换 C 盘/D 盘都一样；手动跑官方 D:\Python312\python.exe 的 pip 正常退出）。**新 Python 依赖 = 手动 pip install --target 到临时目录，再把纯 Python 源码拷进 src/main/python/**（二进制依赖需 Chaquopy 有对应 Android wheel，另议）。
2. **Chaquopy Maven 组件（target/runtime）在阿里云镜像缺失，回源 mavenCentral 被 GFW 卡死**（表现为 Gradle 无限等待无 TCP）。已手动镜像 target-3.12.12-0 + runtime 16.0.0 全套到 `D:\AndroidDev\maven-local`，settings.gradle.kts 两处仓库列表**置顶**本地镜像。升级 Chaquopy 版本需重新跑 `android-app/mirror_chaquopy.py`。
3. buildPython 用 **D:\Python312\python.exe**（本机 3.13/3.14 不受支持）；Kotlin DSL 写法 `buildPython = listOf("D:/Python312/python.exe")`（是 List，赋 String 编译错）。
4. **构建目录迁到 D:\AndroidDev\build-epdownloader**（app/build.gradle.kts `layout.buildDirectory`）：APK 产物路径随之改变，CI/脚本别再找项目内 app/build。
5. yt-dlp 打包形态：`assets/chaquopy/app.imy`（zip 容器，含 engine.pyc + yt_dlp 1083 pyc + socks.pyc）；stdlib 在 `stdlib-common.imy`/`stdlib-<abi>.imy`；cacert.pem 由 Chaquopy 提供。验证打包用 python zipfile 读 APK。
6. pip 的 `--target` 模式**没有** "already satisfied" 检查，预装目录挡不住重装——别在这条路上浪费时间。

---

## 10. 接手检查清单

1. 读 `docs/TASKS.md` M6–M9 了解功能演进与实测结论。
2. 跑一遍 app：下载页（分享/粘贴/批量/任务子页）→ 素材库（长按删除/作者展开/播放）→ 代理页（导入一份 Clash 配置）→ 设置（折叠块/同步其他目录）。
3. 改 UI 前先读 §3/§4/§5，所有新界面照 §4.1 骨架 + §4.2 模式表。
4. 动数据先看 §2.5 迁移规范；动网络先看 ProxyManager 的路由概念（route: null=直连 / http(s):// / socks5://）。
5. 有疑问先翻 TASKS.md 对应条目——大部分"为什么这么做"都有实测注记。
