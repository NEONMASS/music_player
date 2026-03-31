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
fun BlueWhiteFallback(modifier: Modifier = Modifier, iconSize: Dp = 48.dp) {
    Box(modifier = modifier.background(Color.Gray.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) { 
        Icon(Icons.Default.MusicNote, contentDescription = "Music", tint = Color.Gray.copy(alpha = 0.6f), modifier = Modifier.size(iconSize)) 
    }
}

data class LocalSong(val id: Long, val title: String, val artist: String, val albumId: Long, val webUrl: String? = null, val customArtUrl: String? = null) { 
    val uri: Uri get() = if(customArtUrl != null) Uri.parse(customArtUrl.substringBefore("|||")) else Uri.parse("content://media/external/audio/albumart/$albumId") 
}

data class WebData(val id: String = "", val title: String, val artist: String, val artUrl: String, val lyrics: String? = null)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { 
        super.onCreate(savedInstanceState)
        setContent { AestheticTheme { MusicPlayerUI() } } 
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(sId: Long, title: String, artist: String, artUrl: String, isPlaying: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, onAddQueue: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        SubcomposeAsyncImage(model = artUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(52.dp).clip(RoundedCornerShape(6.dp)), error = { BlueWhiteFallback() }, loading = { BlueWhiteFallback() })
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium, fontSize = 16.sp, maxLines = 1, color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(2.dp))
            Text(artist, style = MaterialTheme.typography.bodySmall, fontSize = 13.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        IconButton(onClick = onAddQueue) { Icon(Icons.Default.Add, contentDescription = "Add", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)) }
    }
}

@Composable
fun MiniPlayer(s: LocalSong, w: WebData?, isP: Boolean, pos: Long, dur: Long, onPP: () -> Unit, onN: () -> Unit, onC: () -> Unit) {
    val t = w?.title ?: s.title
    val a = w?.artist ?: s.artist
    val img = w?.artUrl?.substringBefore("|||") ?: s.customArtUrl?.substringBefore("|||") ?: s.uri.toString()
    
    Surface(modifier = Modifier.fillMaxWidth().padding(12.dp, 8.dp).clickable(onClick = onC), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
        Column {
            LinearProgressIndicator(progress = { if (dur > 0) (pos.toFloat()/dur).coerceIn(0f,1f) else 0f }, modifier = Modifier.fillMaxWidth().height(2.dp), color = MaterialTheme.colorScheme.onSurface, trackColor = Color.Transparent)
            Row(modifier = Modifier.padding(12.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SubcomposeAsyncImage(model = ImageRequest.Builder(LocalContext.current).data(img).crossfade(true).build(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)), error = { BlueWhiteFallback(modifier = Modifier.size(20.dp)) })
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) { 
                    Text(t, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(a, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), maxLines = 1) 
                }
                IconButton(onClick = onPP) { Icon(if (isP) Icons.Default.Pause else Icons.Default.PlayArrow, null, modifier = Modifier.size(32.dp)) }
                IconButton(onClick = onN) { Icon(Icons.Default.SkipNext, null) }
            }
        }
    }
}

