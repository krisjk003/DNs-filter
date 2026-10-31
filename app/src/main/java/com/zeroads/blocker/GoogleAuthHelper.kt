package com.zeroads.blocker

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent

object GoogleAuthHelper {

    fun isGoogleAuthUrl(uri: Uri): Boolean {
        if (uri.scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false

        // Strict hostname validation: accounts.google.com or subdomains of accounts.google.com / accounts.youtube.com
        val isAuthHost = (host == "accounts.google.com" || host.endsWith(".accounts.google.com") ||
                          host == "accounts.youtube.com" || host.endsWith(".accounts.youtube.com"))

        if (!isAuthHost) return false

        val path = uri.path?.lowercase() ?: ""
        return path.contains("servicelogin") ||
               path.contains("signin") ||
               path.contains("oauth") ||
               path.contains("accountchooser") ||
               path.contains("v3/signin") ||
               path.contains("interactive") ||
               path.contains("setsid") ||
               path.contains("auth") ||
               path.isEmpty() || path == "/"
    }

    fun launchCustomTab(context: Context, uri: Uri) {
        try {
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setShareState(CustomTabsIntent.SHARE_STATE_ON)
                .build()
            customTabsIntent.launchUrl(context, uri)
        } catch (e: Exception) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, uri)
                context.startActivity(intent)
            } catch (ex: Exception) {
                Toast.makeText(context, "Could not open external browser for authentication", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
