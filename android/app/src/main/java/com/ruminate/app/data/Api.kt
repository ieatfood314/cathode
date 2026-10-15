package com.ruminate.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.serialization.json.Json
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

fun baseClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()

fun requireWifiIfEnabled(context: Context, wifiOnly: Boolean) {
    if (wifiOnly && !isUnmetered(context)) {
        throw WifiBlockedException("Not on unmetered Wi-Fi — sync paused (Wi-Fi only is ON).")
    }
}

class WifiBlockedException(msg: String) : IllegalStateException(msg)

fun fetchFeed(baseUrl: String, unwatchedOnly: Boolean = false): List<Video> {
    val url = "$baseUrl/api/feed?limit=200" + if (unwatchedOnly) "&unwatched_only=true" else ""
    baseClient().newCall(Request.Builder().url(url).build()).execute().use { resp ->
        if (!resp.isSuccessful) throw IllegalStateException("feed HTTP ${resp.code}")
        return json.decodeFromString(
            FeedResponse.serializer(),
            resp.body?.string().orEmpty().ifEmpty { """{"videos":[]}""" },
        ).videos
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
fun addChannel(baseUrl: String, url: String): String {
    val body = "{\"url\":${json.encodeToString(url)}}"
        .toRequestBody("application/json".toMediaType())
    baseClient().newCall(
        Request.Builder().url("$baseUrl/api/subscriptions").post(body).build()
    ).execute().use { resp ->
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw IllegalStateException(text.ifEmpty { "add HTTP ${resp.code}" })
        return text
    }
}

/** Stop tracking a manually-added channel. */
fun removeChannel(baseUrl: String, url: String) {
    val encoded = java.net.URLEncoder.encode(url, "UTF-8")
    baseClient().newCall(
        Request.Builder().url("$baseUrl/api/subscriptions?url=$encoded").delete().build()
    ).execute().use { resp ->
        if (!resp.isSuccessful) {
            throw IllegalStateException(resp.body?.string().orEmpty().ifEmpty { "remove HTTP ${resp.code}" })
        }
    }
}

/** Upload a subscription export (.opml/.csv/.txt). Returns the server's message. */
fun uploadSubscriptions(baseUrl: String, filename: String, bytes: ByteArray): String {
    val part = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
        .addFormDataPart("file", filename, bytes.toRequestBody("text/plain".toMediaType()))
        .build()
    baseClient().newCall(
        Request.Builder().url("$baseUrl/api/subscriptions/upload").post(part).build()
    ).execute().use { resp ->
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw IllegalStateException(text.ifEmpty { "upload HTTP ${resp.code}" })
        return text
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
