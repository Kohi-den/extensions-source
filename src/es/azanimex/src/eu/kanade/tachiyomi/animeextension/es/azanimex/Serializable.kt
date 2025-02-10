package eu.kanade.tachiyomi.animeextension.es.azanimex

import kotlinx.serialization.Serializable

@Serializable
data class OneDriveResponse(
    val file: FileItem? = null, // El archivo puede ser null
    val folder: Folder? = null, // La carpeta también puede ser null
)

@Serializable
data class Folder(
    val value: List<FileItem>,
)

@Serializable
data class FileInfo(
    val mimeType: String,
)

@Serializable
data class FileItem(
    val id: String,
    val name: String,
    val file: FileInfo,
    val video: VideoInfo?,
)

@Serializable
data class VideoInfo(
    val height: Int,
    val width: Int,
    val audioChannels: Int,
    val audioBitsPerSample: Int,
    val frameRate: Double,
    val bitRate: Int,
    val duration: Int,
)
