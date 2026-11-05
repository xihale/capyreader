package com.capyreader.app.ui.articles.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import androidx.collection.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.isSpecified
import org.scilab.forge.jlatexmath.TeXConstants
import org.scilab.forge.jlatexmath.TeXFormula
import org.scilab.forge.jlatexmath.TeXIcon
import ru.noties.jlatexmath.awt.AndroidGraphics2D
import ru.noties.jlatexmath.awt.Color

/**
 * Renders TeX source strings to transparent bitmaps via JLaTeXMath.
 *
 * Rendering parses and lays out the formula and is CPU-bound, so callers must
 * invoke [render] from a background dispatcher. Results are cached by content,
 * size, color and style.
 */
object MathRenderer {
    private const val MAX_CACHE_BYTES = 16 * 1024 * 1024

    /** Above this the formula is likely markup garbage or a pathological dump. */
    private const val MAX_TEX_LENGTH = 4000

    /** Bitmaps larger than this on either side are downscaled to fit. */
    private const val MAX_BITMAP_SIDE = 3072

    private val cache = object : LruCache<String, ImageBitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int {
            return value.asAndroidBitmap().allocationByteCount
        }
    }

    // Negative cache so unparseable formulas don't re-parse on every recomposition.
    // Keyed by hash to avoid holding whole TeX strings alive.
    private val failures = LruCache<Int, Boolean>(512)

    /**
     * @param style one of [TeXConstants.STYLE_DISPLAY] (blocks) or
     * [TeXConstants.STYLE_TEXT] (inline), mirroring KaTeX's display mode flag
     * @return the rendered bitmap, or null when the TeX can't be parsed
     */
    fun render(
        tex: String,
        textSizePx: Float,
        colorArgb: Int,
        style: Int,
    ): ImageBitmap? {
        if (tex.length > MAX_TEX_LENGTH) {
            return null
        }

        val key = "$style\u001F$textSizePx\u001F$colorArgb\u001F$tex"
        cache.get(key)?.let { return it }
        if (failures.get(key.hashCode()) == true) {
            return null
        }

        val icon = buildIcon(tex, textSizePx, colorArgb, style) ?: run {
            failures.put(key.hashCode(), true)
            return null
        }

        // Downscale oversized formulas by re-rendering at a smaller font size
        val scale = if (icon.iconWidth > MAX_BITMAP_SIDE || icon.iconHeight > MAX_BITMAP_SIDE) {
            MAX_BITMAP_SIDE.toFloat() / maxOf(icon.iconWidth, icon.iconHeight)
        } else {
            1f
        }
        val fitted =
            if (scale < 1f) {
                buildIcon(tex, textSizePx * scale, colorArgb, style) ?: icon
            } else {
                icon
            }

        val width = fitted.iconWidth
        val height = fitted.iconHeight

        if (width <= 0 || height <= 0) {
            failures.put(key.hashCode(), true)
            return null
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val graphics = AndroidGraphics2D()
        graphics.setCanvas(Canvas(bitmap))
        fitted.paintIcon(null, graphics, 0, 0)

        val image = bitmap.asImageBitmap()
        cache.put(key, image)
        return image
    }

    /**
     * KaTeX-only commands that jlatexmath rejects. Stripping them on the
     * second attempt turns hard failures (whole formula shows as TeX source)
     * into rendered output without touching parseable formulas.
     */
    internal fun scrubUnsupported(tex: String): String =
        tex
            .replace(Regex("\\\\label\\{[^}]*\\}"), "")
            .replace(Regex("\\\\tag\\*?\\{[^}]*\\}"), "")
            .replace(Regex("\\\\(?:nonumber|notag|qedhere|allowbreak)"), "")

    private fun buildIcon(
        tex: String,
        textSizePx: Float,
        colorArgb: Int,
        style: Int,
    ): TeXIcon? =
        try {
            newIcon(tex, textSizePx, colorArgb, style)
        } catch (e: Exception) {
            Log.w("MathRenderer", "tex parse failed, retrying scrubbed: ${tex.take(80)}", e)
            try {
                newIcon(scrubUnsupported(tex), textSizePx, colorArgb, style)
            } catch (e2: Exception) {
                Log.w("MathRenderer", "scrubbed parse failed", e2)
                null
            }
        }

    private fun newIcon(
        tex: String,
        textSizePx: Float,
        colorArgb: Int,
        style: Int,
    ): TeXIcon =
        TeXFormula(tex)
            .TeXIconBuilder()
            .setStyle(style)
            .setSize(textSizePx)
            .setFGColor(Color(colorArgb))
            .build()
}

/**
 * Font size in px and foreground color the surrounding reader text resolves to.
 * Math bitmaps are rendered with these so formulas match the reader's
 * font-size preference and light/dark theme.
 */
@Composable
internal fun readerMathStyle(): Pair<Float, Int> {
    val density = LocalDensity.current
    val textStyle = LocalTextStyle.current
    val fontSize = if (textStyle.fontSize.isSpecified) textStyle.fontSize else 16.sp
    val color = if (textStyle.color.isSpecified) textStyle.color else MaterialTheme.colorScheme.onSurface

    return with(density) { fontSize.toPx() } to color.toArgb()
}
