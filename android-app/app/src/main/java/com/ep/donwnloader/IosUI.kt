package com.ep.donwnloader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColor
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.graphics.ImageBitmap
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
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
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/* ============================================================
 * Edqiu 液态玻璃设计系统 v3（2026-10-25 全面改版）
 * 壁纸舞台（L0 彩色色场/自定义壁纸）+ 三层玻璃 + 品牌深蓝
 * 设计蓝本：Edqiu GlassSurface / LiquidTabBar / eq-stage（D:\Edqiu\edqiu-dl-repo）
 * ============================================================ */

data class EqColors(
    val isDark: Boolean,
    val bg: Color,        // 舞台渐变第一档（顶部）
    val bg2: Color,       // 舞台渐变第二档
    val bg3: Color,       // 舞台渐变第三档（底部）
    val text: Color,      // 主文字
    val text2: Color,     // 次文字
    val faint: Color,     // 弱文字/未选
    val primary: Color,   // 品牌主色
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
    val spotA: Color,     // 舞台光斑 A（左上）
    val spotB: Color,     // 舞台光斑 B（右上）
)

/** 主题解析：强调色种子（默认 Edqiu 同款深蓝）→ HCT 全量派生。
 *  莫奈/壁纸取色已整体移除——舞台色域跟随强调色，稳定且可预期。 */
object EqTheme {
    val DEFAULT_SEED = Color(0xFF2F4C8F)

    fun resolve(dark: Boolean, seed: Color? = null): EqColors {
        val s = seed ?: DEFAULT_SEED
        val P = ColorScience.paletteOf(s)
        val N = ColorScience.neutralPaletteOf(s)
        val T = ColorScience.paletteOf(TEAL_SEED)
        if (dark) {
            val bg = ColorScience.toneAt(P, 7.0)
            val bg2 = ColorScience.toneAt(P, 10.0)
            val bg3 = ColorScience.toneAt(P, 13.0)
            val primary = ColorScience.ensureContrast(ColorScience.toneAt(P, 80.0), bg2, P, true)
            return EqColors(
                isDark = true,
                bg = bg, bg2 = bg2, bg3 = bg3,
                text = Color(0xFFEDF2FC), text2 = Color(0xFFA9B7D4), faint = Color(0xFF6B7C9E),
                primary = primary,
                primaryA = ColorScience.toneAt(P, 88.0),
                primaryDeep = ColorScience.toneAt(P, 60.0),
                accent = ColorScience.toneAt(P, 70.0),
                teal = ColorScience.toneAt(T, 70.0),
                ok = Color(0xFF43C795), warn = Color(0xFFE8A75A),
                err = Color(0xFFE47C7C), errText = Color(0xFFF0A6A6),
                line = ColorScience.toneAt(N, 20.0),
                rim = Color(0x26FFFFFF),
                fill = ColorScience.toneAt(N, 14.0),
                glow = primary.copy(alpha = 0.40f),
                track = ColorScience.toneAt(N, 17.0),
                spotA = primary.copy(alpha = 0.30f),
                spotB = ColorScience.toneAt(T, 60.0).copy(alpha = 0.26f),
            )
        } else {
            val bg = ColorScience.toneAt(P, 89.0)
            val bg2 = ColorScience.toneAt(P, 92.0)
            val bg3 = ColorScience.toneAt(P, 95.0)
            val primary = ColorScience.ensureContrast(ColorScience.toneAt(P, 40.0), bg2, P, false)
            return EqColors(
                isDark = false,
                bg = bg, bg2 = bg2, bg3 = bg3,
                text = Color(0xFF16213E), text2 = Color(0xFF33456F), faint = Color(0xFF94A0B8),
                primary = primary,
                primaryA = ColorScience.toneAt(P, 60.0),
                primaryDeep = ColorScience.toneAt(P, 28.0),
                accent = ColorScience.toneAt(P, 50.0),
                teal = ColorScience.toneAt(T, 45.0),
                ok = Color(0xFF2E9E7C), warn = Color(0xFFC97A26),
                err = Color(0xFFC0433C), errText = Color(0xFFC0433C),
                line = ColorScience.toneAt(N, 88.0),
                rim = Color(0x8CFFFFFF),
                fill = ColorScience.toneAt(N, 94.0),
                glow = primary.copy(alpha = 0.20f),
                track = ColorScience.toneAt(N, 91.0),
                spotA = ColorScience.toneAt(P, 84.0).copy(alpha = 0.85f),
                spotB = ColorScience.toneAt(T, 86.0).copy(alpha = 0.75f),
            )
        }
    }

    /** 舞台光斑之外的第三色（青色系，固定 hue 保证极光语感）。 */
    val TEAL_SEED = Color(0xFF1898B8)
}

val LocalEq = staticCompositionLocalOf { EqTheme.resolve(false) }

/** 全局取色入口。 */
@Composable fun eq(): EqColors = LocalEq.current

