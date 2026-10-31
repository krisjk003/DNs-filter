package com.zeroads.blocker

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.view.ViewGroup
import android.webkit.URLUtil
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

val NebulaGradient = Brush.verticalGradient(colors = listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF2C5364)))
val CyberAccent = Color(0xFF00E5FF)

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ZeroAdsBrowserScreen(
    viewModel: ZeroAdsBrowserViewModel = viewModel(),
    initialUrl: String = "https://google.com"
) {
    val context = LocalContext.current
    val tabs by viewModel.tabs.collectAsState()
    val currentTabId by viewModel.currentTabId.collectAsState()
    val history by viewModel.history.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val showTabSwitcher by viewModel.showTabSwitcher.collectAsState()
    val toggles by viewModel.ruleManager.toggles.collectAsState()
    val rules by viewModel.ruleManager.rules.collectAsState()
    val webViewCommand by viewModel.webViewCommands.collectAsState()

    var showOverflowMenu by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }

    val currentTab = tabs.find { it.id == currentTabId } ?: return

    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var urlInput by remember(currentTabId) { mutableStateOf(currentTab.url) }
    var isEditingUrl by remember { mutableStateOf(false) }

    LaunchedEffect(currentTab.url) {
        if (!isEditingUrl) {
            urlInput = currentTab.url
        }
    }

    LaunchedEffect(webViewCommand) {
        webViewCommand?.let { cmd ->
            currentTab.webView?.evaluateJavascript(
                "window.postMessage({type: 'ZEROADS_COMMAND', command: '$cmd'}, '*');", null
            )
            viewModel.clearCommand()
        }
    }

    BackHandler(enabled = currentTab.canGoBack || showTabSwitcher || showHistory || showBookmarks || showSettings) {
        if (showTabSwitcher) viewModel.showTabSwitcher.value = false
        else if (showHistory) showHistory = false
        else if (showBookmarks) showBookmarks = false
        else if (showSettings) showSettings = false
        else currentTab.webView?.goBack()
    }

    if (showTabSwitcher) {
        TabSwitcherScreen(
            tabs = tabs,
            currentTabId = currentTabId,
            onCloseTab = { viewModel.closeTab(it) },
            onSwitchTab = { viewModel.switchTab(it) },
            onNewTab = { viewModel.addNewTab("zeroads://newtab") }
        )
        return
    }

    if (showHistory) {
        HistoryScreen(history, { viewModel.clearHistory() }, {
            currentTab.webView?.loadUrl(it)
            showHistory = false
        }, { showHistory = false })
        return
    }

    if (showBookmarks) {
        BookmarksScreen(bookmarks, { u, t -> viewModel.toggleBookmark(u, t) }, {
            currentTab.webView?.loadUrl(it)
            showBookmarks = false
        }, { showBookmarks = false })
        return
    }

    Scaffold(
        bottomBar = {
            BrowserToolbar(
                urlInput = urlInput,
                onUrlChange = { urlInput = it },
                isEditingUrl = isEditingUrl,
                onEditFocusChange = { isEditingUrl = it },
                focusRequester = focusRequester,
                keyboardController = keyboardController,
                currentTab = currentTab,
                tabCount = tabs.size,
                onGo = {
                    val url = normalizeUrl(urlInput)
                    currentTab.webView?.loadUrl(url)
                    isEditingUrl = false
                    keyboardController?.hide()
                },
                onShowTabs = { viewModel.showTabSwitcher.value = true },
                onShowMenu = { showOverflowMenu = true }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(NebulaGradient)
        ) {
            if (currentTab.url == "zeroads://newtab" || currentTab.url.isEmpty()) {
                NewTabPage(
                    onNavigate = { url ->
                        urlInput = url
                        currentTab.webView?.loadUrl(url)
                    },
                    bookmarks = bookmarks
                )
            } else {
                // WebView
                var webViewLoadError by remember(currentTabId) { mutableStateOf(false) }

                if (webViewLoadError) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("Renderer process crashed.", color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            webViewLoadError = false
                            currentTab.webView?.loadUrl(currentTab.url)
                        }) {
                            Text("Recover WebView")
                        }
                    }
                } else {
                    AndroidView(
                        factory = { ctx ->
                            if (currentTab.webView != null) {
                                (currentTab.webView?.parent as? ViewGroup)?.removeView(currentTab.webView)
                                return@AndroidView currentTab.webView!!
                            }
                            ZeroAdsCustomWebView(ctx).apply {
                                this.onWebViewTouched = {
                                    if (isEditingUrl) {
                                        isEditingUrl = false
                                        focusManager.clearFocus()
                                        keyboardController?.hide()
                                    }
                                }
                                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.databaseEnabled = true
                                val cookieManager = CookieManager.getInstance()
                                cookieManager.setAcceptCookie(true)
                                cookieManager.setAcceptThirdPartyCookies(this, true)
                                settings.mediaPlaybackRequiresUserGesture = false
                                settings.setSupportMultipleWindows(true)
                                settings.javaScriptCanOpenWindowsAutomatically = true

                                setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                                    try {
                                        val request = DownloadManager.Request(Uri.parse(url)).apply {
                                            setMimeType(mimetype)
                                            addRequestHeader("User-Agent", userAgent)
                                            setDescription("Downloading file...")
                                            setTitle(URLUtil.guessFileName(url, contentDisposition, mimetype))
                                            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, contentDisposition, mimetype))
                                        }
                                        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                        dm.enqueue(request)
                                        Toast.makeText(context, "Downloading File", Toast.LENGTH_LONG).show()
                                    } catch (_: Exception) {
                                        Toast.makeText(context, "Download failed", Toast.LENGTH_LONG).show()
                                    }
                                }

                                webViewClient = object : ZeroAdsWebViewClient(ZeroAdsRuleManager, { url ->
                                    if (url != null) {
                                        viewModel.updateTabState(currentTabId) { it.copy(url = url) }
                                    }
                                    webViewLoadError = true
                                }) {
                                    override fun onPageFinished(view: WebView, url: String) {
                                        super.onPageFinished(view, url)
                                        val realTitle = view.title ?: "Untitled"
                                        viewModel.updateTabState(currentTabId) {
                                            it.copy(
                                                url = url,
                                                title = realTitle,
                                                isLoading = false,
                                                canGoBack = view.canGoBack(),
                                                canGoForward = view.canGoForward()
                                            )
                                        }
                                        if (url != "zeroads://newtab" && url != "about:blank") {
                                            viewModel.addHistory(url, realTitle)
                                        }
                                    }
                                }

                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView, newProgress: Int) {
                                        viewModel.updateTabState(currentTabId) {
                                            it.copy(progress = newProgress / 100f, isLoading = newProgress < 100)
                                        }
                                    }

                                    override fun onCreateWindow(
                                        view: WebView?,
                                        isDialog: Boolean,
                                        isUserGesture: Boolean,
                                        resultMsg: android.os.Message?
                                    ): Boolean {
                                        val result = view?.hitTestResult
                                        val data = result?.extra
                                        if (data != null) {
                                            val uri = Uri.parse(data)
                                            if (GoogleAuthHelper.isGoogleAuthUrl(uri)) {
                                                GoogleAuthHelper.launchCustomTab(view.context, uri)
                                                return true
                                            }
                                            if (BrowserNavigationPolicy.shouldOverrideUrlLoading(view, uri)) {
                                                return true
                                            }
                                        }
                                        val tempWebView = WebView(view!!.context)
                                        tempWebView.webViewClient = object : android.webkit.WebViewClient() {
                                            override fun shouldOverrideUrlLoading(v: WebView, req: android.webkit.WebResourceRequest): Boolean {
                                                val targetUri = req.url
                                                val currentWebView = currentTab.webView
                                                if (currentWebView != null) {
                                                    val handled = BrowserNavigationPolicy.shouldOverrideUrlLoading(currentWebView, targetUri)
                                                    if (!handled) {
                                                        currentWebView.loadUrl(targetUri.toString())
                                                    }
                                                } else {
                                                    // Fallback if current tab's WebView isn't ready
                                                    if (GoogleAuthHelper.isGoogleAuthUrl(targetUri)) {
                                                        GoogleAuthHelper.launchCustomTab(v.context, targetUri)
                                                    }
                                                }
                                                return true
                                            }

                                        }
                                        val transport = resultMsg?.obj as? WebView.WebViewTransport
                                        transport?.webView = tempWebView
                                        resultMsg?.sendToTarget()
                                        return true
                                    }
                                }

                                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                                    WebViewCompat.addWebMessageListener(this, "ZeroAdsBridge", setOf("https://*.youtube.com", "https://youtube.com"), object : WebViewCompat.WebMessageListener {
                                        override fun onPostMessage(view: WebView, message: WebMessageCompat, sourceOrigin: Uri, isMainFrame: Boolean, replyProxy: androidx.webkit.JavaScriptReplyProxy) {
                                            val host = sourceOrigin.host ?: ""
                                            if (isMainFrame && (host.endsWith("youtube.com") || host == "youtube.com")) {
                                                message.data?.let { viewModel.handleJsMessage(it) }
                                            }
                                        }
                                    })
                                }

                                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                                    val script = DocumentStartScriptBuilder.buildScript(rules, toggles)
                                    WebViewCompat.addDocumentStartJavaScript(this, script, setOf("*"))
                                }

                                viewModel.updateTabState(currentTabId) { it.copy(webView = this) }
                                val loadUrl = if (currentTab.url.isEmpty()) initialUrl else currentTab.url
                                if (loadUrl != "zeroads://newtab") {
                                    loadUrl(loadUrl)
                                }
                            }
                        },
                        update = { webView ->
                            if (webView is ZeroAdsCustomWebView) {
                                webView.backgroundPlayEnabled = toggles.backgroundPlayEnabled
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                if (currentTab.isLoading) {
                    LinearProgressIndicator(
                        progress = { currentTab.progress },
                        modifier = Modifier.fillMaxWidth().height(2.dp).align(Alignment.TopCenter),
                        color = CyberAccent,
                        trackColor = Color.Transparent
                    )
                }
            }
        }
    }

    if (showOverflowMenu) {
        ModalBottomSheet(onDismissRequest = { showOverflowMenu = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                val isBookmarked = bookmarks.any { it.url == currentTab.url }
                MenuItem("＋", "New Tab") { viewModel.addNewTab("zeroads://newtab"); showOverflowMenu = false }
                MenuItem(if (isBookmarked) "★" else "☆", if (isBookmarked) "Remove Bookmark" else "Add Bookmark") {
                    viewModel.toggleBookmark(currentTab.url, currentTab.title)
                    showOverflowMenu = false
                }
                MenuItem("⟳", "Reload") { currentTab.webView?.reload(); showOverflowMenu = false }
                MenuItem("↗", "Share") { showOverflowMenu = false }
                MenuItem("🕒", "History") { showHistory = true; showOverflowMenu = false }
                MenuItem("🔖", "Bookmarks") { showBookmarks = true; showOverflowMenu = false }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                MenuItem("⚙", "Browser Settings") { showSettings = true; showOverflowMenu = false }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            LazyColumn(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                item {
                    Text("ZeroAds Browser Settings", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(16.dp))
                }
                item { ToggleRow(title = "Core Ad & Payload Blocker", checked = toggles.coreAdBlockEnabled) { viewModel.toggleFeature("coreAdBlockEnabled", it) } }
                item { ToggleRow(title = "Background Play & Audio", checked = toggles.backgroundPlayEnabled) { viewModel.toggleFeature("backgroundPlayEnabled", it) } }
                item { ToggleRow(title = "Picture-in-Picture (PiP)", checked = toggles.pipEnabled) { viewModel.toggleFeature("pipEnabled", it) } }
                item { ToggleRow(title = "YouTube SponsorBlock", checked = toggles.sponsorBlockEnabled) { viewModel.toggleFeature("sponsorBlockEnabled", it) } }
                item { ToggleRow(title = "Hide YouTube Shorts", checked = toggles.hideShorts) { viewModel.toggleFeature("hideShorts", it) } }
                item { Spacer(modifier = Modifier.height(32.dp)) }
            }
        }
    }
}

