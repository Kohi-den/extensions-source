package eu.kanade.tachiyomi.animeextension.en.animekai

// DTO for generating token requests
data class TokenRequestDTO(
    val id: String,
    val time: String
)

// DTO for representing the decoded iframe data
data class IframeDataDTO(
    val encryptedData: String
)

// Main DTO for passing decoder configurations (secret, iv) and data for encoding/decoding
data class AnimekaiDecoderDTO(
    val secret: String = AnimekaiDecoder.SECRET,
    val iv: String = AnimekaiDecoder.IV,
    val tokenRequest: TokenRequestDTO,
    val iframeData: IframeDataDTO
)
