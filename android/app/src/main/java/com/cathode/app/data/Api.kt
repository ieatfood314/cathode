package com.cathode.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

val json = Json { ignoreUnknownKeys = true }

/** True only on unmetered Wi-Fi/Ethernet. This is the cellular-data kill switch. */
fun isUnmetered(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val net = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(net) ?: return false
    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
}

/** Any usable connection at all (Wi-Fi only OFF lets the app use these). */
fun isOnline(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val net = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(net) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/** Whether the app may talk to the server right now under the Wi-Fi only rule. */
fun networkAllowed(context: Context, wifiOnly: Boolean): Boolean =
    if (wifiOnly) isUnmetered(context) else isOnline(context)

// One client for the whole app: each OkHttpClient owns a connection pool and
// thread pools, so building one per request leaked idle threads.
private val sharedClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}

fun baseClient(): OkHttpClient = sharedClient

class HttpStatusException(val code: Int, msg: String) : IllegalStateException(msg)

/** FastAPI errors arrive as {"detail": "..."}; show the sentence, not the JSON. */
fun serverDetail(body: String): String = runCatching {
    json.parseToJsonElement(body).jsonObject["detail"]?.jsonPrimitive?.content
}.getOrNull() ?: body

@Serializable
data class AddChannelResponse(val added: Boolean = false, val url: String = "")

@Serializable
data class UploadResponse(val file: String = "", val subscriptions: Int = 0)

fun requireWifiIfEnabled(context: Context, wifiOnly: Boolean) {
    if (wifiOnly && !isUnmetered(context)) {
        throw WifiBlockedException("Not on unmetered Wi-Fi — sync paused (Wi-Fi only is ON).")
    }
}

class WifiBlockedException(msg: String) : IllegalStateException(msg)

fun fetchFeed(baseUrl: String, unwatchedOnly: Boolean = false): List<Video> =
    try {
        fetchFeedPage(baseUrl, 2000, unwatchedOnly)
    } catch (e: HttpStatusException) {
        // Servers before 0.3 cap limit at 500 and reject anything above (422).
        if (e.code != 422) throw e
        fetchFeedPage(baseUrl, 500, unwatchedOnly)
    }

private fun fetchFeedPage(baseUrl: String, limit: Int, unwatchedOnly: Boolean): List<Video> {
    val url = "$baseUrl/api/feed?limit=$limit" + if (unwatchedOnly) "&unwatched_only=true" else ""
    baseClient().newCall(Request.Builder().url(url).build()).execute().use { resp ->
        if (!resp.isSuccessful) throw HttpStatusException(resp.code, "feed HTTP ${resp.code}")
        return json.decodeFromString(
            FeedResponse.serializer(),
            resp.body?.string().orEmpty().ifEmpty { """{"videos":[]}""" },
        ).videos
    }
}

/** What to tell the user when a sync failed, by what actually went wrong. */
fun syncFailureMessage(e: Throwable?): String = when (e) {
    is OfflineException, is java.io.IOException ->
        "Couldn't reach the server — showing your library from the last sync. " +
            "Saved videos still play."
    is HttpStatusException ->
        "The server answered with an error (HTTP ${e.code}). If you just updated the " +
            "app, update the server too."
    else -> "The server sent something unexpected (${e?.message ?: "unknown error"}). " +
        "If you just updated the app, update the server too."
}

/** Tell the server where playback stopped so this video resumes — on this
 *  phone, across app restarts, and anywhere else that opens the same library.
 *  Returns the server's watched state afterwards (it auto-marks past ~95%).
 *  Throws when the server didn't take it. */
fun putProgress(baseUrl: String, videoId: String, positionSeconds: Int): Boolean {
    val body = "{\"position_seconds\":$positionSeconds}".toRequestBody("application/json".toMediaType())
    baseClient().newCall(
        Request.Builder().url("$baseUrl/api/videos/$videoId/progress").post(body).build()
    ).execute().use { resp ->
        if (!resp.isSuccessful) throw HttpStatusException(resp.code, "progress HTTP ${resp.code}")
        return resp.body?.string()?.contains("\"watched\":true") == true
    }
}

/** Mark a video watched / unwatched (long-press menu, end of playback).
 *  Unwatched also rewinds it to the start. Throws when the server didn't take it. */
fun setWatched(baseUrl: String, videoId: String, watched: Boolean) {
    baseClient().newCall(
        Request.Builder().url("$baseUrl/api/videos/$videoId/watched?watched=$watched")
            .post(ByteArray(0).toRequestBody(null)).build()
    ).execute().use { resp ->
        if (!resp.isSuccessful) throw HttpStatusException(resp.code, "watched HTTP ${resp.code}")
    }
}

