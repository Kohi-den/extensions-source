package eu.kanade.tachiyomi.animeextension.en.nineanime

import android.util.Base64
import java.net.URLDecoder
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class AniwaveUtils {

    fun vrfEncrypt(key: String, input: String): String {
        val rc4Key = SecretKeySpec(key.toByteArray(), "RC4")
        val cipher = Cipher.getInstance("RC4")
        cipher.init(Cipher.DECRYPT_MODE, rc4Key, cipher.parameters)
        var vrf = cipher.doFinal(input.toByteArray())
        vrf = Base64.encode(vrf, Base64.URL_SAFE or Base64.NO_WRAP)
        var vrfString = vrf.toString(Charsets.UTF_8)
        return java.net.URLEncoder.encode(vrfString, "utf-8")
    }

    fun vrfDecrypt(key: String, input: String): String {
        var vrf = Base64.decode(input.toByteArray(), Base64.URL_SAFE)
        val rc4Key = SecretKeySpec(key.toByteArray(), "RC4")
        val cipher = Cipher.getInstance("RC4")
        cipher.init(Cipher.DECRYPT_MODE, rc4Key, cipher.parameters)
        vrf = cipher.doFinal(vrf)
        return URLDecoder.decode(vrf.toString(Charsets.UTF_8), "utf-8")
    }
}
