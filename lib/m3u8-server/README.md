# M3U8 Server Library

A local HTTP server library for processing M3U8 files with automatic byte detection and header preservation.

## Features

- **Real HTTP Server**: Runs a NanoHTTPD server on localhost to process M3U8 files
- **Random Port Assignment**: Automatically selects an available port to avoid conflicts
- **Automatic Detection**: Automatically detects and skips fake headers in video segments
- **Header Preservation**: Preserves and forwards HTTP headers from original requests
- **M3U8 Processing**: Modifies M3U8 playlists to redirect segments through local server
- **Segment Processing**: Serves video segments with automatic header detection
- **Health Check**: Provides health check endpoint

## How It Works

1. **Server Startup**: The NanoHTTPD server starts on a random port (or specified port) to avoid conflicts
2. **Header Extraction**: HTTP headers from incoming requests are extracted and preserved
3. **M3U8 Processing**: When an M3U8 URL is processed, it fetches the original content with preserved headers and modifies segment URLs to point to the local server
4. **Automatic Detection**: When serving segments, the server automatically detects fake headers (JPEG, PNG, GIF) and skips them to reveal the actual video content
5. **Header Forwarding**: Headers are forwarded to both M3U8 and segment requests for compatibility

## Endpoints

### GET /m3u8?url=<url>
Processes an M3U8 file and returns modified content with local segment URLs.

**Parameters:**
- `url`: URL of the M3U8 file to process

**Headers:** All HTTP headers from the request are preserved and forwarded

**Response:** Modified M3U8 content with local segment URLs

### GET /segment?url=<url>
Serves a video segment with automatic header detection.

**Parameters:**
- `url`: URL of the video segment

**Headers:** All HTTP headers from the request are preserved and forwarded

**Response:** Video segment data with fake headers automatically removed

### GET /health
Health check endpoint.

**Response:** Server status message

## Integration

The library provides `M3u8Integration` class for easy integration:

```kotlin
// Initialize integration
val integration = M3u8Integration(client)

// Process video list
val processedVideos = integration.processVideoList(originalVideos)

// Get server info
println(integration.getServerInfo())

// Stop server when done
integration.stopServer()
```

## Automatic Detection

The server automatically detects various fake header formats:

- **JPEG Headers**: Detects JPEG magic bytes and finds video content after them
- **PNG Headers**: Detects PNG magic bytes and finds video content after them
- **GIF Headers**: Detects GIF magic bytes and finds video content after them
- **MPEG-TS**: Detects MPEG-TS sync bytes (0x47) for valid transport streams
- **MP4**: Detects "ftyp" atom for MP4 files
- **AVI**: Detects "RIFF" and "AVI" headers for AVI files

## Usage Example

```kotlin
// Create server manager
val manager = M3u8ServerManager()

// Start server with random port (recommended)
manager.startServer() // Uses random port by default

// Or start server on specific port
manager.startServer(8080)

// Process M3U8 URL
val originalUrl = "https://example.com/playlist.m3u8"
val processedUrl = manager.processM3u8Url(originalUrl)

println("Original: $originalUrl")
println("Processed: $processedUrl")
println(manager.getServerInfo())

// Stop server
manager.stopServer()
```

## Dependencies

- **OkHttp**: HTTP client for fetching content
- **NanoHTTPD**: Lightweight HTTP server for Android

## Architecture

```
Extension/App
    ↓
M3u8Integration
    ↓
M3u8ServerManager
    ↓
M3u8HttpServer (NanoHTTPD)
    ↓
AutoDetector
```

The library uses NanoHTTPD to provide a real HTTP server that's compatible with Android and can handle M3U8 processing requests with header preservation. 