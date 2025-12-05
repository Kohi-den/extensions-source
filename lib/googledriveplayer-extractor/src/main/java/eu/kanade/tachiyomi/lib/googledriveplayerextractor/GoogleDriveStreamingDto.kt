package eu.kanade.tachiyomi.lib.googledriveplayerextractor

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GoogleDriveStreamingResponse(
    @SerialName("mediaStreamingData") val mediaStreamingData: MediaStreamingData,
)

@Serializable
data class MediaStreamingData(
    @SerialName("formatStreamingData") val formatStreamingData: FormatStreamingData,
)

@Serializable
data class FormatStreamingData(
    @SerialName("progressiveTranscodes") val progressiveTranscodes: List<ProgressiveTranscode>,
    @SerialName("adaptiveTranscodes") val adaptiveTranscodes: List<AdaptiveTranscode>,
)

@Serializable
data class ProgressiveTranscode(
    val itag: Int,
    val url: String,
    @SerialName("transcodeMetadata") val transcodeMetadata: TranscodeMetadata,
)

@Serializable
data class AdaptiveTranscode(
    val itag: Int,
    val url: String,
    @SerialName("transcodeMetadata") val transcodeMetadata: AdaptiveTranscodeMetadata,
)

@Serializable
data class TranscodeMetadata(
    val height: Int,
)

@Serializable
data class AdaptiveTranscodeMetadata(
    @SerialName("mimeType") val mimeType: String,
    val height: Int,
    @SerialName("maxContainerBitrate") val maxContainerBitrate: Int,
    @SerialName("audioCodecString") val audioCodecString: String? = null,
)

