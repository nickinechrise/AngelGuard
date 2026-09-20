package com.example.angelguard.ai

import android.content.Context
import android.content.Intent
import android.os.Build
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

import org.json.JSONArray
import org.json.JSONObject

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
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
         * Put your real rotated key here or, preferably,
         * load it from BuildConfig / secure backend.
         */
        private const val GROQ_API_KEY =
            "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"

        // =====================================================
        // MULTILINGUAL SPEECH
        // =====================================================

        private const val DEFAULT_SPEECH_LANGUAGE = "en-IN"
        private const val TAMIL_LANGUAGE = "ta-IN"
        private const val TELUGU_LANGUAGE = "te-IN"
        private const val MALAYALAM_LANGUAGE = "ml-IN"
        private const val HINDI_LANGUAGE = "hi-IN"
        private const val KANNADA_LANGUAGE = "kn-IN"

        // =====================================================
        // TIMINGS
        // =====================================================

        private const val LISTEN_DELAY = 500L
        private const val ERROR_RESTART_DELAY = 1200L
        private const val RECREATE_DELAY = 700L
        private const val CONVERSATION_TIMEOUT = 30000L

        private const val MAX_AI_TOKENS = 1024

        // =====================================================
        // WAKE WORD
        // =====================================================

        private const val WAKE_SIMILARITY_THRESHOLD = 0.68
        private const val MIN_FUZZY_WORD_LENGTH = 4

        // =====================================================
        // SPEECH ERROR CODES
        // =====================================================

        private const val ERROR_NO_MATCH = 7
        private const val ERROR_SPEECH_TIMEOUT = 6
    }

    // =========================================================
    // WAKE-WORD SPEECH RECOGNIZER
    // =========================================================

    /*
     * IMPORTANT:
     *
     * SpeechRecognizer is now primarily used for:
     *
     *     "Hey AngelGuard"
     *
     * It is NOT the main active-conversation STT anymore.
     *
     * Active conversation uses WhisperConversationRecorder.
     */
    private var speechRecognizer: SpeechRecognizer? = null

    private lateinit var recognitionIntent: Intent

    private val isListening =
        AtomicBoolean(false)

    // =========================================================
    // WHISPER CONVERSATION RECORDER
    // =========================================================

    private var whisperRecorder:
            WhisperConversationRecorder? = null

    // =========================================================
    // TEXT TO SPEECH
    // =========================================================

    private var textToSpeech: TextToSpeech? = null

    @Volatile
    private var ttsReady = false

    @Volatile
    private var detectedSpeechLanguage =
        DEFAULT_SPEECH_LANGUAGE

    /*
     * Explicit user-selected language.
     *
     * Example:
     *
     * "Talk to me in Tamil"
     *
     * Then AngelGuard continues replying in Tamil until
     * the user explicitly changes the language.
     */
    @Volatile
    private var preferredResponseLanguage: String? = null

    private val supportedConversationLanguages =
        arrayListOf(
            "en-IN",
            "ta-IN",
            "te-IN",
            "ml-IN",
            "hi-IN",
            "kn-IN"
        )

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
    // WAKE WORD FAILURE FEEDBACK
    // =========================================================

    private var wakeWordFailureCount = 0

    private val wakeWordFailurePromptThreshold = 2

    // =========================================================
    // AI SCOPE
    // =========================================================

    private val aiScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO
        )

    // =========================================================
    // HTTP SETTINGS
    // =========================================================

    private val connectTimeoutMs = 15_000
    private val readTimeoutMs = 30_000

    // =========================================================
    // SYSTEM PROMPT
    // =========================================================

    private val systemPrompt = """
        You are AngelGuard, a personal AI safety companion.

        Your primary purpose is helping the user stay safe.

        Respond naturally, briefly and conversationally because
        your responses will be spoken aloud.

        You can understand and respond in:

        - English
        - Tamil
        - Tanglish
        - Telugu
        - Malayalam
        - Hindi
        - Kannada
        - Mixed-language speech

        =====================================================
        TANGlish RULE
        =====================================================

        Tanglish means Tamil spoken naturally but written using
        English letters.

        Examples:

        "Enakku bayama irukku"
        "Enna yaarum follow panraanga"
        "Avan kathiya eduthuttu varaan"

        Treat this as Tamil conversational speech.

        DO NOT interpret Tanglish literally as English.

        Understand the intended Tamil meaning.

        If the user is speaking Tanglish, reply naturally in
        Tanglish unless the user explicitly asks for Tamil script.

        =====================================================
        LANGUAGE RULE
        =====================================================

        Always reply in the language/style appropriate for the
        CURRENT conversation.

        Tamil script:
        Reply in Tamil script.

        Tanglish:
        Reply in Tanglish.

        Telugu:
        Reply in Telugu.

        Malayalam:
        Reply in Malayalam.

        Hindi:
        Reply in Hindi.

        Kannada:
        Reply in Kannada.

        English:
        Reply in English.

        Mixed language:
        Naturally follow the user's dominant language/style.

        Do not switch to English merely because the wake word
        was spoken in English.

        Do not translate the user's message to English unless
        the user asks for translation.

        =====================================================
        EMERGENCY RULE
        =====================================================

        If the user's CURRENT message clearly indicates:

        - immediate danger
        - active physical threat
        - assault
        - kidnapping
        - someone following them
        - someone threatening them
        - someone trying to hurt them
        - urgent request for rescue

        your response MUST begin with:

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

        This emergency rule also applies when the user expresses
        the same meaning in Tamil, Tanglish, Telugu, Malayalam,
        Hindi, Kannada or mixed language.

        Do NOT trigger an emergency for:

        - jokes
        - normal conversation
        - ordinary sadness
        - casual questions
        - harmless statements
        - fictional stories
        - hypothetical questions

        Judge the user's CURRENT situation.

        Do not trigger an emergency merely because the word
        "danger" appears.

        Trigger emergency only when the user's message communicates
        a credible immediate safety threat or an urgent request
        for help.

        Keep responses suitable for speech.

        Do not use markdown.

        Do not provide long explanations.

        If an emergency is present, place
        $EMERGENCY_KEYWORD at the VERY BEGINNING.
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

    private val wakeWordPatterns = listOf(

        "hey angelguard",
        "hey angel guard",
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

        "hello angelguard",
        "hello angel guard",

        "angelguard",
        "angel guard",

        "hi angelguard",
        "angel card","Angel girl","angel",
        "hi angel guard"
    )

    // =========================================================
    // INITIALIZATION
    // =========================================================

    init {

        initializeEngine()

        initializeTextToSpeech()

        initializeWhisperRecorder()
    }

    // =========================================================
    // INITIALIZE WHISPER RECORDER
    // =========================================================

    private fun initializeWhisperRecorder() {

        if (engineTerminated) {
            return
        }

        whisperRecorder =
            WhisperConversationRecorder(

                context = context,

                groqApiKey = GROQ_API_KEY,

                // ---------------------------------------------
                // WHISPER TRANSCRIPTION RECEIVED
                // ---------------------------------------------

                onTranscription = { transcript ->

                    mainHandler.post {

                        if (
                            engineTerminated ||
                            !conversationActive ||
                            emergencyTriggered.get()
                        ) {
                            return@post
                        }

                        val cleanTranscript =
                            normalizeSpeech(
                                transcript
                            )

                        if (cleanTranscript.isBlank()) {

                            scheduleConversationRestart()

                            return@post
                        }

                        /*
                         * Determine language from the actual
                         * Whisper transcript.
                         */
                        updateDetectedLanguageFromUserText(
                            cleanTranscript
                        )

                        Log.d(
                            TAG,
                            "Whisper transcript: $cleanTranscript"
                        )

                        scheduleConversationTimeout()

                        processActiveConversation(
                            cleanTranscript
                        )
                    }
                },

                // ---------------------------------------------
                // RECORDING STARTED
                // ---------------------------------------------

                onRecordingStarted = {

                    Log.d(
                        TAG,
                        "Whisper active conversation recording started"
                    )
                },

                // ---------------------------------------------
                // RECORDING STOPPED
                // ---------------------------------------------

                onRecordingStopped = {

                    Log.d(
                        TAG,
                        "Whisper active conversation recording stopped"
                    )
                },

                // ---------------------------------------------
                // WHISPER ERROR
                // ---------------------------------------------

                onError = { message ->

                    Log.e(
                        TAG,
                        "Whisper recorder error: $message"
                    )

                    mainHandler.post {

                        if (
                            !engineTerminated &&
                            conversationActive &&
                            !emergencyTriggered.get() &&
                            !isSpeaking
                        ) {

                            mainHandler.postDelayed(
                                {

                                    if (
                                        !engineTerminated &&
                                        conversationActive &&
                                        !isSpeaking &&
                                        !emergencyTriggered.get()
                                    ) {

                                        startWhisperConversationRecording()
                                    }

                                },
                                LISTEN_DELAY
                            )
                        }
                    }
                }
            )
    }

    // =========================================================
    // NORMALIZE CONVERSATION TEXT
    // =========================================================

    private fun normalizeSpeech(
        text: String
    ): String {

        /*
         * IMPORTANT:
         *
         * Preserve Unicode.
         *
         * This keeps:
         *
         * Tamil
         * Telugu
         * Malayalam
         * Hindi
         * Kannada
         *
         * intact.
         *
         * It also preserves Tanglish because Tanglish is Latin text.
         */
        return text
            .lowercase(Locale.ROOT)
            .replace(
                Regex("[^\\p{L}\\p{N}\\s]"),
                " "
            )
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim()
    }

    // =========================================================
    // NORMALIZE WAKE WORD
    // =========================================================

    private fun normalizeWakeWordSpeech(
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
    // REMOVE FILLERS
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
    // LEVENSHTEIN
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

            current[0] =
                i + 1

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

        if (
            first.isEmpty() ||
            second.isEmpty()
        ) {
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
    // WAKE WORD MATCH
    // =========================================================

    private fun fuzzyAngelGuardMatch(
        text: String
    ): Boolean {

        val cleaned =
            normalizeWakeWordSpeech(
                text
            )

        if (cleaned.isBlank()) {
            return false
        }

        /*
         * Exact/common speech-recognition variants.
         */
        for (pattern in wakeWordPatterns) {

            if (
                cleaned.contains(
                    pattern
                )
            ) {

                Log.d(
                    TAG,
                    "Known wake variation detected: '$text'"
                )

                return true
            }
        }

        val words =
            cleaned.split(
                Regex("\\s+")
            )

        if (words.size < 2) {
            return false
        }

        /*
         * IMPORTANT:
         *
         * Require a wake prefix.
         *
         * This prevents ordinary speech containing
         * only "angel" or "angelguard" from activating.
         */
        val hasWakePrefix =
            words.take(2).any { word ->

                word == "hey" ||
                        word == "he" ||
                        word == "hello" ||
                        word == "hi"
            }

        if (!hasWakePrefix) {
            return false
        }

        val angelIndex =
            words.indexOfFirst { word ->

                similarity(
                    word,
                    "angel"
                ) >= 0.60 ||

                        similarity(
                            word,
                            "angle"
                        ) >= 0.60 ||

                        similarity(
                            word,
                            "angell"
                        ) >= 0.60
            }

        if (angelIndex < 0) {
            return false
        }

        val guardIndex =
            words.indexOfFirst { word ->

                similarity(
                    word,
                    "guard"
                ) >= 0.55 ||

                        similarity(
                            word,
                            "gard"
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
                            "card"
                        ) >= 0.55 ||

                        similarity(
                            word,
                            "guarded"
                        ) >= 0.55
            }

        if (
            guardIndex >= 0 &&
            guardIndex >= angelIndex &&
            guardIndex - angelIndex <= 2
        ) {

            Log.d(
                TAG,
                "Fuzzy AngelGuard wake match: '$text'"
            )

            return true
        }

        /*
         * Handle combined tokens such as:
         *
         * heyangelguard
         * heyangelgard
         */
        val compact =
            cleaned.replace(
                " ",
                ""
            )

        val compactWithoutPrefix =
            when {

                compact.startsWith("hey") ->
                    compact.removePrefix("hey")

                compact.startsWith("hello") ->
                    compact.removePrefix("hello")

                compact.startsWith("hi") ->
                    compact.removePrefix("hi")

                compact.startsWith("he") ->
                    compact.removePrefix("he")

                else ->
                    ""
            }

        if (
            compactWithoutPrefix.isNotEmpty()
        ) {

            val score =
                similarity(
                    compactWithoutPrefix,
                    "angelguard"
                )

            if (
                score >=
                WAKE_SIMILARITY_THRESHOLD
            ) {

                Log.d(
                    TAG,
                    "Compact fuzzy wake match: '$text' score=$score"
                )

                return true
            }
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
                     * Wake-word stage starts with English.
                     */
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE,
                        DEFAULT_SPEECH_LANGUAGE
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                        false
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_MAX_RESULTS,
                        5
                    )

                    if (
                        Build.VERSION.SDK_INT >= 31
                    ) {

                        putExtra(
                            RecognizerIntent.EXTRA_BIASING_STRINGS,
                            arrayListOf(
                                "Hey AngelGuard",
                                "AngelGuard",
                                "Angel Guard"
                            )
                        )
                    }

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

                    putExtra(
                        RecognizerIntent.EXTRA_CONFIDENCE_SCORES,
                        true
                    )
                }

            Log.d(
                TAG,
                "SpeechRecognizer initialized for wake-word detection"
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
                    "Wake-word microphone ready"
                )
            }

            override fun onBeginningOfSpeech() {

                Log.d(
                    TAG,
                    "Wake-word speech started"
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
                    "Wake-word speech ended"
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
                            "Wake recognition: no match"
                        )
                    }

                    ERROR_SPEECH_TIMEOUT -> {

                        Log.d(
                            TAG,
                            "Wake recognition: timeout"
                        )
                    }

                    else -> {

                        Log.e(
                            TAG,
                            "Wake recognition error: $error"
                        )
                    }
                }

                if (engineTerminated) {
                    return
                }

                if (!conversationActive) {

                    handleWakeWordNotDetected()

                } else {

                    /*
                     * Active conversation should normally NOT
                     * reach here because Whisper is now handling
                     * the microphone.
                     */
                    startWhisperConversationRecording()
                }
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

                    if (!conversationActive) {
                        handleWakeWordNotDetected()
                    } else {
                        startWhisperConversationRecording()
                    }

                    return
                }

                var bestTranscript: String? = null

                for (candidate in matches) {

                    if (
                        candidate.isNotBlank()
                    ) {

                        Log.d(
                            TAG,
                            "Wake recognition candidate: $candidate"
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

                    handleWakeWordNotDetected()
                }
            }

            override fun onPartialResults(
                partialResults: Bundle?
            ) {
                /*
                 * Ignore partial results.
                 *
                 * This prevents duplicate activation.
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
                                Locale.forLanguageTag(
                                    DEFAULT_SPEECH_LANGUAGE
                                )
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
                                                engineTerminated
                                            ) {
                                                return@post
                                            }

                                            if (
                                                conversationActive
                                            ) {

                                                /*
                                                 * IMPORTANT:
                                                 *
                                                 * Active conversation no longer
                                                 * returns to Android SpeechRecognizer.
                                                 *
                                                 * It goes to Whisper instead.
                                                 */
                                                mainHandler.postDelayed(
                                                    {

                                                        if (
                                                            !engineTerminated &&
                                                            conversationActive &&
                                                            !isSpeaking &&
                                                            !emergencyTriggered.get()
                                                        ) {

                                                            startWhisperConversationRecording()
                                                        }

                                                    },
                                                    LISTEN_DELAY
                                                )

                                            } else {

                                                /*
                                                 * Standby mode returns to
                                                 * the wake-word recognizer.
                                                 */
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
                                                !engineTerminated &&
                                                !emergencyTriggered.get()
                                            ) {

                                                scheduleConversationRestart()
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
    // UPDATE TTS LANGUAGE
    // =========================================================

    private fun updateTtsLanguage(
        languageTag: String
    ) {

        if (
            !ttsReady ||
            engineTerminated
        ) {
            return
        }

        try {

            val normalized =
                languageTag.lowercase(
                    Locale.ROOT
                )

            val locale =
                when {

                    normalized.startsWith("ta") ->
                        Locale.forLanguageTag(
                            TAMIL_LANGUAGE
                        )

                    normalized.startsWith("te") ->
                        Locale.forLanguageTag(
                            TELUGU_LANGUAGE
                        )

                    normalized.startsWith("ml") ->
                        Locale.forLanguageTag(
                            MALAYALAM_LANGUAGE
                        )

                    normalized.startsWith("hi") ->
                        Locale.forLanguageTag(
                            HINDI_LANGUAGE
                        )

                    normalized.startsWith("kn") ->
                        Locale.forLanguageTag(
                            KANNADA_LANGUAGE
                        )

                    else ->
                        Locale.forLanguageTag(
                            DEFAULT_SPEECH_LANGUAGE
                        )
                }

            val result =
                textToSpeech?.setLanguage(
                    locale
                )

            val supported =
                result !=
                        TextToSpeech.LANG_MISSING_DATA &&
                        result !=
                        TextToSpeech.LANG_NOT_SUPPORTED

            if (supported) {

                Log.d(
                    TAG,
                    "TTS language changed to ${locale.toLanguageTag()}"
                )

            } else {

                Log.w(
                    TAG,
                    "TTS language not supported: ${locale.toLanguageTag()}"
                )
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to update TTS language",
                e
            )
        }
    }

    // =========================================================
    // INFER TTS LANGUAGE FROM TEXT
    // =========================================================

    private fun inferTtsLanguageFromText(
        text: String
    ): String {

        return when {

            Regex(
                "[\\u0B80-\\u0BFF]"
            ).containsMatchIn(text) ->
                TAMIL_LANGUAGE

            Regex(
                "[\\u0C00-\\u0C7F]"
            ).containsMatchIn(text) ->
                TELUGU_LANGUAGE

            Regex(
                "[\\u0D00-\\u0D7F]"
            ).containsMatchIn(text) ->
                MALAYALAM_LANGUAGE

            Regex(
                "[\\u0900-\\u097F]"
            ).containsMatchIn(text) ->
                HINDI_LANGUAGE

            Regex(
                "[\\u0C80-\\u0CFF]"
            ).containsMatchIn(text) ->
                KANNADA_LANGUAGE

            else ->
                preferredResponseLanguage
                    ?: detectedSpeechLanguage
        }
    }

    // =========================================================
    // START WAKE-WORD LISTENING
    // =========================================================

    @Synchronized
    fun startListening() {

        if (engineTerminated) {
            return
        }

        /*
         * If conversation is active, do NOT start the Android
         * SpeechRecognizer. Active speech is handled by Whisper.
         */
        if (conversationActive) {

            if (
                !isSpeaking &&
                !emergencyTriggered.get()
            ) {

                startWhisperConversationRecording()
            }

            return
        }

        if (isSpeaking) {

            Log.d(
                TAG,
                "Not starting wake listener while TTS is speaking"
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

            /*
             * Wake-word mode is always English.
             */
            recognitionIntent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                DEFAULT_SPEECH_LANGUAGE
            )

            isListening.set(true)

            speechRecognizer?.startListening(
                recognitionIntent
            )

            Log.d(
                TAG,
                "Wake-word standby listening started"
            )

        } catch (e: Exception) {

            isListening.set(false)

            Log.e(
                TAG,
                "Unable to start wake-word recognition",
                e
            )

            recreateSpeechRecognizer()

            scheduleListeningRestart()
        }
    }

    // =========================================================
    // START WHISPER CONVERSATION
    // =========================================================

    private fun startWhisperConversationRecording() {

        if (
            engineTerminated ||
            !conversationActive ||
            isSpeaking ||
            emergencyTriggered.get()
        ) {
            return
        }

        if (
            whisperRecorder?.isRecording() == true
        ) {
            return
        }

        /*
         * Make sure the old wake-word recognizer is not holding
         * the microphone.
         */
        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {
        }

        isListening.set(false)

        restartPending = false

        Log.d(
            TAG,
            "Starting Whisper multilingual conversation capture"
        )

        whisperRecorder?.startRecording()
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

                if (
                    !engineTerminated &&
                    !conversationActive
                ) {

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
                    !isSpeaking &&
                    whisperRecorder?.isRecording() != true
                ) {

                    startWhisperConversationRecording()
                }

                return@post
            }

            conversationActive = true
            wakeWordFailureCount = 0

            try {

                onWakeWordDetected()

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Wake word callback failed",
                    e
                )
            }

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

                try {
                    whisperRecorder?.stopRecording()
                } catch (_: Exception) {
                }

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

            try {
                whisperRecorder?.stopRecording()
            } catch (_: Exception) {
            }

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
    // SCHEDULE CONVERSATION RESTART
    // =========================================================

    @Synchronized
    private fun scheduleConversationRestart() {

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

                if (isSpeaking) {
                    return@postDelayed
                }

                if (conversationActive) {

                    startWhisperConversationRecording()

                } else {

                    if (
                        !isListening.get()
                    ) {

                        startListening()
                    }
                }

            },
            ERROR_RESTART_DELAY
        )
    }

    // =========================================================
    // BACKWARD-COMPATIBLE RESTART
    // =========================================================

    private fun scheduleListeningRestart() {

        scheduleConversationRestart()
    }

    // =========================================================
    // WAKE WORD FAILURE
    // =========================================================

    private fun handleWakeWordNotDetected() {

        if (
            engineTerminated ||
            conversationActive ||
            emergencyTriggered.get()
        ) {
            return
        }

        wakeWordFailureCount++

        Log.d(
            TAG,
            "Wake-word detection failed: $wakeWordFailureCount"
        )

        if (
            wakeWordFailureCount >=
            wakeWordFailurePromptThreshold
        ) {

            wakeWordFailureCount = 0

            speak(
                "I could not detect the exact wake word. Please say Hey AngelGuard clearly."
            )

        } else {

            scheduleListeningRestart()
        }
    }

    // =========================================================
    // PROCESS SPEECH TRANSCRIPT
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

        /*
         * This function is primarily for the wake-word recognizer.
         */
        val normalizedText =
            normalizeSpeech(
                phrase
            )

        if (normalizedText.isBlank()) {

            handleWakeWordNotDetected()

            return
        }

        Log.d(
            TAG,
            "Wake transcript: $normalizedText"
        )

        val detectedWakeWord =
            fuzzyAngelGuardMatch(
                phrase
            )

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
                wakeWordFailureCount = 0

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
                 * Example:
                 *
                 * "hey angel god can you hear me"
                 *
                 * We still process the command immediately.
                 */
                scheduleConversationTimeout()

                processActiveConversation(
                    command
                )

            } else {

                Log.d(
                    TAG,
                    "Speech ignored - waiting for exact AngelGuard wake word"
                )

                handleWakeWordNotDetected()
            }

            return
        }

        /*
         * This branch should rarely happen because Whisper handles
         * active conversation.
         */
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
                "hi angel guard"
            )

        for (pattern in exactPatterns) {

            if (
                result.startsWith(
                    pattern
                )
            ) {

                result =
                    result
                        .removePrefix(
                            pattern
                        )
                        .trim()

                return result
            }
        }

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
    // LANGUAGE DETECTION
    // =========================================================

    private fun updateDetectedLanguageFromUserText(
        text: String
    ) {

        val detected =
            when {

                Regex(
                    "[\\u0B80-\\u0BFF]"
                ).containsMatchIn(text) ->
                    TAMIL_LANGUAGE

                Regex(
                    "[\\u0C00-\\u0C7F]"
                ).containsMatchIn(text) ->
                    TELUGU_LANGUAGE

                Regex(
                    "[\\u0D00-\\u0D7F]"
                ).containsMatchIn(text) ->
                    MALAYALAM_LANGUAGE

                Regex(
                    "[\\u0900-\\u097F]"
                ).containsMatchIn(text) ->
                    HINDI_LANGUAGE

                Regex(
                    "[\\u0C80-\\u0CFF]"
                ).containsMatchIn(text) ->
                    KANNADA_LANGUAGE

                else ->
                    null
            }

        if (detected != null) {

            detectedSpeechLanguage =
                detected

            Log.d(
                TAG,
                "User language inferred from native script: $detected"
            )

            updateTtsLanguage(
                detected
            )
        }
    }

    // =========================================================
    // EXPLICIT LANGUAGE REQUEST
    // =========================================================

    private fun detectRequestedResponseLanguage(
        text: String
    ): String? {

        val normalized =
            normalizeSpeech(
                text
            )

        if (normalized.isBlank()) {
            return null
        }

        // -----------------------------------------------------
        // TAMIL
        // -----------------------------------------------------

        if (
            normalized.contains(
                "talk to me in tamil"
            ) ||
            normalized.contains(
                "speak to me in tamil"
            ) ||
            normalized.contains(
                "speak tamil"
            ) ||
            normalized.contains(
                "talk tamil"
            ) ||
            normalized.contains(
                "respond in tamil"
            ) ||
            normalized.contains(
                "reply in tamil"
            ) ||
            normalized.contains(
                "answer in tamil"
            ) ||
            normalized.contains(
                "tamil la pesu"
            ) ||
            normalized.contains(
                "tamil la pesunga"
            ) ||
            normalized.contains(
                "tamil la pesuviya"
            )
        ) {
            return TAMIL_LANGUAGE
        }

        // -----------------------------------------------------
        // TELUGU
        // -----------------------------------------------------

        if (
            normalized.contains(
                "talk to me in telugu"
            ) ||
            normalized.contains(
                "speak to me in telugu"
            ) ||
            normalized.contains(
                "speak telugu"
            ) ||
            normalized.contains(
                "respond in telugu"
            ) ||
            normalized.contains(
                "reply in telugu"
            ) ||
            normalized.contains(
                "answer in telugu"
            )
        ) {
            return TELUGU_LANGUAGE
        }

        // -----------------------------------------------------
        // MALAYALAM
        // -----------------------------------------------------

        if (
            normalized.contains(
                "talk to me in malayalam"
            ) ||
            normalized.contains(
                "speak to me in malayalam"
            ) ||
            normalized.contains(
                "speak malayalam"
            ) ||
            normalized.contains(
                "respond in malayalam"
            ) ||
            normalized.contains(
                "reply in malayalam"
            ) ||
            normalized.contains(
                "answer in malayalam"
            )
        ) {
            return MALAYALAM_LANGUAGE
        }

        // -----------------------------------------------------
        // HINDI
        // -----------------------------------------------------

        if (
            normalized.contains(
                "talk to me in hindi"
            ) ||
            normalized.contains(
                "speak to me in hindi"
            ) ||
            normalized.contains(
                "speak hindi"
            ) ||
            normalized.contains(
                "respond in hindi"
            ) ||
            normalized.contains(
                "reply in hindi"
            ) ||
            normalized.contains(
                "answer in hindi"
            )
        ) {
            return HINDI_LANGUAGE
        }

        // -----------------------------------------------------
        // KANNADA
        // -----------------------------------------------------

        if (
            normalized.contains(
                "talk to me in kannada"
            ) ||
            normalized.contains(
                "speak to me in kannada"
            ) ||
            normalized.contains(
                "speak kannada"
            ) ||
            normalized.contains(
                "respond in kannada"
            ) ||
            normalized.contains(
                "reply in kannada"
            ) ||
            normalized.contains(
                "answer in kannada"
            )
        ) {
            return KANNADA_LANGUAGE
        }

        // -----------------------------------------------------
        // ENGLISH
        // -----------------------------------------------------

        if (
            normalized.contains(
                "talk to me in english"
            ) ||
            normalized.contains(
                "speak to me in english"
            ) ||
            normalized.contains(
                "speak english"
            ) ||
            normalized.contains(
                "respond in english"
            ) ||
            normalized.contains(
                "reply in english"
            ) ||
            normalized.contains(
                "answer in english"
            )
        ) {
            return DEFAULT_SPEECH_LANGUAGE
        }

        return null
    }

    // =========================================================
    // LANGUAGE CONFIRMATION
    // =========================================================

    private fun languageConfirmationResponse(
        languageTag: String
    ): String {

        return when {

            languageTag.startsWith("ta") ->
                "சரி. இனிமேல் நான் தமிழில் பேசுகிறேன்."

            languageTag.startsWith("te") ->
                "సరే. ఇక నుంచి నేను తెలుగులో మాట్లాడుతాను."

            languageTag.startsWith("ml") ->
                "ശരി. ഇനി മുതൽ ഞാൻ മലയാളത്തിൽ സംസാരിക്കും."

            languageTag.startsWith("hi") ->
                "ठीक है। अब से मैं हिंदी में बात करूंगा।"

            languageTag.startsWith("kn") ->
                "ಸರಿ. ಇನ್ನು ಮುಂದೆ ನಾನು ಕನ್ನಡದಲ್ಲಿ ಮಾತನಾಡುತ್ತೇನೆ."

            else ->
                "Okay. I will speak in English."
        }
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

            scheduleConversationRestart()

            return
        }

        scheduleConversationTimeout()

        // =====================================================
        // EXPLICIT LANGUAGE REQUEST
        // =====================================================

        val requestedLanguage =
            detectRequestedResponseLanguage(
                normalizedText
            )

        if (
            requestedLanguage != null
        ) {

            preferredResponseLanguage =
                requestedLanguage

            detectedSpeechLanguage =
                requestedLanguage

            Log.d(
                TAG,
                "Explicit response language requested: $requestedLanguage"
            )

            updateTtsLanguage(
                requestedLanguage
            )

            speak(
                languageConfirmationResponse(
                    requestedLanguage
                )
            )

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

            try {
                whisperRecorder?.stopRecording()
            } catch (_: Exception) {
            }

            speak(
                "Okay. I'm here whenever you need me."
            )

            return
        }

        // =====================================================
        // PREVENT DUPLICATE REQUESTS
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

                scheduleConversationRestart()
            }
        }
    }

    // =========================================================
    // SPEAK
    // =========================================================

    private fun speak(
        text: String
    ) {

        mainHandler.post {

            if (engineTerminated) {
                return@post
            }

            if (text.isBlank()) {

                scheduleConversationRestart()

                return@post
            }

            if (!ttsReady) {

                Log.e(
                    TAG,
                    "TTS is not ready"
                )

                scheduleConversationRestart()

                return@post
            }

            try {

                /*
                 * Stop both possible microphone systems before
                 * TTS begins.
                 */
                stopListeningInternal()

                try {
                    whisperRecorder?.stopRecording()
                } catch (_: Exception) {
                }

                /*
                 * If the response is native-script Tamil/Telugu/etc.,
                 * automatically select that TTS language.
                 *
                 * Otherwise keep the preferred conversation language.
                 */
                val responseLanguage =
                    inferTtsLanguageFromText(
                        text
                    )

                updateTtsLanguage(
                    responseLanguage
                )

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

                scheduleConversationRestart()
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
    // STOP SPEECH RECOGNIZER
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
    // EMERGENCY
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

        try {
            whisperRecorder?.stopRecording()
        } catch (_: Exception) {
        }

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

                        /*
                         * Prevent GPT-OSS from consuming the entire
                         * completion budget with hidden reasoning.
                         */
                        put(
                            "include_reasoning",
                            false
                        )
                    }

                val requestBody =
                    jsonBody
                        .toString()
                        .toByteArray(
                            StandardCharsets.UTF_8
                        )

                // =================================================
                // HTTP
                // =================================================

                val connection =
                    (
                            URL(BASE_URL)
                                .openConnection()
                                    as HttpURLConnection
                            ).apply {

                            requestMethod =
                                "POST"

                            connectTimeout =
                                connectTimeoutMs

                            readTimeout =
                                readTimeoutMs

                            doOutput = true

                            doInput = true

                            setRequestProperty(
                                "Authorization",
                                "Bearer $GROQ_API_KEY"
                            )

                            setRequestProperty(
                                "Content-Type",
                                "application/json; charset=utf-8"
                            )

                            setRequestProperty(
                                "Accept",
                                "application/json"
                            )
                        }

                try {

                    connection.outputStream.use { outputStream ->

                        outputStream.write(
                            requestBody
                        )

                        outputStream.flush()
                    }

                    val responseCode =
                        connection.responseCode

                    val responseStream =
                        if (
                            responseCode in 200..299
                        ) {

                            connection.inputStream

                        } else {

                            connection.errorStream
                        }

                    val responseData =
                        responseStream
                            ?.use { stream ->

                                BufferedReader(
                                    InputStreamReader(
                                        stream,
                                        StandardCharsets.UTF_8
                                    )
                                ).use { reader ->

                                    reader.readText()
                                }
                            }

                    if (
                        responseCode !in
                        200..299
                    ) {

                        Log.e(
                            TAG,
                            "Groq HTTP error: $responseCode"
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
                    // JSON
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

                        Log.e(
                            TAG,
                            "Groq returned no choices. Full response: $responseData"
                        )

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
                        choices.optJSONObject(
                            0
                        )

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

                    if (
                        rawReply.isEmpty()
                    ) {

                        Log.e(
                            TAG,
                            "Groq message.content is empty. Message JSON: ${message?.toString()}"
                        )

                        Log.e(
                            TAG,
                            "Groq full response: $responseData"
                        )

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

                } finally {

                    connection.disconnect()
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

        val responseLanguage =
            preferredResponseLanguage
                ?: detectedSpeechLanguage

        val languageInstruction =
            when {

                responseLanguage.startsWith(
                    "ta"
                ) ->
                    """
                    The current preferred language is Tamil.

                    If the user message is Tamil script,
                    reply in Tamil script.

                    If the user message is Tanglish,
                    understand it as Tamil and reply naturally
                    in Tanglish unless the user explicitly asks
                    for Tamil script.

                    Do not reply in English.
                    """.trimIndent()

                responseLanguage.startsWith(
                    "te"
                ) ->
                    """
                    The current preferred language is Telugu.
                    Reply in Telugu script.
                    Do not reply in English unless explicitly requested.
                    """.trimIndent()

                responseLanguage.startsWith(
                    "ml"
                ) ->
                    """
                    The current preferred language is Malayalam.
                    Reply in Malayalam script.
                    Do not reply in English unless explicitly requested.
                    """.trimIndent()

                responseLanguage.startsWith(
                    "hi"
                ) ->
                    """
                    The current preferred language is Hindi.
                    Reply in Hindi script.
                    Do not reply in English unless explicitly requested.
                    """.trimIndent()

                responseLanguage.startsWith(
                    "kn"
                ) ->
                    """
                    The current preferred language is Kannada.
                    Reply in Kannada script.
                    Do not reply in English unless explicitly requested.
                    """.trimIndent()

                else ->
                    """
                    The current preferred language is English unless
                    the user is speaking Tanglish.

                    If the user is speaking Tanglish, interpret it
                    as Tamil speech written in English letters and
                    reply naturally in Tanglish.

                    Otherwise reply in English.
                    """.trimIndent()
            }

        messages.put(

            JSONObject().apply {

                put(
                    "role",
                    "system"
                )

                put(
                    "content",
                    languageInstruction
                )
            }
        )

        messages.put(

            JSONObject().apply {

                put(
                    "role",
                    "system"
                )

                put(
                    "content",
                    "The user's current message is: " +
                            "do not translate it before understanding it. " +
                            "Preserve its intended meaning, including Tanglish."
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

        return (
                isListening.get() ||
                        whisperRecorder?.isRecording() == true
                )
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

        wakeWordFailureCount = 0

        aiRequestRunning.set(false)

        try {
            whisperRecorder?.stopRecording()
        } catch (_: Exception) {
        }

        Log.d(
            TAG,
            "Emergency state reset"
        )

        scheduleConversationRestart()
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
            // WHISPER
            // -------------------------------------------------

            try {

                whisperRecorder?.shutdown()

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Error shutting down Whisper recorder",
                    e
                )
            }

            whisperRecorder = null

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