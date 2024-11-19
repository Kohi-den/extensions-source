package eu.kanade.tachiyomi.animeextension.all.jable

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

data class BlockFunction(
    val name: String,
    val blockId: String,
    val functionName: String = "get_block",
)

class BlockFunctionFilter(name: String, private val functions: Array<BlockFunction>) :
    AnimeFilter.Select<String>(name, functions.map { it.name }.toTypedArray()) {
    val selected
        get() = functions[state]
}

open class UriPartFilter(name: String, private val pairs: Array<Pair<String, String>>) :
    AnimeFilter.Select<String>(name, pairs.map { it.first }.toTypedArray()) {
    val selected
        get() = pairs[state]
}

class SortFilter(name: String, pairs: Array<Pair<String, String>>) : UriPartFilter(name, pairs)

class TagFilter(name: String, pairs: Array<Pair<String, String>>) : UriPartFilter(name, pairs)
