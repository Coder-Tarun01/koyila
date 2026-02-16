package com.sonicsync.app

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer

class MainActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private var statusText: TextView? = null
    private var audioInput: EditText? = null
    private var selectedFileUri: Uri? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            // Take persistent permission so we can play it later
            contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            selectedFileUri = uri
            audioInput?.setText("📁 ${getFileName(uri)}")
            statusText?.text = "Status: Local file selected"
        }
    }

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
            hint = "wss://koyila-bl6c.onrender.com/ws"
            setText("wss://koyila-bl6c.onrender.com/ws")
            isSingleLine = true
        }

        // Audio URL input
        val audioLabel = TextView(this).apply {
            text = "Audio URL or select a local file:"
            textSize = 14f
        }
        audioInput = EditText(this).apply {
            hint = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3"
            setText("https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3")
            isSingleLine = true
        }

        // Browse local file button
        val browseButton = Button(this).apply {
            text = "📂 PICK FROM DEVICE"
        }
        browseButton.setOnClickListener {
            selectedFileUri = null
            filePickerLauncher.launch(arrayOf("audio/*"))
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

        // Broadcast Play button (Host Control)
        val hostPlayButton = Button(this).apply {
            text = "📢 BROADCAST PLAY (HOST)"
        }
        hostPlayButton.setOnClickListener {
            val audioUrl = audioInput?.text.toString().trim()
            if (audioUrl.isEmpty() || audioUrl.startsWith("📁")) {
                Toast.makeText(this, "Enter an audio URL to broadcast", Toast.LENGTH_SHORT).show()
            } else {
                if (audioUrl.contains("youtube.com") || audioUrl.contains("youtu.be")) {
                    Toast.makeText(this, "Resolving YouTube link on server...", Toast.LENGTH_SHORT).show()
                }
                statusText?.text = "Status: Sending broadcast request..."
                SonicSyncEngine.safeRequestPlay(audioUrl)
            }
        }

        // Play button - play audio directly on this device
        val playButton = Button(this).apply {
            text = "▶ PLAY LOCALLY"
        }
        playButton.setOnClickListener {
            if (selectedFileUri != null) {
                // Play local file
                statusText?.text = "Status: Playing local file..."
                playAudio(selectedFileUri.toString())
            } else {
                // Play from URL
                val audioUrl = audioInput?.text.toString().trim()
                if (audioUrl.isNotEmpty() && !audioUrl.startsWith("📁")) {
                    statusText?.text = "Status: Playing from URL..."
                    playAudio(audioUrl)
                } else {
                    Toast.makeText(this, "Enter an audio URL or pick a file", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Stop button
        val stopButton = Button(this).apply {
            text = "⏹ STOP AUDIO"
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
        layout.addView(browseButton)
        layout.addView(hostPlayButton)
        layout.addView(playButton)
        layout.addView(stopButton)
        layout.addView(statusText)

        setContentView(layout)
    }

    private fun playAudio(uriString: String) {
        try {
            if (player == null) {
                player = ExoPlayer.Builder(this).build()
            }
            player?.stop()
            val mediaItem = MediaItem.fromUri(uriString)
            player?.setMediaItem(mediaItem)
            player?.prepare()
            player?.play()
            statusText?.text = "Status: Playing audio... 🎶"
            Log.i("SonicSync", "Playing: $uriString")
        } catch (e: Exception) {
            statusText?.text = "Status: Playback error - ${e.message}"
            Log.e("SonicSync", "Playback error", e)
        }
    }

    private fun getFileName(uri: Uri): String {
        var name = "Unknown file"
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = it.getString(idx)
            }
        }
        return name
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
