package com.zeroads.blocker

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.WebView

object BrowserNavigationPolicy {
    private const val TAG = "BrowserNavPolicy"

    private val YOUTUBE_HOSTS = setOf(
        "youtube.com",
        "www.youtube.com",
        "m.youtube.com",
        "music.youtube.com",
        "youtu.be",
        "www.youtube-nocookie.com"
    )

    fun isYouTubeHost(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        return YOUTUBE_HOSTS.contains(host)
    }

    /**
     * Evaluates the URL and decides how it should be handled.
     * @return true if the navigation is consumed/overridden, false if WebView should handle it natively.
     */
    fun shouldOverrideUrlLoading(view: WebView, uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: ""
        val host = uri.host?.lowercase() ?: ""

        // 1. Google Authentication
        if (GoogleAuthHelper.isGoogleAuthUrl(uri)) {
            Log.d(TAG, "Routing to Google Auth Flow: $uri")
            GoogleAuthHelper.launchCustomTab(view.context, uri)
            return true
        }

        // 2. YouTube Intent URLs (intent:// or vnd.youtube://)
        if (scheme == "intent" || scheme == "vnd.youtube") {
            Log.d(TAG, "Intercepted YouTube Intent: $uri")
            return handleIntentUrl(view, uri)
        }

        // 3. Normal HTTP/HTTPS
        if (scheme == "http" || scheme == "https") {
            // Keep YouTube and all normal browsing inside the WebView
            return false
        }

        // 4. Other Application-specific schemes (mailto:, tel:, market:, etc.)
        if (scheme == "mailto" || scheme == "tel" || scheme == "market") {
            return launchExternalAppSafe(view.context, uri)
        }

        // 5. Unsafe or unknown schemes
        Log.w(TAG, "Blocked unsafe or unknown scheme: $scheme")
        return true
    }

    private fun handleIntentUrl(view: WebView, uri: Uri): Boolean {
        try {
            val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
            val fallbackUri = intent.getStringExtra("browser_fallback_url")
                ?.let(Uri::parse)
                ?: intent.data

            if (fallbackUri != null && isYouTubeHttpsUrl(fallbackUri)) {
                Log.d(TAG, "Redirecting YouTube intent to an HTTPS WebView URL")
                view.loadUrl(fallbackUri.toString())
                return true
            }

            // Fallback for vnd.youtube:// if no browser_fallback_url exists
            if (uri.scheme == "vnd.youtube") {
                val videoId = uri.getQueryParameter("v")
                    ?: uri.pathSegments.lastOrNull()?.takeIf { it.isNotBlank() }
                if (videoId != null) {
                    val httpsUri = Uri.Builder()
                        .scheme("https")
                        .authority("m.youtube.com")
                        .path("/watch")
                        .appendQueryParameter("v", videoId)
                        .build()
                    Log.d(TAG, "Converted YouTube app link to an HTTPS WebView URL")
                    view.loadUrl(httpsUri.toString())
                    return true
                }
            }

            // If it's a YouTube intent but we can't extract a fallback, just go to m.youtube.com
            val packageId = intent.`package`
            if (packageId == "com.google.android.youtube") {
                Log.d(TAG, "Unresolved YouTube intent, redirecting to home")
                view.loadUrl("https://m.youtube.com")
                return true
            }

            // For non-YouTube intents, try to launch if safe, else cancel
            return launchExternalAppSafe(view.context, uri)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse intent URI", e)
            return true // Consume to prevent crash
        }
    }

    private fun isYouTubeHttpsUrl(uri: Uri): Boolean {
        return (uri.scheme == "http" || uri.scheme == "https") && isYouTubeHost(uri)
    }

    private fun launchExternalAppSafe(context: Context, uri: Uri): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri)
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch external app for URI: $uri", e)
            true
        }
    }
}
