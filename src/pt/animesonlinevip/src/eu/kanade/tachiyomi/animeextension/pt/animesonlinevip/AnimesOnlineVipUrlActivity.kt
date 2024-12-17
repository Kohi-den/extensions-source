package eu.kanade.tachiyomi.animeextension.pt.animesonlinevip

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.system.exitProcess

/**
 * Springboard that accepts https://animesonlinehd.vip/anime/<slug> and https://animesonlinehd.vip/<id> intents
 * and redirects them to the main Aniyomi process.
 */
class AnimesOnlineVipUrlActivity : Activity() {

    private val tag = javaClass.simpleName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pathSegments = intent?.data?.pathSegments
        if (pathSegments != null && pathSegments.size > 0) {
            val searchQuery = if (pathSegments.size > 1) {
                "${pathSegments[0]}/${pathSegments[1]}"
            } else {
                pathSegments[0]
            }

            val mainIntent = Intent().apply {
                action = "eu.kanade.tachiyomi.ANIMESEARCH"
                putExtra("query", "${AnimesOnlineVip.PREFIX_SEARCH}$searchQuery")
                putExtra("filter", packageName)
            }

            try {
                startActivity(mainIntent)
            } catch (e: ActivityNotFoundException) {
                Log.e(tag, e.toString())
            }
        } else {
            Log.e(tag, "could not parse uri from intent $intent")
        }

        finish()
        exitProcess(0)
    }
}
