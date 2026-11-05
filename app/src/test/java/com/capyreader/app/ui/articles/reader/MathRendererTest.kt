package com.capyreader.app.ui.articles.reader

import androidx.compose.ui.graphics.asAndroidBitmap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class MathRendererTest {
    private val textSizePx = 48f
    private val color = 0xFF111111.toInt()

    @Before
    fun setUp() {
        stopKoin()
        // Robolectric doesn't run the AAR's JLatexMathInitProvider, init manually
        ru.noties.jlatexmath.JLatexMathAndroid.init(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `renders simple formula`() {
        val bitmap = MathRenderer.render(
            tex = "\\frac{1}{2}",
            textSizePx = textSizePx,
            colorArgb = color,
            style = org.scilab.forge.jlatexmath.TeXConstants.STYLE_DISPLAY,
        )

        assertNotNull(bitmap)
        assertTrue(bitmap!!.asAndroidBitmap().width > 0)
        assertTrue(bitmap.asAndroidBitmap().height > 0)
    }

    @Test
    fun `renders formula with KaTeX-only commands via scrub retry`() {
        val bitmap = MathRenderer.render(
            tex = "x = 1 \\nonumber \\label{eq:x} \\tag{1}",
            textSizePx = textSizePx,
            colorArgb = color,
            style = org.scilab.forge.jlatexmath.TeXConstants.STYLE_DISPLAY,
        )

        assertNotNull(bitmap)
    }

    @Test
    fun `rejects oversized TeX without rendering`() {
        val junk = "\\text{stuff}".repeat(1000)

        val bitmap = MathRenderer.render(
            tex = junk,
            textSizePx = textSizePx,
            colorArgb = color,
            style = org.scilab.forge.jlatexmath.TeXConstants.STYLE_DISPLAY,
        )

        assertNull(bitmap)
    }

    @Test
    fun `unparseable TeX returns null`() {
        val bitmap = MathRenderer.render(
            tex = "\\notacommand{zzz}",
            textSizePx = textSizePx,
            colorArgb = color,
            style = org.scilab.forge.jlatexmath.TeXConstants.STYLE_DISPLAY,
        )

        assertNull(bitmap)
    }

    @Test
    fun `huge formulas are downscaled to fit`() {
        // A very wide product chain produces a bitmap wider than the clamp
        val wide = (1..40).joinToString("") { "\\underbrace{x_{n+$it}}_{t$it}+" } + "1"

        val bitmap = MathRenderer.render(
            tex = wide,
            textSizePx = 300f,
            colorArgb = color,
            style = org.scilab.forge.jlatexmath.TeXConstants.STYLE_TEXT,
        )

        assertNotNull(bitmap)
        val w = bitmap!!.asAndroidBitmap().width
        val h = bitmap.asAndroidBitmap().height
        assertTrue("expected clamped bitmap, got ${w}x$h", maxOf(w, h) <= 3072)
    }

    @Test
    fun `scrub keeps normal formulas intact`() {
        assertEquals("a + b", MathRenderer.scrubUnsupported("a + b"))
        assertEquals("x", MathRenderer.scrubUnsupported("\\label{eq}\\tag*{7}x"))
        assertEquals("y  z", MathRenderer.scrubUnsupported("y \\notag z"))
    }
}
