package com.capyreader.app.ui.components

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebView.HitTestResult.SRC_ANCHOR_TYPE
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewAssetLoader.AssetsPathHandler
import androidx.webkit.WebViewAssetLoader.ResourcesPathHandler
import com.capyreader.app.common.AudioEnclosure
import com.capyreader.app.common.Media
import com.capyreader.app.common.WebViewInterface
import com.capyreader.app.common.rememberTalkbackPreference
import com.capyreader.app.ui.LocalTimeFormats
import com.capyreader.app.ui.articles.detail.articleTemplateColors
import com.capyreader.app.ui.articles.detail.byline
import com.capyreader.app.ui.articles.displayFeedName
import com.jocmp.capy.Article
import com.jocmp.capy.articles.ArticleRenderer
import com.jocmp.capy.logging.CapyLog
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import org.koin.core.component.KoinComponent

private const val WEB_VIEW_ASSET_BASE_URL = "https://appassets.androidplatform.net/"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebView(
    modifier: Modifier,
    state: WebViewState,
    article: Article? = null,
    showImages: Boolean = true,
) {
    val context = LocalContext.current
    val timeFormats = LocalTimeFormats.current
    val preparedHtml by produceState<PreparedArticleHtml?>(
        initialValue = null,
        key1 = article,
        key2 = showImages,
    ) {
        val currentArticle = article

        value = if (currentArticle == null) {
            null
        } else {
            withContext(Dispatchers.Default) {
                state.prepareHtml(
                    article = currentArticle,
                    hideImages = !showImages,
                    byline = currentArticle.byline(context, formats = timeFormats),
                    feedName = currentArticle.displayFeedName(context),
                )
            }
        }
    }

    LaunchedEffect(article?.id) {
        if (article != null) {
            state.reset()
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { state.webView },
        update = {
            preparedHtml?.let(state::loadHtml)
        }
    )
}

data class PreparedArticleHtml(
    val articleId: String,
    val html: String,
    val baseUrl: String,
    val contentHash: Int,
)

class AccompanistWebViewClient(
    private val assetLoader: WebViewAssetLoader,
    private val onOpenLink: (url: Uri) -> Unit,
    private val httpClient: OkHttpClient,
) : WebViewClient(),
    KoinComponent {
    lateinit var state: WebViewState
        internal set

    var pageUrl: String? = null
        internal set

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        val asset = assetLoader.shouldInterceptRequest(request.url)
        if (asset != null) {
            val headers = asset.responseHeaders ?: mutableMapOf()
            headers["Access-Control-Allow-Origin"] = "*"
            asset.responseHeaders = headers
            return asset
        }

        if (!shouldProxyRequest(request)) {
            return null
        }

        return proxyRequest(request)
    }

    private fun shouldProxyRequest(request: WebResourceRequest) =
        WebRequestProxyPolicy.shouldProxy(request.url.toString(), request, pageUrl)

    /**
     * Proxies requests to add CORS headers for cross-origin
     * requests (Issue #1616) and Referer headers for media CDNs (Issue #1878)
     */
    private fun proxyRequest(request: WebResourceRequest): WebResourceResponse? {
        return try {
            val okHttpRequest = Request.Builder()
                .url(request.url.toString())
                .apply {
                    request
                        .requestHeaders
                        .filterNot { it.key.equals("Accept-Encoding", ignoreCase = true) }
                        .forEach { (key, value) ->
                            header(key, value)
                        }
                    pageUrl?.let { header("Referer", it) }
                }
                .build()

            val response = httpClient.newCall(okHttpRequest).execute()
            val contentType = response.header("Content-Type") ?: "text/html"
            val mimeType = contentType.substringBefore(";").trim()

            val charset = contentType
                .substringAfter("charset=", "UTF-8")
                .substringBefore(";")
                .trim()

            val corsHeaders = mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
                "Access-Control-Allow-Headers" to "*"
            )
            val responseHeaders = response.headers.toMap() + corsHeaders

            WebResourceResponse(
                mimeType,
                charset,
                response.code,
                response.message.ifEmpty { "OK" },
                responseHeaders,
                response.body.byteStream()
            )
        } catch (e: Exception) {
            CapyLog.error("webview_intercept_request", e)
            null
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url

        if (url != null) {
            onOpenLink(url)
        }

        return true
    }
}

