package com.neo.musicplayer

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.media3.common.*
import androidx.media3.datasource.*
import androidx.media3.exoplayer.*
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class LocalSong(
    val id: Long,
    val title: String,
    val artist: String,
    val albumId: Long,
    val webUrl: String,
    val customArtUrl: String
) {
    val uri: Uri
        get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
}

data class WebData(
    val id: String,
    val title: String,
    val artist: String,
    val artUrl: String,
    val lyrics: String
)

@Composable
fun AestheticTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        content()
    }
}

@Composable
fun FallbackIcon() {
    Box(modifier = Modifier.size(48.dp)) {
        Icon(Icons.Filled.MusicNote, contentDescription = "Fallback Icon")
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MusicPlayerUI() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(song: LocalSong, onAdd: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        SubcomposeAsyncImage(model = ImageRequest.Builder(LocalContext.current)
            .data(song.customArtUrl)
            .crossfade(true)
            .build(), contentDescription = null,
            modifier = Modifier.size(64.dp).clip(CircleShape))
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = song.title, fontWeight = FontWeight.Bold, overflow = TextOverflow.Ellipsis)
            Text(text = song.artist, style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = onAdd) {
            Icon(Icons.Filled.Add, contentDescription = "Add")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicPlayerUI() {
    var localSongs by remember { mutableStateOf(emptyList<LocalSong>()) }
    var searchRes by remember { mutableStateOf(emptyList<LocalSong>()) }
    var isSearch by remember { mutableStateOf(false) }
    var isSearching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(0) }
    var isAlbumMode by remember { mutableStateOf(false) }
    var curSong by remember { mutableStateOf<LocalSong?>(null) }
    var webMeta by remember { mutableStateOf<WebData?>(null) }
    var queue by remember { mutableStateOf(listOf<LocalSong>()) }
    var isPlaying by remember { mutableStateOf(false) }
    var pos by remember { mutableStateOf(0L) }
    var dur by remember { mutableStateOf(0L) }
    var isLive by remember { mutableStateOf(false) }
    var showFS by remember { mutableStateOf(false) }
    val apCtx = remember { LocalContext.current }
    var apIdx by remember { mutableStateOf(0) }
    var actionSong by remember { mutableStateOf<LocalSong?>(null) }
    var viewFavs by remember { mutableStateOf(false) }
    var showNewP by remember { mutableStateOf(false) }

    // Database flows for memories, favs, recents, playlists, prefs and languages would go here.

    // ExoPlayer initialization and permission handling would go here.

    // Player functions: playList, playWeb, handleNext, handlePrev, handleAddQueue

    // Player state listeners and LaunchedEffect for position updates would go here.

    // Search logic LaunchedEffect would go here.

    Scaffold(
        topBar = { /* Top bar content */ },
        bottomBar = { /* Bottom bar content, mini player */ },
        content = { /* Main content area, permission checks and tabs */ }
    )
}

fun generateDummyId(): Int = (0..Int.MAX_VALUE).random().hashCode()

fun getLocalMusic(context: Context): List<LocalSong> {
    // Implementation of querying MediaStore for local music.
}

suspend fun fetchHttp(url: String): String? {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 10000
    return connection.inputStream.bufferedReader().use { it.readText() }
}

fun getAutoLang(): String {
    // Implementation for detecting country and region for language.
}

suspend fun fetchWebSearch(query: String): List<WebData> {
    // Async implementation for fetching data from multiple sources (Saavn, Archive.org, Piped).
}

suspend fun fetchAudioUrl(): String? {
    // Implementation to extract audio streams.
}

suspend fun fetchMetadata(): WebData? {
    // Implementation for metadata fetching from iTunes and Saavn.
}