/** M3 配色方案：由 EqColors 生成，M3 组件（输入框/弹层/开关）与玻璃令牌同步换肤。 */
fun eqScheme(c: EqColors): androidx.compose.material3.ColorScheme {
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
        outline = c.line, outlineVariant = ColorScience.toneAt(N, 30.0),
        error = c.errText, onError = Color(0xFF2A0B0B),
    ) else androidx.compose.material3.lightColorScheme(
        primary = c.primary, onPrimary = Color.White,
        primaryContainer = ColorScience.toneAt(P, 90.0), onPrimaryContainer = ColorScience.toneAt(P, 10.0),
        secondary = c.teal, onSecondary = Color.White,
        secondaryContainer = ColorScience.toneAt(S, 90.0), onSecondaryContainer = ColorScience.toneAt(S, 10.0),
        background = c.bg, onBackground = c.text,
        surface = c.fill, onSurface = c.text,
        surfaceVariant = c.fill, onSurfaceVariant = c.text2,
        outline = c.line, outlineVariant = ColorScience.toneAt(N, 80.0),
        error = c.errText, onError = Color.White,
    )
}

// ---------------- 状态与平台语义色 ----------------

val OkColor = Color(0xFF3BB98C)
val ErrColor = Color(0xFFD05750)
val WarnColor = Color(0xFFD98F3B)
val InfoColor = Color(0xFF4A7BD8)
val TwColor = Color(0xFF16213E)
val IgColor = Color(0xFFE1306C)
val BsColor = Color(0xFF1185FE)

// ---------------- 玻璃表面（Edqiu repo 最新版同构移植，M11：真折射 + 手绘回退双路径） ----------------

/**
 * 玻璃层级（Edqiu 设计文档 §3.2）：
 * - L1 页面层：列表、卡片所在区域（大面玻璃，重透）
 * - L2 浮层：底部导航栏、悬浮控件（悬浮玻璃）
 * - L3 弹出层：BottomSheet、Snackbar（最高层玻璃）
 *
 * 2026-09-21 投影移除 + 边缘光引入：层级浮起感由材质承担（不依赖投影落差）——
 * shadow 在透明玻璃后会从背后透出成"长方形色块"，这是 Edqiu 实机判决过的坑。
 * 2026-10-24 档位取值 = Edqiu「四方面优化」后的最新值：L1 真折射后壁纸直接透进
 * 卡面，bgAlpha 0.30 给文字稳定基座；edgeLight/topLight 减半——轮廓定义交给
 * 光学弯折 + 色散，不是白线。
 */
enum class GlassTier(
    val bgAlpha: Float,
    val edgeAlpha: Float,
    /** 内描边边缘光强度系数（全轮廓的白+seed 双色描边）。 */
    val edgeLight: Float = 1f,
    /** 顶部渐变光带强度系数（玻璃上沿反射）。 */
    val topLight: Float = 1f
) {
    L1(0.30f, 0.24f, edgeLight = 0.35f, topLight = 0.40f),
    L2(0.38f, 0.30f, edgeLight = 1.15f, topLight = 1.20f),
    L3(0.58f, 0.34f, edgeLight = 1.45f, topLight = 1.50f)
}

/**
 * 玻璃内描边边缘光（Edqiu glassEdgeLight 同构移植）。
 *
 * 投影移除后，卡片的"轮廓存在感"与层级浮起感全部转由材质承担，此修饰符即核心手段。
 * 实现要点：
 * - 纯 Canvas 描边（drawWithContent + Stroke），不涉及任何离屏渲染；
 * - 双色：顶部白高光（环境光反射）+ 底部 seed 色微光（环境色反射）——玻璃边缘"透出彩"；
 * - 描边为内描边（沿形状内侧半宽绘制），不会溢出卡片轮廓；
 * - 光源方向（GlassLightAngle）驱动渐变亮端；色散 > 0 时叠加暖/冷副描边（棱镜色散）。
 */
