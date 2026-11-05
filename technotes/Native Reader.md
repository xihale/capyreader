# Native Reader

The article reader is Compose, not a WebView. Shipped in #2245 (2026-09-20), replacing the templated HTML page.

## Pipeline

HTML -> `mallet` -> `List<LinearElement>` -> Compose.

- `Mallet.flatten(html, baseUrl)` returns `Result<LinearArticle>`. Failures are `LinearizeError`.
- `mallet` is a plain Kotlin JVM module with only JSoup, so it tests under `:mallet:test` with no Android.
- Dependency chain is `mallet -> capy -> app`. `Article.flatten()` in `capy/articles/ArticleFlattenExt.kt` is the seam; the app never talks to mallet's parser directly.
- Parsing happens in the ViewModel so the article and its elements arrive in the same frame. Splitting them causes a visible judder on the article transition.
- Renderer lives in `app/ui/articles/reader`.

Caps are 2000 elements / 100k chars. Over that, `LinearArticle.truncated` is set and mallet appends no notice text — `capy` decides what to show.

## Full content

`ArticleContent.fetch` (device-side, browser headers) -> `Mercury.parse(html = ...)` in `capy/articles/MercuryParser.kt`, from the `:mercuryparser` module (Kotlin port of Mercury Parser; per-site rules live in `extractors/custom`). Replaces the ~1MB JS asset the WebView loaded.

## Styling

Matches the old `stylesheet.css`, which is worth diffing against before changing spacing:

- Body line height 1.6, paragraph gaps of `1em` that scale with the reader font size.
- Byline and feed name inherit the browser default 16px in `onSurface` — they were bare `div`s, not a smaller style.
- Headings use 1.3 and code blocks ~1.78 where the stylesheet had ~1.2 and 1.6. Not yet reconciled.

## YouTube

Embeds play inline in a `WebView`; everything else in the reader is native. Two constraints, both expensive to rediscover:

- **A WebView sends no `Referer`,** so YouTube rejects the embed with error 152. The fix is `loadDataWithBaseURL` with `baseUrl` set to the app's identity (`https://capyreader.com`) plus `referrerpolicy="strict-origin-when-cross-origin"` on the iframe. Loading the embed URL as a top-level navigation is not enough — that leaves YouTube with no embedding origin, and setting the `Referer` header alone does not satisfy it.
- **A WebView's containing block has no height,** so `height: 100%` and `100vh` collapse to zero. A zero-height iframe still loads its `src`, so the player boots and renders nothing. Size off `100vw` with `aspect-ratio` instead.

Fullscreen goes through `WebChromeClient.onShowCustomView` into a `Dialog`. Embedded players must be at least 200x200 CSS px; 480x270 recommended for 16:9.

- <https://developers.google.com/youtube/terms/required-minimum-functionality>

## Math

Math formulas render natively. Detection is deliberately generalized — the TeX annotation is the reliable signal, wrapper class names are conveniences:

- KaTeX (server-rendered, the common case): mallet intercepts `.katex-display` → `LinearMath(tex)` blocks and inline `.katex` → `LinearTextAnnotationMath` annotations. Both KaTeX span trees are dropped, so no duplicated/mangled text.
- Bare `<math>` with a TeX `annotation` (Temml, class-stripped KaTeX) → inline annotation.
- MathJax v2: `<script type="math/tex">` (inline) and `; mode=display` (block). Mercury strips `script` on the extraction path, but exempts `script[type^=math/tex]`; feed content reaches mallet untouched.
- Raw TeX delimiters in prose — `$$...$$`, `\(...\)`, `\[...\]` — become inline annotations. Only scanned in soft-wrapping contexts, never in `pre`/code blocks. Single `$` is deliberately not a delimiter (prices).
- Mercury's attribute whitelist strips `encoding`, so annotation matching must not rely on it (bare `annotation` fallback).
- No TeX source at all (html-only KaTeX, MathJax v3 SVG) → legacy flatten-as-text behavior.
- `MathRenderer` (app) parses TeX with JLaTeXMath (GPL-2.0+, license-compatible) to transparent bitmaps, cached by tex+size+color+style with a negative cache for failures; rendering happens on `Dispatchers.Default`. Guards: TeX length cap (4000), bitmap side clamp (3072, re-rendered at scaled size), and one scrub retry that strips KaTeX-only commands (`\label`, `\tag`, `\nonumber`, `\notag`, `\qedhere`, `\allowbreak`) — regexes must escape `}` for Android ICU (`[^}]*\}`), the JVM tolerates bare `}` and Robolectric hides the bug.
- **Release builds require the jlatexmath keep rules in `app/proguard-rules.pro`**: jlatexmath resolves macros (`\frac`, `\sum`, `\sqrt`, matrix environments...) reflectively via registered `_macro` methods, and R8 renames those members without a keep rule. Symptom: only macros-free formulas render; everything else shows raw TeX with `NullPointerException ... Method.invoke on a null object reference` in logcat under an `r8-map-id` frame. Same APK rendered fine on an API 31 emulator and failed on an Android 16 device — don't trust an emulator green run for minified builds; verify R8 builds on the target device.
- Display math: `MathElement` image block, `ContentScale.Inside` so small formulas don't upscale. Inline math: `InlineTextContent` placeholders keyed by `inlineMathAnnotations()` tags — `toAnnotatedString` and `rememberInlineMathContents` must agree on those tags. Placeholder align is `TextCenter`; px→sp keeps formulas scaled with the reader font-size preference.
- Failure mode for unparseable TeX (e.g. `\color`, `\bm`): raw TeX source shows in monospace.
- Local account note: feed sync may purge rows mid-testing; inject test articles with airplane mode on and verify the row still exists before blaming the renderer.

## Gaps

- Non-YouTube iframe embeds (Instagram, Bluesky, Vimeo, Spotify) are dropped at parse time, not just unrendered — `parseIframeVideo` only emits for YouTube. The blockquote form of an Instagram embed survives as quoted fallback text. These all worked under the WebView.
- Heading and code-block line heights, above.
