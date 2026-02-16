package com.sonicsync.app

import android.util.Log

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

    // JNI Native Methods
    private external fun initLogger()
    private external fun connect(url: String, callback: SyncCallback)
    private external fun requestPlay(url: String, delayMs: Long)
    external fun sendSyncRequest()
    external fun getOffset(): Long
    external fun getServerTime(): Long
    external fun calculateCorrection(driftMs: Long, dtSeconds: Double): Double
}
