package com.example.angelguard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.android.gms.location.*
import com.google.firebase.database.FirebaseDatabase
import java.util.Locale
import kotlin.concurrent.thread

class SafetyTileService : TileService() {

    private val TAG = "AngelGuardTile"

    // Initialized at class level to be accessible everywhere
    private val masterKey by lazy { MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build() }
    private val securePrefs by lazy {
        EncryptedSharedPreferences.create(
            this, "secure_guardian_prefs", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun onClick() {
        val tile = qsTile
        if (tile?.state == Tile.STATE_ACTIVE) {
            Log.w(TAG, "Tile already active, ignoring click.")
            return
        }
        super.onClick()
        Log.d(TAG, "Tile clicked: Initiating emergency protocol")

        // 1. Set UI to active immediately
        tile?.state = Tile.STATE_ACTIVE
        tile?.updateTile()

        Toast.makeText(this, "AngelGuard: Fetching GPS and precise coordinates...", Toast.LENGTH_SHORT).show()

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setWaitForAccurateLocation(true)
            .setMaxUpdates(1)
            .build()

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                fusedLocationClient.removeLocationUpdates(this)
                val location = locationResult.lastLocation
                val savedNumber = securePrefs.getString("guardian_number", "") ?: ""

                // Construct the link utilizing the unified Firebase guardianId
                val guardianId = securePrefs.getString("guardian_id", "default_id") ?: ""
                val trackingLink = "https://aungleguard.web.app/map.html?id=$guardianId"
                // Only execute if we have a valid guardian number
                if (!savedNumber.isEmpty()) {
                    val lat = location?.latitude ?: 11.9344
                    val lng = location?.longitude ?: 79.8300
                    val database = FirebaseDatabase.getInstance()
                    val emergencyRef = database.getReference("emergencies").child("emergency_" + System.currentTimeMillis())
                    val contactName = getContactNameFromNumber(this@SafetyTileService, savedNumber)

                    Toast.makeText(
                        this@SafetyTileService,
                        "AngelGuard: Emergency location sent and calling $contactName...✅.",
                        Toast.LENGTH_LONG
                    ).show()

                    val emergencyData = mapOf(
                        "victim_toggle_tile" to true,
                        "lat" to lat,
                        "lng" to lng,
                        "guardian_id" to guardianId
                    )
                    emergencyRef.setValue(emergencyData)

                    // Also update the active guardian ID setting so map.html listens to the correct node automatically
                    database.getReference("settings").child("active_guardian_id").setValue(guardianId)

                    // Trigger the emergency SMS/Database update
                    EmergencyManager.triggerEmergency(this@SafetyTileService, savedNumber, lat, lng)
                    // 3. TRIGGER DIRECT CALL TO REGISTERED GUARDIAN
                    val callIntent = Intent(Intent.ACTION_CALL)
                    callIntent.data = Uri.parse("tel:$savedNumber")
                    callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        startActivity(callIntent)
                        Log.d(TAG, "Direct call initiated to: $savedNumber")
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Call permission missing: ", e)
                    }

                } else {
                    Log.e(TAG, "Guardian number is empty, cannot trigger emergency.")
                    Toast.makeText(this@SafetyTileService, "Error: No guardian number registered.", Toast.LENGTH_SHORT).show()
                }

                // 4. Reset the tile UI after a delay to indicate completion
                Handler(Looper.getMainLooper()).postDelayed({
                    resetTileState()
                }, 5000)
            }
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        } else {
            Log.e(TAG, "Permission denied")
            resetTileState()
        }
    }

    // Helper function to resolve phone number to contact name safely
    private fun getContactNameFromNumber(context: Context, phoneNumber: String): String {
        if (phoneNumber.isBlank() || phoneNumber == "None") return "None"

        var contactName = phoneNumber
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)

        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                if (nameIndex != -1) {
                    contactName = cursor.getString(nameIndex) ?: phoneNumber
                }
            }
        }
        return contactName
    }

    private fun processLocationAndSend(lat: Double, lng: Double, guardianNumber: String) {
        var locationName = "Unknown/Offline Area"
        try {
            val geocoder = Geocoder(this, Locale.getDefault())
            val addresses = geocoder.getFromLocation(lat, lng, 1)
            if (!addresses.isNullOrEmpty()) {
                val address = addresses[0]
                locationName = address.getAddressLine(0) ?: "$lat, $lng"
            }
        } catch (e: Exception) {
            locationName = "GPS Coordinates ($lat, $lng)"
        }
        sendSilentSms(guardianNumber, locationName)
    }

    // Updated to accept any custom message
    private fun sendSilentSms(guardianNumber: String, messageText: String) {
        try {
            if (guardianNumber.isEmpty()) return
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm?.activeNetwork != null) {
                SmsManager.getDefault().sendTextMessage(guardianNumber, null, messageText, null, null)
            } else {
                val smsRequest = OneTimeWorkRequestBuilder<SmsWorker>()
                    .setInputData(workDataOf("guardian_number" to guardianNumber, "message" to messageText))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
                WorkManager.getInstance(this).enqueue(smsRequest)
            }
        } catch (e: Exception) {
            Log.e(TAG, "SMS failed", e)
        }
    }

    private fun resetTileState() {
        Log.d(TAG, "Resetting tile state to inactive")
        qsTile?.state = Tile.STATE_INACTIVE
        qsTile?.updateTile()
    }

    override fun onStartListening() {
        super.onStartListening()

        // Only reset if the tile is NOT currently active (i.e., not in the middle of a protocol)
        if (qsTile?.state != Tile.STATE_ACTIVE) {
            resetTileState()
        }
    }

    fun initiateEmergencyCall(phoneNumber: String) {
        if (phoneNumber.isEmpty()) {
            resetTileState()
            return
        }

        val intent = Intent(this, CallTrampolineActivity::class.java).apply {
            putExtra("phone_number", phoneNumber)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            startActivity(intent)
        } catch (e: Exception) {
            val fallbackIntent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$phoneNumber")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(fallbackIntent)
        }

        // FINAL RESET: Delay 5 seconds to ensure the user sees the transition
        Handler(Looper.getMainLooper()).postDelayed({
            resetTileState()
        }, 5000)
    }
}