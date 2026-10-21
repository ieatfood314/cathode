package com.cathode.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Video(
    val id: String,
    @SerialName("channel_id") val channelId: String? = null,
    @SerialName("channel_title") val channelTitle: String? = null,
    val title: String? = null,
    @SerialName("upload_date") val uploadDate: String? = null,
    val duration: Int = 0,
    @SerialName("file_path") val filePath: String? = null,
    @SerialName("thumb_path") val thumbPath: String? = null,
    val filesize: Long = 0,
    val status: String = "done",
    val published: Long = 0,
    @SerialName("downloaded_at") val downloadedAt: Long = 0,
    val watched: Int = 0,
    @SerialName("position_seconds") val positionSeconds: Int = 0,
)

@Serializable
data class FeedResponse(val videos: List<Video> = emptyList())

@Serializable
data class ServerSettings(
    @SerialName("poll_mode") val pollMode: String = "periodic", // on_open | periodic | instant
    @SerialName("poll_interval_minutes") val pollIntervalMinutes: Int = 30,
    @SerialName("max_resolution") val maxResolution: Int = 1080,
    @SerialName("sponsorblock_remove") val sponsorblockRemove: String = "sponsor,selfpromo,interaction",
    @SerialName("sponsorblock_mark") val sponsorblockMark: String = "all",
    @SerialName("keep_days") val keepDays: Int = 0,
    @SerialName("backfill_days") val backfillDays: Int = 0,
    @SerialName("delete_after_watched") val deleteAfterWatched: Boolean = false,
)

@Serializable
data class SubInput(
    @SerialName("source_url") val sourceUrl: String,
    @SerialName("channel_id") val channelId: String? = null,
    val title: String? = null,
    val origin: String = "", // opml | takeout | manual
)

@Serializable
data class SubsResponse(
    val inputs: List<SubInput> = emptyList(),
    val indexed: List<ChannelInfo> = emptyList(),
)

@Serializable
data class ChannelInfo(
    val id: String,
    val url: String,
    val title: String? = null,
    @SerialName("last_checked") val lastChecked: Long = 0,
)
