package eu.kanade.tachiyomi.lib.m3u8server

import java.nio.ByteBuffer

/**
 * Automatic file format detector and offset calculator
 */
class AutoDetector {

    companion object {
        // Magic headers for different formats
        private val JPEG_HEADER = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        private val PNG_HEADER = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte())
        private val GIF_HEADER = byteArrayOf(0x47.toByte(), 0x49.toByte(), 0x46.toByte())
        private val MPEG_TS_SYNC = 0x47.toByte()
        private val MP4_FTYP = byteArrayOf(0x66.toByte(), 0x74.toByte(), 0x79.toByte(), 0x70.toByte()) // "ftyp"
        private val AVI_RIFF = byteArrayOf(0x52.toByte(), 0x49.toByte(), 0x46.toByte(), 0x46.toByte()) // "RIFF"
        private val AVI_AVI = byteArrayOf(0x41.toByte(), 0x56.toByte(), 0x49.toByte(), 0x20.toByte()) // "AVI "
        private val MPEG_TS_PACKET_SIZE = 188

        /**
         * Automatically detects how many bytes to skip at the beginning of the file
         * @param data File data (first 4KB is sufficient)
         * @return Number of bytes to skip
         */
        fun detectSkipBytes(data: ByteArray): Int {
            if (data.isEmpty()) return 0

            return when {
                // If it's already a valid MPEG-TS, don't need to skip anything
                isMpegTsValid(data) -> 0

                // If it's JPEG disguising another format
                isJpegHeader(data) -> detectJpegDisguise(data)

                // If it's PNG disguising another format
                isPngHeader(data) -> detectPngDisguise(data)

                // If it's GIF disguising another format
                isGifHeader(data) -> detectGifDisguise(data)

                // If it's already a valid video format
                isVideoFormat(data) -> 0

                // Unrecognized pattern, don't skip anything
                else -> 0
            }
        }

        /**
         * Checks if it's a valid MPEG-TS stream
         */
        private fun isMpegTsValid(data: ByteArray): Boolean {
            if (data.size < MPEG_TS_PACKET_SIZE) return false

            // Check if the first byte is sync byte
            if (data[0] != MPEG_TS_SYNC) return false

            // Check if there are multiple sync bytes in correct locations
            var validPackets = 0
            for (i in 0 until minOf(data.size, 1024) step MPEG_TS_PACKET_SIZE) {
                if (i + MPEG_TS_PACKET_SIZE <= data.size && data[i] == MPEG_TS_SYNC) {
                    validPackets++
                }
            }

            return validPackets >= 3
        }

        /**
         * Checks if it starts with JPEG header
         */
        private fun isJpegHeader(data: ByteArray): Boolean {
            return data.size >= 3 &&
                   data[0] == JPEG_HEADER[0] &&
                   data[1] == JPEG_HEADER[1] &&
                   data[2] == JPEG_HEADER[2]
        }

        /**
         * Checks if it starts with PNG header
         */
        private fun isPngHeader(data: ByteArray): Boolean {
            return data.size >= 4 &&
                   data[0] == PNG_HEADER[0] &&
                   data[1] == PNG_HEADER[1] &&
                   data[2] == PNG_HEADER[2] &&
                   data[3] == PNG_HEADER[3]
        }

        /**
         * Checks if it starts with GIF header
         */
        private fun isGifHeader(data: ByteArray): Boolean {
            return data.size >= 3 &&
                   data[0] == GIF_HEADER[0] &&
                   data[1] == GIF_HEADER[1] &&
                   data[2] == GIF_HEADER[2]
        }

        /**
         * Detects if JPEG is disguising another format
         */
        private fun detectJpegDisguise(data: ByteArray): Int {
            // Look for MP4 "ftyp" box
            val ftypOffset = findPattern(data, MP4_FTYP)
            if (ftypOffset > 0) {
                return ftypOffset - 4 // "ftyp" is preceded by 4 bytes of size
            }

            // Look for AVI "RIFF"
            val riffOffset = findPattern(data, AVI_RIFF)
            if (riffOffset > 0) {
                return riffOffset
            }

            // Look for MPEG-TS sync byte
            val mpegTsOffset = findMpegTsSync(data)
            if (mpegTsOffset > 0) {
                return mpegTsOffset
            }

            return 0
        }

        /**
         * Detects if PNG is disguising another format
         */
        private fun detectPngDisguise(data: ByteArray): Int {
            // Look for AVI "RIFF"
            val riffOffset = findPattern(data, AVI_RIFF)
            if (riffOffset > 0) {
                return riffOffset
            }

            // Look for MP4 "ftyp" box
            val ftypOffset = findPattern(data, MP4_FTYP)
            if (ftypOffset > 0) {
                return ftypOffset - 4
            }

            // Look for MPEG-TS sync byte
            val mpegTsOffset = findMpegTsSync(data)
            if (mpegTsOffset > 0) {
                return mpegTsOffset
            }

            return 0
        }

        /**
         * Detects if GIF is disguising another format
         */
        private fun detectGifDisguise(data: ByteArray): Int {
            // Look for MP4 "ftyp" box
            val ftypOffset = findPattern(data, MP4_FTYP)
            if (ftypOffset > 0) {
                return ftypOffset - 4
            }

            // Look for AVI "RIFF"
            val riffOffset = findPattern(data, AVI_RIFF)
            if (riffOffset > 0) {
                return riffOffset
            }

            // Look for MPEG-TS sync byte
            val mpegTsOffset = findMpegTsSync(data)
            if (mpegTsOffset > 0) {
                return mpegTsOffset
            }

            return 0
        }

        /**
         * Checks if it's already a valid video format
         */
        private fun isVideoFormat(data: ByteArray): Boolean {
            return isMpegTsValid(data) ||
                   findPattern(data, MP4_FTYP) > 0 ||
                   findPattern(data, AVI_RIFF) > 0
        }

        /**
         * Finds a specific pattern in the data
         */
        private fun findPattern(data: ByteArray, pattern: ByteArray): Int {
            for (i in 0..data.size - pattern.size) {
                var found = true
                for (j in pattern.indices) {
                    if (data[i + j] != pattern[j]) {
                        found = false
                        break
                    }
                }
                if (found) {
                    return i
                }
            }
            return -1
        }

        /**
         * Finds the first MPEG-TS sync byte
         */
        private fun findMpegTsSync(data: ByteArray): Int {
            for (i in data.indices) {
                if (data[i] == MPEG_TS_SYNC) {
                    // Check if there's a pattern of sync bytes
                    var validCount = 0
                    for (j in i until minOf(data.size, i + 1024) step MPEG_TS_PACKET_SIZE) {
                        if (j + MPEG_TS_PACKET_SIZE <= data.size && data[j] == MPEG_TS_SYNC) {
                            validCount++
                        }
                    }
                    if (validCount >= 2) {
                        return i
                    }
                }
            }
            return -1
        }
    }
}
