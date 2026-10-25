package com.ep.donwnloader

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 全局液态玻璃背景（Kyant0/AndroidLiquidGlass "Backdrop" 库驱动；Edqiu LiquidGlass 同构移植，M11）。
 *
 * 本 App 只有一条页面捕获链：MainActivity 把页面内容 Box 用 layerBackdrop 捕获为
 * [LocalAppBackdrop]，底栏在其节点之外采样它——折射「正下方滚动的页面内容」。
 * 卡片等捕获链内元素采样 [LocalStageBackdrop]（壁纸舞台锐源，链内无卡片，无递归）。
 *
 * 玻璃组件必须位于捕获链（layerBackdrop 节点）之外，否则会把上一帧自己的绘制
 * 采样回来造成 alpha 逐帧累积（玻璃逐步变实色）；链内元素采样「包含自己的链」
 * 更会递归捕获 → RenderThread SIGSEGV（Edqiu 2026-10-22 两次实机判决）。
 */
val LocalAppBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * 舞台 backdrop（Edqiu 2026-10-24「独立舞台架构」）：捕获壁纸舞台的「壁纸锐源 +
 * scrim + 光斑」层（无页面 blur）。
 *
 * 存在意义：卡片是捕获链内元素，不能采样 LocalAppBackdrop（链内含卡片自身 → 递归
 * 捕获 → SIGSEGV）；但它们采样舞台链是安全的——舞台链内只有静态壁纸，不含任何卡片。
 * 锐源（不经页面虚化）是关键：折射要弯折「可辨识内容」才有液态感，采预模糊层则无素材可弯。
 * 弹窗（独立窗口）取不到 → null → 自动回退程序化玻璃。
 */
val LocalStageBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** 悬浮玻璃明暗风格。Dark 对应媒体/胶囊场景的深色玻璃语义。 */
enum class OverlayGlassStyle { Light, Dark }

/**
 * 当前是否可走 Backdrop 真折射路径（Android 13+ 且液态玻璃开启且 backdrop 可用）。
 */
@Composable
fun rememberRealGlassAvailable(): Boolean {
    val backdrop = LocalAppBackdrop.current
    val liquidGlass = ThemeEffects.LiquidGlassEnabled.current
    return backdrop != null && liquidGlass && Build.VERSION.SDK_INT >= 33
}

/**
 * 真液态玻璃悬浮层（iOS 26 Liquid Glass 质感，Backdrop 真折射；Edqiu 同名组件移植）。
 *
 * 效果链（库文档规定顺序）：vibrancy（饱和度增强）→ blur（磨砂）→ lens（边缘折射，Android 13+）。
 * Android 13+ 且液态玻璃开启且 backdrop 可用时走真折射；否则回退：
 * - [OverlayGlassStyle.Light] → [GlassSurface] 渐变模拟玻璃（L2）
 * - [OverlayGlassStyle.Dark] → 深色玻璃（黑 60% + 高光描边，媒体控制层语义）
 *
 * @param shape 必须 CornerBasedShape（lens 折射要求）
 * @param blurRadius 磨砂强度；传 null（默认）时按全局磨砂强度取值（4-20dp）
 * @param lensHeight 边缘折射带高度，需 ≤ 最小圆角半径
 * @param lensAmount 折射位移量
 * @param transparencyBoost 在全局透明度滑块基础上额外拉高有效 t（底栏透字用）
 * @param showEdgeAccent 外圈暖/冷色散描边开关（底栏传 false——用户反馈底栏不要描边）
 * @param showSheen 顶光白洗开关（底栏传 false——tab 栏边缘不要白线）
 */
