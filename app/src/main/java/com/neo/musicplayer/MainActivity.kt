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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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

// --- IN-MEMORY CACHE ---
object AppCache {
    val searchResults = mutableMapOf<String, List<InternetSongData>>()
    val streamUrls = mutableMapOf<String, String>()
}

// MINIMALIST MONOCHROME THEME
private val MinBgDark = Color(0xFF0A0A0A)
private val MinSurfaceDark = Color(0xFF141414)
private val MinTextDark = Color(0xFFF5F5F5)
private val MinPrimaryDark = Color(0xFFFFFFFF)

@Composable
fun AestheticTheme(content: @Composable () -> Unit) {
    val colorScheme = darkColorScheme(
        primary = MinPrimaryDark,
        background = MinBgDark,
        surface = MinSurfaceDark,
        onSurface = MinTextDark
    )
    MaterialTheme(colorScheme = colorScheme, content = content)
}

@Composable
fun FallbackIcon(modifier: Modifier = Modifier, iconSize: Dp = 48.dp) {
    Box(modifier = modifier.background(Color.Gray.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) { 
        Icon(Icons.Default.MusicNote, contentDescription = "Music", tint = Color.Gray.copy(alpha = 0.6f), modifier = Modifier.size(iconSize)) 
    }
}

data class LocalSong(val id: Long, val title: String, val artist: String, val albumId: Long, val webStreamUrl: String? = null, val customArtUrl: String? = null) { 
    val albumArtUri: Uri get() = if(customArtUrl != null) Uri.parse(customArtUrl.substringBefore("|||")) else Uri.parse("content://media/external/audio/albumart/$albumId") 
}

data class InternetSongData(val id: String = "", val title: String, val artist: String, val artUrl: String, val lyrics: String? = null)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { 
        super.onCreate(savedInstanceState)
        setContent { AestheticTheme { MusicPlayerUI() } } 
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(songId: Long, title: String, artist: String, artUrl: String, isPlaying: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, onAddQueue: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        SubcomposeAsyncImage(model = artUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(52.dp).clip(RoundedCornerShape(6.dp)), error = { FallbackIcon() }, loading = { FallbackIcon() })
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium, fontSize = 16.sp, maxLines = 1, color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(2.dp))
            Text(artist, style = MaterialTheme.typography.bodySmall, fontSize = 13.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        if (songId < 0) Icon(Icons.Default.CloudDownload, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f), modifier = Modifier.size(20.dp))
        IconButton(onClick = onAddQueue) { Icon(Icons.Default.Add, contentDescription = "Add", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)) }
    }
}

