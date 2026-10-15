package com.ruminate.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.prefs by preferencesDataStore("ruminate")

object PrefsKeys {
    val SERVER_URL = stringPreferencesKey("server_url") // e.g. http://server.local:8000, no default committed
    val WIFI_ONLY = booleanPreferencesKey("wifi_only") // default true: never use cellular
    val POLL_MODE = stringPreferencesKey("poll_mode") // on_open | periodic | instant
    val POLL_INTERVAL_MIN = intPreferencesKey("poll_interval_min")
    val AUTO_DOWNLOAD = booleanPreferencesKey("auto_download")
    val SAVE_ON_PHONE = booleanPreferencesKey("save_on_phone") // keep offline copies on this phone
    val AUTO_SAVE_NEW = booleanPreferencesKey("auto_save_new") // save new arrivals when the app opens
    val AUTO_SAVE_BASELINE = longPreferencesKey("auto_save_baseline") // max published seen; 0 = not set yet
    val DELETE_AFTER_WATCHED = booleanPreferencesKey("delete_after_watched") // free the phone copy on watched
    val STORAGE_URI = stringPreferencesKey("storage_uri") // SAF tree URI; blank = shared Movies/Ruminate
    val LEGACY_CACHE_WIPED = booleanPreferencesKey("legacy_cache_wiped")
}

data class AppPrefs(
    val serverUrl: String = "",
    val wifiOnly: Boolean = true,
    val pollMode: String = "periodic",
    val pollIntervalMin: Int = 30,
    val autoDownload: Boolean = true,
    val saveOnPhone: Boolean = true,
    val autoSaveNew: Boolean = true,
    val autoSaveBaseline: Long = 0,
    val deleteAfterWatched: Boolean = true,
    val storageUri: String = "",
    val legacyCacheWiped: Boolean = false,
)

class PrefsRepo(private val context: Context) {
    val flow: Flow<AppPrefs> = context.prefs.data.map { p ->
        AppPrefs(
            serverUrl = p[PrefsKeys.SERVER_URL].orEmpty().trim().trimEnd('/'),
            wifiOnly = p[PrefsKeys.WIFI_ONLY] ?: true,
            pollMode = p[PrefsKeys.POLL_MODE] ?: "periodic",
            pollIntervalMin = p[PrefsKeys.POLL_INTERVAL_MIN] ?: 30,
            autoDownload = p[PrefsKeys.AUTO_DOWNLOAD] ?: true,
            saveOnPhone = p[PrefsKeys.SAVE_ON_PHONE] ?: true,
            autoSaveNew = p[PrefsKeys.AUTO_SAVE_NEW] ?: true,
            autoSaveBaseline = p[PrefsKeys.AUTO_SAVE_BASELINE] ?: 0,
            deleteAfterWatched = p[PrefsKeys.DELETE_AFTER_WATCHED] ?: true,
            storageUri = p[PrefsKeys.STORAGE_URI].orEmpty(),
            legacyCacheWiped = p[PrefsKeys.LEGACY_CACHE_WIPED] ?: false,
        )
    }

    suspend fun update(p: AppPrefs) {
        context.prefs.edit { e ->
            e[PrefsKeys.SERVER_URL] = p.serverUrl
            e[PrefsKeys.WIFI_ONLY] = p.wifiOnly
            e[PrefsKeys.POLL_MODE] = p.pollMode
            e[PrefsKeys.POLL_INTERVAL_MIN] = p.pollIntervalMin.coerceIn(1, 1440)
            e[PrefsKeys.AUTO_DOWNLOAD] = p.autoDownload
            e[PrefsKeys.SAVE_ON_PHONE] = p.saveOnPhone
            e[PrefsKeys.AUTO_SAVE_NEW] = p.autoSaveNew
            e[PrefsKeys.DELETE_AFTER_WATCHED] = p.deleteAfterWatched
            e[PrefsKeys.STORAGE_URI] = p.storageUri
            e[PrefsKeys.LEGACY_CACHE_WIPED] = p.legacyCacheWiped
        }
    }

    suspend fun setBaseline(published: Long) {
        context.prefs.edit { e -> e[PrefsKeys.AUTO_SAVE_BASELINE] = published }
    }
}
