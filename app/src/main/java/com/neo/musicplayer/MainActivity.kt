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

// MINIMALIST MONOCHROME THEME
private val MinBgLight = Color(0xFFFAFAFA)
private val MinSurfaceLight = Color(0xFFFFFFFF)
private val MinTextLight = Color(0xFF121212)
private val MinPrimaryLight = Color(0xFF000000)

private val MinBgDark = Color(0xFF0A0A0A)
private val MinSurfaceDark = Color(0xFF141414)
private val MinTextDark = Color(0xFFF5F5F5)
private val MinPrimaryDark = Color(0xFFFFFFFF)

@Composable
fun AestheticTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colorScheme = if (darkTheme) darkColorScheme(primary = MinPrimaryDark, background = MinBgDark, surface = MinSurfaceDark, onSurface = MinTextDark) else lightColorScheme(primary = MinPrimaryLight, background = MinBgLight, surface = MinSurfaceLight, onSurface = MinTextLight)
    MaterialTheme(colorScheme = colorScheme, content = content)
}

@Composable
fun BlueWhiteFallback(modifier: Modifier = Modifier, iconSize: Dp = 48.dp) {
    Box(modifier = modifier.background(Color.Gray.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, contentDescription = "Music", tint = Color.Gray.copy(alpha = 0.6f), modifier = Modifier.size(iconSize)) }
}

