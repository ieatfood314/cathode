package com.cathode.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** One YouTube comment. A reply carries its thread's id in [parent]. */
@Serializable
data class Comment(
    val id: String = "",
    val parent: String = "",
    val author: String = "",
    val text: String = "",
    val likes: Int = 0,
    @SerialName("time_text") val timeText: String = "",
    val pinned: Boolean = false,
    val uploader: Boolean = false,
    val hearted: Boolean = false,
    val mine: Boolean = false,
)

/** The YouTube account the server posts comments as. */
@Serializable
data class YouTubeAccount(
    @SerialName("signed_in") val signedIn: Boolean = false,
    val name: String = "",
)

@Serializable
data class CommentsResponse(
    val comments: List<Comment> = emptyList(),
    @SerialName("fetched_at") val fetchedAt: Long = 0,
    val account: YouTubeAccount = YouTubeAccount(),
)

@Serializable
private data class PostCommentResponse(val comment: Comment = Comment())

// The server asks YouTube while we wait, which can take a while on a
// video it hasn't looked at before.
private val slowClient: OkHttpClient by lazy {
    baseClient().newBuilder().readTimeout(150, TimeUnit.SECONDS).build()
}

private val JSON_TYPE = "application/json".toMediaType()

private fun <T> OkHttpClient.call(req: Request, what: String, parse: (String) -> T): T =
    newCall(req).execute().use { resp ->
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
            throw HttpStatusException(resp.code, serverDetail(text).ifEmpty { "$what HTTP ${resp.code}" })
        }
        parse(text)
    }

/** The video's top comments; [refresh] asks YouTube again instead of using the server's copy. */
fun fetchComments(baseUrl: String, videoId: String, refresh: Boolean = false): CommentsResponse =
    slowClient.call(
        Request.Builder().url("$baseUrl/api/videos/$videoId/comments" + if (refresh) "?refresh=true" else "").build(),
        "comments",
    ) { json.decodeFromString(CommentsResponse.serializer(), it.ifEmpty { "{}" }) }

/** Post to YouTube through the server; [parent] makes it a reply in that thread. */
fun postComment(baseUrl: String, videoId: String, text: String, parent: String?): Comment {
    val body = buildJsonObject {
        put("text", text)
        if (parent != null) put("parent", parent)
    }.toString().toRequestBody(JSON_TYPE)
    return slowClient.call(
        Request.Builder().url("$baseUrl/api/videos/$videoId/comments").post(body).build(),
        "comment",
    ) { json.decodeFromString(PostCommentResponse.serializer(), it).comment }
}

fun getYouTubeAccount(baseUrl: String): YouTubeAccount =
    baseClient().call(Request.Builder().url("$baseUrl/api/youtube/account").build(), "account") {
        json.decodeFromString(YouTubeAccount.serializer(), it.ifEmpty { "{}" })
    }

/** Hand the server a signed-in YouTube session ([cookies] is a Cookie header). */
fun signInYouTube(baseUrl: String, cookies: String): YouTubeAccount {
    val body = buildJsonObject { put("cookies", cookies) }.toString().toRequestBody(JSON_TYPE)
    return slowClient.call(
        Request.Builder().url("$baseUrl/api/youtube/account").post(body).build(), "sign in",
    ) { json.decodeFromString(YouTubeAccount.serializer(), it) }
}

fun signOutYouTube(baseUrl: String): YouTubeAccount =
    baseClient().call(Request.Builder().url("$baseUrl/api/youtube/account").delete().build(), "sign out") {
        json.decodeFromString(YouTubeAccount.serializer(), it.ifEmpty { "{}" })
    }
