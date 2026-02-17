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

    private lateinit var discoveryManager: DiscoveryManager
    private val discoveredServices = mutableListOf<android.net.nsd.NsdServiceInfo>()
    private lateinit var servicesAdapter: ArrayAdapter<String>
    private val serviceNames = mutableListOf<String>()
    
    // Status text views for different modes
    private var hostStatusTextView: TextView? = null
    private var clientStatusTextView: TextView? = null

    // Sync State
    private var currentSyncTrackUrl: String? = null
    private var currentSyncStartTime: Long = 0
    private var currentSyncStartPos: Long = 0
    private var isSyncedPlaying: Boolean = false
    private val driftCorrectionHandler = android.os.Handler(android.os.Looper.getMainLooper())

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

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val intent = android.content.Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_START
                putExtra(AudioCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(AudioCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            startForegroundService(intent)
            statusText?.text = "Status: Live Stream Started 🔴"
        } else {
            Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    // UI Layout Containers
    private lateinit var mainLayout: FrameLayout
    private lateinit var modeSelectionLayout: LinearLayout
    private lateinit var hostLayout: ScrollView
    private lateinit var clientLayout: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // --- DISCOVERY MANAGER SETUP ---
        discoveryManager = DiscoveryManager(this, object : DiscoveryManager.DiscoveryListener {
             override fun onServiceFound(serviceInfo: android.net.nsd.NsdServiceInfo) {
                runOnUiThread {
                    // Check if already in list
                    val existing = discoveredServices.find { 
                        it.serviceName == serviceInfo.serviceName && it.host == serviceInfo.host 
                    }
                    if (existing == null) {
                        discoveredServices.add(serviceInfo)
                        val hostLabel = "${serviceInfo.serviceName} (${serviceInfo.host?.hostAddress}:${serviceInfo.port})"
                        serviceNames.add(hostLabel)
                        servicesAdapter.notifyDataSetChanged()
                    }
                }
            }
            override fun onServiceLost(serviceInfo: android.net.nsd.NsdServiceInfo) {
                runOnUiThread {
                    val index = discoveredServices.indexOfFirst { it.serviceName == serviceInfo.serviceName }
                    if (index != -1) {
                        discoveredServices.removeAt(index)
                        serviceNames.removeAt(index)
                        servicesAdapter.notifyDataSetChanged()
                    }
                }
            }
            override fun onDiscoveryStopped() {
                runOnUiThread {
                    statusText?.text = "Status: Discovery stopped"
                }
            }
        })
        
        // --- 1. SETUP ROOT LAYOUT ---
        mainLayout = FrameLayout(this)
        
        // --- 2. SETUP MODE SELECTION SCREEN ---
        modeSelectionLayout = createModeSelectionView()
        
        // --- 3. SETUP HOST SCREEN ---
        hostLayout = createHostView()
        hostLayout.visibility = android.view.View.GONE
        
        // --- 4. SETUP CLIENT SCREEN ---
        clientLayout = createClientView()
        clientLayout.visibility = android.view.View.GONE
        
        mainLayout.addView(modeSelectionLayout)
        mainLayout.addView(hostLayout)
        mainLayout.addView(clientLayout)
        
        setContentView(mainLayout)
    }

    private fun createModeSelectionView(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        
        val title = TextView(this).apply {
            text = "SonicSync"
            textSize = 32f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 64)
        }
        
        val hostButton = Button(this).apply {
            text = "🏠 START A PARTY (HOST)"
            textSize = 18f
            setPadding(32, 32, 32, 32)
        }
        hostButton.setOnClickListener {
            modeSelectionLayout.visibility = android.view.View.GONE
            hostLayout.visibility = android.view.View.VISIBLE
        }
        
        val joinButton = Button(this).apply {
            text = "🎧 JOIN A PARTY (SPEAKER)"
            textSize = 18f
            setPadding(32, 32, 32, 32)
        }
        joinButton.setOnClickListener {
            modeSelectionLayout.visibility = android.view.View.GONE
            clientLayout.visibility = android.view.View.VISIBLE
             // Auto-start scan
            discoveredServices.clear()
            serviceNames.clear()
            servicesAdapter.notifyDataSetChanged()
            discoveryManager.startDiscovery()
            statusText?.text = "Status: Scanning for hosts..."
        }
        
        layout.addView(title)
        layout.addView(hostButton)
        layout.addView(TextView(this).apply { height = 48 }) // Spacer
        layout.addView(joinButton)
        
        return layout
    }

    private fun createHostView(): ScrollView {
        val scrollView = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        
        val backButton = Button(this).apply { text = "⬅ BACK" }
        backButton.setOnClickListener {
            // Stop stuff if running
            try {
                 val intent = android.content.Intent(this, AudioCaptureService::class.java).apply {
                    action = AudioCaptureService.ACTION_STOP
                }
                startService(intent)
                SonicSyncEngine.stopServer()
                discoveryManager.unregisterService()
                stopSyncHeartbeat()
            } catch (e: Exception) {}
            
            hostLayout.visibility = android.view.View.GONE
            modeSelectionLayout.visibility = android.view.View.VISIBLE
            statusText?.text = "Status: Idle"
        }

        val title = TextView(this).apply {
             text = "HOST A PARTY"
             textSize = 24f
             typeface = android.graphics.Typeface.DEFAULT_BOLD
             gravity = android.view.Gravity.CENTER
             setPadding(0, 0, 0, 32)
        }
        
        // Host Controls
        val startHostButton = Button(this).apply { text = "START SERVER" }
        startHostButton.setOnClickListener {
            val port = 3000
             try {
                 SonicSyncEngine.safeInitLogger() // Initialize Rust logging on host
                 SonicSyncEngine.startServer(port)
                 discoveryManager.registerService(port)
                 statusText?.text = "Status: Server running on port $port"
                 Toast.makeText(this, "Host started!", Toast.LENGTH_SHORT).show()
             } catch (e: Exception) {
                 Log.e("SonicSync", "Failed to start host", e)
                 statusText?.text = "Status: Failed to start host"
             }
        }
        
        val liveStreamButton = Button(this).apply {
            text = "🔴 START LIVE STREAM"
            isEnabled = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
        }
        liveStreamButton.setOnClickListener {
             val projectionManager = getSystemService(android.media.projection.MediaProjectionManager::class.java)
             mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }
        
        // File selection
        val audioLabel = TextView(this).apply { text = "Broadcast File / URL:"; setPadding(0, 20, 0, 0) }
        audioInput = EditText(this).apply {
            hint = "Enter URL or Pick File"
            setText("https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3")
            isSingleLine = true
        }
        val browseButton = Button(this).apply { text = "📂 PICK FILE" }
        browseButton.setOnClickListener {
             selectedFileUri = null
             filePickerLauncher.launch(arrayOf("audio/*"))
        }
        
        val broadcastButton = Button(this).apply { text = "📢 BROADCAST" }
        broadcastButton.setOnClickListener {
            val audioUrl = audioInput?.text.toString().trim()
            val hostIp = getLocalIpAddress() ?: "localhost"
            val port = 3000

            if (selectedFileUri != null && audioInput?.text.toString().startsWith("📁")) {
                val filePath = getRealPathFromURI(selectedFileUri!!)
                if (filePath != null) {
                    updateStatus("Status: Hosting local file and broadcasting...")
                    if (SonicSyncEngine.isNativeAvailable()) {
                        SonicSyncEngine.hostFile(filePath)
                        val streamUrl = "http://$hostIp:$port/stream"
                        val delayMs = 3000L
                        SonicSyncEngine.safeBroadcastPlay(streamUrl, delayMs)
                        val now = SonicSyncEngine.getServerTime()
                        prepareAudio(streamUrl)
                        startHostPlaybackDelayed(delayMs, 0, now + (delayMs * 1000))
                    } else {
                        // Fallback: Local playback
                        prepareAudio(filePath)
                        player?.play()
                        updateStatus("Status: Playing Local File (No Sync - Native Missing)")
                    }
                } else {
                    Toast.makeText(this, "Could not resolve file path", Toast.LENGTH_SHORT).show()
                }
            } else if (audioUrl.isNotEmpty()) {
                 updateStatus("Status: Broadcasting to all clients...")
                 if (SonicSyncEngine.isNativeAvailable()) {
                     val delayMs = 3000L
                     SonicSyncEngine.safeBroadcastPlay(audioUrl, delayMs)
                     val now = SonicSyncEngine.getServerTime()
                     prepareAudio(audioUrl)
                     startHostPlaybackDelayed(delayMs, 0, now + (delayMs * 1000))
                 } else {
                     prepareAudio(audioUrl)
                     player?.play()
                     updateStatus("Status: Playing (No Sync - Native Missing)")
                 }
            } else {
                 Toast.makeText(this, "Enter an audio URL or pick a file first", Toast.LENGTH_SHORT).show()
            }
        }

        // Shared Status
        hostStatusTextView = TextView(this).apply {
            text = "Status: Idle"
            setPadding(0, 30, 0, 30)
            gravity = android.view.Gravity.CENTER
        }

        // Controls Container
        val controlsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(0, 32, 0, 32)
        }
        
        val btnPause = Button(this).apply { text = "⏸ PAUSE" }
        btnPause.setOnClickListener {
            if (isSyncedPlaying || (player != null && player!!.isPlaying)) {
                 if (SonicSyncEngine.isNativeAvailable()) {
                     SonicSyncEngine.sendPause()
                 }
                 player?.pause() 
                 isSyncedPlaying = false
                 stopDriftCorrection()
                 updateStatus("Status: Paused by Host")
            }
        }
        
        val btnResume = Button(this).apply { text = "▶ RESUME" }
        btnResume.setOnClickListener {
             val pos = player?.currentPosition ?: 0
             if (SonicSyncEngine.isNativeAvailable()) {
                 SonicSyncEngine.sendPlay(pos)
                 val delayMs = 500L
                 val now = SonicSyncEngine.getServerTime()
                 startHostPlaybackDelayed(delayMs, pos, now + (delayMs * 1000))
                 updateStatus("Status: Resumed by Host (Synced)")
             } else {
                 player?.play()
                 updateStatus("Status: Resumed (Local)")
             }
        }
        
        val btnSeek = Button(this).apply { text = "⏩ +10s" }
        btnSeek.setOnClickListener {
             val pos = (player?.currentPosition ?: 0) + 10000
             if (SonicSyncEngine.isNativeAvailable()) {
                 SonicSyncEngine.sendSeek(pos)
                 
                 if (player?.isPlaying == true || isSyncedPlaying) {
                     player?.pause() // Pause immediately while waiting for sync
                     val delayMs = 500L
                     val now = SonicSyncEngine.getServerTime()
                     startHostPlaybackDelayed(delayMs, pos, now + (delayMs * 1000))
                 } else {
                     player?.seekTo(pos)
                 }
                 updateStatus("Status: Seeked +10s (Synced)")
             } else {
                 player?.seekTo(pos)
                 updateStatus("Status: Seeked +10s (Local)")
             }
        }
        
        val btnStop = Button(this).apply { text = "⏹ STOP" }
        btnStop.setOnClickListener {
             if (SonicSyncEngine.isNativeAvailable()) {
                 SonicSyncEngine.sendPause()
             }
             player?.stop()
             isSyncedPlaying = false // Ensure we stop drift correction
             stopDriftCorrection()
             updateStatus("Status: Stopped")
        }

        controlsLayout.addView(btnPause)
        controlsLayout.addView(btnResume)
        controlsLayout.addView(btnSeek)
        controlsLayout.addView(btnStop)

        layout.addView(backButton)
        layout.addView(title)
        layout.addView(startHostButton)
        layout.addView(liveStreamButton)
        layout.addView(android.widget.TextView(this).apply { text = "\nOR PLAY FILE:\n" })
        layout.addView(audioLabel)
        layout.addView(audioInput)
        layout.addView(browseButton)
        layout.addView(broadcastButton)
        layout.addView(controlsLayout) // Added controls
        layout.addView(hostStatusTextView) // Add status text here for Host view

        scrollView.addView(layout)
        return scrollView
    }

    private fun createClientView(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }

        val backButton = Button(this).apply { text = "⬅ BACK" }
        backButton.setOnClickListener {
            // Stop client stuff
            player?.stop()
            discoveryManager.stopDiscovery()
            stopSyncHeartbeat()
            
            clientLayout.visibility = android.view.View.GONE
            modeSelectionLayout.visibility = android.view.View.VISIBLE
            updateStatus("Status: Idle")
        }

        val title = TextView(this).apply {
             text = "JOIN A PARTY"
             textSize = 24f
             typeface = android.graphics.Typeface.DEFAULT_BOLD
             gravity = android.view.Gravity.CENTER
             setPadding(0, 0, 0, 32)
        }
        
        val scanButton = Button(this).apply { text = "🔄 RESCAN" }
        scanButton.setOnClickListener {
            discoveredServices.clear()
            serviceNames.clear()
            servicesAdapter.notifyDataSetChanged()
            discoveryManager.startDiscovery()
            Toast.makeText(this, "Scanning...", Toast.LENGTH_SHORT).show()
            updateStatus("Status: Scanning...")
        }
        
        servicesAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, serviceNames)
        val servicesList = ListView(this).apply {
            adapter = servicesAdapter
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 
                0, 
                1f // Weight 1 to fill space
            )
        }
        servicesList.setOnItemClickListener { _, _, position, _ ->
            val service = discoveredServices[position]
            val host = service.host
            val port = service.port
            if (host != null) {
                val url = "ws://${host.hostAddress}:$port/ws"
                connectToServer(url)
            }
        }
        
        layout.addView(backButton)
        layout.addView(title)
        layout.addView(scanButton)
        layout.addView(servicesList)
        
        clientStatusTextView = TextView(this).apply {
             text = "Status: Scanning..."
             setPadding(0, 20, 0, 0)
        }
        layout.addView(clientStatusTextView)
        
        return layout
    }

    private val syncHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val syncRunnable = object : Runnable {
        override fun run() {
            if (SonicSyncEngine.isNativeAvailable()) {
                SonicSyncEngine.sendSyncRequest()
            }
            syncHandler.postDelayed(this, 2000)
        }
    }

    private fun startSyncHeartbeat() {
        syncHandler.removeCallbacks(syncRunnable)
        syncHandler.post(syncRunnable)
    }

    private fun stopSyncHeartbeat() {
        syncHandler.removeCallbacks(syncRunnable)
    }

    private val driftCorrectionRunnable = object : Runnable {
        override fun run() {
            if (!SonicSyncEngine.isNativeAvailable()) {
                driftCorrectionHandler.postDelayed(this, 1000) // Check again later or disable? 
                // Better to just return and rely on isSyncedPlaying being false if native missing (safe logic elsewhere)
                // But better safe than sorry
                return 
            }

            if (isSyncedPlaying && player != null && player!!.isPlaying) {
                try {
                    val nowServerTime = SonicSyncEngine.getServerTime()
                    // Time elapsed since the scheduled "start time" (in server time)
                    // Note: startAtServerTime is when the track *starts* playing from startAtPositionMs
                    val elapsedSinceStartMicros = nowServerTime - currentSyncStartTime
                    val elapsedSinceStartMs = elapsedSinceStartMicros / 1000

                    val expectedPos = currentSyncStartPos + elapsedSinceStartMs
                    val actualPos = player!!.currentPosition 

                    // drift = actual - expected
                    // if actual < expected (behind), drift is negative.
                    // Rust PID expects negative drift to produce positive correction (speed > 1.0)
                    val driftMs = actualPos - expectedPos 

                    // drift > 0: player is ahead (need smaller speed)
                    // drift < 0: player is behind (need larger speed)

                    if (Math.abs(driftMs) > 200) {
                         // Hard resync
                         player!!.seekTo(expectedPos)
                         Log.w("SonicSync", "Hard sync: drift $driftMs ms")
                         statusText?.text = "Resyncing..."
                    } else if (Math.abs(driftMs) > 15) {
                         // Soft resync
                         // Speed up or slow down
                         // Using primitive logic or PID if we want strict sync
                         // Let's use the native PID calculator
                         val speed = SonicSyncEngine.calculateCorrection(driftMs, 0.1)
                         player!!.setPlaybackSpeed(speed.toFloat())
                         // Log.d("SonicSync", "Drift: $driftMs ms -> Speed: $speed")
                    } else {
                         if (player!!.playbackParameters.speed != 1.0f) {
                             player!!.setPlaybackSpeed(1.0f)
                         }
                    }
                } catch (e: Exception) {
                    Log.e("SonicSync", "Drift correction error", e)
                }
            }
            driftCorrectionHandler.postDelayed(this, 100)
        }
    }

    private fun startDriftCorrection() {
        stopDriftCorrection()
        driftCorrectionHandler.post(driftCorrectionRunnable)
    }

    private fun stopDriftCorrection() {
        driftCorrectionHandler.removeCallbacks(driftCorrectionRunnable)
        player?.setPlaybackSpeed(1.0f)
    }

    private fun connectToServer(wsUrl: String) {
        updateStatus("Status: Connecting to $wsUrl...")
        try {
            SonicSyncEngine.safeInitLogger()
            SonicSyncEngine.safeConnect(wsUrl, object : SonicSyncEngine.SyncCallback {
                override fun onPlayCommand(url: String, startAtServerTime: Long, startAtPositionMs: Long, currentServerOffset: Long) {
                    runOnUiThread {
                        if (url == "live") {
                            updateStatus("Status: Joining Live Stream... 🔴")
                            val streamUrl = wsUrl.replace("ws://", "http://").replace("/ws", "/stream/live")
                            prepareAudio(streamUrl)
                            player?.play()
                        } else {
                            val nowServerTime = SonicSyncEngine.getServerTime()
                            val waitTime = (startAtServerTime / 1000) - (nowServerTime / 1000) // Convert micros to millis
                            
                            updateStatus("Status: Scheduled play in ${waitTime}ms (Pos: ${startAtPositionMs}ms)")
                            
                            // Prepare player
                            if (player == null) {
                                player = ExoPlayer.Builder(this@MainActivity).build()
                            }
                            
                            // Load media if different
                            val mediaItem = MediaItem.fromUri(url)
                            player?.setMediaItem(mediaItem)
                            player?.prepare()
                            
                            // Store sync state
                            currentSyncTrackUrl = url
                            currentSyncStartTime = startAtServerTime
                            currentSyncStartPos = startAtPositionMs
                            isSyncedPlaying = true

                            if (waitTime > 0) {
                                player?.seekTo(startAtPositionMs)
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    player?.play()
                                    startDriftCorrection()
                                    updateStatus("Status: Playing (Synced) 🎶")
                                }, waitTime)
                            } else {
                                // Already passed start time, seek to current position
                                val elapsedSinceStart = (nowServerTime - startAtServerTime) / 1000
                                val targetPos = startAtPositionMs + elapsedSinceStart
                                
                                player?.seekTo(targetPos)
                                player?.play()
                                startDriftCorrection()
                                updateStatus("Status: Playing (Joined Late) 🎶")
                            }
                        }
                    }
                }
                
                override fun onPauseCommand(serverTime: Long) {
                    runOnUiThread {
                        player?.pause()
                        isSyncedPlaying = false
                        stopDriftCorrection()
                        updateStatus("Status: Paused (Synced)")
                    }
                }
            })
            if (SonicSyncEngine.isNativeAvailable()) {
                updateStatus("Status: Connected to $wsUrl")
                startSyncHeartbeat()
            }
        } catch (e: Exception) {
            updateStatus("Status: Connection failed - ${e.message}")
            Log.e("SonicSync", "Connection error", e)
        }
    }

    private fun updateStatus(status: String) {
        runOnUiThread {
            statusText?.text = status
            hostStatusTextView?.text = status
            clientStatusTextView?.text = status
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

    private fun prepareAudio(uriString: String) {
        if (uriString.trim().isEmpty()) return
        try {
            if (player == null) {
                player = ExoPlayer.Builder(this).build()
            }
            player?.stop()
            val mediaItem = MediaItem.fromUri(uriString)
            player?.setMediaItem(mediaItem)
            player?.prepare()
            Log.i("SonicSync", "Prepared: $uriString")
        } catch (e: Exception) {
            updateStatus("Status: Audio prep error - ${e.message}")
            Log.e("SonicSync", "Audio prep error", e)
        }
    }

    private fun startHostPlaybackDelayed(delayMs: Long, startPosMs: Long, startServerTimeMicros: Long) {
        // Set sync state
        currentSyncStartTime = startServerTimeMicros
        currentSyncStartPos = startPosMs
        isSyncedPlaying = true

        updateStatus("Status: Starting in ${delayMs}ms...")
        
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                if (player != null) {
                    player?.seekTo(startPosMs)
                    player?.play()
                    startDriftCorrection()
                    updateStatus("Status: Broadcasting & Playing 🎶")
                }
            } catch (e: Exception) {
                 Log.e("SonicSync", "Delayed start failed", e)
            }
        }, delayMs)
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is java.net.Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SonicSync", "Error getting IP", e)
        }
        return null
    }

    private fun getRealPathFromURI(contentUri: Uri): String? {
        // Simple attempt to get path for local files
        // Note: In modern Android this is tricky, we might need to copy to cache
        return try {
            val inputStream = contentResolver.openInputStream(contentUri)
            val file = java.io.File(cacheDir, "temp_broadcast.mp3")
            val outputStream = java.io.FileOutputStream(file)
            inputStream?.copyTo(outputStream)
            inputStream?.close()
            outputStream.close()
            file.absolutePath
        } catch (e: Exception) {
            Log.e("SonicSync", "Failed to cache file", e)
            null
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
