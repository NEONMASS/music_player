package com.neo.musicplayer

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*nimport androidx.compose.material.*nimport androidx.compose.runtime.*nimport androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.android.exoplayer2.*nimport com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.ui.PlayerView
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var audioManager: AudioManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(AudioManager::class.java)
        setContent { 
            MusicPlayerUI() 
        }
    }

    @Composable
    fun MusicPlayerUI() {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Button(onClick = { playMusic() }) {
                Text("Play Music")
            }
            Button(onClick = { pauseMusic() }) {
                Text("Pause Music")
            }
        }
    }

    private fun initPlayer() {
        player = ExoPlayer.Builder(this).build()
        val mediaItem = MediaItem.fromUri("https://your_music_file_url_here")
        player.setMediaItem(mediaItem)
        player.prepare()
    }

    private fun playMusic() {
        initPlayer()
        player.playWhenReady = true
    }

    private fun pauseMusic() {
        player.playWhenReady = false
    }

    override fun onStop() {
        super.onStop()
        player.release()
    }
}