package eu.kanade.tachiyomi.animeextension.en.animekai

data class TokenRequestDTO(
    val id: String,
    val time: String,
)

data class IframeDataDTO(
    val encryptedData: String,
)

data class AnimekaiDecoderDTO(
    val secret: String = AnimekaiDecoder.SECRET,
    val iv: String = AnimekaiDecoder.IV,
    val tokenRequest: TokenRequestDTO,
    val iframeData: IframeDataDTO,
)
