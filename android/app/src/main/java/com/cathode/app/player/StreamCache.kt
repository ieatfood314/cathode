package com.cathode.app.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Read-through cache for videos streamed from the server, so seeking back
 * doesn't refetch. Small and in the system cache dir (Android can reclaim
 * it); saved copies live elsewhere, as plain files you can see and delete.
 */
@UnstableApi
object StreamCache {
    const val MAX_BYTES = 1L * 1024 * 1024 * 1024

    @Volatile private var cache: SimpleCache? = null

    fun get(context: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(
            File(context.cacheDir, "stream_cache"),
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
            StandaloneDatabaseProvider(context.applicationContext),
        ).also { cache = it }
    }

    /** Builds before 0.3 kept up to 16 GB of streamed video in app storage,
     *  invisible and never cleared. Reclaim it. */
    fun deleteLegacy(context: Context) {
        File(context.filesDir, "cathode_cache").takeIf { it.exists() }?.deleteRecursively()
    }
}