@Composable
fun MiniPlayer(s: LocalSong, w: InternetSongData?, isP: Boolean, pos: Long, dur: Long, onPP: () -> Unit, onN: () -> Unit, onC: () -> Unit) {
    val t = w?.title ?: s.title
    val a = w?.artist ?: s.artist
    val img = w?.artUrl?.substringBefore("|||") ?: s.customArtUrl?.substringBefore("|||") ?: s.albumArtUri.toString()
    
    Surface(modifier = Modifier.fillMaxWidth().padding(12.dp, 8.dp).clickable(onClick = onC), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
        Column {
            LinearProgressIndicator(progress = { if (dur > 0) (pos.toFloat()/dur).coerceIn(0f,1f) else 0f }, modifier = Modifier.fillMaxWidth().height(2.dp), color = MaterialTheme.colorScheme.onSurface, trackColor = Color.Transparent)
            Row(modifier = Modifier.padding(12.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SubcomposeAsyncImage(model = ImageRequest.Builder(LocalContext.current).data(img).crossfade(true).build(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)), error = { FallbackIcon(modifier = Modifier.size(20.dp)) })
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) { 
                    Text(t, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(a, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis) 
                }
                IconButton(onClick = onPP) { Icon(if (isP) Icons.Default.Pause else Icons.Default.PlayArrow, null, modifier = Modifier.size(32.dp)) }
                IconButton(onClick = onN) { Icon(Icons.Default.SkipNext, null) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicPlayerUI() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context).libraryDao() }
    
    val songMemories by db.getAllSongMemories().collectAsState(initial = emptyList())
    val memoryMap = remember(songMemories) { songMemories.associateBy { it.localMediaId } }
    val favoriteMemories by db.getFavoriteSongs().collectAsState(initial = emptyList())
    val recentlyPlayedMemories by db.getRecentlyPlayed().collectAsState(initial = emptyList())
    val customPlaylists by db.getAllPlaylists().collectAsState(initial = emptyList())
    
    val sharedPrefs = remember { context.getSharedPreferences("NeoMusicPrefs", Context.MODE_PRIVATE) }
    
    var localSongs by remember { mutableStateOf<List<LocalSong>>(emptyList()) }
    val favoriteSongs = remember(favoriteMemories, localSongs) { favoriteMemories.mapNotNull { mem -> if (mem.localMediaId >= 0) localSongs.find { it.id == mem.localMediaId } else LocalSong(mem.localMediaId, mem.fetchedTitle ?: mem.customTitle ?: "Unknown", mem.fetchedArtist ?: mem.customArtist ?: "Unknown", -1L, null, mem.fetchedArtUrl) } }
    val recentlyPlayedSongs = remember(recentlyPlayedMemories, localSongs) { recentlyPlayedMemories.mapNotNull { mem -> if (mem.localMediaId >= 0) localSongs.find { it.id == mem.localMediaId } else LocalSong(mem.localMediaId, mem.fetchedTitle ?: mem.customTitle ?: "Unknown", mem.fetchedArtist ?: mem.customArtist ?: "Unknown", -1L, null, mem.fetchedArtUrl) } }
    
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var currentTab by remember { mutableIntStateOf(0) } 
    var viewingLikedSongs by remember { mutableStateOf(false) }
    var viewingPlaylistId by remember { mutableStateOf<Long?>(null) }
    var currentPlaylistData by remember { mutableStateOf<PlaylistWithSongs?>(null) }
    
    LaunchedEffect(viewingPlaylistId) { viewingPlaylistId?.let { id -> currentPlaylistData = db.getPlaylistWithSongs(id) } }

    val languages = listOf("All", "Tamil", "English", "Hindi", "Malayalam", "Telugu", "Kannada", "Marathi", "Bengali", "Punjabi", "Gujarati")
    var selectedLanguage by remember { mutableStateOf(sharedPrefs.getString("saved_lang", "") ?: "") } 
    var isPlaylistMode by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (selectedLanguage.isBlank()) {
            val detectedLang = getAutoLanguage()
            selectedLanguage = if (languages.contains(detectedLang)) detectedLang else "All"
            sharedPrefs.edit().putString("saved_lang", selectedLanguage).apply()
        }
    }

    var liveSearchResults by remember { mutableStateOf<List<InternetSongData>>(emptyList()) }
    var isLiveSearching by remember { mutableStateOf(false) }

    LaunchedEffect(searchQuery, selectedLanguage, isPlaylistMode, isSearchActive) {
        if (!isSearchActive) return@LaunchedEffect
        
        val activeLang = if (isPlaylistMode) "All" else selectedLanguage
        val q = if (searchQuery.isBlank()) { 
            if (activeLang == "All" || activeLang.isBlank()) "Top Hits" else "$activeLang Hits" 
        } else { 
            if (activeLang == "All" || activeLang.isBlank()) searchQuery else "$searchQuery $activeLang" 
        }
        
        val cacheKey = "${q}_${isPlaylistMode}"
        if (AppCache.searchResults.containsKey(cacheKey)) {
            liveSearchResults = AppCache.searchResults[cacheKey]!!
            isLiveSearching = false
            return@LaunchedEffect
        }

        delay(400)
        isLiveSearching = true
        
        val freshData = fetchLiveSearchResults(q, searchQuery, isPlaylistMode)
        
        if (freshData.isNotEmpty()) {
            liveSearchResults = freshData
            AppCache.searchResults[cacheKey] = freshData
        }
        isLiveSearching = false
    }

    var currentSong by remember { mutableStateOf<LocalSong?>(null) }
    var fetchedInternetData by remember { mutableStateOf<InternetSongData?>(null) } 
    var selectedSongForAction by remember { mutableStateOf<LocalSong?>(null) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showAddToPlaylistDialog by remember { mutableStateOf(false) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var playQueue by remember { mutableStateOf<List<LocalSong>>(emptyList()) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableStateOf(0L) }
    var totalDuration by remember { mutableStateOf(0L) }
    var isLiveStream by remember { mutableStateOf(false) }
    var showFullScreenPlayer by remember { mutableStateOf(false) }
    var autoPlayContext by remember { mutableStateOf<List<InternetSongData>>(emptyList()) }
    var autoPlayIndex by remember { mutableIntStateOf(-1) }

    val exoPlayer = remember {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory().setUserAgent("Mozilla/5.0").setAllowCrossProtocolRedirects(true)
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(DefaultDataSource.Factory(context, httpDataSourceFactory))).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            repeatMode = Player.REPEAT_MODE_OFF 
        }
    }
    
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    fun playSongList(song: LocalSong, sourceList: List<LocalSong>) {
        autoPlayContext = emptyList()
        autoPlayIndex = -1 
        playQueue = sourceList
        val idx = sourceList.indexOf(song)
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        exoPlayer.setMediaItems(sourceList.map { s -> MediaItem.Builder().setUri(s.webStreamUrl ?: ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, s.id).toString()).setMediaId(s.id.toString()).build() })
        if (idx >= 0) exoPlayer.seekTo(idx, C.TIME_UNSET)
        exoPlayer.prepare()
        exoPlayer.play()
    }

    fun playWebSong(webSongData: InternetSongData) {
        coroutineScope.launch {
            if (webSongData.id.startsWith("ia:")) {
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
                                    val f = files.getJSONObject(i)
                                    val format = f.optString("format", "").lowercase()
                                    val rawName = f.optString("name", "")
                                    val lowerName = rawName.lowercase()
                                    if (lowerName.endsWith(".xml") || lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") || lowerName.endsWith(".png") || lowerName.endsWith(".sqlite") || lowerName.endsWith(".txt")) continue
                                    if (format.contains("mp3") || format.contains("flac") || format.contains("ogg") || lowerName.endsWith(".mp3") || lowerName.endsWith(".m4a")) {
                                        val baseName = rawName.substringBeforeLast(".")
                                        if (trackMap[baseName] == null || format.contains("mp3")) trackMap[baseName] = f
                                    }
                                }
                                val sortedTracks = trackMap.values.sortedWith(compareBy({ it.optString("track", "999").replace(Regex("[^0-9]"), "").toIntOrNull() ?: 999 }, { it.optString("name") }))
                                for (f in sortedTracks) {
                                    val trackTitle = f.optString("title").takeIf { it.isNotBlank() } ?: f.optString("name").substringBeforeLast(".")
                                    val trackArtist = f.optString("creator").takeIf { it.isNotBlank() } ?: webSongData.artist.replace("[Album] ", "")
                                    val dummyId = generateDummyId(trackTitle, trackArtist)
                                    list.add(LocalSong(dummyId, trackTitle, trackArtist, -1L, "https://archive.org/download/$id/${Uri.encode(f.optString("name"))}", "${webSongData.artUrl}|||ia:$id"))
                                }
                            }
                        }
                    } catch (e: Exception) {}
                    list
                }
                
                if (iaPlaylist.isNotEmpty()) {
                    val parentId = generateDummyId(webSongData.title, webSongData.artist)
                    withContext(Dispatchers.IO) { 
                        db.saveSongMemory(SongEntity(parentId, webSongData.title, webSongData.artist, webSongData.title, webSongData.artist, "${webSongData.artUrl}|||${webSongData.id}", null, memoryMap[parentId]?.isFavorite ?: false, System.currentTimeMillis())) 
                    }
                    fetchedInternetData = webSongData
                    playSongList(iaPlaylist.first(), iaPlaylist)
                    showFullScreenPlayer = true
                }
                return@launch
            }

            // UNICODE SAFE MATCHING: No Regex stripping!
            val cTitle = webSongData.title.lowercase().trim()
            val localMatch = if (cTitle.isNotBlank()) localSongs.find { it.title.lowercase().trim().let { n -> n == cTitle || n.contains(cTitle) || cTitle.contains(n) } } else null
            
            if (localMatch != null) { 
                playSongList(localMatch, localSongs)
                showFullScreenPlayer = true
                return@launch 
            }
            
            val streamUrl = fetchAudioStreamUrl(webSongData.title, webSongData.artist, webSongData.id)
            if (streamUrl != null) {
                val dummyId = generateDummyId(webSongData.title, webSongData.artist)
                val dummySong = LocalSong(dummyId, webSongData.title, webSongData.artist, -1L, streamUrl, "${webSongData.artUrl}|||${webSongData.id}")
                
                withContext(Dispatchers.IO) { 
                    db.saveSongMemory(SongEntity(dummyId, webSongData.title, webSongData.artist, webSongData.title, webSongData.artist, "${webSongData.artUrl}|||${webSongData.id}", null, memoryMap[dummyId]?.isFavorite ?: false, System.currentTimeMillis())) 
                }
                fetchedInternetData = webSongData
                playQueue = listOf(dummySong)
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
                exoPlayer.setMediaItem(MediaItem.Builder().setUri(streamUrl).setMediaId(dummyId.toString()).build())
                exoPlayer.prepare()
                exoPlayer.play()
                showFullScreenPlayer = true
            } else {
                Toast.makeText(context, "Stream unavailable. Skipping...", Toast.LENGTH_SHORT).show()
                if (autoPlayContext.isNotEmpty() && autoPlayIndex in 0 until autoPlayContext.size - 1) { 
                    autoPlayIndex++
                    playWebSong(autoPlayContext[autoPlayIndex]) 
                }
            }
        }
    }

    fun handleNext() {
        if (exoPlayer.mediaItemCount > 1) {
            exoPlayer.seekToNextMediaItem()
        } else if (autoPlayContext.isNotEmpty() && autoPlayIndex >= 0 && autoPlayIndex < autoPlayContext.size - 1) {
            autoPlayIndex++
            playWebSong(autoPlayContext[autoPlayIndex])
        }
    }

    fun handlePrev() {
        if (exoPlayer.mediaItemCount > 1) {
            exoPlayer.seekToPreviousMediaItem()
        } else if (autoPlayContext.isNotEmpty() && autoPlayIndex > 0) {
            autoPlayIndex--
            playWebSong(autoPlayContext[autoPlayIndex])
        } else {
            exoPlayer.seekTo(0L)
        }
    }

    fun handleAddQueue(s: LocalSong, w: InternetSongData?) {
        coroutineScope.launch {
            if (s.id < 0) {
                val url = fetchAudioStreamUrl(s.title, s.artist, w?.id ?: s.customArtUrl?.substringAfter("|||") ?: "")
                if (url != null) {
                    val ns = s.copy(webStreamUrl = url)
                    playQueue = playQueue + ns
                    exoPlayer.addMediaItem(MediaItem.fromUri(url))
                    if (!isPlaying && exoPlayer.mediaItemCount == 1) { exoPlayer.prepare(); exoPlayer.play() }
                    Toast.makeText(context, "Added to Queue", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Failed to load track", Toast.LENGTH_SHORT).show()
                }
            } else {
                playQueue = playQueue + s
                exoPlayer.addMediaItem(MediaItem.fromUri(s.albumArtUri))
                if (!isPlaying && exoPlayer.mediaItemCount == 1) { exoPlayer.prepare(); exoPlayer.play() }
                Toast.makeText(context, "Added to Queue", Toast.LENGTH_SHORT).show()
            }
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener { 
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlaybackStateChanged(state: Int) { 
                if (state == Player.STATE_ENDED) {
                    if (autoPlayContext.isNotEmpty() && autoPlayIndex in 0 until autoPlayContext.size - 1) { 
                        autoPlayIndex++
                        playWebSong(autoPlayContext[autoPlayIndex]) 
                    } else if ((currentSong?.id ?: 0L) < 0L) {
                        coroutineScope.launch {
                            val cSong = currentSong
                            if (cSong != null) {
                                val recQuery = "${cSong.artist} Hits"
                                val recs = fetchLiveSearchResults(recQuery, recQuery, false)
                                if (recs.isNotEmpty()) { 
                                    val nextSong = recs[0]
                                    withContext(Dispatchers.Main) { Toast.makeText(context, "Up next: ${nextSong.title}", Toast.LENGTH_LONG).show() }
                                    autoPlayContext = recs
                                    autoPlayIndex = 0
                                    playWebSong(nextSong) 
                                }
                            }
                        }
                    }
                }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { 
                if (mediaItem == null || exoPlayer.mediaItemCount == 0) { 
                    currentSong = null
                    isPlaying = false
                    showFullScreenPlayer = false 
                } else if (exoPlayer.currentMediaItemIndex in playQueue.indices) {
                    currentSong = playQueue[exoPlayer.currentMediaItemIndex] 
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            currentPosition = exoPlayer.currentPosition
            val d = exoPlayer.duration
            totalDuration = if (d == C.TIME_UNSET) 0L else d
            isLiveStream = exoPlayer.isCurrentMediaItemLive || (d == C.TIME_UNSET && exoPlayer.playbackState == Player.STATE_READY)
            delay(500L)
        }
    }

    LaunchedEffect(currentSong) {
        val song = currentSong ?: return@LaunchedEffect
        if (song.id >= 0L) return@LaunchedEffect 
        
        val mem = db.getSongMemory(song.id)
        val dTitle = mem?.customTitle?.takeIf { it.isNotBlank() } ?: mem?.fetchedTitle?.takeIf { it.isNotBlank() } ?: song.title
        val dArtist = mem?.customArtist?.takeIf { it.isNotBlank() } ?: mem?.fetchedArtist?.takeIf { it.isNotBlank() } ?: song.artist
        
        fetchedInternetData = InternetSongData("", dTitle, dArtist, mem?.fetchedArtUrl ?: "", mem?.fetchedLyrics)
        db.saveSongMemory(SongEntity(song.id, mem?.customTitle, mem?.customArtist, mem?.fetchedTitle ?: dTitle, mem?.fetchedArtist ?: dArtist, mem?.fetchedArtUrl, mem?.fetchedLyrics, mem?.isFavorite ?: false, System.currentTimeMillis()))
        
        if (mem?.fetchedArtUrl == null) { 
            val res = fetchMultiSourceMetadata(dTitle, dArtist)
            if (res != null) { 
                fetchedInternetData = res
                db.saveSongMemory(SongEntity(song.id, mem?.customTitle, mem?.customArtist, res.title, res.artist, res.artUrl, res.lyrics, mem?.isFavorite ?: false, System.currentTimeMillis())) 
            } 
        }
    }
    
    var permissionGranted by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted -> 
        permissionGranted = isGranted
        if (isGranted) localSongs = fetchLocalMusic(context)
    }
    
    LaunchedEffect(Unit) { 
        launcher.launch(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE) 
    }

    BackHandler(enabled = showFullScreenPlayer || viewingLikedSongs || viewingPlaylistId != null) { 
        if (showFullScreenPlayer) showFullScreenPlayer = false 
        else if (viewingLikedSongs) viewingLikedSongs = false 
        else if (viewingPlaylistId != null) viewingPlaylistId = null 
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = { 
                TopAppBar(
                    title = { 
                        if (isSearchActive) { 
                            TextField(value = searchQuery, onValueChange = { searchQuery = it }, placeholder = { Text("Search...") }, singleLine = true, colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent)) 
                        } else { 
                            Text(when(currentTab) { 0 -> "Dashboard"; 1 -> "Discover"; 2 -> "Library"; 3 -> "Radio"; else -> "Offline" }, fontWeight = FontWeight.Bold) 
                        } 
                    }, 
                    actions = { 
                        IconButton(onClick = { isSearchActive = !isSearchActive; if (!isSearchActive) searchQuery = "" }) { 
                            Icon(if (isSearchActive) Icons.Default.Close else Icons.Default.Search, contentDescription = null) 
                        } 
                    }, 
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.95f))
                ) 
            },
            bottomBar = { 
                Column {
                    if (currentSong != null) {
                        MiniPlayer(s = currentSong!!, w = fetchedInternetData, isP = isPlaying, pos = currentPosition, dur = totalDuration, onPP = { if (isPlaying) exoPlayer.pause() else exoPlayer.play() }, onN = { handleNext() }, onC = { showFullScreenPlayer = true })
                    }
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                        NavigationBarItem(icon = { Icon(Icons.Default.Home, null) }, label = { Text("Home", fontSize = 10.sp) }, selected = currentTab == 0 && !isSearchActive, onClick = { currentTab = 0; isSearchActive = false; viewingLikedSongs = false; viewingPlaylistId = null })
                        NavigationBarItem(icon = { Icon(Icons.Default.Search, null) }, label = { Text("Search", fontSize = 10.sp) }, selected = isSearchActive, onClick = { isSearchActive = true; currentTab = 1 })
                        NavigationBarItem(icon = { Icon(Icons.Default.QueueMusic, null) }, label = { Text("Library", fontSize = 10.sp) }, selected = currentTab == 2 && !isSearchActive, onClick = { currentTab = 2; isSearchActive = false })
                        NavigationBarItem(icon = { Icon(Icons.Default.Radio, null) }, label = { Text("Radio", fontSize = 10.sp) }, selected = currentTab == 3 && !isSearchActive, onClick = { currentTab = 3; isSearchActive = false; viewingLikedSongs = false; viewingPlaylistId = null })
                        NavigationBarItem(icon = { Icon(Icons.Default.Folder, null) }, label = { Text("Offline", fontSize = 10.sp) }, selected = currentTab == 4 && !isSearchActive, onClick = { currentTab = 4; isSearchActive = false; viewingLikedSongs = false; viewingPlaylistId = null })
                    }
                }
            }, containerColor = MaterialTheme.colorScheme.background
        ) { paddingValues ->
            Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                if (!permissionGranted) { 
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Permission required.") } 
                } else if (isSearchActive) {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !isPlaylistMode, onClick = { isPlaylistMode = false }, label = { Text("Tracks") }, shape = RoundedCornerShape(20.dp))
                            FilterChip(selected = isPlaylistMode, onClick = { isPlaylistMode = true }, label = { Text("Albums") }, shape = RoundedCornerShape(20.dp))
                        }
                        
                        AnimatedVisibility(visible = !isPlaylistMode) {
                            LazyRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(languages) { lang -> 
                                    FilterChip(selected = selectedLanguage == lang, onClick = { selectedLanguage = lang; sharedPrefs.edit().putString("saved_lang", lang).apply() }, label = { Text(lang) }, shape = RoundedCornerShape(20.dp)) 
                                }
                            }
                        }
                    }
                    if (isLiveSearching) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurface) }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(liveSearchResults) { song -> 
                                TrackRow(
                                    songId = -1L, title = song.title, artist = song.artist, artUrl = song.artUrl, isPlaying = false, 
                                    onClick = { autoPlayContext = liveSearchResults; autoPlayIndex = liveSearchResults.indexOf(song); playWebSong(song) }, 
                                    onLongClick = { selectedSongForAction = LocalSong(generateDummyId(song.title, song.artist), song.title, song.artist, -1L, null, "${song.artUrl}|||${song.id}"); fetchedInternetData = song },
                                    onAddQueue = { handleAddQueue(LocalSong(generateDummyId(song.title, song.artist), song.title, song.artist, -1L, null, "${song.artUrl}|||${song.id}"), song) }
                                ) 
                            }
                        }
                    }
                } else {
                    when (currentTab) {
                        0 -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                            item { Spacer(modifier = Modifier.height(16.dp)) }
                            if (recentlyPlayedSongs.isNotEmpty()) {
                                item { Text("Recently Played", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) }
                                item {
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(horizontal = 16.dp), modifier = Modifier.padding(bottom = 32.dp)) {
                                        items(recentlyPlayedSongs) { song ->
                                            val mem = memoryMap[song.id]
                                            val dTitle = mem?.customTitle?.takeIf { it.isNotBlank() } ?: mem?.fetchedTitle?.takeIf { it.isNotBlank() } ?: song.title
                                            val dArt = mem?.fetchedArtUrl?.substringBefore("|||") ?: song.albumArtUri.toString()
                                            val realId = mem?.fetchedArtUrl?.substringAfter("|||", "") ?: ""
                                            
                                            Column(modifier = Modifier.width(120.dp).clickable { 
                                                if (song.id < 0) { 
                                                    autoPlayContext = recentlyPlayedSongs.filter { it.id < 0 }.map { InternetSongData(it.customArtUrl?.substringAfter("|||", "") ?: "", it.title, it.artist, it.customArtUrl?.substringBefore("|||") ?: "") }
                                                    autoPlayIndex = autoPlayContext.indexOfFirst { it.title == dTitle }
                                                    if (song.webStreamUrl != null) { 
                                                        playQueue = listOf(song); exoPlayer.setMediaItem(MediaItem.fromUri(song.webStreamUrl)); exoPlayer.prepare(); exoPlayer.play(); currentSong = song 
                                                    } else {
                                                        playWebSong(InternetSongData(realId, dTitle, song.artist, dArt)) 
                                                    }
                                                } else { 
                                                    playQueue = recentlyPlayedSongs.filter { it.id >= 0 }
                                                    val idx = playQueue.indexOf(song)
                                                    exoPlayer.setMediaItems(playQueue.map { MediaItem.fromUri(it.albumArtUri) })
                                                    exoPlayer.seekTo(idx, 0L); exoPlayer.prepare(); exoPlayer.play() 
                                                } 
                                            }) {
                                                SubcomposeAsyncImage(model = ImageRequest.Builder(LocalContext.current).data(dArt).crossfade(true).build(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(120.dp).clip(RoundedCornerShape(8.dp)), error = { FallbackIcon() })
                                                Spacer(modifier = Modifier.height(8.dp))
                                                Text(dTitle, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        2 -> {
                            if (viewingLikedSongs) {
                                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { 
                                    IconButton(onClick = { viewingLikedSongs = false }) { Icon(Icons.Default.ArrowBack, null) }
                                    Text("Liked Songs", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) 
                                }
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    items(favoriteSongs) { song -> 
                                        val mem = memoryMap[song.id]
                                        val dTitle = mem?.customTitle?.takeIf { it.isNotBlank() } ?: mem?.fetchedTitle?.takeIf { it.isNotBlank() } ?: song.title
                                        val dArtist = mem?.customArtist?.takeIf { it.isNotBlank() } ?: mem?.fetchedArtist?.takeIf { it.isNotBlank() } ?: song.artist
                                        val dArt = mem?.fetchedArtUrl?.substringBefore("|||") ?: song.albumArtUri.toString()
                                        val realId = mem?.fetchedArtUrl?.substringAfter("|||", "") ?: ""
                                        
                                        TrackRow(song.id, dTitle, dArtist, dArt, currentSong?.id == song.id, 
                                            onClick = { 
                                                if (song.id < 0) { playWebSong(InternetSongData(realId, dTitle, dArtist, dArt)) } 
                                                else { 
                                                    playQueue = favoriteSongs.filter { it.id >= 0 }
                                                    val idx = playQueue.indexOf(song)
                                                    exoPlayer.setMediaItems(playQueue.map { MediaItem.fromUri(it.albumArtUri) })
                                                    exoPlayer.seekTo(idx, 0L); exoPlayer.prepare(); exoPlayer.play() 
                                                } 
                                            }, 
                                            onLongClick = { selectedSongForAction = song },
                                            onAddQueue = { handleAddQueue(song, InternetSongData(realId, dTitle, dArtist, dArt)) }
                                        ) 
                                    }
                                }
                            } else if (viewingPlaylistId != null && currentPlaylistData != null) {
                                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { 
                                    IconButton(onClick = { viewingPlaylistId = null }) { Icon(Icons.Default.ArrowBack, null) }
                                    Text(currentPlaylistData!!.playlist.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) 
                                }
                                val playlistSongs = remember(currentPlaylistData, localSongs) { currentPlaylistData!!.songs.mapNotNull { mem -> if (mem.localMediaId >= 0) localSongs.find { it.id == mem.localMediaId } else LocalSong(mem.localMediaId, mem.fetchedTitle ?: "Unknown", mem.fetchedArtist ?: "Unknown", -1L, null, mem.fetchedArtUrl) } }
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    items(playlistSongs) { song ->
                                        val mem = memoryMap[song.id]
                                        val dTitle = mem?.customTitle?.takeIf { it.isNotBlank() } ?: mem?.fetchedTitle?.takeIf { it.isNotBlank() } ?: song.title
                                        val dArtist = mem?.customArtist?.takeIf { it.isNotBlank() } ?: mem?.fetchedArtist?.takeIf { it.isNotBlank() } ?: song.artist
                                        val dArt = mem?.fetchedArtUrl?.substringBefore("|||") ?: song.albumArtUri.toString()
                                        val realId = mem?.fetchedArtUrl?.substringAfter("|||", "") ?: ""
                                        
                                        TrackRow(song.id, dTitle, dArtist, dArt, currentSong?.id == song.id, 
                                            onClick = { 
                                                if (song.id < 0) { 
                                                    if (song.webStreamUrl != null) playSongList(song, listOf(song)) else playWebSong(InternetSongData(realId, dTitle, dArtist, dArt)) 
                                                } else { playSongList(song, playlistSongs.filter { it.id >= 0 }) } 
                                            }, 
                                            onLongClick = { selectedSongForAction = song },
                                            onAddQueue = { handleAddQueue(song, InternetSongData(realId, dTitle, dArtist, dArt)) }
                                        )
                                    }
                                }
                            } else {
                                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { 
                                    Text("Your Library", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                    IconButton(onClick = { showCreatePlaylistDialog = true }) { Icon(Icons.Default.Add, null) } 
                                }
                                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                                    item { 
                                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).clickable { viewingLikedSongs = true }, verticalAlignment = Alignment.CenterVertically) { 
                                            Box(modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha=0.1f)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Favorite, null, tint = Color.White) }
                                            Spacer(modifier = Modifier.width(16.dp))
                                            Column { Text("Liked Songs", fontWeight = FontWeight.Bold, fontSize = 16.sp); Text("${favoriteSongs.size} tracks", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), fontSize = 13.sp) } 
                                        } 
                                    }
                                    items(customPlaylists) { playlist -> 
                                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).clickable { viewingPlaylistId = playlist.playlistId }, verticalAlignment = Alignment.CenterVertically) { 
                                            Box(modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) { Icon(Icons.Default.QueueMusic, null) }
                                            Spacer(modifier = Modifier.width(16.dp))
                                            Column { Text(playlist.name, fontWeight = FontWeight.Bold, fontSize = 16.sp); Text("Playlist", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), fontSize = 13.sp) } 
                                        } 
                                    }
                                }
                            }
                        }
                        3 -> {
                            val stations = listOf(
                                InternetSongData("yt:jfKfPfyJRdk", "Lofi Girl (Beats to Relax/Study)", "Lofi Girl", "https://img.youtube.com/vi/jfKfPfyJRdk/hqdefault.jpg"),
                                InternetSongData("yt:4xDzrUhVKcg", "Synthwave Radio (Spacewave)", "Lofi Girl", "https://img.youtube.com/vi/4xDzrUhVKcg/hqdefault.jpg"),
                                InternetSongData("yt:5yx6BWlEVcY", "Chillhop Radio (Jazzy/Lofi)", "Chillhop Music", "https://img.youtube.com/vi/5yx6BWlEVcY/hqdefault.jpg"),
                                InternetSongData("yt:1t4K450f3qM", "Spinnin' Records 24/7", "Spinnin' Records", "https://img.youtube.com/vi/1t4K450f3qM/hqdefault.jpg"),
                                InternetSongData("yt:7NOSDKb0HlU", "Chillout Lounge Relax", "Chillout", "https://img.youtube.com/vi/7NOSDKb0HlU/hqdefault.jpg")
                            )
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                item { Text("24/7 Live Radio", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp)) }
                                items(stations) { s ->
                                    TrackRow(
                                        songId = -1L, title = s.title, artist = s.artist, artUrl = s.artUrl, isPlaying = currentSong?.title == s.title,
                                        onClick = { playWebSong(s) }, onLongClick = {}, 
                                        onAddQueue = { handleAddQueue(LocalSong(generateDummyId(s.title, s.artist), s.title, s.artist, -1L, null, "${s.artUrl}|||${s.id}"), s) }
                                    )
                                }
                            }
                        }
                        4 -> {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(localSongs) { song -> 
                                    val mem = memoryMap[song.id]
                                    val dTitle = mem?.customTitle?.takeIf { it.isNotBlank() } ?: mem?.fetchedTitle?.takeIf { it.isNotBlank() } ?: song.title
                                    val dArtist = mem?.customArtist?.takeIf { it.isNotBlank() } ?: mem?.fetchedArtist?.takeIf { it.isNotBlank() } ?: song.artist
                                    val dArt = mem?.fetchedArtUrl?.substringBefore("|||") ?: song.albumArtUri.toString()
                                    TrackRow(song.id, dTitle, dArtist, dArt, currentSong?.id == song.id, 
                                        onClick = { 
                                            playQueue = localSongs
                                            val idx = localSongs.indexOf(song)
                                            exoPlayer.setMediaItems(localSongs.map { MediaItem.fromUri(it.albumArtUri) })
                                            exoPlayer.seekTo(idx, 0L)
                                            exoPlayer.prepare()
                                            exoPlayer.play() 
                                        }, 
                                        onLongClick = { selectedSongForAction = song },
                                        onAddQueue = { handleAddQueue(song, null) }
                                    ) 
                                }
                            }
                        }
                    }
                }
            }
        }
        
        AnimatedVisibility(visible = showFullScreenPlayer && currentSong != null, enter = slideInVertically(initialOffsetY = { it }), exit = slideOutVertically(targetOffsetY = { it })) {
            if (currentSong != null) {
                var sL by remember { mutableStateOf(false) }
                var sQ by remember { mutableStateOf(false) }
                val dTitle = fetchedInternetData?.title ?: currentSong!!.title
                val dArtist = fetchedInternetData?.artist ?: currentSong!!.artist
                val dArt = fetchedInternetData?.artUrl?.substringBefore("|||") ?: currentSong!!.customArtUrl?.substringBefore("|||") ?: currentSong!!.albumArtUri.toString()

                Box(modifier = Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) {
                    SubcomposeAsyncImage(model = ImageRequest.Builder(LocalContext.current).data(dArt).crossfade(1000).build(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(60.dp), error = { Box(Modifier.fillMaxSize().background(Color.DarkGray)) })
                    Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.2f), Color.Black.copy(alpha = 0.9f), Color.Black), 0f, 2000f)))
                    
                    Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { showFullScreenPlayer = false }) { Icon(Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(36.dp), tint = Color.White) }
                            Row {
                                IconButton(onClick = { sQ = !sQ; if(sQ) sL = false }) { Icon(if (sQ) Icons.Default.QueueMusic else Icons.Default.FormatListBulleted, null, tint = Color.White) }
                                if (fetchedInternetData?.lyrics != null) {
                                    IconButton(onClick = { sL = !sL; if(sL) sQ = false }) { Icon(if (sL) Icons.Default.MusicNote else Icons.Default.Subject, null, tint = Color.White) }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        
                        if (sQ) {
                            Column(modifier = Modifier.fillMaxWidth().height(400.dp).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp)).padding(8.dp)) {
                                Text("Up Next", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    itemsIndexed(playQueue) { i, qs ->
                                        val m = memoryMap[qs.id]
                                        Row(modifier = Modifier.fillMaxWidth().clickable { 
                                            if (qs.id < 0 && qs.webStreamUrl == null) playWebSong(InternetSongData(qs.customArtUrl?.substringAfter("|||") ?: "", qs.title, qs.artist, qs.customArtUrl?.substringBefore("|||") ?: "")) 
                                            else { exoPlayer.seekTo(i, 0L); exoPlayer.play() } 
                                        }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(if (qs.id == currentSong!!.id) Icons.Default.PlayArrow else Icons.Default.MusicNote, null, tint = Color.White, modifier = Modifier.size(20.dp))
                                            Text(m?.customTitle ?: qs.title, color = Color.White, modifier = Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 1)
                                            IconButton(onClick = { 
                                                val nQ = playQueue.toMutableList(); nQ.removeAt(i); playQueue = nQ
                                                try { exoPlayer.removeMediaItem(i) } catch(e: Exception){}
                                                if (exoPlayer.mediaItemCount == 0) { exoPlayer.stop(); isPlaying = false; showFullScreenPlayer = false; currentSong = null } 
                                            }) { Icon(Icons.Default.Close, null, tint = Color.White.copy(alpha = 0.5f)) }
                                        }
                                    }
                                }
                            }
                        } else if (sL && fetchedInternetData?.lyrics != null) {
                            Text(text = fetchedInternetData!!.lyrics!!, style = MaterialTheme.typography.titleMedium.copy(lineHeight = 28.sp), color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().height(400.dp).verticalScroll(rememberScrollState()))
                        } else {
                            SubcomposeAsyncImage(model = ImageRequest.Builder(LocalContext.current).data(dArt).crossfade(1000).build(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(1f).shadow(24.dp, RoundedCornerShape(32.dp)), error = { FallbackIcon(modifier = Modifier.size(80.dp)) })
                        }
                        
                        Spacer(modifier = Modifier.height(32.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(dTitle, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(dArtist, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.6f), maxLines = 1)
                            }
                            IconButton(onClick = { coroutineScope.launch { db.updateFavoriteStatus(currentSong!!.id, !(memoryMap[currentSong!!.id]?.isFavorite ?: false)) } }) { 
                                Icon(if (memoryMap[currentSong?.id]?.isFavorite == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, modifier = Modifier.size(32.dp), tint = Color.White) 
                            }
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        Slider(value = if (!isLiveStream && totalDuration > 0) (currentPosition.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f) else 0f, onValueChange = { p -> if (!isLiveStream) { val np = (p * totalDuration).toLong(); exoPlayer.seekTo(np); currentPosition = np } }, modifier = Modifier.fillMaxWidth(), colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.2f)))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            val f = { ms: Long -> String.format("%02d:%02d", ms/1000/60, ms/1000%60) }
                            Text(if (isLiveStream) "LIVE" else f(currentPosition), color = Color.White.copy(alpha = 0.5f))
                            Text(if (isLiveStream) "LIVE" else f(totalDuration), color = Color.White.copy(alpha = 0.5f))
                        }
                        Spacer(modifier = Modifier.height(32.dp))
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                            IconButton(onClick = { handlePrev() }) { Icon(Icons.Default.SkipPrevious, null, modifier = Modifier.size(48.dp), tint = Color.White) }
                            Box(modifier = Modifier.size(80.dp).clip(CircleShape).background(Color.White).clickable { if (isPlaying) exoPlayer.pause() else exoPlayer.play() }, contentAlignment = Alignment.Center) { 
                                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, modifier = Modifier.size(48.dp), tint = Color.Black) 
                            }
                            IconButton(onClick = { handleNext() }) { Icon(Icons.Default.SkipNext, null, modifier = Modifier.size(48.dp), tint = Color.White) }
                        }
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }
            }
        }
        
        if (selectedSongForAction != null) {
            val s = selectedSongForAction!!
            AlertDialog(
                onDismissRequest = { selectedSongForAction = null }, 
                title = { Text("Options") }, 
                text = { 
                    Column { 
                        TextButton(onClick = { 
                            if (s.id < 0) {
                                coroutineScope.launch { 
                                    val streamUrl = fetchAudioStreamUrl(s.title, s.artist, s.customArtUrl?.substringAfter("|||", "") ?: "")
                                    if (streamUrl != null) { 
                                        playQueue = playQueue + s.copy(webStreamUrl = streamUrl)
                                        exoPlayer.addMediaItem(MediaItem.fromUri(streamUrl))
                                        db.saveSongMemory(SongEntity(s.id, s.title, s.artist, s.title, s.artist, s.customArtUrl, null, false, System.currentTimeMillis())) 
                                    } 
                                } 
                            } else { 
                                playQueue = playQueue + s
                                exoPlayer.addMediaItem(MediaItem.fromUri(s.albumArtUri)) 
                            }
                            selectedSongForAction = null 
                        }, modifier = Modifier.fillMaxWidth()) { Text("Add to Queue") } 
                        
                        TextButton(onClick = { showAddToPlaylistDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Add to Playlist") }
                        
                        if (s.id >= 0) {
                            TextButton(onClick = { showEditDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Edit Metadata") }
                        }
                    } 
                }, 
                confirmButton = { TextButton(onClick = { selectedSongForAction = null }) { Text("Cancel") } }
            )
        }
        
        if (showEditDialog && selectedSongForAction != null) {
            val s = selectedSongForAction!!
            val m = memoryMap[s.id]
            var eT by remember { mutableStateOf(m?.customTitle ?: s.title) }
            var eA by remember { mutableStateOf(m?.customArtist ?: s.artist) }
            
            AlertDialog(
                onDismissRequest = { showEditDialog = false; selectedSongForAction = null }, 
                title = { Text("Fix Metadata") }, 
                text = { 
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { 
                        OutlinedTextField(value = eT, onValueChange = { eT = it }, label = { Text("Title") }, singleLine = true)
                        OutlinedTextField(value = eA, onValueChange = { eA = it }, label = { Text("Artist") }, singleLine = true) 
                    } 
                },
                confirmButton = { 
                    Button(onClick = { 
                        coroutineScope.launch { 
                            db.saveSongMemory(SongEntity(s.id, eT.trim().takeIf{it.isNotEmpty()}, eA.trim().takeIf{it.isNotEmpty()}, null, null, null, null, m?.isFavorite ?: false, m?.lastPlayedAt ?: 0L))
                            showEditDialog = false
                            selectedSongForAction = null
                            playSongList(s, localSongs) 
                        } 
                    }) { Text("Save") } 
                } 
            )
        }
        
        if (showCreatePlaylistDialog) {
            var newPlaylistName by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showCreatePlaylistDialog = false }, 
                title = { Text("New Playlist") }, 
                text = { OutlinedTextField(value = newPlaylistName, onValueChange = { newPlaylistName = it }, label = { Text("Name") }, singleLine = true) },
                confirmButton = { 
                    Button(onClick = { 
                        if (newPlaylistName.isNotBlank()) { 
                            coroutineScope.launch { db.createPlaylist(PlaylistEntity(name = newPlaylistName.trim())) }
                            showCreatePlaylistDialog = false 
                        } 
                    }) { Text("Create") } 
                } 
            )
        }
        
        if (showAddToPlaylistDialog && selectedSongForAction != null) {
            val songToAdd = selectedSongForAction!!
            AlertDialog(
                onDismissRequest = { showAddToPlaylistDialog = false; selectedSongForAction = null }, 
                title = { Text("Select Playlist") }, 
                text = { 
                    LazyColumn { 
                        items(customPlaylists) { playlist -> 
                            TextButton(onClick = { 
                                coroutineScope.launch { 
                                    if (db.getSongMemory(songToAdd.id) == null) db.saveSongMemory(SongEntity(songToAdd.id, null, null, null, null, songToAdd.customArtUrl, null, false, 0L))
                                    db.addSongToPlaylist(PlaylistSongCrossRef(playlist.playlistId, songToAdd.id)) 
                                }
                                showAddToPlaylistDialog = false
                                selectedSongForAction = null 
                            }, modifier = Modifier.fillMaxWidth()) { Text(playlist.name) } 
                        } 
                    } 
                },
                confirmButton = { TextButton(onClick = { showAddToPlaylistDialog = false; selectedSongForAction = null }) { Text("Cancel") } } 
            )
        }
    }
}

