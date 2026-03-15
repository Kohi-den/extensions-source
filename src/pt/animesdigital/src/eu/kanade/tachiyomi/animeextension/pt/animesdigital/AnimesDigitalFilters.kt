package eu.kanade.tachiyomi.animeextension.pt.animesdigital

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilter.TriState
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

class AnimesDigitalFilters(
    private val baseUrl: String,
    private val client: OkHttpClient,
) {
    private var error = false

    private lateinit var filterList: AnimeFilterList

    fun filterInitialized(): Boolean {
        return this::filterList.isInitialized
    }

    open class QueryPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toQueryPart() = vals[state].second
    }

    open class TriStateFilterList(name: String, values: List<TriFilterVal>) : AnimeFilter.Group<TriState>(name, values)
    class TriFilterVal(name: String) : TriState(name)

    private inline fun <reified R> AnimeFilterList.asQueryPart(): String {
        return (first { it is R } as QueryPartFilter).toQueryPart()
    }

    private inline fun <reified R> AnimeFilterList.parseTriFilter(
        options: Array<Pair<String, String>>,
    ): List<List<String>> {
        return (first { it is R } as TriStateFilterList).state
            .filterNot { it.isIgnored() }
            .map { filter -> filter.state to options.find { it.first == filter.name }!!.second }
            .groupBy { it.first } // group by state
            .let { dict ->
                val included = dict.get(TriState.STATE_INCLUDE)?.map { it.second }.orEmpty()
                val excluded = dict.get(TriState.STATE_EXCLUDE)?.map { it.second }.orEmpty()
                listOf(included, excluded)
            }
    }

    class InitialLetterFilter : QueryPartFilter("Primeira letra", AnimesDigitalFiltersData.INITIAL_LETTER)
    class OrderFilter : QueryPartFilter("Ordenar por", AnimesDigitalFiltersData.ORDERS)
    class AudioFilter : QueryPartFilter("Língua/Áudio", AnimesDigitalFiltersData.AUDIOS)
    class TypeFilter : QueryPartFilter("Tipo", AnimesDigitalFiltersData.TYPES)

    class GenresFilter(genres: Array<Pair<String, String>>) : TriStateFilterList(
        "Gêneros",
        genres.map { TriFilterVal(it.first) },
    ) {
        val genresArray = genres
    }

    fun getFilterList(): AnimeFilterList {
        return if (error) {
            AnimeFilterList(AnimeFilter.Header("Erro ao buscar os filtros."))
        } else if (filterInitialized()) {
            filterList
        } else {
            AnimeFilterList(AnimeFilter.Header("Aperte 'Redefinir' para tentar mostrar os filtros"))
        }
    }

    fun fetchFilters() {
        if (!filterInitialized()) {
            runCatching {
                error = false
                val document = client.newCall(GET("$baseUrl/animes-legendados-online"))
                    .execute()
                    .asJsoup()
                filterList = filtersParse(document)
            }.onFailure {
                error = true
            }
        }
    }

    private fun filtersParse(document: Document): AnimeFilterList {
        val genres = document.select("li.filter_genre")
            .mapNotNull { element ->
                val name = element.text().trim()
                val value = element.attr("data-value")
                if (name.isNotEmpty() && value.isNotEmpty()) {
                    Pair(name, value)
                } else {
                    null
                }
            }
            .sortedBy { it.first }
            .toTypedArray()

        val genresFilters = GenresFilter(genres)

        return AnimeFilterList(
            InitialLetterFilter(),
            OrderFilter(),
            AudioFilter(),
            TypeFilter(),
            AnimeFilter.Separator(),
            genresFilters,
        )
    }

    data class FilterSearchParams(
        val initialLetter: String = "0",
        val orderBy: String = "name",
        val audio: String = "0",
        val type: String = "animes",
        val genres: List<String> = emptyList(),
        val deleted_genres: List<String> = emptyList(),
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty() || !filterInitialized()) return FilterSearchParams()
        val genresFilter = filters.first { it is GenresFilter } as GenresFilter
        val (added, deleted) = filters.parseTriFilter<GenresFilter>(genresFilter.genresArray)

        return FilterSearchParams(
            filters.asQueryPart<InitialLetterFilter>(),
            filters.asQueryPart<OrderFilter>(),
            filters.asQueryPart<AudioFilter>(),
            filters.asQueryPart<TypeFilter>(),
            added,
            deleted,
        )
    }

    private object AnimesDigitalFiltersData {
        val INITIAL_LETTER = arrayOf(Pair("Selecione", "0")) + ('A'..'Z').map {
            Pair(it.toString(), it.toString().lowercase())
        }.toTypedArray()

        val ORDERS = arrayOf(
            Pair("Nome", "name"),
            Pair("Data", "new"),
        )

        val AUDIOS = arrayOf(
            Pair("Todos", "0"),
            Pair("Legendado", "legendado"),
            Pair("Dublado", "dublado"),
        )

        val TYPES = arrayOf(
            Pair("Animes", "animes"),
            Pair("Desenhos", "desenhos"),
            Pair("Doramas", "doramas"),
            Pair("Filmes", "filmes"),
            Pair("Tokusatsus", "tokusatsus"),
        )
    }
}
