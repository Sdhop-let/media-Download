package com.ep.donwnloader

import androidx.compose.animation.animateColor
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/* ============================================================
 * Monet 2026 深蓝 × iOS26 Liquid Glass 设计系统 v2
 * 浅/深双主题 · 品牌深蓝 #2F4C8F · 玻璃拟态 + 流光进度
 * ============================================================ */

data class MonetColors(
    val isDark: Boolean,
    val bg: Color,        // 页面底（渐变第一档）
    val bg2: Color,       // 页面底（渐变第二档）
    val bg3: Color,       // 页面底（渐变第三档）
    val text: Color,      // 主文字
    val text2: Color,     // 次文字
    val faint: Color,     // 弱文字/未选
    val primary: Color,   // 品牌深蓝（主色）
    val primaryA: Color,  // 渐变亮端
    val primaryDeep: Color, // 渐变深端
    val accent: Color,
    val teal: Color,
    val ok: Color,        // 正常
    val warn: Color,      // 告警
    val err: Color,       // 危险
    val errText: Color,
    val line: Color,      // 分隔/描边
    val rim: Color,       // 玻璃高光描边
    val fill: Color,      // 输入/次级底
    val glow: Color,      // 玻璃投影色
    val track: Color,     // 进度轨道
    val spotA: Color = Color(0xFFDDE6FA),  // 背景光斑 A（取色全局跟随派生时随动）
    val spotB: Color = Color(0xFFDEF0E9),  // 背景光斑 B
)

val MonetLight = MonetColors(
    isDark = false,
    bg = Color(0xFFEEF2FB), bg2 = Color(0xFFE9EFFB), bg3 = Color(0xFFE4EDFA),
    text = Color(0xFF16213E), text2 = Color(0xFF33456F), faint = Color(0xFF94A0B8),
    primary = Color(0xFF2F4C8F), primaryA = Color(0xFF4C6FB4), primaryDeep = Color(0xFF223660),
    accent = Color(0xFF8AA5E0), teal = Color(0xFF61C7B0),
    ok = Color(0xFF2E9E7C), warn = Color(0xFFC97A26), err = Color(0xFFC0433C), errText = Color(0xFFC0433C),
    line = Color(0xFFDCE5F5), rim = Color(0x8CFFFFFF), fill = Color(0xFFE8EEF9),
    glow = Color(0x334C6FB4), track = Color(0xFFE4EAF6),
)

val MonetDark = MonetColors(
    isDark = true,
    bg = Color(0xFF0B1324), bg2 = Color(0xFF0E1830), bg3 = Color(0xFF101C38),
    text = Color(0xFFEDF2FC), text2 = Color(0xFFA9B7D4), faint = Color(0xFF6B7C9E),
    primary = Color(0xFF8FA9DE), primaryA = Color(0xFFAFC4EC), primaryDeep = Color(0xFF5C7DBB),
    accent = Color(0xFF7B99D6), teal = Color(0xFF4FC4AC),
    ok = Color(0xFF43C795), warn = Color(0xFFE8A75A), err = Color(0xFFE47C7C), errText = Color(0xFFF0A6A6),
    line = Color(0xFF22304E), rim = Color(0x26FFFFFF), fill = Color(0xFF16233F),
    glow = Color(0x662F4C8F), track = Color(0xFF1B2944),
)

val LocalMonet = staticCompositionLocalOf { MonetLight }

/** 全局取色入口。 */
@Composable fun ios(): MonetColors = LocalMonet.current

// ---------------- 主题派生（莫奈取色作用域 / 自定义种子色） ----------------

/** 由取色来源派生完整 MonetColors 令牌。
 *  - scope="accent"：只动主色系（按钮/选中态/进度），背景与光斑保持中性
 *  - scope="full"：背景渐变与光斑同步向取色色调偏移，整体视觉统一
 *
 *  Color Output Spec 2025（HCT 空间）派生规则：
 *  - 系统角色（primary/accent/teal）用 dynamicColorScheme 原值，单一真源不重算；
 *  - 派生令牌从 primary 所在 TonalPalette 取 tone 档（同 hue 定理 → 与系统色零漂移）；
 *  - 中性令牌（fill/track/line/bg）用低彩度 neutral palette 固定 tone 档，
 *    tone 层次继承原设计（light 95/94/93/94/92/91，dark 6/9/11/14/17/20）；
 *  - 全程 HCT 运算，禁止 sRGB lerp（sRGB 插值掉 chroma 且 hue 漂移）。 */
