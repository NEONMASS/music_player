package com.neo.musicplayer

import android.Manifest
import android.content.*
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.media3.common.*
import androidx.media3.datasource.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.*
import org.json.*
import java.net.*

private val PastelLavenderLight = Color(0xFFB39DDB)
private val PastelBackgroundLight = Color(0xFFFDFBFD)
private val PastelSurfaceLight = Color(0xFFF4EFFC)
private val PastelLavenderDark = Color(0xFFD1B3FF)
private val PastelBackgroundDark = Color(0xFF1E1E2E)
private val PastelSurfaceDark = Color(0xFF2A2A3C)

@Composable
fun AestheticTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colorScheme = if (darkTheme) darkColorScheme(primary = PastelLavenderDark, background = PastelBackgroundDark, surface = PastelSurfaceDark, onSurface = Color(0xFFE0E0E0)) else lightColorScheme(primary = PastelLavenderLight, background = PastelBackgroundLight, surface = PastelSurfaceLight, onSurface = Color(0xFF4A4A4A))
    MaterialTheme(colorScheme = colorScheme, content = content)
}

@Composable
fun BlueWhiteFallback(modifier: Modifier = Modifier, iconSize: Dp = 48.dp) {
    Box(modifier = modifier.background(Brush.linearGradient(listOf(Color(0xFF1976D2), Color(0xFFBBDEFB)))), contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, contentDescription = "Music", tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(iconSize)) }
}

data class LocalSong(val id: Long, val title: String, val artist: String, val albumId: Long, val webStreamUrl: String? = null, val customArtUrl: String? = null) { val albumArtUri: Uri get() = if(customArtUrl != null) Uri.parse(customArtUrl) else Uri.parse("content://media/external/audio/albumart/$albumId") }
data class InternetSongData(val id: String = "", val title: String, val artist: String, val artUrl: String, val lyrics: String? = null)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { AestheticTheme { MusicPlayerUI() } } }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MusicPlayerUI() {
    val context = LocalContext.current; val coroutineScope = rememberCoroutineScope(); val db = remember { AppDatabase.getDatabase(context).libraryDao() }
    val songMemories by db.getAllSongMemories().collectAsState(initial = emptyList())
    val memoryMap = remember(songMemories) { songMemories.associateBy { it.localMediaId } }
    
    var localSongs by remember { mutableStateOf<List<LocalSong>>(emptyList()) }
    val favoriteSongs = remember(favoriteMemories by db.getFavoriteSongs().collectAsState(initial = emptyList()), localSongs) { favoriteMemories.mapNotNull { mem -> if (mem.localMediaId >= 0) localSongs.find { it.id == mem.localMediaId } else LocalSong(mem.localMediaId, mem.fetchedTitle ?: mem.customTitle ?: "Unknown", mem.fetchedArtist ?: mem.customArtist ?: "Unknown", -1L, null, mem.fetchedArtUrl) } }
    val recentlyPlayedSongs = remember(recentlyPlayedMemories by db.getRecentlyPlayed().collectAsState(initial = emptyList()), localSongs) { recentlyPlayedMemories.mapNotNull { mem -> if (mem.localMediaId >= 0) localSongs.find { it.id == mem.localMediaId } else LocalSong(mem.localMediaId, mem.fetchedTitle ?: mem.customTitle ?: "Unknown", mem.fetchedArtist ?: mem.customArtist ?: "Unknown", -1L, null, mem.fetchedArtUrl) } }
    val customPlaylists by db.getAllPlaylists().collectAsState(initial = emptyList())
    
    var isSearchActive by remember { mutableStateOf(false) }; var searchQuery by remember { mutableStateOf("") }; var permissionGranted by remember { mutableStateOf(false) }; var currentTab by remember { mutableIntStateOf(0) } 
    var viewingLikedSongs by remember { mutableStateOf(false) }; var viewingPlaylistId by remember { mutableStateOf<Long?>(null) }; var currentPlaylistData by remember { mutableStateOf<PlaylistWithSongs?>(null) }
    LaunchedEffect(viewingPlaylistId) { viewingPlaylistId?.let { id -> currentPlaylistData = db.getPlaylistWithSongs(id) } }

    var selectedLanguage by remember { mutableStateOf("All") }; val languages = listOf("All", "Tamil", "English", "Hindi")
    var liveSearchResults by remember { mutableStateOf<List<InternetSongData>>(emptyList()) }; var isLiveSearching by remember { mutableStateOf(false) }

    LaunchedEffect(searchQuery, selectedLanguage, isSearchActive) {
        if (!isSearchActive) return@LaunchedEffect
        val queryToSearch = if (searchQuery.isBlank()) { if (selectedLanguage == "All") "Top Trending" else "$selectedLanguage Top Trending" } else { if (selectedLanguage == "All") searchQuery else "$searchQuery $selectedLanguage" }
        delay(300); isLiveSearching = true; liveSearchResults = fetchLiveSearchResults(queryToSearch, selectedLanguage); isLiveSearching = false
    }

    var currentSong by remember { mutableStateOf<LocalSong?>(null) }; var fetchedInternetData by remember { mutableStateOf<InternetSongData?>(null) } 
    var selectedSongForAction by remember { mutableStateOf<LocalSong?>(null) }; var showEditDialog by remember { mutableStateOf(false) }
    var showAddToPlaylistDialog by remember { mutableStateOf(false) }; var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var playQueue by remember { mutableStateOf<List<LocalSong>>(emptyList()) }
    var isPlaying by remember { mutableStateOf(false) }; var playbackState by remember { mutableStateOf(Player.STATE_IDLE) }
    var currentPosition by remember { mutableStateOf(0L) }; var totalDuration by remember { mutableStateOf(0L) }; var showFullScreenPlayer by remember { mutableStateOf(false) }
    var autoPlayContext by remember { mutableStateOf<List<InternetSongData>>(emptyList()) }; var autoPlayIndex by remember { mutableIntStateOf(-1) }

