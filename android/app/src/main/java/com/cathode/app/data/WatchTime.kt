package com.cathode.app.data

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/** What the Stats tab shows. Times in seconds. */
data class WatchStats(
    val today: Double = 0.0,
    val week: Double = 0.0,
    val dailyAvg: Double = 0.0,
    val allTime: Double = 0.0,
    /** The last two weeks, oldest first, ending today. */
    val days: List<Pair<LocalDate, Double>> = emptyList(),
    /** Most-watched channels over the last 30 days. */
    val channels: List<Pair<String, Double>> = emptyList(),
)

@Serializable
private data class WatchFile(
    val sessions: List<WatchSession> = emptyList(),
    val stats: StatsResponse? = null,
)

/**
 * Time actually spent playing videos, for the Stats tab. Wall-clock: pauses
 * and buffering don't count, and a video at 2x counts the time it took.
 *
 * Each video gets one session per day. The server keeps the history and
 * takes each session's running total (the larger wins, so resending is
 * harmless). Time it hasn't confirmed yet waits on disk, like watch
 * progress, and is counted on top of the server's last stats, so time
 * watched offline shows up right away.
 *
 * start/stop/tick are cheap and main-thread safe; flush/refresh do network.
 */
object WatchTime {
    private const val WINDOW_DAYS = 30
    private const val CHART_DAYS = 14
    private const val FLUSH_EVERY_MS = 60_000L
    // A longer gap between ticks means the app was stalled, not watching.
    private const val MAX_STRETCH_MS = 60_000L

    private val lock = Any()
    // One flush/refresh at a time: the cached stats are adjusted by what
    // each flush confirms, which must not interleave with a fresh fetch.
    private val netLock = Mutex()
    private var loaded = false
    private val sessions = LinkedHashMap<String, WatchSession>()
    // The server's stats as last fetched, plus time it has confirmed since.
    private var cache: StatsResponse? = null
    private var active: Active? = null
    @Volatile private var lastFlush = 0L

    private class Active(
        val sessionId: String,
        val videoId: String,
        val channel: String?,
        val day: String,
        // elapsedRealtime of the last accrual; null while paused.
        var mark: Long?,
    )

    private val _stats = MutableStateFlow(WatchStats())
    val stats: StateFlow<WatchStats> = _stats

    private fun file(c: Context) = File(c.filesDir, "watch_time.json")

    /** Read sessions + cached stats from disk (once; later calls are no-ops). */
    fun load(context: Context) {
        synchronized(lock) {
            if (loaded) return
            loaded = true
            val saved = runCatching {
                json.decodeFromString(WatchFile.serializer(), file(context).readText())
            }.getOrNull()
            saved?.sessions?.forEach { sessions[it.id] = it }
            cache = saved?.stats
        }
        publish()
    }

    /** Playback started or resumed. */
    fun start(context: Context, video: Video) {
        load(context)
        synchronized(lock) {
            accrue()
            val a = active
            val day = LocalDate.now().toString()
            if (a != null && a.videoId == video.id && a.day == day) {
                a.mark = SystemClock.elapsedRealtime()
            } else {
                begin(video.id, video.channelTitle, day)
            }
        }
    }

    /** Every few seconds while playing. */
    fun tick(context: Context) {
        load(context)
        synchronized(lock) { accrue() }
        changed(context, send = SystemClock.elapsedRealtime() - lastFlush >= FLUSH_EVERY_MS)
    }

    /** Paused, ended, closed or switched away. */
    fun stop(context: Context) {
        load(context)
        synchronized(lock) {
            accrue()
            active?.mark = null
        }
        changed(context, send = true)
    }

    /** Send unconfirmed time to the server. False when it couldn't. */
    suspend fun flush(context: Context): Boolean = netLock.withLock { flushLocked(context) }

    /** Send what's waiting, then fetch the server's stats. */
    suspend fun refresh(context: Context): Result<Unit> = netLock.withLock {
        flushLocked(context)
        val base = Library.target(context) ?: return@withLock Result.failure<Unit>(OfflineException())
        val since = LocalDate.now().minusDays(WINDOW_DAYS - 1L).toString()
        runCatching { fetchStats(base, since) }.map { fresh ->
            synchronized(lock) {
                cache = fresh
                write(context)
            }
            publish()
        }
    }

    // Under lock.
    private fun begin(videoId: String, channel: String?, day: String) {
        val s = WatchSession(UUID.randomUUID().toString(), videoId, day, channel = channel)
        sessions[s.id] = s
        active = Active(s.id, videoId, channel, day, SystemClock.elapsedRealtime())
    }