data class LocalSong(val id: Long, val title: String, val artist: String, val albumId: Long, val webStreamUrl: String? = null, val customArtUrl: String? = null) { val albumArtUri: Uri get() = if(customArtUrl != null) Uri.parse(customArtUrl.substringBefore("|||")) else Uri.parse("content://media/external/audio/albumart/$albumId") }
data class InternetSongData(val id: String = "", val title: String, val artist: String, val artUrl: String, val lyrics: String? = null)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { AestheticTheme { MusicPlayerUI() } } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(songId: Long, title: String, artist: String, artUrl: String, isPlaying: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        SubcomposeAsyncImage(model = artUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(52.dp).clip(RoundedCornerShape(6.dp)), error = { BlueWhiteFallback() }, loading = { BlueWhiteFallback() })
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium, fontSize = 16.sp, maxLines = 1, color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(2.dp))
            Text(artist, style = MaterialTheme.typography.bodySmall, fontSize = 13.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        if (songId < 0) Icon(Icons.Default.CloudDownload, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f), modifier = Modifier.size(20.dp))
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
    
    val sharedPrefs = remember { context.getSharedPreferences("NeoMusicPrefs", Context.MODE_PRIVATE) }
    
    var localSongs by remember { mutableStateOf<List<LocalSong>>(emptyList()) }
    val favoriteSongs = remember(favoriteMemories, localSongs) { favoriteMemories.mapNotNull { mem -> if (mem.localMediaId >= 0) localSongs.find { it.id == mem.localMediaId } else LocalSong(mem.localMediaId, mem.fetchedTitle ?: mem.customTitle ?: "Unknown", mem.fetchedArtist ?: mem.customArtist ?: "Unknown", -1L, null, mem.fetchedArtUrl) } }
    val recentlyPlayedSongs = remember(recentlyPlayedMemories, localSongs) { recentlyPlayedMemories.mapNotNull { mem -> if (mem.localMediaId >= 0) localSongs.find { it.id == mem.localMediaId } else LocalSong(mem.localMediaId, mem.fetchedTitle ?: mem.customTitle ?: "Unknown", mem.fetchedArtist ?: mem.customArtist ?: "Unknown", -1L, null, mem.fetchedArtUrl) } }
    
    var isSearchActive by remember { mutableStateOf(false) }; var searchQuery by remember { mutableStateOf("") }
    var permissionGranted by remember { mutableStateOf(false) }; var currentTab by remember { mutableIntStateOf(0) } 
    var viewingLikedSongs by remember { mutableStateOf(false) }; var viewingPlaylistId by remember { mutableStateOf<Long?>(null) }
    var currentPlaylistData by remember { mutableStateOf<PlaylistWithSongs?>(null) }
    
    LaunchedEffect(viewingPlaylistId) { viewingPlaylistId?.let { id -> currentPlaylistData = db.getPlaylistWithSongs(id) } }

    val languages = listOf("All", "Tamil", "English", "Hindi", "Malayalam", "Telugu", "Kannada", "Marathi", "Bengali", "Punjabi", "Gujarati")
    var selectedLanguage by remember { mutableStateOf(sharedPrefs.getString("saved_lang", "") ?: "") } 
    var isPlaylistMode by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (selectedLanguage.isBlank()) {
            val detectedLang = getAutoLanguage()
            selectedLanguage = if (languages.contains(detectedLang)) detectedLang else "Global"
            sharedPrefs.edit().putString("saved_lang", selectedLanguage).apply()
        }
    }

    var liveSearchResults by remember { mutableStateOf<List<InternetSongData>>(emptyList()) }; var isLiveSearching by remember { mutableStateOf(false) }

    LaunchedEffect(searchQuery, selectedLanguage, isPlaylistMode, isSearchActive) {
        if (!isSearchActive || selectedLanguage.isBlank()) return@LaunchedEffect
        
        if (searchQuery.isBlank()) {
            val cachedJson = sharedPrefs.getString("ghost_cache_${selectedLanguage}_${isPlaylistMode}", null)
            if (cachedJson != null) {
                try {
                    val arr = JSONArray(cachedJson); val ghostList = mutableListOf<InternetSongData>()
                    for (i in 0 until arr.length()) { val obj = arr.getJSONObject(i); ghostList.add(InternetSongData(obj.optString("id"), obj.optString("title"), obj.optString("artist"), obj.optString("artUrl"), null)) }
                    liveSearchResults = ghostList
                } catch(e: Exception){}
            }
        }

        val q = if (searchQuery.isBlank()) {
            if (selectedLanguage == "All") "Top Hits" else "$selectedLanguage Hits"
        } else {
            if (selectedLanguage == "All") searchQuery else "$searchQuery $selectedLanguage"
        }
        
        delay(400); isLiveSearching = true
        val freshData = fetchLiveSearchResults(q, selectedLanguage, isPlaylistMode)
        
        if (freshData.isNotEmpty()) {
            liveSearchResults = freshData
            if (searchQuery.isBlank()) {
                try {
                    val cacheArr = JSONArray()
                    freshData.take(15).forEach { song -> val obj = JSONObject().apply { put("id", song.id); put("title", song.title); put("artist", song.artist); put("artUrl", song.artUrl) }; cacheArr.put(obj) }
                    sharedPrefs.edit().putString("ghost_cache_${selectedLanguage}_${isPlaylistMode}", cacheArr.toString()).apply()
                } catch(e: Exception){}
            }
        }
        isLiveSearching = false
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
                                    val f = files.getJSONObject(i); val format = f.optString("format", "").lowercase(); val rawName = f.optString("name", ""); val lowerName = rawName.lowercase()
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
                    } catch (e: Exception) {}
                    list
                }
                
                if (iaPlaylist.isNotEmpty()) {
                    val parentId = -(kotlin.math.abs((webSongData.title + webSongData.artist).hashCode().toLong())).let { if(it==0L) -1L else it }
                    val packedArt = "${webSongData.artUrl}|||${webSongData.id}"
                    withContext(Dispatchers.IO) { 
                        db.saveSongMemory(SongEntity(parentId, webSongData.title, webSongData.artist, webSongData.title, webSongData.artist, packedArt, null, memoryMap[parentId]?.isFavorite ?: false, System.currentTimeMillis())) 
                    }
                    fetchedInternetData = webSongData; playSongList(iaPlaylist.first(), iaPlaylist); showFullScreenPlayer = true
                }
                return@launch
            }

            val cleanTitle = webSongData.title.lowercase().replace(Regex("[^a-z0-9]"), "")
            val localMatch = if (cleanTitle.isNotBlank()) localSongs.find { it.title.lowercase().replace(Regex("[^a-z0-9]"), "").let { n -> n == cleanTitle || n.contains(cleanTitle) || cleanTitle.contains(n) } } else null
            if (localMatch != null) { playSongList(localMatch, localSongs); showFullScreenPlayer = true; return@launch }
            
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
                if (autoPlayContext.isNotEmpty() && autoPlayIndex in 0 until autoPlayContext.size - 1) { 
                    coroutineScope.launch { delay(1000); autoPlayIndex++; playWebSong(autoPlayContext[autoPlayIndex]) } 
                }
            }
        }
    }

    val handleNext = {
        if (exoPlayer.mediaItemCount > 1) {
            exoPlayer.seekToNextMediaItem()
        } else if (autoPlayContext.isNotEmpty() && autoPlayIndex >= 0 && autoPlayIndex < autoPlayContext.size - 1) {
            autoPlayIndex++
            playWebSong(autoPlayContext[autoPlayIndex])
        }
    }

    val handlePrev = {
        if (exoPlayer.mediaItemCount > 1) {
            exoPlayer.seekToPreviousMediaItem()
        } else if (autoPlayContext.isNotEmpty() && autoPlayIndex > 0) {
            autoPlayIndex--
            playWebSong(autoPlayContext[autoPlayIndex])
        } else {
            exoPlayer.seekTo(0L)
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
                                val recQuery = "${cSong.artist} Hits"
                                val recs = fetchLiveSearchResults(recQuery, "All", false)
                                if (recs.isNotEmpty()) { 
                                    val nextSong = recs[0]
                                    withContext(Dispatchers.Main) { Toast.makeText(context, "Up next: ${nextSong.title}", Toast.LENGTH_LONG).show() }
                                    autoPlayContext = recs; autoPlayIndex = 0; playWebSong(nextSong) 
                                }
                            }
                        }
                    }
                }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { 
                if (mediaItem == null || exoPlayer.mediaItemCount == 0) { currentSong = null; isPlaying = false; showFullScreenPlayer = false } 
                else if (exoPlayer.currentMediaItemIndex in playQueue.indices) currentSong = playQueue[exoPlayer.currentMediaItemIndex] 
            }
        }
        exoPlayer.addListener(listener); onDispose { exoPlayer.removeListener(listener) }
    }

    LaunchedEffect(currentSong) {
        val song = currentSong ?: return@LaunchedEffect
        if (song.id < 0) return@LaunchedEffect 
        val mem = db.getSongMemory(song.id)
        val dTitle = mem?.customTitle?.takeIf { it.isNotBlank() } ?: mem?.fetchedTitle?.takeIf { it.i