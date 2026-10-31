package com.zeroads.blocker

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "zeroads_settings")

@Serializable
data class QueryParamRule(val host: String, val param: String, val value: String)

@Serializable
data class YouTubeRulesConfig(
    val version: Int = 1,
    val blockedUrlPatterns: List<String> = emptyList(),
    val blockedQueryParams: List<QueryParamRule> = emptyList(),
    val jsonPruneKeys: List<String> = emptyList(),
    val cosmeticCssSelectors: List<String> = emptyList(),
    val feedCleanupCss: Map<String, String> = emptyMap(),
    val skipButtonSelectors: List<String> = emptyList()
)

@Serializable
data class FeatureToggles(
    val coreAdBlockEnabled: Boolean = true,
    val backgroundPlayEnabled: Boolean = true,
    val pipEnabled: Boolean = true,
    val sponsorBlockEnabled: Boolean = true,
    val hideShorts: Boolean = false,
    val hideComments: Boolean = false,
    val hideRelated: Boolean = false,
    val rydEnabled: Boolean = false
)

object ZeroAdsRuleManager {
    private const val RULES_FILENAME = "cached_yt_rules.json"
    // In a real app this would point to your raw JSON endpoint
    private const val REMOTE_RULES_URL = "https://raw.githubusercontent.com/example/zeroads/main/rules.json"

    private val json = Json { ignoreUnknownKeys = true }

    private val _rules = MutableStateFlow(YouTubeRulesConfig())
    val rules = _rules.asStateFlow()

    private val _toggles = MutableStateFlow(FeatureToggles())
    val toggles = _toggles.asStateFlow()

    private val _moduleHealth = MutableStateFlow(mapOf<String, String>())
    val moduleHealth = _moduleHealth.asStateFlow()

    private val _blockedCount = MutableStateFlow(0)
    val blockedCount = _blockedCount.asStateFlow()

    fun incrementBlockedCount() {
        _blockedCount.update { it + 1 }
    }

    fun reportModuleError(module: String, error: String) {
        _moduleHealth.update { it + (module to error) }
    }

    fun clearErrors() {
        _moduleHealth.value = emptyMap()
    }

    fun init(context: Context, scope: CoroutineScope) {
        // Load toggles from DataStore
        scope.launch(Dispatchers.IO) {
            context.dataStore.data.collect { prefs ->
                _toggles.value = FeatureToggles(
                    coreAdBlockEnabled = prefs[booleanPreferencesKey("coreAdBlockEnabled")] ?: true,
                    backgroundPlayEnabled = prefs[booleanPreferencesKey("backgroundPlayEnabled")] ?: true,
                    pipEnabled = prefs[booleanPreferencesKey("pipEnabled")] ?: true,
                    sponsorBlockEnabled = prefs[booleanPreferencesKey("sponsorBlockEnabled")] ?: true,
                    hideShorts = prefs[booleanPreferencesKey("hideShorts")] ?: false,
                    hideComments = prefs[booleanPreferencesKey("hideComments")] ?: false,
                    hideRelated = prefs[booleanPreferencesKey("hideRelated")] ?: false,
                    rydEnabled = prefs[booleanPreferencesKey("rydEnabled")] ?: false
                )
            }
        }

        // Load Rules
        scope.launch(Dispatchers.IO) {
            val cachedFile = File(context.filesDir, RULES_FILENAME)
            var configStr: String? = null

            if (cachedFile.exists()) {
                configStr = cachedFile.readText()
            } else {
                try {
                    context.assets.open("default_yt_rules.json").bufferedReader().use {
                        configStr = it.readText()
                    }
                } catch (e: Exception) {
                    Log.e("ZeroAds", "Failed to load default rules", e)
                }
            }

            if (configStr != null) {
                try {
                    _rules.value = json.decodeFromString(configStr!!)
                } catch (e: Exception) {
                    Log.e("ZeroAds", "Failed to parse rules", e)
                }
            }

            // Try fetching updates
            fetchRemoteRules(context)
        }
    }

    fun fetchRemoteRules(context: Context) {
        Thread {
            try {
                // Placeholder network fetch
                // val text = java.net.URL(REMOTE_RULES_URL).readText()
                // val newConfig = json.decodeFromString<YouTubeRulesConfig>(text)
                // if (newConfig.version > _rules.value.version) {
                //     _rules.value = newConfig
                //     File(context.filesDir, RULES_FILENAME).writeText(text)
                // }
            } catch (e: Exception) {
                Log.e("ZeroAds", "Remote rule fetch failed", e)
                reportModuleError("RuleManager", "Fetch failed: ${e.message}")
            }
        }.start()
    }

    suspend fun updateToggle(context: Context, key: String, value: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[booleanPreferencesKey(key)] = value
        }
    }
}