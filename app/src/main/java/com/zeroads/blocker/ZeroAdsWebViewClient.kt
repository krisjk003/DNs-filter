package com.zeroads.blocker

import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

open class ZeroAdsWebViewClient(
    private val ruleManager: ZeroAdsRuleManager,
    private val onRendererCrash: (String?) -> Unit
) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        if (!ruleManager.toggles.value.coreAdBlockEnabled) {
            return super.shouldInterceptRequest(view, request)
        }

        val url = request.url.toString()
        val rules = ruleManager.rules.value

        var shouldBlock = false

        if (rules.blockedUrlPatterns.any { url.contains(it) }) {
            shouldBlock = true
        }

        if (!shouldBlock && request.url.host?.contains("googlevideo.com") == true) {
            rules.blockedQueryParams.forEach { paramRule ->
                if (request.url.getQueryParameter(paramRule.param) == paramRule.value) {
                    shouldBlock = true
                }
            }
        }

        if (shouldBlock) {
            ruleManager.incrementBlockedCount()
            val origin = request.requestHeaders["Origin"] ?: request.requestHeaders["origin"]
            val headers = mutableMapOf<String, String>()
            if (origin != null) {
                headers["Access-Control-Allow-Origin"] = origin
                headers["Access-Control-Allow-Credentials"] = "true"
            }
            return WebResourceResponse(
                "text/plain",
                "UTF-8",
                204,
                "No Content",
                headers,
                ByteArrayInputStream(ByteArray(0))
            )
        }

        return super.shouldInterceptRequest(view, request)
    }

    @Deprecated("Deprecated in Java")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        val uri = android.net.Uri.parse(url)
        return BrowserNavigationPolicy.shouldOverrideUrlLoading(view, uri)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        return BrowserNavigationPolicy.shouldOverrideUrlLoading(view, request.url)
    }

    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
        // ALWAYS cancel the request for SSL errors for security
        handler?.cancel()
    }

    override fun onRenderProcessGone(
        view: WebView?,
        detail: android.webkit.RenderProcessGoneDetail?
    ): Boolean {
        var lastUrl: String? = null
        if (view != null) {
            lastUrl = view.url
            val parent = view.parent as? android.view.ViewGroup
            parent?.removeView(view)
            view.destroy()
        }
        onRendererCrash(lastUrl)
        return true
    }
}
