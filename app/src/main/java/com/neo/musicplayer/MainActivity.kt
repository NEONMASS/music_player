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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.draw.shadow
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
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// --- THEME & MODELS ---
@Composable
fun AestheticTheme(content: @Composable () -> Unit) {
    val colorScheme = darkColorScheme(
        primary = Color.White,
        background = Color(0xFF0A0A0A),
        surface = Color(0xFF141414),
        onSurface = Color(0xFFF5F5F5)
    )
    MaterialTheme(colorScheme = colorScheme, content = content)
}

@Composable
fun FallbackIcon(size: Dp = 48.dp) {
    Box(
        modifier = Modifier.background(Color.Gray.copy(alpha = 0.2f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.Gray.copy(alpha = 0.6f), modifier = Modifier.size(size))
    }
}

data class LocalSong(
    val id: Long, val title: String, val artist: String, val albumId: Long,
    val webUrl: String? = null, val customArtUrl: String? = null
) {
    val uri: Uri
        get() = if (customArtUrl != null) Uri.parse(customArtUrl.substringBefore("|||"))
        else Uri.parse("content://media/external/audio/albumart/$albumId")
}

data class WebData(val id: String = "", val title: String, val artist: String, val artUrl: String, val lyrics: String? = null)

// --- MAIN ACTIVITY ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AestheticTheme { MusicPlayerUI() }
        }
    }
}

// --- UI COMPONENTS ---
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    songId: Long, title: String, artist: String, artUrl: String, isPlaying: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit, onAddQueue: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(artUrl).crossfade(true).build(),
            contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(6.dp)),
            error = { FallbackIcon() }, loading = { FallbackIcon() }
        )
        Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
            Text(
                text = title,
                fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium,
                fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = artist, style = MaterialTheme.typography.bodySmall, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        IconButton(onClick = onAddQueue) {
            Icon(Icons.Default.Add, contentDescription = "Add", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        }
    }
}

