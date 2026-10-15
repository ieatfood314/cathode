package com.ruminate.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.prefs by preferencesDataStore("ruminate")

object PrefsKeys {
    val SERVER_URL = stringPreferencesKey("server_url") // e.g. http://nas.local:8000, no default IP committed
    val WIFI_ONLY = booleanPreferencesKey("wifi_only") // default true: never use cellular
    val POLL_MODE = stringPreferencesKey("poll_mode") // on_open | periodic | instant
    val POLL_INTERVAL_MIN = intPreferencesKey("poll_interval_min")
    val AUTO_DOWNLOAD = booleanPreferencesKey("auto_download")
}

data class AppPrefs(
    val serverUrl: String = "",
    val wifiOnly: Boolean = true,
    val pollMode: String = "periodic",
    val pollIntervalMin: Int = 30,
    val autoDownload: Boolean = true,
)

class PrefsRepo(private val context: Context) {
    val flow: Flow<AppPrefs> = context.prefs.data.map { p ->
        AppPrefs(
            serverUrl = p[PrefsKeys.SERVER_URL].orEmpty().trim().trimEnd('/'),
            wifiOnly = p[PrefsKeys.WIFI_ONLY] ?: true,
            pollMode = p[PrefsKeys.POLL_MODE] ?: "periodic",
            pollIntervalMin = p[PrefsKeys.POLL_INTERVAL_MIN] ?: 30,
            autoDownload = p[PrefsKeys.AUTO_DOWNLOAD] ?: true,
        )
    }

    suspend fun update(p: AppPrefs) {
        context.prefs.edit { e ->
            e[PrefsKeys.SERVER_URL] = p.serverUrl
            e[PrefsKeys.WIFI_ONLY] = p.wifiOnly
            e[PrefsKeys.POLL_MODE] = p.pollMode
            e[PrefsKeys.POLL_INTERVAL_MIN] = p.pollIntervalMin.coerceIn(1, 1440)
            e[PrefsKeys.AUTO_DOWNLOAD] = p.autoDownload
        }
    }
}