object ThemeDerive {
    /** 莫奈动态取色派生（Android 12+ System 色板，prim/secondary/tertiary 为系统原值）。 */
    fun fromDynamic(
        base: MonetColors, prim: Color, secondary: Color, tertiary: Color, scope: String,
    ): MonetColors {
        val d = base.isDark
        val P = ColorScience.paletteOf(prim)
        val pt = ColorScience.hct(prim).tone
        var c = base.copy(
            primary = prim,
            primaryA = ColorScience.toneAt(P, (pt + if (d) 8.0 else 20.0).coerceIn(15.0, 95.0)),
            primaryDeep = ColorScience.toneAt(P, (pt + if (d) -8.0 else -12.0).coerceIn(5.0, 90.0)),
            accent = secondary,
            teal = tertiary,
            ok = tertiary,
            glow = prim.copy(alpha = 0.2f),
        )
        // 中性偏色令牌随取色 hue 去蓝灰默认感：任务与历史胶囊/输入框/分段控件（fill）、
        // 进度轨道（track）、分隔线（line）——tone 层次固定，hue 随壁纸（不分作用域）
        c = tintNeutrals(c, ColorScience.neutralPaletteOf(prim))
        if (scope == "full") c = tintSurfaces(c, ColorScience.neutralPaletteOf(prim, 1.35), prim)
        return c
    }

    /** 自定义种子色派生（与莫奈路径共用同一条 HCT 管线，M3 规范档位）。 */
    fun fromSeed(base: MonetColors, seed: Color, scope: String): MonetColors {
        val d = base.isDark
        val P = ColorScience.paletteOf(seed)
        val primary = ColorScience.color(P.tone(if (d) 80 else 40))
        var c = base.copy(
            primary = ColorScience.ensureContrast(primary, base.bg, P, d),
            primaryA = ColorScience.toneAt(P, if (d) 88.0 else 60.0),
            primaryDeep = ColorScience.toneAt(P, if (d) 72.0 else 28.0),
            accent = ColorScience.toneAt(P, if (d) 86.0 else 50.0),
            glow = primary.copy(alpha = if (d) 0.4f else 0.2f),
        )
        c = tintNeutrals(c, ColorScience.neutralPaletteOf(seed))
        if (scope == "full") c = tintSurfaces(c, ColorScience.neutralPaletteOf(seed, 1.35), primary)
        return c
    }

    /** fill/track/line：中性 palette 固定 tone 档（继承原设计层次，hue 随取色，可读性恒定）。 */
    private fun tintNeutrals(c: MonetColors, N: io.material.color.utilities.palettes.TonalPalette): MonetColors {
        val d = c.isDark
        return c.copy(
            fill = ColorScience.toneAt(N, if (d) 14.0 else 94.0),
            track = ColorScience.toneAt(N, if (d) 17.0 else 92.0),
            line = ColorScience.toneAt(N, if (d) 20.0 else 91.0),
        )
    }

    /** 全局跟随：背景三档中性 tone 档；光斑对 prim 做 HCT 插值（hue 稳定不漂灰）。 */
    private fun tintSurfaces(c: MonetColors, N: io.material.color.utilities.palettes.TonalPalette, prim: Color): MonetColors {
        val d = c.isDark
        return c.copy(
            bg = ColorScience.toneAt(N, if (d) 6.0 else 95.0),
            bg2 = ColorScience.toneAt(N, if (d) 9.0 else 94.0),
            bg3 = ColorScience.toneAt(N, if (d) 11.0 else 93.0),
            spotA = prim.copy(alpha = if (d) 0.28f else 0.80f),
            spotB = ColorScience.lerpHct(prim, if (d) Color.Black else Color.White, 0.40f)
                .copy(alpha = if (d) 0.22f else 0.70f),
        )
    }
}

/** M3 配色方案：由派生 MonetColors 生成（spec 2025 完整表面体系），
 *  保证 M3 组件（输入框/弹层/开关/Chip）与玻璃令牌同步换肤。
 *  surfaceContainer 六档与 container 角色按规范 tone 档由中性/主 palette 派生。 */
