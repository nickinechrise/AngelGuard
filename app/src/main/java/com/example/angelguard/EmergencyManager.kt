package com.example.angelguard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.database.FirebaseDatabase
import java.util.UUID

object EmergencyManager {

    private const val TAG = "EmergencyManager"

    private val database =
        FirebaseDatabase.getInstance()

    // ============================================================
    // TRIGGER EMERGENCY
    // ============================================================

    fun triggerEmergency(
        context: Context,
        guardianNumber: String,
        lat: Double,
        lng: Double
    ) {

        try {

            // ----------------------------------------------------
            // VALIDATE GUARDIAN NUMBER
            // ----------------------------------------------------

            if (guardianNumber.isBlank()) {

                Log.e(
                    TAG,
                    "Guardian number is empty"
                )

                return
            }

            // ----------------------------------------------------
            // CREATE UNIQUE EMERGENCY SESSION
            // ----------------------------------------------------

            val sessionId =
                UUID.randomUUID().toString()

            Log.d(
                TAG,
                "Emergency session created: $sessionId"
            )

            // ----------------------------------------------------
            // SAVE ACTIVE EMERGENCY ID LOCALLY
            // ----------------------------------------------------

            context
                .getSharedPreferences(
                    "app_prefs",
                    Context.MODE_PRIVATE
                )
                .edit()
                .putString(
                    "active_emergency_id",
                    sessionId
                )
                .apply()

            // ----------------------------------------------------
            // FIREBASE REFERENCE
            // ----------------------------------------------------

            val emergencyRef =
                database
                    .getReference("emergencies")
                    .child(sessionId)

            // ----------------------------------------------------
            // EMERGENCY DATA
            // ----------------------------------------------------

            val emergencyData =
                mapOf<String, Any>(

                    "lat" to lat,

                    "lng" to lng,

                    "timestamp" to
                            System.currentTimeMillis(),

                    "phone" to guardianNumber,

                    "status" to "pending",

                    "sharing_active" to true,

                    "victim_toggle_tile" to true,

                    "trigger_source" to "AI"
                )

            // ----------------------------------------------------
            // SAVE EMERGENCY TO FIREBASE
            // ----------------------------------------------------

            emergencyRef
                .setValue(emergencyData)
                .addOnSuccessListener {

                    Log.d(
                        TAG,
                        "Emergency saved to Firebase: $sessionId"
                    )

                }
                .addOnFailureListener { error ->

                    Log.e(
                        TAG,
                        "Failed to save emergency",
                        error
                    )
                }

            // ----------------------------------------------------
            // LIVE LOCATION URL
            // ----------------------------------------------------

            val liveUrl =
                "https://aungleguard.web.app/map.html?id=$sessionId"

            // ----------------------------------------------------
            // EMERGENCY SMS
            // ----------------------------------------------------

            val messageText =
                "AngelGuard EMERGENCY! " +
                        "I may be in danger. " +
                        "View my LIVE location here: $liveUrl"

            // ----------------------------------------------------
            // SEND SMS
            // ----------------------------------------------------

            sendEmergencySms(
                context = context,
                guardianNumber = guardianNumber,
                message = messageText
            )

            // ----------------------------------------------------
            // CALL GUARDIAN IMMEDIATELY
            // ----------------------------------------------------

            callGuardian(
                context = context,
                guardianNumber = guardianNumber
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Emergency trigger failed",
                e
            )
        }
    }


    // ============================================================
    // SEND EMERGENCY SMS
    // ============================================================

