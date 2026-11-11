package com.mysi.agent.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Loads a page in an off-screen WebView so its JavaScript can build the content, then returns the
 * resulting HTML for the same readability code as read_web_page.
 *
 * Locked down: no file or content access, no geolocation, no permissions, no popups, no
 * downloads, no JavaScript interfaces, third-party cookies off, images off. The main-frame address
 * is checked with DNS before loading and again before reading; non-web schemes and local names
 * are cancelled in shouldOverrideUrlLoading; and the requests WebView reports to
 * shouldInterceptRequest (main frame and subresources) are checked against [UrlSafety] and
 * refused when private.
 *
 * Known limits, not verified on a device: WebView may not report every redirect hop of a
 * subresource or WebSocket connections to shouldInterceptRequest, and WebView resolves DNS itself,
 * so its connection is not pinned to the address that was checked. A fresh WebView is used per
 * call and destroyed after it, and cookies and web storage are cleared afterwards.
 */
object RenderedPage {
    class Rendered(val html: String, val url: String)

    private const val LOAD_TIMEOUT_MS = 20_000L
    private const val SETTLE_MS = 1_500L
    private const val MAX_HTML_CHARS = 3_000_000
    private val lock = Mutex()

    /** A fresh empty 403 per request (a response's stream cannot be shared). */
    private fun blockedResponse() = WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    /** Checks that need no DNS, safe to run on the main thread. */
    private fun staticallySafe(url: String): Boolean {
        val u = WebUrl.parse(url) ?: return false
        return runCatching { UrlSafety.checkStatic(u) }.isSuccess
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun render(ctx: Context, url: WebUrl, safety: UrlSafety): Rendered =
        lock.withLock {
            // The main-frame address is checked (with DNS) before anything loads.
            withContext(Dispatchers.IO) { safety.check(url) }
            val hostVerdicts = ConcurrentHashMap<String, Boolean>()
            fun allowed(raw: String): Boolean {
                val u = WebUrl.parse(raw) ?: return false
                return hostVerdicts.getOrPut(u.host + ":" + u.port) { runCatching { safety.check(u) }.isSuccess }
            }
            withContext(Dispatchers.Main) {
                val finished = CompletableDeferred<Unit>()
                var mainFrameError: String? = null
                val wv = WebView(ctx.applicationContext)
                try {
                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = false
                        allowContentAccess = false
                        setGeolocationEnabled(false)
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                        mediaPlaybackRequiresUserGesture = true
                        blockNetworkImage = true
                        loadsImagesAutomatically = false
                        cacheMode = WebSettings.LOAD_NO_CACHE
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        safeBrowsingEnabled = true
                        userAgentString = "$userAgentString MySI"
                    }
                    CookieManager.getInstance().setAcceptThirdPartyCookies(wv, false)
                    wv.setDownloadListener { _, _, _, _, _ -> }
                    wv.webChromeClient =
                        object : WebChromeClient() {
                            override fun onPermissionRequest(request: PermissionRequest) = request.deny()

                            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) =
                                callback.invoke(origin, false, false)

                            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?) = false

                            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
                                result.cancel()
                                return true
                            }

                            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
                                result.cancel()
                                return true
                            }

                            override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
                                result.cancel()
                                return true
                            }

                            override fun onJsBeforeUnload(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
                                result.confirm()
                                return true
                            }
                        }
                    wv.webViewClient =
                        object : WebViewClient() {
                            // Runs on a background thread for every request, so the DNS check is allowed here.
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                val u = request.url
                                val scheme = u.scheme?.lowercase()
                                if (scheme == "data" || scheme == "blob" || scheme == "about") return null
                                if (scheme != "http" && scheme != "https") return blockedResponse()
                                return if (allowed(u.toString())) null else blockedResponse()
                            }

                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val u = request.url.toString()
                                // Cancel other schemes (intent:, file:, market:…) and obviously local hosts.
                                return !(u.startsWith("http://") || u.startsWith("https://")) || !staticallySafe(u)
                            }

                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) = Unit

                            override fun onPageFinished(view: WebView, url: String?) {
                                if (!finished.isCompleted) finished.complete(Unit)
                            }

                            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                                mainFrameError = "the site's secure connection could not be verified"
                                handler.cancel()
                            }

                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                                if (request.isForMainFrame) mainFrameError = error.description?.toString() ?: "the page failed to load"
                            }
                        }
                    // Give it a phone-sized viewport so layout-dependent scripts run.
                    wv.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
                    wv.layout(0, 0, 1080, 2400)
                    wv.onResume()
                    wv.loadUrl(url.toString())
                    val loaded = withTimeoutOrNull(LOAD_TIMEOUT_MS) { finished.await() } != null
                    if (loaded) delay(SETTLE_MS)
                    mainFrameError?.let { if (!loaded || wv.progress < 50) throw IOException("Could not render the page: $it.") }
                    // Re-check where the page ended up before reading it.
                    val finalUrl = wv.url ?: url.toString()
                    val final = WebUrl.parse(finalUrl) ?: throw BlockedUrlException("The page moved to an address web tools cannot open.")
                    withContext(Dispatchers.IO) { safety.check(final) }
                    val html = extract(wv) ?: throw IOException("Could not read the rendered page" + if (!loaded) " (it did not finish loading in time)." else ".")
                    Rendered(html, final.toString())
                } finally {
                    wv.stopLoading()
                    wv.webChromeClient = null
                    wv.destroy()
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                    WebStorage.getInstance().deleteAllData()
                }
            }
        }

    /** document.documentElement.outerHTML, capped in JavaScript, decoded from the JSON string WebView returns. */
    private suspend fun extract(wv: WebView): String? =
        withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine { cont ->
                wv.evaluateJavascript(
                    "(function(){var d=document.documentElement;var h=d?d.outerHTML:'';return h.length>$MAX_HTML_CHARS?h.substring(0,$MAX_HTML_CHARS):h;})()",
                ) { json ->
                    val html = runCatching { JsonParser.parseString(json).takeIf { it.isJsonPrimitive }?.asString }.getOrNull()
                    if (cont.isActive) cont.resume(html?.takeIf { it.isNotEmpty() })
                }
            }
        }
}