    // Under lock: add the time since the last mark to the running session.
    private fun accrue() {
        val a = active ?: return
        val mark = a.mark ?: return
        val now = SystemClock.elapsedRealtime()
        sessions[a.sessionId]?.let { s ->
            val add = (now - mark).coerceIn(0, MAX_STRETCH_MS) / 1000.0
            sessions[a.sessionId] = s.copy(seconds = s.seconds + add)
        }
        a.mark = now
        // Past midnight: the rest counts toward the new day.
        val day = LocalDate.now().toString()
        if (day != a.day) begin(a.videoId, a.channel, day)
    }

    private fun changed(context: Context, send: Boolean) {
        publish()
        val app = context.applicationContext
        Library.scope.launch {
            synchronized(lock) { write(app) }
            if (send) flush(app)
        }
    }

    private suspend fun flushLocked(context: Context): Boolean {
        load(context)
        lastFlush = SystemClock.elapsedRealtime()
        val batch = synchronized(lock) { sessions.values.filter { it.seconds > it.sent } }
        if (batch.isEmpty()) return true
        val base = Library.target(context) ?: return false
        if (runCatching { postWatchTime(base, batch) }.isFailure) return false
        synchronized(lock) {
            var c = cache ?: StatsResponse()
            for (b in batch) {
                val s = sessions[b.id] ?: continue
                val confirmed = b.seconds - s.sent
                if (confirmed <= 0) continue
                c = c.plus(b.day, b.channel.orEmpty(), confirmed)
                sessions[b.id] = s.copy(sent = b.seconds)
            }
            cache = c
            // Fully confirmed and not running: the cached stats carry it now.
            val running = active?.sessionId
            sessions.entries.removeAll { (id, s) -> id != running && s.sent >= s.seconds }
            write(context)
        }
        publish()
        return true
    }

    private fun StatsResponse.plus(day: String, channel: String, seconds: Double): StatsResponse {
        val i = rows.indexOfFirst { it.day == day && it.channel == channel }
        val next = if (i < 0) rows + StatsRow(day, channel, seconds)
        else rows.toMutableList().also { it[i] = it[i].copy(seconds = it[i].seconds + seconds) }
        return copy(
            rows = next,
            totalSeconds = totalSeconds + seconds,
            firstDay = listOfNotNull(firstDay, day).min(),
        )
    }

    // Under lock.
    private fun write(context: Context) = Library.writeAtomic(
        file(context),
        json.encodeToString(WatchFile.serializer(), WatchFile(sessions.values.toList(), cache)),
    )

    private fun publish() {
        val (rows, total, first) = synchronized(lock) {
            val unsent = sessions.values.filter { it.seconds > it.sent }
                .map { StatsRow(it.day, it.channel.orEmpty(), it.seconds - it.sent) }
            Triple(
                cache?.rows.orEmpty() + unsent,
                (cache?.totalSeconds ?: 0.0) + unsent.sumOf { it.seconds },
                cache?.firstDay,
            )
        }
        _stats.value = summarize(rows, total, first, LocalDate.now())
    }

    private fun summarize(rows: List<StatsRow>, total: Double, firstDay: String?, today: LocalDate): WatchStats {
        val byDay = HashMap<String, Double>()
        rows.forEach { byDay.merge(it.day, it.seconds, Double::plus) }
        fun lastDays(n: Int) = (0 until n).map { today.minusDays(it.toLong()) }
        fun sum(n: Int) = lastDays(n).sumOf { byDay[it.toString()] ?: 0.0 }
        // Averaged over the days since tracking began (up to 30), so the
        // first week isn't divided by 30.
        val first = (rows.map { it.day } + listOfNotNull(firstDay)).minOrNull()
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val tracked = first?.let { ChronoUnit.DAYS.between(it, today) + 1 }
            ?.coerceIn(1, WINDOW_DAYS.toLong()) ?: 1L
        val windowStart = today.minusDays(WINDOW_DAYS - 1L).toString()
        val channels = HashMap<String, Double>()
        rows.filter { it.day >= windowStart && it.day <= today.toString() }
            .forEach { channels.merge(it.channel, it.seconds, Double::plus) }
        return WatchStats(
            today = byDay[today.toString()] ?: 0.0,
            week = sum(7),
            dailyAvg = sum(WINDOW_DAYS) / tracked,
            allTime = total,
            days = lastDays(CHART_DAYS).reversed().map { it to (byDay[it.toString()] ?: 0.0) },
            channels = channels.entries.filter { it.value >= 60 }
                .sortedByDescending { it.value }.take(5).map { it.key to it.value },
        )
    }
}
