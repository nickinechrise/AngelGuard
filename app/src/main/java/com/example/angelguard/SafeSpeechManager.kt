package com.example.angelguard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

class SafeSpeechManager(private val context: Context, private val onCommandDetected: (String) -> Unit) {
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var retryCount = 0
    private val handler = Handler(Looper.getMainLooper())
    private val maxRetries = 3

    init {
        initRecognizer()
    }

    private fun initRecognizer() {
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(SafeRecognitionListener())
            }
        }
    }

    fun startListening() {
        if (isListening) return

        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            speechRecognizer?.startListening(intent)
            isListening = true
            retryCount = 0 // Reset counter on success
        } catch (e: Exception) {
            isListening = false
            Log.e("SafeSpeechManager", "Failed to start listening", e)
            handleErrorRecovery()
        }
    }

    fun stopListening() {
        if (!isListening) return

        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.e("SafeSpeechManager", "Failed to stop listening", e)
        } finally {
            isListening = false
        }
    }

    private fun handleErrorRecovery() {
        if (retryCount >= maxRetries) {
            Log.w("SafeSpeechManager", "Max speech recognizer retries reached. Stopping attempts.")
            return
        }

        retryCount++
        // Exponential backoff delay (2s, 4s, 8s) to let the binder buffer flush completely
        val backoffDelay = (1000 * (1 shl retryCount)).toLong()

        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            initRecognizer()
            startListening()
        }, backoffDelay)
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            speechRecognizer = null
            isListening = false
        }
    }

    private inner class SafeRecognitionListener : RecognitionListener {
        override fun onError(error: Int) {
            isListening = false
            Log.e("SafeSpeechManager", "Speech recognition error code: $error")

            // Handle recoverable errors using backoff instead of instant loops
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                error == SpeechRecognizer.ERROR_CLIENT ||
                error == SpeechRecognizer.ERROR_NO_MATCH) {
                handleErrorRecovery()
            }
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                onCommandDetected(matches[0])
            }
            startListening()
        }

        override fun onEndOfSpeech() {
            isListening = false
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}