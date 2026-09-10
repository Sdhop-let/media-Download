# 调研结论（2026-09-05）

> 调研方式：GitHub 生态检索 + 本机实测验证（Windows 11 + Python 3.14 + Clash Verge 7897）。

## 1. 下载引擎选型

### 结论：gallery-dl 为主引擎 + 自研双兜底

| 引擎 | 结论 |
|---|---|
| **gallery-dl**（mikf/gallery-dl，约 19.4k★） | ✅ 唯一同时覆盖 Twitter/X、Instagram、Bluesky（图片+视频）的单引擎；发布极活跃（v1.32.11，2026-09-04）；支持 `-j/--dump-json` 元数据模式、`--proxy`、`-C cookies`、`--write-metadata` 边车文件 |
| **fxtwitter API**（FxEmbed/FxEmbed，5k★，MIT） | ✅ 免登录获取单条推文媒体 JSON（实测 `GET /{handle}/status/{id}` → 200）；1000 req/min/IP；媒体在 `tweet.media.all[]`（photo/video/gif，含 url/width/height） |
| **Bluesky 公共 API**（public.api.bsky.app） | ✅ 免登录读公开帖子/主页（实测经代理 200）；图片可用 `com.atproto.sync.getBlob?did=&cid=` 直链构造 |
| yt-dlp | Bluesky 免登录可用，但 Twitter/Instagram 仍需 cookies；作为视频类 URL 的备选，本项目暂不引入 |
| instaloader（9~10k★） | 仅 Instagram，且 2025 年后限流严重，不采用 |

### 各平台登录要求（2026 年现状，已核实）

| 平台 | 免登录可行性 | 说明 |
|---|---|---|
| Twitter/X 单条推文 | ✅ 走 fxtwitter | 主页/时间线/搜索需 `auth_token` cookies（guest token 已失效，见 gallery-dl#8293）；不要用账密登录（会被风控） |
| Instagram | ❌ 必须 cookies | 2023 年起全站限制；cookies 从浏览器导出（cookies.txt），过期是常见故障 |
| Bluesky | ✅ 完全免登录 | 公开帖/公开主页均免登录；限流较宽松 |

### 关键 CLI 参数（gallery-dl）

- `-j`：只打印元数据，不下载；输出为流式 JSON（`[序号, kwdict]` 或任务描述）
- `--proxy URL`：设置代理（http 均可，Clash mixed 端口同时支持 socks）
- `-C cookies.txt`：Netscape 格式 cookies；`-d DIR`：目标目录；`--write-metadata`：每个文件旁写 `.json` 边车
- 元数据字段（实测 bsky）：`author.handle/displayName/avatar`、`cid`、`createdAt/date`、`embed.images[].image.blob.$link`、`count`（帖内序号）；**kwdict 中没有最终媒体 URL**，Bluesky 需自行构造 getBlob 直链

### 本机实测记录

- Clash Verge（7897，mixed）：环境变量 `HTTP_PROXY`、注册表 `ProxyEnable=1 + ProxyServer=127.0.0.1:7897`、端口扫描三路均可检出 ✅
- 经代理访问 `public.api.bsky.app` → 200 ✅（直连超时，证明代理模块是刚需）
- fxtwitter 路径约束：必须 `/{handle}/status/{id}`（`/i/status/`、`/x/status/` 返回 403）→ 解析出真实 handle 后调用；x.com 的 `/i/web/status/` 形式无 handle 时走 gallery-dl 兜底

## 2. 代理方案（Windows）

### 检测算法（按优先级）

1. **环境变量**：`HTTP_PROXY/HTTPS_PROXY/ALL_PROXY`（`urllib.request.getproxies()` 会先读环境变量再读注册表）
2. **注册表 WinINET**（`HKCU\...\Internet Settings`）：`ProxyEnable`（DWORD 0/1）→ `ProxyServer`（两种格式：`host:port` 单值 或 `http=...;https=...;socks=...` 分协议）；`AutoConfigURL`（PAC）→ 拉取 PAC 文本，正则提取所有 `PROXY host:port` 作为候选
3. **常见端口扫描兜底**（127.0.0.1）：Clash/CFW `7890/7891`、Clash Verge(mihomo) `7897`、v2rayN `10808(socks)/10809(http)`、sing-box `2080`、SS/SSR `1080`、通用 `8080/8888`
4. 每个候选先用 HTTP CONNECT 探测（httpx `proxy=http://...`），失败再按 SOCKS5 探测（需 `httpx[socks]`），自动归类协议

### 测速与持续优选

- 延迟：经代理请求 204 端点（`https://cp.cloudflare.com/generate_204`）取 2 次均值
- 吞吐：`https://speed.cloudflare.com/__down?bytes=10485760`（5~10MB，限时 5s 截断）
- 评分：`score = 吞吐Mbps / (1 + 延迟ms/300)`，死节点清零；「直连」也作为基线候选参与比较
- 持续优化：后台协程每 N 分钟（默认 10，可配）复测存活候选，若新最优比当前最优得分高 20% 以上则自动切换并发事件到 UI

### 库选择

`httpx[socks]`（async，代理/socks 全支持，实测通过）+ `pysocks`（供 gallery-dl 走 socks）。

## 3. 同类产品 UI 参考

| 项目 | 借鉴点 |
|---|---|
| MeTube（alexta69/metube，14.5k★） | 单输入框 + 队列实时进度条 + 深色模式，Web UI 形态 |
| Media Downloader（mhogomchungu，5k★） | 引擎自动管理；URL Manager（分站点配置）、剪贴板监视、History 页 |
| TikTokDownloader（JoeanAmier，8.8k★） | 按作者组织的素材输出结构 |
| Stacher / Open Video Downloader | 队列 + 任务卡片布局 |

**采纳的 UI 模式**：粘贴链接 → 元数据预览（缩略图/作者/日期）→ 下载确认；常驻下载队列；分站点 cookies 配置；素材库按作者/时间双视图。