// --- HELPER FUNCTIONS ---
fun generateDummyId(title: String, artist: String): Long {
    val h = kotlin.math.abs((title + artist).hashCode().toLong())
    return if (h == 0L) -1L else -h
}

fun fetchLocalMusic(context: Context): List<LocalSong> {
    val songs = mutableListOf<LocalSong>()
    val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM_ID)
    context.contentResolver.query(uri, proj, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} ASC")?.use { cursor ->
        val idC = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val tC = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val aC = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val alC = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        while (cursor.moveToNext()) { 
            val title = cursor.getString(tC) ?: "Unknown"
            if (!title.matches(Regex("(?i)(.*\\d{8}.*|.*aud-.*|.*ptt-.*|.*wa00.*|.*record.*)"))) {
                songs.add(LocalSong(cursor.getLong(idC), title, cursor.getString(aC) ?: "Unknown", cursor.getLong(alC))) 
            }
        }
    }
    return songs
}

private fun fetchHttp(urlStr: String, timeoutMs: Int = 6000): String? {
    return try {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        if (conn.responseCode == 200) conn.inputStream.bufferedReader().readText() else null
    } catch (e: Exception) { null }
}

suspend fun getAutoLanguage(): String = withContext(Dispatchers.IO) {
    try {
        val res = fetchHttp("https://ipwho.is/") ?: return@withContext "All"
        val json = JSONObject(res)
        val country = json.optString("country", "")
        val region = json.optString("region", "")
        if (country.equals("India", true)) {
            if (region.contains("Tamil", true)) return@withContext "Tamil"
            if (region.contains("Maharashtra", true)) return@withContext "Marathi"
            return@withContext "Hindi"
        }
    } catch (e: Exception) {}
    return@withContext "All" 
}

