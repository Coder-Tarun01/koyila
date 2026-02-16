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
                // Local file - provide path to Rust server and broadcast our own IP
                val filePath = getRealPathFromURI(selectedFileUri!!)
                if (filePath != null) {
                    updateStatus("Status: Hosting local file and broadcasting...")
                    SonicSyncEngine.hostFile(filePath)
                    val streamUrl = "http://$hostIp:$port/stream"
                    SonicSyncEngine.safeBroadcastPlay(streamUrl, 3000)
                    // Also play locally
                    playAudio(streamUrl)
                } else {
                    Toast.makeText(this, "Could not resolve file path", Toast.LENGTH_SHORT).show()
                }
            } else if (audioUrl.isNotEmpty()) {
                 updateStatus("Status: Broadcasting to all clients...")
                 SonicSyncEngine.safeBroadcastPlay(audioUrl, 3000)
                 playAudio(audioUrl)
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

        layout.addView(backButton)
        layout.addView(title)
        layout.addView(startHostButton)
        layout.addView(liveStreamButton)
        layout.addView(android.widget.TextView(this).apply { text = "\nOR PLAY FILE:\n" })
        layout.addView(audioLabel)
        layout.addView(audioInput)
        layout.addView(browseButton)
        layout.addView(broadcastButton)
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

    private fun connectToServer(wsUrl: String) {
        updateStatus("Status: Connecting to $wsUrl...")
        try {
            SonicSyncEngine.safeInitLogger()
            SonicSyncEngine.safeConnect(wsUrl, object : SonicSyncEngine.SyncCallback {
                override fun onPlayCommand(url: String, startAtServerTime: Long, currentServerOffset: Long) {
                    runOnUiThread {
                        if (url == "live") {
                            updateStatus("Status: Joining Live Stream... 🔴")
                            val streamUrl = wsUrl.replace("ws://", "http://").replace("/ws", "/stream/live")
                            playAudio(streamUrl)
                        } else {
                            val nowServerTime = SonicSyncEngine.getServerTime()
                            val waitTime = (startAtServerTime / 1000) - (nowServerTime / 1000) // Convert micros to millis
                            
                            updateStatus("Status: Scheduled play in ${waitTime}ms")
                            
                            // Prepare player
                            if (player == null) {
                                player = ExoPlayer.Builder(this@MainActivity).build()
                            }
                            player?.stop()
                            val mediaItem = MediaItem.fromUri(url)
                            player?.setMediaItem(mediaItem)
                            player?.prepare()

                            if (waitTime > 0) {
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    player?.play()
                                    updateStatus("Status: Playing (Synced) 🎶")
                                }, waitTime)
                            } else {
                                // Already passed start time, seek to current position
                                val seekPos = -waitTime // waitTime is negative
                                player?.seekTo(seekPos)
                                player?.play()
                                updateStatus("Status: Playing (Joined Late) 🎶")
                            }
                        }
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

    private fun playAudio(uriString: String) {
        if (uriString.trim().isEmpty()) {
            Log.e("SonicSync", "playAudio called with empty URI")
            return
        }
        try {
            if (player == null) {
                player = ExoPlayer.Builder(this).build()
            }
            player?.stop()
            val mediaItem = MediaItem.fromUri(uriString)
            player?.setMediaItem(mediaItem)
            player?.prepare()
            player?.play()
            updateStatus("Status: Playing audio... 🎶")
            Log.i("SonicSync", "Playing: $uriString")
        } catch (e: Exception) {
            updateStatus("Status: Playback error - ${e.message}")
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

    private fun updateStatus(status: String) {
        runOnUiThread {
            statusText?.text = status
            hostStatusTextView?.text = status
            clientStatusTextView?.text = status
        }
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
