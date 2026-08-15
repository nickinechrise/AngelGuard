package com.example.angelguard.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class AngelGuardAIService : Service() {

    companion object {

        private const val TAG = "AngelGuardAIService"

        private const val CHANNEL_ID =
            "angel_guard_ai_channel"

        private const val NOTIFICATION_ID = 1001

        const val ACTION_START_LISTENING =
            "com.example.angelguard.ai.START_LISTENING"

        const val ACTION_STOP_LISTENING =
            "com.example.angelguard.ai.STOP_LISTENING"

        const val ACTION_STOP_SERVICE =
            "com.example.angelguard.ai.STOP_SERVICE"

        const val ACTION_AI_STATUS =
            "com.example.angelguard.AI_STATUS"
    }

    // ============================================================
    // AI ENGINE
    // ============================================================

    private var aiEngine: AngelGuardAIEngine? = null

    @Volatile
    private var isListening = false

    @Volatile
    private var isConversationActive = false

    // ============================================================
    // SERVICE CREATED
    // ============================================================

    override fun onCreate() {
        super.onCreate()

        Log.d(
            TAG,
            "AngelGuard AI Service created"
        )

        createNotificationChannel()

        /*
         * Foreground service notification must be started
         * immediately.
         */
        startForegroundServiceNotification()

        initializeAIEngine()
    }

    // ============================================================
    // INITIALIZE AI ENGINE
    // ============================================================

    private fun initializeAIEngine() {

        try {

            Log.d(
                TAG,
                "Initializing AngelGuard AI Engine"
            )

            aiEngine = AngelGuardAIEngine(

                context = this,

                // ==================================================
                // WAKE WORD DETECTED
                // ==================================================

                onWakeWordDetected = {

                    Log.d(
                        TAG,
                        "Hey AngelGuard wake word detected"
                    )

                    /*
                     * The AI Engine is now allowed to move
                     * from wake-word detection into conversation.
                     *
                     * The actual microphone/conversation
                     * transition is controlled by
                     * AngelGuardAIEngine.
                     */

                    isConversationActive = true

                    updateNotification(
                        "AngelGuard is listening..."
                    )

                    sendStatusBroadcast(
                        "WAKE_WORD_DETECTED"
                    )
                },

                // ==================================================
                // THREAT DETECTED
                // ==================================================

                onThreatDetected = {

                    Log.w(
                        TAG,
                        "Emergency threat detected by AI"
                    )

                    /*
                     * The AI Engine should already have stopped
                     * its conversation before this callback.
                     */

                    isConversationActive = false
                    isListening = false

                    updateNotification(
                        "Emergency threat detected"
                    )

                    sendStatusBroadcast(
                        "THREAT_DETECTED"
                    )
                }
            )

            Log.d(
                TAG,
                "AngelGuard AI Engine initialized"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to initialize AngelGuard AI Engine",
                e
            )

            aiEngine = null

            isListening = false
            isConversationActive = false

            updateNotification(
                "AI initialization failed"
            )

            sendStatusBroadcast(
                "INITIALIZATION_ERROR"
            )
        }
    }

    // ============================================================
    // START AI LISTENING
    // ============================================================

    private fun startAIListening() {

        val engine = aiEngine

        if (engine == null) {

            Log.e(
                TAG,
                "Cannot start listening: AI engine is null"
            )

            updateNotification(
                "AI engine unavailable"
            )

            sendStatusBroadcast(
                "LISTENING_ERROR"
            )

            return
        }

        /*
         * Do not start a second recognition session.
         */
        if (engine.isCurrentlyListening()) {

            isListening = true

            Log.d(
                TAG,
                "AI wake-word listener is already active"
            )

            updateNotification(
                "Say \"Hey AngelGuard\""
            )

            sendStatusBroadcast(
                "LISTENING_ALREADY_ACTIVE"
            )

            return
        }

        try {

            /*
             * IMPORTANT:
             *
             * startListening() starts the AI engine's
             * listening/wake-word system.
             *
             * It does NOT mean that the user has entered
             * an active conversation.
             *
             * AngelGuardAIEngine is responsible for:
             *
             * 1. Waiting for "Hey AngelGuard"
             * 2. Detecting the wake word
             * 3. Starting conversation mode
             * 4. Processing the user's speech
             * 5. Detecting threats
             */
            engine.startListening()

            isListening = true
            isConversationActive = false

            Log.d(
                TAG,
                "AngelGuard wake-word monitoring started"
            )

            updateNotification(
                "Say \"Hey AngelGuard\""
            )

            sendStatusBroadcast(
                "WAKE_WORD_LISTENING"
            )

        } catch (e: Exception) {

            isListening = false
            isConversationActive = false

            Log.e(
                TAG,
                "Failed to start AI wake-word listening",
                e
            )

            updateNotification(
                "Voice monitoring unavailable"
            )

            sendStatusBroadcast(
                "LISTENING_ERROR"
            )
        }
    }

    // ============================================================
    // STOP AI LISTENING
    // ============================================================

    private fun stopAIListening() {

        val engine = aiEngine

        if (engine == null) {

            isListening = false
            isConversationActive = false

            Log.d(
                TAG,
                "AI engine is already null"
            )

            return
        }

        try {

            /*
             * stopConversation() safely stops:
             *
             * - wake-word/conversation recognition
             * - active conversation
             * - speech recognition
             * - Text-To-Speech
             * - pending restart callbacks
             * - related handlers/coroutines
             */
            engine.stopConversation()

            isListening = false
            isConversationActive = false

            Log.d(
                TAG,
                "AngelGuard AI listening stopped"
            )

            updateNotification(
                "AngelGuard monitoring paused"
            )

            sendStatusBroadcast(
                "LISTENING_STOPPED"
            )

        } catch (e: Exception) {

            isListening = false
            isConversationActive = false

            Log.e(
                TAG,
                "Error stopping AI listening",
                e
            )
        }
    }

    // ============================================================
    // HANDLE SERVICE COMMANDS
    // ============================================================

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        Log.d(
            TAG,
            "onStartCommand: ${intent?.action}"
        )

        when (intent?.action) {

            // ----------------------------------------------------
            // START WAKE-WORD MONITORING
            // ----------------------------------------------------

            ACTION_START_LISTENING -> {

                Log.d(
                    TAG,
                    "Starting AngelGuard wake-word monitoring"
                )

                startAIListening()
            }

            // ----------------------------------------------------
            // STOP LISTENING
            // ----------------------------------------------------

            ACTION_STOP_LISTENING -> {

                Log.d(
                    TAG,
                    "Stopping AngelGuard listening"
                )

                stopAIListening()
            }

            // ----------------------------------------------------
            // STOP SERVICE
            // ----------------------------------------------------

            ACTION_STOP_SERVICE -> {

                Log.d(
                    TAG,
                    "Stopping AngelGuard AI service"
                )

                stopAIListening()

                stopSelf()
            }

            // ----------------------------------------------------
            // NO ACTION
            // ----------------------------------------------------

            null -> {

                /*
                 * MainActivity may start the service without
                 * providing an explicit action.
                 *
                 * Start wake-word monitoring in that case.
                 */
                Log.d(
                    TAG,
                    "No action supplied - starting wake-word monitoring"
                )

                startAIListening()
            }

            // ----------------------------------------------------
            // UNKNOWN ACTION
            // ----------------------------------------------------

            else -> {

                Log.w(
                    TAG,
                    "Unknown service action: ${intent.action}"
                )
            }
        }

        /*
         * Keep the service alive if Android recreates it.
         */
        return START_STICKY
    }

    // ============================================================
    // START FOREGROUND NOTIFICATION
    // ============================================================

    private fun startForegroundServiceNotification() {

        try {

            val notification =
                createNotification(
                    "Say \"Hey AngelGuard\""
                )

            startForeground(
                NOTIFICATION_ID,
                notification
            )

            Log.d(
                TAG,
                "Foreground service started"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to start foreground service",
                e
            )
        }
    }

    // ============================================================
    // UPDATE NOTIFICATION
    // ============================================================

    private fun updateNotification(
        message: String
    ) {

        try {

            val notification =
                createNotification(
                    message
                )

            val notificationManager =
                getSystemService(
                    NotificationManager::class.java
                )

            notificationManager.notify(
                NOTIFICATION_ID,
                notification
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to update notification",
                e
            )
        }
    }

    // ============================================================
    // CREATE NOTIFICATION
    // ============================================================

    private fun createNotification(
        message: String
    ): Notification {

        return NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setContentTitle(
                "AngelGuard Active"
            )
            .setContentText(
                message
            )
            .setSmallIcon(
                android.R.drawable.ic_dialog_info
            )
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(
                NotificationCompat.CATEGORY_SERVICE
            )
            .setPriority(
                NotificationCompat.PRIORITY_LOW
            )
            .setVisibility(
                NotificationCompat.VISIBILITY_PUBLIC
            )
            .build()
    }

    // ============================================================
    // CREATE NOTIFICATION CHANNEL
    // ============================================================

    private fun createNotificationChannel() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "AngelGuard AI Service",
                    NotificationManager.IMPORTANCE_LOW
                )

            channel.description =
                "AngelGuard wake-word and voice security monitoring"

            channel.setShowBadge(false)

            val notificationManager =
                getSystemService(
                    NotificationManager::class.java
                )

            notificationManager.createNotificationChannel(
                channel
            )

            Log.d(
                TAG,
                "Notification channel created"
            )
        }
    }

    // ============================================================
    // SEND STATUS BROADCAST
    // ============================================================

    private fun sendStatusBroadcast(
        status: String
    ) {

        try {

            val intent =
                Intent(
                    ACTION_AI_STATUS
                ).apply {

                    /*
                     * Restrict this broadcast to AngelGuard.
                     */
                    setPackage(
                        packageName
                    )

                    putExtra(
                        "status",
                        status
                    )
                }

            sendBroadcast(
                intent
            )

            Log.d(
                TAG,
                "AI status broadcast: $status"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to send AI status broadcast",
                e
            )
        }
    }

    // ============================================================
    // SERVICE DESTROYED
    // ============================================================

    override fun onDestroy() {

        Log.d(
            TAG,
            "AngelGuard AI Service destroyed"
        )

        /*
         * Stop recognition/conversation first.
         */
        try {

            stopAIListening()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Error stopping AI listener",
                e
            )
        }

        /*
         * Completely release:
         *
         * - SpeechRecognizer
         * - TextToSpeech
         * - coroutines
         * - handlers
         * - callbacks
         */
        try {

            aiEngine?.terminateEngine()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Error terminating AI engine",
                e
            )
        }

        aiEngine = null

        isListening = false
        isConversationActive = false

        /*
         * Remove foreground notification.
         */
        try {

            val notificationManager =
                getSystemService(
                    NotificationManager::class.java
                )

            notificationManager.cancel(
                NOTIFICATION_ID
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to remove notification",
                e
            )
        }

        super.onDestroy()
    }

    // ============================================================
    // NOT A BOUND SERVICE
    // ============================================================

    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return null
    }
}