fun triggerServerPoll(baseUrl: String): String {
    baseClient().newCall(
        Request.Builder().url("$baseUrl/api/subscriptions/refresh").post(ByteArray(0).toRequestBody(null)).build()
    ).execute().use { resp ->
        return resp.body?.string().orEmpty()
    }
}

/** Server health check — also used by the Settings "Test connection" button. */
fun checkHealth(baseUrl: String): String {
    baseClient().newCall(Request.Builder().url("$baseUrl/api/health").build())
        .execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("health HTTP ${resp.code}")
            return resp.body?.string().orEmpty()
        }
}

/** Full server settings (polling, quality, SponsorBlock, retention). */
fun getServerSettings(baseUrl: String): ServerSettings {
    baseClient().newCall(Request.Builder().url("$baseUrl/api/settings").build())
        .execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("settings HTTP ${resp.code}")
            return json.decodeFromString(
                ServerSettings.serializer(),
                resp.body?.string().orEmpty().ifEmpty { "{}" },
            )
        }
}

fun putServerSettings(baseUrl: String, s: ServerSettings) {
    val body = json.encodeToString(ServerSettings.serializer(), s)
        .toRequestBody("application/json".toMediaType())
    baseClient().newCall(
        Request.Builder().url("$baseUrl/api/settings").put(body).build()
    ).execute().use { resp ->
        if (!resp.isSuccessful) throw IllegalStateException("save HTTP ${resp.code}")
    }
}

/** Channel sources the server tracks (export file entries + manual adds). */
fun listSubscriptions(baseUrl: String): SubsResponse {
    baseClient().newCall(Request.Builder().url("$baseUrl/api/subscriptions").build())
        .execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("subscriptions HTTP ${resp.code}")
            return json.decodeFromString(
                SubsResponse.serializer(),
                resp.body?.string().orEmpty().ifEmpty { """{"inputs":[]}""" },
            )
        }
}

/** Track one more channel (URL, @handle, or channel ID). */
fun addChannel(baseUrl: String, url: String): AddChannelResponse {
    // Hand-rolled JSON for one string field (correctly escapes \ and ").
    val esc = url.replace("\\", "\\\\").replace("\"", "\\\"")
    val body = "{\"url\":\"$esc\"}".toRequestBody("application/json".toMediaType())
    baseClient().newCall(
        Request.Builder().url("$baseUrl/api/subscriptions").post(body).build()
    ).execute().use { resp ->
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw IllegalStateException(serverDetail(text).ifEmpty { "add HTTP ${resp.code}" })
        return runCatching { json.decodeFromString(AddChannelResponse.serializer(), text) }
            .getOrDefault(AddChannelResponse(added = true, url = url))
    }
}

/** Stop tracking a manually-added channel. */
fun removeChannel(baseUrl: String, url: String) {
    val encoded = java.net.URLEncoder.encode(url, "UTF-8")
    baseClient().newCall(
        Request.Builder().url("$baseUrl/api/subscriptions?url=$encoded").delete().build()
    ).execute().use { resp ->
        if (!resp.isSuccessful) {
            throw IllegalStateException(
                serverDetail(resp.body?.string().orEmpty()).ifEmpty { "remove HTTP ${resp.code}" })
        }
    }
}

/** Upload a subscription export (.opml/.csv/.txt). */
fun uploadSubscriptions(baseUrl: String, filename: String, bytes: ByteArray): UploadResponse {
    val part = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
        .addFormDataPart("file", filename, bytes.toRequestBody("text/plain".toMediaType()))
        .build()
    baseClient().newCall(
        Request.Builder().url("$baseUrl/api/subscriptions/upload").post(part).build()
    ).execute().use { resp ->
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw IllegalStateException(serverDetail(text).ifEmpty { "upload HTTP ${resp.code}" })
        return runCatching { json.decodeFromString(UploadResponse.serializer(), text) }
            .getOrDefault(UploadResponse())
    }
}

/**
 * Resumable file download with HTTP Range. Progress survives app kills and
 * dropped connections: the .part file stays on disk and we resume with
 * `Range: bytes=<downloaded>-` + exponential backoff (max 8 attempts).
 * Returns the final file.
 */