suspend fun fetchLiveSearchResults(query: String, rawQuery: String, isPlaylistSearch: Boolean): List<InternetSongData> = coroutineScope {
    val qEnc = URLEncoder.encode(query, "UTF-8")
    
    val saavnTask = async<List<InternetSongData>>(Dispatchers.IO) {
        val list = mutableListOf<InternetSongData>()
        if (!isPlaylistSearch) {
            val apis = listOf("https://saavn.dev/api/search/songs?query=", "https://saavn.sumit.co/api/search/songs?query=")
            for (api in apis) {
                try {
                    val res = fetchHttp("$api$qEnc", 5000)
                    if (res != null) {
                        val arr = JSONObject(res).optJSONObject("data")?.optJSONArray("results")
                        if (arr != null) { 
                            for (i in 0 until arr.length()) { 
                                val t = arr.getJSONObject(i)
                                list.add(InternetSongData(t.optString("id"), t.optString("name", t.optString("title")).replace("&quot;", "\""), t.optJSONArray("primaryArtists")?.optJSONObject(0)?.optString("name") ?: "", t.optJSONArray("image")?.optJSONObject(2)?.optString("link") ?: "")) 
                            }
                            break 
                        }
                    }
                } catch(e: Exception){}
            }
        }
        return@async list
    }
    
    val archiveTask = async<List<InternetSongData>>(Dispatchers.IO) {
        val list = mutableListOf<InternetSongData>()
        if (isPlaylistSearch) {
            try {
                val qBase = if (rawQuery.isBlank()) "subject:\"music\" OR subject:\"soundtrack\" OR subject:\"ost\"" else "($rawQuery)"
                val exact = "$qBase AND mediatype:audio AND NOT subject:\"news\" AND NOT subject:\"podcast\" AND NOT subject:\"ep\" AND NOT subject:\"broadcast\" AND NOT creator:\"voa\""
                val res = fetchHttp("https://archive.org/advancedsearch.php?q=${URLEncoder.encode(exact, "UTF-8")}&fl[]=identifier,title,creator&rows=15&output=json", 5000)
                if (res != null) {
                    val docs = JSONObject(res).optJSONObject("response")?.optJSONArray("docs")
                    if (docs != null) { 
                        for (i in 0 until docs.length()) { 
                            val t = docs.getJSONObject(i)
                            val id = t.optString("identifier")
                            if (id.isNotBlank()) list.add(InternetSongData("ia:$id", t.optString("title"), "[Album] ${t.optString("creator")}", "https://archive.org/services/img/$id")) 
                        } 
                    }
                }
            } catch(e: Exception){}
        }
        return@async list
    }
    
    val pipedTask = async<List<InternetSongData>>(Dispatchers.IO) {
        val list = mutableListOf<InternetSongData>()
        if (!isPlaylistSearch) {
            val instances = listOf("pipedapi.kavin.rocks", "pipedapi.smnz.de", "api.piped.stream")
            for (instance in instances) {
                try {
                    val res = fetchHttp("https://$instance/search?q=$qEnc&filter=music_songs", 5000)
                    if (res != null) { 
                        val items = JSONObject(res).optJSONArray("items")
                        if (items != null) { 
                            for (i in 0 until minOf(items.length(), 15)) { 
                                val t = items.getJSONObject(i)
                                if (t.optString("type") == "stream") { 
                                    val vid = t.optString("url").replace("/watch?v=", "").substringBefore("&")
                                    if (vid.isNotBlank()) list.add(InternetSongData("yt:$vid", t.optString("title"), t.optString("uploaderName"), t.optString("thumbnail"))) 
                                } 
                            }
                            break 
                        } 
                    }
                } catch(e: Exception){}
            }
        }
        return@async list
    }
    
    val combined = mutableListOf<InternetSongData>()
    combined.addAll(saavnTask.await())
    combined.addAll(archiveTask.await())
    combined.addAll(pipedTask.await())
    
    return@coroutineScope combined.distinctBy { it.title.lowercase() }
}

