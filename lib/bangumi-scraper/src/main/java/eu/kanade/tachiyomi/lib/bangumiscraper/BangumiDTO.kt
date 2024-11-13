@file:UseSerializers(BoxItemSerializer::class)
package eu.kanade.tachiyomi.lib.bangumiscraper

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Serializer
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
internal data class Images(
    val large: String,
    val common: String,
    val medium: String,
    val small: String,
)

@Serializable
internal data class BoxItem(
    val key: String,
    val value: String,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializer(forClass = BoxItem::class)
internal object BoxItemSerializer : KSerializer<BoxItem> {
    override fun deserialize(decoder: Decoder): BoxItem {
        val item = (decoder as JsonDecoder).decodeJsonElement().jsonObject
        val key = item["key"]!!.jsonPrimitive.content
        val value = (item["value"] as? JsonPrimitive)?.contentOrNull ?: ""
        return BoxItem(key, value)
    }
}

@Serializable
internal data class Subject(
    val name: String,
    @SerialName("name_cn")
    val nameCN: String,
    val summary: String,
    val images: Images,
    @SerialName("meta_tags")
    val metaTags: List<String>,
    @SerialName("infobox")
    val infoBox: List<BoxItem>,
) {
    fun findAuthor(): String? {
        return findInfo("导演", "原作")
    }

    fun findArtist(): String? {
        return findInfo("美术监督", "总作画监督", "动画制作")
    }

    fun findInfo(vararg keys: String): String? {
        keys.forEach { key ->
            return infoBox.find { item ->
                item.key == key
            }?.value ?: return@forEach
        }
        return null
    }
}

@Serializable
internal data class SearchItem(
    val id: Int,
    val name: String,
    @SerialName("name_cn")
    val nameCN: String,
    val summary: String,
    val images: Images,
)

@Serializable
internal data class SearchResponse(val results: Int, val list: List<SearchItem>)
