package com.example.angelguard

import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.LocationServices
import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.os.Build
import android.net.Uri
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.BackoffPolicy
import androidx.work.WorkManager
import androidx.work.ExistingWorkPolicy

class SOSAccessibilityService : AccessibilityService() {

    private var pressCount = 0
    private var lastPressTime: Long = 0
    private var screenOffReceiver: BroadcastReceiver? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Required override
    }

    override fun onInterrupt() {
        // Required override
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        // 1. Version-safe Notification Setup for Foreground Service
        val channelId = "SOS_SERVICE_CHANNEL"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "SOS Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            Notification.Builder(this)
        }

        val notification = builder
            .setContentTitle("AngelGuard Active")
            .setContentText("Monitoring power button emergency gestures...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()

        startForeground(1, notification)

        // 2. Register Screen Off Broadcast Receiver to track Power Button clicks reliably
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }

        screenOffReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF || intent?.action == Intent.ACTION_SCREEN_ON) {
                    handlePowerButtonPress()
                }
            }
        }

        registerReceiver(screenOffReceiver, filter)
        android.util.Log.d("SOS_DEBUG", "SOSAccessibilityService connected & Power BroadcastReceiver registered.")
    }

    private fun handlePowerButtonPress() {
        val currentTime = System.currentTimeMillis()

        // 3000ms (3 seconds) window to register consecutive power button toggles
        if (currentTime - lastPressTime < 3000) {
            pressCount++
        } else {
            pressCount = 1
        }
        lastPressTime = currentTime

        android.util.Log.d("SOS_DEBUG", "Power button toggle detected! Count: $pressCount")

        if (pressCount >= 3) {
            // Hand off the emergency trigger to your PowerButtonReceiver
            val broadcastIntent = Intent(PowerButtonReceiver.ACTION_THREE_POWER_PRESSES).apply {
                setPackage(packageName)
            }
            sendBroadcast(broadcastIntent)

            pressCount = 0
        }
    }

    private var isMessageSent = false
    private fun triggerEmergencyAction() {
        if (isMessageSent) return
        isMessageSent = true

        // Reset flag after 60 seconds
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            isMessageSent = false
        }, 60000)

        try {
            // Enqueue SmsWorker for safe background messaging
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val smsWorkRequest = OneTimeWorkRequestBuilder<SmsWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(this).enqueueUniqueWork(
                "sos_sms_work",
                ExistingWorkPolicy.REPLACE,
                smsWorkRequest
            )

            // Fetch guardian number and trigger immediate phone call
            val masterKey = MasterKey.Builder(this)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val sharedPreferences = EncryptedSharedPreferences.create(
                this, "secure_guardian_prefs", masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            val savedNumber = sharedPreferences.getString("guardian_number", null)

            if (!savedNumber.isNullOrEmpty()) {
                executeEmergencyCall(savedNumber)
            } else {
                android.util.Log.e("SOS_ERROR", "No guardian number found for emergency call!")
            }

        } catch (e: Exception) {
            android.util.Log.e("SOS_ERROR", "Error triggering emergency action: ${e.message}")
            isMessageSent = false
        }
    }

    private fun executeEmergencyCall(phoneNumber: String) {
        try {
            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                startActivity(callIntent)
                android.util.Log.d("SOS_DEBUG", "Emergency phone call launched to: $phoneNumber")
            } else {
                android.util.Log.e("SOS_ERROR", "CALL_PHONE permission is missing!")
            }
        } catch (e: Exception) {
            android.util.Log.e("SOS_ERROR", "CALL EXECUTION FAILED: ${e.message}")
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (screenOffReceiver != null) {
            try {
                unregisterReceiver(screenOffReceiver)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "com.example.angelguard.TRIGGER_SOS" || intent?.action == "TRIGGER_SOS") {
            val emergencyId = intent.getStringExtra("EMERGENCY_ID")
            if (!emergencyId.isNullOrEmpty()) {
                // Save the unique emergency ID locally so termination/tracking matches
                val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                prefs.edit().putString("active_emergency_id", emergencyId).apply()
            }
            triggerEmergencyAction()
        }
        return START_STICKY
    }
}