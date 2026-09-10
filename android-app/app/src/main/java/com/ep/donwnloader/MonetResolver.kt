package com.ep.donwnloader

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.util.Log
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.material.color.utilities.hct.Hct
import io.material.color.utilities.palettes.TonalPalette
import io.material.color.utilities.quantize.QuantizerCelebi
import io.material.color.utilities.score.Score
import java.io.FileInputStream
import kotlin.math.abs
import kotlin.math.max

/** 壁纸取色解析结果。 */
class MonetResolved(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    /** 系统 dynamicColorScheme；仅当直接采用系统值时非 null。 */
    val sysScheme: androidx.compose.material3.ColorScheme?,
    val source: String,
)

/** 多点位采样产出的三个种子色（primary/secondary/tertiary 各用独立 seed，ARGB）。 */
class Seeds(val p: Int, val s: Int, val t: Int)

/** 持久化取色缓存（重启首帧即时取色，免重新解码/量化）。 */
class CachedSeeds(val wpId: Int, val p: Int, val s: Int, val t: Int)

/**
 * 莫奈取色解析器（多点位采样版）。
 *
 * 背景（真机实证）：ColorOS 16 壁纸走 OPPO ColorfulEngine（live wallpaper service），
 * - getWallpaperColors 对引擎壁纸不可靠（可能 null/陈旧，引擎未实现 onComputeColors）；
 * - system_accent overlay 对第三方运行中进程滞后/不更新 → 换壁纸 App 不变色；
 * - dynamicColorScheme(ctx) 在 Android 15+ 依赖系统已生成的 wallpaper color cache，
 *   重启后缓存未就绪时返回默认值 → 莫奈取色"重启后失效"；
 * - getWallpaperFile 在 Android 14+ 需 MANAGE_EXTERNAL_STORAGE（所有文件访问），
 *   绝大多数用户不授权 → 读原图路径实际不可用。
 *
 * 取色链路（2026-09-09 重构，模拟器 Android 15 实测修正）：
 *  0. 快路径（主线程首帧）：持久化缓存（wallpaperId 未变）→ 三 seed 直接派生，零解码零量化，
 *     重启后立即恢复莫奈取色；无缓存才走系统兜底。
 *  1. 深路径（IO 线程）分两档：
 *     a. 有 MANAGE_EXTERNAL_STORAGE（所有文件访问）→ getWallpaperFile 原图 → 全局 Celebi 量化 +
 *        Score 多候选 × 4×4 分块主导色（多点位采样）→ HCT 鲜活度过滤 → 120° 均分窗口选三 seed；
 *     b. 无权限（默认）→ getWallpaperColors() 三主色（Android 12+ 原生免权限，系统自身的多点
 *        采样结果）直接作为三 seed。壁纸位图对应用受系统保护，getDrawable() 实测抛
 *        READ_EXTERNAL_STORAGE denied，非免权限，故不再使用。
 *  2. 兜底：系统 dynamicColorScheme 原值（最差情形）。
 */
object MonetResolver {
    private const val TAG = "MonetEpoch"
    private const val PREFS = "monet_seeds"

    // ---------- 持久化缓存（重启后首帧立即取色） ----------