    val exoPlayer = remember {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory().setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)").setAllowCrossProtocolRedirects(true)
        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory)
        val audioAttributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
        ExoPlayer.Builder(context).setMediaSourceFactory(mediaSourceFactory).build().apply {
            setAudioAttributes(audioAttributes, true); setHandleAudioBecomingNoisy(true); repeatMode = Player.REPEAT_MODE_ALL 
            addListener(object : Player.Listener { 
                override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
                override fun onPlaybackStateChanged(state: Int) { 
                    playbackState = state 
                    if (state == Player.STATE_ENDED) { if (autoPlayContext.isNotEmpty() && autoPlayIndex >= 0 && autoPlayIndex < autoPlayContext.size - 1) { autoPlayIndex++; playWebSong(autoPlayContext[autoPlayIndex]) } }
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { val index = currentMediaItemIndex; if (index in playQueue.indices) currentSong = playQueue[index] }
                override fun onPlayerError(error: PlaybackException) { Toast.makeText(context, "Playback Error: ${error.message}", Toast.LENGTH_LONG).show() }
            })
        }
    }
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    val playSongList = { song: LocalSong, sourceList: List<LocalSong> ->
        autoPlayContext = emptyList(); autoPlayIndex = -1 
        val idx = sourceList.indexOf(song); playQueue = sourceList
        val mediaItems = sourceList.map { s -> if (s.webStreamUrl != null) MediaItem.Builder().setUri(s.webStreamUrl).setMediaId(s.id.toString()).build() else MediaItem.Builder().setUri(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, s.id)).setMediaId(s.id.toString()).build() }
        exoPlayer.setMediaItems(mediaItems); if (idx >= 0) exoPlayer.seekTo(idx, C.TIME_UNSET); exoPlayer.prepare(); exoPlayer.play()
    }

    val playWebSong: (InternetSongData) -> Unit = { webSongData ->
        coroutineScope.launch {
            if (webSongData.id.startsWith("ia:")) {
                Toast.makeText(context, "Fetching Anime Album...", Toast.LENGTH_SHORT).show()
                val identifier = webSongData.id.removePrefix("ia:")
                val metaConn = URL("https://archive.org/metadata/$identifier").openConnection() as HttpURLConnection
                metaConn.setRequestProperty("User-Agent", "Mozilla/5.0")
                if (metaConn.responseCode == 200) {
                    val metaJson = JSONObject(metaConn.inputStream.bufferedReader().readText())
                    val files = metaJson.optJSONArray("files")
                    val iaPlaylist = mutableListOf<LocalSong>()
                    if (files != null) {
                        for (i in 0 until files.length()) {
                            val f = files.getJSONObject(i)
                            val format = f.optString("format", "").lowercase(); val name = f.optString("name", "")
                            if (format.contains("mp3") || format.contains("flac") || name.endsWith(".mp3") || name.endsWith(".m4a") || name.endsWith(".ogg")) {
                                val streamUrl = "https://archive.org/download/$identifier/${Uri.encode(name)}"
                                val trackTitle = f.optString("title").takeIf { it.isNotBlank() } ?: name.substringBeforeLast(".")
                                val trackArtist = f.optString("creator").takeIf { it.isNotBlank() } ?: webSongData.artist
                                var dummyId = -(kotlin.math.abs((trackTitle + trackArtist).hashCode().toLong())); if(dummyId == 0L) dummyId = -1L
                                val s = LocalSong(dummyId, trackTitle, trackArtist, -1L, streamUrl, webSongData.artUrl)
                                iaPlaylist.add(s)
                                db.saveSongMemory(SongEntity(dummyId, trackTitle, trackArtist, trackTitle, trackArtist, webSongData.artUrl, null, memoryMap[dummyId]?.isFavorite ?: false, System.currentTimeMillis()))
                            }
                        }
                    }
                    if (iaPlaylist.isNotEmpty()) { fetchedInternetData = webSongData; playSongList(iaPlaylist.first(), iaPlaylist); showFullScreenPlayer = true; return@launch }
                }
                Toast.makeText(context, "No audio found in this archive.", Toast.LENGTH_SHORT).show(); return@launch
            }

            val normalizedWebTitle = webSongData.title.lowercase().replace(Regex("[^a-z0-9]"), "")
            val localMatch = localSongs.find { val normLocal = it.title.lowercase().replace(Regex("[^a-z0-9]"), ""); normLocal == normalizedWebTitle || normLocal.contains(normalizedWebTitle) || normalizedWebTitle.contains(normLocal) }
            if (localMatch != null) { Toast.makeText(context, "Playing offline file.", Toast.LENGTH_SHORT).show(); autoPlayContext = emptyList(); val idx = localSongs.indexOf(localMatch); playQueue = localSongs; exoPlayer.setMediaItems(localSongs.map { MediaItem.Builder().setUri(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it.id)).setMediaId(it.id.toString()).build() }); if(idx>=0) exoPlayer.seekTo(idx, C.TIME_UNSET); exoPlayer.prepare(); exoPlayer.play(); showFullScreenPlayer = true; return@launch }
            
            Toast.makeText(context, "Extracting audio...", Toast.LENGTH_SHORT).show()
            val streamUrl = fetchAudioStreamUrl(webSongData.title, webSongData.artist, webSongData.id)
            if (streamUrl != null) {
                var dummyId = -(kotlin.math.abs((webSongData.title + webSongData.artist).hashCode().toLong())); if(dummyId == 0L) dummyId = -1L
                val dummySong = LocalSong(dummyId, webSongData.title, webSongData.artist, -1L, streamUrl, webSongData.artUrl)
                db.saveSongMemory(SongEntity(localMediaId = dummyId, customTitle = webSongData.title, customArtist = webSongData.artist, fetchedTitle = webSongData.title, fetchedArtist = webSongData.artist, fetchedArtUrl = webSongData.artUrl, fetchedLyrics = null, isFavorite = memoryMap[dummyId]?.isFavorite ?: false, lastPlayedAt = System.currentTimeMillis()))
                fetchedInternetData = webSongData; playQueue = listOf(dummySong)
                exoPlayer.setMediaItem(MediaItem.Builder().setUri(streamUrl).setMediaId(dummyId.toString()).build())
                exoPlayer.prepare(); exoPlayer.play(); showFullScreenPlayer = true
            } else { 
                Toast.makeText(context, "Failed to extract stream.", Toast.LENGTH_SHORT).show() 
                if (autoPlayContext.isNotEmpty() && autoPlayIndex >= 0 && autoPlayIndex < autoPlayContext.size - 1) { autoPlayIndex++; playWebSong(autoPlayContext[autoPlayIndex]) }
            }
        }
    }

    LaunchedEffect(currentSong) {
        val song = currentSong ?: return@LaunchedEffect
        if (song.id < 0) return@LaunchedEffect 
        val memory = db.getSongMemory(song.id)
        val displayTitle = memory?.customTitle?.takeIf { it.isNotBlank() } ?: memory?.fetchedTitle?.takeIf { it.isNotBlank() } ?: song.title
        val displayArtist = memory?.customArtist?.takeIf { it.isNotBlank() } ?: memory?.fetchedArtist?.takeIf { it.isNotBlank() } ?: song.artist
        fetchedInternetData = InternetSongData("", displayTitle, displayArtist, memory?.fetchedArtUrl ?: "", lyrics = memory?.fetchedLyrics)
        if (memory != null) db.updateLastPlayed(song.id, System.currentTimeMillis()) else db.saveSongMemory(SongEntity(localMediaId = song.id, customTitle = null, customArtist = null, fetchedTitle = null, fetchedArtist = null, fetchedArtUrl = null, fetchedLyrics = null, isFavorite = false, lastPlayedAt = System.currentTimeMillis()))
        if (memory?.fetchedArtUrl == null) { val result = fetchMultiSourceMetadata(displayTitle, displayArtist); if (result != null) { fetchedInternetData = result; db.saveSongMemory(SongEntity(localMediaId = song.id, customTitle = memory?.customTitle, customArtist = memory?.customArtist, fetchedTitle = result.title, fetchedArtist = result.artist, fetchedArtUrl = result.artUrl, fetchedLyrics = result.lyrics, isFavorite = memory?.isFavorite ?: false, lastPlayedAt = System.currentTimeMillis())) } }
    }

    val toggleFavorite = { coroutineScope.launch { currentSong?.let { song -> val memory = db.getSongMemory(song.id); db.updateFavoriteStatus(song.id, !(memory?.isFavorite ?: false)) } } }
    LaunchedEffect(isPlaying) { while (isPlaying) { currentPosition = exoPlayer.currentPosition; totalDuration = if (exoPlayer.duration > 0) exoPlayer.duration else 0L; delay(1000L) } }
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted -> permissionGranted = isGranted; if (isGranted) localSongs = fetchLocalMusic(context) }
    LaunchedEffect(Unit) { launcher.launch(permission) }
    BackHandler(enabled = showFullScreenPlayer || viewingLikedSongs || viewingPlaylistId != null) { if (showFullScreenPlayer) showFullScreenPlayer = false else if (viewingLikedSongs) viewingLikedSongs = false else if (viewingPlaylistId != null) viewingPlaylistId = null }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = { TopAppBar(title = { if (isSearchActive) { TextField(value = searchQuery, onValueChange = { searchQuery = it }, placeholder = { Text("Search any song...") }, singleLine = true, colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent)) } else { Text(text = when(currentTab) { 0 -> "Dashboard"; 1 -> "Discover"; 2 -> "My Library"; else -> "All Songs" }, fontWeight = FontWeight.Bold) } }, actions = { IconButton(onClick = { isSearchActive = !isSearchActive; if (!isSearchActive) searchQuery = "" }) { Icon(if (isSearchActive) Icons.Default.Close else Icons.Default.Search, contentDescription = "Toggle Search", tint = MaterialTheme.colorScheme.primary) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) },
            bottomBar = { 
                Column {
                    if (currentSong != null) PlayerControlsBar(currentSong = currentSong!!, internetData = fetchedInternetData, isPlaying = isPlaying, currentPosition = currentPosition, totalDuration = totalDuration, onPlayPause = { if (isPlaying) exoPlayer.pause() else exoPlayer.play() }, onNext = { exoPlayer.seekToNextMediaItem() }, onPrev = { exoPlayer.seekToPreviousMediaItem() }, onBarClick = { showFullScreenPlayer = true })
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 8.dp) {
                        NavigationBarItem(icon = { Icon(Icons.Default.Home, contentDescription = "Home") }, label = { Text("Home", fontSize = 10.sp) }, selected = currentTab == 0 && !isSearchActive, onClick = { currentTab = 0; isSearchActive = false; viewingLikedSongs = false; viewingPlaylistId = null })
                        NavigationBarItem(icon = { Icon(Icons.Default.Search, contentDescription = "Search") }, label = { Text("Search", fontSize = 10.sp) }, selected = isSearchActive, onClick = { isSearchActive = true; currentTab = 1 })
                        NavigationBarItem(icon = { Icon(Icons.Default.QueueMusic, contentDescription = "Library") }, label = { Text("Playlists", fontSize = 10.sp) }, selected = currentTab == 2 && !isSearchActive, onClick = { currentTab = 2; isSearchActive = false })
                        NavigationBarItem(icon = { Icon(Icons.Default.Folder, contentDescription = "Offline") }, label = { Text("Tracks", fontSize = 10.sp) }, selected = currentTab == 3 && !isSearchActive, onClick = { currentTab = 3; isSearchActive = false; viewingLikedSongs = false; viewingPlaylistId = null })
                    }
                }
            }, containerColor = MaterialTheme.colorScheme.background
        ) { paddingValues ->
            Column(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 16.dp)) {
                if (!permissionGranted) { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Storage permission is required.", color = MaterialTheme.colorScheme.primary) }
                } else if (isSearchActive) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) { items(languages) { lang -> FilterChip(selected = selectedLanguage == lang, onClick = { selectedLanguage = lang }, label = { Text(lang) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))) } }
                        if (isLiveSearching) { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        } else if (liveSearchResults.isEmpty()) { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No tracks found.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) }
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(liveSearchResults) { internetSong ->
                                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                        Row(modifier = Modifier.combinedClickable(
                                            onClick = { autoPlayContext = liveSearchResults; autoPlayIndex = liveSearchResults.indexOf(internetSong); playWebSong(internetSong) },
                                            onLongClick = { var dummyId = -(kotlin.math.abs((internetSong.title + internetSong.artist).hashCode().toLong())); if(dummyId == 0L) dummyId = -1L; selectedSongForAction = LocalSong(dummyId, internetSong.title, internetSong.artist, -1L, null, internetSong.artUrl); fetchedInternetData = internetSong }
                                        ).padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            SubcomposeAsyncImage(model = internetSong.artUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)), error = { BlueWhiteFallback() }, loading = { BlueWhiteFallback() })
                                            Spacer(modifier = Modifier.width(16.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(internetSong.title, fontWeight = FontWeight.SemiBold, maxLines = 1, color = MaterialTheme.colorScheme.onSurface)
                                                Text(internetSong.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                                            }
                                            Icon(Icons.Default.CloudDownload, contentDescription = "Stream", tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), modifier = Modifier.size(24.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    when (currentTab) {
                        0 -> {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                item { Spacer(modifier = Modifier.height(8.dp)) }
                                if (recentlyPlayedSongs.isNotEmpty()) {
                                    item { Text("Recently Played", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp)) }
                                    item {
                                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 24.dp)) {
                                            items(recentlyPlayedSongs) { song ->
                                                val mem = memoryMap[song.id]; val displayTitle = mem?.customTitle?.takeIf { it.isNotBlank() } ?: mem?.fetchedTitle?.takeIf { it.isNotBlank() } ?: song.title; val displayArt = mem?.fetchedArtUrl?.takeIf { it.isNotBlank() } ?: song.albumArtUri.toString()
                                                Column(modifier = Modifier.width(120.dp).clickable { if (song.id < 0) { autoPlayContext = recentlyPlayedSongs.filter { it.id < 0 }.map { InternetSongData(it.id.toString(), it.title, it.artist, it.customArtUrl ?: "") }; autoPlayIndex = autoPlayContext.indexOfFirst { it.title == displayTitle }; playWebSong(InternetSongData("", displayTitle, song.artist, displayArt)) } else playSongList(song, recentlyPlayedSongs.filter{ it.id >= 0 }) }) {
                                                    SubcomposeAsyncImage(model = displayArt, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(120.dp).clip(RoundedCornerShape(12.dp)), error = { BlueWhiteFallback() })
                                                    Spacer(modifier = Modifier.height(6.dp)); Text(displayTitle, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        2 -> {
                            Column(modifier = Modifier.fillMaxSize()) {
                                if (viewingLikedSongs) {
                                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = { viewingLikedSongs = false }) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }; Text("Liked Songs", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
                                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                                        items(favoriteSongs) { song ->
                                            val mem = memoryMap[song.id]; val displayTitle = mem?.customTitle?.takeIf { it.isNotBlank() } ?: mem?.fetchedTitle?.takeIf { it.isNotBlank() } ?: song.title; val displayArtist = mem?.customArtist?.takeIf { it.isNotBlank() } ?: mem?.fetchedArtist?.takeIf { it.isNotBlank() } ?: song.artist; val displayArt = mem?.fetchedArtUrl?.takeIf { it.isNotBlank() } ?: song.albumArtUri.toString()
                                            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Row(modifier = Modifier.combinedClickable(onClick = { if (song.id < 0) { autoPlayContext = favoriteSongs.filter{it.id<0}.map{InternetSongData("",it.title,it.artist,it.customArtUrl?:"")}; autoPlayIndex = autoPlayContext.indexOfFirst{it.title==displayTitle}; playWebSong(InternetSongData("", displayTitle, displayArtist, displayArt)) } else playSongList(song, favoriteSongs.filter{it.id>=0}) }, onLongClick = { selectedSongForAction = song }).padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { SubcomposeAsyncImage(model = displayArt, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)), error = { BlueWhiteFallback() }, loading = { BlueWhiteFallback() }); Spacer(modifier = Modifier.width(16.dp)); Column(modifier = Modifier.weight(1f)) { Text(displayTitle, fontWeight = FontWeight.SemiBold, maxLines = 1, color = if (currentSong?.id == song.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface); Text(displayArtist, style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)) } } }
                                        }
                                    }
                                } else if (viewingPlaylistId != null && currentPlaylistData != null) {
                                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = { viewingPlaylistId = null }) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }; Text(currentPlaylistData!!.playlist.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
                                    val playlistSongs = remember(currentPlaylistData, localSongs) { currentPlaylistData!!.songs.mapNotNull { mem -> if (mem.localMediaId >= 0) localSongs.find { it.id == mem.localMediaId } else LocalSong(mem.localMediaId, mem.fetchedTitle ?: "Unknown", mem.fetchedArtist ?: "Unknown", -1L, null, mem.fetchedArtUrl) } }
                                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                                        items(playlistSongs) { song ->
                                            val mem = memoryMap[song.id]; val displayTitle = mem?.customTitle?.takeIf { it.isNotBlank() } ?: mem?.fetchedTitle?.takeIf { it.isNotBlank() } ?: song.title; val displayArtist = mem?.customArtist?.takeIf { it.isNotBlank() } ?: mem?.fetchedArtist?.takeIf { it.isNotBlank() } ?: song.artist; val displayArt = mem?.fetchedArtUrl?.takeIf { it.isNotBlank() } ?: song.albumArtUri.toString()
                                            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Row(modifier = Modifier.combinedClickable(onClick = { if (song.id < 0) { autoPlayContext = playlistSongs.filter{it.id<0}.map{InternetSongData("",it.title,it.artist,it.customArtUrl?:"")}; autoPlayIndex = autoPlayContext.indexOfFirst{it.title==displayTitle}; playWebSong(InternetSongData("", displayTitle, displayArtist, displayArt)) } else playSongList(song, playlistSongs.filter{it.id>=0}) }, onLongClick = { selectedSongForAction = song }).padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { SubcomposeAsyncImage(model = displayArt, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)), error = { BlueWhiteFallback() }, loading = { BlueWhiteFallback() }); Spacer(modifier = Modifier.width(16.dp)); Column(modifier = Modifier.weight(1f)) { Text(displayTitle, fontWeight = FontWeight.SemiBold, maxLines = 1, color = if (currentSong?.id == song.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface); Text(displayArtist, style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)) } } }
                                        }
                                    }
                                } else {
                                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Your Library", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Button(onClick = { showCreatePlaylistDialog = true }) { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(modifier = Modifier.width(4.dp)); Text("New") } }
                                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                                        item { Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { viewingLikedSongs = true }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Box(modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(Brush.linearGradient(listOf(Color(0xFFFF4081), Color(0xFFE91E63)))), contentAlignment = Alignment.Center) { Icon(Icons.Default.Favorite, contentDescription = null, tint = Color.White) }; Spacer(modifier = Modifier.width(16.dp)); Column { Text("Liked Songs", fontWeight = FontWeight.Bold, fontSize = 18.sp); Text("${favoriteSongs.size} tracks", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)) } } } }
                                        items(customPlaylists) { playlist -> Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { viewingPlaylistId = playlist.playlistId }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Box(modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) { Icon(Icons.Default.QueueMusic, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }; Spacer(modifier = Modifier.width(16.dp)); Column { Text(playlist.name, fontWeight = FontWeight.Bold, fontSize = 18.sp); Text("Custom Playlist", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)) } } } }
                                    }
                                }
                            }
                        }
                        3 -> { 
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(localSongs) { song ->
                                    val mem = memoryMap[song.id]; val displayTitle = mem?.customTitle?.takeIf { it.isNotBlank() } ?: mem?.fetchedTitle?.takeIf { it.isNotBlank() } ?: song.title; val displayArtist = mem?.customArtist?.takeIf { it.isNotBlank() } ?: mem?.fetchedArtist?.takeIf { it.isNotBlank() } ?: song.artist; val displayArt = mem?.fetchedArtUrl?.takeIf { it.isNotBlank() } ?: song.albumArtUri.toString()
                                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Row(modifier = Modifier.combinedClickable(onClick = { playSongList(song, localSongs) }, onLongClick = { selectedSongForAction = song }).padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { SubcomposeAsyncImage(model = displayArt, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)), error = { BlueWhiteFallback() }, loading = { BlueWhiteFallback() }); Spacer(modifier = Modifier.width(16.dp)); Column(modifier = Modifier.weight(1f)) { Text(displayTitle, fontWeight = FontWeight.SemiBold, maxLines = 1, color = if (currentSong?.id == song.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface); Text(displayArtist, style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)) } } }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        AnimatedVisibility(visible = showFullScreenPlayer && currentSong != null, enter = slideInVertically(initialOffsetY = { it }), exit = slideOutVertically(targetOffsetY = { it })) {
            if (currentSong != null) {
                val isFav = memoryMap[currentSong?.id]?.isFavorite == true
                FullScreenPlayer(song = currentSong!!, internetData = fetchedInternetData, isPlaying = isPlaying, currentPosition = currentPosition, totalDuration = totalDuration, isFavorite = isFav, queueList = playQueue, memoryMap = memoryMap, playSong = { qSong -> if(qSong.id<0) { playWebSong(InternetSongData("", qSong.title, qSong.artist, qSong.customArtUrl?:"")) } else playSongList(qSong, playQueue) }, onRemoveFromQueue = { idx -> val newQ = playQueue.toMutableList().apply { removeAt(idx) }; playQueue = newQ; exoPlayer.removeMediaItem(idx) }, onClose = { showFullScreenPlayer = false }, onPlayPause = { if (isPlaying) exoPlayer.pause() else exoPlayer.play() }, onNext = { exoPlayer.seekToNextMediaItem() }, onPrev = { exoPlayer.seekToPreviousMediaItem() }, onSeek = { percentage -> val seekPosition = (percentage * totalDuration).toLong(); exoPlayer.seekTo(seekPosition); currentPosition = seekPosition }, onToggleFavorite = { toggleFavorite() })
            }
        }

        if (selectedSongForAction != null) {
            AlertDialog(onDismissRequest = { selectedSongForAction = null }, title = { Text("Track Options") }, text = { Column { 
                TextButton(onClick = { 
                    val s = selectedSongForAction!!
                    if (s.id < 0) {
                        coroutineScope.launch {
                            Toast.makeText(context, "Fetching stream...", Toast.LENGTH_SHORT).show()
                            val streamUrl = fetchAudioStreamUrl(s.title, s.artist, fetchedInternetData?.id ?: "")
                            if (streamUrl != null) {
                                val sWithUrl = s.copy(webStreamUrl = streamUrl)
                                playQueue = playQueue + sWithUrl
                                exoPlayer.addMediaItem(MediaItem.Builder().setUri(streamUrl).setMediaId(s.id.toString()).build())
                                db.saveSongMemory(SongEntity(s.id, s.title, s.artist, s.title, s.artist, s.customArtUrl, null, false, System.currentTimeMillis()))
                                Toast.makeText(context, "Added to Queue", Toast.LENGTH_SHORT).show()
                            } else Toast.makeText(context, "Failed", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        playQueue = playQueue + s
                        exoPlayer.addMediaItem(MediaItem.Builder().setUri(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, s.id)).setMediaId(s.id.toString()).build())
                        Toast.makeText(context, "Added to Queue", Toast.LENGTH_SHORT).show()
                    }
                    selectedSongForAction = null 
                }, modifier = Modifier.fillMaxWidth()) { Text("Add to Queue") }
                TextButton(onClick = { showAddToPlaylistDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Add to Playlist") }
                if (selectedSongForAction!!.id >= 0) { TextButton(onClick = { showEditDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Edit Metadata") } } 
            } }, confirmButton = { TextButton(onClick = { selectedSongForAction = null }) { Text("Cancel") } })
        }
        if (showEditDialog && selectedSongForAction != null) {
            val targetSong = selectedSongForAction!!; val mem = memoryMap[targetSong.id]; var editTitle by remember { mutableStateOf(mem?.customTitle ?: targetSong.title) }; var editArtist by remember { mutableStateOf(mem?.customArtist ?: targetSong.artist) }
            AlertDialog(onDismissRequest = { showEditDialog = false; selectedSongForAction = null }, title = { Text("Fix Track Metadata", fontWeight = FontWeight.Bold) }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { OutlinedTextField(value = editTitle, onValueChange = { editTitle = it }, label = { Text("Song Title") }, singleLine = true, modifier = Modifier.fillMaxWidth()); OutlinedTextField(value = editArtist, onValueChange = { editArtist = it }, label = { Text("Artist") }, singleLine = true, modifier = Modifier.fillMaxWidth()) } }, confirmButton = { Button(onClick = { coroutineScope.launch { db.saveSongMemory(SongEntity(localMediaId = targetSong.id, customTitle = editTitle.trim().takeIf { it.isNotEmpty() }, customArtist = editArtist.trim().takeIf { it.isNotEmpty() }, fetchedTitle = null, fetchedArtist = null, fetchedArtUrl = null, fetchedLyrics = null, isFavorite = mem?.isFavorite ?: false, lastPlayedAt = mem?.lastPlayedAt ?: 0L)); showEditDialog = false; selectedSongForAction = null; playSongList(targetSong, localSongs) } }) { Text("Save") } }, dismissButton = { TextButton(onClick = { showEditDialog = false; selectedSongForAction = null }) { Text("Cancel") } })
        }
        if (showCreatePlaylistDialog) {
            var newPlaylistName by remember { mutableStateOf("") }
            AlertDialog(onDismissRequest = { showCreatePlaylistDialog = false }, title = { Text("Create Playlist") }, text = { OutlinedTextField(value = newPlaylistName, onValueChange = { newPlaylistName = it }, label = { Text("Playlist Name") }, singleLine = true) }, confirmButton = { Button(onClick = { if (newPlaylistName.isNotBlank()) { coroutineScope.launch { db.createPlaylist(PlaylistEntity(name = newPlaylistName.trim())) }; showCreatePlaylistDialog = false } }) { Text("Create") } }, dismissButton = { TextButton(onClick = { showCreatePlaylistDialog = false }) { Text("Cancel") } })
        }
        if (showAddToPlaylistDialog && selectedSongForAction != null) {
            val songToAdd = selectedSongForAction!!
            AlertDialog(onDismissRequest = { showAddToPlaylistDialog = false; selectedSongForAction = null }, title = { Text("Select Playlist") }, text = { LazyColumn { if (customPlaylists.isEmpty()) item { Text("No playlists created yet.") }; items(customPlaylists) { playlist -> TextButton(onClick = { coroutineScope.launch { val mem = db.getSongMemory(songToAdd.id); if (mem == null) { db.saveSongMemory(SongEntity(localMediaId = songToAdd.id, customTitle = null, customArtist = null, fetchedTitle = null, fetchedArtist = null, fetchedArtUrl = songToAdd.customArtUrl, fetchedLyrics = null, isFavorite = false, lastPlayedAt = 0L)) }; db.addSongToPlaylist(PlaylistSongCrossRef(playlistId = playlist.playlistId, localMediaId = songToAdd.id)) }; showAddToPlaylistDialog = false; selectedSongForAction = null }, modifier = Modifier.fillMaxWidth()) { Text(playlist.name) } } } }, confirmButton = { TextButton(onClick = { showAddToPlaylistDialog = false; selectedSongForAction = null }) { Text("Cancel") } })
        }
    }
}

@Composable
fun FullScreenPlayer(song: LocalSong, internetData: InternetSongData?, isPlaying: Boolean, currentPosition: Long, totalDuration: Long, isFavorite: Boolean, queueList: List<LocalSong>, memoryMap: Map<Long, SongEntity>, playSong: (LocalSong) -> Unit, onRemoveFromQueue: (Int) -> Unit, onClose: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit, onPrev: () -> Unit, onSeek: (Float) -> Unit, onToggleFavorite: () -> Unit) {
    val displayTitle = internetData?.title ?: song.title; val displayArtist = internetData?.artist ?: song.artist; val displayArt = internetData?.artUrl?.takeIf { it.isNotEmpty() } ?: song.customArtUrl ?: song.albumArtUri.toString(); var showLyrics by remember { mutableStateOf(false) }; var showQueue by remember { mutableStateOf(false) } 
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})) {
            key(displayArt) { SubcomposeAsyncImage(model = ImageRequest.Builder(LocalContext.current).data(displayArt).crossfade(true).build(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(radius = 60.dp), error = { BlueWhiteFallback(modifier = Modifier.fillMaxSize()) }, loading = { BlueWhiteFallback(modifier = Modifier.fillMaxSize()) }) }
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
            Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Minimize", modifier = Modifier.size(36.dp), tint = Color.White) }
                    Row { IconButton(onClick = { showQueue = !showQueue; if(showQueue) showLyrics = false }) { Icon(if (showQueue) Icons.Default.QueueMusic else Icons.Default.FormatListBulleted, contentDescription = "Toggle Queue", tint = Color.White) }; if (internetData?.lyrics != null) { IconButton(onClick = { showLyrics = !showLyrics; if(showLyrics) showQueue = false }) { Icon(if (showLyrics) Icons.Default.MusicNote else Icons.Default.Subject, contentDescription = "Toggle Lyrics", tint = Color.White) } } }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (showQueue) {
                        LazyColumn(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(16.dp)).padding(8.dp)) {
                            items(queueList.size) { idx ->
                                val queueSong = queueList[idx]; val mem = memoryMap[queueSong.id]; val qTitle = mem?.customTitle?.takeIf { it.isNotBlank() } ?: mem?.fetchedTitle?.takeIf { it.isNotBlank() } ?: queueSong.title; val qArtist = mem?.customArtist?.takeIf { it.isNotBlank() } ?: mem?.fetchedArtist?.takeIf { it.isNotBlank() } ?: queueSong.artist
                                Row(modifier = Modifier.fillMaxWidth().clickable { playSong(queueSong) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(if (queueSong.id == song.id) Icons.Default.PlayArrow else Icons.Default.MusicNote, contentDescription = null, tint = if (queueSong.id == song.id) MaterialTheme.colorScheme.primary else Color.White, modifier = Modifier.size(24.dp)); Spacer(modifier = Modifier.width(12.dp)); Column(modifier = Modifier.weight(1f)) { Text(qTitle, fontWeight = if (queueSong.id == song.id) FontWeight.Bold else FontWeight.Normal, color = if (queueSong.id == song.id) MaterialTheme.colorScheme.primary else Color.White, maxLines = 1); Text(qArtist, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f), maxLines = 1) }; IconButton(onClick = { onRemoveFromQueue(idx) }) { Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.White.copy(alpha=0.5f)) } }
                            }
                        }
                    } else if (showLyrics && internetData?.lyrics != null) { val scrollState = rememberScrollState(); Text(text = internetData.lyrics, style = MaterialTheme.typography.titleMedium.copy(lineHeight = 28.sp), color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.fillMaxSize().verticalScroll(scrollState).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { showLyrics = false }))
                    } else { Card(modifier = Modifier.fillMaxWidth().aspectRatio(1f).shadow(24.dp, RoundedCornerShape(32.dp)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { if (internetData?.lyrics != null) showLyrics = true }), shape = RoundedCornerShape(32.dp)) { SubcomposeAsyncImage(model = ImageRequest.Builder(LocalContext.current).data(displayArt).crossfade(1000).build(), contentDescription = "Album Art", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), error = { BlueWhiteFallback(modifier = Modifier.fillMaxSize(), iconSize = 80.dp) }, loading = { BlueWhiteFallback(modifier = Modifier.fillMaxSize(), iconSize = 80.dp) }) } }
                }
                Spacer(modifier = Modifier.height(32.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Column(modifier = Modifier.weight(1f)) { Text(displayTitle, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1); Spacer(modifier = Modifier.height(4.dp)); Text(displayArtist, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.8f), maxLines = 1) }; IconButton(onClick = onToggleFavorite) { Icon(if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = "Favorite", modifier = Modifier.size(32.dp), tint = if (isFavorite) Color.Red else Color.White) } }
                Spacer(modifier = Modifier.height(24.dp)); Slider(value = if (totalDuration > 0) currentPosition.toFloat() / totalDuration.toFloat() else 0f, onValueChange = onSeek, modifier = Modifier.fillMaxWidth(), colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f)))
                val formatTime = { ms: Long -> val totalSeconds = ms / 1000; String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60) }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(formatTime(currentPosition), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f)); Text(formatTime(totalDuration), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f)) }
                Spacer(modifier = Modifier.height(32.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) { IconButton(onClick = onPrev) { Icon(Icons.Default.SkipPrevious, contentDescription = "Prev", modifier = Modifier.size(48.dp), tint = Color.White) }; Box(modifier = Modifier.size(80.dp).clip(CircleShape).background(Color.White).clickable { onPlayPause() }, contentAlignment = Alignment.Center) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Play/Pause", modifier = Modifier.size(48.dp), tint = Color.Black) }; IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, contentDescription = "Next", modifier = Modifier.size(48.dp), tint = Color.White) } }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun PlayerControlsBar(currentSong: LocalSong, internetData: InternetSongData?, isPlaying: Boolean, currentPosition: Long, totalDuration: Long, onPlayPause: () -> Unit, onNext: () -> Unit, onPrev: () -> Unit, onBarClick: () -> Unit) {
    val displayTitle = internetData?.title ?: currentSong.title; val displayArtist = internetData?.artist ?: currentSong.artist; val displayArt: String = internetData?.artUrl?.takeIf { it.isNotEmpty() } ?: currentSong.customArtUrl ?: currentSong.albumArtUri.toString()
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().clickable { onBarClick() }, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), shadowElevation = 8.dp) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            LinearProgressIndicator(progress = { if (totalDuration > 0) currentPosition.toFloat() / totalDuration.toFloat() else 0f }, modifier = Modifier.fillMaxWidth().height(2.dp), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SubcomposeAsyncImage(model = ImageRequest.Builder(LocalContext.current).data(displayArt).crossfade(true).build(), contentDescription = "Album Art", contentScale = ContentScale.Crop, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)), error = { BlueWhiteFallback(modifier = Modifier.fillMaxSize(), iconSize = 20.dp) }, loading = { BlueWhiteFallback(modifier = Modifier.fillMaxSize(), iconSize = 20.dp) })
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) { Text(displayTitle, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1); Text(displayArtist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), maxLines = 1) }
                Row { IconButton(onClick = onPrev) { Icon(Icons.Default.SkipPrevious, contentDescription = "Prev", tint = MaterialTheme.colorScheme.primary) }; IconButton(onClick = onPlayPause) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Play/Pause", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp)) }; IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = MaterialTheme.colorScheme.primary) } }
            }
        }
    }
}