fun Modifier.glassEdgeLight(
    shape: androidx.compose.ui.graphics.Shape,
    seed: Color,
    whiteTop: Float,
    whiteBottom: Float,
    seedTop: Float,
    seedBottom: Float,
    width: Dp = 1.25.dp,
    lightStrength: Float = 1f,
    lightAngle: Float = -45f,
    lightIntensity: Float = 1f,
    dispersion: Float = 0f
): Modifier = this.drawWithContent {
    drawContent()
    val stroke = width.toPx()
    if (stroke <= 0f) return@drawWithContent
    val ls = lightStrength.coerceAtLeast(0f)
    val li = lightIntensity.coerceIn(0f, 2f)
    val halfInset = stroke / 2f
    // 屏幕坐标 y 向下，θ=-45 须指向左上 → 指向光源的单位向量取 (sin θ, -cos θ)
    val rad = lightAngle * PI.toFloat() / 180f
    val dx = sin(rad)
    val dy = -cos(rad)
    val cx = size.width / 2f
    val cy = size.height / 2f
    val radius = hypot(size.width, size.height) / 2f
    val lightSide = Offset(cx + dx * radius, cy + dy * radius)
    val darkSide = Offset(cx - dx * radius, cy - dy * radius)
    val whiteBrush = Brush.linearGradient(
        start = lightSide, end = darkSide,
        colors = listOf(
            Color.White.copy(alpha = (whiteTop * ls * li).coerceIn(0f, 1f)),
            Color.White.copy(alpha = (whiteBottom * ls * li).coerceIn(0f, 1f))
        )
    )
    val seedBrush = Brush.linearGradient(
        start = lightSide, end = darkSide,
        colors = listOf(
            seed.copy(alpha = (seedTop * ls * li).coerceIn(0f, 1f)),
            seed.copy(alpha = (seedBottom * ls * li).coerceIn(0f, 1f))
        )
    )
    // 内描边：形状尺寸向内缩一个线宽再整体外移半个线宽 → 恰好贴内侧轮廓
    val outline = shape.createOutline(
        size = Size((size.width - stroke).coerceAtLeast(0f), (size.height - stroke).coerceAtLeast(0f)),
        layoutDirection = layoutDirection,
        density = this
    )
    val path = Path()
    when (outline) {
        is androidx.compose.ui.graphics.Outline.Rounded -> path.addRoundRect(outline.roundRect)
        is androidx.compose.ui.graphics.Outline.Rectangle -> path.addRect(outline.rect)
        is androidx.compose.ui.graphics.Outline.Generic -> path.addPath(outline.path)
    }
    val strokeStyle = Stroke(width = stroke)
    translate(left = halfInset, top = halfInset) {
        // 色散副描边：暖色朝光源侧（R 偏折大）、冷色朝背光侧（B 偏折大），
        // alpha ≤ 0.15 极其微妙，只作折射点缀；偏移 1.2×stroke 保证不溢出轮廓
        if (dispersion > 0.01f) {
            val dispAlpha = (0.10f * dispersion * lightIntensity).coerceIn(0f, 1f)
            if (dispAlpha > 0f) {
                translate(left = dx * stroke * 1.2f, top = dy * stroke * 1.2f) {
                    drawPath(path, Color(0xFFFF6B4A).copy(alpha = dispAlpha), style = strokeStyle)
                }
                translate(left = -dx * stroke * 1.2f, top = -dy * stroke * 1.2f) {
                    drawPath(path, Color(0xFF4AC8FF).copy(alpha = dispAlpha), style = strokeStyle)
                }
            }
        }
        // seed 层先画（打底）；白色高光层叠加（光源侧更亮）
        drawPath(path = path, brush = seedBrush, style = strokeStyle)
        drawPath(path = path, brush = whiteBrush, style = strokeStyle)
    }
}

/**
 * 玻璃表面光装饰层（Edqiu GlassSurfaceLights 同构移植，真折射/手绘双路径共用）。
 * 五层：背景渐变折射 → 顶部方向性高光线 → 内高光渐变 → 细镜面高光 → 底部反光。
 * 与背后采样层叠加才是完整玻璃（Apple 结构：采样层 + 表面光层 + 内容层，缺一即「贴膜感」）。
 *
 * @param alphaScale 白色装饰整体缩放。真折射路径背后是 backdrop 真实采样，白装饰 ×0.7
 *   收敛（白叠白）；手绘回退路径背后无采样，×1 保持原观感。
 */
@Composable
private fun BoxScope.GlassSurfaceLights(
    dark: Boolean,
    tier: GlassTier,
    lightAngle: Float,
    lightIntensity: Float,
    lightStrength: Float,
    alphaScale: Float
) {
    // 背景渐变折射：上部亮 + 底部微暗（模拟玻璃曲率）
    Box(
        Modifier
            .matchParentSize()
            .background(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = (((if (dark) 0.03f else 0.10f) * lightIntensity) * alphaScale).coerceIn(0f, 1f)),
                    0.55f to Color.Transparent,
                    1f to Color.Black.copy(alpha = (((if (dark) 0.06f else 0.015f) * lightIntensity) * alphaScale).coerceIn(0f, 1f))
                )
            )
    )
    // 顶部折射高光线（Liquid 灵魂）：沿 lightAngle 方向，亮端锚定光源侧边缘
    Box(
        Modifier
            .matchParentSize()
            .drawBehind {
                val rad = lightAngle * PI.toFloat() / 180f
                val dx = sin(rad)
                val dy = -cos(rad)
                val halfSpan = abs(dx) * size.width / 2f + abs(dy) * size.height / 2f
                val start = Offset(size.width / 2f + dx * halfSpan, size.height / 2f + dy * halfSpan)
                val end = Offset(start.x - dx * 90f, start.y - dy * 90f)
                drawRect(
                    Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = ((tier.edgeAlpha * 0.7f * tier.topLight * lightStrength * lightIntensity) * alphaScale).coerceIn(0f, 1f)),
                            Color.White.copy(alpha = ((tier.edgeAlpha * 0.08f * lightIntensity) * alphaScale).coerceIn(0f, 1f)),
                            Color.Transparent
                        ),
                        start = start,
                        end = end
                    )
                )
            }
    )
    // 内高光描边：顶部亮、底部暗（玻璃边缘光）
    Box(
        Modifier
            .matchParentSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = (((if (dark) 0.06f else 0.08f) * lightIntensity) * alphaScale).coerceIn(0f, 1f)),
                        Color.White.copy(alpha = ((0.03f * lightIntensity) * alphaScale).coerceIn(0f, 1f)),
                        Color.White.copy(alpha = (((if (dark) 0.015f else 0.04f) * lightIntensity) * alphaScale).coerceIn(0f, 1f))
                    ),
                    startY = 0f,
                    endY = 400f
                )
            )
    )
    // iOS 26 液态玻璃增强：细镜面高光（更亮、更窄，模拟玻璃上沿反射）
    Box(
        Modifier
            .matchParentSize()
            .drawBehind {
                val rad = lightAngle * PI.toFloat() / 180f
                val dx = sin(rad)
                val dy = -cos(rad)
                val halfSpan = abs(dx) * size.width / 2f + abs(dy) * size.height / 2f
                val start = Offset(size.width / 2f + dx * halfSpan, size.height / 2f + dy * halfSpan)
                val end = Offset(start.x - dx * 60f, start.y - dy * 60f)
                drawRect(
                    Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = (((if (dark) 0.08f else 0.12f) * tier.topLight * lightStrength * lightIntensity) * alphaScale).coerceIn(0f, 1f)),
                            Color.White.copy(alpha = ((0.015f * lightIntensity) * alphaScale).coerceIn(0f, 1f)),
                            Color.Transparent
                        ),
                        start = start,
                        end = end
                    )
                )
            }
    )
    // 底部微弱反光（玻璃下缘向内容区回弹一点光）
    Box(
        Modifier
            .matchParentSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = ((if (dark) 0.015f else 0.02f) * alphaScale).coerceIn(0f, 1f))
                    )
                )
            )
    )
}

