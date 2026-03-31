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

// MINIMALIST THEME
@Composable
fun AestheticTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = darkColorScheme(primary = Color.White, background = Color(0xFF0A0A0A), surface = Color(0xFF141414), onSurface = Color(0xFFF5F5F5)), content = content)
@Composable
fun FallbackIcon(size: Dp = 48.dp) = Box(Modifier.background(Color.Gray.copy(0.2f)), contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, tint = Color.Gray.copy(0.6f), modifier = Modifier.size(size)) }

data class LocalSong(val id: Long, val title: String, val artist: String, val albumId: Long, val webUrl: String? = null, val artUrl: String? = null) { val uri: Uri get() = if(artUrl != null) Uri.parse(artUrl.substringBefore("|||")) else Uri.parse("content://media/external/audio/albumart/$albumId") }
data class WebData(val id: String = "", val title: String, val artist: String, val artUrl: String, val lyrics: String? = null)

class MainActivity : ComponentActivity() { override fun onCreate(s: Bundle?) { super.onCreate(s); setContent { AestheticTheme { MusicPlayerUI() } } } }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(sId: Long, title: String, artist: String, artUrl: String, isPlaying: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) = Row(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
    SubcomposeAsyncImage(ImageRequest.Builder(LocalContext.current).data(artUrl).crossfade(true).build(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(52.dp).clip(RoundedCornerShape(6.dp)), error = { FallbackIcon() }, loading = { FallbackIcon() })
    Column(Modifier.weight(1f).padding(start = 16.dp)) { Text(title, fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium, fontSize = 16.sp, maxLines = 1, color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface); Text(artist, style = MaterialTheme.typography.bodySmall, fontSize = 13.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurface.copy(0.6f)) }
    if (sId < 0) Icon(Icons.Default.CloudDownload, null, tint = MaterialTheme.colorScheme.onSurface.copy(0.3f), modifier = Modifier.size(20.dp))
}

@Composable
fun MiniPlayer(s: LocalSong, w: WebData?, isP: Boolean, pos: Long, dur: Long, onPP: () -> Unit, onN: () -> Unit, onC: () -> Unit) {
    val t = w?.title ?: s.title; val a = w?.artist ?: s.artist; val img = w?.artUrl?.substringBefore("|||") ?: s.customArtUrl?.substringBefore("|||") ?: s.uri.toString()
    Surface(Modifier.fillMaxWidth().padding(12.dp, 8.dp).clickable(onClick = onC), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
        Column {
            LinearProgressIndicator({ if (dur > 0) (pos.toFloat()/dur).coerceIn(0f,1f) else 0f }, Modifier.fillMaxWidth().height(2.dp), color = MaterialTheme.colorScheme.onSurface, trackColor = Color.Transparent)
            Row(Modifier.padding(12.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SubcomposeAsyncImage(ImageRequest.Builder(LocalContext.current).data(img).crossfade(true).build(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)), error = { FallbackIcon(20.dp) })
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(t, fontWeight = FontWeight.Bold, maxLines = 1); Text(a, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(0.6f), maxLines = 1) }
                IconButton(onPP) { Icon(if (isP) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(32.dp)) }
                IconButton(onN) { Icon(Icons.Default.SkipNext, null) }
            }
        }
    }
}

