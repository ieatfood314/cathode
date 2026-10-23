package com.cathode.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.prefs by preferencesDataStore("cathode")

object PrefsKeys {
    val SERVER_URL = stringPreferencesKey("server_url") // e.g. http://server.local:8000, no default committed
    val WIFI_ONLY = booleanPreferencesKey("wifi_only") // default true: never use cellular
    val POLL_MODE = stringPreferencesKey("poll_mode") // mirrors the server's: on_open | periodic | instant
    val POLL_INTERVAL_MIN = intPreferencesKey("poll_interval_min")
    val SAVE_ON_PHONE = booleanPreferencesKey("save_on_phone") // keep offline copies on this phone
    val AUTO_SAVE_NEW = booleanPreferencesKey("auto_save_new") // save new arrivals when the app opens
    // Newest server download time (epoch s) auto-save has handled; 0 = not set yet.
    val AUTO_SAVE_BASELINE = longPreferencesKey("auto_save_baseline")
    val DELETE_AFTER_WATCHED = booleanPreferencesKey("delete_after_watched") // free the phone copy on watched
    val STORAGE_URI = stringPreferencesKey("storage_uri") // SAF tree URI; blank = shared Movies/Cathode
}

data class AppPrefs(
    val serverUrl: String = "",
    val wifiOnly: Boolean = true,
    val pollMode: String = "periodic",
    val pollIntervalMin: Int = 30,
    val saveOnPhone: Boolean = true,
    val autoSaveNew: Boolean = true,
    val autoSaveBaseline: Long = 0,
    val deleteAfterWatched: Boolean = true,
    val storageUri: String = "",
)

/** "192.168.1.5:8000" works too: a missing scheme means plain http (LAN). */
fun normalizeServerUrl(raw: String): String {
    val t = raw.trim().trimEnd('/')
    if (t.isEmpty()) return ""
    return if ("://" in t) t else "http://$t"
}

class PrefsRepo(private val context: Context) {
    private fun read(p: Preferences) = AppPrefs(
        serverUrl = normalizeServerUrl(p[PrefsKeys.SERVER_URL].orEmpty()),
        wifiOnly = p[PrefsKeys.WIFI_ONLY] ?: true,
        pollMode = p[PrefsKeys.POLL_MODE] ?: "periodic",
        pollIntervalMin = p[PrefsKeys.POLL_INTERVAL_MIN] ?: 30,
        saveOnPhone = p[PrefsKeys.SAVE_ON_PHONE] ?: true,
        autoSaveNew = p[PrefsKeys.AUTO_SAVE_NEW] ?: true,
        autoSaveBaseline = p[PrefsKeys.AUTO_SAVE_BASELINE] ?: 0,
        deleteAfterWatched = p[PrefsKeys.DELETE_AFTER_WATCHED] ?: true,
        storageUri = p[PrefsKeys.STORAGE_URI].orEmpty(),
    )

    val flow: Flow<AppPrefs> = context.prefs.data.map { read(it) }

    /**
     * Apply a change to the *stored* prefs. Taking a transform (not a full
     * AppPrefs snapshot) means a stale copy held by the UI can never write
     * back over a newer value — the cold-start blank-defaults wipe of 0.1.3.
     */
    suspend fun update(transform: (AppPrefs) -> AppPrefs) {
        context.prefs.edit { e ->
            val old = read(e)
            val p = transform(old)
            // Stored raw (what was typed); only rewritten when it changed.
            if (p.serverUrl != old.serverUrl) e[PrefsKeys.SERVER_URL] = p.serverUrl
            e[PrefsKeys.WIFI_ONLY] = p.wifiOnly
            e[PrefsKeys.POLL_MODE] = p.pollMode
            e[PrefsKeys.POLL_INTERVAL_MIN] = p.pollIntervalMin.coerceIn(1, 1440)
            e[PrefsKeys.SAVE_ON_PHONE] = p.saveOnPhone
            e[PrefsKeys.AUTO_SAVE_NEW] = p.autoSaveNew
            e[PrefsKeys.DELETE_AFTER_WATCHED] = p.deleteAfterWatched
            e[PrefsKeys.STORAGE_URI] = p.storageUri
        }
    }

    suspend fun setBaseline(downloadedAt: Long) {
        context.prefs.edit { e -> e[PrefsKeys.AUTO_SAVE_BASELINE] = downloadedAt }
    }
}
