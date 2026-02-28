package eu.kanade.tachiyomi.animeextension.en.hanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

object RequestBodyBuilder {
    fun searchRequestBody(query: String, page: Int, filters: AnimeFilterList, source: Hanime): RequestBody {
        val (includedTags, blackListedTags, brands, tagsMode, orderBy, ordering) =
            FilterProcessor.getSearchParameters(filters, source)

        return """
            {"search_text": "$query",
            "tags": $includedTags,
            "tags_mode":"$tagsMode",
            "brands": $brands,
            "blacklist": $blackListedTags,
            "order_by": "$orderBy",
            "ordering": "$ordering",
            "page": ${page - 1}}
        """.trimIndent().toRequestBody("application/json".toMediaType())
    }

    fun latestSearchRequestBody(page: Int): RequestBody {
        return """
            {"search_text": "",
            "tags": [],
            "tags_mode":"AND",
            "brands": [],
            "blacklist": [],
            "order_by": "published_at_unix",
            "ordering": "desc",
            "page": ${page - 1}}
        """.trimIndent().toRequestBody("application/json".toMediaType())
    }
}
