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
import java.util.*
import java.text.SimpleDateFormat

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

data class LocalSong(val id: Long, val title: String, val artist: String, val albumId: Long, val webStreamUrl: String? = null, val customArtUrl: String? = null) { val albumArtUri: Uri get() = if(customArtUrl != null) Uri.parse(customArtUrl.substringBefore("|||")) else Uri.parse("content://media/external/audio/albumart/$albumId") }
data class InternetSongData(val id: String = "", val title: String, val artist: String, val artUrl: String, val lyrics: String? = null)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { AestheticTheme { MusicPlayerUI() } } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(songId: Long, title: String, artist: String, artUrl: String, isPlaying: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SubcomposeAsyncImage(model = artUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)), error = { BlueWhiteFallback() }, loading = { BlueWhiteFallback() })
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                Text(artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
            if (songId < 0) Icon(Icons.Default.CloudDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), modifier = Modifier.size(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicPlayerUI() {
    val context = LocalContext.current; val coroutineScope = rememberCoroutineScope(); val db = remember { AppDatabase.getDatabase(context).libraryDao() }
    val songMemories by db.getAllSongMemories().collectAsState(initial = emptyList())
    val memoryMap = remember(songMemories) { songMemories.associateBy { it.localMediaId } }
    val favoriteMemories by db.getFavoriteSongs().collectAsState(initial = emptyList())
    val recentlyPlayedMemories by db.getRecentlyPlayed().collectAsState(initial = emptyList())
    val customPlaylists by db.getAllPlaylists().collectAsState(initial = emptyList())
    
    var localSongs by remember { mutableStateOf<List<LocalSong>>(emptyList()) }
    val favoriteSongs = remember(favoriteMemories, localSongs) { favoriteMemories.mapNotNull { mem -> if (mem.localMediaId >= 0) localSongs.find { it.id == mem.localMediaId } else LocalSong(mem.localMediaId, mem.fetchedTitle ?: mem.customTitle ?: "Unknown", mem.fetchedArtist ?: mem.customArtist ?: "Unknown", -1L, null, mem.fetchedArtUrl) } }
    val recentlyPlayedSongs = remember(recentlyPlayedMemories, localSongs) { recentlyPlayedMemories.mapNotNull { mem -> if (mem.localMediaId >= 0) localSongs.find { it.id == mem.localMediaId } else LocalSong(mem.localMediaId, mem.fetchedTitle ?: mem.customTitle ?: "Unknown", mem.fetchedArtist ?: mem.customArtist ?: "Unknown", -1L, null, mem.fetchedArtUrl) } }
    
    var isSearchActive by remember { mutableStateOf(false) }; var searchQuery by remember { mutableStateOf("") }
    var permissionGranted by remember { mutableStateOf(false) }; var currentTab by remember { mutableIntStateOf(0) } 
    var viewingLikedSongs by remember { mutableStateOf(false) }; var viewingPlaylistId by remember { mutableStateOf<Long?>(null) }
    var currentPlaylistData by remember { mutableStateOf<PlaylistWithSongs?>(null) }
    
    LaunchedEffect(viewingPlaylistId) { viewingPlaylistId?.let { id -> currentPlaylistData = db.getPlaylistWithSongs(id) } }

    val languages = listOf("All", "Tamil", "English", "Hindi", "Malayalam", "Telugu", "Kannada", "Marathi", "Bengali", "Punjabi", "Gujarati")
    var selectedLanguage by remember { mutableStateOf("Tamil") } 
    var showLangMenu by remember { mutableStateOf(false) }
    
    // NEW: Explicit toggle for Playlists vs Tracks
    var isPlaylistMode by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val detectedLang = getPrivacyMaskedLanguage()
        if (languages.contains(detectedLang)) selectedLanguage = detectedLang
    }

    var liveSearchResults by remember { mutableStateOf<List<InternetSongData>>(emptyList()) }; var isLiveSearching by remember { mutableStateOf(false) }

    LaunchedEffect(searchQuery, selectedLanguage, isPlaylistMode, isSearchActive) {
        if (!isSearchActive) return@LaunchedEffect
        val q = if (searchQuery.isBlank()) {
            if (selectedLanguage == "All") "Top Hits" else "$selectedLanguage Hits"
        } else {
            if (selectedLanguage == "All") searchQuery else "$searchQuery $selectedLanguage"
        }
        delay(400); isLiveSearching = true; liveSearchResults = fetchLiveSearchResults(q, selectedLanguage, isPlaylistMode); isLiveSearching = false
    }

    var currentSong by remember { mutableStateOf<LocalSong?>(null) }; var fetchedInternetData by remember { mutableStateOf<InternetSongData?>(null) } 
    var selectedSongForAction by remember { mutableStateOf<LocalSong?>(null) }; var showEditDialog by remember { mutableStateOf(false) }
    var showAddToPlaylistDialog by remember { mutableStateOf(false) }; var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    
    var playQueue by remember { mutableStateOf<List<LocalSong>>(emptyList()) }
    var isPlaying by remember { mutableStateOf(false) }; var playbackState by remember { mutableStateOf(Player.STATE_IDLE) }
    var currentPosition by remember { mutableStateOf(0L) }; var totalDuration by remember { mutableStateOf(0L) }
    var showFullScreenPlayer by remember { mutableStateOf(false) }
    var autoPlayContext by remember { mutableStateOf<List<InternetSongData>>(emptyList()) }; var autoPlayIndex by remember { mutableIntStateOf(-1) }

    val exoPlayer = remember {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory().setUserAgent("Mozilla/5.0").setAllowCrossProtocolRedirects(true)
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(DefaultDataSource.Factory(context, httpDataSourceFactory))).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true); repeatMode = Player.REPEAT_MODE_OFF 
        }
    }
    
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    fun playSongList(song: LocalSong, sourceList: List<LocalSong>) {
        autoPlayContext = emptyList(); autoPlayIndex = -1 
        val idx = sourceList.indexOf(song); playQueue = sourceList
        exoPlayer.stop(); exoPlayer.clearMediaItems()
        exoPlayer.setMediaItems(sourceList.map { s -> MediaItem.Builder().setUri(s.webStreamUrl ?: ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, s.id).toString()).setMediaId(s.id.toString()).build() })
        if (idx >= 0) exoPlayer.seekTo(idx, C.TIME_UNSET)
        exoPlayer.prepare(); exoPlayer.play()
    }

    fun playWebSong(webSongData: InternetSongData) {
        coroutineScope.launch {
            if (webSongData.id.startsWith("ia:")) {
                withContext(Dispatchers.Main) { Toast.makeText(context, "Deep Scanning Archive... Extracting Files", Toast.LENGTH_SHORT).show() }
                
                val iaPlaylist = withContext(Dispatchers.IO) {
                    val id = webSongData.id.removePrefix("ia:")
                    val list = mutableListOf<LocalSong>()
                    try {
                        val metaStr = fetchHttp("https://archive.org/metadata/$id")
                        if (metaStr != null) {
                            val metaJson = JSONObject(metaStr)
                            val files = metaJson.optJSONArray("files")
                            if (files != null) {
                                val trackMap = mutableMapOf<String, JSONObject>()
                                for (i in 0 until files.length()) {
                                    val f = files.getJSONObject(i); val format = f.optString("format", "").lowercase()
                                    val rawName = f.optString("name", ""); val lowerName = rawName.lowercase()
                                    if (lowerName.endsWith(".xml") || lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") || lowerName.endsWith(".png") || lowerName.endsWith(".sqlite") || lowerName.endsWith(".txt")) continue
                                    if (format.contains("mp3") || format.contains("flac") || format.contains("ogg") || lowerName.endsWith(".mp3") || lowerName.endsWith(".m4a")) {
                                        val baseName = rawName.substringBeforeLast(".")
                                        if (trackMap[baseName] == null || format.contains("mp3")) trackMap[baseName] = f
                                    }
                                }
                                val sortedTracks = trackMap.values.sortedWith(compareBy({ it.optString("track", "999").replace(Regex("[^0-9]"), "").toIntOrNull() ?: 999 }, { it.optString("name") }))
                                for (f in sortedTracks) {
                                    val trackTitle = f.optString("title").takeIf { it.isNotBlank() } ?: f.optString("name").substringBeforeLast(".")
                                    val trackArtist = f.optString("creator").takeIf { it.isNotBlank() } ?: webSongData.artist.replace("[Full Album] ", "").replace("[Playlist] ", "")
                                    val dummyId = -(kotlin.math.abs((trackTitle + trackArtist).hashCode().toLong())).let { if(it==0L) -1L else it }
                                    val packedArt = "${webSongData.artUrl}|||ia:$id"
                                    list.add(LocalSong(dummyId, trackTitle, trackArtist, -1L, "https://archive.org/download/$id/${Uri.encode(f.optString("name"))}", packedArt))
                                }
                            }
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                    list
                }
                
                if (iaPlaylist.isNotEmpty()) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Hidden Collection Found: ${iaPlaylist.size} tracks!", Toast.LENGTH_LONG).show() }
                    val parentId = -(kotlin.math.abs((webSongData.title + webSongData.artist).hashCode().toLong())).let { if(it==0L) -1L else it }
                    val packedArt = "${webSongData.artUrl}|||${webSongData.id}"
                    withContext(Dispatchers.IO) { 
                        db.saveSongMemory(SongEntity(parentId, webSongData.title, webSongData.artist, webSongData.title, webSongData.artist, packedArt, null, memoryMap[parentId]?.isFavorite ?: false, System.currentTimeMillis())) 
                    }
                    fetchedInternetData = webSongData; playSongList(iaPlaylist.first(), iaPlaylist); showFullScreenPlayer = true
                } else {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "No audio found.", Toast.LENGTH_SHORT).show() }
                }
                return@launch
            }

            val cleanTitle = webSongData.title.lowercase().replace(Regex("[^a-z0-9]"), "")
            val localMatch = if (cleanTitle.isNotBlank()) localSongs.find { it.title.lowercase().replace(Regex("[^a-z0-9]"), "").let { n -> n == cleanTitle || n.contains(cleanTitle) || cleanTitle.contains(n) } } else null
            if (localMatch != null) { Toast.makeText(context, "Playing offline file.", Toast.LENGTH_SHORT).show(); playSongList(localMatch, localSongs); showFullScreenPlayer = true; return@launch }
            
            Toast.makeText(context, "Extracting audio...", Toast.LENGTH_SHORT).show()
            val streamUrl = fetchAudioStreamUrl(webSongData.title, webSongData.artist, webSongData.id)
            if (streamUrl != null) {
                val dummyId = -(kotlin.math.abs((webSongData.title + webSongData.artist).hashCode().toLong())).let { if(it==0L) -1L else it }
                val packedArt = "${webSongData.artUrl}|||${webSongData.id}"
                val dummySong = LocalSong(dummyId, webSongData.title, webSongData.artist, -1L, streamUrl, packedArt)
                
                withContext(Dispatchers.IO) { db.saveSongMemory(SongEntity(dummyId, webSongData.title, webSongData.artist, webSongData.title, webSongData.artist, packedArt, null, memoryMap[dummyId]?.isFavorite ?: false, System.currentTimeMillis())) }
                fetchedInternetData = webSongData; playQueue = listOf(dummySong)
                exoPlayer.stop(); exoPlayer.clearMediaItems()
                exoPlayer.setMediaItem(MediaItem.Builder().setUri(streamUrl).setMediaId(dummyId.toString()).build()); exoPlayer.prepare(); exoPlayer.play(); showFullScreenPlayer = true
            } else { 
                Toast.makeText(context, "Stream unplayable. Skipping...", Toast.LENGTH_SHORT).show() 
                if (autoPlayContext.isNotEmpty() && autoPlayIndex in 0 until autoPlayContext.size - 1) { 
                    coroutineScope.launch { delay(1000); autoPlayIndex++; playWebSong(autoPlayContext[autoPlayIndex]) } 
                }
            }
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener { 
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlaybackStateChanged(state: Int) { 
                playbackState = state 
                if (state == Player.STATE_ENDED) {
                    if (autoPlayContext.isNotEmpty() && autoPlayIndex in 0 until autoPlayContext.size - 1) { 
                        coroutineScope.launch { delay(1000); autoPlayIndex++; playWebSong(autoPlayContext[autoPlayIndex]) } 
                    } else if (currentSong != null && (currentSong!!.id < 0)) {
                        coroutineScope.launch {
                            val cSong = currentSong
                            if (cSong != null) {
                                withContext(Dispatchers.Main) { Toast.makeText(context, "Finding recommendations...", Toast.LENGTH_SHORT).show() }
                                val recQuery = "${cSong.artist} Hits"
                                val recs = fetchLiveSearchResults(recQuery, selectedLanguage, false)
                                if (recs.isNotEmpty()) { autoPlayContext = recs; autoPlayIndex = 0; playWebSong(recs[0]) }
                            }
                        }
                    }
                }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { 
                if (mediaItem == null || exoPlayer.mediaItemCount == 0) { currentSong = null; isPlaying = false; showFullScreenPlayer = false } 
                else if (exoPlayer.currentMediaItemIndex in playQueue.indices) currentSong = playQueue[exoPlayer.currentMediaItemIndex] 
            }
            override fun onPlayerError(error: PlaybackException) { Toast.makeText(context, "Error: ${error.message}", Toast.LENGTH_LONG).show() }
        }
        exoPlayer.addListener(listener); onDispose { exoPlayer.removeListener(listener) }
    }

    LaunchedEffect(currentSong) {
        val song = currentSong ?: return@LaunchedEffect
        if (song.id < 0) return@LaunchedEffect 
        val mem = db.getSongMemory(song.id)
        val dTitle = mem?.customTitle?.takeIf { it.isNotBlank() } ?: mem?.fetchedTitle?.takeIf { it.isNotBlank() } ?: song.title
        val dArtist = mem?.customArtist?.takeIf { it.isNotBlank() } ?: mem?.fetchedArtist?.takeIf { it.isNotBlank() } ?: song.artist
        fetchedInternetData = InternetSongData("", dTitle, dArtist, mem?.fetchedArtUrl ?: "", mem?.fetchedLyrics)
        db.saveSongMemory(SongEntity(song.id, mem?.customTitle, mem?.customArtist, mem?.fetchedTitle ?: dTitle, mem?.fetchedArtist ?: dArtist, mem?.fetchedArtUrl, mem?.fetchedLyrics, mem?.isFavorite ?: false, System.currentTimeMillis()))
        if (mem?.fetchedArtUrl == null) { 
            val res = fetchMultiSourceMetadata(dTitle, dArtist)
            if (res != null) { fetchedInternetData = res; db.saveSongMemory(SongEntity(song.id, mem?.customTitle, mem?.customArtist, res.title, res.artist, res.artUrl, res.lyrics, mem?.isFavorite ?: false, System.currentTimeMillis())) } 
        }
    }

    val toggleFavorite = { coroutineScope.launch { currentSong?.let { song -> db.updateFavoriteStatus(song.id, !(db.getSongMemory(song.id)?.isFavorite ?: false)) } } }
    LaunchedEffect(isPlaying) { while (isPlaying) { currentPosition = exoPlayer.currentPosition; totalDuration = if (exoPlayer.duration > 0) exoPlayer.duration else 0L; delay(1000L) } }
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted -> permissionGranted = isGranted; if (isGranted) localSongs = fetchLocalMusic(context) }
    LaunchedEffect(Unit) { launcher.launch(permission) }
    BackHandler(enabled = showFullScreenPlayer || viewingLikedSongs || viewingPlaylistId != null) { if (showFullScreenPlayer) showFullScreenPlayer = false else if (viewingLikedSongs) viewingLikedSongs = false else if (viewingPlaylistId != null) viewingPlaylistId = null }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = { 
                TopAppBar(
                    title = { 
                        if (isSearchActive) {
                            TextField(value = searchQuery, onValueChange = { searchQuery = it }, placeholder = { Text("Search ${if (selectedLanguage == "All") "" else selectedLanguage}...") }, singleLine = true, colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent)) 
                        } else { 
                            Text(when(currentTab) { 0 -> "Dashboard"; 1 -> "Discover"; 2 -> "My Library"; else -> "All Songs" }, fontWeight = FontWeight.Bold) 
                        } 
                    }, 
                    actions = { 
                        if (isSearchActive) {
                            Box {
                                IconButton(onClick = { showLangMenu = true }) { Icon(Icons.Default.FilterList, contentDescription = "Filter", tint = MaterialTheme.colorScheme.primary) }
                                DropdownMenu(expanded = showLangMenu, onDismissRequest = { showLangMenu = false }) {
                                    languages.forEach { lang ->
                                        DropdownMenuItem(
                                            text = { Text(lang, fontWeight = if (selectedLanguage == lang) FontWeight.Bold else FontWeight.Normal, color = if (selectedLanguage == lang) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                                            onClick = { selectedLanguage = lang; showLangMenu = false }
                                        )
                                    }
                                }
                            }
                        }
                        IconButton(onClick = { isSearchActive = !isSearchActive; if (!isSearchActive) searchQuery = "" }) { 
                            Icon(if (isSearchActive) Icons.Default.Close else Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary) 
                        } 
                    }, 
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                ) 
            },
            bottomBar = { 
                Column {
                    if (currentSong != null) PlayerControlsBar(currentSong = currentSong!!, internetData = fetchedInternetData, isPlaying = isPlaying, currentPosition = currentPosition, totalDuration = totalDuration, onPlayPause = { if (isPlaying) exoPlayer.pause() else exoPlayer.play() }, onNext = { exoPlayer.seekToNextMediaItem() }, onPrev = { exoPlayer.seekToPreviousMediaItem() }, onBarClick = { showFullScreenPlayer = true })
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 8.dp) {
                        NavigationBarItem(icon = { Icon(Icons.Default.Home, null) }, label = { Text("Home", fontSize = 10.sp) }, selected = currentTab == 0 && !isSearchActive, onClick = { currentTab = 0; isSearchActive = false; viewingLikedSongs = false; viewingPlaylistId = null })
                        NavigationBarItem(icon = { Icon(Icons.Default.Search, null) }, label = { Text("Search", fontSize = 10.sp) }, selected = isSearchActive, onClick = { isSearchActive = true; currentTab = 1 })
                        NavigationBarItem(icon = { Icon(Icons.Default.QueueMusic, null) }, label = { Text("Library", fontSize = 10.sp) }, selected = currentTab == 2 && !isSearchActive, onClick = { currentTab = 2; isSearchActive = false })
                        NavigationBarItem(icon = { Icon(Icons.Default.Folder, null) }, label = { Text("Offline", fontSize = 10.sp) }, selected = currentTab == 3 && !isSearchActive, onClick = { currentTab = 3; isSearchActive = false; viewingLikedSongs = false; viewingPlaylistId = null })
                    }
                }
            }, containerColor = MaterialTheme.colorScheme.background
        ) { paddingValues ->
            Column(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 16.dp)) {
                if (!permissionGranted) { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Storage permission is required.", color = MaterialTheme.colorScheme.primary) }
                } else if (isSearchActive) {
                    
                    // NEW: Hardcoded Filter Chips for Search Mode
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !isPlaylistMode, onClick = { isPlaylistMode = false }, label = { Text("Tracks") }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)))
                        FilterChip(selected = isPlaylistMode, onClick = { isPlaylistMode = true }, label = { Text("Albums & Playlists") }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)))
                    }

                    if (isLiveSearching) Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    else if (liveSearchResults.isEmpty()) Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No tracks found.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) }
                    else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(liveSearchResults) { song -> 
                                TrackRow(songId = -1L, title = song.title, artist = song.artist, artUrl = song.artUrl, isPlaying = false, 
                                    onClick = { autoPlayContext = liveSearchResults; autoPlayIndex = liveSearchResults.indexOf(song); playWebSong(song) }, 
                                    onLongClick = { selectedSongForAction = LocalSong(-(kotlin.math.abs((song.title + song.artist).hashCode().toLong())).let{if(it==0L) -1L else it}, song.title, song.artist, -1L, null, "${song.artUrl}|||${song.id}"); fetchedInternetData = song }) 
                            }
                        }
                    }
                } else {
                    when (currentTab) {
                        0 -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                            item { Spacer(modifier = Modifier.height(8.dp)) }
                            if (recentlyPlayedSongs.isNotEmpty()) {
                                item { Text("Recently Played", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp)) }
                                item {
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 24.dp)) {
                                        items(recentlyPlayedSongs) { song ->
                                            val mem = memoryMap[song.id]; val dTitle = mem?.customTitle?.takeIf { it.isNotBlank() } ?: mem?.fetchedTitle?.takeIf { it.isNotBlank() } ?: song.title; val dArt = mem?.fetchedArtUrl?.substringBefore("|||") ?: song.albumArtUri.toString()
                                            val realId = mem?.fetchedArtUrl?.substringAfter("|||", "") ?: ""
                                            Column(modifier = Modifier.width(120.dp).clickable { if (song.id < 0) { autoPlayContext = recentlyPlayedSongs.filter { it.id < 0 }.map { val rId = it.customArtUrl?.substringAfter("|||", "") ?: ""; InternetSongData(rId, it.title, it.artist, it.customArtUrl?.substringBefore("|||") ?: "") }; autoPlayIndex = autoPlayContext.indexOfFirst { it.title == dTitle }; if (song.webStreamUrl != null) playSongList(song, listOf(song)) else playWebSong(InternetSongData(realId, dTitle, song.artist, dArt)) } else playSongList(song, recentlyPlayedSongs.filter{ it.id >= 0 }) }) {
                                                SubcomposeAsyncImage(model = dArt, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(120.dp).clip(RoundedCornerShape(12.dp)), error = { BlueWhiteFallback() })
                                                Spacer(modifier = Modifier.height(6.dp)); Text(dTitle, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        2 -> {
                            if (viewingLikedSongs) {
                                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = { viewingLikedSongs = false }) { Icon(Icons.Default.ArrowBack, null) }; Text("Liked Songs", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    items(favoriteSongs) { song ->
                                        val mem = memoryMap[song.id]; val dTitle = mem?.customTitle?.takeIf { it.isNotBlank() } ?: mem?.fetchedTitle?.takeIf { it.isNotBlank() } ?: song.title; val dArtist = mem?.customArtist?.takeIf { it.isNotBlank() } ?: mem?.fetchedArtist?.takeIf { it.isNotBlank() } ?: song.artist; val dArt = mem?.fetchedArtUrl?.substringBefore("|||") ?: song.albumArtUri.toString(); val realId = mem?.fetchedArtUrl?.substringAfter("|||", "") ?: ""
                                        TrackRow(song.id, dTitle, dArtist, dArt, currentSong?.id == song.id, onClick = { if (song.id < 0) { if(song.webStreamUrl != null) playSongList(song, listOf(song)) else playWebSong(InternetSongData(realId, dTitle, dArtist, dArt)) } else playSongList(song, favoriteSongs.filter{it.id>=0}) }, onLongClick = { selectedSongForAction = song })
                                    }
                                }
                            } else if (viewingPlaylistId != null && currentPlaylistData != null) {
                                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = { viewingPlaylistId = null }) { Icon(Icons.Default.ArrowBack, null) }; Text(currentPlaylistData!!.playlist.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
                                val playlistSongs = remember(currentPlaylistData, localSongs) { currentPlaylistData!!.songs.mapNotNull { mem -> if (mem.localMediaId >= 0) localSongs.find { it.id == mem.localMediaId } else LocalSong(mem.localMediaId, mem.fetchedTitle ?: "Unknown", mem.fetchedArtist ?: "Unknown", -1L, null, mem.fetchedArtUrl) } }
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    items(playlistSongs) { song ->
                                        val mem = memoryMap[song.id]; val dTitle = mem?.customTitle?.takeIf { it.isNotBlank() } ?: mem?.fetchedTitle?.takeIf { it.isNotBlank() } ?: song.title; val dArtist = mem?.customArtist?.takeIf { it.isNotBlank() } ?: mem?.fetchedArtist?.takeIf { it.isNotBlank() } ?: song.artist; val dArt = mem?.fetchedArtUrl?.substringBefore("|||") ?: song.albumArtUri.toString(); val realId = mem?.fetchedArtUrl?.substringAfter("|||", "") ?: ""
                                        TrackRow(song.id, dTitle, dArtist, dArt, currentSong?.id == song.id, onClick = { if (song.id < 0) { if(song.webStreamUrl != null) playSongList(song, listOf(song)) else playWebSong(InternetSongData(realId, dTitle, dArtist, dArt)) } else playSongList(song, playlistSongs.filter{it.id>=0}) }, onLongClick = { selectedSongForAction = song })
                                    }
                                }
                            } else {
                                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Your Library", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Button(onClick = { showCreatePlaylistDialog = true }) { Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp)); Spacer(modifier = Modifier.width(4.dp)); Text("New") } }
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    item { Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { viewingLikedSongs = true }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Box(modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(Brush.linearGradient(listOf(Color(0xFFFF4081), Color(0xFFE91E63)))), contentAlignment = Alignment.Center) { Icon(Icons.Default.Favorite, null, tint = Color.White) }; Spacer(modifier = Modifier.width(16.dp)); Column { Text("Liked Songs", fontWeight = FontWeight.Bold, fontSize = 18.sp); Text("${favoriteSongs.size} tracks", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)) } } } }
                                    items(customPlaylists) { playlist -> Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { viewingPlaylistId = playlist.playlistId }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Box(modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) { Icon(Icons.Default.QueueMusic, null, tint = MaterialTheme.colorScheme.primary) }; Spacer(modifier = Modifier.width(16.dp)); Column { Text(playlist.name, fontWeight = FontWeight.Bold, fontSize = 18.sp); Text("Custom Playlist", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)) } } } }
                                }
                            }
                        }
                        3 -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(localSongs) { song ->
                                val mem = memoryMap[song.id]; val dTitle = mem?.customTitle?.takeIf { it.isNotBlank() } ?: mem?.fetchedTitle?.takeIf { it.isNotBlank() } ?: song.title; val dArtist = mem?.customArtist?.takeIf { it.isNotBlank() } ?: mem?.fetchedArtist?.takeIf { it.isNotBlank() } ?: song.artist; val dArt = mem?.fetchedArtUrl?.substringBefore("|||") ?: song.albumArtUri.toString()
                                TrackRow(song.id, dTitle, dArtist, dArt, currentSong?.id == song.id, onClick = { playSongList(song, localSongs) }, onLongClick = { selectedSongForAction = song })
                            }
                        }
                    }
                }
            }
        }
        
        AnimatedVisibility(visible = showFullScreenPlayer && currentSong != null, enter = slideInVertically(initialOffsetY = { it }), exit = slideOutVertically(targetOffsetY = { it })) {
            if (currentSong != null) {
                val isLive = totalDuration == C.TIME_UNSET || totalDuration <= 0L
                val progress = if (!isLive && totalDuration > 0) (currentPosition.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f) else 0f
                
                FullScreenPlayer(song = currentSong!!, internetData = fetchedInternetData, isPlaying = isPlaying, currentPosition = currentPosition, totalDuration = totalDuration, isFavorite = memoryMap[currentSong?.id]?.isFavorite == true, queueList = playQueue, memoryMap = memoryMap, 
                playSong = { qSong -> 
                    val qIdx = playQueue.indexOf(qSong)
                    if (qIdx in 0 until exoPlayer.mediaItemCount) { exoPlayer.seekTo(qIdx, C.TIME_UNSET); exoPlayer.play() } 
                    else if (qSong.id < 0 && qSong.webStreamUrl == null) playWebSong(InternetSongData(qSong.customArtUrl?.substringAfter("|||", "") ?: "", qSong.title, qSong.artist, qSong.customArtUrl?.substringBefore("|||")?:"")) 
                    else playSongList(qSong, playQueue)
                }, 
                onRemoveFromQueue = { idx -> 
                    if (idx in playQueue.indices) { 
                        playQueue = playQueue.toMutableList().apply { removeAt(idx) }
                        if (idx in 0 until exoPlayer.mediaItemCount) {
                            try { exoPlayer.removeMediaItem(idx) } catch(e: Exception){} 
                            if (exoPlayer.mediaItemCount == 0) { exoPlayer.stop(); exoPlayer.clearMediaItems(); isPlaying = false; showFullScreenPlayer = false; currentSong = null }
                        }
                    } 
                }, 
                onClose = { showFullScreenPlayer = false }, onPlayPause = { if (isPlaying) exoPlayer.pause() else exoPlayer.play() }, onNext = { exoPlayer.seekToNextMediaItem() }, onPrev = { exoPlayer.seekToPreviousMediaItem() }, onSeek = { percentage -> if(!isLive) { exoPlayer.seekTo((percentage * totalDuration).toLong()); currentPosition = (percentage * totalDuration).toLong() } }, onToggleFavorite = { toggleFavorite() }, isLive = isLive, progress = progress)
            }
        }

        if (selectedSongForAction != null) {
            val s = selectedSongForAction!!
            AlertDialog(onDismissRequest = { selectedSongForAction = null }, title = { Text("Track Options") }, text = { 
                Column { 
                    TextButton(onClick = { 
                        if (s.id < 0) {
                            coroutineScope.launch {
                                Toast.makeText(context, "Fetching stream...", Toast.LENGTH_SHORT).show()
                                val streamUrl = fetchAudioStreamUrl(s.title, s.artist, s.customArtUrl?.substringAfter("|||", "") ?: "")
                                if (streamUrl != null) {
                                    playQueue = playQueue + s.copy(webStreamUrl = streamUrl)
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
                    if (s.id >= 0) TextButton(onClick = { showEditDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Edit Metadata") } 
                } 
            }, confirmButton = { TextButton(onClick = { selectedSongForAction = null }) { Text("Cancel") } })
        }
        
        if (showEditDialog && selectedSongForAction != null) {
            val targetSong = selectedSongForAction!!; val mem = memoryMap[targetSong.id]; var editTitle by remember { mutableStateOf(mem?.customTitle ?: targetSong.title) }; var editArtist by remember { mutableStateOf(mem?.customArtist ?: targetSong.artist) }
            AlertDialog(onDismissRequest = { showEditDialog = false; selectedSongForAction = null }, title = { Text("Fix Metadata", fontWeight = FontWeight.Bold) }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { OutlinedTextField(value = editTitle, onValueChange = { editTitle = it }, label = { Text("Song Title") }, singleLine = true, modifier = Modifier.fillMaxWidth()); OutlinedTextField(value = editArtist, onValueChange = { editArtist = it }, label = { Text("Artist") }, singleLine = true, modifier = Modifier.fillMaxWidth()) } }, confirmButton = { Button(onClick = { coroutineScope.launch { db.saveSongMemory(SongEntity(targetSong.id, editTitle.trim().takeIf { it.isNotEmpty() }, editArtist.trim().takeIf { it.isNotEmpty() }, null, null, null, null, mem?.isFavorite ?: false, mem?.lastPlayedAt ?: 0L)); showEditDialog = false; selectedSongForAction = null; playSongList(targetSong, localSongs) } }) { Text("Save") } }, dismissButton = { TextButton(onClick = { showEditDialog = false; selectedSongForAction = null }) { Text("Cancel") } })
        }
        
        if (showCreatePlaylistDialog) {
            var newPlaylistName by remember { mutableStateOf("") }
            AlertDialog(onDismissRequest = { showCreatePlaylistDialog = false }, title = { Text("Create Playlist") }, text = { OutlinedTextField(value = newPlaylistName, onValueChange = { newPlaylistName = it }, label = { Text("Playlist Name") }, singleLine = true) }, confirmButton = { Button(onClick = { if (newPlaylistName.isNotBlank()) { coroutineScope.launch { db.createPlaylist(PlaylistEntity(name = newPlaylistName.trim())) }; showCreatePlaylistDialog = false } }) { Text("Create") } }, dismissButton = { TextButton(onClick = { showCreatePlaylistDialog = false }) { Text("Cancel") } })
        }
        
        if (showAddToPlaylistDialog && selectedSongForAction != null) {
            val songToAdd = selectedSongForAction!!
            AlertDialog(onDismissRequest = { showAddToPlaylistDialog = false; selectedSongForAction = null }, title = { Text("Select Playlist") }, text = { LazyColumn { if (customPlaylists.isEmpty()) item { Text("No playlists created yet.") }; items(customPlaylists) { playlist -> TextButton(onClick = { coroutineScope.launch { if (db.getSongMemory(songToAdd.id) == null) db.saveSongMemory(SongEntity(songToAdd.id, null, null, null, null, songToAdd.customArtUrl, null, false, 0L)); db.addSongToPlaylist(PlaylistSongCrossRef(playlistId = playlist.playlistId, localMediaId = songToAdd.id)) }; showAddToPlaylistDialog = false; selectedSongForAction = null }, modifier = Modifier.fillMaxWidth()) { Text(playlist.name) } } } }, confirmButton = { TextButton(onClick = { showAddToPlaylistDialog = false; selectedSongForAction = null }) { Text("Cancel") } })
        }
    }
}

@Composable
fun FullScreenPlayer(song: LocalSong, internetData: InternetSongData?, isPlaying: Boolean, currentPosition: Long, totalDuration: Long, isFavorite: Boolean, queueList: List<LocalSong>, memoryMap: Map<Long, SongEntity>, playSong: (LocalSong) -> Unit, onRemoveFromQueue: (Int) -> Unit, onClose: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit, onPrev: () -> Unit, onSeek: (Float) -> Unit, onToggleFavorite: () -> Unit, isLive: Boolean, progress: Float) {
    val displayTitle = internetData?.title ?: song.title; val displayArtist = internetData?.artist ?: song.artist; val displayArt = internetData?.artUrl?.substringBefore("|||") ?: song.customArtUrl?.substringBefore("|||") ?: song.albumArtUri.toString(); var showLyrics by remember { mutableStateOf(false) }; var showQueue by remember { mutableStateOf(false) } 
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})) {
            key(displayArt) { SubcomposeAsyncImage(model = ImageRequest.Builder(LocalContext.current).data(displayArt).crossfade(true).build(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(radius = 60.dp), error = { BlueWhiteFallback(modifier = Modifier.fillMaxSize()) }, loading = { BlueWhiteFallback(modifier = Modifier.fillMaxSize()) }) }
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
            Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(36.dp), tint = Color.White) }
                    Row { 
                        IconButton(onClick = { showQueue = !showQueue; if(showQueue) showLyrics = false }) { Icon(if (showQueue) Icons.Default.QueueMusic else Icons.Default.FormatListBulleted, null, tint = Color.White) }
                        if (internetData?.lyrics != null) IconButton(onClick = { showLyrics = !showLyrics; if(showLyrics) showQueue = false }) { Icon(if (showLyrics) Icons.Default.MusicNote else Icons.Default.Subject, null, tint = Color.White) } 
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (showQueue) {
                        LazyColumn(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(16.dp)).padding(8.dp)) {
                            itemsIndexed(queueList) { idx, queueSong ->
                                val mem = memoryMap[queueSong.id]; val qTitle = mem?.customTitle?.takeIf { it.isNotBlank() } ?: mem?.fetchedTitle?.takeIf { it.isNotBlank() } ?: queueSong.title; val qArtist = mem?.customArtist?.takeIf { it.isNotBlank() } ?: mem?.fetchedArtist?.takeIf { it.isNotBlank() } ?: queueSong.artist
                                Row(modifier = Modifier.fillMaxWidth().clickable { playSong(queueSong) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) { 
                                    Icon(if (queueSong.id == song.id) Icons.Default.PlayArrow else Icons.Default.MusicNote, null, tint = if (queueSong.id == song.id) MaterialTheme.colorScheme.primary else Color.White, modifier = Modifier.size(24.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) { Text(qTitle, fontWeight = if (queueSong.id == song.id) FontWeight.Bold else FontWeight.Normal, color = if (queueSong.id == song.id) MaterialTheme.colorScheme.primary else Color.White, maxLines = 1); Text(qArtist, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f), maxLines = 1) }
                                    IconButton(onClick = { onRemoveFromQueue(idx) }) { Icon(Icons.Default.Close, null, tint = Color.White.copy(alpha=0.5f)) } 
                                }
                            }
                        }
                    } else if (showLyrics && internetData?.lyrics != null) { 
                        Text(text = internetData.lyrics, style = MaterialTheme.typography.titleMedium.copy(lineHeight = 28.sp), color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { showLyrics = false }))
                    } else { 
                        Card(modifier = Modifier.fillMaxWidth().aspectRatio(1f).shadow(24.dp, RoundedCornerShape(32.dp)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { if (internetData?.lyrics != null) showLyrics = true }), shape = RoundedCornerShape(32.dp)) { SubcomposeAsyncImage(model = ImageRequest.Builder(LocalContext.current).data(displayArt).crossfade(1000).build(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), error = { BlueWhiteFallback(modifier = Modifier.fillMaxSize(), iconSize = 80.dp) }, loading = { BlueWhiteFallback(modifier = Modifier.fillMaxSize(), iconSize = 80.dp) }) } 
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { 
                    Column(modifier = Modifier.weight(1f)) { Text(displayTitle, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1); Spacer(modifier = Modifier.height(4.dp)); Text(displayArtist, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.8f), maxLines = 1) }
                    IconButton(onClick = onToggleFavorite) { Icon(if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, modifier = Modifier.size(32.dp), tint = if (isFavorite) Color.Red else Color.White) } 
                }
                Spacer(modifier = Modifier.height(24.dp))
                Slider(value = progress, onValueChange = onSeek, modifier = Modifier.fillMaxWidth(), colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f)))
                val formatTime = { ms: Long -> val totalSeconds = ms / 1000; String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60) }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(if(isLive) "LIVE" else formatTime(currentPosition), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f)); Text(if(isLive) "LIVE" else formatTime(totalDuration), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f)) }
                Spacer(modifier = Modifier.height(32.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) { 
                    IconButton(onClick = onPrev) { Icon(Icons.Default.SkipPrevious, null, modifier = Modifier.size(48.dp), tint = Color.White) }
                    Box(modifier = Modifier.size(80.dp).clip(CircleShape).background(Color.White).clickable { onPlayPause() }, contentAlignment = Alignment.Center) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, modifier = Modifier.size(48.dp), tint = Color.Black) }
                    IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, null, modifier = Modifier.size(48.dp), tint = Color.White) } 
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun PlayerControlsBar(currentSong: LocalSong, internetData: InternetSongData?, isPlaying: Boolean, currentPosition: Long, totalDuration: Long, onPlayPause: () -> Unit, onNext: () -> Unit, onPrev: () -> Unit, onBarClick: () -> Unit) {
    val displayTitle = internetData?.title ?: currentSong.title; val displayArtist = internetData?.artist ?: currentSong.artist; val displayArt = internetData?.artUrl?.substringBefore("|||") ?: currentSong.customArtUrl?.substringBefore("|||") ?: currentSong.albumArtUri.toString()
    val isLive = totalDuration == C.TIME_UNSET || totalDuration <= 0L
    val progress = if (!isLive && totalDuration > 0) (currentPosition.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f) else 0f

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().clickable { onBarClick() }, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), shadowElevation = 8.dp) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(2.dp), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SubcomposeAsyncImage(model = ImageRequest.Builder(LocalContext.current).data(displayArt).crossfade(true).build(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)), error = { BlueWhiteFallback(modifier = Modifier.fillMaxSize(), iconSize = 20.dp) }, loading = { BlueWhiteFallback(modifier = Modifier.fillMaxSize(), iconSize = 20.dp) })
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) { Text(displayTitle, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1); Text(displayArtist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), maxLines = 1) }
                Row { IconButton(onClick = onPrev) { Icon(Icons.Default.SkipPrevious, null, tint = MaterialTheme.colorScheme.primary) }; IconButton(onClick = onPlayPause) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp)) }; IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, null, tint = MaterialTheme.colorScheme.primary) } }
            }
        }
    }
}

