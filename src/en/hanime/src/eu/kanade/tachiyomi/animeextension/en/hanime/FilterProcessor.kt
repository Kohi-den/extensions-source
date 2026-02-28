package eu.kanade.tachiyomi.animeextension.en.hanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import java.util.Locale

object FilterProcessor {
    fun getSearchParameters(filters: AnimeFilterList, source: Hanime): SearchParameters {
        val includedTags = ArrayList<String>()
        val blackListedTags = ArrayList<String>()
        val brands = ArrayList<String>()
        var tagsMode = "AND"
        var orderBy = "likes"
        var ordering = "desc"

        filters.forEach { filter ->
            when (filter) {
                is FilterProvider.TagList -> {
                    filter.state.forEach { tag ->
                        if (tag.isIncluded()) {
                            includedTags.add("\"" + tag.id.lowercase(Locale.US) + "\"")
                        } else if (tag.isExcluded()) {
                            blackListedTags.add("\"" + tag.id.lowercase(Locale.US) + "\"")
                        }
                    }
                }
                is FilterProvider.TagInclusionMode -> {
                    tagsMode = filter.values[filter.state].uppercase(Locale.US)
                }
                is FilterProvider.SortFilter -> {
                    if (filter.state != null) {
                        val query = FilterProvider.SORTABLE_LIST[filter.state!!.index].second
                        ordering = if (filter.state!!.ascending) "asc" else "desc"
                        orderBy = query
                    }
                }
                is FilterProvider.BrandList -> {
                    filter.state.forEach { brand ->
                        if (brand.state) {
                            brands.add("\"" + brand.id.lowercase(Locale.US) + "\"")
                        }
                    }
                }
                else -> {}
            }
        }

        return SearchParameters(includedTags, blackListedTags, brands, tagsMode, orderBy, ordering)
    }
}