// --- MAIN SCREEN ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicPlayerUI() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(ctx).libraryDao() }

    // State Variables
    var localSongs by remember { mutableStateOf<List<LocalSong>>(emptyList()) }
    var searchRes by remember { mutableStateOf<List<WebData>>(emptyList()) }
    var isSearch by remember { mutableStateOf(false) }
    var isSearching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    var isAlbumMode by remember { mutableStateOf(false) }
    
    // Player State
    var curSong by remember { mutableStateOf<LocalSong?>(null) }
    var webMeta by remember { mutableStateOf<WebData?>(null) }
    var queue by remember { mutableStateOf<List<LocalSong>>(emptyList()) }
    var isPlaying by remember { mutableStateOf(false) }
    var pos by remember { mutableStateOf(0L) }
    var dur by remember { mutableStateOf(0L) }
    var isLive by remember { mutableStateOf(false) }
    var showFS by remember { mutableStateOf(false) }
    
    // AutoPlay Context
    var apCtx by remember { mutableStateOf<List<WebData>>(emptyList()) }
    var apIdx by remember { mutableIntStateOf(-1) }

    // Dialogs & Actions
    var actionSong by remember { mutableStateOf<LocalSong?>(null) }
    var viewFavs by remember { mutableStateOf(false) }
    var showNewP by remember { mutableStateOf(false) }

    // Database Flows
    val memories by db.getAllSongMemories().collectAsState(initial = emptyList())
    val memoryMap = remember(memories) { memories.associateBy { it.localMediaId } }
    val favs by db.getFavoriteSongs().collectAsState(initial = emptyList())
    val recents by db.getRecentlyPlayed().collectAsState(initial = emptyList())
    val playlists by db.getAllPlaylists().collectAsState(initial = emptyList())
    
    val fSongs = remember(favs, localSongs) {
        favs.mapNotNull { m ->
            if (m.localMediaId >= 0) localSongs.find { it.id == m.localMediaId }
            else LocalSong(m.localMediaId, m.fetchedTitle ?: "Unknown", m.fetchedArtist ?: "Unknown", -1L, null, m.fetchedArtUrl)
        }
    }
    
    val rSongs = remember(recents, localSongs) {
        recents.mapNotNull { m ->
            if (m.localMediaId >= 0) localSongs.find { it.id == m.localMediaId }
            else LocalSong(m.localMediaId, m.fetchedTitle ?: "Unknown", m.fetchedArtist ?: "Unknown", -1L, null, m.fetchedArtUrl)
        }
    }

    // Prefs & Languages
    val prefs = remember { ctx.getSharedPreferences("NeoPrefs", Context.MODE_PRIVATE) }
    val langs = listOf("All", "Tamil", "English", "Hindi", "Malayalam", "Telugu", "Kannada", "Marathi", "Bengali", "Punjabi", "Gujarati")
    var selLang by remember { mutableStateOf(prefs.getString("lang", "") ?: "") }

    LaunchedEffect(Unit) {
        if (selLang.isBlank()) {
            val detected = getAutoLang()
            selLang = if (langs.contains(detected)) detected else "All"
            prefs.edit().putString("lang", selLang).apply()
        }
    }

    // ExoPlayer Setup
    val exo = remember {
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ctx).setDataSourceFactory(DefaultDataSource.Factory(ctx, DefaultHttpDataSource.Factory().setUserAgent("Mozilla/5.0").setAllowCrossProtocolRedirects(true))))
            .build().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
                setHandleAudioBecomingNoisy(true)
                repeatMode = Player.REPEAT_MODE_OFF
            }
    }

    DisposableEffect(Unit) {
        onDispose { exo.release() }
    }

    // Permissions
    var permissionGranted by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
        if (granted) {
            localSongs = getLocalMusic(ctx)
        }
    }

    LaunchedEffect(Unit) {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        launcher.launch(perm)
    }

    // Player Functions
    fun playList(s: LocalSong, l: List<LocalSong>) {
        apCtx = emptyList()
        apIdx = -1
        queue = l
        exo.stop()
        exo.clearMediaItems()
        exo.setMediaItems(l.map { MediaItem.Builder().setUri(it.webUrl ?: ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it.id).toString()).setMediaId(it.id.toString()).build() })
        val idx = l.indexOf(s).coerceAtLeast(0)
        exo.seekTo(idx, C.TIME_UNSET)
        exo.prepare()
        exo.play()
    }

    fun playWeb(w: WebData) {
        scope.launch {
            if (w.id.startsWith("ia:")) {
                val iaQ = withContext(Dispatchers.IO) {
                    try {
                        val res = fetchHttp("https://archive.org/metadata/${w.id.removePrefix("ia:")}")
                        if (res != null) {
                            val files = JSONObject(res).optJSONArray("files")
                            if (files != null) {
                                val list = mutableListOf<LocalSong>()
                                val trackMap = mutableMapOf<String, JSONObject>()
                                for (i in 0 until files.length()) {
                                    val f = files.getJSONObject(i)
                                    val format = f.optString("format", "").lowercase()
                                    val name = f.optString("name", "").lowercase()
                                    if (name.endsWith(".xml") || name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".sqlite") || name.endsWith(".txt")) continue
                                    if (format.contains("mp3") || format.contains("flac") || format.contains("ogg") || name.endsWith(".mp3") || name.endsWith(".m4a")) {
                                        val base = name.substringBeforeLast(".")
                                        if (trackMap[base] == null || format.contains("mp3")) trackMap[base] = f
                                    }
                                }
                                val sorted = trackMap.values.sortedBy { it.optString("track", "999").replace("\\D".toRegex(), "").toIntOrNull() ?: 999 }
                                for (f in sorted) {
                                    val t = f.optString("title").takeIf { it.isNotBlank() } ?: f.optString("name").substringBeforeLast(".")
                                    val a = f.optString("creator").takeIf { it.isNotBlank() } ?: w.artist.replace("[Album] ", "")
                                    val sId = generateDummyId(t, a)
                                    list.add(LocalSong(sId, t, a, -1L, "https://archive.org/download/${w.id.removePrefix("ia:")}/${Uri.encode(f.optString("name"))}", "${w.artUrl}|||ia:${w.id}"))
                                }
                                return@withContext list
                            }
                        }
                    } catch (e: Exception) {}
                    emptyList<LocalSong>()
                }

                if (iaQ.isNotEmpty()) {
                    val pId = generateDummyId(w.title, w.artist)
                    withContext(Dispatchers.IO) {
                        db.saveSongMemory(SongEntity(pId, w.title, w.artist, w.title, w.artist, "${w.artUrl}|||${w.id}", null, memoryMap[pId]?.isFavorite ?: false, System.currentTimeMillis()))
                    }
                    webMeta = w
                    playList(iaQ.first(), iaQ)
                    showFS = true
                }
                return@launch
            }

            val tMatch = w.title.lowercase().replace("[^a-z0-9]".toRegex(), "")
            val local = if (tMatch.isNotBlank()) localSongs.find { it.title.lowercase().replace("[^a-z0-9]".toRegex(), "").let { n -> n == tMatch || n.contains(tMatch) || tMatch.contains(n) } } else null

            if (local != null) {
                playList(local, localSongs)
                showFS = true
                return@launch
            }

            val streamUrl = fetchAudioUrl(w.id)
            if (streamUrl != null) {
                val id = generateDummyId(w.title, w.artist)
                val s = LocalSong(id, w.title, w.artist, -1L, streamUrl, "${w.artUrl}|||${w.id}")
                withContext(Dispatchers.IO) {
                    db.saveSongMemory(SongEntity(id, w.title, w.artist, w.title, w.artist, "${w.artUrl}|||${w.id}", null, memoryMap[id]?.isFavorite ?: false, System.currentTimeMillis()))
                }
                webMeta = w
                queue = listOf(s)
                exo.stop()
                exo.clearMediaItems()
                exo.setMediaItem(MediaItem.Builder().setUri(streamUrl).setMediaId(id.toString()).build())
                exo.prepare()
                exo.play()
                showFS = true
            } else if (apCtx.isNotEmpty() && apIdx < apCtx.size - 1) {
                apIdx++
                playWeb(apCtx[apIdx])
            }
        }
    }

    fun handleNext() {
        if (exo.mediaItemCount > 1) {
            exo.seekToNextMediaItem()
        } else if (apCtx.isNotEmpty() && apIdx < apCtx.size - 1) {
            apIdx++
            playWeb(apCtx[apIdx])
        }
    }

    fun handlePrev() {
        if (exo.mediaItemCount > 1) {
            exo.seekToPreviousMediaItem()
        } else if (apCtx.isNotEmpty() && apIdx > 0) {
            apIdx--
            playWeb(apCtx[apIdx])
        } else {
            exo.seekTo(0L)
        }
    }

    fun handleAddQueue(s: LocalSong, w: WebData?) {
        scope.launch {
            if (s.id < 0) {
                val url = fetchAudioUrl(w?.id ?: s.customArtUrl?.substringAfter("|||") ?: "")
                if (url != null) {
                    val ns = s.copy(webUrl = url)
                    queue = queue + ns
                    exo.addMediaItem(MediaItem.fromUri(url))
                    if (!isPlaying && exo.mediaItemCount == 1) { exo.prepare(); exo.play() }
                    Toast.makeText(ctx, "Added to Queue", Toast.LENGTH_SHORT).show()
                }
            } else {
                queue = queue + s
                exo.addMediaItem(MediaItem.fromUri(s.uri))
                if (!isPlaying && exo.mediaItemCount == 1) { exo.prepare(); exo.play() }
                Toast.makeText(ctx, "Added to Queue", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Player State Listeners
    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) { isPlaying = p }
            override fun onPlaybackStateChanged(s: Int) {
                if (s == Player.STATE_ENDED) {
                    if (apCtx.isNotEmpty() && apIdx < apCtx.size - 1) {
                        apIdx++
                        playWeb(apCtx[apIdx])
                    } else if ((curSong?.id ?: 0L) < 0L) {
                        scope.launch {
                            val qArtist = curSong?.artist ?: return@launch
                            val r = fetchWebSearch("$qArtist Hits", "$qArtist Hits", false)
                            if (r.isNotEmpty()) {
                                withContext(Dispatchers.Main) { Toast.makeText(ctx, "Up next: ${r[0].title}", Toast.LENGTH_LONG).show() }
                                apCtx = r
                                apIdx = 0
                                playWeb(r[0])
                            }
                        }
                    }
                }
            }
            override fun onMediaItemTransition(m: MediaItem?, r: Int) {
                if (m == null || exo.mediaItemCount == 0) {
                    curSong = null
                    isPlaying = false
                    showFS = false
                } else if (exo.currentMediaItemIndex in queue.indices) {
                    curSong = queue[exo.currentMediaItemIndex]
                }
            }
        }
        exo.addListener(listener)
        onDispose { exo.removeListener(listener) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            pos = exo.currentPosition
            val d = exo.duration
            dur = if (d == C.TIME_UNSET) 0L else d
            isLive = exo.isCurrentMediaItemLive || (dur == 0L && exo.playbackState == Player.STATE_READY)
            delay(500L)
        }
    }

    // Search Logic
    LaunchedEffect(query, selLang, isAlbumMode, isSearch) {
        if (!isSearch) return@LaunchedEffect
        val activeLang = if (isAlbumMode) "All" else selLang

        if (query.isBlank()) {
            val cached = prefs.getString("gc_${activeLang}_$isAlbumMode", null)
            if (cached != null) {
                try {
                    val arr = JSONArray(cached)
                    val list = mutableListOf<WebData>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        list.add(WebData(o.optString("id"), o.optString("title"), o.optString("artist"), o.optString("artUrl")))
                    }
                    searchRes = list
                } catch(e: Exception){}
            }
        }

        delay(400)
        isSearching = true
        val qStr = if (query.isBlank()) {
            if (activeLang == "All" || activeLang.isBlank()) "Top Hits" else "$activeLang Hits"
        } else {
            if (activeLang == "All" || activeLang.isBlank()) query else "$query $activeLang"
        }

        val fresh = fetchWebSearch(qStr, query, isAlbumMode)
        if (fresh.isNotEmpty()) {
            searchRes = fresh
            if (query.isBlank()) {
                try {
                    val cacheArr = JSONArray()
                    fresh.take(15).forEach {
                        val obj = JSONObject()
                        obj.put("id", it.id)
                        obj.put("title", it.title)
                        obj.put("artist", it.artist)
                        obj.put("artUrl", it.artUrl)
                        cacheArr.put(obj)
                    }
                    prefs.edit().putString("gc_${activeLang}_$isAlbumMode", cacheArr.toString()).apply()
                } catch(e: Exception){}
            }
        }
        isSearching = false
    }

    // Scaffold UI
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSearch) {
                        TextField(
                            value = query, onValueChange = { query = it },
                            placeholder = { Text("Search...") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            )
                        )
                    } else {
                        Text(
                            text = when (tab) {
                                0 -> "Home"
                                1 -> "Discover"
                                2 -> "Library"
                                3 -> "Radio"
                                else -> "Offline"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { isSearch = !isSearch; query = "" }) {
                        Icon(if (isSearch) Icons.Default.Close else Icons.Default.Search, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.95f))
            )
        },
        bottomBar = {
            Column {
                if (curSong != null) {
                    Surface(modifier = Modifier.fillMaxWidth().padding(12.dp, 8.dp).clickable { showFS = true }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
                        Column {
                            LinearProgressIndicator(progress = { if (dur > 0) (pos.toFloat()/dur).coerceIn(0f,1f) else 0f }, modifier = Modifier.fillMaxWidth().height(2.dp), color = MaterialTheme.colorScheme.onSurface, trackColor = Color.Transparent)
                            Row(modifier = Modifier.padding(12.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                val dImg = webMeta?.artUrl?.substringBefore("|||") ?: curSong?.customArtUrl?.substringBefore("|||") ?: curSong?.uri.toString()
                                SubcomposeAsyncImage(model = ImageRequest.Builder(LocalContext.current).data(dImg).crossfade(true).build(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)), error = { FallbackIcon(20.dp) })
                                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(webMeta?.title ?: curSong!!.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(webMeta?.artist ?: curSong!!.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(onClick = { if (isPlaying) exo.pause() else exo.play() }) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, modifier = Modifier.size(32.dp)) }
                                IconButton(onClick = { handleNext() }) { Icon(Icons.Default.SkipNext, null) }
                            }
                        }
                    }
                }
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    NavigationBarItem(icon = { Icon(Icons.Default.Home, null) }, label = { Text("Home", fontSize = 10.sp) }, selected = tab == 0 && !isSearch, onClick = { tab = 0; isSearch = false; viewFavs = false })
                    NavigationBarItem(icon = { Icon(Icons.Default.Search, null) }, label = { Text("Search", fontSize = 10.sp) }, selected = isSearch, onClick = { isSearch = true; tab = 1 })
                    NavigationBarItem(icon = { Icon(Icons.Default.QueueMusic, null) }, label = { Text("Library", fontSize = 10.sp) }, selected = tab == 2 && !isSearch, onClick = { tab = 2; isSearch = false })
                    NavigationBarItem(icon = { Icon(Icons.Default.Radio, null) }, label = { Text("Radio", fontSize = 10.sp) }, selected = tab == 3 && !isSearch, onClick = { tab = 3; isSearch = false; viewFavs = false })
                    NavigationBarItem(icon = { Icon(Icons.Default.Folder, null) }, label = { Text("Offline", fontSize = 10.sp) }, selected = tab == 4 && !isSearch, onClick = { tab = 4; isSearch = false; viewFavs = false })
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { pad ->
        Column(modifier = Modifier.fillMaxSize().padding(pad)) {
            if (!permissionGranted) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Storage Permission Required.") }
            } else if (isSearch) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !isAlbumMode, onClick = { isAlbumMode = false }, label = { Text("Tracks") }, shape = RoundedCornerShape(20.dp))
                        FilterChip(selected = isAlbumMode, onClick = { isAlbumMode = true }, label = { Text("Albums") }, shape = RoundedCornerShape(20.dp))
                    }
                    AnimatedVisibility(visible = !isAlbumMode) {
                        LazyRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(langs) { l ->
                                FilterChip(selected = selLang == l, onClick = { selLang = l; prefs.edit().putString("lang", l).apply() }, label = { Text(l) }, shape = RoundedCornerShape(20.dp))
                            }
                        }
                    }
                }
                if (isSearching) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurface) }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(searchRes) { s ->
                            TrackRow(
                                songId = -1L, title = s.title, artist = s.artist, artUrl = s.artUrl, isPlaying = false,
                                onClick = { apCtx = searchRes; apIdx = searchRes.indexOf(s); playWeb(s) },
                                onLongClick = { actionSong = LocalSong(generateDummyId(s.title, s.artist), s.title, s.artist, -1L, null, "${s.artUrl}|||${s.id}"); webMeta = s },
                                onAddQueue = { handleAddQueue(LocalSong(generateDummyId(s.title, s.artist), s.title, s.artist, -1L, null, "${s.artUrl}|||${s.id}"), s) }
                            )
                        }
                    }
                }
            } else {
                when (tab) {
                    0 -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        if (rSongs.isNotEmpty()) {
                            item { Text("Recently Played", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
                            item {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(horizontal = 16.dp), modifier = Modifier.padding(bottom = 32.dp)) {
                                    items(rSongs) { s ->
                                        val m = memoryMap[s.id]
                                        val dT = m?.customTitle?.takeIf { it.isNotBlank() } ?: m?.fetchedTitle?.takeIf { it.isNotBlank() } ?: s.title
                                        val dArt = m?.fetchedArtUrl?.substringBefore("|||") ?: s.uri.toString()
                                        val realId = m?.fetchedArtUrl?.substringAfter("|||", "") ?: ""

                                        Column(modifier = Modifier.width(120.dp).clickable {
                                            if (s.id < 0) {
                                                apCtx = rSongs.filter { it.id < 0 }.map { WebData(it.customArtUrl?.substringAfter("|||") ?: "", it.title, it.artist, it.customArtUrl?.substringBefore("|||") ?: "") }
                                                apIdx = apCtx.indexOfFirst { it.title == dT }
                                                if (s.webStreamUrl != null) {
                                                    queue = listOf(s); exo.setMediaItem(MediaItem.fromUri(s.webStreamUrl)); exo.prepare(); exo.play(); curSong = s
                                                } else {
                                                    playWeb(WebData(realId, dT, s.artist, dArt))
                                                }
                                            } else {
                                                queue = rSongs.filter { it.id >= 0 }
                                                val idx = queue.indexOf(s)
                                                exo.setMediaItems(queue.map { MediaItem.fromUri(it.uri) })
                                                exo.seekTo(idx, 0L); exo.prepare(); exo.play()
                                            }
                                        }) {
                                            SubcomposeAsyncImage(model = dArt, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(120.dp).clip(RoundedCornerShape(8.dp)), error = { FallbackIcon() })
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(dT, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    2 -> {
                        if (viewFavs) {
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { viewFavs = false }) { Icon(Icons.Default.ArrowBack, null) }
                                Text("Liked Songs", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            }
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(fSongs) { s ->
                                    val m = memoryMap[s.id]
                                    val dT = m?.customTitle ?: s.title; val dA = m?.customArtist ?: s.artist; val dArt = m?.fetchedArtUrl?.substringBefore("|||") ?: s.uri.toString(); val realId = m?.fetchedArtUrl?.substringAfter("|||", "") ?: ""
                                    TrackRow(s.id, dT, dA, dArt, curSong?.id == s.id,
                                        onClick = { if (s.id < 0) playWeb(WebData(realId, dT, dA, dArt)) else { queue = fSongs.filter { it.id >= 0 }; exo.setMediaItems(queue.map { MediaItem.fromUri(it.uri) }); exo.seekTo(queue.indexOf(s), 0L); exo.prepare(); exo.play() } },
                                        onLongClick = { actionSong = s },
                                        onAddQueue = { handleAddQueue(s, WebData(realId, dT, dA, dArt)) }
                                    )
                                }
                            }
                        } else {
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Your Library", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                IconButton(onClick = { showNewP = true }) { Icon(Icons.Default.Add, null) }
                            }
                            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                                item {
                                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).clickable { viewFavs = true }, verticalAlignment = Alignment.CenterVertically) {
                                        Box(modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Favorite, null, tint = Color.White) }
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Column { Text("Liked Songs", fontWeight = FontWeight.Bold, fontSize = 16.sp); Text("${fSongs.size} tracks", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), fontSize = 13.sp) }
                                    }
                                }
                                items(playlists) { playlist ->
                                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
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
                            WebData("yt:jfKfPfyJRdk", "Lofi Girl (Beats to Relax/Study)", "Lofi Girl", "https://img.youtube.com/vi/jfKfPfyJRdk/hqdefault.jpg"),
                            WebData("yt:4xDzrUhVKcg", "Synthwave Radio (Spacewave)", "Lofi Girl", "https://img.youtube.com/vi/4xDzrUhVKcg/hqdefault.jpg"),
                            WebData("yt:5yx6BWlEVcY", "Chillhop Radio (Jazzy/Lofi)", "Chillhop Music", "https://img.youtube.com/vi/5yx6BWlEVcY/hqdefault.jpg"),
                            WebData("yt:1t4K450f3qM", "Spinnin' Records 24/7", "Spinnin' Records", "https://img.youtube.com/vi/1t4K450f3qM/hqdefault.jpg"),
                            WebData("yt:7NOSDKb0HlU", "Chillout Lounge Relax", "Chillout", "https://img.youtube.com/vi/7NOSDKb0HlU/hqdefault.jpg")
                        )
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            item { Text("24/7 Live Radio", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp)) }
                            items(stations) { s ->
                                TrackRow(
                                    songId = -1L, title = s.title, artist = s.artist, artUrl = s.artUrl, isPlaying = curSong?.title == s.title,
                                    onClick = { playWeb(s) }, onLongClick = {}, onAddQueue = { handleAddQueue(LocalSong(generateDummyId(s.title, s.artist), s.title, s.artist, -1L, null, "${s.artUrl}|||${s.id}"), s) }
                                )
                            }
                        }
                    }
                    4 -> {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(localSongs) { s ->
                                val m = memoryMap[s.id]
                                val dT = m?.customTitle ?: s.title; val dA = m?.customArtist ?: s.artist; val dArt = m?.fetchedArtUrl?.substringBefore("|||") ?: s.uri.toString()
                                TrackRow(s.id, dT, dA, dArt, curSong?.id == s.id,
                                    onClick = { queue = localSongs; val idx = localSongs.indexOf(s); exo.setMediaItems(localSongs.map { MediaItem.fromUri(it.uri) }); exo.seekTo(idx, 0L); exo.prepare(); exo.play() },
                                    onLongClick = { actionSong = s }, onAddQueue = { handleAddQueue(s, null) }
                                )
                            }
                        }
                    }
                }
            }
        }
        
        AnimatedVisibility(visible = showFS && curSong != null, enter = slideInVertically(initialOffsetY = { it }), exit = slideOutVertically(targetOffsetY = { it })) {
            if (curSong != null) {
                var sL by remember { mutableStateOf(false) }
                var sQ by remember { mutableStateOf(false) }
                val dTitle = webMeta?.title ?: curSong!!.title
                val dArtist = webMeta?.artist ?: curSong!!.artist
                val dArt = webMeta?.artUrl?.substringBefore("|||") ?: curSong!!.customArtUrl?.substringBefore("|||") ?: curSong!!.uri.toString()

                Box(modifier = Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) {
                    SubcomposeAsyncImage(model = ImageRequest.Builder(LocalContext.current).data(dArt).crossfade(1000).build(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(60.dp), error = { Box(Modifier.fillMaxSize().background(Color.DarkGray)) })
                    Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.2f), Color.Black.copy(alpha = 0.9f), Color.Black), 0f, 2000f)))
                    
                    Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { showFS = false }) { Icon(Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(36.dp), tint = Color.White) }
                            Row {
                                IconButton(onClick = { sQ = !sQ; if(sQ) sL = false }) { Icon(if (sQ) Icons.Default.QueueMusic else Icons.Default.FormatListBulleted, null, tint = Color.White) }
                                if (webMeta?.lyrics != null) {
                                    IconButton(onClick = { sL = !sL; if(sL) sQ = false }) { Icon(if (sL) Icons.Default.MusicNote else Icons.Default.Subject, null, tint = Color.White) }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        
                        if (sQ) {
                            Column(modifier = Modifier.fillMaxWidth().height(400.dp).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp)).padding(8.dp)) {
                                Text("Up Next", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    itemsIndexed(queue) { i, qs ->
                                        val m = memoryMap[qs.id]
                                        Row(modifier = Modifier.fillMaxWidth().clickable { 
                                            if (qs.id < 0 && qs.webStreamUrl == null) playWeb(WebData(qs.customArtUrl?.substringAfter("|||") ?: "", qs.title, qs.artist, qs.customArtUrl?.substringBefore("|||") ?: "")) 
                                            else { exo.seekTo(i, 0L); exo.play() } 
                                        }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(if (qs.id == curSong!!.id) Icons.Default.PlayArrow else Icons.Default.MusicNote, null, tint = Color.White, modifier = Modifier.size(20.dp))
                                            Text(m?.customTitle ?: qs.title, color = Color.White, modifier = Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 1)
                                            IconButton(onClick = { 
                                                val nQ = queue.toMutableList(); nQ.removeAt(i); queue = nQ
                                                try { exo.removeMediaItem(i) } catch(e: Exception){}
                                                if (exo.mediaItemCount == 0) { exo.stop(); isPlaying = false; showFS = false; curSong = null } 
                                            }) { Icon(Icons.Default.Close, null, tint = Color.White.copy(alpha = 0.5f)) }
                                        }
                                    }
                                }
                            }
                        } else if (sL && webMeta?.lyrics != null) {
                            Text(text = webMeta!!.lyrics!!, style = MaterialTheme.typography.titleMedium.copy(lineHeight = 28.sp), color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().height(400.dp).verticalScroll(rememberScrollState()))
                        } else {
                            SubcomposeAsyncImage(model = ImageRequest.Builder(LocalContext.current).data(dArt).crossfade(1000).build(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(1f).shadow(24.dp, RoundedCornerShape(32.dp)), error = { FallbackIcon(80.dp) })
                        }
                        
                        Spacer(modifier = Modifier.height(32.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(dTitle, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(dArtist, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.6f), maxLines = 1)
                            }
                            IconButton(onClick = { scope.launch { db.updateFavoriteStatus(curSong!!.id, !(memoryMap[curSong!!.id]?.isFavorite ?: false)) } }) { 
                                Icon(if (memoryMap[curSong?.id]?.isFavorite == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, modifier = Modifier.size(32.dp), tint = Color.White) 
                            }
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        Slider(value = if (!isLive && dur > 0) (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f, onValueChange = { p -> if (!isLive) { val np = (p * dur).toLong(); exo.seekTo(np); pos = np } }, modifier = Modifier.fillMaxWidth(), colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.2f)))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            val f = { ms: Long -> String.format("%02d:%02d", ms/1000/60, ms/1000%60) }
                            Text(if (isLive) "LIVE" else f(pos), color = Color.White.copy(alpha = 0.5f))
                            Text(if (isLive) "LIVE" else f(dur), color = Color.White.copy(alpha = 0.5f))
                        }
                        Spacer(modifier = Modifier.height(32.dp))
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                            IconButton(onClick = { handlePrev() }) { Icon(Icons.Default.SkipPrevious, null, modifier = Modifier.size(48.dp), tint = Color.White) }
                            Box(modifier = Modifier.size(80.dp).clip(CircleShape).background(Color.White).clickable { if (isPlaying) exo.pause() else exo.play() }, contentAlignment = Alignment.Center) { 
                                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, modifier = Modifier.size(48.dp), tint = Color.Black) 
                            }
                            IconButton(onClick = { handleNext() }) { Icon(Icons.Default.SkipNext, null, modifier = Modifier.size(48.dp), tint = Color.White) }
                        }
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }
            }
        }
        
        if (actionSong != null) {
            val s = actionSong!!
            AlertDialog(
                onDismissRequest = { actionSong = null },
                title = { Text("Options") },
                text = {
                    Column {
                        TextButton(onClick = { handleAddQueue(s, webMeta); actionSong = null }, modifier = Modifier.fillMaxWidth()) { Text("Add to Queue") }
                        if (s.id >= 0) { TextButton(onClick = { showEdit = true }, modifier = Modifier.fillMaxWidth()) { Text("Edit Metadata") } }
                    }
                },
                confirmButton = { TextButton(onClick = { actionSong = null }) { Text("Cancel") } }
            )
        }
        
        if (showEdit && actionSong != null) {
            val s = actionSong!!
            val m = memoryMap[s.id]
            var eT by remember { mutableStateOf(m?.customTitle ?: s.title) }
            var eA by remember { mutableStateOf(m?.customArtist ?: s.artist) }
            
            AlertDialog(
                onDismissRequest = { showEdit = false; actionSong = null },
                title = { Text("Fix Metadata") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(value = eT, onValueChange = { eT = it }, label = { Text("Title") }, singleLine = true)
                        OutlinedTextField(value = eA, onValueChange = { eA = it }, label = { Text("Artist") }, singleLine = true)
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        scope.launch {
                            db.saveSongMemory(SongEntity(s.id, eT.trim().takeIf { it.isNotEmpty() }, eA.trim().takeIf { it.isNotEmpty() }, null, null, null, null, m?.isFavorite ?: false, m?.lastPlayedAt ?: 0L))
                            showEdit = false; actionSong = null; playList(s, localSongs)
                        }
                    }) { Text("Save") }
                }
            )
        }
    }
}