fun fetchLocalMusic(context: Context): List<LocalSong> {
    val songs = mutableListOf<LocalSong>()
    val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM_ID)
    context.contentResolver.query(uri, proj, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} ASC")?.use { cursor ->
        val idC = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID); val tC = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE); val aC = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST); val alC = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        while (cursor.moveToNext()) {
            val title = cursor.getString(tC) ?: "Unknown"
            if (!title.matches(Regex("(?i)(.*\\d{8}.*|.*aud-.*|.*ptt-.*|.*wa00.*|.*record.*)"))) songs.add(LocalSong(cursor.getLong(idC), title, cursor.getString(aC) ?: "Unknown Artist", cursor.getLong(alC)))
        }
    }
    return songs
}

private fun fetchHttp(urlStr: String): String? {
    return try {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.connectTimeout = 4000; conn.readTimeout = 4000
        if (conn.responseCode == 200) conn.inputStream.bufferedReader().readText() else null
    } catch (e: Exception) { null }
}

suspend fun getPrivacyMaskedLanguage(): String = withContext(Dispatchers.IO) {
    try {
        var dummyIp = ""
        val myIpRes = fetchHttp("https://api4.ipify.org")
        if (myIpRes != null && myIpRes.contains(".")) {
            dummyIp = myIpRes.substringBeforeLast(".") + ".12" 
        }
        
        val geoUrl = if (dummyIp.isNotBlank()) "https://ipwho.is/$dummyIp" else "https://ipwho.is/"
        val res = fetchHttp(geoUrl)
        
        if (res != null) {
            val json = JSONObject(res)
            val country = json.optString("country", "")
            val region = json.optString("region", "")
            
            if (country.equals("India", true)) {
                if (region.contains("Tamil", true)) return@withContext "Tamil"
                if (region.contains("Karnataka", true)) return@withContext "Kannada"
                if (region.contains("Kerala", true)) return@withContext "Malayalam"
                if (region.contains("Andhra", true) || region.contains("Telangana", true)) return@withContext "Telugu"
                if (region.contains("Maharashtra", true)) return@withContext "Marathi"
                if (region.contains("Gujarat", true)) return@withContext "Gujarati"
                if (region.contains("Bengal", true)) return@withContext "Bengali"
                if (region.contains("Punjab", true)) return@withContext "Punjabi"
                return@withContext "Hindi"
            } else if (country.isNotBlank() && !country.equals("United States", true) && !country.equals("United Kingdom", true)) {
                return@withContext country
            }
        }
    } catch (e: Exception) {}
    
    return@withContext "Tamil" 
}

