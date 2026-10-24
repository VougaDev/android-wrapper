package com.vougadev.shell.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper

/**
 * Whether the phone can reach the internet now, followed live: [online] for the WebView's request threads, [onChange] on
 * the main thread when it flips. A network the system has not validated yet (a captive portal) counts as offline.
 */
class Connectivity(context: Context, private val onChange: (Boolean) -> Unit) {

    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var online: Boolean = current()
        private set

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = update(usable(capabilities))
        override fun onLost(network: Network) = update(current())
        override fun onUnavailable() = update(false)
    }

    fun start() {
        try {
            manager?.registerDefaultNetworkCallback(callback)
        } catch (_: RuntimeException) {
            // too many callbacks registered by the process: the state read at start stands
        }
    }

    fun stop() {
        try {
            manager?.unregisterNetworkCallback(callback)
        } catch (_: IllegalArgumentException) {
            // never registered
        }
    }

    private fun update(now: Boolean) {
        if (now == online) return
        online = now
        main.post { onChange(now) }
    }

    private fun current(): Boolean {
        val capabilities = manager?.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return usable(capabilities)
    }

    private fun usable(capabilities: NetworkCapabilities) =
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
