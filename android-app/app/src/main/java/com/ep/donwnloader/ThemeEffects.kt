package com.ep.donwnloader

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 主题效果全局状态（由 MainActivity 顶层注入；Edqiu ThemeEffects 同构移植，M11）。
 *
 * 通过 CompositionLocal 而非层层传参，让任意层级的玻璃组件（GlassSurface、
 * GlassTabBar、弹窗遮罩等）都能读取到统一的玻璃参数，保证「全局生效、实时跟随、彼此协同」。
 *
 * 当前版本默认值 = Edqiu v1.8/1.9 调优后的推荐值（设置页滑杆后续按需开放）。
 * 光学三参数对齐 Pixso《液态玻璃教程》：光源角度 -45° / 亮度 0.8 / 色散 0.5。
 */
object ThemeEffects {

    /** 玻璃透明度 0-1（0=实、1=透）：真玻璃表面色 alpha、卡片底色、普通面板 alpha。 */
    val GlassTransparency = staticCompositionLocalOf { 0.6f }

    /** 磨砂强度 0-1：真玻璃 blur（4-20dp）、卡片磨砂雾感、背景柔化半径。 */
    val GlassFrostStrength = staticCompositionLocalOf { 0.6f }

    /** 折射强度 0-1：真玻璃边缘 lens 位移 0.4x-2.0x（仅液态玻璃模式生效）。 */
    val GlassRefractionStrength = staticCompositionLocalOf { 0.6f }

    /** 液态玻璃全局开关。true = 真折射玻璃质感；false = 普通哑光面板。 */
    val LiquidGlassEnabled = staticCompositionLocalOf { true }

    /** 描边粗细（dp），0 = 不描边（glassEdgeLight 内描边线宽）。 */
    val GlassEdgeWidth = staticCompositionLocalOf { 1.25f }

    /** 亮边强度倍数 0-1.6，1 = Edqiu 观感，0 = 熄灭全部高光。 */
    val GlassEdgeLightStrength = staticCompositionLocalOf { 1f }

    /** 压暗程度 0-1：在玻璃材质层叠加黑色（深色模式自动 ×1.5 补偿），0 = 不压暗。 */
    val GlassDimAmount = staticCompositionLocalOf { 0f }

    /** 光源角度（度，数学惯例）：-45 = 左上（Apple/教程默认）。方向性高光的灵魂。 */
    val GlassLightAngle = staticCompositionLocalOf { -45f }

    /** 光源亮度 0-1（教程默认 0.8）：等比缩放全部高光 alpha。 */
    val GlassLightIntensity = staticCompositionLocalOf { 0.8f }

    /** 色散强度 0-1（教程默认 0.5）：边缘彩虹色差——暖/冷副描边 + lens 七光谱采样。 */
    val GlassDispersion = staticCompositionLocalOf { 0.5f }

    /** 深色模式下的压暗补偿系数（iOS 深色阴影加倍规律的材质等价实现）。 */
    const val DARK_DIM_COMPENSATION = 1.5f

    /** 计算最终生效的压暗量：深色模式下自动放大，浅色模式原样。 */
    fun effectiveDim(dim: Float, dark: Boolean): Float {
        val v = dim.coerceIn(0f, 1f)
        return (if (dark) v * DARK_DIM_COMPENSATION else v).coerceIn(0f, 1f)
    }
}