@Composable
fun NewTabPage(onNavigate: (String) -> Unit, bookmarks: List<Bookmark>) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("ZEROADS", style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold, color = Color.White))
        Text("NEBULA", style = MaterialTheme.typography.titleMedium.copy(color = CyberAccent, letterSpacing = 8.sp))
        Spacer(modifier = Modifier.height(48.dp))

        if (bookmarks.isNotEmpty()) {
            Text("Favorites", style = MaterialTheme.typography.titleMedium, color = Color.White)
            Spacer(modifier = Modifier.height(16.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(bookmarks.take(8)) { bkmk ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { onNavigate(bkmk.url) }
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(bkmk.title.take(1).uppercase(), fontSize = 20.sp, color = CyberAccent)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(bkmk.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = Color.White)
                    }
                }
            }
        }
    }
}

fun normalizeUrl(input: String): String {
    val trimmed = input.trim()
    return if (URLUtil.isValidUrl(trimmed)) {
        trimmed
    } else if (trimmed.contains(".") && !trimmed.contains(" ")) {
        "https://$trimmed"
    } else {
        "https://www.google.com/search?q=${Uri.encode(trimmed)}"
    }
}

@Composable
fun BrowserToolbar(
    urlInput: String,
    onUrlChange: (String) -> Unit,
    isEditingUrl: Boolean,
    onEditFocusChange: (Boolean) -> Unit,
    focusRequester: FocusRequester,
    keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController?,
    currentTab: TabState,
    tabCount: Int,
    onGo: () -> Unit,
    onShowTabs: () -> Unit,
    onShowMenu: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding(),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
        shadowElevation = 8.dp
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isEditingUrl) {
                    IconButton(onClick = { currentTab.webView?.goBack() }, enabled = currentTab.canGoBack) {
                        Text("◁", color = if (currentTab.canGoBack) MaterialTheme.colorScheme.onSurface else Color.Gray)
                    }
                    IconButton(onClick = { currentTab.webView?.goForward() }, enabled = currentTab.canGoForward) {
                        Text("▷", color = if (currentTab.canGoForward) MaterialTheme.colorScheme.onSurface else Color.Gray)
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        if (!isEditingUrl && currentTab.url.startsWith("https://")) {
                            Text("🔒", modifier = Modifier.padding(end = 8.dp))
                        }
                        BasicTextField(
                            value = urlInput,
                            onValueChange = onUrlChange,
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focusRequester)
                                .onFocusChanged { state ->
                                    if (state.isFocused && !isEditingUrl) {
                                        onEditFocusChange(true)
                                    } else if (!state.isFocused && isEditingUrl) {
                                        onEditFocusChange(false)
                                    }
                                },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 16.sp
                            ),
                            cursorBrush = SolidColor(CyberAccent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { onGo() }),
                            decorationBox = { innerTextField ->
                                if (urlInput.isEmpty()) {
                                    Text("Search or type URL", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else if (!isEditingUrl && currentTab.url != "zeroads://newtab") {
                                    val displayUrl = try { Uri.parse(urlInput).host ?: urlInput } catch (e: Exception) { urlInput }
                                    Text(displayUrl, color = MaterialTheme.colorScheme.onSurface)
                                } else {
                                    innerTextField()
                                }
                            }
                        )
                        if (isEditingUrl && urlInput.isNotEmpty()) {
                            IconButton(onClick = { onUrlChange("") }, modifier = Modifier.size(24.dp)) {
                                Text("X", color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }

                if (!isEditingUrl) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onShowTabs() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(tabCount.toString(), style = MaterialTheme.typography.titleSmall)
                    }
                    IconButton(onClick = onShowMenu) {
                        Text("⋮")
                    }
                }
            }
        }
    }
}