/**
 * 毛玻璃表面组件（Edqiu repo 最新版同构移植）：
 * - 真折射路径（Android 13+ + 舞台 backdrop 可用 + 液态玻璃开）：vibrancy → blur → lens
 *   采样舞台壁纸锐源，表面色画在采样之上（onDrawSurface）；
 * - 手绘回退路径：渐变高光 + API 31+ 真高斯磨砂（8-36dp，Unbounded + 外层 clip 安全结构）；
 * - 液态玻璃关：普通哑光面板。
 *
 * ⚠️ 内容对齐坑（Edqiu 2026-10-20 实机 bug）：内容 Box 默认 TopStart 且不传最小约束，
 * 固定尺寸表层若直接塞 contentAlignment=Center 的内层 Box 会钉在左上角——内层必须
 * `Box(Modifier.fillMaxSize(), contentAlignment = Center)`。
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    tier: GlassTier = GlassTier.L1,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(24.dp),
    tint: Color = MaterialTheme.colorScheme.primaryContainer,
    content: @Composable BoxScope.() -> Unit
) {
    val c = eq()
    val dark = c.isDark
    val liquidGlass = ThemeEffects.LiquidGlassEnabled.current
    val transparency = ThemeEffects.GlassTransparency.current.coerceIn(0f, 1f)
    val frost = ThemeEffects.GlassFrostStrength.current.coerceIn(0f, 1f)
    val edgeWidth = ThemeEffects.GlassEdgeWidth.current.coerceIn(0f, 2.5f)
    val lightStrength = ThemeEffects.GlassEdgeLightStrength.current.coerceIn(0f, 1.6f)
    val dim = ThemeEffects.effectiveDim(ThemeEffects.GlassDimAmount.current, dark)
    val lightAngle = ThemeEffects.GlassLightAngle.current
    val lightIntensity = ThemeEffects.GlassLightIntensity.current.coerceIn(0f, 1f)
    val dispersion = ThemeEffects.GlassDispersion.current.coerceIn(0f, 1f)

    // 玻璃底色 = 半透明白/暗（主体）+ 主题容器色 tint；透明度 t 在 1.3x（实）与 0.55x（透）间缩放
    // 2026-10-25 壁纸舞台灰字可读性（Edqiu 用户实报）：浅色卡面 +0.12 白——灰字需要稳定浅色衬底
    val alphaScale = 1.30f - 0.75f * transparency
    val base = if (dark) {
        Color(0xFF1A1D21).copy(alpha = (tier.bgAlpha + 0.10f) * alphaScale)
    } else {
        Color.White.copy(alpha = (tier.bgAlpha + 0.12f) * alphaScale)
    }
    val glassColor = androidx.compose.ui.graphics.lerp(
        base,
        tint.copy(alpha = if (dark) 0.30f else 0.34f),
        (if (dark) 0.45f else 0.40f) * (1f - frost * 0.4f)
    )
    val edgeSeed = c.primary

    // 真折射路径判定：采样源 = LocalStageBackdrop（壁纸锐源链，链内无卡片，无递归捕获）
    val stageBackdrop = LocalStageBackdrop.current
    val canReal = stageBackdrop != null &&
        android.os.Build.VERSION.SDK_INT >= 33 &&
        liquidGlass &&
        shape is CornerBasedShape
    val realBlur = (4f + 16f * frost).dp
    val realRefraction = ThemeEffects.GlassRefractionStrength.current.coerceIn(0f, 1f)
    val realLensHeight = 18.dp
    // 2026-10-24 卡片边缘强化：位移曲线 0.4+1.6r（默认 r=0.6 → 24.5dp）
    val realLensAmount = 18.dp * (0.4f + 1.6f * realRefraction)

    val m = modifier
        .clip(shape)
        // 真折射表面色由 drawBackdrop 的 onDrawSurface 画在采样之上，不走 .background
        .then(if (canReal) Modifier else Modifier.background(glassColor))
        .glassEdgeLight(
            shape = shape,
            seed = edgeSeed,
            whiteTop = (if (dark) 0.20f else 0.26f) * tier.edgeLight,
            whiteBottom = (if (dark) 0.05f else 0.07f) * tier.edgeLight,
            seedTop = (if (dark) 0.22f else 0.20f) * tier.edgeLight,
            seedBottom = (if (dark) 0.34f else 0.30f) * tier.edgeLight,
            width = edgeWidth.dp,
            lightStrength = lightStrength,
            lightAngle = lightAngle,
            lightIntensity = lightIntensity,
            dispersion = dispersion
        )

    Box(modifier = m) {
        if (canReal) {
            // 真折射采样层：vibrancy → blur → lens 作用于舞台壁纸真实像素
            Box(
                Modifier
                    .matchParentSize()
                    .drawBackdrop(
                        backdrop = stageBackdrop!!,
                        shape = { shape as CornerBasedShape },
                        effects = {
                            vibrancy()
                            blur(realBlur.toPx())
                            lens(
                                realLensHeight.toPx(),
                                realLensAmount.toPx(),
                                chromaticAberration = dispersion > 0f
                            )
                        },
                        onDrawSurface = { drawRect(glassColor) }
                    )
            )
            // 表面光装饰：背后已有真实采样，白装饰 ×0.7 收敛避免白叠白
            GlassSurfaceLights(dark, tier, lightAngle, lightIntensity, lightStrength, alphaScale = 0.7f)
        } else if (liquidGlass) {
            // 手绘回退路径：装饰保持原强度 + 磨砂雾感（渐变之上叠真高斯，Edqiu A 方案）
            GlassSurfaceLights(dark, tier, lightAngle, lightIntensity, lightStrength, alphaScale = 1f)
            if (frost > 0f) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = ((0.05f + 0.13f * frost) * lightIntensity).coerceIn(0f, 1f)),
                                    Color.Transparent,
                                    Color.White.copy(alpha = ((0.02f + 0.05f * frost) * lightIntensity).coerceIn(0f, 1f))
                                ),
                                startY = 0f,
                                endY = 220f
                            )
                        )
                        .then(
                            if (android.os.Build.VERSION.SDK_INT >= 31) {
                                Modifier.blur((8f + 28f * frost).dp, BlurredEdgeTreatment.Unbounded)
                            } else Modifier
                        )
                )
            }
        } else {
            // 液态玻璃关闭：普通哑光面板（透明度 t 与磨砂 f 独立可调）
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh
                            .copy(alpha = 0.96f - 0.41f * transparency)
                    )
            )
            if (frost > 0f) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.04f + 0.12f * frost),
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.01f + 0.05f * frost)
                                ),
                                startY = 0f,
                                endY = 260f
                            )
                        )
                )
            }
        }
        // 压暗程度：叠加在材质层之上、内容之下——只压暗玻璃本身，不影响文字对比度
        if (dim > 0f) {
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = dim)))
        }
        content()
    }
}

/** 玻璃卡片（全 app 通用卡片 = GlassSurface L1 + 可选状态顶条）。 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    topStripe: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassSurface(modifier = modifier, tier = GlassTier.L1, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(top = if (topStripe != null) 5.dp else 0.dp)) { content() }
        if (topStripe != null) {
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(3.dp).background(topStripe))
        }
    }
}

/**
 * L0 背景层 = 「壁纸舞台」（Edqiu GlassBackground 同构移植：双捕获层架构）。
 *
 * - **舞台捕获层**：「壁纸锐源 + scrim + 光斑」（无 blur），经 LocalStageBackdrop
 *   提供给卡片做真折射采样——锐源才有可弯折的明暗与色彩；
 * - **可见背景层**：壁纸 28dp 高斯虚化 + scrim + 光斑，人眼直接看的氛围层；
 * - 无壁纸（自选未设置且系统壁纸读取失败）：落回「中性基底 + 局部光斑团」色场，
 *   无缝降级（Edqiu 色场设计原则：基底永远中性，色彩只出现在局部光斑里）。
 */
