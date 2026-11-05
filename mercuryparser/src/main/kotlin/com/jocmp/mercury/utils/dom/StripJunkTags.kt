package com.jocmp.mercury.utils.dom

import com.jocmp.mercury.dsl.Doc
import com.jocmp.mercury.dsl.Selection

fun stripJunkTags(
    article: Selection,
    doc: Doc,
    tags: List<String> = emptyList(),
): Doc {
    val effectiveTags = if (tags.isEmpty()) STRIP_OUTPUT_TAGS else tags

    // Keep MathJax v2 TeX sources around so downstream renderers can re-render formulas
    val selectors = effectiveTags.map { tag ->
        if (tag.equals("script", ignoreCase = true)) {
            "script:not([type^=math/tex])"
        } else {
            tag
        }
    }

    // Remove matching elements, but ignore
    // any element with a class of mercury-parser-keep
    article.find(selectors.joinToString(",")).each { _, node ->
        if (!node.hasClass(KEEP_CLASS)) {
            node.remove()
        }
    }
    return doc
}