fun monetScheme(c: MonetColors): androidx.compose.material3.ColorScheme {
    val P = ColorScience.paletteOf(c.primary)
    val S = ColorScience.paletteOf(c.teal)
    val N = ColorScience.neutralPaletteOf(c.primary)
    return if (c.isDark) androidx.compose.material3.darkColorScheme(
        primary = c.primary, onPrimary = ColorScience.toneAt(P, 20.0),
        primaryContainer = ColorScience.toneAt(P, 30.0), onPrimaryContainer = ColorScience.toneAt(P, 90.0),
        secondary = c.teal, onSecondary = ColorScience.toneAt(S, 20.0),
        secondaryContainer = ColorScience.toneAt(S, 30.0), onSecondaryContainer = ColorScience.toneAt(S, 90.0),
        background = c.bg, onBackground = c.text,
        surface = c.fill, onSurface = c.text,
        surfaceVariant = c.fill, onSurfaceVariant = c.text2,
        surfaceDim = ColorScience.toneAt(N, 6.0), surfaceBright = ColorScience.toneAt(N, 24.0),
        surfaceContainerLowest = ColorScience.toneAt(N, 4.0),
        surfaceContainerLow = ColorScience.toneAt(N, 10.0),
        surfaceContainer = ColorScience.toneAt(N, 12.0),
        surfaceContainerHigh = ColorScience.toneAt(N, 17.0),
        surfaceContainerHighest = ColorScience.toneAt(N, 22.0),
        outline = c.line, outlineVariant = ColorScience.toneAt(N, 30.0),
        error = c.errText, onError = Color(0xFF2A0B0B),
    ) else androidx.compose.material3.lightColorScheme(
        primary = c.primary, onPrimary = ColorScience.toneAt(P, 100.0),
        primaryContainer = ColorScience.toneAt(P, 90.0), onPrimaryContainer = ColorScience.toneAt(P, 10.0),
        secondary = c.teal, onSecondary = ColorScience.toneAt(S, 100.0),
        secondaryContainer = ColorScience.toneAt(S, 90.0), onSecondaryContainer = ColorScience.toneAt(S, 10.0),
        background = c.bg, onBackground = c.text,
        surface = c.fill, onSurface = c.text,
        surfaceVariant = c.fill, onSurfaceVariant = c.text2,
        surfaceDim = ColorScience.toneAt(N, 87.0), surfaceBright = ColorScience.toneAt(N, 98.0),
        surfaceContainerLowest = ColorScience.toneAt(N, 100.0),
        surfaceContainerLow = ColorScience.toneAt(N, 96.0),
        surfaceContainer = ColorScience.toneAt(N, 94.0),
        surfaceContainerHigh = ColorScience.toneAt(N, 92.0),
        surfaceContainerHighest = ColorScience.toneAt(N, 90.0),
        outline = c.line, outlineVariant = ColorScience.toneAt(N, 80.0),
        error = c.errText, onError = Color.White,
    )
}

/** M3 配色方案（供 Button/TextField 等组件在双主题下自适应）。 */
val MonetLightScheme = lightColorScheme(
    primary = MonetLight.primary, onPrimary = Color.White,
    secondary = MonetLight.teal, onSecondary = Color.White,
    background = MonetLight.bg, onBackground = MonetLight.text,
    surface = MonetLight.fill, onSurface = MonetLight.text,
    surfaceVariant = MonetLight.fill, onSurfaceVariant = MonetLight.text2,
    outline = MonetLight.line, error = MonetLight.errText, onError = Color.White,
)

val MonetDarkScheme = darkColorScheme(
    primary = MonetDark.primary, onPrimary = Color(0xFF10213F),
    secondary = MonetDark.teal, onSecondary = Color(0xFF062E27),
    background = MonetDark.bg, onBackground = MonetDark.text,
    surface = MonetDark.fill, onSurface = MonetDark.text,
    surfaceVariant = MonetDark.fill, onSurfaceVariant = MonetDark.text2,
    outline = MonetDark.line, error = MonetDark.errText, onError = Color(0xFF2A0B0B),
)

// ---------------- 状态与平台语义色 ----------------

val OkColor = Color(0xFF3BB98C)
val ErrColor = Color(0xFFD05750)
val WarnColor = Color(0xFFD98F3B)
val InfoColor = Color(0xFF4A7BD8)
val TwColor = Color(0xFF16213E)
val IgColor = Color(0xFFE1306C)
val BsColor = Color(0xFF1185FE)

@Composable
private fun glassBrush(c: MonetColors): Brush {
    return if (c.isDark) Brush.linearGradient(
        0f to Color(0x1CFFFFFF),
        0.55f to Color(0x0EFFFFFF),
        1f to Color(0x07FFFFFF),
        start = Offset(0f, 0f), end = Offset(0.45f, 1f),
    )
    else Brush.linearGradient(
        0f to Color(0xB8FFFFFF),
        0.55f to Color(0x75FFFFFF),
        1f to Color(0x57FFFFFF),
        start = Offset(0f, 0f), end = Offset(0.45f, 1f),
    )
}

