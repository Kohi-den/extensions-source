# Google Drive Player Extractor

A library for extracting video player URLs from Google Drive. Unlike the standard Google Drive extractor that attempts to get download URLs, this extractor focuses on extracting streaming player URLs with multiple quality options.

## Description

This extractor uses a WebView to intercept Google Drive's player API requests and extracts streaming URLs with various quality options. It supports both progressive (video + audio combined) and adaptive (separated video and audio tracks) streaming formats.

## Key Differences

- **Standard Google Drive Extractor**: Attempts to get direct download URLs
- **Google Drive Player Extractor**: Extracts streaming player URLs with quality options

## Installation

Add the dependency to your extension's `build.gradle`:

```gradle
dependencies {
    implementation(project(":lib:googledriveplayer-extractor"))
}
```

## Usage

### Basic Usage

```kotlin
import eu.kanade.tachiyomi.lib.googledriveplayerextractor.GoogleDrivePlayerExtractor

// Initialize the extractor
val extractor = GoogleDrivePlayerExtractor(client, headers)

// Extract videos from a Google Drive URL
val videos = extractor.videosFromUrl("https://drive.google.com/file/d/VIDEO_ID/view")

// Use the videos
videos.forEach { video ->
    println("Quality: ${video.quality}")
    println("URL: ${video.url}")
}
```

### Integration Example

```kotlin
class MyExtension : AnimeStream(...) {
    private val googleDrivePlayerExtractor by lazy { 
        GoogleDrivePlayerExtractor(client, headers) 
    }

    override fun getVideoList(url: String, name: String): List<Video> {
        return when {
            "drive.google.com" in url -> {
                googleDrivePlayerExtractor.videosFromUrl(url)
            }
            else -> emptyList()
        }
    }
}
```

## Features

- **Multiple Quality Options**: Extracts videos in various qualities (240p, 360p, 480p, 720p, 1080p)
- **Progressive Transcodes**: Video files with audio included
- **Adaptive Transcodes**: Separate video and audio tracks for better quality
- **Automatic Quality Detection**: Maps video itags to quality labels
- **Cookie Management**: Automatically handles cookies from WebView

## How It Works

1. **WebView Loading**: Loads the Google Drive URL in a WebView
2. **Request Interception**: Intercepts the playback API request
3. **Cookie Extraction**: Extracts cookies from the WebView
4. **API Request**: Makes a GET request to the playback API with cookies
5. **Video Extraction**: Parses the response and extracts video URLs with quality information

## Returned Video Format

The extractor returns `Video` objects with:
- **URL**: Direct streaming URL
- **Quality**: Quality label (e.g., "360p", "720p")
- **Headers**: Required HTTP headers including cookies
- **Audio Tracks**: For adaptive transcodes, includes separate audio tracks

## Notes

- Requires Android WebView to intercept requests
- Cookies are automatically extracted and included in video requests
- Timeout is set to 10 seconds for WebView loading
- Supports both progressive and adaptive streaming formats