// --- HELPER FUNCTIONS ---
fun generateDummyId(title: String, artist: String): Long {
    val h = kotlin.math.abs((title + artist).hashCode().toLong())
    return if (h == 0L) -1L else -h
}

fun getLocalMusic(ctx: Context): List<LocalSong> {
    val songs = mutableListOf<LocalSong>()
    val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM_ID)
    ctx.contentResolver.query(uri, proj, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} ASC")?.use { c ->
        val iC = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val tC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val aC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val alC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        while (c.moveToNext()) {
            val t = c.getString(tC) ?: "Unknown"
            if (!t.matches(Regex("(?i)(.*\\d{8}.*|.*aud-.*|.*ptt-.*|.*wa00.*|.*record.*)"))) {
                songs.add(LocalSong(c.getLong(iC), t, c.getString(aC) ?: "Unknown", c.getLong(alC)))
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

suspend fun getAutoLang(): String = withContext(Dispatchers.IO) {
    try {
        val res = fetchHttp("https://ipwho.is/") ?: return@withContext "All"
        val json = JSONObject(res)
        if (json.optString("country") == "India") {
            val r = json.optString("region")
            if (r.contains("Tamil", true)) return@withContext "Tamil"
            if (r.contains("Maharashtra", true)) return@withContext "Marathi"
            return@withContext "Hindi"
        }
    } catch (e: Exception) {}
    return@withContext "All"
}

suspend fun fetchWebSearch(q: String, rawQ: String, isAlbum: Boolean): List<WebData> = coroutineScope {
    val qE = URLEncoder.encode(q, "UTF-8")
    
    val saavnTask = async(Dispatchers.IO) {
        if (isAlbum) return@async emptyList<WebData>()
        val apis = listOf("https://saavn.dev/api/search/songs?query=", "https://saavn.sumit.co/api/search/songs?query=")
        val defs = apis.map { api ->
            async {
                try {
                    val res = fetchHttp("$api$qE", 3000) ?: return@async emptyList<WebData>()
                    val arr = JSONObject(res).optJSONObject("data")?.optJSONArray("results") ?: return@async emptyList<WebData>()
                    val list = mutableListOf<WebData>()
                    for (i in 0 until arr.length()) {
                        val t = arr.getJSONObject(i)
                        list.add(WebData(t.optString("id"), t.optString("name", t.optString("title")).replace("&quot;", "\""), t.optJSONArray("primaryArtists")?.optJSONObject(0)?.optString("name") ?: "", t.optJSONArray("image")?.optJSONObject(2)?.optString("link") ?: ""))
                    }
                    list
                } catch(e: Exception){ emptyList<WebData>() }
            }
        }
        val results = defs.awaitAll()
        for (r in results) { if (r.isNotEmpty()) return@async r }
        
        try {
            val res = fetchHttp("https://www.jiosaavn.com/api.php?__call=autocomplete.get&_format=json&_marker=0&cc=in&query=$qE", 3000)
            if (res != null) {
                val arr = JSONObject(res).optJSONObject("songs")?.optJSONArray("data")
                if (arr != null) {
                    val list = mutableListOf<WebData>()
                    for (i in 0 until arr.length()) {
                        val t = arr.getJSONObject(i)
                        list.add(WebData(t.optString("id"), t.optString("title", t.optString("name")).replace("&quot;", "\""), t.optJSONObject("more_info")?.optString("primary_artists") ?: "", t.optString("image").replace("50x50.jpg", "500x500.jpg")))
                    }
                    return@async list
                }
            }
        } catch(e: Exception){}
        emptyList<WebData>()
    }
    
    val archiveTask = async(Dispatchers.IO) {
        if (!isAlbum) return@async emptyList<WebData>()
        try {
            val qBase = if (rawQ.isBlank()) "(subject:\"music\" OR subject:\"soundtrack\" OR subject:\"ost\")" else "($rawQ)"
            val exact = "$qBase AND mediatype:audio AND (subject:\"ost\" OR subject:\"music\" OR subject:\"soundtrack\") AND NOT subject:\"news\" AND NOT subject:\"podcast\" AND NOT subject:\"ep\" AND NOT subject:\"broadcast\" AND NOT creator:\"voa\""
            val res = fetchHttp("https://archive.org/advancedsearch.php?q=${URLEncoder.encode(exact, "UTF-8")}&fl[]=identifier,title,creator&rows=15&output=json", 4000)
            if (res != null) {
                val docs = JSONObject(res).optJSONObject("response")?.optJSONArray("docs")
                val list = mutableListOf<WebData>()
                if (docs != null) {
                    for (i in 0 until docs.length()) {
                        val t = docs.getJSONObject(i)
                        val id = t.optString("identifier")
                        if (id.isNotBlank()) list.add(WebData("ia:$id", t.optString("title"), "[Album] ${t.optString("creator")}", "https://archive.org/services/img/$id"))
                    }
                }
                return@async list
            }
        } catch(e: Exception){}
        emptyList<WebData>()
    }
    
    val pipedTask = async(Dispatchers.IO) {
        if (isAlbum) return@async emptyList<WebData>()
        val instances = listOf("pipedapi.kavin.rocks", "pipedapi.tokhmi.xyz", "piped.projectsegfau.lt", "pipedapi.smnz.de")
        val defs = instances.map { inst ->
            async {
                try {
                    val res = fetchHttp("https://$inst/search?q=$qE&filter=music_songs", 3000) ?: return@async emptyList<WebData>()
                    val items = JSONObject(res).optJSONArray("items") ?: return@async emptyList<WebData>()
                    val list = mutableListOf<WebData>()
                    for (i in 0 until minOf(items.length(), 15)) {
                        val t = items.getJSONObject(i)
                        if (t.optString("type") == "stream") {
                            val vid = t.optString("url").replace("/watch?v=", "").substringBefore("&")
                            if (vid.isNotBlank()) list.add(WebData("yt:$vid", t.optString("title"), t.optString("uploaderName"), t.optString("thumbnail")))
                        }
                    }
                    list
                } catch(e: Exception){ emptyList<WebData>() }
            }
        }
        val results = defs.awaitAll()
        for (r in results) { if (r.isNotEmpty()) return@async r }
        emptyList<WebData>()
    }
    
    val combined = mutableListOf<WebData>()
    val sRes = saavnTask.await()
    val aRes = archiveTask.await()
    val pRes = pipedTask.await()
    
    combined.addAll(sRes)
    combined.addAll(aRes)
    combined.addAll(pRes)
    
    return@coroutineScope combined.distinctBy { it.title.lowercase() }
}

suspend fun fetchAudioUrl(id: String): String? = withContext(Dispatchers.IO) {
    if (!id.startsWith("ia:") && !id.startsWith("yt:")) {
        val apis = listOf("saavn.dev", "saavn.sumit.co")
        for (api in apis) {
            try {
                val res = fetchHttp("https://$api/api/songs?ids=$id", 4000)
                if (res != null) {
                    val data = JSONObject(res).optJSONArray("data")
                    if (data != null && data.length() > 0) {
                        val urls = data.getJSONObject(0).optJSONArray("downloadUrl")
                        if (urls != null) return@withContext urls.getJSONObject(urls.length()-1).optString("link")
                    }
                }
            } catch(e: Exception){}
        }
    } else if (id.startsWith("yt:")) {
        val vid = id.removePrefix("yt:")
        val instances = listOf("pipedapi.kavin.rocks", "pipedapi.tokhmi.xyz", "piped.projectsegfau.lt", "pipedapi.smnz.de")
        val defs = instances.map { inst ->
            async {
                try {
                    val res = fetchHttp("https://$inst/streams/$vid", 4000) ?: return@async null
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
                    bestUrl
                } catch(e: Exception){ null }
            }
        }
        val results = defs.awaitAll()
        for (r in results) { if (r != null) return@withContext r }
    }
    return@withContext null
}

suspend fun fetchMetadata(title: String, artist: String): WebData? = coroutineScope {
    val cleanTitle = title.lowercase().replace(".mp3", "").replace(".m4a", "").replace(Regex("\\[.*?\\]|\\(.*?\\)"), "").trim()
    
    val t1 = async<WebData?>(Dispatchers.IO) {
        try {
            val res = fetchHttp("https://itunes.apple.com/search?term=${URLEncoder.encode("$cleanTitle $artist", "UTF-8")}&limit=1", 4000)
            if (res != null) {
                val results = JSONObject(res).optJSONArray("results")
                if (results != null && results.length() > 0) {
                    val t = results.getJSONObject(0)
                    return@async WebData("", t.optString("trackName"), t.optString("artistName"), t.optString("artworkUrl100").replace("100x100bb", "600x600bb"))
                }
            }
        } catch(e: Exception){}
        return@async null
    }
    
    val t2 = async<WebData?>(Dispatchers.IO) {
        try {
            val res = fetchHttp("https://www.jiosaavn.com/api.php?__call=autocomplete.get&_format=json&_marker=0&cc=in&query=${URLEncoder.encode("$cleanTitle $artist", "UTF-8")}", 4000)
            if (res != null) {
                val songs = JSONObject(res).optJSONObject("songs")?.optJSONArray("data")
                if (songs != null && songs.length() > 0) {
                    val t = songs.getJSONObject(0)
                    return@async WebData("", t.optString("title", t.optString("name")).replace("&quot;", "\""), t.optJSONObject("more_info")?.optString("primary_artists") ?: "", t.optString("image").replace("50x50.jpg", "500x500.jpg"))
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
