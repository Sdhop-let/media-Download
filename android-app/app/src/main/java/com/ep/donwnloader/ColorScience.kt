package com.ep.donwnloader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import io.material.color.utilities.hct.Hct
import io.material.color.utilities.palettes.TonalPalette
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 色彩科学桥（Color Output Spec 2025 基座）。
 *
 * 内嵌 material-color-utilities（Google 官方取色库，MIT，Android 12+ 系统莫奈同源）：
 * CAM16 / HCT / TonalPalette，位于 io.material.color.utilities 包。
 *
 * 设计原则（保证「取色与应用色差相对值不漂移」）：
 *  1. 单一真源：系统 dynamicColorScheme 给的角色原样使用，不二次派生；
 *  2. 同构派生：自定义令牌从 primary 所在的 TonalPalette 派生——tonal palette
 *     同 hue 定理保证派生色与系统色的色相严格一致，tone 沿规范档位移动；
 *  3. 同空间运算：所有插值/位移在 HCT 空间完成，禁止 sRGB lerp
 *     （sRGB 插值会掉 chroma 且 hue 漂移，黄色系壁纸下尤其明显）；
 *  4. 对比度守恒：关键前景/背景对做 WCAG 校验，不达标自动拉 tone 档。
 */
object ColorScience {

    // ---------- 基础转换 ----------

    fun hct(c: Color): Hct = Hct.fromInt(c.toArgb())

    fun color(h: Hct): Color = Color(h.toInt())

    /** ARGB int → Compose Color（TonalPalette.tone(n) 返回值直接用）。 */
    fun color(argb: Int): Color = Color(argb)

    /** 种子色的 tonal palette（hue/chroma 取自种子，tone 全档可取）。 */
    fun paletteOf(seed: Color): TonalPalette = TonalPalette.fromInt(seed.toArgb())

    /** 从 palette 取任意 tone 档（支持小数 tone，内部取整到 0-100 整数档）。 */
    fun toneAt(p: TonalPalette, tone: Double): Color = color(p.tone(tone.roundToInt()))

    /**
     * 低彩度中性 palette（spec 2025：neutral chroma ≈ 4-8）。
     * strength 用于背景系（full 作用域）相对 fill 系微调彩度权重。
     */
    fun neutralPaletteOf(seed: Color, strength: Double = 1.0): TonalPalette {
        val p = hct(seed)
        val chroma = (p.chroma * 0.30 * strength).coerceIn(2.0, 8.0)
        return TonalPalette.fromHueAndChroma(p.hue, chroma)
    }

    /**
     * HCT 空间插值：hue 走最短弧，tone/chroma 线性。
     * 替代 sRGB lerp——等 hue 路径不漂移，饱和色不会插灰。
     */
    fun lerpHct(a: Color, b: Color, t: Float): Color {
        if (t <= 0f) return a
        if (t >= 1f) return b
        val ha = hct(a); val hb = hct(b)
        var dh = hb.hue - ha.hue
        if (dh > 180.0) dh -= 360.0 else if (dh < -180.0) dh += 360.0
        val hue = (ha.hue + dh * t + 360.0) % 360.0
        val chroma = ha.chroma + (hb.chroma - ha.chroma) * t
        val tone = ha.tone + (hb.tone - ha.tone) * t
        return color(Hct.from(hue, chroma, tone))
    }

    // ---------- 对比度守恒 ----------

    /** WCAG 对比度（>=4.5 文本级，>=3.0 图形级）。 */
    fun contrast(a: Color, b: Color): Float {
        val la = a.luminance(); val lb = b.luminance()
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }

    /**
     * 前景对背景的对比度守恒：不达标时沿 palette 拉 tone 档。
     * M3 spec 的 tone40(浅)/tone80(深) 数学上保证对白/黑 >=4.5，
     * 此函数兜底极端种子色（用户手输 / 壁纸 seed 异常）。
     */
    fun ensureContrast(fg: Color, bg: Color, palette: TonalPalette, isDark: Boolean): Color {
        if (contrast(fg, bg) >= 3.0f) return fg
        val step = if (isDark) -6 else 6
        var tone = hct(fg).tone
        for (i in 1..8) {
            val t = (tone + step * i).roundToInt().coerceIn(0, 100)
            val cand = color(palette.tone(t))
            if (contrast(cand, bg) >= 3.0f) return cand
        }
        return fg
    }
}
