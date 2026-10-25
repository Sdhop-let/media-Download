# 任务拆分（Task Breakdown）

> 标记：✅ 完成 / ⬜ 待办。每项均为可独立验证的小任务。

## M6 Android 端（com.ep.donwnloader，Kotlin + Jetpack Compose）
- ✅ T6.1 环境就绪（SDK/AVD/WHPX；下载 Gradle 8.9 → 生成 wrapper；阿里云镜像源）
- ✅ T6.2 项目骨架（AGP 8.7.3/Kotlin 2.0.21/Compose BOM；中文路径豁免；INTERNET 权限；SEND 分享接入）
- ✅ T6.3 SQLite 数据层（authors/posts/media/tasks/proxy 表 + UNIQUE 去重 + 时间/作者查询）
- ✅ T6.4 URL 识别（X 推文/主页、Instagram、Bluesky 帖子/主页，两段式 bsky 主页路径）
- ✅ T6.5 提取器（fxtwitter 单推免登录；Bluesky resolveHandle/getPostThread/getAuthorFeed posts_with_media、#view 后缀、fullsize 直链、DID 缓存）
- ✅ T6.6 代理模块（手动/直连/自动；端口扫描含模拟器宿主 10.0.2.2；204 延迟 + Cloudflare 吞吐 + 评分；周期优选自动切换；事件日志）
- ✅ T6.7 下载引擎（并发 2 队列；主/备路由自动回退 + 成功路由记忆 + 每路由重试 2 次；逐文件容错；MediaMetadataRetriever 视频抽帧）
- ✅ T6.8 Compose UI 四页（下载/素材库/代理/设置；暗色主题；全局 toast；输入草稿跨页保留）
- ✅ T6.9 Coil 图片加载挂优选路由（头像等远程图经代理加载）
- ✅ T6.10 构建与真机化验证（APK 装模拟器；分享链接 e2e：识别→提取→65 文件下载→去重入库→素材库时间/作者视图→详情弹窗回跳入口；代理页真实检出宿主代理并优选）
- ⬜ T6.11 后续：发布签名 APK、无限滚动分页、任务实时速度显示

## M7 Android 补齐（主力端强化，2026-09-05）
- ✅ T7.1 下载前台服务（dataSync 类型）：退后台/划走保活，通知栏聚合进度，空闲自动退场
- ✅ T7.2 通知权限运行时申请（POST_NOTIFICATIONS）
- ✅ T7.3 WorkManager 周期优选（≥15 分钟，App 完全退出后仍复测代理并记录最优）
- ✅ T7.4 Bluesky 视频免登录：record.embed.video 的 blob cid → getBlob 直下原始 mp4 + 视频抽帧缩略图
- ✅ T7.5 Instagram 提取器（移动 API v1：shortcode→media_id、单帖/多图/视频、主页 feed 分页；需设置页填 sessionid Cookie；未实测——沙盒无 IG cookies）
- ✅ T7.6 X 主页批量（syndication 免登录 + fxtwitter 逐条，最近 12 条；实验性——接口限流敏感）
- ✅ T7.7 分享链接在 App 已在前台时也能即时进入（onNewIntent + 可观察 pendingShare）
- ✅ T7.8 实测：bsky 视频帖下载成功（mp4+抽帧）；后台下载通知栏进度可见；65 文件回归正常

