package com.sonicsync.app

import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer

class MainActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private var statusText: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }

        // Server URL input
        val serverLabel = TextView(this).apply {
            text = "WebSocket Server URL:"
            textSize = 14f
        }
        val serverInput = EditText(this).apply {
            hint = "ws://192.168.0.101:3000/ws"
            setText("ws://192.168.0.101:3000/ws")
            isSingleLine = true
        }

        // Audio URL input
        val audioLabel = TextView(this).apply {
            text = "Audio URL (direct MP3/WAV link):"
            textSize = 14f
        }
        val audioInput = EditText(this).apply {
            hint = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3"
            setText("https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3")
            isSingleLine = true
        }

        // Status display
        statusText = TextView(this).apply {
            text = "Status: Not connected"
            textSize = 12f
            setPadding(0, 16, 0, 16)
        }

        // Connect button
        val connectButton = Button(this).apply {
            text = "CONNECT TO SERVER"
        }
        connectButton.setOnClickListener {
            val wsUrl = serverInput.text.toString().trim()
            if (wsUrl.isNotEmpty()) {
                statusText?.text = "Status: Connecting to $wsUrl..."
                try {
                    SonicSyncEngine.safeInitLogger()
                    SonicSyncEngine.safeConnect(wsUrl, object : SonicSyncEngine.SyncCallback {
                        override fun onPlayCommand(url: String, startAtServerTime: Long, currentServerOffset: Long) {
                            runOnUiThread {
                                statusText?.text = "Status: Received play command for $url"
                                playAudio(url)
                            }
                        }
                    })
                    if (SonicSyncEngine.isNativeAvailable()) {
                        statusText?.text = "Status: Connected to $wsUrl"
                    } else {
                        statusText?.text = "Status: Audio-only mode (no native sync lib)"
                    }
                } catch (e: Exception) {
                    statusText?.text = "Status: Connection failed - ${e.message}"
                    Log.e("SonicSync", "Connection error", e)
                }
            } else {
                Toast.makeText(this, "Please enter server URL", Toast.LENGTH_SHORT).show()
            }
        }

        // Play button - play audio directly on this device
        val playButton = Button(this).apply {
            text = "PLAY AUDIO"
        }
        playButton.setOnClickListener {
            val audioUrl = audioInput.text.toString().trim()
            if (audioUrl.isNotEmpty()) {
                statusText?.text = "Status: Playing $audioUrl"
                playAudio(audioUrl)
            } else {
                Toast.makeText(this, "Please enter an audio URL", Toast.LENGTH_SHORT).show()
            }
        }

        // Stop button
        val stopButton = Button(this).apply {
            text = "STOP AUDIO"
        }
        stopButton.setOnClickListener {
            player?.stop()
            statusText?.text = "Status: Stopped"
        }

        layout.addView(serverLabel)
        layout.addView(serverInput)
        layout.addView(connectButton)
        layout.addView(TextView(this).apply { text = " " }) // spacer
        layout.addView(audioLabel)
        layout.addView(audioInput)
        layout.addView(playButton)
        layout.addView(stopButton)
        layout.addView(statusText)

        setContentView(layout)
    }

    private fun playAudio(url: String) {
        try {
            if (player == null) {
                player = ExoPlayer.Builder(this).build()
            }
            player?.stop()
            val mediaItem = MediaItem.fromUri(url)
            player?.setMediaItem(mediaItem)
            player?.prepare()
            player?.play()
            statusText?.text = "Status: Playing audio..."
            Log.i("SonicSync", "Playing: $url")
        } catch (e: Exception) {
            statusText?.text = "Status: Playback error - ${e.message}"
            Log.e("SonicSync", "Playback error", e)
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
