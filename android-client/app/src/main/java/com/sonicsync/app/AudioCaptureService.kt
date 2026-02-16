package com.sonicsync.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.IOException
import java.nio.ByteBuffer

class AudioCaptureService : Service() {

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var mediaCodec: MediaCodec? = null
    private var isRecording = false
    private var captureThread: Thread? = null

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        const val NOTIFICATION_ID = 42
        const val CHANNEL_ID = "AudioCaptureChannel"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (resultCode != 0 && resultData != null) {
                    startForegroundService(resultCode, resultData)
                }
            }
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Audio Capture Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun startForegroundService(resultCode: Int, resultData: Intent) {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SonicSync Live")
            .setContentText("Streaming internal audio...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        startCapture(resultCode, resultData)
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
        if (mediaProjection == null) {
            Log.e("AudioCaptureService", "MediaProjection is null")
            stopSelf()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(44100)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            try {
                audioRecord = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(8192) // Adjustable
                    .build()

                if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                    startEncoding()
                } else {
                    Log.e("AudioCaptureService", "AudioRecord init failed")
                }
            } catch (e: Exception) {
                Log.e("AudioCaptureService", "Error starting capture", e)
            }
        }
    }

    private fun startEncoding() {
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mediaCodec?.start()

            isRecording = true
            audioRecord?.startRecording()

            captureThread = Thread {
                captureLoop()
            }
            captureThread?.start()

            // Notify Rust that live stream started
            SonicSyncEngine.startLiveStream()

        } catch (e: IOException) {
            Log.e("AudioCaptureService", "MediaCodec creation failed", e)
        }
    }

    private fun captureLoop() {
        val buffer = ByteArray(4096)
        val bufferInfo = MediaCodec.BufferInfo()

        while (isRecording && !Thread.interrupted()) {
            val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
            if (read > 0) {
                // Send to Encoder
                val inputBufferIndex = mediaCodec?.dequeueInputBuffer(5000) ?: -1
                if (inputBufferIndex >= 0) {
                    val inputBuffer = mediaCodec?.getInputBuffer(inputBufferIndex)
                    inputBuffer?.clear()
                    inputBuffer?.put(buffer, 0, read)
                    mediaCodec?.queueInputBuffer(inputBufferIndex, 0, read, System.nanoTime() / 1000, 0)
                }

                // Get from Encoder
                var outputBufferIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, 0) ?: -1
                while (outputBufferIndex >= 0) {
                    val outputBuffer = mediaCodec?.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        // Adjust position and limit
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        
                        // Add ADTS header?
                        // For raw stream, we might need ADTS. Let's add it.
                        // Actually, let's try raw AAC first, but standard players usually need ADTS.
                        // I'll implement a simple ADTS header generator.
                        
                        val packetSize = bufferInfo.size + 7
                        val adtsHeader = ByteArray(7)
                        addADTStoPacket(adtsHeader, packetSize)
                        
                        val adtsData = ByteArray(packetSize)
                        System.arraycopy(adtsHeader, 0, adtsData, 0, 7)
                        outputBuffer.get(adtsData, 7, bufferInfo.size)

                        // Send to Rust
                        SonicSyncEngine.sendAudioChunk(adtsData)
                    }
                    mediaCodec?.releaseOutputBuffer(outputBufferIndex, false)
                    outputBufferIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, 0) ?: -1
                }
            }
        }
    }

    private fun addADTStoPacket(packet: ByteArray, packetLen: Int) {
        val profile = 2 // AAC LC
        val freqIdx = 4 // 44.1KHz
        val chanCfg = 2 // CPE (Stereo)

        packet[0] = 0xFF.toByte()
        packet[1] = 0xF9.toByte()
        packet[2] = (((profile - 1) shl 6) + (freqIdx shl 2) + (chanCfg shr 2)).toByte()
        packet[3] = (((chanCfg and 3) shl 6) + (packetLen shr 11)).toByte()
        packet[4] = ((packetLen and 0x7FF) shr 3).toByte()
        packet[5] = (((packetLen and 7) shl 5) + 0x1F).toByte()
        packet[6] = 0xFC.toByte()
    }

    private fun stopCapture() {
        isRecording = false
        try {
            captureThread?.join(1000)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }

        try {
            audioRecord?.stop()
            audioRecord?.release()
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        SonicSyncEngine.stopLiveStream()
        
        audioRecord = null
        mediaCodec = null
        mediaProjection?.stop()
        mediaProjection = null
    }
}
