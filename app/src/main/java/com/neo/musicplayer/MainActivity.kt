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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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

@Composable
fun BlueWhiteFallback(modifier: Modifier = Modifier, iconSize: Dp = 48.dp) {
    Box(
        modifier = modifier.background(Brush.linearGradient(listOf(Color(0xFF1976D2), Color(0xFFBBDEFB)))),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.MusicNote, contentDescription = "Music", tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(iconSize))
    }
}

data class LocalSong(val id: Long, val title: String, val artist: String, val albumId: Long) {
    val albumArtUri: Uri get() = Uri.parse("content://media/external/audio/albumart/$albumId")
}

data class InternetSongData(val title: String, val artist: String, val artUrl: String, val lyrics: String? = null)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AestheticTheme { MusicPlayerUI() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MusicPlayerUI() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context).libraryDao() }
    
    // --- MEMORY & PLAYLIST LISTENERS ---
    val songMemories by db.getAllSongMemories().collectAsState(initial = emptyList())
    val memoryMap = remember(songMemories) { songMemories.associateBy { it.localMediaId } }
    
    val favoriteMemories by db.getFavoriteSongs().collectAsState(initial = emptyList())
    val recentlyPlayedMemories by db.getRecentlyPlayed().collectAsState(initial = emptyList())
    val customPlaylists by db.getAllPlaylists().collectAsState(initial = emptyList())
    
    var localSongs by remember { mutableStateOf<List<LocalSong>>(emptyList()) }
    
    val favoriteSongs = remember(favoriteMemories, localSongs) { localSongs.filter { song -> favoriteMemories.any { it.localMediaId == song.id } } }
    val recentlyPlayedSongs = remember(recentlyPlayedMemories, localSongs) { 
        recentlyPlayedMemories.mapNotNull { mem -> localSongs.find { it.id == mem.localMediaId } }
    }
    
    // --- APP STATE ---
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var permissionGranted by remember { mutableStateOf(false) }
    var currentTab by remember { mutableIntStateOf(0) } 

    var currentSong by remember { mutableStateOf<LocalSong?>(null) }
    var fetchedInternetData by remember { mutableStateOf<InternetSongData?>(null) } 
    
    // DIALOG STATES
    var selectedSongForAction by remember { mutableStateOf<LocalSong?>(null) } 
    var showEditDialog by remember { mutableStateOf(false) }
    var showAddToPlaylistDialog by remember { mutableStateOf(false) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    
    var isPlaying by remember { mutableStateOf(false) }
    var playbackState by remember { mutableStateOf(Player.STATE_IDLE) }
    var currentPosition by remember { mutableStateOf(0L) }
    var totalDuration by remember { mutableStateOf(0L) }
    var showFullScreenPlayer by remember { mutableStateOf(false) }

    val mediaItems = remember(localSongs) {
        localSongs.map { song ->
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)
            MediaItem.Builder().setUri(uri).setMediaId(song.id.toString()).build()
        }
    }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener { 
                override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
                override fun onPlaybackStateChanged(state: Int) { playbackState = state }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val index = currentMediaItemIndex
                    if (index in localSongs.indices) currentSong = localSongs[index]
                }
            })
        }
    }

    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    LaunchedEffect(currentSong) {
        val song = currentSong ?: return@LaunchedEffect
        val memory = db.getSongMemory(song.id)
        
        val displayTitle = memory?.customTitle?.takeIf { it.isNotBlank() } ?: memory?.fetchedTitle?.takeIf { it.isNotBlank() } ?: song.title
        val displayArtist = memory?.customArtist?.takeIf { it.isNotBlank() } ?: memory?.fetchedArtist?.takeIf { it.isNotBlank() } ?: song.artist

        fetchedInternetData = InternetSongData(title = displayTitle, artist = displayArtist, artUrl = memory?.fetchedArtUrl ?: "", lyrics = memory?.fetchedLyrics)

        // RECORD PLAY HISTORY FOR DASHBOARD
        if (memory != null) {
            db.updateLastPlayed(song.id, System.currentTimeMillis())
        } else {
            db.saveSongMemory(SongEntity(localMediaId = song.id, customTitle = null, customArtist = null, fetchedTitle = null, fetchedArtist = null, fetchedArtUrl = null, fetchedLyrics = null, isFavorite = false, lastPlayedAt = System.currentTimeMillis()))
        }

        if (memory?.fetchedArtUrl == null) {
            val result = fetchMultiSourceMetadata(displayTitle, displayArtist)
            if (result != null) {
                fetchedInternetData = result 
                db.saveSongMemory(SongEntity(
                    localMediaId = song.id, customTitle = memory?.customTitle, customArtist = memory?.customArtist,
                    fetchedTitle = result.title, fetchedArtist = result.artist, fetchedArtUrl = result.artUrl, 
                    fetchedLyrics = result.lyrics, isFavorite = memory?.isFavorite ?: false, lastPlayedAt = System.currentTimeMillis()
                ))
            }
        }
    }

    val playSong = { song: LocalSong ->
        val startIndex = localSongs.indexOf(song)
        if (startIndex >= 0 && mediaItems.isNotEmpty()) {
            exoPlayer.setMediaItems(mediaItems, startIndex, androidx.media3.common.C.TIME_UNSET)
            exoPlayer.prepare()
            exoPlayer.play()
        }
    }

    val toggleFavorite = {
        currentSong?.let { song ->
            coroutineScope.launch {
                val memory = db.getSongMemory(song.id)
                db.updateFavoriteStatus(song.id, !(memory?.isFavorite ?: false))
            }
        }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            currentPosition = exoPlayer.currentPosition
            totalDuration = if (exoPlayer.duration > 0) exoPlayer.duration else 0L
            delay(1000L)
        }
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
                        if (isSearchActive) { TextField(value = searchQuery, onValueChange = { searchQuery = it }, placeholder = { Text("Search web...") }, singleLine = true, colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                        } else { 
                            Text(text = when(currentTab) { 0 -> "Dashboard"; 1 -> "Discover"; 2 -> "My Library"; else -> "All Songs" }, fontWeight = FontWeight.Bold) 
                        }
                    },
                    actions = { IconButton(onClick = { isSearchActive = !isSearchActive; if (!isSearchActive) searchQuery = "" }) { Icon(if (isSearchActive) Icons.Default.Close else Icons.Default.Search, contentDescription = "Toggle Search", tint = MaterialTheme.colorScheme.primary) } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
            },
            bottomBar = { 
                Column {
                    if (currentSong != null) {
                        PlayerControlsBar(
                            currentSong = currentSong!!, internetData = fetchedInternetData, isPlaying = isPlaying, currentPosition = currentPosition, totalDuration = totalDuration,
                            onPlayPause = { if (isPlaying) exoPlayer.pause() else exoPlayer.play() },
                            onNext = { exoPlayer.seekToNextMediaItem() }, 
                            onPrev = { exoPlayer.seekToPreviousMediaItem() }, 
                            onBarClick = { showFullScreenPlayer = true }
                        )
                    }
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 8.dp) {
                        NavigationBarItem(icon = { Icon(Icons.Default.Home, contentDescription = "Home") }, label = { Text("Home", fontSize = 10.sp) }, selected = currentTab == 0, onClick = { currentTab = 0; isSearchActive = false })
                        NavigationBarItem(icon = { Icon(Icons.Default.Search, contentDescription = "Search") }, label = { Text("Search", fontSize = 10.sp) }, selected = currentTab == 1, onClick = { currentTab = 1; isSearchActive = true })
                        NavigationBarItem(icon = { Icon(Icons.Default.LibraryMusic, contentDescription = "Library") }, label = { Text("Playlists", fontSize = 10.sp) }, selected = currentTab == 2, onClick = { currentTab = 2; isSearchActive = false })
                        NavigationBarItem(icon = { Icon(Icons.Default.Folder, contentDescription = "Offline") }, label = { Text("Tracks", fontSize = 10.sp) }, selected = currentTab == 3, onClick = { currentTab = 3; isSearchActive = false })
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { paddingValues ->
            Column(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 16.dp)) {
                if (!permissionGranted) { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Storage permission is required.", color = MaterialTheme.colorScheme.primary) }
                } else {
                    when (currentTab) {
                        // --- TAB 0: THE NEW SPOTIFY DASHBOARD ---
                        0 -> {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                item { Spacer(modifier = Modifier.height(8.dp)) }
                                
                                // ROW 1: Recently Played
                                if (recentlyPlayedSongs.isNotEmpty()) {
                                    item { Text("Recently Played", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp)) }
                                    item {
                                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 24.dp)) {
                                            items(recentlyPlayedSongs) { song ->
                                                val mem = memoryMap[song.id]
                                                val displayTitle = mem?.customTitle?.takeIf { it.isNotBlank() } ?: mem?.fetchedTitle?.takeIf { it.isNotBlank() } ?: song.title
                                                val displayArt = mem?.fetchedArtUrl?.takeIf { it.isNotBlank() } ?: song.albumArtUri
                                                
                                                Column(modifier = Modifier.width(120.dp).clickable { playSong(song) }) {
                                                    SubcomposeAsyncImage(model = displayArt, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(120.dp).clip(RoundedCornerShape(12.dp)), error = { BlueWhiteFallback() })
                                                    Spacer(modifier = Modifier.height(6.dp))
                                                    Text(displayTitle, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                                }
                                            }
                                        }
                                    }
                                }

                                // ROW 2: Recommended (Phase 3 Placeholder)
                                item { Text("Recommended for You", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp)) }
                                item {
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 24.dp)) {
                                        items(5) { 
                                            Column(modifier = Modifier.width(140.dp)) {
                                                Box(modifier = Modifier.size(140.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                                                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                                                }
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Text("Phase 3 Scraper", fontWeight = FontWeight.SemiBold, maxLines = 1, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                            }
                                        }
                                    }
                                }

                                // ROW 3: Seasonal Mixes (Phase 3 Placeholder)
                                item { Text("Seasonal Mixes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp)) }
                                item {
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 24.dp)) {
                                        items(3) { 
                                            Box(modifier = Modifier.width(200.dp).height(100.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(Color(0xFFFFA726), Color(0xFFFF7043)))), contentAlignment = Alignment.Center) {
                                                Text("Summer Vibes", fontWeight = FontWeight.Bold, color = Color.White)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // --- TAB 1: SEARCH PLACEHOLDER ---
                        1 -> { 
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text("Web Audio Scraper", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                    Text("Phase 3 Ready.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                                }
                            }
                        }

                        // --- TAB 2: PLAYLIST ENGINE ---
                        2 -> {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("Your Library", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                    Button(onClick = { showCreatePlaylistDialog = true }) {
                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("New")
                                    }
                                }

                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    // 1. Fixed Liked Songs Row
                                    item {
                                        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { /* Future: Open Liked Playlist */ }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                            Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                Box(modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(Brush.linearGradient(listOf(Color(0xFFFF4081), Color(0xFFE91E63)))), contentAlignment = Alignment.Center) {
                                                    Icon(Icons.Default.Favorite, contentDescription = null, tint = Color.White)
                                                }
                                                Spacer(modifier = Modifier.width(16.dp))
                                                Column {
                                                    Text("Liked Songs", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                                    Text("${favoriteSongs.size} tracks", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                                                }
                                            }
                                        }
                                    }

                                    // 2. Custom Playlists
                                    items(customPlaylists) { playlist ->
                                        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { /* Future: Open Playlist */ }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                            Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                Box(modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                                                    Icon(Icons.Default.QueueMusic, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                                }
                                                Spacer(modifier = Modifier.width(16.dp))
                                                Column {
                                                    Text(playlist.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                                    Text("Custom Playlist", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // --- TAB 3: MASTER OFFLINE TRACKS LIST ---
                        3 -> { 
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(localSongs) { song ->
                                    val mem = memoryMap[song.id]
                                    val displayTitle = mem?.customTitle?.takeIf { it.isNotBlank() } ?: mem?.fetchedTitle?.takeIf { it.isNotBlank() } ?: song.title
                                    val displayArtist = mem?.customArtist?.takeIf { it.isNotBlank() } ?: mem?.fetchedArtist?.takeIf { it.isNotBlank() } ?: song.artist
                                    val displayArt = mem?.fetchedArtUrl?.takeIf { it.isNotBlank() } ?: song.albumArtUri

                                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                        Row(modifier = Modifier.combinedClickable(
                                            onClick = { playSong(song) }, 
                                            // TRIGGERS THE ACTION MENU
                                            onLongClick = { selectedSongForAction = song }
                                        ).padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            SubcomposeAsyncImage(model = displayArt, contentDescription = "Album Art", contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)), error = { BlueWhiteFallback() }, loading = { BlueWhiteFallback() })
                                            Spacer(modifier = Modifier.width(16.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(displayTitle, fontWeight = FontWeight.SemiBold, maxLines = 1, color = if (currentSong?.id == song.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                                Text(displayArtist, style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- THE FULL SCREEN PLAYER ---
        AnimatedVisibility(visible = showFullScreenPlayer && currentSong != null, enter = slideInVertically(initialOffsetY = { it }), exit = slideOutVertically(targetOffsetY = { it })) {
            if (currentSong != null) {
                val isFav = memoryMap[currentSong?.id]?.isFavorite == true
                FullScreenPlayer(
                    song = currentSong!!, internetData = fetchedInternetData, isPlaying = isPlaying, currentPosition = currentPosition, totalDuration = totalDuration, isFavorite = isFav,
                    onClose = { showFullScreenPlayer = false }, onPlayPause = { if (isPlaying) exoPlayer.pause() else exoPlayer.play() },
                    onNext = { exoPlayer.seekToNextMediaItem() }, onPrev = { exoPlayer.seekToPreviousMediaItem() },
                    onSeek = { percentage -> val seekPosition = (percentage * totalDuration).toLong(); exoPlayer.seekTo(seekPosition); currentPosition = seekPosition },
                    onToggleFavorite = { toggleFavorite() }
                )
            }
        }

        // --- DIALOG 1: ACTION MENU (Long Press) ---
        if (selectedSongForAction != null) {
            AlertDialog(
                onDismissRequest = { selectedSongForAction = null },
                title = { Text("Track Options") },
                text = {
                    Column {
                        TextButton(onClick = { showAddToPlaylistDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Add to Playlist") }
                        TextButton(onClick = { showEditDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Edit Metadata (Fix Names)") }
                    }
                },
                confirmButton = { TextButton(onClick = { selectedSongForAction = null }) { Text("Cancel") } }
            )
        }

        // --- DIALOG 2: EDIT METADATA ---
        if (showEditDialog && selectedSongForAction != null) {
            val targetSong = selectedSongForAction!!
            val mem = memoryMap[targetSong.id]
            var editTitle by remember { mutableStateOf(mem?.customTitle ?: targetSong.title) }
            var editArtist by remember { mutableStateOf(mem?.customArtist ?: targetSong.artist) }

            AlertDialog(
                onDismissRequest = { showEditDialog = false; selectedSongForAction = null }, 
                title = { Text("Fix Track Metadata", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(value = editTitle, onValueChange = { editTitle = it }, label = { Text("Song Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = editArtist, onValueChange = { editArtist = it }, label = { Text("Artist") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        coroutineScope.launch {
                            db.saveSongMemory(SongEntity(localMediaId = targetSong.id, customTitle = editTitle.trim().takeIf { it.isNotEmpty() }, customArtist = editArtist.trim().takeIf { it.isNotEmpty() }, fetchedTitle = null, fetchedArtist = null, fetchedArtUrl = null, fetchedLyrics = null, isFavorite = mem?.isFavorite ?: false, lastPlayedAt = mem?.lastPlayedAt ?: 0L))
                            showEditDialog = false
                            selectedSongForAction = null
                            playSong(targetSong) // Refetch
                        }
                    }) { Text("Save & Refetch") }
                },
                dismissButton = { TextButton(onClick = { showEditDialog = false; selectedSongForAction = null }) { Text("Cancel") } }
            )
        }

        // --- DIALOG 3: CREATE NEW PLAYLIST ---
        if (showCreatePlaylistDialog) {
            var newPlaylistName by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showCreatePlaylistDialog = false },
                title = { Text("Create Playlist") },
                text = { OutlinedTextField(value = newPlaylistName, onValueChange = { newPlaylistName = it }, label = { Text("Playlist Name") }, singleLine = true) },
                confirmButton = {
                    Button(onClick = {
                        if (newPlaylistName.isNotBlank()) {
                            coroutineScope.launch { db.createPlaylist(PlaylistEntity(name = newPlaylistName.trim())) }
                            showCreatePlaylistDialog = false
                        }
                    }) { Text("Create") }
                },
                dismissButton = { TextButton(onClick = { showCreatePlaylistDialog = false }) { Text("Cancel") } }
            )
        }

        // --- DIALOG 4: ADD SONG TO PLAYLIST ---
        if (showAddToPlaylistDialog && selectedSongForAction != null) {
            AlertDialog(
                onDismissRequest = { showAddToPlaylistDialog = false; selectedSongForAction = null },
                title = { Text("Select Playlist") },
                text = {
                    LazyColumn {
                        if (customPlaylists.isEmpty()) item { Text("No playlists created yet.") }
                        items(customPlaylists) { playlist ->
                            TextButton(onClick = {
                                coroutineScope.launch { db.addSongToPlaylist(PlaylistSongCrossRef(playlistId = playlist.playlistId, localMediaId = selectedSongForAction!!.id)) }
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

@Composable
fun FullScreenPlayer(
    song: LocalSong, internetData: InternetSongData?, isPlaying: Boolean, currentPosition: Long, totalDuration: Long, isFavorite: Boolean,
    onClose: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit, onPrev: () -> Unit, onSeek: (Float) -> Unit, onToggleFavorite: () -> Unit
) {
    val displayTitle = internetData?.title ?: song.title
    val displayArtist = internetData?.artist ?: song.artist
    val displayArt = internetData?.artUrl?.takeIf { it.isNotEmpty() } ?: song.albumArtUri
    var showLyrics by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})) {
            key(displayArt) { SubcomposeAsyncImage(model = ImageRequest.Builder(LocalContext.current).data(displayArt).crossfade(true).build(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(radius = 60.dp), error = { BlueWhiteFallback(modifier = Modifier.fillMaxSize()) }, loading = { BlueWhiteFallback(modifier = Modifier.fillMaxSize()) }) }
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))

            Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Minimize", modifier = Modifier.size(36.dp), tint = Color.White) }
                    if (internetData?.lyrics != null) { IconButton(onClick = { showLyrics = !showLyrics }) { Icon(if (showLyrics) Icons.Default.MusicNote else Icons.Default.Subject, contentDescription = "Toggle Lyrics", tint = Color.White) } }
                }
                Spacer(modifier = Modifier.height(16.dp))

                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (showLyrics && internetData?.lyrics != null) {
                        val scrollState = rememberScrollState()
                        Text(text = internetData.lyrics, style = MaterialTheme.typography.titleMedium.copy(lineHeight = 28.sp), color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.fillMaxSize().verticalScroll(scrollState).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { showLyrics = false }))
                    } else {
                        Card(modifier = Modifier.fillMaxWidth().aspectRatio(1f).shadow(24.dp, RoundedCornerShape(32.dp)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { if (internetData?.lyrics != null) showLyrics = true }), shape = RoundedCornerShape(32.dp)) {
                            SubcomposeAsyncImage(model = ImageRequest.Builder(LocalContext.current).data(displayArt).crossfade(1000).build(), contentDescription = "Album Art", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), error = { BlueWhiteFallback(modifier = Modifier.fillMaxSize(), iconSize = 80.dp) }, loading = { BlueWhiteFallback(modifier = Modifier.fillMaxSize(), iconSize = 80.dp) })
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(displayTitle, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(displayArtist, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.8f), maxLines = 1)
                    }
                    IconButton(onClick = onToggleFavorite) { Icon(if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = "Favorite", modifier = Modifier.size(32.dp), tint = if (isFavorite) Color.Red else Color.White) }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Slider(value = if (totalDuration > 0) currentPosition.toFloat() / totalDuration.toFloat() else 0f, onValueChange = onSeek, modifier = Modifier.fillMaxWidth(), colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f)))
                val formatTime = { ms: Long -> val totalSeconds = ms / 1000; String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60) }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(currentPosition), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
                    Text(formatTime(totalDuration), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
                }

                Spacer(modifier = Modifier.height(32.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconButton(onClick = onPrev) { Icon(Icons.Default.SkipPrevious, contentDescription = "Prev", modifier = Modifier.size(48.dp), tint = Color.White) }
                    Box(modifier = Modifier.size(80.dp).clip(CircleShape).background(Color.White).clickable { onPlayPause() }, contentAlignment = Alignment.Center) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Play/Pause", modifier = Modifier.size(48.dp), tint = Color.Black) }
                    IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, contentDescription = "Next", modifier = Modifier.size(48.dp), tint = Color.White) }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun PlayerControlsBar(currentSong: LocalSong, internetData: InternetSongData?, isPlaying: Boolean, currentPosition: Long, totalDuration: Long, onPlayPause: () -> Unit, onNext: () -> Unit, onPrev: () -> Unit, onBarClick: () -> Unit) {
    val displayTitle = internetData?.title ?: currentSong.title
    val displayArtist = internetData?.artist ?: currentSong.artist
    val displayArt = internetData?.artUrl?.takeIf { it.isNotEmpty() } ?: currentSong.albumArtUri

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().clickable { onBarClick() }, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), shadowElevation = 8.dp) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            LinearProgressIndicator(progress = { if (totalDuration > 0) currentPosition.toFloat() / totalDuration.toFloat() else 0f }, modifier = Modifier.fillMaxWidth().height(2.dp), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SubcomposeAsyncImage(model = ImageRequest.Builder(LocalContext.current).data(displayArt).crossfade(true).build(), contentDescription = "Album Art", contentScale = ContentScale.Crop, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)), error = { BlueWhiteFallback(modifier = Modifier.fillMaxSize(), iconSize = 20.dp) }, loading = { BlueWhiteFallback(modifier = Modifier.fillMaxSize(), iconSize = 20.dp) })
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(displayTitle, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                    Text(displayArtist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), maxLines = 1)
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

private fun searchItunesAPI(query: String): InternetSongData? {
    try {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://itunes.apple.com/search?term=$encodedQuery&media=music&entity=song&limit=1")
        val connection = url.openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", "Mozilla/5.0")
        connection.connectTimeout = 3000
        connection.readTimeout = 3000

        if (connection.responseCode == 200) {
            val response = connection.inputStream.bufferedReader().readText()
            val results = JSONObject(response).optJSONArray("results")
            if (results != null && results.length() > 0) {
                val trackNode = results.getJSONObject(0)
                val officialTitle = trackNode.optString("trackName", "")
                val officialArtist = trackNode.optString("artistName", "")
                val rawArt = trackNode.optString("artworkUrl100", "").replace("100x100bb", "600x600bb")
                return InternetSongData(officialTitle, officialArtist, rawArt)
            }
        }
    } catch (e: Exception) { e.printStackTrace() }
    return null
}

private fun searchDeezerAPI(query: String): InternetSongData? {
    try {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://api.deezer.com/search?q=$encodedQuery&limit=1")
        val connection = url.openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", "Mozilla/5.0")
        connection.connectTimeout = 3000
        connection.readTimeout = 3000

        if (connection.responseCode == 200) {
            val response = connection.inputStream.bufferedReader().readText()
            val data = JSONObject(response).optJSONArray("data")
            if (data != null && data.length() > 0) {
                val trackNode = data.getJSONObject(0)
                val officialTitle = trackNode.optString("title", "")
                val artistNode = trackNode.optJSONObject("artist")
                val officialArtist = artistNode?.optString("name", "") ?: ""
                val albumNode = trackNode.optJSONObject("album")
                val highResArt = albumNode?.optString("cover_xl", "") ?: ""
                return InternetSongData(officialTitle, officialArtist, highResArt)
            }
        }
    } catch (e: Exception) { e.printStackTrace() }
    return null
}

private fun searchJioSaavnAPI(query: String): InternetSongData? {
    try {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://www.jiosaavn.com/api.php?__call=autocomplete.get&_format=json&_marker=0&cc=in&query=$encodedQuery")
        val connection = url.openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", "Mozilla/5.0")
        connection.connectTimeout = 3000
        connection.readTimeout = 3000

        if (connection.responseCode == 200) {
            val response = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(response)
            val songsArray = json.optJSONObject("songs")?.optJSONArray("data")
            
            if (songsArray != null && songsArray.length() > 0) {
                val trackNode = songsArray.getJSONObject(0)
                val officialTitle = trackNode.optString("title", "").replace("&quot;", "\"").replace("&amp;", "&")
                val moreInfo = trackNode.optJSONObject("more_info")
                val officialArtist = moreInfo?.optString("singers", "") ?: moreInfo?.optString("primary_artists", "") ?: ""
                val rawArt = trackNode.optString("image", "")
                val highResArt = rawArt.replace("50x50.jpg", "500x500.jpg")
                
                return InternetSongData(officialTitle, officialArtist, highResArt)
            }
        }
    } catch (e: Exception) { e.printStackTrace() }
    return null
}

private fun searchYouTubePipedAPI(query: String): InternetSongData? {
    try {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://pipedapi.kavin.rocks/search?q=$encodedQuery&filter=all")
        val connection = url.openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", "Mozilla/5.0")
        connection.connectTimeout = 4000
        connection.readTimeout = 4000

        if (connection.responseCode == 200) {
            val response = connection.inputStream.bufferedReader().readText()
            val items = JSONObject(response).optJSONArray("items")
            
            if (items != null && items.length() > 0) {
                for (i in 0 until items.length()) {
                    val trackNode = items.getJSONObject(i)
                    if (trackNode.optString("type") == "stream") {
                        val officialTitle = trackNode.optString("title", "")
                        val officialArtist = trackNode.optString("uploaderName", "")
                        val highResArt = trackNode.optString("thumbnail", "")
                        return InternetSongData(officialTitle, officialArtist, highResArt)
                    }
                }
            }
        }
    } catch (e: Exception) { e.printStackTrace() }
    return null
}

private fun searchLyricsAPI(title: String, artist: String): String? {
    try {
        val query = if (artist.isNotBlank()) "$title $artist" else title
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://lrclib.net/api/search?q=$encodedQuery")
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.connectTimeout = 3000
        conn.readTimeout = 3000

        if (conn.responseCode == 200) {
            val response = conn.inputStream.bufferedReader().readText()
            val jsonArray = JSONArray(response)
            for (i in 0 until jsonArray.length()) {
                val track = jsonArray.getJSONObject(i)
                val plainLyrics = track.optString("plainLyrics", "")
                val syncedLyrics = track.optString("syncedLyrics", "")

                if (plainLyrics.isNotBlank() && plainLyrics != "null") return plainLyrics
                if (syncedLyrics.isNotBlank() && syncedLyrics != "null") {
                    return syncedLyrics.replace(Regex("\\[.*?\\]"), "").trim()
                }
            }
        }
    } catch (e: Exception) { e.printStackTrace() }
    return null
}

suspend fun fetchMultiSourceMetadata(title: String, artist: String): InternetSongData? = coroutineScope {
    val cleanTitle = title.lowercase()
        .replace(".mp3", "").replace(".m4a", "").replace(".wav", "")
        .replace("y2mate.com", "").replace("y2mate", "")
        .replace("official video", "").replace("official audio", "")
        .replace("lyrics", "").replace("hd", "")
        .replace("slowed", "").replace("reverb", "") 
        .replace(Regex("\\b\\d{2,4}[-/_]\\d{2}[-/_]\\d{2,4}\\b"), "")
        .replace(Regex("\\b\\d{6,10}\\b"), "")
        .replace(Regex("[^a-zA-Z0-9 ]"), " ") 
        .replace(Regex("\\s+"), " ")
        .trim()

    val isUnknownArtist = artist.contains("unknown", ignoreCase = true)
    var result: InternetSongData? = null

    val strictItunesTask = async(Dispatchers.IO) { if (!isUnknownArtist) searchItunesAPI("$cleanTitle $artist") else null }
    val strictSaavnTask = async(Dispatchers.IO) { if (!isUnknownArtist) searchJioSaavnAPI("$cleanTitle $artist") else null }
    val strictDeezerTask = async(Dispatchers.IO) { if (!isUnknownArtist) searchDeezerAPI("$cleanTitle $artist") else null }
    val strictYoutubeTask = async(Dispatchers.IO) { if (!isUnknownArtist) searchYouTubePipedAPI("$cleanTitle $artist") else null }
    
    val strictLyricsTask = async(Dispatchers.IO) { searchLyricsAPI(cleanTitle, if (!isUnknownArtist) artist else "") }

    result = strictItunesTask.await() 
        ?: strictSaavnTask.await() 
        ?: strictDeezerTask.await() 
        ?: strictYoutubeTask.await()
        
    var foundLyrics = strictLyricsTask.await()

    if (result == null && isUnknownArtist) {
        val looseItunesTask = async(Dispatchers.IO) { searchItunesAPI(cleanTitle) }
        val looseSaavnTask = async(Dispatchers.IO) { searchJioSaavnAPI(cleanTitle) }
        val looseDeezerTask = async(Dispatchers.IO) { searchDeezerAPI(cleanTitle) }
        val looseYoutubeTask = async(async(Dispatchers.IO) { searchYouTubePipedAPI(cleanTitle) })
        
        result = looseItunesTask.await() ?: looseSaavnTask.await() ?: looseDeezerTask.await() ?: looseYoutubeTask.await()
    }
    
    if (foundLyrics == null && !isUnknownArtist) {
        foundLyrics = searchLyricsAPI(cleanTitle, "")
    }

    if (result != null) {
        return@coroutineScope result.copy(lyrics = foundLyrics)
    } else if (foundLyrics != null) {
        return@coroutineScope InternetSongData(cleanTitle, artist, "", foundLyrics)
    }

    return@coroutineScope null
}