suspend fun fetchAudioStreamUrl(title: String, artist: String, songId: String = ""): String? = withContext(Dispatchers.IO) {
    if (AppCache.streamUrls.containsKey(songId)) return@withContext AppCache.streamUrls[songId]
    
    var finalUrl: String? = null

    // 1. Try Saavn if not explicitly YT/Archive
    if (songId.isNotBlank() && !songId.startsWith("ia:") && !songId.startsWith("yt:")) {
        val apis = listOf("https://saavn.dev/api/songs?ids=", "https://saavn.sumit.co/api/songs?ids=")
        for (api in apis) {
            try {
                val res = fetchHttp("$api$songId", 5000)
                if (res != null) {
                    val data = JSONObject(res).optJSONArray("data")
                    if (data != null && data.length() > 0) {
                        val urls = data.getJSONObject(0).optJSONArray("downloadUrl")
                        if (urls != null) {
                            finalUrl = urls.getJSONObject(urls.length()-1).optString("link")
                            break
                        }
                    }
                }
            } catch(e: Exception){}
        }
    }

    // 2. Try YouTube if explicitly YT
    if (finalUrl == null && songId.startsWith("yt:")) {
        val vid = songId.removePrefix("yt:")
        finalUrl = getPipedStream(vid)
    }

    // 3. ULTIMATE FALLBACK: If Saavn failed, search YouTube and grab the highest quality audio stream
    if (finalUrl == null && title.isNotBlank()) {
        val qEnc = URLEncoder.encode("$title $artist audio", "UTF-8")
        val instances = listOf("pipedapi.kavin.rocks", "pipedapi.smnz.de", "api.piped.stream")
        for (inst in instances) {
            try {
                val res = fetchHttp("https://$inst/search?q=$qEnc&filter=music_songs", 5000)
                val items = JSONObject(res ?: "").optJSONArray("items")
                if (items != null && items.length() > 0) {
                    val vid = items.getJSONObject(0).optString("url").substringAfter("v=").substringBefore("&")
                    if (vid.isNotBlank()) {
                        finalUrl = getPipedStream(vid)
                        if (finalUrl != null) break
                    }
                }
            } catch(e: Exception){}
        }
    }

    if (finalUrl != null) {
        AppCache.streamUrls[songId] = finalUrl
    }
    return@withContext finalUrl
}

