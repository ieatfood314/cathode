package com.cathode.app.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cathode.app.data.Comment
import com.cathode.app.data.YouTubeAccount
import com.cathode.app.data.fetchComments
import com.cathode.app.data.getYouTubeAccount
import com.cathode.app.data.postComment
import com.cathode.app.data.signInYouTube
import com.cathode.app.data.signOutYouTube
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What went wrong, in a few words. */
private fun friendly(e: Exception): String = when (e) {
    is java.io.IOException -> "Can't reach server"
    else -> e.message ?: "Failed"
}

/**
 * One video's YouTube comments, as the player shows them. The server
 * fetches and caches them, and posts through the account it's signed in to.
 */
class CommentsState(private val serverUrl: String, private val videoId: String) {
    var list by mutableStateOf<List<Comment>?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var account by mutableStateOf<YouTubeAccount?>(null)
    var replyTo by mutableStateOf<Comment?>(null)
    var draft by mutableStateOf("")
    var sending by mutableStateOf(false)
        private set
    /** The line above the prompt: "Posted", or why it wasn't. */
    var message by mutableStateOf<String?>(null)
    /** Threads whose replies are showing. */
    val open = mutableStateListOf<String>()

    suspend fun load(refresh: Boolean = false) {
        if (loading || (list != null && !refresh)) return
        loading = true
        error = null
        try {
            val r = withContext(Dispatchers.IO) { fetchComments(serverUrl, videoId, refresh) }
            list = r.comments.distinctBy { it.id }
            account = r.account
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = friendly(e)
        } finally {
            loading = false
        }
    }

    suspend fun send() {
        val text = draft.trim()
        if (text.isEmpty() || sending) return
        sending = true
        message = null
        try {
            val c = withContext(Dispatchers.IO) { postComment(serverUrl, videoId, text, replyTo?.id) }
            val now = list.orEmpty()
            list = if (c.parent.isEmpty()) listOf(c) + now else {
                // After the last comment of its thread.
                val at = now.indexOfLast { it.id == c.parent || it.parent == c.parent }
                if (c.parent !in open) open.add(c.parent)
                now.toMutableList().apply { add(if (at < 0) size else at + 1, c) }
            }
            draft = ""
            replyTo = null
            message = "Posted"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = "! " + friendly(e)
        } finally {
            sending = false
        }
    }
}

/** Fetched as the video opens, so the list is there by the time you look. */
@Composable
fun rememberComments(serverUrl: String, videoId: String, canUseNetwork: Boolean): CommentsState {
    val state = remember(serverUrl, videoId) { CommentsState(serverUrl, videoId) }
    LaunchedEffect(state, canUseNetwork) {
        if (canUseNetwork && serverUrl.isNotBlank()) state.load()
    }
    return state
}

/** The comments tab's rows, inside the player's own list. */
fun LazyListScope.commentItems(state: CommentsState, canUseNetwork: Boolean, onRetry: () -> Unit) {
    val list = state.list
    when {
        list == null -> item(key = "comments-status") {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                when {
                    !canUseNetwork -> Osd("OFFLINE", color = P60, size = 22.sp, glow = false)
                    state.error != null -> {
                        Osd("! ${state.error}".uppercase(), color = P80, size = 21.sp, glow = false, maxLines = 3)
                        Gap(10)
                        Key("RETRY", lit = true, onClick = onRetry)
                    }
                    else -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Osd("TUNING", color = P60, size = 22.sp, glow = false)
                        Spacer(Modifier.width(6.dp))
                        Cursor(height = 18.dp, width = 9.dp)
                    }
                }
            }
        }
        list.isEmpty() -> item(key = "comments-status") {
            Osd("NO COMMENTS YET", Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                color = P60, size = 22.sp, glow = false)
        }
        else -> {
            val canReply = state.account?.signedIn == true
            val replies = list.filter { it.parent.isNotEmpty() }.groupBy { it.parent }
            for (c in list) {
                if (c.parent.isNotEmpty()) continue
                val thread = replies[c.id].orEmpty()
                val isOpen = c.id in state.open
                item(key = c.id) {
                    CommentRow(
                        c, reply = false, replies = thread.size, open = isOpen,
                        onReply = if (canReply) ({ state.replyTo = c; state.message = null }) else null,
                        onToggle = { if (isOpen) state.open.remove(c.id) else state.open.add(c.id) },
                    )
                }
                if (isOpen) for (r in thread) item(key = r.id) {
                    CommentRow(
                        r, reply = true, replies = 0, open = false,
                        onReply = if (canReply) ({ state.replyTo = r; state.message = null }) else null,
                        onToggle = {},
                    )
                }
            }
            item(key = "comments-reload") {
                Box(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                    Key(if (state.loading) "LOADING" else "RELOAD", lit = !state.loading,
                        onClick = { if (!state.loading) onRetry() })
                }
            }
        }
    }
}

