cat << 'EOF' > app/src/main/java/com/neo/musicplayer/MainActivity.kt
package com.neo.musicplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MusicPlayerUI()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicPlayerUI() {
    // This state remembers what you type in the search bar
    var searchQuery by remember { mutableStateOf("") }
    
    // Dummy data until we hook up the Room Database
    val dummySongs = listOf("Cyberpunk City - Synthwave", "Neon Rain - Lofi", "Midnight Drive - Retrowave")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hybrid Player", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        bottomBar = {
            BottomPlayerControls()
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // 1. The Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search for a song...") },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { 
                        // TODO: Trigger Jsoup Web Scraper here
                        println("Searching for: $searchQuery") 
                    }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                }
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            Text("Local Playlist", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            // 2. The Playlist (LazyColumn)
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(dummySongs) { song ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(song)
                            IconButton(onClick = { /* TODO: Send to ExoPlayer */ }) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Play")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BottomPlayerControls() {
    // 3. The Player Controls
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("No track selected", fontWeight = FontWeight.Bold)
                Text("Ready to stream", style = MaterialTheme.typography.bodySmall)
            }
            Row {
                IconButton(onClick = { /* TODO: Pause/Play ExoPlayer */ }) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Play/Pause")
                }
            }
        }
    }
}
EOF