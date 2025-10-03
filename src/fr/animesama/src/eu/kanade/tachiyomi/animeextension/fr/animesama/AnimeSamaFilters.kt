package eu.kanade.tachiyomi.animeextension.fr.animesama

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AnimeSamaFilters {

    open class CheckBoxFilterList(name: String, values: List<CheckBox>) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, values)

    private class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    private inline fun <reified R> AnimeFilterList.getFirst(): R {
        return this.filterIsInstance<R>().first()
    }

    private inline fun <reified R> AnimeFilterList.parseCheckbox(
        options: Array<Pair<String, String>>,
    ): List<String> {
        return (this.getFirst<R>() as CheckBoxFilterList).state
            .mapNotNull { checkbox ->
                if (checkbox.state) {
                    options.find { it.first == checkbox.name }!!.second
                } else {
                    null
                }
            }
    }

    class TypesFilter : CheckBoxFilterList(
        "Type",
        AnimeSamaFiltersData.TYPES.map { CheckBoxVal(it.first, false) },
    )

    class LangFilter : CheckBoxFilterList(
        "Langage",
        AnimeSamaFiltersData.LANGUAGES.map { CheckBoxVal(it.first, false) },
    )

    class GenresFilter : CheckBoxFilterList(
        "Genre",
        AnimeSamaFiltersData.GENRES.map { CheckBoxVal(it.first, false) },
    )

    val FILTER_LIST get() = AnimeFilterList(
        TypesFilter(),
        LangFilter(),
        GenresFilter(),
    )

    data class SearchFilters(
        val types: List<String> = emptyList(),
        val language: List<String> = emptyList(),
        val genres: List<String> = emptyList(),
    )

    fun getSearchFilters(filters: AnimeFilterList): SearchFilters {
        if (filters.isEmpty()) return SearchFilters()
        return SearchFilters(
            filters.parseCheckbox<TypesFilter>(AnimeSamaFiltersData.TYPES),
            filters.parseCheckbox<LangFilter>(AnimeSamaFiltersData.LANGUAGES),
            filters.parseCheckbox<GenresFilter>(AnimeSamaFiltersData.GENRES),
        )
    }

    private object AnimeSamaFiltersData {
        val TYPES = arrayOf(
            Pair("Anime", "Anime"),
            Pair("Film", "Film"),
            Pair("Autres", "Autres"),
        )

        val LANGUAGES = arrayOf(
            Pair("VF", "VF"),
            Pair("VOSTFR", "VOSTFR"),
            Pair("VASTFR", "VASTFR"),
        )

        val GENRES = arrayOf(
            Pair("Action", "Action"),
            Pair("Adolescence", "Adolescence"),
            Pair("Aliens / Extra-terrestres", "Aliens / Extra-terrestres"),
            Pair("Amitié", "Amitié"),
            Pair("Amour", "Amour"),
            Pair("Apocalypse", "Apocalypse"),
            Pair("Art", "Art"),
            Pair("Arts martiaux", "Arts martiaux"),
            Pair("Assassinat", "Assassinat"),
            Pair("Autre monde", "Autre monde"),
            Pair("Aventure", "Aventure"),
            Pair("Combats", "Combats"),
            Pair("Comédie", "Comédie"),
            Pair("Crime", "Crime"),
            Pair("Cyberpunk", "Cyberpunk"),
            Pair("Danse", "Danse"),
            Pair("Démons", "Démons"),
            Pair("Détective", "Détective"),
            Pair("Donghua", "Donghua"),
            Pair("Drame", "Drame"),
            Pair("Ecchi", "Ecchi"),
            Pair("Ecole", "Ecole"),
            Pair("Enquête", "Enquête"),
            Pair("Famille", "Famille"),
            Pair("Fantastique", "Fantastique"),
            Pair("Fantasy", "Fantasy"),
            Pair("Fantômes", "Fantômes"),
            Pair("Futur", "Futur"),
            Pair("Ghibli", "Ghibli"),
            Pair("Guerre", "Guerre"),
            Pair("Harcèlement", "Harcèlement"),
            Pair("Harem", "Harem"),
            Pair("Harem inversé", "Harem inversé"),
            Pair("Histoire", "Histoire"),
            Pair("Historique", "Historique"),
            Pair("Horreur", "Horreur"),
            Pair("Isekai", "Isekai"),
            Pair("Jeunesse", "Jeunesse"),
            Pair("Jeux", "Jeux"),
            Pair("Jeux vidéo", "Jeux vidéo"),
            Pair("Josei", "Josei"),
            Pair("Journalisme", "Journalisme"),
            Pair("Mafia", "Mafia"),
            Pair("Magical girl", "Magical girl"),
            Pair("Magie", "Magie"),
            Pair("Maladie", "Maladie"),
            Pair("Mariage", "Mariage"),
            Pair("Mature", "Mature"),
            Pair("Mechas", "Mechas"),
            Pair("Médiéval", "Médiéval"),
            Pair("Militaire", "Militaire"),
            Pair("Monde virtuel", "Monde virtuel"),
            Pair("Monstres", "Monstres"),
            Pair("Musique", "Musique"),
            Pair("Mystère", "Mystère"),
            Pair("Nekketsu", "Nekketsu"),
            Pair("Ninjas", "Ninjas"),
            Pair("Nostalgie", "Nostalgie"),
            Pair("Paranormal", "Paranormal"),
            Pair("Philosophie", "Philosophie"),
            Pair("Pirates", "Pirates"),
            Pair("Police", "Police"),
            Pair("Politique", "Politique"),
            Pair("Post-apocalyptique", "Post-apocalyptique"),
            Pair("Pouvoirs psychiques", "Pouvoirs psychiques"),
            Pair("Préhistoire", "Préhistoire"),
            Pair("Prison", "Prison"),
            Pair("Psychologique", "Psychologique"),
            Pair("Quotidien", "Quotidien"),
            Pair("Religion", "Religion"),
            Pair("Réincarnation / Transmigration", "Réincarnation / Transmigration"),
            Pair("Romance", "Romance"),
            Pair("Samouraïs", "Samouraïs"),
            Pair("School Life", "School Life"),
            Pair("Science-Fantasy", "Science-Fantasy"),
            Pair("Science-fiction", "Science-fiction"),
            Pair("Scientifique", "Scientifique"),
            Pair("Seinen", "Seinen"),
            Pair("Shôjo", "Shôjo"),
            Pair("Shônen", "Shônen"),
            Pair("Shônen-Ai", "Shônen-Ai"),
            Pair("Slice of Life", "Slice of Life"),
            Pair("Société", "Société"),
            Pair("Sport", "Sport"),
            Pair("Super pouvoirs", "Super pouvoirs"),
            Pair("Super-héros", "Super-héros"),
            Pair("Surnaturel", "Surnaturel"),
            Pair("Survie", "Survie"),
            Pair("Survival game", "Survival game"),
            Pair("Technologies", "Technologies"),
            Pair("Thriller", "Thriller"),
            Pair("Tournois", "Tournois"),
            Pair("Travail", "Travail"),
            Pair("Vampires", "Vampires"),
            Pair("Vengeance", "Vengeance"),
            Pair("Voyage", "Voyage"),
            Pair("Voyage temporel", "Voyage temporel"),
            Pair("Webcomic", "Webcomic"),
            Pair("Yakuza", "Yakuza"),
            Pair("Yaoi", "Yaoi"),
            Pair("Yokai", "Yokai"),
            Pair("Yuri", "Yuri"),
        )
    }
}