/** A small outlined key, like ADD on the Lineup prompt. */
@Composable
private fun Key(label: String, lit: Boolean, filled: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .border(1.dp, if (lit) P100 else P40)
            .background(if (filled && lit) P100 else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Osd(label, color = if (!lit) P40 else if (filled) OnPhosphor else P100, size = 22.sp, glow = false)
    }
}

@Composable
private fun CommentRow(
    c: Comment,
    reply: Boolean,
    replies: Int,
    open: Boolean,
    onReply: (() -> Unit)?,
    onToggle: () -> Unit,
) {
    // Long comments are cut short until tapped.
    var whole by remember(c.id) { mutableStateOf(false) }
    var cut by remember(c.id) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = if (reply) 26.dp else 20.dp, end = 20.dp, top = if (reply) 4.dp else 12.dp)
            .then(
                // Replies hang off a rule down the left, like a quoted thread.
                if (reply) Modifier.drawBehind {
                    drawLine(P20, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx())
                }.padding(start = 12.dp) else Modifier
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val name = c.author.ifEmpty { "?" }.uppercase()
            if (c.uploader) Reverse(name, Modifier.weight(1f, fill = false), size = 18.sp)
            else Osd(name, Modifier.weight(1f, fill = false), color = P60, size = 18.sp, glow = false,
                overflow = TextOverflow.Ellipsis)
            if (c.timeText.isNotEmpty()) Osd(c.timeText.uppercase(), color = P40, size = 18.sp, glow = false)
            if (c.pinned) Box(Modifier.border(1.dp, P40).padding(horizontal = 5.dp)) {
                Osd("PINNED", color = P60, size = 16.sp, glow = false)
            }
        }
        Text(
            c.text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 3.dp)
                .then(if (cut || whole) Modifier.clickable { whole = !whole } else Modifier),
            style = TextStyle(fontFamily = Vt, fontSize = 21.sp, lineHeight = 23.sp, color = P80),
            maxLines = if (whole) Int.MAX_VALUE else 8,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!whole) cut = it.hasVisualOverflow },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (c.likes > 0) Osd("+${c.likes}", Modifier.padding(end = 18.dp), color = P60, size = 18.sp, glow = false)
            if (onReply != null) Osd(
                "REPLY", Modifier.clickable(onClick = onReply).padding(end = 18.dp, top = 6.dp, bottom = 6.dp),
                color = P60, size = 18.sp, glow = false,
            )
            if (replies > 0) Osd(
                if (open) "HIDE REPLIES" else if (replies == 1) "1 REPLY" else "$replies REPLIES",
                Modifier.clickable(onClick = onToggle).padding(top = 6.dp, bottom = 6.dp),
                size = 18.sp, glow = false,
            )
            // Keeps rows the same height when there's nothing to tap.
            Spacer(Modifier.height(30.dp))
        }
    }
}

/**
 * "> add a comment  [SEND]", pinned under the list. Signed out, it's the
 * way in to the sign-in page instead.
 */
@Composable
fun CommentPrompt(state: CommentsState, serverUrl: String, canUseNetwork: Boolean) {
    val scope = rememberCoroutineScope()
    var signingIn by remember { mutableStateOf(false) }
    val account = state.account
    Column(Modifier.fillMaxWidth().background(Tube).imePadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(P20))
        state.message?.let {
            Osd(it.uppercase(), Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
                color = P80, size = 19.sp, glow = false, maxLines = 2)
        }
        when {
            // Not known yet (still loading, or offline): nothing to offer.
            account == null || !canUseNetwork -> Unit
            !account.signedIn -> Row(
                Modifier.fillMaxWidth().clickable {
                    // Signed in from Setup since this list loaded? Then there's nothing to do.
                    scope.launch {
                        val now = withContext(Dispatchers.IO) { runCatching { getYouTubeAccount(serverUrl) }.getOrNull() }
                        if (now?.signedIn == true) state.account = now else signingIn = true
                    }
                }.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Osd(">", color = P60, size = 24.sp, glow = false)
                Spacer(Modifier.width(12.dp))
                Osd("SIGN IN TO COMMENT", size = 24.sp)
            }
            else -> {
                val target = state.replyTo
                if (target != null) Row(
                    Modifier.padding(start = 20.dp, end = 8.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Osd("REPLY TO ${target.author.uppercase()}", Modifier.weight(1f), color = P60, size = 19.sp,
                        glow = false, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { state.replyTo = null }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel reply", tint = P100,
                            modifier = Modifier.size(18.dp))
                    }
                }
                PromptField(
                    value = state.draft,
                    onValue = { state.draft = it },
                    placeholder = if (target != null) "ADD A REPLY" else "ADD A COMMENT",
                    sending = state.sending,
                    // Tapping Reply puts the cursor in the box.
                    focusKey = target?.id,
                    onSend = { scope.launch { state.send() } },
                )
            }
        }
    }
    if (signingIn) YouTubeSignIn(serverUrl) { signedIn ->
        signingIn = false
        if (signedIn != null) state.account = signedIn
    }
}

