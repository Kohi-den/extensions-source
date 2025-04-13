package eu.kanade.tachiyomi.animeextension.en.animekai

/**
 * Data Transfer Object for token request.
 *
 * @property id The unique identifier for the token request.
 * @property time The timestamp for the token request.
 */
data class TokenRequestDTO(
    val id: String,
    val time: String,
)

/**
 * Data Transfer Object for iframe data.
 *
 * @property encryptedData The encrypted data contained in the iframe.
 */
data class IframeDataDTO(
    val encryptedData: String,
)

/**
 * Data Transfer Object for AnimeKai decoder.
 *
 * @property secret The secret key for decoding.
 * @property iv The initialization vector for decoding.
 * @property tokenRequest The token request data.
 * @property iframeData The iframe data to decode.
 */
data class AnimekaiDecoderDTO(
    val secret: String = AnimekaiDecoder.SECRET,
    val iv: String = AnimekaiDecoder.IV,
    val tokenRequest: TokenRequestDTO,
    val iframeData: IframeDataDTO,
)