    private fun readCache(ctx: Context): CachedSeeds? {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!sp.contains("seed_p")) return null
        return CachedSeeds(
            sp.getInt("wp_id", -1),
            sp.getInt("seed_p", 0), sp.getInt("seed_s", 0), sp.getInt("seed_t", 0))
    }

    private fun writeCache(ctx: Context, wpId: Int, s: Seeds) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("wp_id", wpId)
            .putInt("seed_p", s.p).putInt("seed_s", s.s).putInt("seed_t", s.t)
            .putLong("saved_at", System.currentTimeMillis()).apply()
    }

    private fun wallpaperId(wm: WallpaperManager): Int =
        try { wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM) } catch (e: Exception) { -1 }

    private fun hueDeltaDeg(a: Double, b: Double): Double {
        var d = abs(a - b) % 360.0
        if (d > 180.0) d = 360.0 - d
        return d
    }

    /** 快路径（主线程安全，重启后首帧即有色）：
     *  1. 持久化缓存（壁纸未换）→ 三 seed 派生，零解码零量化，重启后立即莫奈取色；
     *  2. WallpaperColors 三主色 → 三 seed 派生；
     *  3. 系统 dynamicColorScheme 原值（最差情形 = 旧版行为）。 */
    fun quickResolve(ctx: Context, dark: Boolean): MonetResolved {
        val wm = WallpaperManager.getInstance(ctx)
        val curId = wallpaperId(wm)
        val cache = readCache(ctx)
        if (cache != null && (cache.wpId == curId || cache.wpId == -1 || curId == -1)) {
            Log.i(TAG, "quick source=cache p=${hex(cache.p)} s=${hex(cache.s)} t=${hex(cache.t)} (id=$curId)")
            return deriveMultiSeed(Seeds(cache.p, cache.s, cache.t), dark, cache.wpId, "cache")
        }
        val wc = try { wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM) } catch (e: Exception) { null }
        if (wc != null) {
            val p: android.graphics.Color? = wc.primaryColor
            val s: android.graphics.Color? = wc.secondaryColor
            val t: android.graphics.Color? = wc.tertiaryColor
            if (p != null && s != null && t != null) {
                Log.i(TAG, "quick source=wallpaper-colors3 p=${hex(p.toArgb())} s=${hex(s.toArgb())} t=${hex(t.toArgb())}")
                return deriveMultiSeed(Seeds(p.toArgb(), s.toArgb(), t.toArgb()), dark, curId, "wallpaper-colors")
            }
            // 仅 primary 可用：与系统 primary 实况 hue 偏差 > 20° 时改用 WallpaperColors 直取
            val single: android.graphics.Color? = wc.primaryColor
            if (single != null) {
                val sys = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
                val wcH = ColorScience.hct(Color(single.toArgb()))
                var gap = abs(ColorScience.hct(sys.primary).hue - wcH.hue)
                if (gap > 180.0) gap = 360.0 - gap
                if (gap > 20.0 && wcH.chroma > 8.0) {
                    Log.i(TAG, "quick source=wallpaper-colors1 hueGap=${"%.1f".format(gap)}")
                    return deriveSingleSeed(single.toArgb(), dark, curId, "wallpaper-colors")
                }
                Log.i(TAG, "quick source=system sys=${hex(sys.primary)} wc=${hex(single.toArgb())} hueGap=${"%.1f".format(gap)}")
                return fromSystem(sys)
            }
        }
        return fromSystem(if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx))
    }

    /** 深路径（IO 线程）：有文件权限时原图多点采样（全功能鲜活），否则系统 WallpaperColors 三色。
     *  每次调用重新提取（调用点仅 MainActivity epoch 驱动，开销毫秒级），保证壁纸变化即时跟进。 */
    fun deepResolve(ctx: Context, dark: Boolean): MonetResolved {
        val wm = WallpaperManager.getInstance(ctx)
        val id = wallpaperId(wm)
        if (Environment.isExternalStorageManager()) {
            val seeds = extractSeedsFromFile(ctx)
            if (seeds != null) {
                writeCacheGated(ctx, id, seeds)
                return deriveMultiSeed(seeds, dark, id, "wallpaper")
            }
        }
        return colorsScheme(ctx, dark, id)
    }

    /** 免权限路径：WallpaperColors 三主色（系统多点采样结果）→ 三 seed 派生 + 持久化缓存；
     *  不足则系统 scheme。 */
    private fun colorsScheme(ctx: Context, dark: Boolean, id: Int): MonetResolved {
        val wm = WallpaperManager.getInstance(ctx)
        return try {
            val wc = wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            val p: android.graphics.Color? = wc?.primaryColor
            val s: android.graphics.Color? = wc?.secondaryColor
            val t: android.graphics.Color? = wc?.tertiaryColor
            if (p != null && s != null && t != null) {
                val seeds = Seeds(p.toArgb(), s.toArgb(), t.toArgb())
                Log.i(TAG, "colors3 p=${hex(seeds.p)} s=${hex(seeds.s)} t=${hex(seeds.t)}")
                writeCacheGated(ctx, id, seeds)
                deriveMultiSeed(seeds, dark, id, "wallpaper-colors")
            } else systemScheme(ctx, dark)
        } catch (e: Exception) { systemScheme(ctx, dark) }
    }

    /** 写缓存门控：与旧缓存三 seed hue 差异均 ≤15° 时跳过写盘（省电，避免回前台无谓 IO）。 */
    private fun writeCacheGated(ctx: Context, id: Int, seeds: Seeds) {
        val old = readCache(ctx)
        val changed = old == null || old.wpId != id ||
            hueDeltaDeg(Hct.fromInt(old.p).hue, Hct.fromInt(seeds.p).hue) > 15.0 ||
            hueDeltaDeg(Hct.fromInt(old.s).hue, Hct.fromInt(seeds.s).hue) > 15.0 ||
            hueDeltaDeg(Hct.fromInt(old.t).hue, Hct.fromInt(seeds.t).hue) > 15.0
        if (changed) {
            writeCache(ctx, id, seeds)
            Log.i(TAG, "deep new seeds p=${hex(seeds.p)} s=${hex(seeds.s)} t=${hex(seeds.t)} (id=$id)")
        } else {
            Log.i(TAG, "deep seeds unchanged vs cache, skip write")
        }
    }

    /** 有 MANAGE_EXTERNAL_STORAGE 时的原图多点采样（最鲜活路径）。 */
    private fun extractSeedsFromFile(ctx: Context): Seeds? {
        val wm = WallpaperManager.getInstance(ctx)
        val pfd = try { wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM) } catch (e: Exception) { null }
            ?: return null
        val bmp = try {
            FileInputStream(pfd.fileDescriptor).use { ins ->
                BitmapFactory.decodeStream(ins, null,
                    BitmapFactory.Options().apply { inSampleSize = 8 })
            }
        } catch (e: Exception) { Log.i(TAG, "extract: file decode EX ${e.message}"); null }
        finally { try { pfd.close() } catch (_: Exception) {} }
        if (bmp == null) return null
        return try { multiSampleSeeds(bmp) } finally { bmp.recycle() }
    }

    /**
     * 多点位采样：
     *  A. 全局 Celebi 量化（128 色）→ Score 评分取前 10 候选（占比×鲜活度综合，Google 官方选色）；
     *  B. 4×4 分块，块内 Celebi 量化（10 色）取块主导色 —— 捕捉壁纸局部区域色（天空/主体/点缀）；
     *  C. 候选池 → HCT 鲜活度过滤（chroma ≥ 6 排除灰调，tone 8..94 排除纯黑纯白）+ hue 15° 分槽去重；
     *  D. 三 seed 选取（120° 均分窗口，经典和谐构图）：primary = Score 首位（通过过滤者，保持系统
     *     取色直觉）；secondary / tertiary = 以 primary 为基准 +120° / +240° 目标 hue 的 ±30° 窗口内
     *     选 chroma 最高候选（壁纸真实色，鲜活且色相关系可控）；窗口内无候选 → 数学派生（目标 hue +
     *     primary 彩度缩放），保证三色相均分 360°。
     */
    private fun multiSampleSeeds(bmp: Bitmap): Seeds? {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        val scored: List<Int> = try {
            Score.score(QuantizerCelebi.quantize(px, 128), 10)
        } catch (e: Exception) { emptyList() }

        val blocks = ArrayList<Int>(16)
        val gx = 4; val gy = 4
        for (i in 0 until gx) for (j in 0 until gy) {
            val xs = w * i / gx; val xe = w * (i + 1) / gx
            val ys = h * j / gy; val ye = h * (j + 1) / gy
            val region = IntArray((xe - xs) * (ye - ys))
            var k = 0
            for (y in ys until ye) for (x in xs until xe) region[k++] = px[y * w + x]
            try {
                QuantizerCelebi.quantize(region, 10).maxByOrNull { it.value }?.key?.let { blocks.add(it) }
            } catch (e: Exception) { }
        }

        val pool = ArrayList<Hct>()
        val hueSlot = HashMap<Int, Int>()
        fun offer(argb: Int) {
            val c = Hct.fromInt(argb)
            if (c.chroma < 6.0 || c.tone < 8.0 || c.tone > 94.0) return
            val slot = (c.hue / 15.0).toInt()
            val ex = hueSlot[slot]
            if (ex != null) { if (c.chroma > pool[ex].chroma) pool[ex] = c }
            else { hueSlot[slot] = pool.size; pool.add(c) }
        }
        scored.forEach { offer(it) }
        blocks.forEach { offer(it) }
        if (pool.isEmpty()) return null

        // primary：Score 首位（通过过滤）优先，保持与系统取色直觉一致；否则池内 chroma 最高
        var primary: Hct = pool.maxByOrNull { it.chroma }!!
        for (c in scored) {
            val hc = Hct.fromInt(c)
            if (hc.chroma >= 6.0 && hc.tone in 8.0..94.0) { primary = hc; break }
        }
        // 窗口内选 chroma 最高者（鲜活），窗口 ±30° 保证与 primary 的和谐色相关系
        fun pickWindow(target: Double, fallbackChroma: Double): Hct {
            val hit = pool.filter { hueDeltaDeg(it.hue, target) <= 30.0 }.maxByOrNull { it.chroma }
            return hit ?: Hct.from(target % 360.0, fallbackChroma, primary.tone)
        }
        val secondary = pickWindow(primary.hue + 120.0, max(primary.chroma * 0.9, 12.0))
        val tertiary = pickWindow(primary.hue + 240.0, max(primary.chroma * 0.8, 10.0))

        return Seeds(primary.toInt(), secondary.toInt(), tertiary.toInt())
    }

    /** 三 seed 各自独立 TonalPalette 派生：三个角色色相来自壁纸不同区域，鲜活多色。
     *  secondary/tertiary 彩度微降（×0.85）保视觉和谐，hue 保持采样原值。 */
    private fun deriveMultiSeed(s: Seeds, dark: Boolean, wpId: Int, source: String): MonetResolved {
        val tone = if (dark) 80 else 40
        val P = TonalPalette.fromInt(s.p)
        val sh = Hct.fromInt(s.s)
        val th = Hct.fromInt(s.t)
        val S = TonalPalette.fromHueAndChroma(sh.hue, max(sh.chroma * 0.85, 8.0))
        val T = TonalPalette.fromHueAndChroma(th.hue, max(th.chroma * 0.85, 8.0))
        return MonetResolved(
            primary = Color(P.tone(tone)),
            secondary = Color(S.tone(tone)),
            tertiary = Color(T.tone(tone)),
            sysScheme = null,
            source = "$source(id=$wpId)",
        ).also { Log.i(TAG, "resolve $source dark=$dark p=${hex(it.primary)} s=${hex(it.secondary)} t=${hex(it.tertiary)}") }
    }

    /** 单 seed 派生（WallpaperColors 仅 primary 时的兜底）：同 hue 彩度缩放 + 60° 偏移。 */
    private fun deriveSingleSeed(seedArgb: Int, dark: Boolean, wpId: Int, source: String): MonetResolved {
        val h = Hct.fromInt(seedArgb)
        return deriveMultiSeed(
            Seeds(
                seedArgb,
                Hct.from((h.hue + 120.0) % 360.0, max(h.chroma * 0.8, 8.0), h.tone).toInt(),
                Hct.from((h.hue + 240.0) % 360.0, max(h.chroma * 0.7, 8.0), h.tone).toInt(),
            ), dark, wpId, source)
    }

    private fun fromSystem(sys: androidx.compose.material3.ColorScheme) =
        MonetResolved(sys.primary, sys.secondary, sys.tertiary, sys, "system")

    private fun systemScheme(ctx: Context, dark: Boolean): MonetResolved =
        fromSystem(if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx))

    private fun hex(c: Color): String = "#%06X".format(c.toArgb() and 0xFFFFFF)
    private fun hex(i: Int): String = "#%06X".format(i and 0xFFFFFF)
}
