package eu.kanade.tachiyomi.animeextension.en.av1encodes

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

internal fun extractCleanTitle(raw: String): String {
    var cleaned = raw.replace(Regex("""\s*·\s*\d+\s*downloads?.*""", RegexOption.IGNORE_CASE), "")
    cleaned = cleaned.replace(Regex("""^\[[a-zA-Z0-9_\-]+]\s*"""), "")
    cleaned = cleaned.replace(Regex("""\s*\[\d{3,4}p].*""", RegexOption.IGNORE_CASE), "")
    cleaned = cleaned.replace(Regex("""\.(mkv|mp4)$""", RegexOption.IGNORE_CASE), "")
    return cleaned.trim()
}

internal fun getListImageUrl(anchor: Element, baseUrl: String): String? {
    val img = anchor.selectFirst("img")
    if (img != null) {
        val url = img.attr("abs:data-src").ifBlank { img.attr("abs:data-lazy-src") }
            .ifBlank { img.attr("abs:src") }
        if (url.isNotBlank()) return url
    }

    var bg = extractBg(anchor, baseUrl)
    if (bg != null) return bg

    for (child in anchor.allElements) {
        bg = extractBg(child, baseUrl)
        if (bg != null) return bg
    }
    return null
}

internal fun extractBg(el: Element, baseUrl: String): String? {
    val style = el.attr("style")
    if (style.contains("background", ignoreCase = true)) {
        val match = Regex("""url\(['"]?(.*?)['"]?\)""").find(style)
        if (match != null && match.groupValues[1].isNotBlank()) {
            val url = match.groupValues[1]
            return if (url.startsWith("http")) url else "$baseUrl/${url.removePrefix("/")}"
        }
    }
    return null
}

/**
 * Extracts the X-DDL-Token from the episode page's inline JavaScript.
 *
 * The token always appears as one of:
 *   'X-DDL-Token': "VALUE"   (fetch header object literal)
 *   ddl_token = "VALUE"      (variable assignment)
 *
 * Broader fallback patterns (data-token, bare "token" key, any *Token variable)
 * were removed because pattern 5 in particular matched the ?token= query param
 * found in episode download hrefs on the same page, silently returning the
 * wrong value and causing /get_ddl/ calls to fail.
 */
internal fun extractDdlToken(html: String): String? {
    val patterns = listOf(
        // 'X-DDL-Token': "VALUE"  or  "X-DDL-Token": "VALUE"
        Regex("""['"]X-DDL-Token['"]\s*:\s*['"]([A-Za-z0-9+/=_\-]+)['"]"""),
        // ddl_token = "VALUE"  /  ddltoken = "VALUE"  /  ddl-token: "VALUE"
        Regex("""ddl[_\-]?token['"\s]*[=:]\s*['"]([A-Za-z0-9+/=_\-]+)['"]""", RegexOption.IGNORE_CASE),
    )
    for (pattern in patterns) {
        val match = pattern.find(html)
        if (match != null) return match.groupValues[1]
    }
    return null
}

/**
 * Parses the /episodes/{slug}/{season}/{quality} HTML response and returns
 * a list of [EpisodeItem]s.
 *
 * Target structure:
 * ```html
 * <div class="episode-item">
 *   <a href="/episode/attack-on-titan-s01e01-1080p">
 *     <span class="episode-label">Episode 1</span>
 *     <span class="audio-badge">Dual Audio</span>
 *   </a>
 * </div>
 * ```
 *
 * Falls back to any <a> whose href contains "/episode/" if the structured
 * class is absent.
 */