    private fun sendEmergencySms(
        context: Context,
        guardianNumber: String,
        message: String
    ) {

        try {

            // ----------------------------------------------------
            // CHECK SMS PERMISSION
            // ----------------------------------------------------

            if (
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.SEND_SMS
                ) != PackageManager.PERMISSION_GRANTED
            ) {

                Log.e(
                    TAG,
                    "SEND_SMS permission not granted"
                )

                return
            }

            // ----------------------------------------------------
            // GET SMS MANAGER
            // ----------------------------------------------------

            val smsManager =
                if (android.os.Build.VERSION.SDK_INT >=
                    android.os.Build.VERSION_CODES.S
                ) {

                    context.getSystemService(
                        SmsManager::class.java
                    )

                } else {

                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }

            if (smsManager == null) {

                Log.e(
                    TAG,
                    "SmsManager unavailable"
                )

                return
            }

            // ----------------------------------------------------
            // SEND SMS
            // ----------------------------------------------------

            smsManager.sendTextMessage(
                guardianNumber,
                null,
                message,
                null,
                null
            )

            Log.d(
                TAG,
                "Emergency SMS sent successfully"
            )

        } catch (e: SecurityException) {

            Log.e(
                TAG,
                "SMS permission/security error",
                e
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Emergency SMS failed",
                e
            )
        }
    }


    // ============================================================
    // CALL GUARDIAN
    // ============================================================

    private fun callGuardian(
        context: Context,
        guardianNumber: String
    ) {

        try {

            // ----------------------------------------------------
            // CHECK PHONE CALL PERMISSION
            // ----------------------------------------------------

            if (
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CALL_PHONE
                ) != PackageManager.PERMISSION_GRANTED
            ) {

                Log.e(
                    TAG,
                    "CALL_PHONE permission not granted"
                )

                // ------------------------------------------------
                // FALLBACK TO CALL TRAMPOLINE
                // ------------------------------------------------

                launchCallTrampoline(
                    context,
                    guardianNumber
                )

                return
            }

            // ----------------------------------------------------
            // USE CALL TRAMPOLINE
            // ----------------------------------------------------

            launchCallTrampoline(
                context,
                guardianNumber
            )

        } catch (e: SecurityException) {

            Log.e(
                TAG,
                "Call permission/security error",
                e
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Guardian call failed",
                e
            )
        }
    }


    // ============================================================
    // LAUNCH CALL TRAMPOLINE
    // ============================================================

    private fun launchCallTrampoline(
        context: Context,
        guardianNumber: String
    ) {

        try {

            val callIntent =
                Intent(
                    context,
                    CallTrampolineActivity::class.java
                ).apply {

                    putExtra(
                        "phone_number",
                        guardianNumber
                    )

                    // Needed if context is not an Activity
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                }

            context.startActivity(
                callIntent
            )

            Log.d(
                TAG,
                "Call trampoline launched"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Unable to launch CallTrampolineActivity",
                e
            )
        }
    }


    // ============================================================
    // UPDATE LIVE LOCATION
    // ============================================================

    fun updateLocation(
        sessionId: String,
        lat: Double,
        lng: Double
    ) {

        try {

            val ref =
                database
                    .getReference("emergencies")
                    .child(sessionId)

            val updates =
                mapOf<String, Any>(

                    "lat" to lat,

                    "lng" to lng,

                    "timestamp" to
                            System.currentTimeMillis()
                )

            ref.updateChildren(
                updates
            )

            Log.d(
                TAG,
                "Emergency location updated"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to update emergency location",
                e
            )
        }
    }


    // ============================================================
    // STOP EMERGENCY
    // ============================================================

    fun stopEmergency(
        sessionId: String
    ) {

        try {

            val ref =
                database
                    .getReference("emergencies")
                    .child(sessionId)

            val updates =
                mapOf<String, Any>(

                    "sharing_active" to false,

                    "victim_toggle_tile" to false,

                    "status" to "terminated",

                    "termination_source" to
                            "APP_SECURE_PIN",

                    "terminated_timestamp" to
                            System.currentTimeMillis()
                )

            ref.updateChildren(
                updates
            )

            Log.d(
                TAG,
                "Emergency terminated: $sessionId"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to terminate emergency",
                e
            )
        }
    }
}