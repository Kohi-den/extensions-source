package eu.kanade.tachiyomi.animeextension.en.sakuhentai

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import kotlin.system.exitProcess

class SakuhentaiUrlActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pathSegments = intent?.data?.pathSegments

        if (pathSegments != null && pathSegments.isNotEmpty()) {
            val slug = pathSegments.joinToString("/")

            val mainIntent = Intent().apply {
                action = "eu.kanade.tachiyomi.ANIMESEARCH"
                putExtra("query", "${Sakuhentai.PREFIX_SEARCH}$slug")
                putExtra("filter", packageName)
            }

            startActivity(mainIntent)
        }

        finish()
        exitProcess(0)
    }
}
