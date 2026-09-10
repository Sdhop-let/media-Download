# 媒体下载器 · 桌面图标设计方案（ICON_DESIGN）

> 版本 v1.1 · 2026-09-07 · 适用 Android v1.1+（com.ep.donwnloader）
> **状态：已落地并真机验证 ✅**（方案 A 已实现，实机特写见 [icon-on-device.png](icon-on-device.png)，2026-09-07 OPPO PLK110 实测）
> 配套视觉稿：[icon-design.svg](icon-design.svg) · 设计系统依据：[HANDOFF.md](HANDOFF.md) §3

---

## 1. 设计定位

图标是 app 内设计系统 **「iOS 26 Liquid Glass × 莫奈深蓝」** 在桌面的第一张名片：
玻璃卡片浮在极光渐变上 → 桌面上即"玻璃图形浮在莫奈蓝渐变上"。
不是拟物、不是纯扁平，定位为 **分层渐变 + 玻璃质感几何图形**（Adaptive Icon 双层结构天然承载）。

品牌记忆点一句话：**莫奈蓝渐变上一枚白玻璃下载箭头，落入盛着三平台色的托盘。**

## 2. 方案对比与结论

| 方案 | 核心图形 | 语义 | 小尺寸表现 | 结论 |
|---|---|---|---|---|
| **A · 落盘** | 白玻璃下载箭头 + 玻璃托盘 + 三平台色点 | 三源媒体"落入"收件箱/素材库 | 箭头粗笔画稳定，色点 48px 仍可辨 | ✅ **采用** |
| B · 汇聚 | 三条平台色流汇入玻璃漏斗 | 多源汇聚、收件箱漏斗 | 流线 48px 糊成色斑，漏斗语义需学习 | 备选 |
| C · 媒体卡 | 玻璃照片卡 + teal 下载角标 | 媒体文件 + 下载动作 | 双元素构图小尺寸重心散 | 备选 |

选 A 的理由：下载箭头是零学习成本的功能直译；托盘呼应收件箱→素材库的产品主线；三点是全 app 平台语义色（PlatBadge）的延伸——图标与界面共用同一套颜色语言。

## 3. 方案 A 完整规范

### 3.1 色值（与 IosUI.kt 主题色严格同源）

| Token | 色值 | 用途 | 来源 |
|---|---|---|---|
| MonetBlueLight | `#5C82C9` | 背景渐变起点（左上） | primaryA 提亮 |
| MonetBlue | `#2F4C8F` | 背景渐变中段 | `MonetLight.primary` |
| MonetDeep | `#223660` | 背景渐变终点（右下） | `MonetLight.primaryDeep` |
| GlassWhite | `#FFFFFF` 96% | 箭头笔画 | — |
| GlassRim | `#FFFFFF` 62% | 托盘描边（玻璃 rim，呼应 `c.rim`） | — |
| GlassFill | `#FFFFFF` 18% | 托盘填充（呼应 `GlassCard` 玻璃底） | — |
| AuroraTeal | `#61C7B0` 13% | 左上极光光斑 | `MonetLight.teal` |
| AuroraAccent | `#8AA5E0` 16% | 右下光斑 | `MonetLight.accent` |
| XDot | `#16213E` | 托盘色点 1（X/Twitter） | `TwColor` |
| IgDot | `#E1306C` | 托盘色点 2（Instagram） | `IgColor` |
| BsDot | `#1185FE` | 托盘色点 3（Bluesky） | `BsColor` |

背景渐变方向：左上 → 右下 135°。光斑位置与 `GlassBackground` 页面顶部极光一致。

### 3.2 自适应图标分层（108×108dp 画布）

```
背景层 background：全幅渐变 + 两颗极光光斑 + 左缘 7% 白高光竖带（liquid glass 暗示）
前景层 foreground：图形全部收在中心 Ø66dp 安全区内
  ├─ 下载箭头：主干竖线 + V 形箭头头，笔画 7dp，圆帽圆角，白 96%
  │    主干 y 24→57dp；箭头头顶点 y 57dp，宽 28dp
  ├─ 玻璃托盘：56×12dp 圆角胶囊（rx 6dp），y 66→78dp，fill 18% + rim 描边 1.7dp
  └─ 三平台色点：Ø5.6dp，y 轴居托盘中线，x 间距 13.5dp 居中排布
单色层 monochrome：仅箭头 + 托盘描边（去掉三点与半透明填充），系统动态取色
```

安全区核算：图形最外沿（托盘宽 56dp）< Ø66dp 安全区直径 ✓；所有元素距画布边 ≥26dp。

### 3.3 多尺寸策略

| 尺寸 | 场景 | 策略 |
|---|---|---|
| 512px | 应用商店 | 完整版，可外加 4% 外发光衬深底 |
| 192 / 108dp | 桌面 | 标准版（上述规范） |
| 96px | 桌面小图标 | 同标准版 |
| 48px | 通知、分享选单、任务列表 | 三点缩至 Ø2.8px 仍可辨（实测对比稿）；如需再减，仅省略 X 色点 |
| Themed Icon | Android 13+ 长按桌面 | monochrome 层生效，白描边图形随壁纸取色 |

深浅壁纸验证：莫奈蓝中明度底色 + 白 96% 图形，深/浅壁纸上对比度均 ≥4.5:1（对比稿右下角）。

### 3.4 命名

图标下显示名沿用 `strings.xml` 的 **「媒体下载器」**，与图标语义（下载 + 媒体托盘）互为印证，不改。

## 4. 落地清单（minSdk 26，零 legacy PNG）

```
res/
├─ drawable/ic_launcher_background.xml    # vector 108×108：渐变 + 光斑 + 高光带
├─ drawable/ic_launcher_foreground.xml    # vector 108×108：箭头 + 托盘 + 三点
├─ drawable/ic_launcher_monochrome.xml    # vector 108×108：描边版（箭头 + 托盘）
└─ mipmap-anydpi-v26/
   ├─ ic_launcher.xml                     # background/foreground/monochrome 三引用
   └─ ic_launcher_round.xml               # 同上
AndroidManifest.xml：
   android:icon="@mipmap/ic_launcher"
   android:roundIcon="@mipmap/ic_launcher_round"
```

注意：
- `<gradient>` vector 属性 API 24+ 支持，minSdk 26 无兼容问题。
- 现状 Manifest **没有** `android:icon`，res 无 mipmap——当前桌面是系统默认占位图，本方案落地即修复。
- 三平台色点在 monochrome 层省略（单色模式下无意义），托盘保留描边维持"容器"意象。

## 5. 验收标准

1. 桌面 96–192px：一眼读出"下载"动作；三色点可辨。
2. 48px：箭头 + 托盘轮廓完整，无断笔画。
3. 深浅壁纸、圆形/圆角方/squircle 三种 launcher mask 下构图不顶边、不偏心。
4. Android 13+ 长按图标出现动态取色单色版。
5. 图标色值与 app 内主题/平台徽章色一一对应（§3.1 表逐项核对）。