@Composable
fun GlassOverlaySurface(
    modifier: Modifier = Modifier,
    shape: CornerBasedShape = RoundedCornerShape(24.dp),
    style: OverlayGlassStyle = OverlayGlassStyle.Light,
    blurRadius: Dp? = null,
    lensHeight: Dp = 18.dp,
    lensAmount: Dp = 18.dp,
    transparencyBoost: Float = 0f,
    showEdgeAccent: Boolean = true,
    showSheen: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    val backdrop = LocalAppBackdrop.current
    val liquidGlass = ThemeEffects.LiquidGlassEnabled.current
    val effectiveBlur = blurRadius ?: ((4f + 16f * ThemeEffects.GlassFrostStrength.current).dp)
    // Android 13+（RuntimeShader/AGSL 折射）；31/32 设备走回退——回退保留渐变模拟是
    // 兼容与性能保守选择，非 OS 限制（「ColorOS 白框=blur 修饰符」归因 2026-10-22 已证伪）
    val canReal = backdrop != null && liquidGlass && Build.VERSION.SDK_INT >= 33

    // 2026-09-21 投影已整体移除：原 12dp shadow 在玻璃透明后会从背后透出成"长方形色块"。
    if (canReal) {
        RealGlassOverlay(
            modifier = modifier,
            shape = shape,
            style = style,
            blurRadius = effectiveBlur,
            lensHeight = lensHeight,
            lensAmount = lensAmount,
            transparencyBoost = transparencyBoost,
            showEdgeAccent = showEdgeAccent,
            showSheen = showSheen,
            backdrop = backdrop!!,
            content = content
        )
    } else {
        FallbackGlassOverlay(
            modifier = modifier,
            shape = shape,
            style = style,
            content = content
        )
    }
}

/** 真折射路径：AGSL 折射 + RenderEffect 磨砂 + 主题色表面。 */
@Composable
private fun RealGlassOverlay(
    modifier: Modifier,
    shape: CornerBasedShape,
    style: OverlayGlassStyle,
    blurRadius: Dp,
    lensHeight: Dp,
    lensAmount: Dp,
    transparencyBoost: Float,
    showEdgeAccent: Boolean,
    showSheen: Boolean,
    backdrop: Backdrop,
    content: @Composable BoxScope.() -> Unit
) {
    val darkTheme = MaterialTheme.colorScheme.background.luminance() <= 0.5f
    // 三参数独立：透明度 t 控表面色 alpha，折射 r 控边缘位移，磨砂由 blurRadius 承载
    val t = (ThemeEffects.GlassTransparency.current.coerceIn(0f, 1f) + transparencyBoost)
        .coerceIn(0f, 1f)
    val r = ThemeEffects.GlassRefractionStrength.current.coerceIn(0f, 1f)
    val dim = ThemeEffects.effectiveDim(ThemeEffects.GlassDimAmount.current, darkTheme)
    val lightStrength = ThemeEffects.GlassEdgeLightStrength.current.coerceIn(0f, 1.6f)
    val lightIntensity = ThemeEffects.GlassLightIntensity.current.coerceIn(0f, 1f)
    val dispersion = ThemeEffects.GlassDispersion.current.coerceIn(0f, 1f)
    val lightAngle = ThemeEffects.GlassLightAngle.current
    // 指向光源的单位向量（屏幕 y 向下）：θ=-45° → (-0.707, -0.707) 指向左上 ✓
    val rad = (lightAngle * PI / 180.0).toFloat()
    val lightDx = -cos(rad)
    val lightDy = sin(rad)
    // 表面色（透明度：0=实 0.58，1=透 0.16；Dark 风格保持可读下限）
    val surface = when (style) {
        OverlayGlassStyle.Light ->
            if (darkTheme) Color(0xFF1A1D21).copy(alpha = 0.62f - 0.36f * t)
            else Color.White.copy(alpha = 0.58f - 0.42f * t)
        OverlayGlassStyle.Dark -> Color(0xFF14181C).copy(alpha = 0.80f - 0.28f * t)
    }
    // 压暗：只压 RGB、保留 alpha——保持半透明语义，避免把「透」压成「实」
    val drawnSurface = if (dim > 0f) {
        Color(
            red = surface.red * (1f - dim),
            green = surface.green * (1f - dim),
            blue = surface.blue * (1f - dim),
            alpha = surface.alpha
        )
    } else surface

    // 顺序：clip（内容裁剪）→ drawBackdrop（按 shape 绘制折射层）；投影已移除
    val m = modifier.clip(shape)
    val dispersionOn = ThemeEffects.GlassDispersion.current > 0f

    Box(
        modifier = m.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                // Kyant0 iOS 26 复刻效果链（顺序为库文档规定）：饱和度提升 → 磨砂 → 边缘折射
                vibrancy()
                blur(blurRadius.toPx())
                lens(
                    lensHeight.toPx(),
                    lensAmount.toPx() * (0.2f + 1.4f * r),
                    chromaticAberration = dispersionOn
                )
            },
            onDrawSurface = { drawRect(drawnSurface) }
        )
    ) {
        // 顶部窄镜面高光：沿光源方向的 linearGradient（亮端贴光源侧），
        // background 拿不到 size → drawBehind 在 DrawScope 内现场构造 Brush
        if (showSheen) {
            Box(
                Modifier
                    .matchParentSize()
                    .drawBehind {
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val l = 60f
                        val topAlpha = ((if (style == OverlayGlassStyle.Dark) 0.14f else 0.10f) *
                            lightStrength * lightIntensity).coerceIn(0f, 1f)
                        val midAlpha = (0.02f * lightStrength * lightIntensity).coerceIn(0f, 1f)
                        val brush = Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = topAlpha),
                                Color.White.copy(alpha = midAlpha),
                                Color.Transparent
                            ),
                            start = Offset(cx + lightDx * l, cy + lightDy * l),
                            end = Offset(cx - lightDx * l, cy - lightDy * l)
                        )
                        drawRect(brush)
                    }
            )
        }
        // 色散内描边：棱镜色散模拟——暖/冷双层描边沿轮廓 ±1.5px 沿光源方向偏移
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    if (showEdgeAccent && dispersion > 0f) {
                        val outline = shape.createOutline(size, layoutDirection, this)
                        val outlinePath = when (outline) {
                            is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
                            is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
                            is Outline.Generic -> outline.path
                        }
                        val stroke = Stroke(width = 1.5.dp.toPx())
                        val warmAlpha = (0.09f * dispersion).coerceIn(0f, 1f)
                        translate(left = lightDx * 1.5f, top = lightDy * 1.5f) {
                            drawPath(
                                outlinePath,
                                Color(0xFFFF6B4A).copy(alpha = warmAlpha),
                                style = stroke
                            )
                        }
                        translate(left = -lightDx * 1.5f, top = -lightDy * 1.5f) {
                            drawPath(
                                outlinePath,
                                Color(0xFF4AC8FF).copy(alpha = warmAlpha),
                                style = stroke
                            )
                        }
                    }
                }
        )
        content()
    }
}

