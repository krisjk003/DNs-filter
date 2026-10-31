package com.zeroads.blocker

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64

enum class VpnMode {
    DISCONNECTED,
    MODE_A_DNS_ONLY,
    MODE_B_WIREGUARD_ONLY,
    MODE_C_WIREGUARD_PLUS_DNS
}

data class WireGuardStats(
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
    val endpoint: String? = null,
    val assignedIp: String? = null
)

enum class WireGuardConnectionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, ERROR }

object WireGuardManager {
    private var backend: Backend? = null
    private var activeTunnel: Tunnel? = null
    private var appContext: Context? = null
    private var _savedConfig: Config? = null
    val savedConfig: Config? get() = _savedConfig

    private val _wgStats = MutableStateFlow(WireGuardStats())
    val wgStats = _wgStats.asStateFlow()
    private val _connectionState = MutableStateFlow(WireGuardConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError = _lastError.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        if (_savedConfig == null) {
            _savedConfig = WireGuardConfigStore.load(context)
        }
        if (backend == null) {
            backend = GoBackend(context)
        }
        restoreBackendState()
    }

    fun parseConfig(inputStream: InputStream): Config? {
        return try {
            Config.parse(inputStream)
        } catch (e: Exception) {
            Log.e("ZeroAds", "Failed to parse WireGuard config: ${e.message}")
            null
        }
    }

    fun saveConfig(context: Context, config: Config) {
        require(config.`interface`.addresses.isNotEmpty()) { "WireGuard profile has no tunnel address" }
        require(config.peers.isNotEmpty()) { "WireGuard profile has no peer" }
        require(config.peers.any { it.endpoint.isPresent }) { "WireGuard profile has no server endpoint" }
        WireGuardConfigStore.save(context, config)
        _savedConfig = config
        _lastError.value = null
    }

    fun connect(context: Context, config: Config) {
        init(context)
        _connectionState.value = WireGuardConnectionState.CONNECTING
        _lastError.value = null
        val tunnel = WgTunnel("ZeroAdsWG")
        activeTunnel = tunnel
        try {
            backend?.setState(tunnel, Tunnel.State.UP, config)
            if (backend?.getState(tunnel) != Tunnel.State.UP) {
                throw IllegalStateException("WireGuard backend did not report an active tunnel")
            }
            _wgStats.update {
                it.copy(
                    endpoint = config.peers.firstOrNull()?.endpoint?.map { ep -> ep.toString() }?.orElse("Unknown"),
                    assignedIp = config.`interface`.addresses.joinToString(", ")
                )
            }
            _connectionState.value = WireGuardConnectionState.CONNECTED
        } catch (e: Exception) {
            activeTunnel = null
            _connectionState.value = WireGuardConnectionState.ERROR
            _lastError.value = e.message ?: "WireGuard connection failed"
            Log.e("ZeroAds", "Failed to connect WireGuard: ${e.message}")
        }
    }

    fun disconnect() {
        try {
            activeTunnel?.let { backend?.setState(it, Tunnel.State.DOWN, null) }
            activeTunnel = null
            _wgStats.update { WireGuardStats() }
            _connectionState.value = WireGuardConnectionState.DISCONNECTED
            _lastError.value = null
        } catch (e: Exception) {
            _connectionState.value = WireGuardConnectionState.ERROR
            _lastError.value = e.message ?: "WireGuard disconnect failed"
            Log.e("ZeroAds", "Failed to disconnect WireGuard: ${e.message}")
        }
    }

    fun getBackendState(): Tunnel.State {
        return activeTunnel?.let { backend?.getState(it) } ?: Tunnel.State.DOWN
    }

    private fun restoreBackendState() {
        val restoredTunnel = WgTunnel("ZeroAdsWG")
        try {
            if (backend?.getState(restoredTunnel) == Tunnel.State.UP) {
                activeTunnel = restoredTunnel
                _connectionState.value = WireGuardConnectionState.CONNECTED
            }
        } catch (_: Exception) {
            _connectionState.value = WireGuardConnectionState.DISCONNECTED
        }
    }
}

private object WireGuardConfigStore {
    private const val PREFS = "wireguard_profile"
    private const val CONFIG = "encrypted_config"
    private const val KEY_ALIAS = "zeroads_wireguard_config_key"

    fun save(context: Context, config: Config) {
        val encrypted = encrypt(config.toWgQuickString())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(CONFIG, encrypted)
            .apply()
    }

    fun load(context: Context): Config? {
        val encrypted = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CONFIG, null)
            ?: return null
        return try {
            Config.parse(decrypt(encrypted).byteInputStream(StandardCharsets.UTF_8))
        } catch (e: Exception) {
            Log.e("ZeroAds", "Stored WireGuard profile is invalid: ${e.message}")
            null
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), StandardCharsets.UTF_8)
    }
}

class WgTunnel(private val name: String) : Tunnel {
    override fun getName(): String = name
    override fun onStateChange(newState: Tunnel.State) {
        Log.d("ZeroAds", "WG State changed to $newState")
    }
}