@Composable
fun StageBackground(modifier: Modifier = Modifier, blurRadius: Dp = 0.dp, content: @Composable BoxScope.() -> Unit) {
    val c = eq()
    val dark = c.isDark
    val density = LocalDensity.current
    val context = LocalContext.current
    // 壁纸异步加载（IO decode）；version 变更（自选壁纸保存/清除）即强制重载，重载前保留旧图
    val wallpaperVersion by StageWallpaper.version.collectAsState()
    val wallpaper by androidx.compose.runtime.produceState<ImageBitmap?>(
        initialValue = StageWallpaper.current, wallpaperVersion
    ) {
        value = StageWallpaper.load(context, force = wallpaperVersion > 0)
    }

    // 舞台捕获层：链内只有静态壁纸/scrim/光斑，不含卡片 → 无递归捕获
    val stageBackdrop = rememberLayerBackdrop { drawContent() }
    // 可读性 scrim：深色压暗 / 浅色提亮（Edqiu 2026-10-25 定档 0.35/0.34——文字在任意壁纸下可读）
    val scrimColor = if (dark) Color.Black.copy(alpha = 0.34f) else Color.White.copy(alpha = 0.35f)
    // 基底降级：中性面三段渐变（越往下越沉，给底部内容一个稳定的"地面"）
    val baseGradient = Brush.verticalGradient(0f to c.bg, 0.6f to c.bg2, 1f to c.fill)
    CompositionLocalProvider(LocalStageBackdrop provides stageBackdrop) {
        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            val wPx = with(density) { maxWidth.toPx() }
            val hPx = with(density) { maxHeight.toPx() }
            val minPx = minOf(wPx, hPx)
            // 光斑强度分主题：有壁纸时浅色近全强（0.9）、深色减半（0.5）
            val spotScale = if (wallpaper != null) (if (dark) 0.5f else 0.9f) else 1f

            // ── 舞台锐源层：drawBackdrop 采样源（无页面 blur，被可见层完全遮盖，仅作采样用）──
            Box(modifier = Modifier.fillMaxSize().layerBackdrop(stageBackdrop)) {
                if (wallpaper != null) {
                    Image(
                        bitmap = wallpaper!!,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        // 对齐桌面壁纸的顶部锚定（Crop 居中会把主体压到中下部，与桌面所见错位）
                        alignment = Alignment.TopCenter,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(Modifier.fillMaxSize().background(scrimColor))
                } else {
                    Box(Modifier.fillMaxSize().background(baseGradient))
                }
                StageSpots(c, dark, wPx, hPx, minPx, spotScale)
            }
            // ── 可见背景层：壁纸 28dp 高斯虚化铺底（全屏层默认 clamp 正确用法）──
            // blurRadius 作用于本层（柔化光斑边缘），不影响舞台锐源层与前景 content
            Box(Modifier.fillMaxSize().let { if (blurRadius > 0.dp) it.blur(blurRadius) else it }) {
                if (wallpaper != null) {
                    Image(
                        bitmap = wallpaper!!,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                        modifier = Modifier.fillMaxSize().blur(28.dp)
                    )
                    Box(Modifier.fillMaxSize().background(scrimColor))
                } else {
                    Box(Modifier.fillMaxSize().background(baseGradient))
                }
                StageSpots(c, dark, wPx, hPx, minPx, spotScale)
            }
            content()
        }
    }
}

