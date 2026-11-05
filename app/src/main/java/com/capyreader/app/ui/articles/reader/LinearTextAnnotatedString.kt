package com.capyreader.app.ui.articles.reader

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.isSpecified
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.scilab.forge.jlatexmath.TeXConstants
import com.jocmp.mallet.LinearText
import com.jocmp.mallet.LinearTextAnnotation
import com.jocmp.mallet.LinearTextAnnotationBold
import com.jocmp.mallet.LinearTextAnnotationCode
import com.jocmp.mallet.LinearTextAnnotationFont
import com.jocmp.mallet.LinearTextAnnotationH1
import com.jocmp.mallet.LinearTextAnnotationH2
import com.jocmp.mallet.LinearTextAnnotationH3
import com.jocmp.mallet.LinearTextAnnotationH4
import com.jocmp.mallet.LinearTextAnnotationH5
import com.jocmp.mallet.LinearTextAnnotationH6
import com.jocmp.mallet.LinearTextAnnotationItalic
import com.jocmp.mallet.LinearTextAnnotationLink
import com.jocmp.mallet.LinearTextAnnotationMath
import com.jocmp.mallet.LinearTextAnnotationMonospace
import com.jocmp.mallet.LinearTextAnnotationStrikethrough
import com.jocmp.mallet.LinearTextAnnotationSubscript
import com.jocmp.mallet.LinearTextAnnotationSuperscript
import com.jocmp.mallet.LinearTextAnnotationUnderline
import com.jocmp.mallet.LinearTextBlockStyle

val LinearText.headingScale: Float?
    get() = annotations.firstNotNullOfOrNull { annotation ->
        when (annotation.data) {
            LinearTextAnnotationH1 -> 2f
            LinearTextAnnotationH2 -> 1.5f
            LinearTextAnnotationH3 -> 1.17f
            LinearTextAnnotationH4 -> 1f
            LinearTextAnnotationH5 -> 0.83f
            LinearTextAnnotationH6 -> 0.67f
            else -> null
        }
    }

val LinearText.links: List<LinearTextAnnotation>
    get() = annotations.filter { it.data is LinearTextAnnotationLink }

@Composable
fun LinearText.toAnnotatedString(
    idToIndex: Map<String, Int>,
    onLinkClick: (url: String, elementIndex: Int?) -> Unit,
): AnnotatedString {
    val colors = MaterialTheme.colorScheme
    val baseSize = LocalTextStyle.current.fontSize
    val linkStyles = TextLinkStyles(
        style = SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline)
    )

    return buildAnnotatedString {
        val maths = inlineMathAnnotations()

        var cursor = 0
        for ((tag, annotation) in maths) {
            if (annotation.start > cursor) {
                append(text, cursor, annotation.start)
            }

            appendInlineContent(
                id = tag,
                alternateText = text.substring(annotation.start, annotation.endExclusive),
            )

            cursor = annotation.endExclusive
        }

        if (cursor < text.length) {
            append(text, cursor, text.length)
        }

        annotations.forEach { annotation ->
            val link = annotation.data as? LinearTextAnnotationLink

            if (link != null) {
                val href = link.href

                addLink(
                    clickable = LinkAnnotation.Clickable(
                        tag = href,
                        styles = linkStyles,
                        linkInteractionListener = {
                            onLinkClick(href, resolveAnchorIndex(href, idToIndex))
                        },
                    ),
                    start = annotation.start,
                    end = annotation.endExclusive,
                )

                return@forEach
            }

            val style = when (val data = annotation.data) {
                LinearTextAnnotationBold -> SpanStyle(fontWeight = FontWeight.Bold)
                LinearTextAnnotationItalic -> SpanStyle(fontStyle = FontStyle.Italic)
                LinearTextAnnotationUnderline -> SpanStyle(textDecoration = TextDecoration.Underline)
                LinearTextAnnotationStrikethrough -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                LinearTextAnnotationMonospace -> SpanStyle(fontFamily = FontFamily.Monospace)
                is LinearTextAnnotationMath -> SpanStyle(fontFamily = FontFamily.Monospace)
                is LinearTextAnnotationLink -> null
                is LinearTextAnnotationFont -> SpanStyle(fontFamily = data.face.toFontFamily())
                LinearTextAnnotationCode -> inlineCodeStyle(colors.surfaceContainer)
                LinearTextAnnotationSubscript -> shiftedStyle(BaselineShift.Subscript, baseSize)
                LinearTextAnnotationSuperscript -> shiftedStyle(BaselineShift.Superscript, baseSize)
                LinearTextAnnotationH1,
                LinearTextAnnotationH2,
                LinearTextAnnotationH3,
                LinearTextAnnotationH4,
                LinearTextAnnotationH5,
                LinearTextAnnotationH6 -> null
            }

            if (style != null) {
                addStyle(style = style, start = annotation.start, end = annotation.endExclusive)
            }
        }
    }
}

private fun LinearText.inlineCodeStyle(background: androidx.compose.ui.graphics.Color): SpanStyle? {
    if (blockStyle == LinearTextBlockStyle.CODE_BLOCK) {
        return null
    }

    return SpanStyle(fontFamily = FontFamily.Monospace, background = background)
}

private fun shiftedStyle(shift: BaselineShift, baseSize: androidx.compose.ui.unit.TextUnit): SpanStyle {
    if (!baseSize.isSpecified) {
        return SpanStyle(baselineShift = shift)
    }

    return SpanStyle(baselineShift = shift, fontSize = baseSize * 0.75f)
}

private fun String.toFontFamily(): FontFamily? {
    return when (lowercase()) {
        "monospace" -> FontFamily.Monospace
        "serif" -> FontFamily.Serif
        "sans-serif" -> FontFamily.SansSerif
        "cursive" -> FontFamily.Cursive
        else -> null
    }
}

/**
 * Inline math annotations paired with unique inline-content tags, in text order.
 * [toAnnotatedString] and [rememberInlineMathContents] must agree on these tags.
 */
internal fun LinearText.inlineMathAnnotations(): List<Pair<String, LinearTextAnnotation>> =
    annotations
        .withIndex()
        .filter { it.value.data is LinearTextAnnotationMath }
        .map { (index, annotation) -> "math_$index" to annotation }

/**
 * Pre-renders each inline formula to a bitmap and exposes it as an inline
 * text content keyed by [inlineMathAnnotations] tags. Formulas that fail to
 * render are omitted so the TeX source shows as fallback text.
 */
@Composable
fun LinearText.rememberInlineMathContents(): Map<String, InlineTextContent> {
    val maths = inlineMathAnnotations()

    if (maths.isEmpty()) {
        return emptyMap()
    }

    val density = LocalDensity.current
    val (textSizePx, colorInt) = readerMathStyle()

    val bitmaps by produceState(emptyMap<String, ImageBitmap>(), maths, textSizePx, colorInt) {
        value = withContext(Dispatchers.Default) {
            maths.mapNotNull { (tag, annotation) ->
                val tex = (annotation.data as LinearTextAnnotationMath).tex

                MathRenderer.render(
                    tex = tex,
                    textSizePx = textSizePx,
                    colorArgb = colorInt,
                    style = TeXConstants.STYLE_TEXT,
                )?.let { tag to it }
            }.toMap()
        }
    }

    return bitmaps.mapValues { (_, bitmap) ->
        InlineTextContent(
            Placeholder(
                width = with(density) { bitmap.width.toSp() },
                height = with(density) { bitmap.height.toSp() },
                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
            ),
        ) {
            Image(
                painter = BitmapPainter(bitmap),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}