@Composable
fun FullScreenPlayer(s: LocalSong, w: WebData?, isP: Boolean, pos: Long, dur: Long, isFav: Boolean, q: List<LocalSong>, mems: Map<Long, SongEntity>, onP: (LocalSong) -> Unit, onRm: (Int) -> Unit, onC: () -> Unit, onPP: () -> Unit, onN: () -> Unit, onPr: () -> Unit, onS: (Float) -> Unit, onF: () -> Unit, isL: Boolean, prog: Float) {
    val t = w?.title ?: s.title
    val a = w?.artist ?: s.artist
    val img = w?.artUrl?.substringBefore("|||") ?: s.customArtUrl?.substringBefore("|||") ?: s.uri.toString()
    var sL by remember { mutableStateOf(false) }
    var sQ by remember { mutableStateOf(false) } 
    
    Box(modifier = Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})) {
        SubcomposeAsyncImage(model = ImageRequest.Builder(LocalContext.current).data(img).crossfade(1000).build(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(60.dp), error = { Box(Modifier.fillMaxSize().background(Color.DarkGray)) })
        Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.2f), Color.Black.copy(alpha = 0.9f), Color.Black), 0f, 2000f)))
        
        Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { 
                IconButton(onClick = onC) { Icon(Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(36.dp), tint = Color.White) }
                Row { 
                    IconButton(onClick = { sQ = !sQ; if(sQ) sL = false }) { Icon(if (sQ) Icons.Default.QueueMusic else Icons.Default.FormatListBulleted, null, tint = Color.White) }
                    if (w?.lyrics != null) {
                        IconButton(onClick = { sL = !sL; if(sL) sQ = false }) { Icon(if (sL) Icons.Default.MusicNote else Icons.Default.Subject, null, tint = Color.White) } 
                    }
                } 
            }
            Spacer(modifier = Modifier.weight(1f))
            
            if (sQ) {
                Column(modifier = Modifier.fillMaxWidth().height(400.dp).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp)).padding(8.dp)) {
                    Text("Up Next", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))
                    LazyColumn(modifier = Modifier.fillMaxSize()) { 
                        itemsIndexed(q) { i, qs -> 
                            val m = mems[qs.id]
                            Row(modifier = Modifier.fillMaxWidth().clickable { onP(qs) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) { 
                                Icon(if (qs.id == s.id) Icons.Default.PlayArrow else Icons.Default.MusicNote, null, tint = Color.White, modifier = Modifier.size(20.dp))
                                Text(m?.customTitle ?: qs.title, color = Color.White, modifier = Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 1)
                                IconButton(onClick = { onRm(i) }) { Icon(Icons.Default.Close, null, tint = Color.White.copy(alpha = 0.5f)) } 
                            } 
                        } 
                    }
                }
            } else if (sL && w?.lyrics != null) {
                Text(w.lyrics, style = MaterialTheme.typography.titleMedium.copy(lineHeight = 28.sp), color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().height(400.dp).verticalScroll(rememberScrollState()))
            } else {
                SubcomposeAsyncImage(model = ImageRequest.Builder(LocalContext.current).data(img).crossfade(1000).build(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(1f).shadow(24.dp, RoundedCornerShape(32.dp)), error = { FallbackIcon(80.dp) })
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { 
                Column(modifier = Modifier.weight(1f)) { 
                    Text(t, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(a, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.6f), maxLines = 1) 
                }
                IconButton(onClick = onF) { Icon(if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, modifier = Modifier.size(32.dp), tint = Color.White) } 
            }
            Spacer(modifier = Modifier.height(24.dp))
            Slider(value = prog, onValueChange = onS, modifier = Modifier.fillMaxWidth(), colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.2f)))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { 
                val f = { ms: Long -> String.format("%02d:%02d", ms/1000/60, ms/1000%60) }
                Text(if(isL) "LIVE" else f(pos), color = Color.White.copy(alpha = 0.5f))
                Text(if(isL) "LIVE" else f(dur), color = Color.White.copy(alpha = 0.5f)) 
            }
            Spacer(modifier = Modifier.height(32.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) { 
                IconButton(onClick = onPr) { Icon(Icons.Default.SkipPrevious, null, modifier = Modifier.size(48.dp), Color.White) }
                Box(modifier = Modifier.size(80.dp).clip(CircleShape).background(Color.White).clickable(onClick = onPP), contentAlignment = Alignment.Center) { 
                    Icon(if (isP) Icons.Default.Pause else Icons.Default.PlayArrow, null, modifier = Modifier.size(48.dp), Color.Black) 
                }
                IconButton(onClick = onN) { Icon(Icons.Default.SkipNext, null, modifier = Modifier.size(48.dp), Color.White) } 
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicPlayerUI() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(ctx).libraryDao() }
    
    val memories by db.getAllSongMemories().collectAsState(initial = emptyList())
    val memoryMap = remember(memories) { memories.associateBy { it.localMediaId } }
    val favs by db.getFavoriteSongs().collectAsState(initial = emptyList())
    val recents by db.getRecentlyPlayed().collectAsState(initial = emptyList())
    val playlists by db.getAllPlaylists().collectAsState(initial = emptyList())
    
    val prefs = remember { ctx.getSharedPreferences("NeoPrefs", Context.MODE_PRIVATE) }
    
    var localSongs by remember { mutableStateOf<List<LocalSong>>(emptyList()) }
    val fSongs = remember(favs, localSongs) { favs.mapNotNull { m -> if (m.localMediaId >= 0) localSongs.find { it.id == m.localMediaId } else LocalSong(m.localMediaId, m.fetchedTitle ?: m.customTitle ?: "Unknown", m.fetchedArtist ?: "Unknown", -1L, null, m.fetchedArtUrl) } }
    val rSongs = remember(recents, localSongs) { recents.mapNotNull { m -> if (m.localMediaId >= 0) localSongs.find { it.id == m.localMediaId } else LocalSong(m.localMediaId, m.fetchedTitle ?: m.customTitle ?: "Unknown", m.fetchedArtist ?: "Unknown", -1L, null, m.fetchedArtUrl) } }
    
    var isSearch by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    var viewFavs by remember { mutableStateOf(false) }
    var viewPid by remember { mutableStateOf<Long?>(null) }
    var pData by remember { mutableStateOf<PlaylistWithSongs?>(null) }
    
    LaunchedEffect(viewPid) { viewPid?.let { pData = db.getPlaylistWithSongs(it) } }

    val langs = listOf("All", "Tamil", "English", "Hindi", "Malayalam", "Telugu", "Kannada", "Marathi", "Bengali", "Punjabi", "Gujarati")
    var selLang by remember { mutableStateOf(prefs.getString("lang", "") ?: "") }
    var isAlbumMode by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { 
        if (selLang.isBlank()) { 
            val d = getAutoLang()
            selLang = if (langs.contains(d)) d else "All"
            prefs.edit().putString("lang", selLang).apply() 
        } 
    }

    var searchRes by remember { mutableStateOf<List<WebData>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    
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
        
        val q = if (query.isBlank()) {
            if (activeLang == "All" || activeLang.isBlank()) "Top Hits" else "$activeLang Hits"
        } else {
            if (activeLang == "All" || activeLang.isBlank()) query else "$query $activeLang"
        }
        
        val fresh = fetchWebSearch(q, query, isAlbumMode)
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

    var curSong by remember { mutableStateOf<LocalSong?>(null) }
    var webMeta by remember { mutableStateOf<WebData?>(null) }
    var actionSong by remember { mutableStateOf<LocalSong?>(null) }
    var showEdit by remember { mutableStateOf(false) }
    var showAddP by remember { mutableStateOf(false) }
    var showNewP by remember { mutableStateOf(false) }
    var queue by remember { mutableStateOf<List<LocalSong>>(emptyList()) }
    var isPlaying by remember { mutableStateOf(false) }
    var pos by remember { mutableStateOf(0L) }
    var dur by remember { mutableStateOf(0L) }
    var isLiveStream by remember { mutableStateOf(false) }
    var showFS by remember { mutableStateOf(false) }
    var apCtx by remember { mutableStateOf<List<WebData>>(emptyList()) }
    var apIdx by remember { mutableIntStateOf(-1) }

    val exo = remember { 
        ExoPlayer.Builder(ctx).setMediaSourceFactory(DefaultMediaSourceFactory(ctx).setDataSourceFactory(DefaultDataSource.Factory(ctx, DefaultHttpDataSource.Factory().setUserAgent("Mozilla/5.0").setAllowCrossProtocolRedirects(true)))).build().apply { 
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            repeatMode = Player.REPEAT_MODE_OFF 
        } 
    }
    
    DisposableEffect(Unit) { onDispose { exo.release() } }

    fun playList(s: LocalSong, l: List<LocalSong>) { 
        apCtx = emptyList()
        apIdx = -1
        queue = l
        exo.stop()
        exo.clearMediaItems()
        exo.setMediaItems(l.map { MediaItem.Builder().setUri(it.webUrl ?: ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it.id).toString()).setMediaId(it.id.toString()).build() })
        exo.seekTo(l.indexOf(s).coerceAtLeast(0), C.TIME_UNSET)
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
                    } catch(e: Exception){}
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

    fun hNext() { 
        if (exo.mediaItemCount > 1) {
            exo.seekToNextMediaItem() 
        } else if (apCtx.isNotEmpty() && apIdx < apCtx.size - 1) { 
            apIdx++
            playWeb(apCtx[apIdx]) 
        } 
    }
    
    fun hPrev() { 
        if (exo.mediaItemCount > 1) {
            exo.seekToPreviousMediaItem() 
        } else if (apCtx.isNotEmpty() && apIdx > 0) { 
            apIdx--
            playWeb(apCtx[apIdx]) 
        } else {
            exo.seekTo(0L) 
        } 
    }

    DisposableEffect(exo) {
        val lst = object : Player.Listener {
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
        exo.addListener(lst)
        onDispose { exo.removeListener(lst) }
    }

    LaunchedEffect(Unit) { 
        while (true) { 
            pos = exo.currentPosition
            val d = exo.duration
            dur = if (d == C.TIME_UNSET) 0L else d
            isLiveStream = exo.isCurrentMediaItemLive || (d == C.TIME_UNSET && exo.playbackState == Player.STATE_READY)
            delay(500L) 
        } 
    }

    LaunchedEffect(curSong) {
        val s = curSong ?: return@LaunchedEffect
        if (s.id >= 0L) return@LaunchedEffect
        
        val m = db.getSongMemory(s.id)
        val dT = m?.customTitle?.takeIf { it.isNotBlank() } ?: m?.fetchedTitle?.takeIf { it.isNotBlank() } ?: s.title
        val dA = m?.customArtist?.takeIf { it.isNotBlank() } ?: m?.fetchedArtist?.takeIf { it.isNotBlank() } ?: s.artist
        
        webMeta = WebData("", dT, dA, m?.fetchedArtUrl ?: "", m?.fetchedLyrics)
        db.saveSongMemory(SongEntity(s.id, m?.customTitle, m?.customArtist, m?.fetchedTitle ?: dT, m?.fetchedArtist ?: dA, m?.fetchedArtUrl, m?.fetchedLyrics, m?.isFavorite ?: false, System.currentTimeMillis()))
        
        if (m?.fetchedArtUrl == null) { 
            val res = fetchMetadata(dT, dA)
            if (res != null) { 
                webMeta = res
                db.saveSongMemory(SongEntity(s.id, m?.customTitle, m?.customArtist, res.title, res.artist, res.artUrl, res.lyrics, m?.isFavorite ?: false, System.currentTimeMillis())) 
            } 
        }
    }

    var permissionGranted by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted -> 
        permissionGranted = isGranted
        if (isGranted) localSongs = getLocalMusic(ctx) 
    }
    
    LaunchedEffect(Unit) { 
        launcher.launch(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE) 
    }
    
    BackHandler(showFS || viewFavs || viewPid != null) { 
        if (showFS) showFS = false else if (viewFavs) viewFavs = false else viewPid = null 
    }

    val handleAddToQueue = { s: LocalSong, w: WebData? ->
        if (s.id < 0) {
            Toast.makeText(ctx, "Added to Queue", Toast.LENGTH_SHORT).show()
            scope.launch {
                val streamUrl = fetchAudioUrl(w?.id ?: s.customArtUrl?.substringAfter("|||") ?: "")
                if (streamUrl != null) {
                    queue = queue + s.copy(webUrl = streamUrl)
                    exo.addMediaItem(MediaItem.fromUri(streamUrl))
                    if (!isPlaying && exo.mediaItemCount == 1) { exo.prepare(); exo.play() }
                }
            }
        } else {
            Toast.makeText(ctx, "Added to Queue", Toast.LENGTH_SHORT).show()
            queue = queue + s
            exo.addMediaItem(MediaItem.fromUri(s.uri))
            if (!isPlaying && exo.mediaItemCount == 1) { exo.prepare(); exo.play() }
        }
    }

    Scaffold(
        topBar = { 
            TopAppBar(
                title = { 
                    if (isSearch) TextField(value = query, onValueChange = { query = it }, placeholder = { Text("Search...") }, singleLine = true, colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent)) 
                    else Text(when(tab) { 0 -> "Dashboard"; 1 -> "Discover"; 2 -> "Library"; 3 -> "Radio"; else -> "Offline" }, fontWeight = FontWeight.Bold) 
                }, 
                actions = { 
                    IconButton(onClick = { isSearch = !isSearch; query = "" }) { Icon(if (isSearch) Icons.Default.Close else Icons.Default.Search, null) } 
                }, 
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.95f))
            ) 
        },
        bottomBar = { 
            Column { 
                if (curSong != null) MiniPlayer(curSong!!, webMeta, isPlaying, pos, dur, { if (isPlaying) exo.pause() else exo.play() }, { hNext() }, { showFS = true })
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) { 
                    NavigationBarItem(icon = { Icon(Icons.Default.Home, null) }, label = { Text("Home", fontSize=10.sp) }, selected = tab==0&&!isSearch, onClick = { tab=0; isSearch=false; viewFavs=false; viewPid=null })
                    NavigationBarItem(icon = { Icon(Icons.Default.Search, null) }, label = { Text("Search", fontSize=10.sp) }, selected = isSearch, onClick = { isSearch=true; tab=1 })
                    NavigationBarItem(icon = { Icon(Icons.Default.QueueMusic, null) }, label = { Text("Library", fontSize=10.sp) }, selected = tab==2&&!isSearch, onClick = { tab=2; isSearch=false })
                    NavigationBarItem(icon = { Icon(Icons.Default.Radio, null) }, label = { Text("Radio", fontSize=10.sp) }, selected = tab==3&&!isSearch, onClick = { tab=3; isSearch=false; viewFavs=false; viewPid=null })
                    NavigationBarItem(icon = { Icon(Icons.Default.Folder, null) }, label = { Text("Offline", fontSize=10.sp) }, selected = tab==4&&!isSearch, onClick = { tab=4; isSearch=false; viewFavs=false; viewPid=null }) 
                } 
            } 
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (!permissionGranted) {
                Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Permission required.") }
            } else if (isSearch) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { 
                        FilterChip(selected = !isAlbumMode, onClick = { isAlbumMode = false }, label = { Text("Tracks") }, shape = RoundedCornerShape(20.dp))
                        FilterChip(selected = isAlbumMode, onClick = { isAlbumMode = true }, label = { Text("Albums") }, shape = RoundedCornerShape(20.dp)) 
                    }
                    AnimatedVisibility(visible = !isAlbumMode) { 
                        LazyRow(Modifier.padding(top=8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { 
                            items(langs) { l -> 
                                FilterChip(selected = selLang == l, onClick = { selLang = l; prefs.edit().putString("lang", l).apply() }, label = { Text(l) }, shape = RoundedCornerShape(20.dp)) 
                            } 
                        } 
                    }
                }
                if (isSearching) {
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurface) }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) { 
                        items(searchRes) { s -> 
                            TrackRow(
                                sId = -1L, 
                                title = s.title, 
                                artist = s.artist, 
                                artUrl = s.artUrl, 
                                isPlaying = false, 
                                onClick = { 
                                    apCtx = searchRes
                                    apIdx = searchRes.indexOf(s)
                                    playWeb(s) 
                                }, 
                                onLongClick = { 
                                    actionSong = LocalSong(generateDummyId(s.title, s.artist), s.title, s.artist, -1L, null, "${s.artUrl}|||${s.id}")
                                    webMeta = s 
                                }, 
                                onAddQueue = { 
                                    handleAddToQueue(LocalSong(generateDummyId(s.title, s.artist), s.title, s.artist, -1L, null, "${s.artUrl}|||${s.id}"), s) 
                                }
                            ) 
                        } 
                    }
                }
            } else {
                when (tab) {
                    0 -> LazyColumn(Modifier.fillMaxSize()) { 
                        if (rSongs.isNotEmpty()) { 
                            item { Text("Recently Played", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
                            item { 
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(horizontal = 16.dp), modifier = Modifier.padding(bottom = 32.dp)) { 
                                    items(rSongs) { s -> 
                                        val m = memoryMap[s.id]
                                        val dT = m?.customTitle?.takeIf{it.isNotBlank()} ?: m?.fetchedTitle ?: s.title
                                        val dArt = m?.fetchedArtUrl?.substringBefore("|||") ?: s.uri.toString()
                                        
                                        Column(Modifier.width(120.dp).clickable { 
                                            if (s.id < 0) { 
                                                apCtx = rSongs.filter{it.id<0}.map{WebData(it.customArtUrl?.substringAfter("|||")?:"", it.title, it.artist, it.customArtUrl?.substringBefore("|||")?:"")}
                                                apIdx = apCtx.indexOfFirst{it.title==dT}
                                                if(s.webUrl!=null){ 
                                                    queue=listOf(s)
                                                    exo.setMediaItem(MediaItem.fromUri(s.webUrl))
                                                    exo.prepare()
                                                    exo.play()
                                                    curSong=s 
                                                } else {
                                                    playWeb(WebData(m?.fetchedArtUrl?.substringAfter("|||")?:"", dT, s.artist, dArt))
                                                }
                                            } else { 
                                                queue = rSongs.filter{it.id>=0}
                                                exo.setMediaItems(queue.map{MediaItem.fromUri(it.uri)})
                                                exo.seekTo(queue.indexOf(s), 0L)
                                                exo.prepare()
                                                exo.play() 
                                            } 
                                        }) { 
                                            SubcomposeAsyncImage(model = ImageRequest.Builder(LocalContext.current).data(dArt).crossfade(true).build(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(120.dp).clip(RoundedCornerShape(8.dp)), error = { FallbackIcon() })
                                            Spacer(Modifier.height(8.dp))
                                            Text(dT, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) 
                                        } 
                                    } 
                                } 
                            } 
                        } 
                    }
                    2 -> {
                        if (viewFavs) { 
                            Row(Modifier.padding(horizontal=16.dp, vertical=12.dp), verticalAlignment=Alignment.CenterVertically) { 
                                IconButton(onClick={viewFavs=false}) { Icon(Icons.Default.ArrowBack, null) }
                                Text("Liked Songs", style=MaterialTheme.typography.headlineSmall, fontWeight=FontWeight.Bold) 
                            }
                            LazyColumn(Modifier.fillMaxSize()) { 
                                items(fSongs) { s -> 
                                    val m = memoryMap[s.id]
                                    val dT = m?.customTitle ?: s.title
                                    val dA = m?.customArtist ?: s.artist
                                    val dArt = m?.fetchedArtUrl?.substringBefore("|||") ?: s.uri.toString()
                                    TrackRow(s.id, dT, dA, dArt, curSong?.id==s.id, 
                                        onClick={ 
                                            if(s.id<0) playWeb(WebData(m?.fetchedArtUrl?.substringAfter("|||")?:"", dT, dA, dArt)) 
                                            else { 
                                                queue = fSongs.filter{it.id>=0}
                                                exo.setMediaItems(queue.map{MediaItem.fromUri(it.uri)})
                                                exo.seekTo(queue.indexOf(s), 0L)
                                                exo.prepare()
                                                exo.play() 
                                            } 
                                        }, 
                                        onLongClick={actionSong=s}, 
                                        onAddQueue = { handleAddToQueue(s, WebData(m?.fetchedArtUrl?.substringAfter("|||")?:"", dT, dA, dArt)) }
                                    ) 
                                } 
                            } 
                        } else if (viewPid != null && pData != null) { 
                            val pSongs = pData!!.songs.mapNotNull { m -> if(m.localMediaId>=0) localSongs.find{it.id==m.localMediaId} else LocalSong(m.localMediaId, m.fetchedTitle?:"Unk", m.fetchedArtist?:"Unk", -1L, null, m.fetchedArtUrl) }
                            Row(Modifier.padding(horizontal=16.dp, vertical=12.dp), verticalAlignment=Alignment.CenterVertically) { 
                                IconButton(onClick={viewPid=null}) { Icon(Icons.Default.ArrowBack, null) }
                                Text(pData!!.playlist.name, style=MaterialTheme.typography.headlineSmall, fontWeight=FontWeight.Bold) 
                            }
                            LazyColumn(Modifier.fillMaxSize()) { 
                                items(pSongs) { s -> 
                                    val m = memoryMap[s.id]
                                    val dT = m?.customTitle ?: s.title
                                    val dA = m?.customArtist ?: s.artist
                                    val dArt = m?.fetchedArtUrl?.substringBefore("|||") ?: s.uri.toString()
                                    TrackRow(s.id, dT, dA, dArt, curSong?.id==s.id, 
                                        onClick={ 
                                            if(s.id<0) playWeb(WebData(m?.fetchedArtUrl?.substringAfter("|||")?:"", dT, dA, dArt)) 
                                            else { 
                                                queue = pSongs.filter{it.id>=0}
                                                exo.setMediaItems(queue.map{MediaItem.fromUri(it.uri)})
                                                exo.seekTo(queue.indexOf(s), 0L)
                                                exo.prepare()
                                                exo.play() 
                                            } 
                                        }, 
                                        onLongClick={actionSong=s}, 
                                        onAddQueue = { handleAddToQueue(s, WebData(m?.fetchedArtUrl?.substringAfter("|||")?:"", dT, dA, dArt)) }
                                    ) 
                                } 
                            } 
                        } else { 
                            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp, vertical=12.dp), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) { 
                                Text("Your Library", style=MaterialTheme.typography.headlineSmall, fontWeight=FontWeight.Bold)
                                IconButton(onClick={showNewP=true}){ Icon(Icons.Default.Add, null) } 
                            }
                            LazyColumn(Modifier.padding(horizontal=16.dp)) { 
                                item { 
                                    Row(Modifier.fillMaxWidth().padding(vertical=12.dp).clickable{viewFavs=true}, verticalAlignment=Alignment.CenterVertically) { 
                                        Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(0.1f)), contentAlignment=Alignment.Center) { Icon(Icons.Default.Favorite, null, tint=Color.White) }
                                        Column(Modifier.padding(start=16.dp)) { 
                                            Text("Liked Songs", fontWeight=FontWeight.Bold, fontSize=16.sp)
                                            Text("${fSongs.size} tracks", color=MaterialTheme.colorScheme.onSurface.copy(0.5f), fontSize=13.sp) 
                                        } 
                                    } 
                                }
                                items(playlists) { p -> 
                                    Row(Modifier.fillMaxWidth().padding(vertical=12.dp).clickable{viewPid=p.playlistId}, verticalAlignment=Alignment.CenterVertically) { 
                                        Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(0.1f)), contentAlignment=Alignment.Center) { Icon(Icons.Default.QueueMusic, null) }
                                        Column(Modifier.padding(start=16.dp)) { 
                                            Text(p.name, fontWeight=FontWeight.Bold, fontSize=16.sp)
                                            Text("Playlist", color=MaterialTheme.colorScheme.onSurface.copy(0.5f), fontSize=13.sp) 
                                        } 
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
                        LazyColumn(Modifier.fillMaxSize()) {
                            item { Text("24/7 Live Radio", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp)) }
                            items(stations) { s ->
                                TrackRow(
                                    sId = -1L, 
                                    title = s.title, 
                                    artist = s.artist, 
                                    artUrl = s.artUrl, 
                                    isPlaying = curSong?.title == s.title, 
                                    onClick = { playWeb(s) }, 
                                    onLongClick = {}, 
                                    onAddQueue = { handleAddToQueue(LocalSong(generateDummyId(s.title, s.artist), s.title, s.artist, -1L, null, "${s.artUrl}|||${s.id}"), s) }
                                )
                            }
                        }
                    }
                    4 -> LazyColumn(Modifier.fillMaxSize()) { 
                        items(localSongs) { s -> 
                            val m = memoryMap[s.id]
                            val dT = m?.customTitle ?: s.title
                            val dA = m?.customArtist ?: s.artist
                            val dArt = m?.fetchedArtUrl?.substringBefore("|||") ?: s.uri.toString()
                            TrackRow(s.id, dT, dA, dArt, curSong?.id==s.id, 
                                onClick={ 
                                    queue = localSongs
                                    exo.setMediaItems(localSongs.map{MediaItem.fromUri(it.uri)})
                                    exo.seekTo(localSongs.indexOf(s), 0L)
                                    exo.prepare()
                                    exo.play() 
                                }, 
                                onLongClick={actionSong=s}, 
                                onAddQueue = { handleAddToQueue(s, null) }
                            ) 
                        } 
                    }
                }
            }
        }
        
        AnimatedVisibility(visible = showFS && curSong != null, enter = slideInVertically { it }, exit = slideOutVertically { it }) { 
            if (curSong != null) { 
                FullScreenPlayer(
                    s = curSong!!, 
                    w = webMeta, 
                    isP = isPlaying, 
                    pos = pos, 
                    dur = dur, 
                    isFav = memoryMap[curSong?.id ?: -1L]?.isFavorite == true, 
                    q = queue, 
                    mems = memoryMap, 
                    onP = { qs -> 
                        if (qs.id < 0 && qs.webUrl == null) {
                            playWeb(WebData(qs.customArtUrl?.substringAfter("|||") ?: "", qs.title, qs.artist, qs.customArtUrl?.substringBefore("|||") ?: "")) 
                        } else { 
                            exo.seekTo(queue.indexOf(qs), 0L)
                            exo.play() 
                        } 
                    }, 
                    onRm = { i -> 
                        if (i in queue.indices) { 
                            val newQ = queue.toMutableList()
                            newQ.removeAt(i)
                            queue = newQ
                            try { exo.removeMediaItem(i) } catch(e: Exception){}
                            if (exo.mediaItemCount == 0) { 
                                exo.stop()
                                isPlaying = false
                                showFS = false
                                curSong = null 
                            } 
                        } 
                    }, 
                    onC = { showFS = false }, 
                    onPP = { if (isPlaying) exo.pause() else exo.play() }, 
                    onN = { hNext() }, 
                    onPr = { hPrev() }, 
                    onS = { p -> 
                        if(!isLiveStream) { 
                            val nPos = (p * dur).toLong()
                            exo.seekTo(nPos)
                            pos = nPos 
                        } 
                    }, 
                    onF = { 
                        scope.launch { db.updateFavoriteStatus(curSong!!.id, !(memoryMap[curSong!!.id ?: -1L]?.isFavorite ?: false)) } 
                    }, 
                    isL = isLiveStream, 
                    prog = if (!isLiveStream && dur > 0) (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f
                ) 
            } 
        }
        
        if (actionSong != null) { 
            val s = actionSong!!
            AlertDialog(
                onDismissRequest = { actionSong = null }, 
                confirmButton = { TextButton(onClick={actionSong=null}){Text("Cancel")} }, 
                title = { Text("Options") }, 
                text = { 
                    Column { 
                        TextButton(onClick = { 
                            if (s.id < 0) {
                                scope.launch { 
                                    val streamUrl = fetchAudioUrl(s.customArtUrl?.substringAfter("|||") ?: "")
                                    if (streamUrl != null) { 
                                        queue = queue + s.copy(webUrl = streamUrl)
                                        exo.addMediaItem(MediaItem.fromUri(streamUrl))
                                        db.saveSongMemory(SongEntity(s.id, s.title, s.artist, s.title, s.artist, s.customArtUrl, null, false, System.currentTimeMillis())) 
                                    } 
                                } 
                            } else { 
                                queue = queue + s
                                exo.addMediaItem(MediaItem.fromUri(s.uri)) 
                            }
                            actionSong = null 
                        }, modifier = Modifier.fillMaxWidth()) { Text("Add to Queue") }
                        
                        TextButton(onClick = { showAddP = true }, modifier = Modifier.fillMaxWidth()) { Text("Add to Playlist") }
                        
                        if (s.id >= 0) {
                            TextButton(onClick = { showEdit = true }, modifier = Modifier.fillMaxWidth()) { Text("Edit Metadata") }
                        }
                    } 
                }
            ) 
        }
        
        if (showEdit && actionSong != null) { 
            val s = actionSong!!
            val m = memoryMap[s.id]
            var eT by remember { mutableStateOf(m?.customTitle ?: s.title) }
            var eA by remember { mutableStateOf(m?.customArtist ?: s.artist) }
            
            AlertDialog(
                onDismissRequest = { showEdit = false; actionSong = null }, 
                confirmButton = { 
                    Button(onClick = { 
                        scope.launch { 
                            db.saveSongMemory(SongEntity(s.id, eT.trim().takeIf{it.isNotEmpty()}, eA.trim().takeIf{it.isNotEmpty()}, null, null, null, null, m?.isFavorite ?: false, m?.lastPlayedAt ?: 0L))
                            showEdit = false
                            actionSong = null
                            playList(s, localSongs) 
                        } 
                    }) { Text("Save") } 
                }, 
                title = { Text("Fix Metadata") }, 
                text = { 
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { 
                        OutlinedTextField(value = eT, onValueChange = { eT = it }, label = { Text("Title") }, singleLine = true)
                        OutlinedTextField(value = eA, onValueChange = { eA = it }, label = { Text("Artist") }, singleLine = true) 
                    } 
                }
            ) 
        }
        
        if (showNewP) { 
            var n by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showNewP = false }, 
                confirmButton = { 
                    Button(onClick = { 
                        if (n.isNotBlank()) { 
                            scope.launch { db.createPlaylist(PlaylistEntity(name = n.trim())) }
                            showNewP = false 
                        } 
                    }) { Text("Create") } 
                }, 
                title = { Text("New Playlist") }, 
                text = { OutlinedTextField(value = n, onValueChange = { n = it }, label = { Text("Name") }, singleLine = true) }
            ) 
        }
        
        if (showAddP && actionSong != null) { 
            val songToAdd = actionSong!!
            AlertDialog(
                onDismissRequest = { showAddP = false; actionSong = null }, 
                confirmButton = { TextButton(onClick={showAddP=false; actionSong=null}){Text("Cancel")} }, 
                title = { Text("Select Playlist") }, 
                text = { 
                    LazyColumn { 
                        items(playlists) { p -> 
                            TextButton(onClick = { 
                                scope.launch { 
                                    if (db.getSongMemory(songToAdd.id) == null) db.saveSongMemory(SongEntity(songToAdd.id, null, null, null, null, songToAdd.customArtUrl, null, false, 0L))
                                    db.addSongToPlaylist(PlaylistSongCrossRef(p.playlistId, songToAdd.id)) 
                                }
                                showAddP = false
                                actionSong = null 
                            }, modifier = Modifier.fillMaxWidth()) { Text(p.name) } 
                        } 
                    } 
                }
            ) 
        }
    }
}

