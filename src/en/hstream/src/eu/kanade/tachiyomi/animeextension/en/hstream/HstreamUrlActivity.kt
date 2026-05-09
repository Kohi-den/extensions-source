package eu.kanade.tachiyomi.animeextension.en.hstream

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import kotlin.system.exitProcess

/**
 * Springboard that accepts https://hstream.moe/hentai/<item> intents
 * and redirects them to the main Aniyomi process.
 */
class HstreamUrlActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pathSegments = intent?.data?.pathSegments
        if (pathSegments != null && pathSegments.size > 1) {
            val item = pathSegments[1]
            val mainIntent = Intent().apply {
                action = "eu.kanade.tachiyomi.ANIMESEARCH"
                putExtra("query", "${Hstream.PREFIX_SEARCH}$item")
                putExtra("filter", packageName)
            }

            try {
                HstreamLogger.debug("HstreamUrlActivity", "Redirecting to Aniyomi with item=$item")
                startActivity(mainIntent)
            } catch (e: ActivityNotFoundException) {
                HstreamLogger.error("HstreamUrlActivity", e.toString())
                Toast.makeText(this@HstreamUrlActivity, "Unable to open Aniyomi. Please ensure the app is installed.", Toast.LENGTH_LONG).show()
            }
        } else {
            HstreamLogger.error("HstreamUrlActivity", "could not parse uri from intent $intent")
        }

        finish()
        exitProcess(0)
    }
}
