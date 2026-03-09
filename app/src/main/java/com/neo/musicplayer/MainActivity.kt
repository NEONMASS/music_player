package com.neo.musicplayer

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// --- Aesthetic Pastel Theme Colors ---
private val PastelLavenderLight = Color(0xFFB39DDB)
private val PastelBackgroundLight = Color(0xFFFDFBFD) 
private val PastelSurfaceLight = Color(0xFFF4EFFC)

private val PastelLavenderDark = Color(0xFFD1B3FF) 
private val PastelBackgroundDark = Color(0xFF1E1E2E) 
private val PastelSurfaceDark = Color(0xFF2A2A3C) 

@Composable
fun AestheticTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colorScheme = if (darkTheme) {
        darkColorScheme(primary = PastelLavenderDark, background = PastelBackgroundDark, surface = PastelSurfaceDark, onSurface = Color(0xFFE0E0E0))
    } else {
        lightColorScheme(primary = PastelLavenderLight, background = PastelBackgroundLight, surface = PastelSurfaceLight, onSurface = Color(0xFF4A4A4A))
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

data class LocalSong(val id: Long, val title: String, val artist: String, val albumId: Long) {
    val albumArtUri: Uri get() = Uri.parse("content://media/external/audio/albumart/$albumId")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AestheticTheme { MusicPlayerUI() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicPlayerUI() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var localSongs by remember { mutableStateOf<List<LocalSong>>(emptyList()) }
    var permissionGranted by remember { mutableStateOf(false) }

    var currentSong by remember { mutableStateOf<LocalSong?>(null) }
    var currentPremiumArtUrl by remember { mutableStateOf<String?>(null) } 
    
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableStateOf(0L) }
    var totalDuration by remember { mutableStateOf(0L) }
    var showFullScreenPlayer by remember { mutableStateOf(false) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener { override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing } })
        }
    }

    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            currentPosition = exoPlayer.currentPosition
            totalDuration = if (exoPlayer.duration > 0) exoPlayer.duration else 0L
            delay(1000L)
        }
    }

    val playSong = { song: LocalSong ->
        currentSong = song
        currentPremiumArtUrl = null 
        
        coroutineScope.launch {
            currentPremiumArtUrl = getInternetAlbumArt(song.title, song.artist)
        }

        val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)
        exoPlayer.setMediaItem(MediaItem.fromUri(contentUri))
        exoPlayer.prepare()
        exoPlayer.play()
    }

    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        permissionGranted = isGranted
        if (isGranted) { localSongs = fetchLocalMusic(context) }
    }

    LaunchedEffect(Unit) { launcher.launch(permission) }
    BackHandler(enabled = showFullScreenPlayer) { showFullScreenPlayer = false }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { 
                        if (isSearchActive) {
                            TextField(value = searchQuery, onValueChange = { searchQuery = it }, placeholder = { Text("Search web...") }, singleLine = true, colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                        } else { Text("Hybrid Player", fontWeight = FontWeight.Bold) }
                    },
                    actions = {
                        IconButton(onClick = { isSearchActive = !isSearchActive; if (!isSearchActive) searchQuery = "" }) {
                            Icon(if (isSearchActive) Icons.Default.Close else Icons.Default.Search, contentDescription = "Toggle Search", tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
            },
            bottomBar = { 
                if (currentSong != null) {
                    PlayerControlsBar(
                        currentSong = currentSong!!, internetArtUrl = currentPremiumArtUrl, isPlaying = isPlaying, currentPosition = currentPosition, totalDuration = totalDuration,
                        onPlayPause = { if (isPlaying) exoPlayer.pause() else exoPlayer.play() },
                        onNext = { val idx = localSongs.indexOf(currentSong); if (idx in 0 until localSongs.size - 1) playSong(localSongs[idx + 1]) },
                        onPrev = { val idx = localSongs.indexOf(currentSong); if (idx > 0) playSong(localSongs[idx - 1]) },
                        onBarClick = { showFullScreenPlayer = true }
                    )
                } else { IconOnlyBottomBar() }
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { paddingValues ->
            Column(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 16.dp)) {
                Spacer(modifier = Modifier.height(8.dp))
                if (!permissionGranted) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Storage permission is required.", color = MaterialTheme.colorScheme.primary) }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(localSongs) { song ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Row(
                                    modifier = Modifier.clickable { playSong(song) }.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        AsyncImage(
                                            model = song.albumArtUri, contentDescription = "Album Art", contentScale = ContentScale.Crop,
                                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                                        )
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Column {
                                            Text(song.title, fontWeight = FontWeight.SemiBold, maxLines = 1, color = if (currentSong?.id == song.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                            Text(song.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(visible = showFullScreenPlayer && currentSong != null, enter = slideInVertically(initialOffsetY = { it }), exit = slideOutVertically(targetOffsetY = { it })) {
            if (currentSong != null) {
                FullScreenPlayer(
                    song = currentSong!!, internetArtUrl = currentPremiumArtUrl, isPlaying = isPlaying, currentPosition = currentPosition, totalDuration = totalDuration,
                    onClose = { showFullScreenPlayer = false },
                    onPlayPause = { if (isPlaying) exoPlayer.pause() else exoPlayer.play() },
                    onNext = { val idx = localSongs.indexOf(currentSong); if (idx in 0 until localSongs.size - 1) playSong(localSongs[idx + 1]) },
                    onPrev = { val idx = localSongs.indexOf(currentSong); if (idx > 0) playSong(localSongs[idx - 1]) },
                    onSeek = { percentage -> val seekPosition = (percentage * totalDuration).toLong(); exoPlayer.seekTo(seekPosition); currentPosition = seekPosition }
                )
            }
        }
    }
}

@Composable
fun FullScreenPlayer(
    song: LocalSong, internetArtUrl: String?, isPlaying: Boolean, currentPosition: Long, totalDuration: Long,
    onClose: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit, onPrev: () -> Unit, onSeek: (Float) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(internetArtUrl ?: song.albumArtUri).crossfade(true).build(),
            contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().blur(radius = 60.dp).background(Color.Black.copy(alpha = 0.5f)) 
        )

        Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                IconButton(onClick = onClose) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Minimize", modifier = Modifier.size(36.dp), tint = Color.White) }
            }
            Spacer(modifier = Modifier.height(32.dp))

            Card(modifier = Modifier.fillMaxWidth().aspectRatio(1f).shadow(24.dp, RoundedCornerShape(32.dp)), shape = RoundedCornerShape(32.dp)) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(internetArtUrl ?: song.albumArtUri).crossfade(1000).build(),
                    contentDescription = "Album Art", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
                )
            }

            Spacer(modifier = Modifier.height(48.dp))
            Text(song.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
            Spacer(modifier = Modifier.height(8.dp))
            Text(song.artist, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.8f), maxLines = 1)

            Spacer(modifier = Modifier.weight(1f))

            Slider(
                value = if (totalDuration > 0) currentPosition.toFloat() / totalDuration.toFloat() else 0f, onValueChange = onSeek,
                modifier = Modifier.fillMaxWidth(), colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f))
            )

            val formatTime = { ms: Long -> val totalSeconds = ms / 1000; String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60) }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(currentPosition), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
                Text(formatTime(totalDuration), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
            }

            Spacer(modifier = Modifier.height(32.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                IconButton(onClick = onPrev) { Icon(Icons.Default.SkipPrevious, contentDescription = "Prev", modifier = Modifier.size(48.dp), tint = Color.White) }
                Box(modifier = Modifier.size(80.dp).clip(CircleShape).background(Color.White).clickable { onPlayPause() }, contentAlignment = Alignment.Center) {
                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Play/Pause", modifier = Modifier.size(48.dp), tint = Color.Black)
                }
                IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, contentDescription = "Next", modifier = Modifier.size(48.dp), tint = Color.White) }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun PlayerControlsBar(
    currentSong: LocalSong, internetArtUrl: String?, isPlaying: Boolean, currentPosition: Long, totalDuration: Long,
    onPlayPause: () -> Unit, onNext: () -> Unit, onPrev: () -> Unit, onBarClick: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().clickable { onBarClick() }, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            LinearProgressIndicator(progress = { if (totalDuration > 0) currentPosition.toFloat() / totalDuration.toFloat() else 0f }, modifier = Modifier.fillMaxWidth().height(2.dp), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(internetArtUrl ?: currentSong.albumArtUri).crossfade(true).build(),
                    contentDescription = "Album Art", contentScale = ContentScale.Crop, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(currentSong.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                    Text(currentSong.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), maxLines = 1)
                }
                Row {
                    IconButton(onClick = onPrev) { Icon(Icons.Default.SkipPrevious, contentDescription = "Prev", tint = MaterialTheme.colorScheme.primary) }
                    IconButton(onClick = onPlayPause) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Play/Pause", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp)) }
                    IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = MaterialTheme.colorScheme.primary) }
                }
            }
        }
    }
}