fun generateDummyId(title: String, artist: String): Long {
    val h = kotlin.math.abs((title + artist).hashCode().toLong())
    return if (h == 0L) -1L else -h
}

fun getLocalMusic(ctx: Context) = mutableListOf<LocalSong>().apply {
    ctx.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM_ID), "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} ASC")?.use { c ->
        val i = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val t = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val a = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val al = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        while (c.moveToNext()) {
            val title = c.getString(t) ?: "Unknown"
            if (!title.matches(Regex("(?i)(.*\\d{8}.*|.*aud-.*|.*ptt-.*|.*wa00.*|.*record.*)"))) {
                add(LocalSong(c.getLong(i), title, c.getString(a) ?: "Unknown", c.getLong(al)))
            }
        }
    }
}

private fun fetchHttp(url: String, timeoutMs: Int = 6000): String? {
    return try {
        val conn = URL(url).openConnection() as HttpURLConnection
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
            when {
                r.contains("Tamil", true) -> return@withContext "Tamil"
                r.contains("Maharashtra", true) -> return@withContext "Marathi"
                else -> return@withContext "Hindi"
            }
        }
    } catch (e: Exception) {}
    return@withContext "All"
}

suspend fun fetchWebSearch(q: String, rawQ: String, isP: Boolean): List<WebData> = coroutineScope {
    val qE = URLEncoder.encode(q, "UTF-8")
    
    val sTask = async<List<WebData>>(Dispatchers.IO) {
        if (isP) return@async emptyList()
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
        val resultLists = defs.awaitAll()
        for (list in resultLists) { if (list.isNotEmpty()) return@async list }
        
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
        emptyList()
    }
    
    val aTask = async<List<WebData>>(Dispatchers.IO) {
        if (!isP) return@async emptyList()
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
        emptyList()
    }
    
    val pTask = async<List<WebData>>(Dispatchers.IO) {
        if (isP) return@async emptyList()
        val instances = listOf("pipedapi.kavin.rocks", "pipedapi.tokhmi.xyz", "piped.projectsegfau.lt", "pipedapi.smnz.de")
        val defs = instances.map { instance ->
            async {
                try {
                    val res = fetchHttp("https://$instance/search?q=$qE&filter=music_songs", 3000) ?: return@async emptyList<WebData>()
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
        val resultLists = defs.awaitAll()
        for (list in resultLists) { if (list.isNotEmpty()) return@async list }
        emptyList()
    }
    
    val combined = mutableListOf<WebData>()
    combined.addAll(sTask.await())
    combined.addAll(aTask.await())
    combined.addAll(pTask.await())
    
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
        for (res in results) { if (res != null) return@withContext res }
    }
    return@withContext null
}

suspend fun fetchMetadata(title: String, artist: String): WebData? = coroutineScope {
    val cT = title.lowercase().replace(".mp3", "").replace(".m4a", "").replace(Regex("\\[.*?\\]|\\(.*?\\)"), "").trim()
    
    val t1 = async<WebData?>(Dispatchers.IO) { 
        try {
            val res = fetchHttp("https://itunes.apple.com/search?term=${URLEncoder.encode("$cT $artist", "UTF-8")}&limit=1", 3000)
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
            val res = fetchHttp("https://www.jiosaavn.com/api.php?__call=autocomplete.get&_format=json&_marker=0&cc=in&query=${URLEncoder.encode("$cT $artist", "UTF-8")}", 3000)
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
        val lyrics = try {
            val res = fetchHttp("https://lrclib.net/api/search?q=${URLEncoder.encode("$title $artist", "UTF-8")}", 3000)
            if (res != null) {
                val arr = JSONArray(res)
                if (arr.length() > 0) arr.getJSONObject(0).optString("plainLyrics") else null
            } else null
        } catch(e: Exception) { null }
        
        return@coroutineScope result.copy(lyrics = lyrics)
    }
    return@coroutineScope null
}