@Composable
fun FullScreenPlayer(s: LocalSong, w: WebData?, isP: Boolean, pos: Long, dur: Long, isFav: Boolean, q: List<LocalSong>, mems: Map<Long, SongEntity>, onP: (LocalSong) -> Unit, onRm: (Int) -> Unit, onC: () -> Unit, onPP: () -> Unit, onN: () -> Unit, onPr: () -> Unit, onS: (Float) -> Unit, onF: () -> Unit, isL: Boolean, prog: Float) {
    val t = w?.title ?: s.title; val a = w?.artist ?: s.artist; val img = w?.artUrl?.substringBefore("|||") ?: s.customArtUrl?.substringBefore("|||") ?: s.uri.toString()
    var sL by remember { mutableStateOf(false) }; var sQ by remember { mutableStateOf(false) } 
    Box(Modifier.fillMaxSize().clickable(interactionSource=remember{MutableInteractionSource()}, indication=null){}) {
        SubcomposeAsyncImage(ImageRequest.Builder(LocalContext.current).data(img).crossfade(1000).build(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(60.dp), error = { Box(Modifier.fillMaxSize().background(Color.DarkGray)) })
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(0.2f), Color.Black.copy(0.9f), Color.Black), 0f, 2000f)))
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { IconButton(onC) { Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(36.dp), Color.White) }; Row { IconButton({ sQ = !sQ; if(sQ) sL = false }) { Icon(if (sQ) Icons.Default.QueueMusic else Icons.Default.FormatListBulleted, null, tint = Color.White) }; if (w?.lyrics != null) IconButton({ sL = !sL; if(sL) sQ = false }) { Icon(if (sL) Icons.Default.MusicNote else Icons.Default.Subject, null, tint = Color.White) } } }
            Spacer(Modifier.weight(1f))
            if (sQ) LazyColumn(Modifier.fillMaxWidth().height(400.dp).background(Color.Black.copy(0.5f), RoundedCornerShape(12.dp)).padding(8.dp)) { itemsIndexed(q) { i, qs -> val m = mems[qs.id]; Row(Modifier.fillMaxWidth().clickable { onP(qs) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(if (qs.id == s.id) Icons.Default.PlayArrow else Icons.Default.MusicNote, null, tint = Color.White, modifier = Modifier.size(20.dp)); Text(m?.customTitle ?: qs.title, color = Color.White, modifier = Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 1); IconButton({ onRm(i) }) { Icon(Icons.Default.Close, null, tint = Color.White.copy(0.5f)) } } } }
            else if (sL && w?.lyrics != null) Text(w.lyrics, style = MaterialTheme.typography.titleMedium.copy(lineHeight = 28.sp), color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().height(400.dp).verticalScroll(rememberScrollState()))
            else SubcomposeAsyncImage(ImageRequest.Builder(LocalContext.current).data(img).crossfade(1000).build(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(1f).shadow(24.dp, RoundedCornerShape(32.dp)), error = { FallbackIcon(80.dp) })
            Spacer(Modifier.height(32.dp))
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(t, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1); Text(a, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(0.6f), maxLines = 1) }; IconButton(onF) { Icon(if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, Modifier.size(32.dp), Color.White) } }
            Spacer(Modifier.height(24.dp)); Slider(prog, onS, Modifier.fillMaxWidth(), colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(0.2f)))
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { val f = { ms: Long -> String.format("%02d:%02d", ms/1000/60, ms/1000%60) }; Text(if(isL) "LIVE" else f(pos), color = Color.White.copy(0.5f)); Text(if(isL) "LIVE" else f(dur), color = Color.White.copy(0.5f)) }
            Spacer(Modifier.height(32.dp)); Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) { IconButton(onPr) { Icon(Icons.Default.SkipPrevious, null, Modifier.size(48.dp), Color.White) }; Box(Modifier.size(80.dp).clip(CircleShape).background(Color.White).clickable(onClick = onPP), Alignment.Center) { Icon(if (isP) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(48.dp), Color.Black) }; IconButton(onN) { Icon(Icons.Default.SkipNext, null, Modifier.size(48.dp), Color.White) } }; Spacer(Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicPlayerUI() {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope(); val db = remember { AppDatabase.getDatabase(ctx).libraryDao() }
    val memories by db.getAllSongMemories().collectAsState(emptyList()); val memoryMap = remember(memories) { memories.associateBy { it.localMediaId } }
    val favs by db.getFavoriteSongs().collectAsState(emptyList()); val recents by db.getRecentlyPlayed().collectAsState(emptyList()); val playlists by db.getAllPlaylists().collectAsState(emptyList())
    val prefs = remember { ctx.getSharedPreferences("NeoPrefs", Context.MODE_PRIVATE) }
    
    var localSongs by remember { mutableStateOf<List<LocalSong>>(emptyList()) }
    val fSongs = remember(favs, localSongs) { favs.mapNotNull { m -> if (m.localMediaId >= 0) localSongs.find { it.id == m.localMediaId } else LocalSong(m.localMediaId, m.fetchedTitle ?: m.customTitle ?: "Unknown", m.fetchedArtist ?: "Unknown", -1L, null, m.fetchedArtUrl) } }
    val rSongs = remember(recents, localSongs) { recents.mapNotNull { m -> if (m.localMediaId >= 0) localSongs.find { it.id == m.localMediaId } else LocalSong(m.localMediaId, m.fetchedTitle ?: m.customTitle ?: "Unknown", m.fetchedArtist ?: "Unknown", -1L, null, m.fetchedArtUrl) } }
    
    var isSearch by remember { mutableStateOf(false) }; var query by remember { mutableStateOf("") }; var tab by remember { mutableIntStateOf(0) }
    var viewFavs by remember { mutableStateOf(false) }; var viewPid by remember { mutableStateOf<Long?>(null) }; var pData by remember { mutableStateOf<PlaylistWithSongs?>(null) }
    LaunchedEffect(viewPid) { viewPid?.let { pData = db.getPlaylistWithSongs(it) } }

    val langs = listOf("All", "Tamil", "English", "Hindi", "Malayalam", "Telugu", "Kannada", "Marathi", "Bengali", "Punjabi", "Gujarati")
    var selLang by remember { mutableStateOf(prefs.getString("lang", "") ?: "") }; var isAlbumMode by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (selLang.isBlank()) { val d = getAutoLang(); selLang = if (langs.contains(d)) d else "All"; prefs.edit().putString("lang", selLang).apply() } }

    var searchRes by remember { mutableStateOf<List<WebData>>(emptyList()) }; var isSearching by remember { mutableStateOf(false) }
    LaunchedEffect(query, selLang, isAlbumMode, isSearch) {
        if (!isSearch || selLang.isBlank()) return@LaunchedEffect
        if (query.isBlank()) prefs.getString("gc_${selLang}_$isAlbumMode", null)?.let { runCatching { val a = JSONArray(it); searchRes = (0 until a.length()).map { i -> a.getJSONObject(i).let { o -> WebData(o.optString("id"), o.optString("title"), o.optString("artist"), o.optString("artUrl")) } } } }
        delay(400); isSearching = true; val q = if (query.isBlank()) if (selLang == "All") "Top Hits" else "$selLang Hits" else if (selLang == "All") query else "$query $selLang"
        val fresh = fetchWebSearch(q, isAlbumMode)
        if (fresh.isNotEmpty()) { searchRes = fresh; if (query.isBlank()) runCatching { prefs.edit().putString("gc_${selLang}_$isAlbumMode", JSONArray().apply { fresh.take(15).forEach { put(JSONObject().apply { put("id", it.id); put("title", it.title); put("artist", it.artist); put("artUrl", it.artUrl) }) } }.toString()).apply() } }
        isSearching = false
    }

    var curSong by remember { mutableStateOf<LocalSong?>(null) }; var webMeta by remember { mutableStateOf<WebData?>(null) }
    var actionSong by remember { mutableStateOf<LocalSong?>(null) }; var showEdit by remember { mutableStateOf(false) }; var showAddP by remember { mutableStateOf(false) }; var showNewP by remember { mutableStateOf(false) }
    var queue by remember { mutableStateOf<List<LocalSong>>(emptyList()) }; var isPlaying by remember { mutableStateOf(false) }
    var pos by remember { mutableStateOf(0L) }; var dur by remember { mutableStateOf(0L) }; var showFS by remember { mutableStateOf(false) }
    var apCtx by remember { mutableStateOf<List<WebData>>(emptyList()) }; var apIdx by remember { mutableIntStateOf(-1) }

    val exo = remember { ExoPlayer.Builder(ctx).setMediaSourceFactory(DefaultMediaSourceFactory(ctx).setDataSourceFactory(DefaultDataSource.Factory(ctx, DefaultHttpDataSource.Factory().setUserAgent("Mozilla/5.0").setAllowCrossProtocolRedirects(true)))).build().apply { setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true); setHandleAudioBecomingNoisy(true); repeatMode = Player.REPEAT_MODE_OFF } }
    DisposableEffect(Unit) { onDispose { exo.release() } }

    val playList = { s: LocalSong, l: List<LocalSong> -> apCtx = emptyList(); apIdx = -1; queue = l; exo.stop(); exo.clearMediaItems(); exo.setMediaItems(l.map { MediaItem.Builder().setUri(it.webUrl ?: ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it.id).toString()).setMediaId(it.id.toString()).build() }); exo.seekTo(l.indexOf(s).coerceAtLeast(0), C.TIME_UNSET); exo.prepare(); exo.play() }
    val playWeb = { w: WebData -> scope.launch {
        if (w.id.startsWith("ia:")) {
            val iaQ = withContext(Dispatchers.IO) { runCatching { JSONObject(fetchHttp("https://archive.org/metadata/${w.id.removePrefix("ia:")}")!!).optJSONArray("files")?.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.optString("format").matches(Regex("(?i).*(mp3|flac|ogg|m4a).*")) }.sortedBy { it.optString("track", "999").replace("\\D".toRegex(), "").toIntOrNull() ?: 999 }.map { f -> val t = f.optString("title").takeIf { it.isNotBlank() } ?: f.optString("name").substringBeforeLast("."); val a = f.optString("creator").takeIf { it.isNotBlank() } ?: w.artist.replace("[Album] ", ""); LocalSong(-(kotlin.math.abs((t+a).hashCode().toLong())).takeIf { it != 0L } ?: -1L, t, a, -1L, "https://archive.org/download/${w.id.removePrefix("ia:")}/${Uri.encode(f.optString("name"))}", "${w.artUrl}|||${w.id}") } } }.getOrNull() ?: emptyList() }
            if (iaQ.isNotEmpty()) { val pId = -(kotlin.math.abs((w.title+w.artist).hashCode().toLong())).takeIf { it != 0L } ?: -1L; withContext(Dispatchers.IO) { db.saveSongMemory(SongEntity(pId, w.title, w.artist, w.title, w.artist, "${w.artUrl}|||${w.id}", null, memoryMap[pId]?.isFavorite ?: false, System.currentTimeMillis())) }; webMeta = w; playList(iaQ.first(), iaQ); showFS = true }; return@launch
        }
        val t = w.title.lowercase().replace("[^a-z0-9]".toRegex(), ""); val l = if (t.isNotBlank()) localSongs.find { it.title.lowercase().replace("[^a-z0-9]".toRegex(), "").let { n -> n == t || n.contains(t) || t.contains(n) } } else null
        if (l != null) { playList(l, localSongs); showFS = true; return@launch }
        val stream = fetchAudioUrl(w.id)
        if (stream != null) { val id = -(kotlin.math.abs((w.title+w.artist).hashCode().toLong())).takeIf { it != 0L } ?: -1L; val s = LocalSong(id, w.title, w.artist, -1L, stream, "${w.artUrl}|||${w.id}"); withContext(Dispatchers.IO) { db.saveSongMemory(SongEntity(id, w.title, w.artist, w.title, w.artist, "${w.artUrl}|||${w.id}", null, memoryMap[id]?.isFavorite ?: false, System.currentTimeMillis())) }; webMeta = w; queue = listOf(s); exo.stop(); exo.clearMediaItems(); exo.setMediaItem(MediaItem.Builder().setUri(stream).setMediaId(id.toString()).build()); exo.prepare(); exo.play(); showFS = true } 
        else if (apCtx.isNotEmpty() && apIdx < apCtx.size - 1) { apIdx++; playWeb(apCtx[apIdx]) }
    }}

    val hNext = { if (exo.mediaItemCount > 1) exo.seekToNextMediaItem() else if (apCtx.isNotEmpty() && apIdx < apCtx.size - 1) { apIdx++; playWeb(apCtx[apIdx]) } }
    val hPrev = { if (exo.mediaItemCount > 1) exo.seekToPreviousMediaItem() else if (apCtx.isNotEmpty() && apIdx > 0) { apIdx--; playWeb(apCtx[apIdx]) } else exo.seekTo(0L) }

    DisposableEffect(exo) {
        val lst = object : Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) { isPlaying = p }
            override fun onPlaybackStateChanged(s: Int) { if (s == Player.STATE_ENDED) { if (apCtx.isNotEmpty() && apIdx < apCtx.size - 1) { apIdx++; playWeb(apCtx[apIdx]) } else if (curSong?.id ?: 0 < 0) scope.launch { val r = fetchWebSearch("${curSong!!.artist} Hits", false); if (r.isNotEmpty()) { withContext(Dispatchers.Main) { Toast.makeText(ctx, "Up next: ${r[0].title}", Toast.LENGTH_LONG).show() }; apCtx = r; apIdx = 0; playWeb(r[0]) } } } }
            override fun onMediaItemTransition(m: MediaItem?, r: Int) { if (m == null || exo.mediaItemCount == 0) { curSong = null; isPlaying = false; showFS = false } else if (exo.currentMediaItemIndex in queue.indices) curSong = queue[exo.currentMediaItemIndex] }
        }
        exo.addListener(lst); onDispose { exo.removeListener(lst) }
    }

    LaunchedEffect(curSong) {
        val s = curSong ?: return@LaunchedEffect; if (s.id < 0) return@LaunchedEffect
        val m = db.getSongMemory(s.id); val dT = m?.customTitle?.takeIf { it.isNotBlank() } ?: m?.fetchedTitle?.takeIf { it.isNotBlank() } ?: s.title; val dA = m?.customArtist?.takeIf { it.isNotBlank() } ?: m?.fetchedArtist?.takeIf { it.isNotBlank() } ?: s.artist
        webMeta = WebData("", dT, dA, m?.fetchedArtUrl ?: "", m?.fetchedLyrics); db.saveSongMemory(SongEntity(s.id, m?.customTitle, m?.customArtist, m?.fetchedTitle ?: dT, m?.fetchedArtist ?: dA, m?.fetchedArtUrl, m?.fetchedLyrics, m?.isFavorite ?: false, System.currentTimeMillis()))
        if (m?.fetchedArtUrl == null) { val res = fetchMetadata(dT, dA); if (res != null) { webMeta = res; db.saveSongMemory(SongEntity(s.id, m?.customTitle, m?.customArtist, res.title, res.artist, res.artUrl, res.lyrics, m?.isFavorite ?: false, System.currentTimeMillis())) } }
    }

    LaunchedEffect(isPlaying) { while (isPlaying) { pos = exo.currentPosition; dur = if (exo.duration > 0) exo.duration else 0L; delay(1000L) } }
    LaunchedEffect(Unit) { rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) localSongs = getLocalMusic(ctx); permissionGranted = it }.launch(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE) }
    BackHandler(showFS || viewFavs || viewPid != null) { if (showFS) showFS = false else if (viewFavs) viewFavs = false else viewPid = null }

    Scaffold(
        topBar = { TopAppBar(title = { if (isSearch) TextField(value = query, onValueChange = { query = it }, placeholder = { Text("Search...") }, singleLine = true, colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent)) else Text(when(tab) { 0 -> "Dashboard"; 1 -> "Discover"; 2 -> "Library"; else -> "Offline" }, fontWeight = FontWeight.Bold) }, actions = { IconButton(onClick = { isSearch = !isSearch; query = "" }) { Icon(if (isSearch) Icons.Default.Close else Icons.Default.Search, null) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background.copy(0.95f))) },
        bottomBar = { Column { if (curSong != null) MiniPlayer(curSong!!, webMeta, isPlaying, pos, dur, { if (isPlaying) exo.pause() else exo.play() }, hNext, { showFS = true }); NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) { NavigationBarItem(icon = { Icon(Icons.Default.Home, null) }, label = { Text("Home", fontSize=10.sp) }, selected = tab==0&&!isSearch, onClick = { tab=0; isSearch=false; viewFavs=false; viewPid=null }); NavigationBarItem(icon = { Icon(Icons.Default.Search, null) }, label = { Text("Search", fontSize=10.sp) }, selected = isSearch, onClick = { isSearch=true; tab=1 }); NavigationBarItem(icon = { Icon(Icons.Default.QueueMusic, null) }, label = { Text("Library", fontSize=10.sp) }, selected = tab==2&&!isSearch, onClick = { tab=2; isSearch=false }); NavigationBarItem(icon = { Icon(Icons.Default.Folder, null) }, label = { Text("Offline", fontSize=10.sp) }, selected = tab==3&&!isSearch, onClick = { tab=3; isSearch=false; viewFavs=false; viewPid=null }) } } },
        containerColor = MaterialTheme.colorScheme.background
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (!permissionGranted) Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Permission required.") }
            else if (isSearch) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(selected = !isAlbumMode, onClick = { isAlbumMode = false }, label = { Text("Tracks") }, shape = RoundedCornerShape(20.dp)); FilterChip(selected = isAlbumMode, onClick = { isAlbumMode = true }, label = { Text("Albums") }, shape = RoundedCornerShape(20.dp)) }
                    AnimatedVisibility(!isAlbumMode) { LazyRow(Modifier.padding(top=8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(langs) { l -> FilterChip(selected = selLang == l, onClick = { selLang = l; prefs.edit().putString("lang", l).apply() }, label = { Text(l) }, shape = RoundedCornerShape(20.dp)) } } }
                }
                if (isSearching) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() } else LazyColumn(Modifier.fillMaxSize()) { items(searchRes) { s -> TrackRow(sId = -1L, title = s.title, artist = s.artist, artUrl = s.artUrl, isPlaying = false, onClick = { apCtx = searchRes; apIdx = searchRes.indexOf(s); playWeb(s) }, onLongClick = { actionSong = LocalSong(-(kotlin.math.abs((s.title+s.artist).hashCode().toLong())).takeIf{it!=0L}?:-1L, s.title, s.artist, -1L, null, "${s.artUrl}|||${s.id}"); webMeta = s }) } }
            } else {
                when (tab) {
                    0 -> LazyColumn(Modifier.fillMaxSize()) { if (rSongs.isNotEmpty()) { item { Text("Recently Played", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }; item { LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(horizontal = 16.dp)) { items(rSongs) { s -> val m = memoryMap[s.id]; val dT = m?.customTitle?.takeIf{it.isNotBlank()} ?: m?.fetchedTitle ?: s.title; val dArt = m?.fetchedArtUrl?.substringBefore("|||") ?: s.uri.toString(); Column(Modifier.width(120.dp).clickable { if (s.id < 0) { apCtx = rSongs.filter{it.id<0}.map{WebData(it.customArtUrl?.substringAfter("|||")?:"", it.title, it.artist, it.customArtUrl?.substringBefore("|||")?:"")}; apIdx = apCtx.indexOfFirst{it.title==dT}; if(s.webUrl!=null){ queue=listOf(s); exo.setMediaItem(MediaItem.fromUri(s.webUrl)); exo.prepare(); exo.play(); curSong=s } else playWeb(WebData(m?.fetchedArtUrl?.substringAfter("|||")?:"", dT, s.artist, dArt)) } else { queue = rSongs.filter{it.id>=0}; exo.setMediaItems(queue.map{MediaItem.fromUri(it.uri)}); exo.seekTo(queue.indexOf(s), 0L); exo.prepare(); exo.play() } }) { SubcomposeAsyncImage(ImageRequest.Builder(LocalContext.current).data(dArt).crossfade(true).build(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(120.dp).clip(RoundedCornerShape(8.dp)), error = { FallbackIcon() }); Spacer(Modifier.height(8.dp)); Text(dT, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) } } } } } }
                    2 -> {
                        if (viewFavs) { Row(Modifier.padding(horizontal=16.dp, vertical=12.dp), verticalAlignment=Alignment.CenterVertically) { IconButton(onClick={viewFavs=false}) { Icon(Icons.Default.ArrowBack, null) }; Text("Liked Songs", style=MaterialTheme.typography.headlineSmall, fontWeight=FontWeight.Bold) }; LazyColumn(Modifier.fillMaxSize()) { items(fSongs) { s -> val m = memoryMap[s.id]; val dT = m?.customTitle ?: s.title; val dA = m?.customArtist ?: s.artist; val dArt = m?.fetchedArtUrl?.substringBefore("|||") ?: s.uri.toString(); TrackRow(s.id, dT, dA, dArt, curSong?.id==s.id, onClick={ if(s.id<0) playWeb(WebData(m?.fetchedArtUrl?.substringAfter("|||")?:"", dT, dA, dArt)) else { queue = fSongs.filter{it.id>=0}; exo.setMediaItems(queue.map{MediaItem.fromUri(it.uri)}); exo.seekTo(queue.indexOf(s), 0L); exo.prepare(); exo.play() } }, onLongClick={actionSong=s}) } } }
                        else if (viewPid != null && pData != null) { val pSongs = pData!!.songs.mapNotNull { m -> if(m.localMediaId>=0) localSongs.find{it.id==m.localMediaId} else LocalSong(m.localMediaId, m.fetchedTitle?:"Unk", m.fetchedArtist?:"Unk", -1L, null, m.fetchedArtUrl) }; Row(Modifier.padding(horizontal=16.dp, vertical=12.dp), verticalAlignment=Alignment.CenterVertically) { IconButton(onClick={viewPid=null}) { Icon(Icons.Default.ArrowBack, null) }; Text(pData!!.playlist.name, style=MaterialTheme.typography.headlineSmall, fontWeight=FontWeight.Bold) }; LazyColumn(Modifier.fillMaxSize()) { items(pSongs) { s -> val m = memoryMap[s.id]; val dT = m?.customTitle ?: s.title; val dA = m?.customArtist ?: s.artist; val dArt = m?.fetchedArtUrl?.substringBefore("|||") ?: s.uri.toString(); TrackRow(s.id, dT, dA, dArt, curSong?.id==s.id, onClick={ if(s.id<0) playWeb(WebData(m?.fetchedArtUrl?.substringAfter("|||")?:"", dT, dA, dArt)) else { queue = pSongs.filter{it.id>=0}; exo.setMediaItems(queue.map{MediaItem.fromUri(it.uri)}); exo.seekTo(queue.indexOf(s), 0L); exo.prepare(); exo.play() } }, onLongClick={actionSong=s}) } } }
                        else { Row(Modifier.fillMaxWidth().padding(horizontal=16.dp, vertical=12.dp), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) { Text("Your Library", style=MaterialTheme.typography.headlineSmall, fontWeight=FontWeight.Bold); IconButton(onClick={showNewP=true}){ Icon(Icons.Default.Add, null) } }; LazyColumn(Modifier.padding(horizontal=16.dp)) { item { Row(Modifier.fillMaxWidth().padding(vertical=12.dp).clickable{viewFavs=true}, verticalAlignment=Alignment.CenterVertically) { Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(0.1f)), contentAlignment=Alignment.Center) { Icon(Icons.Default.Favorite, null, tint=Color.White) }; Column(Modifier.padding(start=16.dp)) { Text("Liked Songs", fontWeight=FontWeight.Bold, fontSize=16.sp); Text("${fSongs.size} tracks", color=MaterialTheme.colorScheme.onSurface.copy(0.5f), fontSize=13.sp) } } }; items(playlists) { p -> Row(Modifier.fillMaxWidth().padding(vertical=12.dp).clickable{viewPid=p.playlistId}, verticalAlignment=Alignment.CenterVertically) { Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(0.1f)), contentAlignment=Alignment.Center) { Icon(Icons.Default.QueueMusic, null) }; Column(Modifier.padding(start=16.dp)) { Text(p.name, fontWeight=FontWeight.Bold, fontSize=16.sp); Text("Playlist", color=MaterialTheme.colorScheme.onSurface.copy(0.5f), fontSize=13.sp) } } } } }
                    }
                    3 -> LazyColumn(Modifier.fillMaxSize()) { items(localSongs) { s -> val m = memoryMap[s.id]; val dT = m?.customTitle ?: s.title; val dA = m?.customArtist ?: s.artist; val dArt = m?.fetchedArtUrl?.substringBefore("|||") ?: s.uri.toString(); TrackRow(s.id, dT, dA, dArt, curSong?.id==s.id, onClick={ playQueue = localSongs; exo.setMediaItems(localSongs.map{MediaItem.fromUri(it.uri)}); exo.seekTo(localSongs.indexOf(s), 0L); exo.prepare(); exo.play() }, onLongClick={actionSong=s}) } }
                }
            }
        }
        AnimatedVisibility(showFS && curSong != null, enter = slideInVertically { it }, exit = slideOutVertically { it }) { if (curSong != null) { val isL = dur <= 0L; FullScreenPlayer(curSong!!, webMeta, isPlaying, pos, dur, memoryMap[curSong?.id]?.isFavorite == true, queue, memoryMap, { q -> if (q.id < 0 && q.webUrl == null) playWeb(WebData(q.customArtUrl?.substringAfter("|||") ?: "", q.title, q.artist, q.customArtUrl?.substringBefore("|||") ?: "")) else { exo.seekTo(queue.indexOf(q), 0L); exo.play() } }, { i -> if (i in queue.indices) { queue = queue.toMutableList().apply { removeAt(i) }; runCatching { exo.removeMediaItem(i) }; if (exo.mediaItemCount == 0) { exo.stop(); isPlaying = false; showFS = false; curSong = null } } }, { showFS = false }, { if (isPlaying) exo.pause() else exo.play() }, hNext, hPrev, { p -> if(!isL) { exo.seekTo((p * dur).toLong()); pos = (p * dur).toLong() } }, { scope.launch { db.updateFavoriteStatus(curSong!!.id, !(memoryMap[curSong!!.id]?.isFavorite ?: false)) } }, isL, if (!isL) (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f) } }
        
        if (actionSong != null) { val s = actionSong!!; AlertDialog(onDismissRequest = { actionSong = null }, confirmButton = { TextButton(onClick={actionSong=null}){Text("Cancel")} }, title = { Text("Options") }, text = { Column { TextButton(onClick = { if (s.id < 0) scope.launch { fetchAudioUrl(s.customArtUrl?.substringAfter("|||") ?: "")?.let { queue = queue + s.copy(webUrl = it); exo.addMediaItem(MediaItem.fromUri(it)); db.saveSongMemory(SongEntity(s.id, s.title, s.artist, s.title, s.artist, s.customArtUrl, null, false, System.currentTimeMillis())) } } else { queue = queue + s; exo.addMediaItem(MediaItem.fromUri(s.uri)) }; actionSong = null }, Modifier.fillMaxWidth()) { Text("Add to Queue") }; TextButton(onClick = { showAddP = true }, Modifier.fillMaxWidth()) { Text("Add to Playlist") }; if (s.id >= 0) TextButton(onClick = { showEdit = true }, Modifier.fillMaxWidth()) { Text("Edit Metadata") } } }) }
        if (showEdit && actionSong != null) { val s = actionSong!!; val m = memoryMap[s.id]; var eT by remember { mutableStateOf(m?.customTitle ?: s.title) }; var eA by remember { mutableStateOf(m?.customArtist ?: s.artist) }; AlertDialog(onDismissRequest = { showEdit = false; actionSong = null }, confirmButton = { Button(onClick = { scope.launch { db.saveSongMemory(SongEntity(s.id, eT.trim().takeIf{it.isNotEmpty()}, eA.trim().takeIf{it.isNotEmpty()}, null, null, null, null, m?.isFavorite ?: false, m?.lastPlayedAt ?: 0L)); showEdit = false; actionSong = null; playList(s, localSongs) } }) { Text("Save") } }, title = { Text("Fix Metadata") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { OutlinedTextField(eT, { eT = it }, label = { Text("Title") }, singleLine = true); OutlinedTextField(eA, { eA = it }, label = { Text("Artist") }, singleLine = true) } }) }
        if (showNewP) { var n by remember { mutableStateOf("") }; AlertDialog(onDismissRequest = { showNewP = false }, confirmButton = { Button(onClick = { if (n.isNotBlank()) { scope.launch { db.createPlaylist(PlaylistEntity(name = n.trim())) }; showNewP = false } }) { Text("Create") } }, title = { Text("New Playlist") }, text = { OutlinedTextField(n, { n = it }, label = { Text("Name") }, singleLine = true) }) }
        if (showAddP && actionSong != null) { AlertDialog(onDismissRequest = { showAddP = false; actionSong = null }, confirmButton = { TextButton(onClick={showAddP=false; actionSong=null}){Text("Cancel")} }, title = { Text("Select Playlist") }, text = { LazyColumn { items(playlists) { p -> TextButton(onClick = { scope.launch { if (db.getSongMemory(actionSong!!.id) == null) db.saveSongMemory(SongEntity(actionSong!!.id, null, null, null, null, actionSong!!.customArtUrl, null, false, 0L)); db.addSongToPlaylist(PlaylistSongCrossRef(p.playlistId, actionSong!!.id)) }; showAddP = false; actionSong = null }, Modifier.fillMaxWidth()) { Text(p.name) } } } }) }
    }
}