suspend fun fetchLiveSearchResults(query: String, language: String, isPlaylistSearch: Boolean): List<InternetSongData> = coroutineScope {
    val results = mutableListOf<InternetSongData>()

    val saavnTask = async(Dispatchers.IO) {
        val saavnList = mutableListOf<InternetSongData>()
        if (!isPlaylistSearch) {
            try {
                val qEncoded = URLEncoder.encode(query, "UTF-8")
                val res = fetchHttp("https://www.jiosaavn.com/api.php?__call=autocomplete.get&_format=json&_marker=0&cc=in&query=$qEncoded")
                if (res != null) {
                    val arr = JSONObject(res).optJSONObject("songs")?.optJSONArray("data")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val t = arr.getJSONObject(i); val info = t.optJSONObject("more_info")
                            saavnList.add(InternetSongData(t.optString("id", ""), t.optString("title", "").replace("&quot;", "\"").replace("&amp;", "&"), info?.optString("singers", "") ?: info?.optString("primary_artists", "") ?: "", t.optString("image", "").replace("50x50.jpg", "500x500.jpg")))
                        }
                    }
                }
            } catch (e: Exception) {}
        }
        saavnList
    }

    val archiveTask = async(Dispatchers.IO) {
        val iaList = mutableListOf<InternetSongData>()
        if (isPlaylistSearch) {
            try {
                val qBase = if (query.isBlank()) "subject:\"anime\" OR subject:\"soundtrack\"" else "($query)"
                val exactQuery = "$qBase AND mediatype:audio AND NOT subject:\"news\" AND NOT subject:\"podcast\" AND NOT subject:\"radio\" AND NOT creator:\"voa\" AND NOT collection:\"voa\" AND NOT title:\"voa\" AND NOT title:\"news\" AND NOT creator:\"Voice of America\""
                val res = fetchHttp("https://archive.org/advancedsearch.php?q=${URLEncoder.encode(exactQuery, "UTF-8")}&fl[]=identifier,title,creator&rows=15&output=json")
                if (res != null) {
                    val docs = JSONObject(res).optJSONObject("response")?.optJSONArray("docs")
                    if (docs != null) {
                        for (i in 0 until docs.length()) {
                            val t = docs.getJSONObject(i); val id = t.optString("identifier", "")
                            val title = t.optString("title", "Unknown Album")
                            val creator = t.optString("creator", "Archive OST")
                            
                            if (id.isNotBlank() && !title.contains("VOA", true) && !creator.contains("Voice of America", true)) {
                                iaList.add(InternetSongData("ia:$id", title, "[Full Album] $creator", "https://archive.org/services/img/$id"))
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
        }
        iaList
    }

    val pipedTask = async(Dispatchers.IO) {
        val pipedList = mutableListOf<InternetSongData>()
        if (!isPlaylistSearch && query.isNotBlank()) {
            try {
                val res = fetchHttp("https://pipedapi.kavin.rocks/search?q=${URLEncoder.encode(query, "UTF-8")}&filter=music_songs")
                if (res != null) {
                    val items = JSONObject(res).optJSONArray("items")
                    if (items != null) {
                        for (i in 0 until minOf(items.length(), 10)) {
                            val t = items.getJSONObject(i)
                            if (t.optString("type") == "stream") {
                                val url = t.optString("url", "")
                                val vid = url.replace("/watch?v=", "").substringBefore("&")
                                if (vid.isNotBlank()) pipedList.add(InternetSongData("yt:$vid", t.optString("title", ""), t.optString("uploaderName", ""), t.optString("thumbnail", "")))
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
        }
        pipedList
    }

    results.addAll(saavnTask.await()); results.addAll(archiveTask.await()); results.addAll(pipedTask.await())
    return@coroutineScope results.distinctBy { it.title.lowercase() }
}

suspend fun fetchAudioStreamUrl(title: String, artist: String, songId: String = ""): String? = withContext(Dispatchers.IO) {
    val isDummyId = songId.toLongOrNull() != null && songId.toLong() < 0
    val safeSongId = if (isDummyId) "" else songId

    if (safeSongId.startsWith("yt:")) {
        val vid = safeSongId.removePrefix("yt:")
        val pipedInstances = listOf("pipedapi.kavin.rocks", "pipedapi.tokhmi.xyz", "piped-api.garudalinux.org", "piped.projectsegfau.lt", "pipedapi.smnz.de")
        for (instance in pipedInstances) {
            try {
                val streamRes = fetchHttp("https://$instance/streams/$vid") ?: continue
                val audioStreams = JSONObject(streamRes).optJSONArray("audioStreams")
                var bestUrl: String? = null; var highestBitrate = 0
                if (audioStreams != null) {
                    for (i in 0 until audioStreams.length()) {
                        val stream = audioStreams.getJSONObject(i)
                        val format = stream.optString("format", "").lowercase(); val mime = stream.optString("mimeType", "").lowercase(); val bitrate = stream.optInt("bitrate", 0)
                        if (format.contains("m4a") || format.contains("webm") || mime.contains("audio")) { 
                            if (bitrate >= highestBitrate) { highestBitrate = bitrate; bestUrl = stream.optString("url", "") } 
                        }
                    }
                }
                if (bestUrl == null) { val hls = JSONObject(streamRes).optString("hls", ""); if (hls.isNotBlank()) bestUrl = hls }
                if (!bestUrl.isNullOrEmpty()) return@withContext bestUrl
            } catch (e: Exception) {}
        }
    }

    if (safeSongId.isNotBlank() && !safeSongId.startsWith("ia:") && !safeSongId.startsWith("yt:")) {
        val idApis = listOf("https://saavn.dev/api/songs?ids=", "https://saavn.sumit.co/api/songs?ids=")
        for (api in idApis) {
            try {
                val res = fetchHttp("$api$safeSongId")
                if (res != null) {
                    val json = JSONObject(res)
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
            } catch (e: Exception) {}
        }
    }

    val cleanTitle = title.replace(Regex("\\(.*?\\)"), "").replace(Regex("\\[.*?\\]"), "").trim()
    val ytQuery = URLEncoder.encode("$cleanTitle $artist official audio", "UTF-8")
    
    val pipedInstances = listOf("pipedapi.kavin.rocks", "pipedapi.tokhmi.xyz", "piped-api.garudalinux.org", "piped.projectsegfau.lt", "pipedapi.smnz.de")
    for (instance in pipedInstances) {
        try {
            val searchRes = fetchHttp("https://$instance/search?q=$ytQuery&filter=all") ?: continue
            val items = JSONObject(searchRes).optJSONArray("items") ?: continue
            var videoId = ""
            for (i in 0 until items.length()) { if (items.getJSONObject(i).optString("type") == "stream") { videoId = items.getJSONObject(i).optString("url", "").replace("/watch?v=", "").substringBefore("&"); break } }
            if (videoId.isEmpty()) continue
            
            val streamRes = fetchHttp("https://$instance/streams/$videoId") ?: continue
            val audioStreams = JSONObject(streamRes).optJSONArray("audioStreams") ?: continue
            var bestUrl: String? = null; var highestBitrate = 0
            for (i in 0 until audioStreams.length()) {
                val stream = audioStreams.getJSONObject(i)
                val format = stream.optString("format", "").lowercase(); val mimeType = stream.optString("mimeType", "").lowercase(); val bitrate = stream.optInt("bitrate", 0)
                if (format.contains("m4a") || format.contains("webm") || mimeType.contains("audio")) { if (bitrate >= highestBitrate) { highestBitrate = bitrate; bestUrl = stream.optString("url", "") } }
            }
            if (!bestUrl.isNullOrEmpty()) return@withContext bestUrl
        } catch(e: Exception) {}
    }
    return@withContext null
}

suspend fun fetchMultiSourceMetadata(title: String, artist: String): InternetSongData? = coroutineScope {
    val cleanTitle = title.lowercase().replace(".mp3", "").replace(".m4a", "").replace(".wav", "").replace("y2mate.com", "").replace("y2mate", "").replace("official video", "").replace("official audio", "").replace("lyrics", "").replace("hd", "").replace("slowed", "").replace("reverb", "").replace(Regex("\\b\\d{2,4}[-/_]\\d{2}[-/_]\\d{2,4}\\b"), "").replace(Regex("\\b\\d{6,10}\\b"), "").replace(Regex("[^a-zA-Z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
    val isUnknownArtist = artist.contains("unknown", ignoreCase = true)
    
    val t1 = async(Dispatchers.IO) { try { if (!isUnknownArtist) fetchHttp("https://itunes.apple.com/search?term=${URLEncoder.encode("$cleanTitle $artist", "UTF-8")}&media=music&entity=song&limit=1")?.let { JSONObject(it).optJSONArray("results")?.optJSONObject(0)?.let { t -> InternetSongData("", t.optString("trackName", ""), t.optString("artistName", ""), t.optString("artworkUrl100", "").replace("100x100bb", "600x600bb")) } } else null } catch(e:Exception){null} }
    val t2 = async(Dispatchers.IO) { try { if (!isUnknownArtist) fetchHttp("https://www.jiosaavn.com/api.php?__call=autocomplete.get&_format=json&_marker=0&cc=in&query=${URLEncoder.encode("$cleanTitle $artist", "UTF-8")}")?.let { JSONObject(it).optJSONObject("songs")?.optJSONArray("data")?.optJSONObject(0)?.let { t -> InternetSongData("", t.optString("title", "").replace("&quot;", "\"").replace("&amp;", "&"), t.optJSONObject("more_info")?.optString("primary_artists", "") ?: "", t.optString("image", "").replace("50x50.jpg", "500x500.jpg")) } } else null } catch(e:Exception){null} }
    val t3 = async(Dispatchers.IO) { searchLyricsAPI(cleanTitle, if (!isUnknownArtist) artist else "") }

    var result = t1.await() ?: t2.await()
    var lyrics = t3.await()

    if (result == null && isUnknownArtist) {
        val l1 = async(Dispatchers.IO) { try { fetchHttp("https://itunes.apple.com/search?term=${URLEncoder.encode(cleanTitle, "UTF-8")}&media=music&entity=song&limit=1")?.let { JSONObject(it).optJSONArray("results")?.optJSONObject(0)?.let { t -> InternetSongData("", t.optString("trackName", ""), t.optString("artistName", ""), t.optString("artworkUrl100", "").replace("100x100bb", "600x600bb")) } } } catch(e:Exception){null} }
        val l2 = async(Dispatchers.IO) { try { fetchHttp("https://www.jiosaavn.com/api.php?__call=autocomplete.get&_format=json&_marker=0&cc=in&query=${URLEncoder.encode(cleanTitle, "UTF-8")}")?.let { JSONObject(it).optJSONObject("songs")?.optJSONArray("data")?.optJSONObject(0)?.let { t -> InternetSongData("", t.optString("title", "").replace("&quot;", "\"").replace("&amp;", "&"), t.optJSONObject("more_info")?.optString("primary_artists", "") ?: "", t.optString("image", "").replace("50x50.jpg", "500x500.jpg")) } } } catch(e:Exception){null} }
        result = l1.await() ?: l2.await()
    }
    
    if (lyrics == null && !isUnknownArtist) lyrics = searchLyricsAPI(cleanTitle, "")
    if (result != null) return@coroutineScope result.copy(lyrics = lyrics)
    else if (lyrics != null) return@coroutineScope InternetSongData("", cleanTitle, artist, "", lyrics)
    return@coroutineScope null
}

private fun searchLyricsAPI(title: String, artist: String): String? {
    try {
        val res = fetchHttp("https://lrclib.net/api/search?q=${URLEncoder.encode(if (artist.isNotBlank()) "$title $artist" else title, "UTF-8")}") ?: return null
        val arr = JSONArray(res)
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i); val p = t.optString("plainLyrics", ""); val s = t.optString("syncedLyrics", "")
            if (p.isNotBlank() && p != "null") return p
            if (s.isNotBlank() && s != "null") return s.replace(Regex("\\[.*?\\]"), "").trim() 
        }
    } catch(e: Exception) {}
    return null
}
