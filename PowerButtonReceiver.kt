package com.example.angelguard

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import android.os.Looper
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult

class PowerButtonReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PowerButtonReceiver"
        const val ACTION_THREE_POWER_PRESSES = "com.example.angelguard.THREE_POWER_PRESSES"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_THREE_POWER_PRESSES) {
            Log.d(TAG, "Ignoring unrelated broadcast: ${intent?.action}")
            return
        }

        Log.e(TAG, "================================")
        Log.e(TAG, "3 POWER BUTTON PRESSES DETECTED")
        Log.e(TAG, "Starting AngelGuard SOS with Live Location")
        Log.e(TAG, "================================")

        // 1. Give immediate vibration feedback
        vibrateDevice(context)

        // 2. Fetch the current location and trigger the emergency workflow (matching the Quick Tile)
        fetchLocationAndTriggerEmergency(context)
    }

    private fun fetchLocationAndTriggerEmergency(context: Context) {
        try {
            val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val securePrefs = EncryptedSharedPreferences.create(
                context, "secure_guardian_prefs", masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            val savedNumber = securePrefs.getString("guardian_number", "") ?: ""
            if (savedNumber.isEmpty()) {
                Log.e(TAG, "Guardian number is empty, cannot trigger emergency.")
                return
            }

            // Request fresh high-accuracy location just like SafetyTileService
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
                .setWaitForAccurateLocation(true)
                .setMaxUpdates(1)
                .build()

            val locationCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    fusedLocationClient.removeLocationUpdates(this)
                    val location = locationResult.lastLocation

                    val lat = location?.latitude ?: 11.9344
                    val lng = location?.longitude ?: 79.8300

                    // Invoke your unified EmergencyManager (pushes to Firebase & sends SMS tracking link)
                    EmergencyManager.triggerEmergency(context, savedNumber, lat, lng)

                    // Launch the emergency phone call via CallTrampolineActivity
                    val callIntent = Intent(context, CallTrampolineActivity::class.java).apply {
                        putExtra("phone_number", savedNumber)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(callIntent)
                    Log.d(TAG, "Emergency call and location broadcast triggered successfully via Power Button.")
                }
            }

            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
            } else {
                Log.e(TAG, "Location permission missing in PowerButtonReceiver!")
                // Fallback to trigger without precise GPS if permissions block it
                EmergencyManager.triggerEmergency(context, savedNumber, 11.9344, 79.8300)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute location-based emergency workflow", e)
        }
    }

    private fun triggerSmsWork(context: Context) {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val smsWorkRequest = OneTimeWorkRequestBuilder<SmsWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "sos_sms_work",
                ExistingWorkPolicy.REPLACE,
                smsWorkRequest
            )
            Log.d(TAG, "SmsWorker enqueued successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue SmsWorker", e)
        }
    }

    private fun triggerEmergencyCall(context: Context) {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val sharedPreferences = EncryptedSharedPreferences.create(
                context, "secure_guardian_prefs", masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            val savedNumber = sharedPreferences.getString("guardian_number", null)

            if (!savedNumber.isNullOrEmpty()) {
                val callIntent = Intent(context, CallTrampolineActivity::class.java).apply {
                    putExtra("phone_number", savedNumber)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(callIntent)
                Log.d(TAG, "Emergency call trampoline launched for: $savedNumber")
            } else {
                Log.e(TAG, "No guardian number found for emergency call!")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch emergency call", e)
        }
    }

    private fun vibrateDevice(context: Context) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(android.os.VibratorManager::class.java)
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            vibrator?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    it.vibrate(VibrationEffect.createOneShot(500L, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    it.vibrate(500L)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to vibrate device", e)
        }
    }
}