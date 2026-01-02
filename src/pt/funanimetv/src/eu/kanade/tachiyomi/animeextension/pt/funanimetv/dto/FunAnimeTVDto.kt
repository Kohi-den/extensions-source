package eu.kanade.tachiyomi.animeextension.pt.funanimetv.dto

import android.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.RequestBody
import java.security.MessageDigest

@Serializable
class FunAnimeTVRequest(
    @SerialName("method_name")
    val methodName: String,
    @Transient
    val singsalt: String = "",
) {
    @SerialName("salt")
    val salt: String = (1..900).random().toString()

    @SerialName("sign")
    val sign: String = run {
        MessageDigest.getInstance("MD5")
            .digest("$singsalt$salt".toByteArray(Charsets.UTF_8))
            .joinToString("") {
                "%02x".format(it)
            }
    }

    fun toBase64(json: Json): String {
        return Base64.encodeToString(json.encodeToString(this).toByteArray(), Base64.NO_PADDING)
    }

    fun toRequestBody(json: Json): RequestBody {
        return FormBody.Builder()
            .add("data", toBase64(json))
            .build()
    }
}

@Serializable
data class GetAppDetailsResponse(
    @SerialName("SINGSALT")
    val singsalt: String,
    @SerialName("ARRAYPADRAO")
    val arrayPadrao: String,
)

@Serializable
data class GetHomeVideosResponse(
    @SerialName("most_viewed")
    val mostViewed: List<MostViewed>,
    @SerialName("latest_video")
    val latestVideo: List<LatestVideo>,
    @SerialName("latest_video_dub")
    val latestVideoDub: List<LatestVideoDub>,
    @SerialName("all_video_cat")
    val allVideoCat: List<AllVideoCat>,
) {
    @Serializable
    data class MostViewed(
        val cid: String,
        @SerialName("category_name")
        val categoryName: String,
        val genero: String,
        val sinopse: String,
        @SerialName("category_image")
        val categoryImage: String,
        val tid: String,
        @SerialName("is_temporada")
        val isTemporada: Boolean,
    )

    @Serializable
    data class LatestVideo(
        val id: String,
        @SerialName("video_title")
        val videoTitle: String,
        @SerialName("video_thumbnail_b")
        val videoThumbnailB: String,
        @SerialName("category_name")
        val categoryName: String,
    )

    @Serializable
    data class LatestVideoDub(
        val id: String,
        @SerialName("video_title")
        val videoTitle: String,
        @SerialName("video_thumbnail_b")
        val videoThumbnailB: String,
        @SerialName("category_name")
        val categoryName: String,
    )

    @Serializable
    data class AllVideoCat(
        val cid: String,
        @SerialName("category_name")
        val categoryName: String,
        val sinopse: String,
        @SerialName("category_image")
        val categoryImage: String,
        val tid: String,
    )
}

@Serializable
data class SearchVideoItemDto(
    val cid: String,
    @SerialName("category_name")
    val categoryName: String,
    val genero: String,
    val sinopse: String,
    @SerialName("audio_type")
    val audioType: String,
    @SerialName("category_image")
    val categoryImage: String,
    @SerialName("is_temporada")
    val isTemporada: Boolean,
    val tid: String,
    @SerialName("temp_name")
    val tempName: String,
)

@Serializable
data class SingleVideoItemDto(
    @SerialName("cat_id")
    val catId: String,
    @SerialName("category_name")
    val categoryName: String,
    @SerialName("video_url_fhd")
    val videoUrlFhd: String,
    @SerialName("video_url_sd")
    val videoUrlSd: String,
    @SerialName("video_url")
    val videoUrl: String,
    @SerialName("temp_id")
    val tempId: String,
    @SerialName("temp_name")
    val tempName: String,
    @SerialName("temp_image")
    val tempImage: String,
)

// get_video_by_cat
@Serializable
data class VideoByCatItemDto(
    val id: String,
    @SerialName("video_title")
    val videoTitle: String,
    @SerialName("video_ep")
    val videoEp: String,
)
