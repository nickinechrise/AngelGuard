package com.example.angelguard.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

import org.json.JSONArray
import org.json.JSONObject

import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min


class AngelGuardAIEngine(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit,
    private val onThreatDetected: () -> Unit
) {

    companion object {

        private const val TAG = "AngelGuardAIEngine"

        // =====================================================
        // GROQ
        // =====================================================

        private const val BASE_URL =
            "https://api.groq.com/openai/v1/chat/completions"

        private const val MODEL_NAME =
            "openai/gpt-oss-20b"

        private const val EMERGENCY_KEYWORD =
            "[EMERGENCY_TRIGGER]"

        /*
         * IMPORTANT:
         *
         * Do NOT commit a real API key into your APK.
         *
         * Put your new rotated key in a secure configuration
         * such as BuildConfig or your backend.
         */
        private const val GROQ_API_KEY =
            "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"

        // =====================================================
        // TIMINGS
        // =====================================================

        private const val LISTEN_DELAY = 500L

        private const val ERROR_RESTART_DELAY = 1200L

        private const val RECREATE_DELAY = 700L

        private const val CONVERSATION_TIMEOUT = 30000L

        private const val MAX_AI_TOKENS = 250

        /*
         * How close a word needs to be to "angelguard".
         *
         * 0.0 = completely different
         * 1.0 = exactly the same
         */
        private const val WAKE_SIMILARITY_THRESHOLD = 0.68

        /*
         * Minimum length before fuzzy matching is attempted.
         */
        private const val MIN_FUZZY_WORD_LENGTH = 4

        // =====================================================
        // SPEECH ERROR CODES
        // =====================================================

        private const val ERROR_NO_MATCH = 7

        private const val ERROR_SPEECH_TIMEOUT = 6
    }

    // =========================================================
    // SPEECH RECOGNIZER
    // =========================================================

    private var speechRecognizer: SpeechRecognizer? = null

    private lateinit var recognitionIntent: Intent

    private val isListening =
        AtomicBoolean(false)

    // =========================================================
    // TEXT TO SPEECH
    // =========================================================

    private var textToSpeech: TextToSpeech? = null

    @Volatile
    private var ttsReady = false

    @Volatile
    private var isSpeaking = false

    // =========================================================
    // CONVERSATION STATE
    // =========================================================

    @Volatile
    private var conversationActive = false

    @Volatile
    private var engineTerminated = false

    // =========================================================
    // EMERGENCY STATE
    // =========================================================

    private val emergencyTriggered =
        AtomicBoolean(false)

    // =========================================================
    // AI REQUEST STATE
    // =========================================================

    private val aiRequestRunning =
        AtomicBoolean(false)

    // =========================================================
    // HANDLER
    // =========================================================

    private val mainHandler =
        Handler(Looper.getMainLooper())

    @Volatile
    private var restartPending = false

    // =========================================================
    // AI SCOPE
    // =========================================================

    private val aiScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO
        )

    // =========================================================
    // HTTP CLIENT
    // =========================================================

    private val client =
        OkHttpClient.Builder()
            .connectTimeout(
                15,
                TimeUnit.SECONDS
            )
            .readTimeout(
                30,
                TimeUnit.SECONDS
            )
            .writeTimeout(
                15,
                TimeUnit.SECONDS
            )
            .build()

    // =========================================================
    // SYSTEM PROMPT
    // =========================================================

    private val systemPrompt = """
        You are AngelGuard, a personal AI safety companion.

        Your primary purpose is helping the user stay safe.

        Respond naturally and briefly because your responses
        will be spoken aloud.

        You can understand:
        - English
        - Tamil
        - Tanglish
        - Mixed English and Tamil

        Respond in the same language or style used by the user.

        Be calm, friendly, supportive and concise.

        IMPORTANT EMERGENCY RULE:

        If the user's CURRENT message clearly indicates
        immediate danger, active physical threat, assault,
        kidnapping, someone following them, someone threatening
        them, or an urgent request for rescue, your response
        MUST begin with:

        $EMERGENCY_KEYWORD

        Examples:

        I am in danger
        Help me
        Save me
        Someone is attacking me
        Someone is following me
        I am being attacked
        I am not safe
        Call for help
        Please save me
        Someone is threatening me
        He is attacking me
        They are trying to hurt me

        If there is no immediate danger,
        NEVER use $EMERGENCY_KEYWORD.

        Do not trigger emergency mode for:
        - jokes
        - normal conversation
        - ordinary sadness
        - casual questions
        - harmless statements
        - fictional stories
        - hypothetical questions

        Judge the user's CURRENT situation.

        Do not trigger an emergency merely because the
        conversation contains the word "danger".

        Trigger emergency only when the user's message
        communicates a credible immediate safety threat
        or an urgent request for help.

        Keep responses suitable for speech.

        Do not use markdown.

        Do not provide long explanations.

        IMPORTANT:
        If you decide an emergency is present,
        put $EMERGENCY_KEYWORD at the VERY BEGINNING
        of your response.
    """.trimIndent()

    // =========================================================
    // DIRECT EMERGENCY PHRASES
    // =========================================================

    private val emergencyPhrases = listOf(

        "help me",
        "save me",

        "i am in danger",
        "im in danger",
        "i'm in danger",

        "i am not safe",
        "im not safe",
        "i'm not safe",

        "someone is attacking me",
        "someone attacking me",

        "someone is following me",
        "someone following me",

        "someone is threatening me",
        "someone threatening me",

        "they are attacking me",
        "they're attacking me",

        "he is attacking me",
        "he's attacking me",

        "she is attacking me",
        "she's attacking me",

        "call for help",

        "please save me",
        "please help me",

        "i need help now",
        "i need help immediately",

        "i am being attacked",
        "im being attacked",
        "i'm being attacked",

        "i am being followed",
        "im being followed",
        "i'm being followed",

        "someone is trying to hurt me",
        "someone trying to hurt me",

        "they are trying to hurt me",
        "they're trying to hurt me",

        "someone wants to hurt me",

        "i am being kidnapped",
        "someone is kidnapping me",

        "they are kidnapping me",

        "i am trapped",
        "i'm trapped",

        "i am unsafe",
        "i'm unsafe"
    )

    // =========================================================
    // END CONVERSATION PHRASES
    // =========================================================

    private val endConversationPhrases = listOf(

        "goodbye angel",
        "goodbye angelguard",

        "bye angel",
        "bye angelguard",

        "stop listening",
        "stop listening angel",
        "stop listening angelguard",

        "go to sleep angel",
        "go to sleep angelguard",

        "you can stop listening",

        "that's all angel",
        "thats all angel",

        "thank you angel",
        "thanks angel",

        "thank you angelguard",
        "thanks angelguard"
    )

    // =========================================================
    // WAKE WORD PATTERNS
    // =========================================================

    /*
     * These are intentional variants because Android's
     * SpeechRecognizer may transcribe the same spoken phrase
     * differently.
     */
    private val wakeWordPatterns = listOf(

        // Exact/common
        "hey angelguard",
        "hey angel guard",
        "angelguard",
        "angel guard",

        // Common speech-recognition variations
        "hey angel god",
        "he angel god",

        "hey angel got",
        "he angel got",

        "hey angle guard",
        "hey angle god",
        "hey angle got",

        "he angle guard",
        "he angle god",
        "he angle got",

        "hey angell guard",
        "hey angel gard",

        "hey angel grad",
        "hey angel card",

        "hey angel guarded",
        "hey angel guard",

        // Shorter forms
        "hey angel",
        "he angel",
        "hey angle",
        "he angle",

        "hello angelguard",
        "hello angel guard",

        "hi angelguard",
        "hi angel guard"
    )

    // =========================================================
    // INITIALIZATION
    // =========================================================

    init {

        initializeEngine()

        initializeTextToSpeech()
    }

    // =========================================================
    // NORMALIZE SPEECH
    // =========================================================

    private fun normalizeSpeech(
        text: String
    ): String {

        return text
            .lowercase(Locale.ROOT)
            .replace(
                Regex("[^a-z0-9\\s]"),
                " "
            )
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim()
    }

    // =========================================================
    // REMOVE FILLER WORDS
    // =========================================================

    private fun removeFillerWords(
        text: String
    ): String {

        return text
            .replace(
                Regex("\\b(hey|he|hello|hi)\\b"),
                " "
            )
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim()
    }

    // =========================================================
    // LEVENSHTEIN DISTANCE
    // =========================================================

    private fun levenshteinDistance(
        first: String,
        second: String
    ): Int {

        if (first == second) {
            return 0
        }

        if (first.isEmpty()) {
            return second.length
        }

        if (second.isEmpty()) {
            return first.length
        }

        val previous =
            IntArray(second.length + 1) { it }

        val current =
            IntArray(second.length + 1)

        for (i in first.indices) {

            current[0] = i + 1

            for (j in second.indices) {

                val cost =
                    if (
                        first[i] ==
                        second[j]
                    ) {
                        0
                    } else {
                        1
                    }

                current[j + 1] =
                    min(
                        min(
                            current[j] + 1,
                            previous[j + 1] + 1
                        ),
                        previous[j] + cost
                    )
            }

            for (j in previous.indices) {
                previous[j] = current[j]
            }
        }

        return previous[second.length]
    }

    // =========================================================
    // SIMILARITY
    // =========================================================

    private fun similarity(
        first: String,
        second: String
    ): Double {

        if (first.isEmpty() || second.isEmpty()) {
            return 0.0
        }

        if (first == second) {
            return 1.0
        }

        val distance =
            levenshteinDistance(
                first,
                second
            )

        val maxLength =
            max(
                first.length,
                second.length
            )

        return 1.0 -
                distance.toDouble() /
                maxLength.toDouble()
    }

    // =========================================================
    // FUZZY ANGELGUARD MATCH
    // =========================================================

    private fun fuzzyAngelGuardMatch(
        text: String
    ): Boolean {

        val cleaned =
            removeFillerWords(
                normalizeSpeech(text)
            )

        if (cleaned.isBlank()) {
            return false
        }

        /*
         * First check the complete phrase.
         */
        for (pattern in wakeWordPatterns) {

            val normalizedPattern =
                normalizeSpeech(pattern)

            if (
                cleaned.contains(
                    normalizedPattern
                )
            ) {

                return true
            }
        }

        /*
         * Direct compact comparison.
         *
         * Example:
         *
         * angel guard
         * angelguard
         *
         * both become:
         *
         * angelguard
         */
        val compact =
            cleaned
                .replace(
                    " ",
                    ""
                )

        val target =
            "angelguard"

        if (
            compact.contains(
                target
            )
        ) {

            return true
        }

        /*
         * Fuzzy comparison against the entire compact
         * phrase.
         */
        if (
            compact.length >=
            MIN_FUZZY_WORD_LENGTH
        ) {

            val similarityScore =
                similarity(
                    compact,
                    target
                )

            if (
                similarityScore >=
                WAKE_SIMILARITY_THRESHOLD
            ) {

                Log.d(
                    TAG,
                    "Fuzzy wake match: '$text' score=$similarityScore"
                )

                return true
            }
        }

        /*
         * Compare individual words.
         *
         * This catches speech recognition such as:
         *
         * "he angel got"
         *
         * "hey angle guard"
         *
         * "angel gard"
         */
        val words =
            cleaned.split(
                Regex("\\s+")
            )

        for (word in words) {

            if (
                word.length >=
                MIN_FUZZY_WORD_LENGTH
            ) {

                val score =
                    similarity(
                        word,
                        target
                    )

                if (
                    score >=
                    WAKE_SIMILARITY_THRESHOLD
                ) {

                    Log.d(
                        TAG,
                        "Fuzzy individual wake match: '$word' score=$score"
                    )

                    return true
                }
            }
        }

        /*
         * Special two-word phonetic approximation.
         *
         * angel + guard
         * angel + god
         * angel + got
         * angle + guard
         */
        val hasAngelLikeWord =
            words.any { word ->

                similarity(
                    word,
                    "angel"
                ) >= 0.65 ||
                        similarity(
                            word,
                            "angle"
                        ) >= 0.65
            }

        val hasGuardLikeWord =
            words.any { word ->

                similarity(
                    word,
                    "guard"
                ) >= 0.55 ||
                        similarity(
                            word,
                            "god"
                        ) >= 0.60 ||
                        similarity(
                            word,
                            "got"
                        ) >= 0.60 ||
                        similarity(
                            word,
                            "gard"
                        ) >= 0.55 ||
                        similarity(
                            word,
                            "card"
                        ) >= 0.55
            }

        if (
            hasAngelLikeWord &&
            hasGuardLikeWord
        ) {

            Log.d(
                TAG,
                "Phonetic-style AngelGuard match: '$text'"
            )

            return true
        }

        /*
         * Finally accept "hey angel" / "he angel".
         *
         * This is intentionally lower priority because it
         * can create false activations.
         *
         * Only accept it when the phrase starts with
         * hey/he/hello/hi and contains angel/angle.
         */
        val startsWithWakePrefix =
            cleaned.startsWith("hey ") ||
                    cleaned.startsWith("he ") ||
                    cleaned.startsWith("hello ") ||
                    cleaned.startsWith("hi ")

        val containsAngel =
            cleaned.contains("angel") ||
                    cleaned.contains("angle")

        if (
            startsWithWakePrefix &&
            containsAngel
        ) {

            Log.d(
                TAG,
                "Short Angel wake match: '$text'"
            )

            return true
        }

        return false
    }

    // =========================================================
    // INITIALIZE SPEECH RECOGNIZER
    // =========================================================

    private fun initializeEngine() {

        if (engineTerminated) {
            return
        }

        if (
            !SpeechRecognizer
                .isRecognitionAvailable(context)
        ) {

            Log.e(
                TAG,
                "Speech recognition unavailable"
            )

            return
        }

        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }

        speechRecognizer = null

        try {

            speechRecognizer =
                SpeechRecognizer
                    .createSpeechRecognizer(
                        context
                    )

            speechRecognizer
                ?.setRecognitionListener(
                    createRecognitionListener()
                )

            recognitionIntent =
                Intent(
                    RecognizerIntent.ACTION_RECOGNIZE_SPEECH
                ).apply {

                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )

                    /*
                     * Prefer device default language.
                     */
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE,
                        Locale.getDefault()
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                        false
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_MAX_RESULTS,
                        5
                    )

                    /*
                     * Slightly longer listening window.
                     */
                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                        1800L
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                        1600L
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                        700L
                    )

                    /*
                     * Bias recognition toward spoken commands.
                     */
                    putExtra(
                        RecognizerIntent.EXTRA_CONFIDENCE_SCORES,
                        true
                    )
                }

            Log.d(
                TAG,
                "SpeechRecognizer initialized"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "SpeechRecognizer initialization failed",
                e
            )
        }
    }

    // =========================================================
    // RECOGNITION LISTENER
    // =========================================================

    private fun createRecognitionListener():
            RecognitionListener {

        return object :
            RecognitionListener {

            override fun onReadyForSpeech(
                params: Bundle?
            ) {

                Log.d(
                    TAG,
                    "Microphone ready"
                )
            }

            override fun onBeginningOfSpeech() {

                Log.d(
                    TAG,
                    "Speech started"
                )
            }

            override fun onRmsChanged(
                rmsdB: Float
            ) {
            }

            override fun onBufferReceived(
                buffer: ByteArray?
            ) {
            }

            override fun onEndOfSpeech() {

                Log.d(
                    TAG,
                    "Speech ended"
                )
            }

            override fun onError(
                error: Int
            ) {

                isListening.set(false)

                when (error) {

                    ERROR_NO_MATCH -> {

                        Log.d(
                            TAG,
                            "Speech recognition: no match"
                        )
                    }

                    ERROR_SPEECH_TIMEOUT -> {

                        Log.d(
                            TAG,
                            "Speech recognition: timeout"
                        )
                    }

                    else -> {

                        Log.e(
                            TAG,
                            "Speech recognition error: $error"
                        )
                    }
                }

                if (engineTerminated) {
                    return
                }

                scheduleListeningRestart()
            }

            override fun onResults(
                results: Bundle?
            ) {

                isListening.set(false)

                if (engineTerminated) {
                    return
                }

                val matches =
                    results?.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION
                    )

                if (
                    matches.isNullOrEmpty()
                ) {

                    scheduleListeningRestart()

                    return
                }

                /*
                 * Try all recognition alternatives rather
                 * than only matches.first().
                 *
                 * This is a major improvement for wake-word
                 * detection.
                 */
                var bestTranscript: String? = null

                for (candidate in matches) {

                    if (
                        candidate.isNotBlank()
                    ) {

                        Log.d(
                            TAG,
                            "Recognition candidate: $candidate"
                        )

                        if (
                            fuzzyAngelGuardMatch(
                                candidate
                            )
                        ) {

                            bestTranscript =
                                candidate

                            break
                        }

                        if (
                            bestTranscript == null
                        ) {

                            bestTranscript =
                                candidate
                        }
                    }
                }

                if (
                    !bestTranscript.isNullOrBlank()
                ) {

                    processTranscript(
                        bestTranscript
                    )

                } else {

                    scheduleListeningRestart()
                }
            }

            override fun onPartialResults(
                partialResults: Bundle?
            ) {
                /*
                 * Intentionally ignored.
                 *
                 * This prevents duplicate wake-word
                 * activation and duplicate AI requests.
                 */
            }

            override fun onEvent(
                eventType: Int,
                params: Bundle?
            ) {
            }
        }
    }

    // =========================================================
    // INITIALIZE TTS
    // =========================================================

    private fun initializeTextToSpeech() {

        if (engineTerminated) {
            return
        }

        textToSpeech =
            TextToSpeech(
                context
            ) { status ->

                mainHandler.post {

                    if (engineTerminated) {
                        return@post
                    }

                    if (
                        status ==
                        TextToSpeech.SUCCESS
                    ) {

                        val result =
                            textToSpeech?.setLanguage(
                                Locale.getDefault()
                            )

                        ttsReady =
                            result !=
                                    TextToSpeech.LANG_MISSING_DATA &&
                                    result !=
                                    TextToSpeech.LANG_NOT_SUPPORTED

                        textToSpeech?.setSpeechRate(
                            1.0f
                        )

                        textToSpeech?.setPitch(
                            1.0f
                        )

                        textToSpeech
                            ?.setOnUtteranceProgressListener(

                                object :
                                    UtteranceProgressListener() {

                                    override fun onStart(
                                        utteranceId: String?
                                    ) {

                                        isSpeaking = true

                                        Log.d(
                                            TAG,
                                            "TTS started"
                                        )
                                    }

                                    override fun onDone(
                                        utteranceId: String?
                                    ) {

                                        mainHandler.post {

                                            isSpeaking = false

                                            if (
                                                !engineTerminated &&
                                                conversationActive
                                            ) {

                                                mainHandler.postDelayed(
                                                    {

                                                        if (
                                                            !engineTerminated &&
                                                            conversationActive &&
                                                            !isListening.get() &&
                                                            !isSpeaking
                                                        ) {

                                                            startListening()
                                                        }

                                                    },
                                                    LISTEN_DELAY
                                                )

                                            } else if (
                                                !engineTerminated &&
                                                !conversationActive
                                            ) {

                                                mainHandler.postDelayed(
                                                    {

                                                        if (
                                                            !engineTerminated &&
                                                            !isListening.get() &&
                                                            !isSpeaking &&
                                                            !emergencyTriggered.get()
                                                        ) {

                                                            startListening()
                                                        }

                                                    },
                                                    LISTEN_DELAY
                                                )
                                            }
                                        }
                                    }

                                    override fun onError(
                                        utteranceId: String?
                                    ) {

                                        mainHandler.post {

                                            isSpeaking = false

                                            Log.e(
                                                TAG,
                                                "TTS error"
                                            )

                                            if (
                                                !engineTerminated
                                            ) {

                                                scheduleListeningRestart()
                                            }
                                        }
                                    }
                                }
                            )

                        Log.d(
                            TAG,
                            "TextToSpeech initialized"
                        )

                    } else {

                        ttsReady = false

                        Log.e(
                            TAG,
                            "TextToSpeech initialization failed"
                        )
                    }
                }
            }
    }

    // =========================================================
    // START LISTENING
    // =========================================================

    @Synchronized
    fun startListening() {

        if (engineTerminated) {
            return
        }

        if (isSpeaking) {

            Log.d(
                TAG,
                "Not listening while TTS is speaking"
            )

            return
        }

        if (isListening.get()) {

            return
        }

        if (
            !::recognitionIntent.isInitialized ||
            speechRecognizer == null
        ) {

            initializeEngine()
        }

        if (speechRecognizer == null) {

            Log.e(
                TAG,
                "SpeechRecognizer unavailable"
            )

            return
        }

        try {

            restartPending = false

            isListening.set(true)

            speechRecognizer?.startListening(
                recognitionIntent
            )

            if (conversationActive) {

                Log.d(
                    TAG,
                    "Conversation microphone started"
                )

            } else {

                Log.d(
                    TAG,
                    "Wake-word standby listening started"
                )
            }

        } catch (e: Exception) {

            isListening.set(false)

            Log.e(
                TAG,
                "Unable to start speech recognition",
                e
            )

            recreateSpeechRecognizer()

            scheduleListeningRestart()
        }
    }

    // =========================================================
    // RECREATE SPEECH RECOGNIZER
    // =========================================================

    private fun recreateSpeechRecognizer() {

        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {
        }

        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }

        speechRecognizer = null

        mainHandler.postDelayed(
            {

                if (!engineTerminated) {

                    initializeEngine()
                }

            },
            RECREATE_DELAY
        )
    }

    // =========================================================
    // START CONVERSATION
    // =========================================================

    fun startConversation() {

        mainHandler.post {

            if (engineTerminated) {
                return@post
            }

            if (emergencyTriggered.get()) {

                Log.w(
                    TAG,
                    "Conversation blocked because emergency is active"
                )

                return@post
            }

            if (conversationActive) {

                if (
                    !isListening.get() &&
                    !isSpeaking
                ) {

                    startListening()
                }

                return@post
            }

            conversationActive = true

            Log.d(
                TAG,
                "================================"
            )

            Log.d(
                TAG,
                "ANGELGUARD CONVERSATION STARTED"
            )

            Log.d(
                TAG,
                "================================"
            )

            try {

                onWakeWordDetected()

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Wake word callback failed",
                    e
                )
            }

            speak(
                "Yes, I'm listening."
            )

            scheduleConversationTimeout()
        }
    }

    // =========================================================
    // CONVERSATION TIMEOUT
    // =========================================================

    private fun scheduleConversationTimeout() {

        mainHandler.removeCallbacks(
            conversationTimeoutRunnable
        )

        mainHandler.postDelayed(
            conversationTimeoutRunnable,
            CONVERSATION_TIMEOUT
        )
    }

    private val conversationTimeoutRunnable =
        Runnable {

            if (
                !engineTerminated &&
                conversationActive &&
                !emergencyTriggered.get()
            ) {

                Log.d(
                    TAG,
                    "Conversation timeout reached"
                )

                conversationActive = false

                stopListeningInternal()

                speak(
                    "I'll wait here until you need me again."
                )
            }
        }

    // =========================================================
    // STOP CONVERSATION
    // =========================================================

    fun stopConversation() {

        mainHandler.post {

            conversationActive = false

            restartPending = false

            mainHandler.removeCallbacks(
                conversationTimeoutRunnable
            )

            Log.d(
                TAG,
                "ANGELGUARD CONVERSATION STOPPED"
            )

            stopListeningInternal()

            stopSpeaking()

            if (!engineTerminated) {

                mainHandler.postDelayed(

                    {

                        if (
                            !conversationActive &&
                            !isListening.get() &&
                            !isSpeaking &&
                            !emergencyTriggered.get()
                        ) {

                            startListening()
                        }

                    },

                    LISTEN_DELAY
                )
            }
        }
    }

    // =========================================================
    // SCHEDULE LISTENING RESTART
    // =========================================================

    @Synchronized
    private fun scheduleListeningRestart() {

        if (engineTerminated) {
            return
        }

        if (emergencyTriggered.get()) {
            return
        }

        if (restartPending) {
            return
        }

        if (isSpeaking) {
            return
        }

        restartPending = true

        mainHandler.postDelayed(

            {

                restartPending = false

                if (engineTerminated) {
                    return@postDelayed
                }

                if (emergencyTriggered.get()) {
                    return@postDelayed
                }

                if (isListening.get()) {
                    return@postDelayed
                }

                if (isSpeaking) {
                    return@postDelayed
                }

                startListening()

            },

            ERROR_RESTART_DELAY
        )
    }

    // =========================================================
    // PROCESS TRANSCRIPT
    // =========================================================

    private fun processTranscript(
        phrase: String
    ) {

        if (engineTerminated) {
            return
        }

        if (emergencyTriggered.get()) {
            return
        }

        val normalizedText =
            normalizeSpeech(
                phrase
            )

        if (normalizedText.isEmpty()) {

            scheduleListeningRestart()

            return
        }

        Log.d(
            TAG,
            "Recognized speech: $normalizedText"
        )

        // =====================================================
        // WAKE WORD CHECK
        // =====================================================

        val detectedWakeWord =
            fuzzyAngelGuardMatch(
                normalizedText
            )

        // =====================================================
        // STANDBY
        // =====================================================

        if (!conversationActive) {

            if (detectedWakeWord) {

                Log.d(
                    TAG,
                    "================================"
                )

                Log.d(
                    TAG,
                    "ANGELGUARD WAKE WORD DETECTED"
                )

                Log.d(
                    TAG,
                    "Original transcript: $phrase"
                )

                Log.d(
                    TAG,
                    "================================"
                )

                conversationActive = true

                try {

                    onWakeWordDetected()

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Wake callback failed",
                        e
                    )
                }

                val command =
                    removeWakeWord(
                        normalizedText
                    )

                if (command.isBlank()) {

                    speak(
                        "Yes, I'm listening."
                    )

                    scheduleConversationTimeout()

                    return
                }

                /*
                 * If the recognition result contained
                 * both the wake word and the command:
                 *
                 * "he angel got what is the weather"
                 *
                 * then immediately process:
                 *
                 * "what is the weather"
                 */
                scheduleConversationTimeout()

                processActiveConversation(
                    command
                )

                return
            }

            Log.d(
                TAG,
                "Speech ignored - waiting for AngelGuard wake word"
            )

            scheduleListeningRestart()

            return
        }

        // =====================================================
        // ACTIVE CONVERSATION
        // =====================================================

        scheduleConversationTimeout()

        processActiveConversation(
            normalizedText
        )
    }

    // =========================================================
    // REMOVE WAKE WORD
    // =========================================================

    private fun removeWakeWord(
        text: String
    ): String {

        var result =
            normalizeSpeech(text)

        /*
         * Remove common exact phrases.
         */
        val exactPatterns =
            listOf(
                "hey angelguard",
                "hey angel guard",

                "hey angel god",
                "he angel god",

                "hey angel got",
                "he angel got",

                "hey angle guard",
                "he angle guard",

                "hey angel",
                "he angel",

                "hey angle",
                "he angle",

                "hello angelguard",
                "hello angel guard",

                "hi angelguard",
                "hi angel guard",

                "angelguard",
                "angel guard"
            )

        for (pattern in exactPatterns) {

            if (
                result.startsWith(
                    pattern
                )
            ) {

                result =
                    result
                        .removePrefix(pattern)
                        .trim()

                return result
            }
        }

        /*
         * Handle speech recognition variants where
         * the wake phrase is slightly incorrect.
         *
         * Example:
         *
         * he angel got can you hear me
         *
         * becomes:
         *
         * can you hear me
         */
        val words =
            result.split(
                Regex("\\s+")
            ).toMutableList()

        if (words.size >= 2) {

            var removeCount = 0

            for (
            i in 0 until
                    min(
                        words.size,
                        4
                    )
            ) {

                val word =
                    words[i]

                if (
                    word == "hey" ||
                    word == "he" ||
                    word == "hello" ||
                    word == "hi"
                ) {

                    removeCount++
                    continue
                }

                if (
                    similarity(
                        word,
                        "angel"
                    ) >= 0.60 ||
                    similarity(
                        word,
                        "angle"
                    ) >= 0.60
                ) {

                    removeCount++
                    continue
                }

                if (
                    similarity(
                        word,
                        "guard"
                    ) >= 0.50 ||
                    similarity(
                        word,
                        "god"
                    ) >= 0.50 ||
                    similarity(
                        word,
                        "got"
                    ) >= 0.50 ||
                    similarity(
                        word,
                        "gard"
                    ) >= 0.50
                ) {

                    removeCount++
                    continue
                }

                break
            }

            if (
                removeCount > 0 &&
                removeCount < words.size
            ) {

                return words
                    .drop(removeCount)
                    .joinToString(" ")
                    .trim()
            }

            if (
                removeCount >= words.size
            ) {

                return ""
            }
        }

        return result
    }

    // =========================================================
    // ACTIVE CONVERSATION PROCESSING
    // =========================================================

    private fun processActiveConversation(
        normalizedText: String
    ) {

        if (
            engineTerminated ||
            !conversationActive ||
            emergencyTriggered.get()
        ) {

            return
        }

        if (normalizedText.isBlank()) {

            scheduleListeningRestart()

            return
        }

        // =====================================================
        // DIRECT EMERGENCY
        // =====================================================

        val directEmergency =
            emergencyPhrases.any { emergencyPhrase ->

                normalizedText.contains(
                    normalizeSpeech(
                        emergencyPhrase
                    )
                )
            }

        if (directEmergency) {

            Log.e(
                TAG,
                "DIRECT EMERGENCY PHRASE DETECTED"
            )

            triggerEmergency()

            return
        }

        // =====================================================
        // END CONVERSATION
        // =====================================================

        val wantsToStop =
            endConversationPhrases.any { phraseToStop ->

                normalizedText.contains(
                    normalizeSpeech(
                        phraseToStop
                    )
                )
            }

        if (wantsToStop) {

            Log.d(
                TAG,
                "Conversation termination detected"
            )

            conversationActive = false

            mainHandler.removeCallbacks(
                conversationTimeoutRunnable
            )

            speak(
                "Okay. I'm here whenever you need me."
            )

            return
        }

        // =====================================================
        // PREVENT DUPLICATE AI REQUESTS
        // =====================================================

        if (
            !aiRequestRunning.compareAndSet(
                false,
                true
            )
        ) {

            Log.w(
                TAG,
                "AI request already running"
            )

            return
        }

        // =====================================================
        // SEND TO AI
        // =====================================================

        aiScope.launch {

            try {

                chatWithAI(
                    normalizedText
                ) { aiReply, isEmergency ->

                    handleAIResponse(
                        aiReply,
                        isEmergency
                    )
                }

            } finally {

                aiRequestRunning.set(false)
            }
        }
    }

    // =========================================================
    // HANDLE AI RESPONSE
    // =========================================================

    private fun handleAIResponse(
        aiReply: String,
        isEmergency: Boolean
    ) {

        mainHandler.post {

            if (engineTerminated) {
                return@post
            }

            Log.d(
                TAG,
                "AI response: $aiReply"
            )

            Log.d(
                TAG,
                "AI emergency: $isEmergency"
            )

            if (isEmergency) {

                Log.e(
                    TAG,
                    "AI EMERGENCY TRIGGER ACTIVATED"
                )

                triggerEmergency()

                return@post
            }

            if (
                conversationActive &&
                aiReply.isNotBlank()
            ) {

                speak(
                    aiReply
                )

            } else {

                scheduleListeningRestart()
            }
        }
    }

    // =========================================================
    // TEXT TO SPEECH
    // =========================================================

    private fun speak(
        text: String
    ) {

        mainHandler.post {

            if (engineTerminated) {
                return@post
            }

            if (text.isBlank()) {

                scheduleListeningRestart()

                return@post
            }

            if (!ttsReady) {

                Log.e(
                    TAG,
                    "TTS is not ready"
                )

                scheduleListeningRestart()

                return@post
            }

            try {

                stopListeningInternal()

                isSpeaking = true

                val utteranceId =
                    "AngelGuard_${System.currentTimeMillis()}"

                Log.d(
                    TAG,
                    "AngelGuard speaking: $text"
                )

                textToSpeech?.speak(

                    text,

                    TextToSpeech.QUEUE_FLUSH,

                    null,

                    utteranceId
                )

            } catch (e: Exception) {

                isSpeaking = false

                Log.e(
                    TAG,
                    "TTS failed",
                    e
                )

                scheduleListeningRestart()
            }
        }
    }

    // =========================================================
    // STOP SPEAKING
    // =========================================================

    private fun stopSpeaking() {

        try {

            textToSpeech?.stop()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Unable to stop TTS",
                e
            )
        }

        isSpeaking = false
    }

    // =========================================================
    // STOP LISTENING
    // =========================================================

    private fun stopListeningInternal() {

        try {

            speechRecognizer?.cancel()

        } catch (_: Exception) {
        }

        isListening.set(false)

        restartPending = false
    }

    // =========================================================
    // EMERGENCY TRIGGER
    // =========================================================

    private fun triggerEmergency() {

        if (
            !emergencyTriggered.compareAndSet(
                false,
                true
            )
        ) {

            Log.w(
                TAG,
                "Emergency already triggered"
            )

            return
        }

        if (engineTerminated) {
            return
        }

        Log.e(
            TAG,
            "================================"
        )

        Log.e(
            TAG,
            "ANGELGUARD EMERGENCY TRIGGERED"
        )

        Log.e(
            TAG,
            "================================"
        )

        conversationActive = false

        mainHandler.removeCallbacks(
            conversationTimeoutRunnable
        )

        stopListeningInternal()

        stopSpeaking()

        mainHandler.post {

            try {

                onThreatDetected()

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Emergency callback failed",
                    e
                )
            }
        }
    }

    // =========================================================
    // GROQ AI
    // =========================================================

    suspend fun chatWithAI(
        userMessage: String,
        onResponse: (
            aiReply: String,
            isEmergency: Boolean
        ) -> Unit
    ) {

        withContext(Dispatchers.IO) {

            try {

                // =================================================
                // API KEY CHECK
                // =================================================

                if (
                    GROQ_API_KEY.isBlank() ||
                    GROQ_API_KEY ==
                    "YOUR_NEW_GROQ_API_KEY"
                ) {

                    Log.e(
                        TAG,
                        "Groq API key is not configured"
                    )

                    withContext(
                        Dispatchers.Main
                    ) {

                        onResponse(
                            "My AI connection is not configured yet.",
                            false
                        )
                    }

                    return@withContext
                }

                // =================================================
                // REQUEST JSON
                // =================================================

                val jsonBody =
                    JSONObject().apply {

                        put(
                            "model",
                            MODEL_NAME
                        )

                        put(
                            "messages",
                            buildConversationMessages(
                                userMessage
                            )
                        )

                        put(
                            "max_completion_tokens",
                            MAX_AI_TOKENS
                        )

                        put(
                            "temperature",
                            0.2
                        )

                        put(
                            "stream",
                            false
                        )
                    }

                val body =
                    jsonBody
                        .toString()
                        .toRequestBody(
                            "application/json; charset=utf-8"
                                .toMediaType()
                        )

                // =================================================
                // HTTP REQUEST
                // =================================================

                val request =
                    Request.Builder()
                        .url(BASE_URL)
                        .addHeader(
                            "Authorization",
                            "Bearer $GROQ_API_KEY"
                        )
                        .addHeader(
                            "Content-Type",
                            "application/json"
                        )
                        .post(body)
                        .build()

                // =================================================
                // API CALL
                // =================================================

                client
                    .newCall(request)
                    .execute()
                    .use { response ->

                        val responseData =
                            response.body?.string()

                        if (!response.isSuccessful) {

                            Log.e(
                                TAG,
                                "Groq HTTP error: ${response.code}"
                            )

                            Log.e(
                                TAG,
                                "Groq response: $responseData"
                            )

                            withContext(
                                Dispatchers.Main
                            ) {

                                onResponse(
                                    "I'm having trouble connecting right now. Please try again.",
                                    false
                                )
                            }

                            return@withContext
                        }

                        if (
                            responseData.isNullOrBlank()
                        ) {

                            withContext(
                                Dispatchers.Main
                            ) {

                                onResponse(
                                    "I couldn't understand the response.",
                                    false
                                )
                            }

                            return@withContext
                        }

                        // =================================================
                        // PARSE JSON
                        // =================================================

                        val jsonResponse =
                            try {

                                JSONObject(
                                    responseData
                                )

                            } catch (e: Exception) {

                                Log.e(
                                    TAG,
                                    "Invalid Groq JSON response",
                                    e
                                )

                                withContext(
                                    Dispatchers.Main
                                ) {

                                    onResponse(
                                        "I received an invalid AI response.",
                                        false
                                    )
                                }

                                return@withContext
                            }

                        val choices =
                            jsonResponse.optJSONArray(
                                "choices"
                            )

                        if (
                            choices == null ||
                            choices.length() == 0
                        ) {

                            withContext(
                                Dispatchers.Main
                            ) {

                                onResponse(
                                    "I couldn't generate a response.",
                                    false
                                )
                            }

                            return@withContext
                        }

                        val firstChoice =
                            choices.optJSONObject(0)

                        val message =
                            firstChoice?.optJSONObject(
                                "message"
                            )

                        val rawReply =
                            message
                                ?.optString(
                                    "content",
                                    ""
                                )
                                ?.trim()
                                .orEmpty()

                        if (rawReply.isEmpty()) {

                            withContext(
                                Dispatchers.Main
                            ) {

                                onResponse(
                                    "I couldn't generate a response.",
                                    false
                                )
                            }

                            return@withContext
                        }

                        // =================================================
                        // EMERGENCY DETECTION
                        // =================================================

                        val isEmergency =
                            rawReply.contains(
                                EMERGENCY_KEYWORD,
                                ignoreCase = true
                            )

                        // =================================================
                        // CLEAN RESPONSE
                        // =================================================

                        val cleanReply =
                            rawReply
                                .replace(
                                    EMERGENCY_KEYWORD,
                                    "",
                                    ignoreCase = true
                                )
                                .trim()

                        withContext(
                            Dispatchers.Main
                        ) {

                            onResponse(
                                cleanReply,
                                isEmergency
                            )
                        }
                    }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Groq connection failed",
                    e
                )

                withContext(
                    Dispatchers.Main
                ) {

                    onResponse(
                        "Connection failed, but I'm still here with you.",
                        false
                    )
                }
            }
        }
    }

    // =========================================================
    // BUILD AI MESSAGES
    // =========================================================

    private fun buildConversationMessages(
        currentUserMessage: String
    ): JSONArray {

        val messages =
            JSONArray()

        messages.put(

            JSONObject().apply {

                put(
                    "role",
                    "system"
                )

                put(
                    "content",
                    systemPrompt
                )
            }
        )

        messages.put(

            JSONObject().apply {

                put(
                    "role",
                    "user"
                )

                put(
                    "content",
                    currentUserMessage
                )
            }
        )

        return messages
    }

    // =========================================================
    // PUBLIC STATUS
    // =========================================================

    fun isConversationActive():
            Boolean {

        return conversationActive
    }

    fun isCurrentlyListening():
            Boolean {

        return isListening.get()
    }

    fun isCurrentlySpeaking():
            Boolean {

        return isSpeaking
    }

    fun isEmergencyTriggered():
            Boolean {

        return emergencyTriggered.get()
    }

    // =========================================================
    // RESET EMERGENCY STATE
    // =========================================================

    fun resetEmergencyState() {

        if (engineTerminated) {
            return
        }

        emergencyTriggered.set(false)

        conversationActive = false

        aiRequestRunning.set(false)

        Log.d(
            TAG,
            "Emergency state reset"
        )

        scheduleListeningRestart()
    }

    // =========================================================
    // TERMINATE ENGINE
    // =========================================================

    fun terminateEngine() {

        if (engineTerminated) {
            return
        }

        try {

            Log.d(
                TAG,
                "Terminating AngelGuard AI Engine"
            )

            engineTerminated = true

            conversationActive = false

            restartPending = false

            isListening.set(false)

            aiRequestRunning.set(false)

            // -------------------------------------------------
            // HANDLER
            // -------------------------------------------------

            mainHandler.removeCallbacksAndMessages(
                null
            )

            // -------------------------------------------------
            // SPEECH RECOGNIZER
            // -------------------------------------------------

            try {
                speechRecognizer?.stopListening()
            } catch (_: Exception) {
            }

            try {
                speechRecognizer?.cancel()
            } catch (_: Exception) {
            }

            try {
                speechRecognizer?.destroy()
            } catch (_: Exception) {
            }

            speechRecognizer = null

            // -------------------------------------------------
            // TTS
            // -------------------------------------------------

            try {

                textToSpeech?.stop()

                textToSpeech?.shutdown()

            } catch (_: Exception) {
            }

            textToSpeech = null

            ttsReady = false

            isSpeaking = false

            // -------------------------------------------------
            // COROUTINES
            // -------------------------------------------------

            try {

                aiScope.cancel()

            } catch (_: Exception) {
            }

            // -------------------------------------------------
            // HTTP
            // -------------------------------------------------

            try {

                client.dispatcher
                    .executorService
                    .shutdown()

                client.connectionPool
                    .evictAll()

            } catch (_: Exception) {
            }

            Log.d(
                TAG,
                "AngelGuard AI Engine terminated"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Error terminating AI engine",
                e
            )
        }
    }
}