/** 极光渐变页面背景：三色光斑 + 纵向底色，深浅双主题；光斑色随取色作用域派生（spotA/spotB）。 */
@Composable
fun GlassBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val c = LocalMonet.current
    Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c.bg, c.bg2, c.bg3)))) {
        Box(
            Modifier.align(Alignment.TopStart).size(560.dp)
                .offset(x = (-140).dp, y = (-180).dp)
                .background(Brush.radialGradient(
                    listOf(c.spotA, c.spotA.copy(alpha = 0f))), CircleShape))
        Box(
            Modifier.align(Alignment.TopEnd).size(480.dp)
                .offset(x = 140.dp, y = (-120).dp)
                .background(Brush.radialGradient(
                    listOf(c.spotB, c.spotB.copy(alpha = 0f))), CircleShape))
        // 底部不放光斑：底栏区域只悬浮胶囊本身
        content()
    }
}

/** 玻璃卡片：主题化半透明渐变 + 高光描边 + 蓝调投影 + 可选状态顶条。 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    topStripe: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalMonet.current
    Box(
        modifier
            .shadow(14.dp, RoundedCornerShape(24.dp),
                ambientColor = c.glow, spotColor = c.glow)
            .clip(RoundedCornerShape(24.dp))
            .background(glassBrush(c))
            .border(1.dp, c.rim, RoundedCornerShape(24.dp)),
    ) {
        Column(Modifier.padding(top = if (topStripe != null) 5.dp else 0.dp)) { content() }
        if (topStripe != null) {
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(3.dp).background(topStripe))
        }
    }
}

/**
 * 空状态面板：轻玻璃容器 + 渐变圆标锚点 + 主/副文案 + 可选操作胶囊。
 * 收件箱、任务列表、素材库等空列表统一用它，替代裸文字提示。
 * compact=true 用于页内小节（如任务子页的进行中/历史）。
 */
@Composable
fun EmptyState(
    icon: String,
    title: String,
    hint: String = "",
    actionText: String = "",
    onAction: (() -> Unit)? = null,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val c = LocalMonet.current
    Box(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(
                c.primary.copy(alpha = if (c.isDark) 0.16f else 0.07f),
                c.primary.copy(alpha = if (c.isDark) 0.05f else 0.02f),
            )))
            .border(1.dp, c.rim, RoundedCornerShape(24.dp))
            .padding(horizontal = 24.dp, vertical = if (compact) 20.dp else 30.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // 视觉锚点：主色渐变玻璃圆 + emoji
            Box(
                Modifier.size(if (compact) 46.dp else 62.dp)
                    .background(Brush.linearGradient(listOf(
                        c.primary.copy(alpha = if (c.isDark) 0.32f else 0.16f),
                        c.primaryA.copy(alpha = if (c.isDark) 0.18f else 0.07f),
                    )), CircleShape)
                    .border(1.dp, c.primary.copy(alpha = if (c.isDark) 0.32f else 0.15f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(icon, fontSize = if (compact) 21.sp else 26.sp)
            }
            Spacer(Modifier.height(if (compact) 10.dp else 14.dp))
            Text(title, fontSize = if (compact) 13.sp else 14.sp,
                fontWeight = FontWeight.SemiBold, color = c.text2,
                textAlign = TextAlign.Center)
            if (hint.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(hint, fontSize = if (compact) 10.5.sp else 11.5.sp,
                    color = c.faint, textAlign = TextAlign.Center, lineHeight = 16.sp)
            }
            if (actionText.isNotBlank() && onAction != null) {
                Spacer(Modifier.height(13.dp))
                // 引导操作：primary 低透明胶囊（同「任务与历史」胶囊 tab 的取色语言）
                Box(
                    Modifier.clip(RoundedCornerShape(100.dp))
                        .background(c.primary.copy(alpha = if (c.isDark) 0.24f else 0.11f))
                        .border(1.dp, c.primary.copy(alpha = if (c.isDark) 0.36f else 0.20f),
                            RoundedCornerShape(100.dp))
                        .clickable(onClick = onAction)
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                ) {
                    Text(actionText, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        color = if (c.isDark) c.primaryA else c.primary)
                }
            }
        }
    }
}

