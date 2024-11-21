package eu.kanade.tachiyomi.animeextension.all.jable

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory

class JableFactory : AnimeSourceFactory {
    override fun createSources(): List<AnimeSource> {
        return listOf(
            Jable("zh"),
            Jable("en"),
            Jable("jp"),
        )
    }
}