/**
 * 舞台光斑（Edqiu StageSpots 同构移植，舞台/可见双层共用）。
 * 色场设计原则：基底中性，色彩只在局部光斑；每主题一明一暗一对团，
 * 明暗差是玻璃「存在感」的第一来源（Apple 草地演示的核心）。
 */
@Composable
private fun StageSpots(c: EqColors, dark: Boolean, wPx: Float, hPx: Float, minPx: Float, spotScale: Float) {
    // 左上主光斑：primary 饱和团
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(
        colors = listOf(c.primary.copy(alpha = (if (dark) 0.55f else 0.44f) * spotScale), Color.Transparent),
        center = Offset(wPx * 0.15f, hPx * 0.10f), radius = minPx * 0.42f)))
    // 右中次光斑：teal 青，与主光斑对角呼应
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(
        colors = listOf(c.teal.copy(alpha = (if (dark) 0.45f else 0.40f) * spotScale), Color.Transparent),
        center = Offset(wPx * 0.88f, hPx * 0.32f), radius = minPx * 0.38f)))
    // 中部暗影团：深色 = 大黑团、浅色 = 灰影团——制造整屏唯一的大暗部
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(
        colors = listOf((if (dark) Color.Black else Color(0xFF48484A))
            .copy(alpha = (if (dark) 0.55f else 0.22f) * spotScale), Color.Transparent),
        center = Offset(wPx * 0.50f, hPx * 0.54f), radius = minPx * 0.58f)))
    // 左下小高光团：深色白光「物」/浅色主色补光
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(
        colors = listOf((if (dark) Color.White else c.primary)
            .copy(alpha = (if (dark) 0.10f else 0.28f) * spotScale), Color.Transparent),
        center = Offset(wPx * 0.12f, hPx * 0.74f), radius = minPx * 0.30f)))
    // 底部反光团：primary 回弹，给底栏玻璃一点色彩素材
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(
        colors = listOf(c.primary.copy(alpha = (if (dark) 0.20f else 0.22f) * spotScale), Color.Transparent),
        center = Offset(wPx * 0.78f, hPx * 0.90f), radius = minPx * 0.36f)))
}


/**
 * 空状态面板：轻玻璃容器 + 渐变圆标锚点 + 主/副文案 + 可选操作胶囊。
 * 收件箱、任务列表、素材库等空列表统一用它，替代裸文字提示。
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
    val c = eq()
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

/** 区块标题：竖条强调。 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    val c = eq()
    Row(modifier.padding(start = 6.dp, top = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(4.dp, 14.dp).background(c.accent, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = c.text)
    }
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
    val c = eq()
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
    val c = eq()
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
        Box(
            Modifier
                .offset(x = sliderX)
                .size(segW, segH)
                .background(
                    Brush.linearGradient(listOf(c.primaryA, c.primary)),
                    RoundedCornerShape(11.dp)),
        )
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

/** iOS 风格自绘开关：spring 弹性位移 + 轨道颜色渐变 + 按压缩放反馈。 */
@Composable
fun IosSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = eq()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val w = 50.dp; val h = 30.dp; val thumb = 26.dp; val pad = 2.dp
    val trans = androidx.compose.animation.core.updateTransition(targetState = checked, label = "iossw")
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
    val c = eq()
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