/** 页头：眉题 + 大标题 + 计数 + 圆形操作按钮（玻璃底）。 */
@Composable
fun AppHeader(
    eyebrow: String,
    title: String,
    count: String? = null,
    onAction: (() -> Unit)? = null,
    actionIcon: String = "↻",
) {
    val c = LocalMonet.current
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(glassBrush(c))
            .border(1.dp, c.rim, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(eyebrow, fontSize = 9.sp, letterSpacing = 3.sp,
                fontWeight = FontWeight.Bold, color = c.primary)
            Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold,
                color = c.text, modifier = Modifier.padding(top = 1.dp))
            if (count != null) {
                Text(count, fontSize = 11.sp, color = c.faint, modifier = Modifier.padding(top = 1.dp))
            }
        }
        if (onAction != null) {
            Box(
                Modifier.size(40.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(c.fill)
                    .border(1.dp, if (c.isDark) Color(0x33FFFFFF) else Color(0x0D16213E), RoundedCornerShape(13.dp))
                    .clickable { onAction() },
                contentAlignment = Alignment.Center,
            ) { Text(actionIcon, fontSize = 16.sp, color = c.text2) }
        }
    }
}

/** 区块标题：竖条强调。 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    val c = LocalMonet.current
    Row(modifier.padding(start = 6.dp, top = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(4.dp, 14.dp).background(c.accent, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = c.text)
    }
}

/** 大标题（保留旧名）。 */
@Composable
fun LargeTitle(title: String, subtitle: String? = null) {
    AppHeader(eyebrow = "MEDIA DOWNLOADER", title = title, count = subtitle)
}

/** 玻璃行：标题/副标题 + 尾部内容 + 行分隔线。 */
@Composable
fun IosRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable (() -> Unit)? = null,
    destructive: Boolean = false,
) {
    val c = LocalMonet.current
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp,
                color = if (destructive) c.errText else c.text,
                fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(subtitle, fontSize = 12.5.sp, color = c.faint, lineHeight = 16.sp)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            trailing()
        }
    }
    if (content != null) {
        Column(Modifier.padding(horizontal = 16.dp)) { content() }
    }
    Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(1.dp).background(c.line.copy(alpha = 0.6f)))
}

/** iOS 分段控件：主色滑块随选中项 spring 滑动（非逐段淡入淡出）。 */
@Composable
fun IosSegmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalMonet.current
    BoxWithConstraints(
        modifier
            .background(c.fill, RoundedCornerShape(14.dp))
            .padding(3.dp),
    ) {
        val segH = 30.dp
        val gap = 3.dp
        val segW = (maxWidth - gap * (options.size - 1)) / options.size
        val sliderX by animateDpAsState(
            targetValue = segW * selected + gap * selected,
            animationSpec = spring(dampingRatio = 0.82f, stiffness = 480f),
            label = "seg",
        )
        // 滑块层（在标签层之下，位移由 spring 驱动）
        Box(
            Modifier
                .offset(x = sliderX)
                .size(segW, segH)
                .background(
                    Brush.linearGradient(listOf(c.primaryA, c.primary)),
                    RoundedCornerShape(11.dp)),
        )
        // 标签层：热区与滑块严格对齐
        Row(Modifier.fillMaxWidth().height(segH),
            horizontalArrangement = Arrangement.spacedBy(gap)) {
            options.forEachIndexed { i, label ->
                val on = i == selected
                Box(
                    Modifier
                        .width(segW)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, fontSize = 12.5.sp,
                        color = if (on) Color.White else c.text2,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1)
                }
            }
        }
    }
}

/** iOS 风格自绘开关：spring 弹性位移 + 轨道颜色渐变 + 按压缩放反馈；轨道色随取色体系。 */
@Composable
fun IosSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalMonet.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val w = 50.dp; val h = 30.dp; val thumb = 26.dp; val pad = 2.dp
    val trans = updateTransition(targetState = checked, label = "iossw")
    val tx by trans.animateDp(
        transitionSpec = { spring(dampingRatio = 0.72f, stiffness = 460f) },
        label = "tx",
    ) { if (it) w - thumb - pad * 2 else pad }
    val track by trans.animateColor(
        transitionSpec = { tween(190) },
        label = "track",
    ) { if (it) c.primary else if (c.isDark) Color(0xFF35425F) else Color(0xFFC2CDDD) }
    val scale by animateFloatAsState(
        if (pressed) 0.92f else 1f,
        spring(dampingRatio = 0.6f, stiffness = 600f), label = "sc")
    Box(
        modifier
            .size(w, h)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(100.dp))
            .background(track)
            .clickable(interactionSource = interaction, indication = null) { onChange(!checked) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = tx)
                .size(thumb)
                .shadow(2.dp, CircleShape,
                    ambientColor = Color(0x33000000), spotColor = Color(0x33000000))
                .background(Color.White, CircleShape),
        )
    }
}