@Stable
class WebViewState(
    private val renderer: ArticleRenderer,
    private val colors: Map<String, String>,
    private val enableNativeScroll: Boolean,
    internal val webView: WebView,
) {
    private var htmlId: String? = null
    private var contentHash: Int = 0
    private var baseUrl: String? = null
    private var currentAudioUrl: String? = null
    private var isAudioPlaying: Boolean = false

    init {
        loadEmpty()
    }

    fun prepareHtml(article: Article, hideImages: Boolean, byline: String, feedName: String): PreparedArticleHtml {
        val html = renderer.render(
            article = article,
            hideImages = hideImages,
            byline = byline,
            colors = colors,
            feedName = feedName,
        )

        val resolvedBaseUrl = article.url?.toString() ?: article.siteURL ?: WEB_VIEW_ASSET_BASE_URL

        return PreparedArticleHtml(
            articleId = article.id,
            html = html,
            baseUrl = resolvedBaseUrl,
            contentHash = html.hashCode(),
        )
    }

    fun loadHtml(preparedHtml: PreparedArticleHtml) {
        val id = preparedHtml.articleId
        val hash = preparedHtml.contentHash

        if (id == htmlId && hash == contentHash && preparedHtml.baseUrl == baseUrl) {
            return
        }

        webView.isVerticalScrollBarEnabled = enableNativeScroll
        htmlId = id
        contentHash = hash
        baseUrl = preparedHtml.baseUrl

        val client = webView.webViewClient as? AccompanistWebViewClient
        client?.pageUrl = preparedHtml.baseUrl

        webView.loadDataWithBaseURL(
            preparedHtml.baseUrl,
            preparedHtml.html,
            "text/html",
            "UTF-8",
            null,
        )
    }

    fun reset() {
        htmlId = null
        contentHash = 0
        baseUrl = null
        loadEmpty()
    }

    fun updateAudioPlayState(url: String?, isPlaying: Boolean) {
        currentAudioUrl = url
        isAudioPlaying = isPlaying
        if (htmlId == null) {
            return
        }
        webView.post {
            if (url != null) {
                val escapedUrl = url.replace("'", "\\'")
                webView.evaluateJavascript("updateAudioPlayState('$escapedUrl', $isPlaying)", null)
            } else {
                webView.evaluateJavascript("resetAudioPlayState()", null)
            }
        }
    }

    fun resetAudioPlayState() {
        updateAudioPlayState(null, false)
    }

    private fun loadEmpty() = webView.loadUrl("about:blank")
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun rememberWebViewState(
    renderer: ArticleRenderer = koinInject(),
    httpClient: OkHttpClient = koinInject(),
    onNavigateToMedia: (media: Media) -> Unit,
    onRequestLinkDialog: (link: ShareLink) -> Unit,
    onRequestImageDialog: (imageUrl: String) -> Unit = {},
    onOpenLink: (url: Uri) -> Unit,
    onOpenAudioPlayer: (audio: AudioEnclosure) -> Unit = {},
    onPauseAudio: () -> Unit = {},
    currentAudioUrl: String? = null,
    isAudioPlaying: Boolean = false,
    key: String? = null,
): WebViewState {
    val enableNativeScroll by rememberTalkbackPreference()
    val colors = articleTemplateColors()
    val context = LocalContext.current
    val currentAudioUrlState by rememberUpdatedState(currentAudioUrl)
    val isAudioPlayingState by rememberUpdatedState(isAudioPlaying)

    val reset = if (enableNativeScroll) {
        key
    } else {
        null
    }

    val client = remember {
        AccompanistWebViewClient(
            assetLoader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", AssetsPathHandler(context))
                .addPathHandler("/res/", ResourcesPathHandler(context))
                .build(),
            onOpenLink = onOpenLink,
            httpClient = httpClient,
        )
    }

    val state = remember(enableNativeScroll) {
        val webViewInterface = WebViewInterface(
            navigateToMedia = { onNavigateToMedia(it) },
            onRequestLinkDialog = onRequestLinkDialog,
            onRequestImageDialog = onRequestImageDialog,
            onOpenAudioPlayer = onOpenAudioPlayer,
            onPauseAudio = onPauseAudio,
        )

        val webView = WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                mediaPlaybackRequiresUserGesture = false
                domStorageEnabled = true
            }
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false

            setOnLongClickListener {
                hitTestResult.type == SRC_ANCHOR_TYPE
            }

            addJavascriptInterface(webViewInterface, WebViewInterface.INTERFACE_NAME)

            setBackgroundColor(android.graphics.Color.TRANSPARENT)

            webViewClient = client
        }

        WebViewState(
            renderer,
            colors,
            enableNativeScroll = enableNativeScroll,
            webView,
        ).also {
            client.state = it
            webViewInterface.onRequestAudioState = {
                it.updateAudioPlayState(currentAudioUrlState, isAudioPlayingState)
            }
        }
    }

    LaunchedEffect(reset) {
        if (reset != null) {
            state.reset()
        }
    }

    return state
}