@Composable
private fun PromptField(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    sending: Boolean,
    focusKey: String?,
    onSend: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val ready = value.isNotBlank() && !sending
    LaunchedEffect(focusKey) { if (focusKey != null) runCatching { focus.requestFocus() } }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .border(1.dp, if (focused) P100 else P40, RoundedCornerShape(4.dp))
            .background(Tube1)
            .clickable { focus.requestFocus() }
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Osd(">", Modifier.padding(bottom = 3.dp), color = if (focused) P100 else P60, size = 26.sp, glow = focused)
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = value,
            onValueChange = onValue,
            enabled = !sending,
            maxLines = 5,
            textStyle = TextStyle(fontFamily = Vt, fontSize = 23.sp, lineHeight = 25.sp, color = P100),
            cursorBrush = SolidColor(P100),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 4.dp)
                .focusRequester(focus)
                .onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Osd(placeholder, color = P40, size = 22.sp, glow = false)
                    inner()
                }
            },
        )
        Spacer(Modifier.width(8.dp))
        Key(if (sending) "SENDING" else "SEND", lit = ready, filled = true) {
            if (ready) onSend() else if (!sending) focus.requestFocus()
        }
    }
}

// ---------------------------------------------------------------- account

private const val SIGN_IN_URL =
    "https://accounts.google.com/ServiceLogin?service=youtube&passive=true&continue=" +
        "https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26next%3D" +
        "https%253A%252F%252Fwww.youtube.com%252F"

/**
 * Google's own sign-in page. Once YouTube has signed the page in, its
 * session cookies go to the server (which posts the comments) and are
 * wiped from the phone. [onDone] gets the account, or null if closed.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeSignIn(serverUrl: String, onDone: (YouTubeAccount?) -> Unit) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var sent by remember { mutableStateOf(false) }

    fun offer(url: String?) {
        val host = runCatching { Uri.parse(url).host }.getOrNull() ?: return
        if (sent || !(host == "youtube.com" || host.endsWith(".youtube.com"))) return
        val cookies = CookieManager.getInstance().getCookie("https://www.youtube.com").orEmpty()
        // Only a signed-in session has this one.
        if (!cookies.contains("SAPISID=")) return
        sent = true
        status = "Checking"
        scope.launch {
            try {
                val account = withContext(Dispatchers.IO) { signInYouTube(serverUrl, cookies) }
                CookieManager.getInstance().removeAllCookies(null)
                onDone(account)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "! " + friendly(e)
                sent = false
            }
        }
    }

    Dialog(onDismissRequest = { onDone(null) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Tube)) {
            Row(Modifier.padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Osd("YOUTUBE SIGN IN", size = 26.sp)
                Spacer(Modifier.width(12.dp))
                Osd(status.orEmpty().uppercase(), Modifier.weight(1f), color = P80, size = 19.sp, glow = false,
                    overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { onDone(null) }) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = P100)
                }
            }
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // Google turns away embedded browsers: look like plain Chrome.
                        settings.userAgentString = settings.userAgentString
                            .replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) = offer(url)
                            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) =
                                offer(url)
                        }
                        loadUrl(SIGN_IN_URL)
                    }
                },
                onRelease = { it.destroy() },
            )
        }
    }
}

/** Setup's row for the account comments are posted as. */
@Composable
fun YouTubeAccountPanel(serverUrl: String) {
    val scope = rememberCoroutineScope()
    var account by remember(serverUrl) { mutableStateOf<YouTubeAccount?>(null) }
    var busy by remember { mutableStateOf(false) }
    var signingIn by remember { mutableStateOf(false) }
    LaunchedEffect(serverUrl) {
        account = withContext(Dispatchers.IO) { runCatching { getYouTubeAccount(serverUrl) }.getOrNull() }
    }
    val a = account ?: return
    SectionLabel("YouTube account")
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (!a.signedIn) "Not signed in" else a.name.ifEmpty { "Signed in" }, color = P100)
                Hint("For posting comments")
            }
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else if (a.signedIn) TextButton(onClick = {
                busy = true
                scope.launch {
                    account = withContext(Dispatchers.IO) { runCatching { signOutYouTube(serverUrl) }.getOrNull() } ?: a
                    busy = false
                }
            }) { Text("Sign out") }
            else TextButton(onClick = { signingIn = true }) { Text("Sign in") }
        }
    }
    if (signingIn) YouTubeSignIn(serverUrl) { signedIn ->
        signingIn = false
        if (signedIn != null) account = signedIn
    }
}