fun fetchLocalMusic(context: Context): List<LocalSong> {
    val songs = mutableListOf<LocalSong>(); val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.ALBUM_ID)
    val junkPattern = Regex("(?i)(.*\\d{8}.*|.*aud-.*|.*ptt-.*|.*wa00.*|.*record.*)")
    context.contentResolver.query(uri, projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} ASC")?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID); val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE); val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST); val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        while (cursor.moveToNext()) {
            val title = cursor.getString(titleCol) ?: "Unknown"
            if (title.matches(junkPattern)) continue 
            songs.add(LocalSong(cursor.getLong(idCol), title, cursor.getString(artistCol) ?: "Unknown Artist", cursor.getLong(albumIdCol)))
        }
    }
    return songs
}

suspend fun fetchLiveSearchResults(query: String, language: String): List<InternetSongData> = coroutineScope {
    val results = mutableListOf<InternetSongData>()

    val saavnTask = async(Dispatchers.IO) {
        val saavnList = mutableListOf<InternetSongData>()
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val conn = URL("https://www.jiosaavn.com/api.php?__call=autocomplete.get&_format=json&_marker=0&cc=in&query=$q").openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            if (conn.responseCode == 200) {
                val arr = JSONObject(conn.inputStream.bufferedReader().readText()).optJSONObject("songs")?.optJSONArray("data")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val t = arr.getJSONObject(i); val info = t.optJSONObject("more_info"); val id = t.optString("id", "")
                        saavnList.add(InternetSongData(id, t.optString("title", "").replace("&quot;", "\"").replace("&amp;", "&"), info?.optString("singers", "") ?: info?.optString("primary_artists", "") ?: "", t.optString("image", "").replace("50x50.jpg", "500x500.jpg")))
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        saavnList
    }

    val archiveTask = async(Dispatchers.IO) {
        val iaList = mutableListOf<InternetSongData>()
        try {
            val q = URLEncoder.encode("$query AND mediatype:audio", "UTF-8")
            val conn = URL("https://archive.org/advancedsearch.php?q=$q&fl[]=identifier,title,creator&rows=10&output=json").openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            if (conn.responseCode == 200) {
                val docs = JSONObject(conn.inputStream.bufferedReader().readText()).optJSONObject("response")?.optJSONArray("docs")
                if (docs != null) {
                    for (i in 0 until docs.length()) {
                        val t = docs.getJSONObject(i); val id = t.optString("identifier", "")
                        if (id.isNotBlank()) iaList.add(InternetSongData("ia:$id", t.optString("title", "Unknown Album"), t.optString("creator", "Archive OST"), "https://archive.org/services/img/$id"))
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        iaList
    }

    results.addAll(saavnTask.await())
    results.addAll(archiveTask.await()) 
    return@coroutineScope results
}

suspend fun fetchAudioStreamUrl(title: String, artist: String, saavnId: String = ""): String? = withContext(Dispatchers.IO) {
    if (saavnId.isNotBlank() && !saavnId.startsWith("ia:")) {
        val idApis = listOf("https://saavn.dev/api/songs?ids=", "https://saavn.sumit.co/api/songs?ids=")
        for (api in idApis) {
            try {
                val conn = URL("$api$saavnId").openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.connectTimeout = 3000
                if (conn.responseCode == 200) {
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    val dataArr = json.optJSONArray("data") ?: json.optJSONObject("data")?.optJSONArray("results")
                    if (dataArr != null && dataArr.length() > 0) {
                        val downloadUrls = dataArr.getJSONObject(0).optJSONArray("downloadUrl")
                        if (downloadUrls != null && downloadUrls.length() > 0) {
                            var bestUrl = ""
                            for (j in 0 until downloadUrls.length()) { val link = downloadUrls.getJSONObject(j).optString("link", downloadUrls.getJSONObject(j).optString("url", "")); if (link.isNotBlank()) bestUrl = link }
                            if (bestUrl.isNotBlank()) return@withContext bestUrl.replace("http://", "https://")
                        }
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    val cleanTitle = title.replace(Regex("\\(.*?\\)"), "").replace(Regex("\\[.*?\\]"), "").trim()
    val ytQuery = URLEncoder.encode("$cleanTitle $artist official audio", "UTF-8")
    
    val pipedInstances = listOf("pipedapi.tokhmi.xyz", "pipedapi.kavin.rocks", "pipedapi.smnz.de")
    for (instance in pipedInstances) {
        try {
            var searchConn = URL("https://$instance/search?q=$ytQuery&filter=all").openConnection() as HttpURLConnection
            searchConn.setRequestProperty("User-Agent", "Mozilla/5.0")
            searchConn.connectTimeout = 4000
            if (searchConn.responseCode != 200) continue
            val items = JSONObject(searchConn.inputStream.bufferedReader().readText()).optJSONArray("items") ?: continue
            var videoId = ""
            for (i in 0 until items.length()) { val item = items.getJSONObject(i); if (item.optString("type") == "stream") { videoId = item.optString("url", "").replace("/watch?v=", "").substringBefore("&"); break } }
            if (videoId.isEmpty()) continue
            val streamConn = URL("https://$instance/streams/$videoId").openConnection() as HttpURLConnection
            streamConn.setRequestProperty("User-Agent", "Mozilla/5.0")
            streamConn.connectTimeout = 4000
            if (streamConn.responseCode != 200) continue
            val audioStreams = JSONObject(streamConn.inputStream.bufferedReader().readText()).optJSONArray("audioStreams") ?: continue
            var bestUrl: String? = null
            var highestBitrate = 0
            for (i in 0 until audioStreams.length()) {
                val stream = audioStreams.getJSONObject(i)
                val format = stream.optString("format", "").lowercase()
                val mimeType = stream.optString("mimeType", "").lowercase()
                val bitrate = stream.optInt("bitrate", 0)
                if (format.contains("m4a") || format.contains("webm") || mimeType.contains("audio")) { if (bitrate >= highestBitrate) { highestBitrate = bitrate; bestUrl = stream.optString("url", "") } }
            }
            if (!bestUrl.isNullOrEmpty()) return@withContext bestUrl
        } catch (e: Exception) { e.printStackTrace() }
    }
    return@withContext null
}

private fun searchItunesAPI(query: String): InternetSongData? {
    try {
        val conn = URL("https://itunes.apple.com/search?term=${URLEncoder.encode(query, "UTF-8")}&media=music&entity=song&limit=1").openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        if (conn.responseCode == 200) {
            val res = JSONObject(conn.inputStream.bufferedReader().readText()).optJSONArray("results")
            if (res != null && res.length() > 0) { val t = res.getJSONObject(0); return InternetSongData("", t.optString("trackName", ""), t.optString("artistName", ""), t.optString("artworkUrl100", "").replace("100x100bb", "600x600bb")) }
        }
    } catch (e: Exception) { e.printStackTrace() }
    return null
}

private fun searchDeezerAPI(query: String): InternetSongData? {
    try {
        val conn = URL("https://api.deezer.com/search?q=${URLEncoder.encode(query, "UTF-8")}&limit=1").openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        if (conn.responseCode == 200) {
            val data = JSONObject(conn.inputStream.bufferedReader().readText()).optJSONArray("data")
            if (data != null && data.length() > 0) { val t = data.getJSONObject(0); return InternetSongData("", t.optString("title", ""), t.optJSONObject("artist")?.optString("name", "") ?: "", t.optJSONObject("album")?.optString("cover_xl", "") ?: "") }
        }
    } catch (e: Exception) { e.printStackTrace() }
    return null
}

private fun searchJioSaavnAPI(query: String): InternetSongData? {
    try {
        val conn = URL("https://www.jiosaavn.com/api.php?__call=autocomplete.get&_format=json&_marker=0&cc=in&query=${URLEncoder.encode(query, "UTF-8")}").openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        if (conn.responseCode == 200) {
            val arr = JSONObject(conn.inputStream.bufferedReader().readText()).optJSONObject("songs")?.optJSONArray("data")
            if (arr != null && arr.length() > 0) { val t = arr.getJSONObject(0); val m = t.optJSONObject("more_info"); return InternetSongData("", t.optString("title", "").replace("&quot;", "\"").replace("&amp;", "&"), m?.optString("singers", "") ?: m?.optString("primary_artists", "") ?: "", t.optString("image", "").replace("50x50.jpg", "500x500.jpg")) }
        }
    } catch (e: Exception) { e.printStackTrace() }
    return null
}

private fun searchYouTubePipedAPI(query: String): InternetSongData? {
    try {
        val conn = URL("https://pipedapi.tokhmi.xyz/search?q=${URLEncoder.encode(query, "UTF-8")}&filter=all").openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        if (conn.responseCode == 200) {
            val items = JSONObject(conn.inputStream.bufferedReader().readText()).optJSONArray("items")
            if (items != null) { for (i in 0 until items.length()) { val t = items.getJSONObject(i); if (t.optString("type") == "stream") return InternetSongData("", t.optString("title", ""), t.optString("uploaderName", ""), t.optString("thumbnail", "")) } }
        }
    } catch (e: Exception) { e.printStackTrace() }
    return null
}

private fun searchLyricsAPI(title: String, artist: String): String? {
    try {
        val conn = URL("https://lrclib.net/api/search?q=${URLEncoder.encode(if (artist.isNotBlank()) "$title $artist" else title, "UTF-8")}").openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        if (conn.responseCode == 200) {
            val arr = JSONArray(conn.inputStream.bufferedReader().readText())
            for (i in 0 until arr.length()) { val t = arr.getJSONObject(i); val p = t.optString("plainLyrics", ""); val s = t.optString("syncedLyrics", ""); if (p.isNotBlank() && p != "null") return p; if (s.isNotBlank() && s != "null") return s.replace(Regex("\\[.*?\\]"), "").trim() }
        }
    } catch (e: Exception) { e.printStackTrace() }
    return null
}

suspend fun fetchMultiSourceMetadata(title: String, artist: String): InternetSongData? = coroutineScope {
    val cleanTitle = title.lowercase().replace(".mp3", "").replace(".m4a", "").replace(".wav", "").replace("y2mate.com", "").replace("y2mate", "").replace("official video", "").replace("official audio", "").replace("lyrics", "").replace("hd", "").replace("slowed", "").replace("reverb", "").replace(Regex("\\b\\d{2,4}[-/_]\\d{2}[-/_]\\d{2,4}\\b"), "").replace(Regex("\\b\\d{6,10}\\b"), "").replace(Regex("[^a-zA-Z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
    val isUnknownArtist = artist.contains("unknown", ignoreCase = true)
    var result: InternetSongData? = null

    val t1 = async(Dispatchers.IO) { if (!isUnknownArtist) searchItunesAPI("$cleanTitle $artist") else null }
    val t2 = async(Dispatchers.IO) { if (!isUnknownArtist) searchJioSaavnAPI("$cleanTitle $artist") else null }
    val t3 = async(Dispatchers.IO) { if (!isUnknownArtist) searchDeezerAPI("$cleanTitle $artist") else null }
    val t4 = async(Dispatchers.IO) { if (!isUnknownArtist) searchYouTubePipedAPI("$cleanTitle $artist") else null }
    val t5 = async(Dispatchers.IO) { searchLyricsAPI(cleanTitle, if (!isUnknownArtist) artist else "") }

    result = t1.await() ?: t2.await() ?: t3.await() ?: t4.await()
    var lyrics = t5.await()

    if (result == null && isUnknownArtist) {
        val l1 = async(Dispatchers.IO) { searchItunesAPI(cleanTitle) }
        val l2 = async(Dispatchers.IO) { searchJioSaavnAPI(cleanTitle) }
        val l3 = async(Dispatchers.IO) { searchDeezerAPI(cleanTitle) }
        val l4 = async(Dispatchers.IO) { searchYouTubePipedAPI(cleanTitle) }
        result = l1.await() ?: l2.await() ?: l3.await() ?: l4.await()
    }
    
    if (lyrics == null && !isUnknownArtist) lyrics = searchLyricsAPI(cleanTitle, "")
    if (result != null) return@coroutineScope result.copy(lyrics = lyrics)
    else if (lyrics != null) return@coroutineScope InternetSongData("", cleanTitle, artist, "", lyrics)
    return@coroutineScope null
}