## M8 对标 Edqiu 强化（2026-09-05 第二批）
- ✅ T8.1 逆向分析 Edqiu（com.ed.twitterdownload）：内嵌 Python+yt-dlp zipapp+FFmpeg+QuickJS、收件箱隐喻、auth_token/ct0 双框、回收站/WebDAV/网盘备份/莫奈取色/自更新
- ✅ T8.2 数据库 v2：inbox 表（捕获归档）+ media.deleted 软删（旧数据迁移无损，65 文件任务保留）
- ✅ T8.3 收件箱流：粘贴/分享/剪贴板 → 捕获归档（作者头像/handle/文案自动拉取）→ 状态漏斗 chips → 单条/批量下载 → 任务完成后状态回写；失败保留完整错误可重试（已实测全链路含代理抖动失败→重试成功→8 文件去重跳过）
- ✅ T8.4 X Cookie 双字段（auth_token + ct0 两个输入框，无需导出文件）
- ✅ T8.5 回收站（恢复/彻底删除，已实测删除→回收站→可恢复）
- ✅ T8.6 Media3 应用内播放（详情弹窗 ▶ 播放）+ 扫描下载目录重登记
- ✅ T8.7 莫奈动态取色开关（Android 12+ 跟随壁纸）
- ✅ T8.8 内嵌 Python + yt-dlp（Chaquopy 16.0.0，2026-09-07 实施完成，APK 55.8MB）：
    - 依据：Edqiu 同架构实测可行（libpython.zip.so + res/raw/ytdlp 官方 zipapp + pycryptodomex/mutagen site-packages）
    - ① buildPython：静默安装 Python 3.12.8 到 D:\Python312（Git Bash 直接跑安装器会被安全软件吞掉，需 PowerShell Start-Process）✅
    - ② Chaquopy 坐标：开源版 16.0.0 在 Maven Central（`com.chaquo.python`），AGP 7.0–8.8 / minSdk≥24 兼容本项目 ✅
    - ③ Gradle 接入：root plugins + app 插件 + `chaquopy { defaultConfig { version="3.12"; buildPython=listOf("D:/Python312/python.exe") } }`（Kotlin DSL 中 buildPython 是 List 不是 String）；abiFilters arm64-v8a+x86_64 ✅
    - ④ app/src/main/python/engine.py：YoutubeDL extract_info(skip_download) 递归展开 playlist → 挑 http 渐进式直链（拒 HLS/DASH）→ 规范化 JSON（posts[]/media[]）✅
    - ⑤ Kotlin 桥：YtDlp.kt（ensure/warmup/fallbackPlan + Netscape cookies.txt 写 cacheDir：X 域 auth_token+ct0 双写 x.com/twitter.com、IG sessionid）；TaskManager.run() 在原生提取 plan.error 时降级；Store.init 后台预热 ✅
    - ⑥ 包体：abiFilters 双 ABI，APK 12→55.8MB ✅
    - ⚠️ 与原方案的重大偏差（安全软件血泪）：**构建期 pip 装包必死**——pip --target 解压数千文件后，联想安全软件反勒索启发式把 buildPython venv 的 python.exe 挂死（TaskBuilder.execBuildPython waitFor 永久等待；手动跑官方 D:\Python312\python.exe pip 正常退出）。绕行：**不用 pip {} 配置，yt-dlp 2026.8.19 + PySocks 1.7.1 纯 Python 源码直接放 src/main/python/**（Chaquopy 编译为 app.imy 5.4MB，含 yt_dlp 1083 文件 + engine.pyc + socks.pyc）。Chaquopy Maven 组件（target/runtime 16.0.0）也被 GFW 卡死，已镜像到 D:\AndroidDev\maven-local 并在 settings.gradle.kts 置顶。构建目录迁 D:\AndroidDev\build-epdownloader（layout.buildDirectory）
    - 待真机验证：X 受限内容单推（需 Cookie）、X 主页（需 Cookie）、IG 帖子（需 sessionid）；yt-dlp 无 brotli（可选依赖，未打包，影响小）

## M9 分享直达保存（2026-09-06）
- ✅ T9.1 系统分享直达：X / Instagram / Bluesky 点「分享」选本应用 → 提取链接 → 自动捕获收件箱 → 按设置「分享自动下载」（默认开）立即创建下载任务，全程零额外点击；SEND + SEND_MULTIPLE（text/plain），EXTRA_TEXT 空时回退 EXTRA_SUBJECT
- ✅ T9.2 ShareIn 链接提取：混排文本抓全部 http(s) 链接、无协议裸链接、t.co 短链跟随一次跳转再识别；无可识别链接时退回旧行为（填入下载页输入框）
- ✅ T9.3 设置页「分享自动下载」开关（关闭则仅捕获入收件箱）；Toast 反馈（开始下载 N 条 / 已捕获 / 已在收件箱）
- ✅ T9.4 真机实测（3B161X007XP00000，ColorOS）：分享目标注册 ✓；冷启动 SEND（onCreate）✓；前台 onNewIntent ✓（注：系统在息屏/锁定时会挂起 SEND 意图，解锁后照常投递）；X 单推（老推文 fxtwitter 404 正确回显）✓；X 主页分享 13 文件 ✓；Bluesky 主页分享 67 文件 ✓；重复分享去重 ✓；单条失败不拖垮批次 ✓
- ✅ T9.5 下载页 UI 重构（2026-09-06 模拟器实测）：①顶部仅居中「收件箱」，左「捕获」右「下载 N」胶囊对称 ②粘贴区默认隐藏，点捕获展开动画+自动捕获剪贴板链接，输入内容再点即捕获并收起 ③五个状态筛选胶囊一行等宽 ④收件箱卡片左列头像下新增媒体预览图（DB v3 inbox.preview_url，fetchMeta 取首个媒体缩图，×N 角标）⑤底部只悬浮胶囊 tab、去背景带，内容延伸到屏底可滚出（各屏底部预留 100-124dp）
- ✅ T9.6 素材库按作者视图重构（2026-09-06 模拟器实测，按用户二次反馈定型）：作者竖向列表按先后顺序排列（头像+名字+项数+展开箭头行）；点击展开/收起该作者作品（手风琴，同时只展开一个，AnimatedVisibility）；作品 4 列方格预览墙，按**下载先后倒序**（早下载的排后面：media id 倒序分组、组内 media_index 正序）；图片/视频类型筛选两种视图均可用（listMediaTime 增 asc 参数备用）
- ✅ T9.7 素材库头部与筛选重构（2026-09-06 模拟器实测）：「素材库」标题居中、眉题/副标题删除；新增统计玻璃块（X/Instagram/Bluesky 项数一排 + 图片/视频项数一排，Db.stats 增 byType）；平台筛选（全部/X/Instagram/Bluesky）一排等宽胶囊、类型筛选（全部/图片/视频）另起一排
- ✅ T9.8 图片查看/视频播放呈现验证（2026-09-06 模拟器实测）：图片全屏查看器修复 Dialog 默认宽度导致非全屏、露底层弹层的问题（改 usePlatformDefaultWidth=false + decorFitsSystemWindows=false，与播放器一致）；视频播放页验证：沉浸深蓝播放器、标题+文件名+关闭、控制条（⏮↺5▶↻15⏭+进度条）正常、横版视频 FIT 居中黑边、短片自动播放至结束的完整生命周期 ✓
- ✅ T9.9 下载页交互升级（2026-09-06，用户真机已验证）：点「下载 N」弹底部菜单（全部下载 / 批量下载自选条目）；批量模式卡片勾选圈 + 「已选 N 条｜全选｜取消」操作栏 + 头部按钮变「下载 N」；任务与历史折叠为标题下满长胶囊 tab（进行中 X · 历史 Y ▸），点入任务子页（进行中 TaskCard + 历史 TaskRow），BackHandler 返回
- ✅ T9.10 代理页重构 + Clash 导入（2026-09-06）：标题居中删眉题；当前使用/地址/延迟居中；重新检测与立即测速两等分；自动优选重排（开关行+间隔行）；事件日志折叠（AnimatedVisibility）；新增 ClashConfig 解析器（proxies flow/block + 裸 socks5:// 链接；http/socks5 可用，ss/vmess/trojan 标记跳过）、prefs.clashImport 持久化、每次 detect 自动重建导入候选并参与周期优选；导入卡片（选择文件/粘贴配置/清除）
- ✅ T9.11 设置页重构（2026-09-06）：标题居中删其余；X Cookie / IG Cookie / 账号登入 / 代理优选 四个折叠块（AnimatedVisibility）；账号登入行=登入（开网页）+验证（AccountKit：X verify_credentials、IG accounts/edit 显示本人账号；Bluesky 免登录提示）；存储卡=标题+小字同行、扫描下载目录/同步其他目录两等分；同步其他目录子页（输入路径→验证可用性→syncExternalDir 扫描图片视频按 source_url 判重登记，文件不复制）；支持范围/关于全部重写
- ✅ T9.12 追加调整（2026-09-06，用户反馈）：①素材库长按批量删除——长按格子进选择模式（两视图通用），选中格子遮罩+✓角标，顶部操作栏「已选 N｜全选｜取消｜删除」（删除按钮定在顶部操作栏右侧红色），软删进回收站可恢复，BackHandler 退出；②账号登入功能移除（浏览器登录无法回传 Cookie，AccountKit 删除）；③Clash 导入支持 URL 形式——导入卡片三等分（选择文件/粘贴配置/URL 导入），URL 弹层拉取配置文本解析，base64 订阅自动解码兼容

## 已知限制（v1）
- Instagram 必须提供浏览器导出的 cookies.txt，且 cookies 过期需手动更新
- Twitter 主页批量下载需 cookies；单条推文免登录（fxtwitter）

## 作者统计（2026-09-11，真机验证待设备接入）
- ✅ Db.kt：新增 `AuthorStatRow`（id/平台/handle/名字/头像/素材数/图片/视频/帖子数/容量/最近入库）+ `authorStats(platform, type, origin)`——按当前筛选聚合每位作者素材维度，素材数降序、容量次序，只含有素材作者（与作者视图口径一致）
- ✅ LibraryScreen.kt：顶部统计玻璃块「图片/视频」行扩为三格，新增可点「作者」格（数字=当前筛选下有素材的作者数，标签带 ▸ 提示）；`authorStatRows` 随 stats 同 LaunchedEffect 刷新（mediaVersion/reload/筛选变化均触发）
- ✅ AuthorStatsSheet 弹层（与 MediaSheet 同款玻璃风格）：汇总行「N 位作者 · M 项素材 · 容量 · 当前筛选」；排行行=头像+名字+@handle·图/视拆分·帖数+素材数/容量+主色占比条（以榜首为基准，最低 4% 保底可见）；点行跳作者分组视图并展开该作者（跨分组经 pendingAuthorJump，规避 filterChanged 重置手风琴）
- ⏳ 真机验证：本轮设备未连接（adb devices 空），待插线后跑 install -r → 弹层截图闭环

## 同步其他目录·取消+进度（2026-09-11，真机验证待设备接入）
- ✅ TaskManager.syncExternalDir 加协作取消：签名扩为 (dirPath, isCancelled: ()=>Boolean, onProgress: (phase,done,total,registered)=>Unit)；检查点=File 通道逐文件指纹 / root 通道 40 条分批（md5sum）/ 逐条登记循环；取消即返回部分报告（已入库保留、refresh+mediaVersion 保证 UI 一致，幂等登记天然续传）
- ✅ ExternalSyncReport 扩字段：cancelled / total（两阶段进度分母）
- ✅ SyncExternalScreen：AtomicBoolean cancelFlag + SyncProgress 状态；同步中显示真实进度条（IosProgress pct=done/total，原为无限条）+ 文案「校验指纹 X/Y → 登记 X/Y·已入库 Z」+「取消同步」按钮；取消结果文案「已取消：本次已入库 N（重复跳过 M）…再次同步自动续传」（绿色非报错）；说明文字补取消语义
- ⏳ 真机验证：设备仍未连接，与作者统计一并插线后闭环

## 同步其他目录·整体退订（2026-09-11，用户二次澄清：要的是事后移除而非过程取消）
- 需求澄清：用户「如果我不需要这个目录里面东西，现在没有取消按钮」→ 指同步后反悔无入口；过程取消已做（上一节），本轮做**目录级退订**
- ✅ Db.kt：likeEscape（LIKE 通配转义）+ externalDirMedia(prefix, excludeBase)（列子树条目含回收站）+ removeExternalDirRows（COUNT→DELETE 硬删登记行）；匹配规则 `prefix'/%'` 目录边界安全（不误伤兄弟目录），排除 App 私有目录前缀（只退外部共享条目）
- ✅ TaskManager：countExternalDir（预览计数）+ removeExternalDir（退订执行：清理 App 内缩略图文件 → 硬删登记行 → refresh + mediaVersion++；文件一律不动，posts/authors 留孤儿——不进视图、再同步 upsertPost 复用归并不丢，与 purgeEntries 行为一致）
- ✅ SyncExternalScreen：「移除该目录登记」按钮（!syncing && !removing 才可点）→ IO 计数 → 0 则 toast「该目录暂无已登记素材」→ ConfirmDialog（红字「解除登记」，文案注明文件保留原位/可再同步）→ 执行后 result 行显示「已解除登记 N 项」；说明文字补退订语义
- ⏳ 真机验证：设备仍未连接，三项（作者统计/同步取消/目录退订）插线后一并闭环

## Android 16 兼容性修复·16 KB 页对齐（2026-10-15，真机已验证）
- 现象：PLK110（ColorOS 16 / Android 16）每次启动弹系统「Android 应用兼容性」告警，点名 3 个 Chaquopy 原生库
- 定位：读 APK 内 18 个 `.so` 的 ELF64 PT_LOAD `p_align` —— 只有 `libssl_chaquopy.so` / `libcrypto_chaquopy.so` / `libsqlite3_chaquopy.so` 是 4096（各 3688 字节的 stub 库），其余（含 `libpython3.12.so`）已是 16384
- 排除二进制补丁：三个 stub 的 `(vaddr-off)%16384` = 0/4096/8192，不满足对齐同余要求，改 `p_align` 无效
- ✅ 修复：Chaquopy **16.0.0 → 16.1.0**（上游 #1324 将 `_chaquopy` 的 OpenSSL/SQLite stub 改为 16 KB 链接；该版同时正式支持 AGP 8.9）
- ✅ `mirror_chaquopy.py` 改造：版本 16.0.0→16.1.0、代理 7897→7890、补 target 3.12.15-0；重跑镜像 runtime 5 组件 + target 到 `D:\AndroidDev\maven-local`
- ✅ 校验：APK 内 arm64-v8a + x86_64 共 18 个 `.so`，**未对齐数量 0**；真机弹窗消失、logcat 无兼容性告警、nativeloader 三个 stub 库加载 ok、`YtDlp: warmup ok: python started`、无崩溃
- 顺带说明：日志里出现的 `/system/bin/app_process64` 崩溃（`DexLoadReporterExtImpl` / `Swordfish` 的 UnsatisfiedLinkError）是 ColorOS 系统组件自身问题，与 App 无关

## M10 Edqiu 视觉重构 + 外部目录自动监控（2026-10-25，模拟器实测通过）
### Bug1 修复：Edqiu 目录「扫描+监控」失效（用户反馈：升级后新素材不入库，扫描+监控是摆设）
- 根因：外部目录同步此前只有设置页手动入口（syncExternalDir 仅被 SyncExternalScreen 调用），无任何自动触发——属主 App（Edqiu）新下载的文件永远不会自动入库
- ✅ 启动自动增量登记：Store.init 的 IO 块新增 `tasks.autoSyncExternal()`（读 prefs.syncDir，静默增量登记，异常吞掉只打日志，不打扰用户）
- ✅ 后台周期监控：OptimizeWorker.doWork 开头执行 autoSyncExternal()（独立于 autoOptimize 开关——素材登记不应取决于代理优选是否开启），随既有 15 分钟 WorkManager 周期
- ✅ 并发互斥：syncExternalDir 拆为 wrapper + syncExternalDirLocked，AtomicBoolean 门保证手动/自动不会并发双写（再入抛「已有同步在进行」/静默跳过）
- 排除项：核对 Edqiu v1.7 源码（D:\Edqiu\edqiu-dl-repo InternalMediaDownloader.kt:93）文件名格式仍为 `{uploader}_{tweetId}_{idx}_{kind}_{quality}.ext`，与 EDQIU_NAME 正则一致，非根因
### Edqiu 视觉重构（设计蓝本 D:\Edqiu\edqiu-dl-repo：ui/components/GlassSurface.kt、LiquidTabBar.kt、GlassBackground、edqiu-liquid-glass/colors_and_type.css）
- ✅ 删除莫奈取色全链：MonetResolver.kt 删除；Store.dynamicColor/dynamicScope/monetEpoch、MainActivity 壁纸颜色监听 + quickResolve/deepResolve 派生、设置页「莫奈动态取色/取色作用域」全部移除。ColorScience.kt 保留（纯 HCT 数学库，种子色派生用）
- ✅ 主题令牌改名 MonetColors→EqColors / LocalMonet→LocalEq / ios()→eq()；EqTheme.resolve(seed, dark) 唯一派生路径：强调色种子（默认 #2F4C8F，Edqiu 同款深蓝）→ HCT tone 档派生全套（primary 族 + fill/track/line 中性层 + 舞台三档渐变/光斑）
- ✅ 壁纸舞台（L0，用户点名必要）：StageBackground 默认 = 极光色场（种子色纵向渐变 endY=2800 + 顶部光晕 + 双径向光斑，Edqiu GlassBackground 同构）；可选 = 自选壁纸（GetContent 拷贝到 filesDir/stage_wallpaper.jpg，blur 22dp 毛玻璃铺底 + 明暗纱）；prefs.stage_wallpaper，设置 → 外观 → 壁纸舞台（默认极光/选择图片）
- ✅ GlassSurface 三层玻璃（L1 0.34 / L2 0.46 / L3 0.58）移植：shadow 必须在 clip 之前（Edqiu 2026-09-14 教训）；毛玻璃 = 半透明底 + 种子色 tint + 多层渐变高光，**不用 Modifier.blur**（ColorOS 离屏渲染白框 bug，Edqiu 实测规避）；GlassCard 变为 GlassSurface L1 封装
- ✅ 底栏 LiquidTabBar 移植：50dp stadium 玻璃壳（GlassSurface L2），选中胶囊 primaryContainer 实底 α0.94 + 圆角随位置 16→22dp 贴合内壁变形，按压 tween(50ms) 即时到位、松手 spring 回弹，文字仅选中项出现（9sp Bold），图标 21dp 选中放大 1.12；navigationBarsPadding+bottom 10dp 完整可见（修复底栏被手势区遮挡）；tab 改名 收件箱/素材库/代理/设置；图标重绘：收件筐（E 化）/ 照片框 / 地球 / 滑杆
- ✅ 下载页（自动性去摆设）：头部「＋捕获」→「任务 N」（showTasks 时变 Primary「◂ 收件箱」），满长「任务与历史」胶囊删除；右下 FAB ＋（56dp 圆角方块主色渐变 + 投影，pasteOpen/批量选择时隐藏）承接粘贴区展开 + 剪贴板自动捕获；InboxCard 重排为 Edqiu 链接卡：84dp/20dp 圆角缩略 + 右下白边头像气泡 + ×N 角标移左上、作者名 14sp Bold + 状态淡底胶囊、平台徽章 + InfoColor @handle + fmtIso 时间右对齐、文案 11sp；筛选胶囊选中态 accent→primary 实底
- ✅ 设置页：外观组 = 主题分段 + 壁纸舞台双按钮 + 强调色色板（AccentDot 去掉莫奈禁用逻辑，永远可点）；其余 6 目录卡排版 v1.2.0 已 Edqiu 化，保持
- ⚠️ 构建环境变更：JAVA_HOME 失效（指向已删除的 C:\temp\jdk21），需 `JAVA_HOME="D:\AndroidDev\jdk\jdk21"` 构建
- 验证：模拟器 MemoTest（1080×2400）安装实测：收件箱/素材库/设置/外观展开/深色主题截图 docs/edqiu-restyle-*.png；选中胶囊、FAB、极光舞台、深色海军蓝舞台均符合预期

### Bug1 后半：目录清空后素材库仍显示旧 Edqiu 素材（用户二次反馈，2026-10-25 真机验证通过）
- 现象：Edqiu 目录里的视频/图片被用户清空后，探测能正确报 0，但素材库仍显示旧登记——同步只做增量加入，从不清理「文件已消失」的陈旧登记
- ✅ 失效登记清扫：syncExternalDirLocked 在候选收集后、登记前执行——`Db.externalDirRows(prefix, base)`（新查询，带 file_path，含回收站条目）找出登记路径不在本次候选集且文件确已不存在的条目 → `MediaFiles.deleteDisk` 清缩略图 → `hardDeleteMedia` 硬删登记行；posts/authors 留孤儿（与目录退订同语义，再同步可复用）；mediaVersion++ 驱动素材库即时刷新
- 安全前提：清扫只在目录枚举成功后执行（useApi 过 isDirectory；root 通道 listTree 失败早已抛 UNREACHABLE），存在性判定与登记同源（File.exists / 全量枚举集合），临时不可读不会误判为已删除
- ✅ ExternalSyncReport 增 stale 字段；设置页结果文案追加「清理失效登记 N」；说明文字补「对方删除的文件同步时自动解除登记（启动与后台周期会自动同步）」
- ✅ 真机验证（3B161X007XP00000）：覆盖安装后冷启动，logcat `syncExternalDir: 清扫失效登记 216 条（文件已从目录消失）`——用户库中 216 条失效 Edqiu 登记在首次启动自动清扫完成
