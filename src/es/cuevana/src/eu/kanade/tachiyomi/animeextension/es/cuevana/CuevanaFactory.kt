package eu.kanade.tachiyomi.animeextension.es.cuevana

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory

class CuevanaFactory : AnimeSourceFactory {
    override fun createSources(): List<AnimeSource> = listOf(
        CuevanaCh("Cuevana3Is", "https://www.cuevana.is"),
        CuevanaEu("Cuevana3Eu", "https://www.cuevana3.eu"),
    )
}
