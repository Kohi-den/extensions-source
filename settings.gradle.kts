apply(from = "repositories.gradle.kts")

include(":core")

// Load all modules under /lib
File(rootDir, "lib").eachDir { include("lib:${it.name}") }

// Load all modules under /lib-multisrc
File(rootDir, "lib-multisrc").eachDir { include("lib-multisrc:${it.name}") }

if (System.getenv("CI") != "true") {
    // Local development (full project build)

    /**
     * Add or remove modules to load as needed for local development here.
     */
    loadAllIndividualExtensions()
    // loadIndividualExtension("all", "jellyfin")
} else {
    // Running in CI (GitHub Actions)

    val chunkSize = System.getenv("CI_CHUNK_SIZE")?.toIntOrNull() ?: Int.MAX_VALUE
    val chunk = System.getenv("CI_CHUNK_NUM")?.toIntOrNull() ?: 0

    // Loads individual extensions
    File(rootDir, "src").getChunk(chunk, chunkSize)?.forEach {
        loadIndividualExtension(it.parentFile.name, it.name)
    }
}

fun loadAllIndividualExtensions() {
    File(rootDir, "src").eachDir { dir ->
        dir.eachDir { subdir ->
            loadIndividualExtension(dir.name, subdir.name)
        }
    }
}
fun loadIndividualExtension(lang: String, name: String) {
    include("src:$lang:$name")
}

fun File.getChunk(chunk: Int, chunkSize: Int): List<File>? {
    return listFiles()
        // Lang folder
        ?.filter { it.isDirectory }
        // Extension subfolders
        ?.mapNotNull { dir -> dir.listFiles()?.filter { it.isDirectory } }
        ?.flatten()
        ?.sortedBy { it.name }
        ?.chunked(chunkSize)
        ?.get(chunk)
}

fun File.eachDir(block: (File) -> Unit) {
    val files = listFiles() ?: return
    for (file in files) {
        if (file.isDirectory && file.name != ".gradle" && file.name != "build") {
            block(file)
        }
    }
}