fun getLocalMusic(ctx: Context) = mutableListOf<LocalSong>().apply {
    ctx.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM_ID), "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} ASC")?.use { c ->
        val i = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID); val t = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE); val a = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST); val al = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        while (c.moveToNext()) c.getString(t)?.takeIf { !it.matches(Regex("(?i)(.*\\d{8}.*|.*aud-.*|.*ptt-.*|.*wa00.*|.*record.*)")) }?.let { add(LocalSong(c.getLong(i), it, c.getString(a) ?: "Unknown", c.getLong(al))) }
    }
}

private fun fetchHttp(url: String) = runCatching { (URL(url).openConnection() as HttpURLConnection).apply { setRequestProperty("User-Agent", "Mozilla/5.0"); connectTimeout = 6000; readTimeout = 6000 }.inputStream.bufferedReader().readText() }.getOrNull()

suspend fun getAutoLang() = withContext(Dispatchers.IO) {
    runCatching { val j = JSONObject(fetchHttp("https://ipwho.is/")!!); if (j.optString("country") == "India") { val r = j.optString("region"); when { r.contains("Tamil", true) -> "Tamil"; r.contains("Maharashtra", true) -> "Marathi"; else -> "Hindi" } } else "All" }.getOrDefault("All")
}

suspend fun fetchWebSearch(q: String, isP: Boolean) = coroutineScope {
    val qE = URLEncoder.encode(q, "UTF-8")
    val sTask = async(Dispatchers.IO) { if (isP) emptyList() else listOf("saavn.dev", "saavn.sumit.co").firstNotNullOfOrNull { api -> runCatching { JSONObject(fetchHttp("https://$api/api/search/songs?query=$qE")!!).optJSONObject("data")?.optJSONArray("results")?.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it).let { t -> WebData(t.optString("id"), t.optString("name", t.optString("title")).replace("&quot;", "\""), t.optJSONArray("primaryArtists")?.optJSONObject(0)?.optString("name") ?: "", t.optJSONArray("image")?.optJSONObject(2)?.optString("link") ?: "") } } } }.getOrNull() } ?: runCatching { JSONObject(fetchHttp("https://www.jiosaavn.com/api.php?__call=autocomplete.get&_format=json&_marker=0&cc=in&query=$qE")!!).optJSONObject("songs")?.optJSONArray("data")?.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it).let { t -> WebData(t.optString("id"), t.optString("title", t.optString("name")).replace("&quot;", "\""), t.optJSONObject("more_info")?.optString("primary_artists") ?: "", t.optString("image").replace("50x50.jpg", "500x500.jpg")) } } } }.getOrDefault(emptyList()) }
    val aTask = async(Dispatchers.IO) { if (!isP) emptyList() else runCatching { JSONObject(fetchHttp("https://archive.org/advancedsearch.php?q=${URLEncoder.encode("${if (q.isBlank()) "subject:\"anime\" OR subject:\"soundtrack\"" else "($q)"} AND mediatype:audio AND (subject:\"ost\" OR subject:\"music\" OR subject:\"soundtrack\") AND NOT subject:\"news\" AND NOT subject:\"podcast\" AND NOT subject:\"ep\" AND NOT subject:\"broadcast\" AND NOT creator:\"voa\"", "UTF-8")}&fl[]=identifier,title,creator&rows=15&output=json")!!).optJSONObject("response")?.optJSONArray("docs")?.let { docs -> (0 until docs.length()).mapNotNull { docs.getJSONObject(it).let { t -> val id = t.optString("identifier"); if (id.isNotBlank()) WebData("ia:$id", t.optString("title"), "[Album] ${t.optString("creator")}", "https://archive.org/services/img/$id") else null } } } }.getOrDefault(emptyList()) }
    val pTask = async(Dispatchers.IO) { if (isP) emptyList() else listOf("pipedapi.kavin.rocks", "pipedapi.tokhmi.xyz", "piped.projectsegfau.lt", "pipedapi.smnz.de").firstNotNullOfOrNull { inst -> runCatching { JSONObject(fetchHttp("https://$inst/search?q=$qE&filter=music_songs")!!).optJSONArray("items")?.let { items -> (0 until minOf(items.length(), 10)).mapNotNull { items.getJSONObject(it).takeIf { it.optString("type") == "stream" }?.let { t -> WebData("yt:${t.optString("url").substringAfter("v=").substringBefore("&")}", t.optString("title"), t.optString("uploaderName"), t.optString("thumbnail")) } } } }.getOrNull() } ?: emptyList() }
    (sTask.await() + aTask.await() + pTask.await()).distinctBy { it.title.lowercase() }
}

suspend fun fetchAudioUrl(id: String) = withContext(Dispatchers.IO) {
    if (!id.startsWith("ia:") && !id.startsWith("yt:")) listOf("saavn.dev", "saavn.sumit.co").firstNotNullOfOrNull { api -> runCatching { JSONObject(fetchHttp("https://$api/api/songs?ids=$id")!!).optJSONArray("data")?.getJSONObject(0)?.optJSONArray("downloadUrl")?.let { it.getJSONObject(it.length()-1).optString("link") } }.getOrNull() }
    else if (id.startsWith("yt:")) listOf("pipedapi.kavin.rocks", "pipedapi.tokhmi.xyz", "piped.projectsegfau.lt", "pipedapi.smnz.de").firstNotNullOfOrNull { inst -> runCatching { val s = JSONObject(fetchHttp("https://$inst/streams/${id.removePrefix("yt:")}")!!).optJSONArray("audioStreams"); var bU: String? = null; var hB = 0; if (s != null) { for (i in 0 until s.length()) { val t = s.getJSONObject(i); val f = t.optString("format").lowercase(); val m = t.optString("mimeType").lowercase(); val b = t.optInt("bitrate", 0); if (f.contains("m4a") || f.contains("webm") || m.contains("audio")) { if (b >= hB) { hB = b; bU = t.optString("url") } } } }; bU }.getOrNull() }
    else null
}

suspend fun fetchMetadata(title: String, artist: String) = coroutineScope {
    val cT = title.lowercase().replace(".mp3", "").replace(".m4a", "").replace(Regex("\\[.*?\\]|\\(.*?\\)"), "").trim()
    val t1 = async(Dispatchers.IO) { runCatching { JSONObject(fetchHttp("https://itunes.apple.com/search?term=${URLEncoder.encode("$cT $artist", "UTF-8")}&limit=1")!!).optJSONArray("results")?.optJSONObject(0)?.let { t -> WebData("", t.optString("trackName"), t.optString("artistName"), t.optString("artworkUrl100").replace("100x100bb", "600x600bb")) } }.getOrNull() }
    val t2 = async(Dispatchers.IO) { runCatching { JSONObject(fetchHttp("https://www.jiosaavn.com/api.php?__call=autocomplete.get&_format=json&_marker=0&cc=in&query=${URLEncoder.encode("$cT $artist", "UTF-8")}")!!).optJSONObject("songs")?.optJSONArray("data")?.optJSONObject(0)?.let { t -> WebData("", t.optString("title", t.optString("name")).replace("&quot;", "\""), t.optJSONObject("more_info")?.optString("primary_artists") ?: "", t.optString("image").replace("50x50.jpg", "500x500.jpg")) } }.getOrNull() }
    (t1.await() ?: t2.await())?.copy(lyrics = runCatching { val a = JSONArray(fetchHttp("https://lrclib.net/api/search?q=${URLEncoder.encode("$title $artist", "UTF-8")}")!!); if (a.length() > 0) a.getJSONObject(0).optString("plainLyrics") else null }.getOrNull())
}
