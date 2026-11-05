package com.capyreader.app.ui.articles.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import com.jocmp.mallet.LinearMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.scilab.forge.jlatexmath.TeXConstants

/**
 * A display-mode math formula rendered as its own block.
 *
 * Falls back to the raw TeX source in monospace while rendering and for
 * formulas the renderer can't parse.
 */
@Composable
fun MathElement(
    math: LinearMath,
    modifier: Modifier = Modifier,
) {
    val (textSizePx, colorInt) = readerMathStyle()

    val bitmap by produceState<ImageBitmap?>(null, math.tex, textSizePx, colorInt) {
        value = withContext(Dispatchers.Default) {
            MathRenderer.render(
                tex = math.tex,
                textSizePx = textSizePx,
                colorArgb = colorInt,
                style = TeXConstants.STYLE_DISPLAY,
            )
        }
    }

    val currentBitmap = bitmap

    if (currentBitmap != null) {
        Image(
            painter = BitmapPainter(currentBitmap),
            contentDescription = math.tex,
            modifier = modifier.fillMaxWidth(),
            alignment = Alignment.Center,
            contentScale = ContentScale.Inside,
        )
    } else {
        Text(
            text = math.tex,
            fontFamily = FontFamily.Monospace,
            modifier = modifier.fillMaxWidth(),
        )
    }
}