/** 折叠指示 chevron：向右小箭头，展开时弹性旋转 90° 指向下；可作导航箭头（open=false）。 */
@Composable
fun Chevron(open: Boolean, modifier: Modifier = Modifier, tint: Color? = null) {
    val c = LocalMonet.current
    val rot by animateFloatAsState(
        if (open) 90f else 0f,
        spring(dampingRatio = 0.7f, stiffness = 380f), label = "chev")
    Canvas(modifier.size(14.dp, 14.dp)) {
        val col = tint ?: c.faint
        val stroke = Stroke(width = 1.7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val cx = size.width / 2f; val cy = size.height / 2f
        val d = size.minDimension * 0.22f
        val p = Path().apply {
            moveTo(cx - d, cy - d)
            lineTo(cx + d, cy)
            lineTo(cx - d, cy + d)
        }
        rotate(rot) { drawPath(p, col, style = stroke) }
    }
}

/** 渐变主按钮（深蓝主色）。 */
@Composable
fun GradientButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalMonet.current
    Box(
        modifier
            .background(
                Brush.linearGradient(listOf(c.primaryA, c.primary, c.primaryDeep)),
                RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** 玻璃按钮：Primary=深蓝渐变主操作；Ghost=玻璃底次级操作。 */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    kind: ButtonKind = ButtonKind.Primary,
) {
    val c = LocalMonet.current
    val shape = RoundedCornerShape(15.dp)
    val alpha = if (enabled) 1f else 0.4f
    val bg = if (kind == ButtonKind.Primary)
        Brush.linearGradient(listOf(
            c.primaryA.copy(alpha = alpha), c.primary.copy(alpha = alpha), c.primaryDeep.copy(alpha = alpha)))
    else Brush.linearGradient(
        if (c.isDark) listOf(Color(0x1CFFFFFF).copy(alpha = alpha), Color(0x0EFFFFFF).copy(alpha = alpha))
        else listOf(Color(0xB8FFFFFF).copy(alpha = alpha), Color(0x75FFFFFF).copy(alpha = alpha)))
    Box(
        modifier
            .clip(shape)
            .background(bg)
            .then(if (kind == ButtonKind.Ghost) Modifier.border(1.dp, c.rim, shape) else Modifier)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text,
            color = if (kind == ButtonKind.Primary) Color.White.copy(alpha = alpha) else c.text2,
            fontSize = 13.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

enum class ButtonKind { Primary, Ghost }

/** 玻璃输入框：无边框、圆角、主题化容器。enabled=false 供掩码态（如 Cookie 隐藏）复用玻璃配色，不灰化突兀。 */
@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = false,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    enabled: Boolean = true,
) {
    val c = LocalMonet.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall) },
        textStyle = MaterialTheme.typography.bodySmall.copy(color = c.text),
        singleLine = singleLine,
        minLines = minLines,
        keyboardOptions = keyboardOptions,
        shape = RoundedCornerShape(18.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = c.fill.copy(alpha = if (c.isDark) 1f else 0.72f),
            unfocusedContainerColor = c.fill.copy(alpha = if (c.isDark) 1f else 0.72f),
            focusedBorderColor = c.primary.copy(alpha = 0.45f),
            unfocusedBorderColor = Color.Transparent,
            cursorColor = c.primary,
            focusedTextColor = c.text,
            unfocusedTextColor = c.text,
            focusedPlaceholderColor = c.faint,
            unfocusedPlaceholderColor = c.faint,
            disabledContainerColor = c.fill.copy(alpha = if (c.isDark) 1f else 0.72f),
            disabledBorderColor = Color.Transparent,
            disabledTextColor = c.text,
            disabledPlaceholderColor = c.faint,
        ),
    )
}

/** 玻璃筛选胶囊（on：accent 底白字；off：玻璃底 + 描边）。 */
@Composable
fun IosChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalMonet.current
    Box(
        Modifier
            .clip(RoundedCornerShape(100.dp))
            .then(
                if (selected) Modifier.background(c.accent)
                else Modifier.background(
                    if (c.isDark) Color(0x14FFFFFF) else Color(0xB8FFFFFF))
                    .border(1.dp, c.rim, RoundedCornerShape(100.dp)))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(label, fontSize = 12.5.sp,
            color = if (selected) Color.White else c.text2,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1)
    }
}

