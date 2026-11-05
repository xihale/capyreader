package com.jocmp.mercury.utils.dom

import com.jocmp.mercury.assertClean
import com.jocmp.mercury.dsl.Doc
import kotlin.test.Test
import kotlin.test.assertEquals

class StripJunkTagsTest {
    @Test
    fun `strips script and other junk tags`() {
        val doc =
            Doc.load(
                """
            <div>
              <style>.red { color: 'red'; }</style>
              <title>WOW</title>
              <link rel="asdflkjawef" />
              <p>What an article</p>
              <script type="text/javascript">alert('hi!');</script>
              <noscript>Don't got it</noscript>
              <hr />
            </div>
            """,
                isDocument = false,
            )

        stripJunkTags(doc("*").first(), doc)
        assertClean(
            doc("body").html(),
            """
            <div>
              <p>What an article</p>
            </div>
            """,
        )
    }

    @Test
    fun `keeps youtube embeds`() {
        val doc =
            Doc.load(
                """
            <div>
              <style>.red { color: 'red'; }</style>
              <title>WOW</title>
              <link rel="asdflkjawef" />
              <p>What an article</p>
              <iframe class="mercury-parser-keep" src="https://www.youtube.com/embed/_2AqQV8wDvY" frameborder="0" allowfullscreen></iframe>
              <hr />
            </div>
            """,
                isDocument = false,
            )

        stripJunkTags(doc("*").first(), doc)
        assertEquals(1, doc("iframe[src^=\"https://www.youtube.com\"]").length)
    }

    @Test
    fun `keeps mathjax tex scripts while stripping other scripts`() {
        val doc =
            Doc.load(
                """
            <div>
              <p>Euler: <script type="math/tex">e^{i\pi} + 1 = 0</script> indeed.</p>
              <script type="math/tex; mode=display">\int_0^1 x^2\,dx</script>
              <script type="text/javascript">alert('hi!');</script>
            </div>
            """,
                isDocument = false,
            )

        stripJunkTags(doc("*").first(), doc)
        assertEquals(2, doc("script[type^=math/tex]").length)
        assertEquals(0, doc("script[type=text/javascript]").length)
    }
}