@Composable
fun MenuItem(iconText: String, title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(iconText, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 24.sp)
        Spacer(modifier = Modifier.width(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabSwitcherScreen(
    tabs: List<TabState>,
    currentTabId: String,
    onCloseTab: (String) -> Unit,
    onSwitchTab: (String) -> Unit,
    onNewTab: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tabs") },
                actions = {
                    IconButton(onClick = onNewTab) {
                        Text("＋")
                    }
                }
            )
        }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(tabs) { tab ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clickable { onSwitchTab(tab.id) },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (tab.id == currentTabId) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.2f))
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(if (tab.title.isEmpty() || tab.url == "zeroads://newtab") "New Tab" else tab.title, maxLines = 1, modifier = Modifier.weight(1f), fontSize = 12.sp)
                            IconButton(onClick = { onCloseTab(tab.id) }, modifier = Modifier.size(24.dp)) {
                                Text("X", fontSize = 16.sp)
                            }
                        }
                        Box(modifier = Modifier.fillMaxSize().background(Color.DarkGray), contentAlignment = Alignment.Center) {
                            Text(tab.url, maxLines = 2, fontSize = 10.sp, color = Color.LightGray, modifier = Modifier.padding(8.dp))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(history: List<HistoryEntry>, onClearAll: () -> Unit, onOpen: (String) -> Unit, onClose: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Text("◁") }
                },
                actions = {
                    TextButton(onClick = onClearAll) { Text("Clear") }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding)) {
            items(history) { entry ->
                Column(modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(entry.url) }
                    .padding(16.dp)
                ) {
                    Text(entry.title, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                    Text(entry.url, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksScreen(bookmarks: List<Bookmark>, onDelete: (String, String) -> Unit, onOpen: (String) -> Unit, onClose: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bookmarks") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Text("◁") }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding)) {
            items(bookmarks) { bkmk ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(bkmk.url) }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(bkmk.title, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                        Text(bkmk.url, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onDelete(bkmk.url, bkmk.title) }) {
                        Text("Del")
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun ToggleRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
