package com.zeroads.blocker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import android.app.PictureInPictureParams
import android.webkit.CookieManager
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.zeroads.blocker.ui.theme.ZeroAdsTheme
import com.zeroads.blocker.ui.theme.Dashboard

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        var initialUrl = "https://m.youtube.com"

        // Handle Share intent from YouTube app
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (sharedText != null && (sharedText.contains("youtube.com") || sharedText.contains("youtu.be"))) {
                // Extract the first URL-like string
                val urlRegex = "(https?://[^\\s]+)".toRegex()
                val match = urlRegex.find(sharedText)
                if (match != null) {
                    initialUrl = match.value
                }
            }
        } else if (intent.action == Intent.ACTION_VIEW) {
            val data = intent.dataString
            if (data != null) {
                initialUrl = data
            }
        }

        enableEdgeToEdge()
        setContent {
            ZeroAdsTheme {
                var selectedTab by remember { mutableStateOf(0) }
                
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                icon = { Text("VPN") },
                                label = { Text("Dashboard") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                icon = { Text("Web") },
                                label = { Text("Browser") }
                            )
                        }
                    }
                ) { innerPadding ->
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        if (selectedTab == 0) {
                            Dashboard()
                        } else {
                            ZeroAdsBrowserScreen(initialUrl = initialUrl)
                        }
                    }
                }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        lifecycleScope.launch {
            val pipEnabled = ZeroAdsRuleManager.toggles.value.pipEnabled
            if (pipEnabled) {
                enterPictureInPictureMode(PictureInPictureParams.Builder().build())
            }
        }
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        super.onPause()
    }
}