/** 流光进度条：渐变填充 + 移动光斑；pct=null 时为不确定态（扫描动画）。 */
@Composable
fun IosProgress(pct: Float?, modifier: Modifier = Modifier, color: Color? = null) {
    val c = LocalMonet.current
    val base = color ?: c.primary
    val trans = rememberInfiniteTransition(label = "pg")
    val sweep by trans.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1700, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep")
    val density = LocalDensity.current
    val glintSpread = with(density) { 46.dp.toPx() }
    val glint = Color.White.copy(alpha = if (c.isDark) 0.30f else 0.55f)

    BoxWithConstraints(
        modifier.fillMaxWidth().height(6.dp)
            .clip(RoundedCornerShape(3.dp)).background(c.track),
    ) {
        val pxW = constraints.maxWidth.toFloat()
        val fraction = pct?.coerceIn(0f, 1f)
        if (fraction != null) {
            if (fraction > 0f) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(
                    Brush.horizontalGradient(
                        listOf(base.copy(alpha = 0.8f), base, base.copy(alpha = 0.8f)),
                        startX = 0f, endX = with(density) { 220.dp.toPx() })))
            }
        } else {
            // 不确定态：一段渐变沿轨道往复扫过
            val segFrac = 0.34f
            Box(Modifier.fillMaxWidth(segFrac).fillMaxHeight()
                .offset { IntOffset((sweep * (1f - segFrac) * pxW).roundToInt(), 0) }
                .background(Brush.horizontalGradient(
                    listOf(base.copy(alpha = 0.35f), base, base.copy(alpha = 0.35f)),
                    startX = 0f, endX = with(density) { 140.dp.toPx() })))
        }
        // 光斑
        Box(Modifier.matchParentSize()) {
            Box(Modifier.matchParentSize()
                .offset { IntOffset((sweep * pxW).roundToInt(), 0) }
                .background(Brush.horizontalGradient(
                    listOf(Color.Transparent, glint, Color.Transparent),
                    startX = -glintSpread, endX = glintSpread)))
        }
    }
}

