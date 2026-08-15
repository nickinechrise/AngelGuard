package com.example.angelguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class AudioRecordingService : Service() {

    private val CHANNEL_ID = "AngelGuardForegroundChannel"

    private var isRecording = false
    private var recordingThread: Thread? = null

    // Audio configuration parameters
    private val sampleRate = 16000 // Standard for speech recognition
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private var audioRecord: AudioRecord? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AngelGuard Active")
            .setContentText("Listening for your command...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()

        startForeground(1, notification)

        // Start the background audio listening loop
        startAudioListening()

        return START_STICKY
    }

    private fun startAudioListening() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        isRecording = true
        audioRecord?.startRecording()

        recordingThread = Thread {
            val data = ByteArray(bufferSize)
            while (isRecording) {
                val readSize = audioRecord?.read(data, 0, data.size) ?: 0
                if (readSize > 0) {
                    // TODO: Pass this 'data' chunk into your Wake-Word engine
                    // (like Porcupine) or local processing model to check for "Hey AngelGuard"
                    val wakeWordDetected = false // Change this to true when your keyword engine detects the match

                    if (wakeWordDetected) {
                        val intent = Intent(this, MainActivity::class.java).apply {
                            action = "ACTION_START_SPEECH_RECOGNITION"
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        startActivity(intent)
                    }
                }
            }
        }
        recordingThread?.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        recordingThread = null
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "AngelGuard Background Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }
}