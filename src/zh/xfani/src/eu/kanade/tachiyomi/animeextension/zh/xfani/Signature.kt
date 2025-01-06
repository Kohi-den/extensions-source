package eu.kanade.tachiyomi.animeextension.zh.xfani

import java.security.MessageDigest

private const val UID = "DCC147D11943AF75"

internal fun generateKey(time: Long): String {
    return "DS${time}$UID".md5()
}

internal fun String.md5(): String {
    val md = MessageDigest.getInstance("MD5")
    val digest = md.digest(this.toByteArray())
    return digest.joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }
}
