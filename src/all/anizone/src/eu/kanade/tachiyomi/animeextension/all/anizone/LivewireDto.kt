package eu.kanade.tachiyomi.animeextension.all.anizone

import kotlinx.serialization.Serializable

@Serializable
class LivewireDto(
    val components: List<ComponentDto>,
) {
    @Serializable
    class ComponentDto(
        val snapshot: String,
        val effects: EffectsDto,
    ) {
        @Serializable
        class EffectsDto(
            val html: String,
        )
    }
}