/** 状态胶囊（主题化 16% 底 + 彩字）。 */
@Composable
fun StatusPill(text: String, color: Color) {
    Box(
        Modifier.background(color.copy(alpha = 0.16f), RoundedCornerShape(100.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) { Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
}

/** 平台徽章（品牌色淡底）。 */
@Composable
fun PlatBadge(platform: String) {
    val c = LocalMonet.current
    val color = when (platform) {
        "twitter" -> c.text
        "instagram" -> IgColor
        "bluesky" -> BsColor
        else -> c.faint
    }
    Text(
        platName(platform),
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .background(color.copy(alpha = 0.13f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

// ---------------- 底部液态玻璃 Tab 栏（iOS 26 Liquid Glass） ----------------

/** 底栏图标类型（自绘矢量，与 Chevron 同一圆头描边语言）。 */
enum class TabIconType { Download, Library, Globe, Gear }

/** 底栏矢量图标：24 单位视口缩放绘制，1.9dp 圆头描边。 */
@Composable
fun TabIcon(type: TabIconType, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(23.dp)) {
        val s = size.minDimension / 24f
        scale(s, pivot = Offset.Zero) {
            val stroke = Stroke(width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            when (type) {
                TabIconType.Download -> {
                    // 托盘：U 形圆角底
                    drawPath(Path().apply {
                        moveTo(5f, 13.6f)
                        lineTo(5f, 16.4f)
                        quadraticTo(5f, 19.2f, 7.8f, 19.2f)
                        lineTo(16.2f, 19.2f)
                        quadraticTo(19f, 19.2f, 19f, 16.4f)
                        lineTo(19f, 13.6f)
                    }, color, style = stroke)
                    // 下箭头
                    drawPath(Path().apply {
                        moveTo(12f, 4.4f)
                        lineTo(12f, 13.8f)
                        moveTo(8.4f, 10.4f)
                        lineTo(12f, 14f)
                        lineTo(15.6f, 10.4f)
                    }, color, style = stroke)
                }
                TabIconType.Library -> {
                    // 2×2 圆角网格（媒体墙），光学居中外扩
                    val cell = Size(6.8f, 6.8f)
                    val r = CornerRadius(2.4f, 2.4f)
                    listOf(3.6f to 3.6f, 13.6f to 3.6f, 3.6f to 13.6f, 13.6f to 13.6f).forEach { (x, y) ->
                        drawRoundRect(color, topLeft = Offset(x, y), size = cell,
                            cornerRadius = r, style = stroke)
                    }
                }
                TabIconType.Globe -> {
                    // 地球：外圆 + 赤道线 + 经线椭圆（代理=网络中转）
                    val o = Offset(12f, 12f)
                    drawCircle(color, radius = 8.2f, center = o, style = stroke)
                    drawLine(color, Offset(3.8f, 12f), Offset(20.2f, 12f),
                        strokeWidth = 1.9f, cap = StrokeCap.Round)
                    drawOval(color, topLeft = Offset(8.2f, 3.8f),
                        size = Size(7.6f, 16.4f), style = stroke)
                }
                TabIconType.Gear -> {
                    val o = Offset(12f, 12f)
                    drawCircle(color, radius = 6.9f, center = o, style = stroke)
                    drawCircle(color, radius = 2.7f, center = o, style = stroke)
                    for (i in 0..7) {
                        rotate(i * 45f, pivot = o) {
                            drawLine(color,
                                Offset(12f, 12f - 6.9f), Offset(12f, 12f - 10.3f),
                                strokeWidth = 1.9f, cap = StrokeCap.Round)
                        }
                    }
                }
            }
        }
    }
}

/** 悬浮液态玻璃底栏（iOS 26 Liquid Glass）：
 *  高透明玻璃条（页面内容从后透出）+ 顶部光泽 + spring 弹性滑动的液态选中药丸（滑动时拉伸回弹）+ 自绘矢量图标。 */
@Composable
fun GlassTabBar(
    labels: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    val c = LocalMonet.current
    val shape = RoundedCornerShape(28.dp)
    // 液态玻璃条材质：比旧版大幅透明（上亮下沉纵向渐变）
    val barBrush = if (c.isDark)
        Brush.verticalGradient(listOf(Color(0x591E2C4C), Color(0x33142038)))
    else Brush.verticalGradient(listOf(Color(0x70FFFFFF), Color(0x45FFFFFF)))
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .shadow(18.dp, shape, ambientColor = c.glow, spotColor = c.glow)
            .clip(shape)
            .background(barBrush)
            .border(1.dp, c.rim, shape),
    ) {
        // 顶部玻璃光泽（浅色主题更亮的镜面反光）
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            0f to Color.White.copy(alpha = if (c.isDark) 0.14f else 0.50f),
            0.45f to Color.Transparent)))
        // 液态选中药丸：spring 位移（低阻尼 → 过冲回弹），|当前-目标| 驱动拉伸/挤压形变
        val slotW = maxWidth / labels.size
        val targetX = slotW * selected
        val x by animateDpAsState(targetValue = targetX,
            animationSpec = spring(dampingRatio = 0.52f, stiffness = 340f), label = "tabPillX")
        val stretch = ((targetX - x).value / slotW.value).let { kotlin.math.abs(it) }
            .coerceIn(0f, 1f)
        Box(
            Modifier
                .offset(x = x)
                .width(slotW)
                .fillMaxHeight()
                .graphicsLayer {
                    scaleX = 1f + stretch * 0.12f
                    scaleY = 1f - stretch * 0.07f
                }
                .padding(4.dp)
                .clip(RoundedCornerShape(23.dp))
                .background(c.primary.copy(alpha = if (c.isDark) 0.32f else 0.13f))
                .border(1.dp, c.primary.copy(alpha = if (c.isDark) 0.40f else 0.20f),
                    RoundedCornerShape(23.dp)),
        ) {
            // 药丸凸起高光（液态玻璃体积感）
            Box(Modifier.matchParentSize().clip(RoundedCornerShape(23.dp)).background(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = if (c.isDark) 0.20f else 0.60f),
                    0.5f to Color.Transparent)))
        }
        // 标签层
        Row(Modifier.fillMaxSize()) {
            labels.forEachIndexed { i, label ->
                val on = i == selected
                val iconColor by animateColorAsState(
                    if (on) c.primary else c.faint, tween(200), label = "tabIc")
                val iconScale by animateFloatAsState(
                    if (on) 1.08f else 1f,
                    spring(dampingRatio = 0.55f, stiffness = 520f), label = "tabIcS")
                Box(
                    Modifier.weight(1f).fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.graphicsLayer { scaleX = iconScale; scaleY = iconScale }) {
                            TabIcon(TabIconType.entries[i], iconColor)
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(label, fontSize = 10.5.sp, lineHeight = 12.sp,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                            color = if (on) c.primary else c.faint, maxLines = 1)
                    }
                }
            }
        }
    }
}
