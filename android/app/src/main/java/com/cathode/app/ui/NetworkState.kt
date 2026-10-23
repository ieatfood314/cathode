package com.cathode.app.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.cathode.app.data.isOnline
import com.cathode.app.data.isUnmetered

data class NetState(val online: Boolean, val unmetered: Boolean)

/**
 * Live connectivity. A one-off check at composition went stale: walking back
 * onto Wi-Fi left sync "paused" until something unrelated redrew the screen.
 */
@Composable
fun rememberNetState(context: Context): NetState {
    val app = context.applicationContext
    var state by remember { mutableStateOf(NetState(isOnline(app), isUnmetered(app))) }
    DisposableEffect(app) {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            fun recheck() {
                state = NetState(isOnline(app), isUnmetered(app))
            }

            override fun onAvailable(network: Network) = recheck()
            override fun onLost(network: Network) = recheck()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = recheck()
        }
        cm.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
        onDispose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }
    return state
}