fun downloadResumable(
    context: Context,
    url: String,
    dest: File,
    wifiOnly: Boolean,
    onProgress: (bytesDone: Long, total: Long) -> Unit = { _, _ -> },
    maxAttempts: Int = 8,
): File {
    val part = File(dest.path + ".part")
    val client = baseClient()
    var attempt = 0
    var backoffMs = 1_000L
    while (true) {
        attempt++
        requireWifiIfEnabled(context, wifiOnly)
        val done = if (part.exists()) part.length() else 0L
        val req = Request.Builder().url(url).apply {
            if (done > 0) header("Range", "bytes=$done-")
        }.build()
        try {
            var rangeIgnored = false
            client.newCall(req).execute().use { resp ->
                if (resp.code == 416) { // already complete
                    part.renameTo(dest)
                    return dest
                }
                if (!resp.isSuccessful && resp.code != 206) {
                    throw IllegalStateException("HTTP ${resp.code}")
                }
                if (done > 0 && resp.code == 200) {
                    // Server ignored Range: appending the full file would
                    // corrupt the part file. Discard and restart from zero.
                    part.delete()
                    rangeIgnored = true
                    return@use
                }
                val total = resp.header("Content-Range")
                    ?.substringAfter("/")?.toLongOrNull()
                    ?: resp.header("Content-Length")?.toLongOrNull()?.plus(done)
                    ?: -1L
                val body = resp.body ?: throw IllegalStateException("empty body")
                RandomAccessFile(part, "rw").use { raf ->
                    raf.seek(done)
                    val buf = ByteArray(64 * 1024)
                    var count = done
                    while (true) {
                        requireWifiIfEnabled(context, wifiOnly)
                        val n = body.byteStream().read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        count += n
                        onProgress(count, total)
                    }
                }
                part.renameTo(dest)
                return dest
            }
            if (rangeIgnored) {
                Thread.sleep(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
            }
        } catch (e: WifiBlockedException) {
            throw e // do not retry on metered networks; WorkManager will reschedule on UNMETERED
        } catch (e: Exception) {
            if (attempt >= maxAttempts) throw e
            Thread.sleep(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
        }
    }
}

/**
 * Same resume logic as downloadResumable, but appends to any OutputStream —
 * used for SAF locations (SD card / USB) where there is no direct file path.
 * existingBytes = current partial length; openSink() must open an append
 * stream each attempt. Throws on final failure.
 */
fun downloadResumableStream(
    context: Context,
    url: String,
    wifiOnly: Boolean,
    existingBytes: () -> Long,
    openSink: () -> java.io.OutputStream?,
    onProgress: (bytesDone: Long, total: Long) -> Unit = { _, _ -> },
    maxAttempts: Int = 8,
    onRangeIgnored: () -> Unit = {},
) {
    val client = baseClient()
    var attempt = 0
    var backoffMs = 1_000L
    while (true) {
        attempt++
        requireWifiIfEnabled(context, wifiOnly)
        val done = existingBytes()
        val req = Request.Builder().url(url).apply {
            if (done > 0) header("Range", "bytes=$done-")
        }.build()
        try {
            client.newCall(req).execute().use { resp ->
                if (resp.code == 416) return // already complete
                if (!resp.isSuccessful && resp.code != 206) {
                    throw IllegalStateException("HTTP ${resp.code}")
                }
                if (done > 0 && resp.code == 200) {
                    throw RangeIgnoredException()
                }
                val total = resp.header("Content-Range")
                    ?.substringAfter("/")?.toLongOrNull()
                    ?: resp.header("Content-Length")?.toLongOrNull()?.plus(done)
                    ?: -1L
                val body = resp.body ?: throw IllegalStateException("empty body")
                val out = openSink() ?: throw IllegalStateException("cannot open download target")
                out.use {
                    val buf = ByteArray(64 * 1024)
                    var count = done
                    while (true) {
                        requireWifiIfEnabled(context, wifiOnly)
                        val n = body.byteStream().read(buf)
                        if (n < 0) break
                        it.write(buf, 0, n)
                        count += n
                        onProgress(count, total)
                    }
                }
                return
            }
        } catch (e: WifiBlockedException) {
            throw e
        } catch (e: RangeIgnoredException) {
            // Server ignored Range: drop the partial so the retry starts at zero.
            runCatching { onRangeIgnored() }
            if (attempt >= maxAttempts) throw IllegalStateException("server ignores resume")
            Thread.sleep(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
        } catch (e: Exception) {
            if (attempt >= maxAttempts) throw e
            Thread.sleep(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
        }
    }
}

private class RangeIgnoredException : IllegalStateException("server ignored Range")
