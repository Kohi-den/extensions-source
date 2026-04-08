override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val pathSegments = intent?.data?.pathSegments
    // Site yapısı değiştiği için kontrolü esnetiyoruz
    if (pathSegments != null && pathSegments.size >= 2) {
        // hentaizm6.online/anime/anime-slug -> 1. segment 'anime-slug' olur
        val item = pathSegments.last() 
        val mainIntent = Intent().apply {
            action = "eu.kanade.tachiyomi.ANIMESEARCH"
            putExtra("query", "${HentaiZM.PREFIX_SEARCH}$item")
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