@Composable
fun IconOnlyBottomBar() {
    BottomAppBar(containerColor = MaterialTheme.colorScheme.surface, contentPadding = PaddingValues(horizontal = 24.dp), modifier = Modifier.fillMaxWidth(), tonalElevation = 8.dp) {
        Icon(Icons.Default.List, contentDescription = "Local Library", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
    }
}

fun fetchLocalMusic(context: Context): List<LocalSong> {
    val songs = mutableListOf<LocalSong>()
    val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.ALBUM_ID)
    
    val junkPattern = Regex("(?i)(.*\\d{8}.*|.*aud-.*|.*ptt-.*|.*wa00.*|.*record.*)")

    context.contentResolver.query(uri, projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} ASC")?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
        val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        
        while (cursor.moveToNext()) {
            val title = cursor.getString(titleCol) ?: "Unknown"
            val path = cursor.getString(dataCol) ?: ""
            
            if (title.matches(junkPattern) || path.matches(junkPattern)) continue 

            val artist = cursor.getString(artistCol) ?: "Unknown Artist"
            songs.add(LocalSong(cursor.getLong(idCol), title, artist, cursor.getLong(albumIdCol)))
        }
    }
    return songs
}

suspend fun getInternetAlbumArt(title: String, artist: String): String? = withContext(Dispatchers.IO) {
    try {
        // 1. Clean the Metadata
        // Remove common file extensions that ruin search results
        var cleanTitle = title.replace(".mp3", "", ignoreCase = true)
            .replace(".m4a", "", ignoreCase = true)
            .replace(".wav", "", ignoreCase = true)
            .trim()
            
        // Ignore the artist if the Android OS flagged it as unknown
        var cleanArtist = artist
        if (cleanArtist.contains("unknown", ignoreCase = true) || cleanArtist.contains("<unknown>")) {
            cleanArtist = "" 
        }

        // Build a smart search query
        val searchTerm = if (cleanArtist.isNotEmpty()) "$cleanTitle $cleanArtist" else cleanTitle
        val query = URLEncoder.encode(searchTerm, "UTF-8")
        
        val urlString = "https://itunes.apple.com/search?term=$query&media=music&entity=song&limit=1"
        println("MusicPlayer API: Searching -> $urlString") // Prints to Logcat so you can debug it
        
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        
        // 2. Pretend to be a browser so Apple doesn't block the request
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        connection.connectTimeout = 5000
        connection.readTimeout = 5000

        // 3. Process the response
        if (connection.responseCode == 200) {
            val response = connection.inputStream.bufferedReader().readText()
            
            // Extract the 100x100 artwork URL
            val artworkIndex = response.indexOf("\"artworkUrl100\":\"")
            if (artworkIndex != -1) {
                val start = artworkIndex + 17
                val end = response.indexOf("\"", start)
                val smallUrl = response.substring(start, end)
                
                // Upgrade it to crisp 600x600 resolution
                return@withContext smallUrl.replace("100x100bb", "600x600bb") 
            }
        } else {
            println("MusicPlayer API: Request failed with code ${connection.responseCode}")
        }
    } catch (e: Exception) { 
        println("MusicPlayer API Error: ${e.message}")
    }
    null
}