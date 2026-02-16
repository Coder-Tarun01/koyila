package com.sonicsync.app

import android.util.Log
import androidx.annotation.Keep

@Keep
object SonicSyncEngine {

    private var nativeLoaded = false

    init {
        try {
            System.loadLibrary("sonicsync_bridge")
            nativeLoaded = true
            Log.i("SonicSync", "Native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            nativeLoaded = false
            Log.w("SonicSync", "Native library not available: ${e.message}. Running in audio-only mode.")
        }
    }

    fun isNativeAvailable(): Boolean = nativeLoaded

    interface SyncCallback {
        fun onPlayCommand(url: String, startAtServerTime: Long, currentServerOffset: Long)
    }

    private var callback: SyncCallback? = null

    fun safeInitLogger() {
        if (nativeLoaded) {
            try { initLogger() } catch (e: Exception) {
                Log.e("SonicSync", "initLogger failed", e)
            }
        }
    }

    fun safeConnect(url: String, cb: SyncCallback) {
        if (nativeLoaded) {
            try { connect(url, cb) } catch (e: Exception) {
                Log.e("SonicSync", "connect failed", e)
            }
        } else {
            Log.w("SonicSync", "Cannot connect: native library not loaded")
        }
    }

    fun safeRequestPlay(url: String, delayMs: Long = 2000) {
        if (nativeLoaded) {
            try { requestPlay(url, delayMs) } catch (e: Exception) {
                Log.e("SonicSync", "requestPlay failed", e)
            }
        }
    }

    fun safeBroadcastPlay(url: String, delayMs: Long = 2000) {
        if (nativeLoaded) {
            try { broadcastPlay(url, delayMs) } catch (e: Exception) {
                Log.e("SonicSync", "broadcastPlay failed", e)
            }
        } else {
            Log.w("SonicSync", "Cannot broadcast: native library not loaded")
        }
    }

    // JNI Native Methods
    @JvmStatic
    private external fun initLogger()
    @JvmStatic
    private external fun connect(url: String, callback: SyncCallback)
    @JvmStatic
    private external fun requestPlay(url: String, delayMs: Long)
    @JvmStatic
    private external fun broadcastPlay(url: String, delayMs: Long)
    @JvmStatic
    external fun sendSyncRequest()
    @JvmStatic
    external fun getOffset(): Long
    @JvmStatic
    external fun getServerTime(): Long
    @JvmStatic
    external fun calculateCorrection(driftMs: Long, dtSeconds: Double): Double

    // Host Mode Methods
    @JvmStatic
    external fun startServer(port: Int)
    @JvmStatic
    external fun stopServer()
    @JvmStatic
    external fun hostFile(path: String)
    @JvmStatic
    external fun getLocalIpAddress(): String
    
    // Host Controls
    @JvmStatic
    external fun sendPlay()
    @JvmStatic
    external fun sendPause()
    @JvmStatic
    external fun sendSeek(positionMs: Long)

    // Live Streaming
    @JvmStatic
    external fun startLiveStream()
    @JvmStatic
    external fun stopLiveStream()
    @JvmStatic
    external fun sendAudioChunk(data: ByteArray)
    @JvmStatic
    external fun isLiveStreaming(): Boolean
}