internal fun parseEpisodeItems(html: String): List<EpisodeItem> {
    val doc = Jsoup.parse(html)
    val items = mutableListOf<EpisodeItem>()

    // Primary path: strict .episode-item divs
    val containers = doc.select("div.episode-item, [class~=episode-item]")
    if (containers.isNotEmpty()) {
        for (div in containers) {
            val a = div.selectFirst("a[href]") ?: continue
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: continue
            val label = div.selectFirst("span.episode-label, [class~=episode-label]")?.text()?.trim()
                ?: a.text().trim()
            val audio = div.selectFirst("span.audio-badge, [class~=audio-badge]")?.text()?.trim() ?: ""
            val num = Regex("""\d+""").find(label)?.value?.toIntOrNull() ?: items.size + 1
            items.add(EpisodeItem(num = num, label = label, audio = audio, href = href))
        }
        return items
    }

    // Fallback: any anchor pointing to an episode detail page
    for (a in doc.select("a[href*='/episode/']")) {
        val href = a.attr("href").takeIf { it.isNotBlank() } ?: continue
        val label = a.selectFirst("[class~=episode-label]")?.text()?.trim()
            ?: a.text().trim()
        val audio = a.selectFirst("[class~=audio-badge]")?.text()?.trim() ?: ""
        val num = Regex("""\d+""").find(label)?.value?.toIntOrNull() ?: items.size + 1
        items.add(EpisodeItem(num = num, label = label, audio = audio, href = href))
    }
    return items
}

/**
 * Extracts the decoded .mkv filename from a /download/…/filename.mkv?token= URL.
 * Used when episode.url is already a direct download link (Path A in getVideoList).
 *
 * Example input:
 *   /download/demon-slayer/movie/1920%20x%201080/%5BS01%5D%20Demon%20Slayer%20%5B1080p%5D.mkv?token=…
 * Returns:
 *   [S01] Demon Slayer [1080p].mkv
 */
internal fun mkvFilenameFromDownloadUrl(downloadUrl: String): String? {
    val path = downloadUrl.substringBefore("?").trimEnd('/')
    val segment = path.substringAfterLast("/")
    if (!segment.endsWith(".mkv", ignoreCase = true)) return null
    return try {
        java.net.URLDecoder.decode(segment, "UTF-8")
    } catch (_: Exception) {
        segment
    }
}

/**
 * Scans the episode page HTML for any anchor whose href contains a /download/ path
 * with a .mkv file segment, then returns the URL-decoded .mkv filename.
 *
 * The site embeds links like:
 *   /download/{slug}/{season}/{resolution}/[S01-E04] Title [1080p] [Sub].mkv?token=…
 *
 * We extract the last path segment (before the query string) and decode it,
 * giving us exactly the filename that /get_ddl/ expects — regardless of resolution.
 * If multiple resolutions are present (1080p, 720p, …) we return the first one found;
 * the API response contains all available links anyway.
 */
internal fun extractMkvFilenameFromHtml(html: String): String? {
    val doc = Jsoup.parse(html)
    // Match any anchor whose href has /download/ and ends with .mkv (before query)
    val downloadHref = doc.select("a[href*='/download/']")
        .map { it.attr("href") }
        .firstOrNull { href ->
            val path = href.substringBefore("?")
            path.endsWith(".mkv", ignoreCase = true)
        }
        ?: return null

    val path = downloadHref.substringBefore("?").trimEnd('/')
    val segment = path.substringAfterLast("/")
    return try {
        java.net.URLDecoder.decode(segment, "UTF-8")
    } catch (_: Exception) {
        segment
    }
}

/**
 * Extracts the filename segment that the /get_ddl/ API expects from an
 * episode detail page URL.
 *
 * Algorithm:
 *  1. Unescape \? → ? and \& → &
 *  2. Drop query string
 *  3. Take the last non-empty path segment
 *  4. Drop everything from '\' onward (Windows path safety)
 *  5. URL-decode the result
 */
internal fun filenameFromPageUrl(pageUrl: String): String {
    val unescaped = pageUrl.replace("\\?", "?").replace("\\&", "&")
    val path = unescaped.substringBefore("?")
    val segment = path.trimEnd('/').substringAfterLast("/")
    val noWin = segment.substringBefore("\\")
    return try {
        java.net.URLDecoder.decode(noWin, "UTF-8")
    } catch (_: Exception) {
        noWin
    }
}

internal fun buildEpisodeLabel(item: EpisodeItem, season: String): String {
    val base = if (item.label.isNotBlank()) item.label else "Episode ${item.num}"
    val audioTag = item.audio.ifBlank { null }
    val seasonPrefix = if (season != "1" && season.isNotBlank()) "Season $season " else ""
    return "$seasonPrefix$base${if (audioTag != null) " [$audioTag]" else ""}"
}
