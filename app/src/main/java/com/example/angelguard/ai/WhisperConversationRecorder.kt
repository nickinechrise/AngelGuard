package com.example.angelguard.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.abs

class WhisperConversationRecorder(
    private val context: Context,
    private val groqApiKey: String,
    private val onTranscription: (String) -> Unit,
    private val onRecordingStarted: () -> Unit = {},
    private val onRecordingStopped: () -> Unit = {},
    private val onError: (String) -> Unit = {}
) {

    companion object {
        private const val TAG = "WhisperRecorder"

        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL_CONFIG =
            AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT =
            AudioFormat.ENCODING_PCM_16BIT

        private const val MAX_RECORDING_MS = 12_000L
        private const val MIN_RECORDING_MS = 700L

        /*
         * Silence detection.
         *
         * Increase this if speech is being cut too early.
         * Decrease this if the system waits too long.
         */
        private const val SILENCE_TIMEOUT_MS = 1_300L

        /*
         * RMS threshold.
         *
         * Lower = more sensitive to quiet speech.
         */
        private const val SILENCE_RMS_THRESHOLD = 850

        private const val WHISPER_URL =
            "https://api.groq.com/openai/v1/audio/transcriptions"

        private const val WHISPER_MODEL =
            "whisper-large-v3"
    }

    private val mainHandler =
        Handler(Looper.getMainLooper())

    private val executor =
        Executors.newSingleThreadExecutor()

    @Volatile
    private var recording = false

    private var audioRecord: AudioRecord? = null

    private var recordingThread: Thread? = null

    fun isRecording(): Boolean {
        return recording
    }

    fun startRecording() {

        if (recording) {
            Log.d(TAG, "Recording already active")
            return
        }

        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            onError("Microphone permission is not granted")
            return
        }

        if (groqApiKey.isBlank()) {
            onError("Groq API key is missing")
            return
        }

        val minBufferSize =
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            )

        if (
            minBufferSize ==
            AudioRecord.ERROR ||
            minBufferSize ==
            AudioRecord.ERROR_BAD_VALUE
        ) {
            onError("Unable to determine microphone buffer size")
            return
        }

        val bufferSize =
            maxOf(
                minBufferSize * 2,
                4096
            )

        try {

            audioRecord =
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )

            if (
                audioRecord?.state !=
                AudioRecord.STATE_INITIALIZED
            ) {
                audioRecord?.release()
                audioRecord = null

                onError(
                    "AudioRecord could not be initialized"
                )

                return
            }

            recording = true

            audioRecord?.startRecording()

            Log.d(
                TAG,
                "Whisper microphone recording started"
            )

            mainHandler.post {
                onRecordingStarted()
            }

            recordingThread =
                Thread {

                    recordUntilSilence(
                        bufferSize
                    )

                }.apply {

                    name =
                        "AngelGuardWhisperRecorder"

                    start()
                }

        } catch (e: Exception) {

            recording = false

            try {
                audioRecord?.release()
            } catch (_: Exception) {
            }

            audioRecord = null

            Log.e(
                TAG,
                "Unable to start recording",
                e
            )

            onError(
                "Unable to start microphone recording"
            )
        }
    }

    fun stopRecording() {

        if (!recording) {
            return
        }

        Log.d(
            TAG,
            "Stopping Whisper recording"
        )

        recording = false

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
    }

    private fun recordUntilSilence(
        bufferSize: Int
    ) {

        val pcmOutput =
            ByteArrayOutputStream()

        val buffer =
            ByteArray(bufferSize)

        var totalBytes = 0

        val startedAt =
            System.currentTimeMillis()

        var lastSpeechAt =
            startedAt

        var detectedSpeech = false

        try {

            while (recording) {

                val bytesRead =
                    audioRecord?.read(
                        buffer,
                        0,
                        buffer.size
                    ) ?: -1

                if (bytesRead <= 0) {
                    continue
                }

                pcmOutput.write(
                    buffer,
                    0,
                    bytesRead
                )

                totalBytes += bytesRead

                val rms =
                    calculateRms(
                        buffer,
                        bytesRead
                    )

                val now =
                    System.currentTimeMillis()

                if (
                    rms >=
                    SILENCE_RMS_THRESHOLD
                ) {

                    detectedSpeech = true

                    lastSpeechAt = now
                }

                val elapsed =
                    now - startedAt

                if (
                    detectedSpeech &&
                    elapsed >= MIN_RECORDING_MS &&
                    now - lastSpeechAt >=
                    SILENCE_TIMEOUT_MS
                ) {

                    Log.d(
                        TAG,
                        "Silence detected, stopping recording"
                    )

                    break
                }

                if (
                    elapsed >= MAX_RECORDING_MS
                ) {

                    Log.d(
                        TAG,
                        "Maximum recording time reached"
                    )

                    break
                }
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Recording loop failed",
                e
            )

            mainHandler.post {
                onError(
                    "Microphone recording failed"
                )
            }

        } finally {

            recording = false

            try {
                audioRecord?.stop()
            } catch (_: Exception) {
            }

            try {
                audioRecord?.release()
            } catch (_: Exception) {
            }

            audioRecord = null

            recordingThread = null

            val pcmData =
                pcmOutput.toByteArray()

            mainHandler.post {
                onRecordingStopped()
            }

            if (pcmData.isEmpty()) {

                mainHandler.post {
                    onError(
                        "No speech audio was captured"
                    )
                }

                return
            }

            if (!detectedSpeech) {

                mainHandler.post {
                    onError(
                        "No clear speech detected"
                    )
                }

                return
            }

            executor.execute {

                try {

                    val wavFile =
                        createWavFile(
                            pcmData
                        )

                    transcribeWithWhisper(
                        wavFile
                    )

                    try {
                        wavFile.delete()
                    } catch (_: Exception) {
                    }

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Whisper processing failed",
                        e
                    )

                    mainHandler.post {
                        onError(
                            "Speech transcription failed"
                        )
                    }
                }
            }
        }
    }

    private fun calculateRms(
        buffer: ByteArray,
        length: Int
    ): Int {

        if (length < 2) {
            return 0
        }

        var sum = 0.0
        var samples = 0

        var i = 0

        while (i + 1 < length) {

            val low =
                buffer[i]
                    .toInt() and 0xFF

            val high =
                buffer[i + 1]
                    .toInt()

            val sample =
                (high shl 8) or low

            sum +=
                abs(sample)

            samples++

            i += 2
        }

        if (samples == 0) {
            return 0
        }

        return (
                sum / samples
                ).toInt()
    }

    private fun createWavFile(
        pcmData: ByteArray
    ): File {

        val file =
            File(
                context.cacheDir,
                "angelguard_${System.currentTimeMillis()}.wav"
            )

        val channels = 1
        val bitsPerSample = 16
        val byteRate =
            SAMPLE_RATE *
                    channels *
                    bitsPerSample /
                    8

        val blockAlign =
            channels *
                    bitsPerSample /
                    8

        DataOutputStream(
            file.outputStream()
        ).use { out ->

            out.writeBytes("RIFF")

            out.writeIntLE(
                36 + pcmData.size
            )

            out.writeBytes("WAVE")

            out.writeBytes("fmt ")

            out.writeIntLE(16)

            out.writeShortLE(1)

            out.writeShortLE(
                channels
            )

            out.writeIntLE(
                SAMPLE_RATE
            )

            out.writeIntLE(
                byteRate
            )

            out.writeShortLE(
                blockAlign
            )

            out.writeShortLE(
                bitsPerSample
            )

            out.writeBytes("data")

            out.writeIntLE(
                pcmData.size
            )

            out.write(
                pcmData
            )
        }

        return file
    }

    private fun transcribeWithWhisper(
        wavFile: File
    ) {

        Log.d(
            TAG,
            "Sending audio to Groq Whisper"
        )

        val boundary =
            "----AngelGuardBoundary${System.currentTimeMillis()}"

        val url =
            URL(WHISPER_URL)

        val connection =
            url.openConnection()
                    as HttpURLConnection

        try {

            connection.requestMethod =
                "POST"

            connection.connectTimeout =
                20_000

            connection.readTimeout =
                60_000

            connection.doOutput = true

            connection.setRequestProperty(
                "Authorization",
                "Bearer $groqApiKey"
            )

            connection.setRequestProperty(
                "Content-Type",
                "multipart/form-data; boundary=$boundary"
            )

            DataOutputStream(
                connection.outputStream
            ).use { out ->

                writeMultipartText(
                    out,
                    boundary,
                    "model",
                    WHISPER_MODEL
                )

                /*
                 * This prompt helps Whisper keep
                 * the conversational style.
                 */
                writeMultipartText(
                    out,
                    boundary,
                    "prompt",
                    "Tamil speech may be spoken in Tanglish using English letters. " +
                            "Preserve the spoken words and names as accurately as possible. " +
                            "The user may also speak English, Tamil, Telugu, Malayalam, Hindi, or Kannada."
                )

                writeMultipartText(
                    out,
                    boundary,
                    "temperature",
                    "0"
                )

                writeMultipartText(
                    out,
                    boundary,
                    "response_format",
                    "json"
                )

                out.writeBytes(
                    "--$boundary\r\n"
                )

                out.writeBytes(
                    "Content-Disposition: form-data; " +
                            "name=\"file\"; " +
                            "filename=\"${wavFile.name}\"\r\n"
                )

                out.writeBytes(
                    "Content-Type: audio/wav\r\n\r\n"
                )

                FileInputStream(
                    wavFile
                ).use { input ->

                    val buffer =
                        ByteArray(8192)

                    while (true) {

                        val read =
                            input.read(
                                buffer
                            )

                        if (read == -1) {
                            break
                        }

                        out.write(
                            buffer,
                            0,
                            read
                        )
                    }
                }

                out.writeBytes("\r\n")

                out.writeBytes(
                    "--$boundary--\r\n"
                )

                out.flush()
            }

            val responseCode =
                connection.responseCode

            val responseText =
                try {

                    connection.inputStream
                        .bufferedReader()
                        .use {
                            it.readText()
                        }

                } catch (_: Exception) {

                    connection.errorStream
                        ?.bufferedReader()
                        ?.use {
                            it.readText()
                        }
                        .orEmpty()
                }

            Log.d(
                TAG,
                "Whisper HTTP response: $responseCode"
            )

            Log.d(
                TAG,
                "Whisper response: $responseText"
            )

            if (
                responseCode !in
                200..299
            ) {

                mainHandler.post {
                    onError(
                        "Whisper API error: $responseCode"
                    )
                }

                return
            }

            val json =
                JSONObject(
                    responseText
                )

            val transcript =
                json.optString(
                    "text",
                    ""
                ).trim()

            if (transcript.isBlank()) {

                mainHandler.post {
                    onError(
                        "Whisper returned an empty transcript"
                    )
                }

                return
            }

            Log.d(
                TAG,
                "Whisper transcript: $transcript"
            )

            mainHandler.post {
                onTranscription(
                    transcript
                )
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Whisper HTTP request failed",
                e
            )

            mainHandler.post {
                onError(
                    "Unable to reach Whisper transcription service"
                )
            }

        } finally {

            connection.disconnect()
        }
    }

    private fun writeMultipartText(
        out: DataOutputStream,
        boundary: String,
        name: String,
        value: String
    ) {

        out.writeBytes(
            "--$boundary\r\n"
        )

        out.writeBytes(
            "Content-Disposition: form-data; name=\"$name\"\r\n\r\n"
        )

        out.writeBytes(
            value
        )

        out.writeBytes(
            "\r\n"
        )
    }

    fun shutdown() {

        stopRecording()

        executor.shutdownNow()
    }

    private fun DataOutputStream.writeIntLE(
        value: Int
    ) {

        write(
            byteArrayOf(
                (value and 0xFF).toByte(),
                ((value shr 8) and 0xFF).toByte(),
                ((value shr 16) and 0xFF).toByte(),
                ((value shr 24) and 0xFF).toByte()
            )
        )
    }

    private fun DataOutputStream.writeShortLE(
        value: Int
    ) {

        write(
            byteArrayOf(
                (value and 0xFF).toByte(),
                ((value shr 8) and 0xFF).toByte()
            )
        )
    }
}