package com.zeroads.blocker

import android.content.Context
import android.content.Intent
import android.util.Log
import com.zeroads.blocker.vpn.AdBlockVpnService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object VpnCoordinator {
    private val _currentMode = MutableStateFlow(VpnMode.DISCONNECTED)
    val currentMode = _currentMode.asStateFlow()
    private val _preferredMode = MutableStateFlow(VpnMode.MODE_A_DNS_ONLY)
    val preferredMode = _preferredMode.asStateFlow()

    fun init(context: Context) {
        WireGuardManager.init(context)
        val saved = context.getSharedPreferences("vpn_preferences", Context.MODE_PRIVATE)
            .getString("preferred_mode", null)
        _preferredMode.value = saved?.let { runCatching { VpnMode.valueOf(it) }.getOrNull() }
            ?: VpnMode.MODE_A_DNS_ONLY
    }

    fun setPreferredMode(context: Context, mode: VpnMode) {
        _preferredMode.value = mode
        context.getSharedPreferences("vpn_preferences", Context.MODE_PRIVATE).edit()
            .putString("preferred_mode", mode.name)
            .apply()
    }

    fun setMode(context: Context, mode: VpnMode) {
        WireGuardManager.init(context)
        if (mode != VpnMode.DISCONNECTED) setPreferredMode(context, mode)
        if (_currentMode.value == mode && mode == VpnMode.DISCONNECTED) return

        // Stop current
        when (_currentMode.value) {
            VpnMode.MODE_A_DNS_ONLY -> stopDnsVpn(context)
            VpnMode.MODE_B_WIREGUARD_ONLY, VpnMode.MODE_C_WIREGUARD_PLUS_DNS -> stopWireGuard()
            VpnMode.DISCONNECTED -> {}
        }

        _currentMode.value = mode

        // Start new
        when (mode) {
            VpnMode.MODE_A_DNS_ONLY -> startDnsVpn(context)
            VpnMode.MODE_B_WIREGUARD_ONLY, VpnMode.MODE_C_WIREGUARD_PLUS_DNS -> startWireGuard(context)
            VpnMode.DISCONNECTED -> {}
        }
    }

    private fun startDnsVpn(context: Context) {
        val intent = Intent(context, AdBlockVpnService::class.java).apply {
            action = AdBlockVpnService.ACTION_START
        }
        try {
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            Log.e("ZeroAds", "Failed to start DNS VPN", e)
        }
    }

    private fun stopDnsVpn(context: Context) {
        val intent = Intent(context, AdBlockVpnService::class.java).apply {
            action = AdBlockVpnService.ACTION_STOP
        }
        try {
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            Log.e("ZeroAds", "Failed to stop DNS VPN", e)
        }
    }

    private fun startWireGuard(context: Context) {
        val config = WireGuardManager.savedConfig
        if (config != null) {
            WireGuardManager.connect(context, config)
        } else {
            Log.e("ZeroAds", "No WireGuard profile saved")
            _currentMode.value = VpnMode.DISCONNECTED
        }
    }

    private fun stopWireGuard() {
        WireGuardManager.disconnect()
    }
}
