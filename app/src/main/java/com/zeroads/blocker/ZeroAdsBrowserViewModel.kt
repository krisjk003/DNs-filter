package com.zeroads.blocker

import android.app.Application
import android.content.Intent
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

@Serializable
data class Bookmark(val url: String, val title: String)

@Serializable
data class HistoryEntry(val url: String, val title: String, val timestamp: Long)

data class TabState(
    val id: String,
    var url: String = "https://google.com",
    var title: String = "New Tab",
    var progress: Float = 0f,
    var isLoading: Boolean = false,
    var canGoBack: Boolean = false,
    var canGoForward: Boolean = false,
    var webView: ZeroAdsCustomWebView? = null,
    var searchEngine: String = "https://www.google.com/search?q="
)

class ZeroAdsBrowserViewModel(application: Application) : AndroidViewModel(application) {
    val ruleManager = ZeroAdsRuleManager

    private val historyKey = stringPreferencesKey("browser_history")
    private val bookmarksKey = stringPreferencesKey("browser_bookmarks")

    val tabs = MutableStateFlow<List<TabState>>(listOf(TabState(id = UUID.randomUUID().toString())))
    val currentTabId = MutableStateFlow(tabs.value.first().id)

    val history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())

    val showTabSwitcher = MutableStateFlow(false)
    val showBookmarks = MutableStateFlow(false)
    val showHistory = MutableStateFlow(false)

    private val _webViewCommands = MutableStateFlow<String?>(null)
    val webViewCommands = _webViewCommands.asStateFlow()

    private val _lastSkippedSponsor = MutableStateFlow<String?>(null)
    val lastSkippedSponsor = _lastSkippedSponsor.asStateFlow()

    init {
        ZeroAdsRuleManager.init(application, viewModelScope)
        WebViewBridgeRelay.commandListener = { command ->
            _webViewCommands.update { command }
        }
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            getApplication<Application>().dataStore.data.collect { prefs ->
                val histStr = prefs[historyKey] ?: "[]"
                val bkmkStr = prefs[bookmarksKey] ?: "[]"
                try {
                    history.value = Json.decodeFromString(histStr)
                    bookmarks.value = Json.decodeFromString(bkmkStr)
                } catch (e: Exception) {}
            }
        }
    }

    fun addNewTab(url: String = "https://google.com") {
        val newTab = TabState(id = UUID.randomUUID().toString(), url = url)
        tabs.update { it + newTab }
        currentTabId.value = newTab.id
        showTabSwitcher.value = false
    }

    fun closeTab(id: String) {
        tabs.update { it.filter { t -> t.id != id } }
        if (tabs.value.isEmpty()) {
            addNewTab()
        } else if (currentTabId.value == id) {
            currentTabId.value = tabs.value.last().id
        }
    }

    fun switchTab(id: String) {
        currentTabId.value = id
        showTabSwitcher.value = false
    }

    fun addHistory(url: String, title: String) {
        if (url.startsWith("data:") || url.startsWith("chrome:")) return
        viewModelScope.launch {
            val newEntry = HistoryEntry(url, title, System.currentTimeMillis())
            val updated = (listOf(newEntry) + history.value).distinctBy { it.url }.take(100)
            history.value = updated
            saveData(historyKey, Json.encodeToString(updated))
        }
    }

    fun toggleBookmark(url: String, title: String) {
        viewModelScope.launch {
            val isBookmarked = bookmarks.value.any { it.url == url }
            val updated = if (isBookmarked) {
                bookmarks.value.filter { it.url != url }
            } else {
                bookmarks.value + Bookmark(url, title)
            }
            bookmarks.value = updated
            saveData(bookmarksKey, Json.encodeToString(updated))
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            history.value = emptyList()
            saveData(historyKey, "[]")
        }
    }

    private suspend fun saveData(key: androidx.datastore.preferences.core.Preferences.Key<String>, value: String) {
        getApplication<Application>().dataStore.edit { prefs ->
            prefs[key] = value
        }
    }

    fun updateTabState(id: String, mutator: (TabState) -> TabState) {
        tabs.update { list ->
            list.map { if (it.id == id) mutator(it) else it }
        }
    }

    fun clearCommand() {
        _webViewCommands.value = null
    }

    fun handleJsMessage(message: String) {
        try {
            val jsonObj = Json.parseToJsonElement(message).jsonObject
            val type = jsonObj["type"]?.jsonPrimitive?.content ?: return

            when (type) {
                "URL_BLOCKED" -> {
                    ZeroAdsRuleManager.incrementBlockedCount()
                }
                "MODULE_ERROR" -> {
                    val module = jsonObj["module"]?.jsonPrimitive?.content ?: "Unknown"
                    val err = jsonObj["error"]?.jsonPrimitive?.content ?: "Unknown error"
                    ZeroAdsRuleManager.reportModuleError(module, err)
                }
                "MEDIA_PLAY" -> {
                    val intent = Intent(getApplication(), ZeroAdsMediaPlaybackService::class.java).apply {
                        action = "SYNC_STATE"
                        putExtra("state", "PLAY")
                    }
                    try {
                        androidx.core.content.ContextCompat.startForegroundService(getApplication(), intent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                "MEDIA_PAUSE" -> {
                    val intent = Intent(getApplication(), ZeroAdsMediaPlaybackService::class.java).apply {
                        action = "SYNC_STATE"
                        putExtra("state", "PAUSE")
                    }
                    try {
                        androidx.core.content.ContextCompat.startForegroundService(getApplication(), intent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                "SPONSOR_SKIPPED" -> {
                    _lastSkippedSponsor.value = "Skipped Sponsor Segment"
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun toggleFeature(key: String, value: Boolean) {
        viewModelScope.launch {
            ZeroAdsRuleManager.updateToggle(getApplication(), key, value)
        }
    }
}
