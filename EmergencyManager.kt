package com.example.angelguard

import android.content.Context
import android.telephony.SmsManager
import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import java.util.UUID

object EmergencyManager {
    private val database = FirebaseDatabase.getInstance()

    // We keep the sessionId variable so we can update it if needed
    fun triggerEmergency(context: Context, guardianNumber: String, lat: Double, lng: Double) {
        // Generate a stable ID for this emergency session
        val sessionId = UUID.randomUUID().toString()
        val ref = database.getReference("emergencies/$sessionId")
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("active_emergency_id", sessionId)
            .apply()
        // Prepare the data
        val locationData = mapOf(
            "lat" to lat,
            "lng" to lng,
            "timestamp" to System.currentTimeMillis(),
            "status" to "pending" // Added status for your upcoming two-way feedback feature
        )

        // Push to Firebase
        ref.setValue(locationData)

        // Generate the URL for the guardian
        val liveUrl = "https://aungleguard.web.app/map.html?id=$sessionId"
        val messageText = "AngelGuard EMERGENCY! View my LIVE location here: $liveUrl"

        // Send SMS
        try {
            @Suppress("DEPRECATION")
            val smsManager = SmsManager.getDefault()
            smsManager.sendTextMessage(guardianNumber, null, messageText, null, null)
            Log.d("EmergencyManager", "SMS sent successfully to $guardianNumber")
        } catch (e: Exception) {
            Log.e("EmergencyManager", "SMS failed: ", e)
        }
    }

    // New helper to update location in real-time if needed
    fun updateLocation(sessionId: String, lat: Double, lng: Double) {
        val ref = database.getReference("emergencies/$sessionId")
        ref.child("lat").setValue(lat)
        ref.child("lng").setValue(lng)
        ref.child("timestamp").setValue(System.currentTimeMillis())
    }
}