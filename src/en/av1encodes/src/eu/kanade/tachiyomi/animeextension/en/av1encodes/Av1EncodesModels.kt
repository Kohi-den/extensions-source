package eu.kanade.tachiyomi.animeextension.en.av1encodes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// CHANGED: Added watch_link (confirmed from live API response).
@Serializable
internal data class DdlResponse(
    @SerialName("success") val success: Boolean = false,
    @SerialName("stream_link") val streamLink: String? = null,
    @SerialName("download_link") val downloadLink: String? = null,
    @SerialName("torrent_link") val torrentLink: String? = null,
    @SerialName("watch_link") val watchLink: String? = null,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("file_size") val fileSize: String? = null,
    @SerialName("subtitles") val subtitles: List<SubtitleInfo>? = null,
    @SerialName("audio_details") val audioDetails: AudioDetailsWrapper? = null,
    @SerialName("video_details") val videoDetails: List<VideoDetailInfo>? = null,
)

@Serializable
internal data class SubtitleInfo(
    @SerialName("format") val format: String? = null,
    @SerialName("language") val language: String? = null,
)

@Serializable
internal data class AudioDetailsWrapper(
    @SerialName("audio") val audio: List<AudioTrackInfo>? = null,
)

@Serializable
internal data class AudioTrackInfo(
    @SerialName("language") val language: String? = null,
    @SerialName("format") val format: String? = null,
    @SerialName("bit_rate") val bitRate: String? = null,
)

// ADDED: New model for the video_details array returned by the updated API.
// NOTE: width/height come as strings like "1 920 pixels" / "1 080 pixels" from the API,
// so we store them as String and parse digits-only in Av1Encodes.kt.
@Serializable
internal data class VideoDetailInfo(
    @SerialName("width") val width: String? = null,
    @SerialName("height") val height: String? = null,
    @SerialName("frame_rate") val frameRate: String? = null,
    @SerialName("format") val format: String? = null,
    @SerialName("bit_depth") val bitDepth: String? = null,
    @SerialName("duration") val duration: String? = null,
)

// ADDED: Model for a single item in the /episodes/ JSON array response.
// The API now returns a JSON array where each item includes the episode href
// (with the ?token= query param already embedded) and optionally the full DDL
// block, so we can skip the /get_ddl/ round-trip when the data is already present.
@Serializable
internal data class EpisodeItem(
    @SerialName("num") val num: Int = 0,
    @SerialName("label") val label: String = "",
    @SerialName("audio") val audio: String = "",
    // Full relative path, e.g. "/download/slug/1/1920%20x%201080/file.mkv?token=..."
    @SerialName("href") val href: String = "",
    // May already contain the resolved DDL links – use as a fast path in getVideoList.
    @SerialName("ddl") val ddl: DdlResponse? = null,
)