/** 玻璃按钮：Primary=主色渐变主操作；Ghost=玻璃底次级操作。 */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    kind: ButtonKind = ButtonKind.Primary,
) {
    val c = eq()
    val shape = RoundedCornerShape(15.dp)
    val alpha = if (enabled) 1f else 0.4f
    val bg = if (kind == ButtonKind.Primary)
        Brush.linearGradient(listOf(
            c.primaryA.copy(alpha = alpha), c.primary.copy(alpha = alpha), c.primaryDeep.copy(alpha = alpha)))
    else Brush.linearGradient(
        if (c.isDark) listOf(Color(0x1CFFFFFF).copy(alpha = alpha), Color(0x0EFFFFFF).copy(alpha = alpha))
        else listOf(Color(0x20FFFFFF).copy(alpha = alpha), Color(0x14FFFFFF).copy(alpha = alpha)))
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
    val c = eq()
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

/** 流光进度条：渐变填充 + 移动光斑；pct=null 时为不确定态（扫描动画）。 */
@Composable
fun IosProgress(pct: Float?, modifier: Modifier = Modifier, color: Color? = null) {
    val c = eq()
    val base = color ?: c.primary
    val trans = androidx.compose.animation.core.rememberInfiniteTransition(label = "pg")
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
            val segFrac = 0.34f
            Box(Modifier.fillMaxWidth(segFrac).fillMaxHeight()
                .offset { IntOffset((sweep * (1f - segFrac) * pxW).roundToInt(), 0) }
                .background(Brush.horizontalGradient(
                    listOf(base.copy(alpha = 0.35f), base, base.copy(alpha = 0.35f)),
                    startX = 0f, endX = with(density) { 140.dp.toPx() })))
        }
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
    val c = eq()
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

// ---------------- 液态玻璃 Tab 栏（Edqiu LiquidTabBar 移植） ----------------

/** 底栏图标类型（自绘矢量：圆头描边语言）。 */
enum class TabIconType { Download, Library, Globe, Gear }

/** 底栏矢量图标：24 单位视口，2.0 圆头描边。 */
@Composable
fun TabIcon(type: TabIconType, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(21.dp)) {
        val s = size.minDimension / 24f
        scale(s, pivot = Offset.Zero) {
            val stroke = Stroke(width = 2.0f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            when (type) {
                TabIconType.Download -> {
                    // 收件筐：左侧竖壁 + 顶部 + 右侧圆角 + 底部开口（Edqiu E 化收件筐语言）
                    drawPath(Path().apply {
                        moveTo(5f, 4f)
                        lineTo(16f, 4f)
                        quadraticTo(19f, 4f, 19f, 7f)
                        lineTo(19f, 13f)
                        quadraticTo(19f, 16f, 16f, 16f)
                        lineTo(9f, 16f)
                    }, color, style = stroke)
                    // 内部层板两道
                    drawLine(color, Offset(5f, 9f), Offset(13f, 9f),
                        strokeWidth = 2.0f, cap = StrokeCap.Round)
                    drawLine(color, Offset(5f, 13.5f), Offset(15f, 13.5f),
                        strokeWidth = 2.0f, cap = StrokeCap.Round)
                    // 左竖壁延伸到开口
                    drawLine(color, Offset(5f, 4f), Offset(5f, 16f),
                        strokeWidth = 2.0f, cap = StrokeCap.Round)
                    // 放入箭头（筐口向下）
                    drawPath(Path().apply {
                        moveTo(15.5f, 18.5f)
                        lineTo(17.5f, 20.5f)
                        lineTo(21f, 16.5f)
                    }, color, style = stroke)
                }
                TabIconType.Library -> {
                    // 照片框：圆角矩形 + 太阳 + 山形（媒体墙语义）
                    drawPath(Path().apply {
                        moveTo(8f, 4f)
                        lineTo(17f, 4f)
                        quadraticTo(20f, 4f, 20f, 7f)
                        lineTo(20f, 14f)
                        quadraticTo(20f, 17f, 17f, 17f)
                        lineTo(8f, 17f)
                        quadraticTo(5f, 17f, 5f, 14f)
                        lineTo(5f, 7f)
                        quadraticTo(5f, 4f, 8f, 4f)
                        close()
                    }, color, style = stroke)
                    drawCircle(color, radius = 1.6f, center = Offset(9.6f, 8.6f), style = stroke)
                    drawPath(Path().apply {
                        moveTo(5.6f, 14.6f)
                        lineTo(9.4f, 11.2f)
                        lineTo(13f, 14.4f)
                        lineTo(15.6f, 12.2f)
                        lineTo(20f, 15.8f)
                    }, color, style = stroke)
                }
                TabIconType.Globe -> {
                    // 地球：外圆 + 赤道线 + 经线椭圆（代理=网络中转）
                    val o = Offset(12f, 12f)
                    drawCircle(color, radius = 8.2f, center = o, style = stroke)
                    drawLine(color, Offset(3.8f, 12f), Offset(20.2f, 12f),
                        strokeWidth = 2.0f, cap = StrokeCap.Round)
                    drawOval(color, topLeft = Offset(8.2f, 3.8f),
                        size = Size(7.6f, 16.4f), style = stroke)
                }
                TabIconType.Gear -> {
                    // 滑杆（设置）：三道横杆 + 错位圆钮，比齿轮更轻盈现代
                    drawLine(color, Offset(4.5f, 7f), Offset(19.5f, 7f),
                        strokeWidth = 2.0f, cap = StrokeCap.Round)
                    drawLine(color, Offset(4.5f, 12f), Offset(19.5f, 12f),
                        strokeWidth = 2.0f, cap = StrokeCap.Round)
                    drawLine(color, Offset(4.5f, 17f), Offset(19.5f, 17f),
                        strokeWidth = 2.0f, cap = StrokeCap.Round)
                    drawCircle(color, radius = 2.3f, center = Offset(15.2f, 7f), style = stroke)
                    drawCircle(color, radius = 2.3f, center = Offset(8.6f, 12f), style = stroke)
                    drawCircle(color, radius = 2.3f, center = Offset(16.6f, 17f), style = stroke)
                }
            }
        }
    }
}

/**
 * 悬浮液态玻璃 Tab 栏（Edqiu LiquidTabBar 同款）：
 * 高 50dp stadium 玻璃外壳，选中胶囊随按压缩放滑动、圆角贴边变形（16→22dp），
 * 文字仅选中项出现（9sp Bold），图标 21dp 选中放大 1.12。
 */
@Composable
fun GlassTabBar(
    labels: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    badgeCount: Int = 0,
    onSelect: (Int) -> Unit,
) {
    val c = eq()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .padding(horizontal = 14.dp)
    ) {
        val barWidth = maxWidth
        val itemWidth = barWidth / labels.size

        var pressedIndex by remember { mutableStateOf(-1) }
        val capsuleIndex = if (pressedIndex >= 0) pressedIndex else selected
        val capsuleTargetX = itemWidth * capsuleIndex
        val capsuleX by animateFloatAsState(
            targetValue = capsuleTargetX.value,
            animationSpec = if (pressedIndex >= 0) tween(50)
            else spring(dampingRatio = 0.8f, stiffness = 260f),
            label = "capsuleX")

        // 选中胶囊圆角动态贴合外壳内壁（滑到两端时 16→22dp stadium，弧线同心）
        val tabCount = labels.size
        val barCenterX = itemWidth.value * tabCount / 2f
        val capsuleCenterX = capsuleX + itemWidth.value / 2f
        val halfSpan = (barCenterX - itemWidth.value / 2f).coerceAtLeast(1f)
        val edgeT = abs(capsuleCenterX - barCenterX) / halfSpan
        val capsuleRadius by animateFloatAsState(
            targetValue = 16f + edgeT.coerceIn(0f, 1f) * 6f,
            animationSpec = spring(dampingRatio = 0.85f, stiffness = 600f),
            label = "capsuleRadius")

        // 玻璃外壳（Edqiu 底栏定版参数）：Backdrop 真折射 vibrancy+blur+lens，
        // 透出并折射正下方滚动的页面内容；<33/关闭时回退渐变模拟玻璃。
        // 固定 8dp 轻磨砂——全局 0.6 档会把滚入底栏的文字糊成色块（透色不透字）；
        // 无描边无白线（用户反馈定版），透光 boost 0.5 内容从底栏下穿透可辨
        GlassOverlaySurface(
            shape = RoundedCornerShape(25.dp),
            style = OverlayGlassStyle.Light,
            blurRadius = 8.dp,
            lensHeight = 14.dp,
            lensAmount = 16.dp,
            transparencyBoost = 0.5f,
            showEdgeAccent = false,
            showSheen = false,
            modifier = Modifier.fillMaxSize(),
        ) { }

        // 选中胶囊：primary 低透明色洗（2026-10-24 按钮透明可观重做）——
        // 内容从胶囊下穿透可辨，选中态由主题色 tint（图标/文字转 primary）承担
        Box(
            modifier = Modifier
                .offset(x = capsuleX.dp)
                .width(itemWidth)
                .fillMaxHeight()
                .padding(horizontal = 3.dp, vertical = 3.dp)
                .clip(RoundedCornerShape(capsuleRadius.dp))
                .background(c.primary.copy(alpha = if (c.isDark) 0.38f else 0.28f)),
        )

        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            labels.forEachIndexed { index, label ->
                val on = index == selected
                val iconScale by animateFloatAsState(
                    targetValue = if (on) 1.12f else 1f,
                    animationSpec = spring(dampingRatio = 0.7f, stiffness = 900f),
                    label = "scale$index")
                val iconColor by animateColorAsState(
                    if (on) c.primary else c.faint, tween(200), label = "tabIc")
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .semantics { role = Role.Tab }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onSelect(index) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.graphicsLayer { scaleX = iconScale; scaleY = iconScale },
                    ) {
                        TabIcon(TabIconType.entries[index], iconColor)
                        AnimatedVisibility(
                            visible = on,
                            enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 3 },
                            exit = fadeOut(tween(100)) + slideOutVertically(tween(100)) { it / 3 },
                        ) {
                            Text(
                                text = label,
                                color = c.primary,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                lineHeight = 11.sp,
                                modifier = Modifier.padding(top = 1.dp),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }

        if (badgeCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = 2.dp)
                    .size(18.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color(0xFFFF3B30)),
                contentAlignment = Alignment.Center,
            ) {
                Text(badgeCount.toString(), color = Color.White,
                    fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