/** 回退路径：非 Android 13+ / 玻璃开关关闭 / backdrop 不可用。 */
@Composable
private fun FallbackGlassOverlay(
    modifier: Modifier,
    shape: CornerBasedShape,
    style: OverlayGlassStyle,
    content: @Composable BoxScope.() -> Unit
) {
    when (style) {
        OverlayGlassStyle.Light ->
            GlassSurface(
                modifier = modifier,
                tier = GlassTier.L2,
                shape = shape,
                content = content
            )
        OverlayGlassStyle.Dark -> FallbackDarkGlassSurface(
            modifier = modifier,
            shape = shape,
            content = content
        )
    }
}

/** 深色玻璃回退：黑 60% + 顶部高光 + 内描边（媒体控制层语义，投影已移除）。 */
@Composable
private fun FallbackDarkGlassSurface(
    modifier: Modifier,
    shape: CornerBasedShape,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier = modifier.clip(shape).background(Color.Black.copy(alpha = 0.60f))) {
        Box(
            Modifier
                .matchParentSize()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.18f),
                            Color.White.copy(alpha = 0.04f),
                            Color.Transparent
                        ),
                        startY = 0f,
                        endY = 120f
                    )
                )
        )
        Box(
            Modifier
                .matchParentSize()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.22f),
                            Color.White.copy(alpha = 0.06f),
                            Color.White.copy(alpha = 0.04f)
                        ),
                        startY = 0f,
                        endY = 60f
                    )
                )
        )
        content()
    }
}