suspend fun getPipedStream(vid: String): String? {
    val instances = listOf("pipedapi.kavin.rocks", "pipedapi.smnz.de", "api.piped.stream", "piped.tokhmi.xyz")
    for (inst in instances) {
        try {
            val res = fetchHttp("https://$inst/streams/$vid", 5000) ?: continue
            val streams = JSONObject(res).optJSONArray("audioStreams")
            var bestUrl: String? = null
            var highestBitrate = 0
            if (streams != null) {
                for (i in 0 until streams.length()) {
                    val st = streams.getJSONObject(i)
                    val f = st.optString("format").lowercase()
                    val m = st.optString("mimeType").lowercase()
                    val b = st.optInt("bitrate", 0)
                    if (f.contains("m4a") || f.contains("webm") || m.contains("audio")) {
                        if (b >= highestBitrate) {
                            highestBitrate = b
                            bestUrl = st.optString("url")
                        }
                    }
                }
            }
            if (bestUrl != null) return bestUrl
        } catch(e: Exception){}
    }
    return null
}

suspend fun fetchMultiSourceMetadata(title: String, artist: String): InternetSongData? = coroutineScope {
    val cleanTitle = title.lowercase().replace(".mp3", "").replace(".m4a", "").trim()
    
    val t1 = async<InternetSongData?>(Dispatchers.IO) { 
        try {
            val res = fetchHttp("https://itunes.apple.com/search?term=${URLEncoder.encode("$cleanTitle $artist", "UTF-8")}&limit=1", 4000)
            if (res != null) {
                val results = JSONObject(res).optJSONArray("results")
                if (results != null && results.length() > 0) {
                    val t = results.getJSONObject(0)
                    return@async InternetSongData("", t.optString("trackName"), t.optString("artistName"), t.optString("artworkUrl100").replace("100x100bb", "600x600bb"))
                }
            }
        } catch(e: Exception){}
        return@async null
    }
    
    val t2 = async<InternetSongData?>(Dispatchers.IO) { 
        try {
            val res = fetchHttp("https://www.jiosaavn.com/api.php?__call=autocomplete.get&_format=json&_marker=0&cc=in&query=${URLEncoder.encode("$cleanTitle $artist", "UTF-8")}", 4000)
            if (res != null) {
                val songs = JSONObject(res).optJSONObject("songs")?.optJSONArray("data")
                if (songs != null && songs.length() > 0) {
                    val t = songs.getJSONObject(0)
                    return@async InternetSongData("", t.optString("title", t.optString("name")).replace("&quot;", "\""), t.optJSONObject("more_info")?.optString("primary_artists") ?: "", t.optString("image").replace("50x50.jpg", "500x500.jpg"))
                }
            }
        } catch(e: Exception){}
        return@async null
    }
    
    val result = t1.await() ?: t2.await()
    if (result != null) {
        try {
            val res = fetchHttp("https://lrclib.net/api/search?q=${URLEncoder.encode("$title $artist", "UTF-8")}", 4000)
            if (res != null) {
                val arr = JSONArray(res)
                if (arr.length() > 0) {
                    return@coroutineScope result.copy(lyrics = arr.getJSONObject(0).optString("plainLyrics"))
                }
            }
        } catch(e: Exception){}
        return@coroutineScope result
    }
    return@coroutineScope null
}
