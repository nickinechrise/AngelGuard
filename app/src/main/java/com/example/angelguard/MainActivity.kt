package com.example.angelguard

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.ContactsContract
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

import com.example.angelguard.ai.AngelGuardAIEngine
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.model.RectangularBounds
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.firebase.FirebaseApp
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.hbb20.CountryCodePicker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean


class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    // ============================================================
    // TAG / CONSTANTS
    // ============================================================

    companion object {
        private const val TAG = "MainActivity"

        private const val PERMISSION_REQUEST_CODE = 101
        private const val CONTACT_PERMISSION_REQUEST_CODE = 102
    }


    // ============================================================
    // UI
    // ============================================================

    private lateinit var btnSave: Button

    private lateinit var tvActiveNumberDisplay: TextView

    private lateinit var etGuardianNumber: EditText

    private lateinit var historyContainer: LinearLayout

    private lateinit var tvNoHistory: TextView

    private lateinit var ccp: CountryCodePicker

    private lateinit var guardianModeLayout: LinearLayout

    private lateinit var publicDispatchLayout: LinearLayout

    private lateinit var btnCancelAlert: Button

    private lateinit var btnStopLiveLocation: Button


    // ============================================================
    // ANGELGUARD AI
    // ============================================================

    private lateinit var aiEngine: AngelGuardAIEngine

    private var mMap: GoogleMap? = null

    /**
     * Prevent multiple emergency triggers at the same time.
     */
    private val emergencyInProgress =
        AtomicBoolean(false)

    /**
     * True after "Hey AngelGuard" is detected.
     */
    @Volatile
    private var voiceSessionActive = false


    // ============================================================
    // CONTACT PICKER
    // ============================================================

    private val contactPickerLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->

            if (result.resultCode != RESULT_OK) {
                return@registerForActivityResult
            }

            val contactUri =
                result.data?.data
                    ?: return@registerForActivityResult

            readSelectedContact(contactUri)
        }


    // ============================================================
    // ON CREATE
    // ============================================================

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(savedInstanceState)

        setTheme(
            androidx.appcompat.R.style
                .Theme_AppCompat_Light_DarkActionBar
        )

        setContentView(
            R.layout.activity_main
        )


        // ========================================================
        // 1. INITIALIZE ALL VIEWS
        // ========================================================

        guardianModeLayout =
            findViewById(
                R.id.layoutGuardianMode
            )

        publicDispatchLayout =
            findViewById(
                R.id.layoutPublicDispatch
            )

        btnCancelAlert =
            findViewById(
                R.id.btnCancelAlert
            )

        btnStopLiveLocation =
            findViewById(
                R.id.btnStopLiveLocation
            )

        tvActiveNumberDisplay =
            findViewById(
                R.id.tvActiveNumberDisplay
            )

        etGuardianNumber =
            findViewById(
                R.id.etGuardianNumber
            )

        historyContainer =
            findViewById(
                R.id.historyContainer
            )

        tvNoHistory =
            findViewById(
                R.id.tvNoHistory
            )

        ccp =
            findViewById(
                R.id.ccp
            )

        btnSave =
            findViewById(
                R.id.btnSave
            )


        // ========================================================
        // 2. CANCEL ALERT
        // ========================================================

        btnCancelAlert.setOnClickListener {
            showPinVerificationDialog()
        }


        // ========================================================
        // 3. STOP LIVE LOCATION
        // ========================================================

        btnStopLiveLocation.setOnClickListener {
            showPinVerificationDialog()
        }


        // ========================================================
        // 4. GOOGLE MAP
        // ========================================================

        val mapFragment =
            supportFragmentManager
                .findFragmentById(
                    R.id.map
                ) as? SupportMapFragment

        mapFragment?.getMapAsync(this)


        // ========================================================
        // 5. COUNTRY CODE PICKER
        // ========================================================

        ccp.registerCarrierNumberEditText(
            etGuardianNumber
        )


        // ========================================================
        // 6. CONTACT PICKER
        // ========================================================

        etGuardianNumber.setOnClickListener {
            openContactPicker()
        }


        // ========================================================
        // 7. SAVE GUARDIAN
        // ========================================================

        btnSave.setOnClickListener {
            saveGuardianFromInput()
        }


        // ========================================================
        // 8. FIREBASE
        // ========================================================

        initializeFirebase()


        // ========================================================
        // 9. PERMISSIONS
        // ========================================================

        checkAndRequestPermissions()


        // ========================================================
        // 10. RESTORE APP MODE
        // ========================================================

        restoreAppMode()


        // ========================================================
        // 11. RESTORE GUARDIAN UI
        // ========================================================

        refreshUI()


        // ========================================================
        // 12. SAFETY PIN
        // ========================================================

        checkIfPinIsSet()


        // ========================================================
        // 13. INITIALIZE VOICE AI
        // ========================================================

        initAngelGuardAI()


        // ========================================================
        // 14. START VOICE LISTENING
        // ========================================================

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {

            startAngelGuardListening()
        }
    }


    // ============================================================
    // FIREBASE INITIALIZATION
    // ============================================================

    private fun initializeFirebase() {

        try {

            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this)
            }

            Log.d(
                TAG,
                "Firebase initialized"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Firebase initialization error",
                e
            )
        }
    }


    // ============================================================
    // CONTACT PICKER
    // ============================================================

    private fun openContactPicker() {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.READ_CONTACTS
                ),
                CONTACT_PERMISSION_REQUEST_CODE
            )

            return
        }

        try {

            val intent =
                Intent(
                    Intent.ACTION_PICK,
                    ContactsContract
                        .CommonDataKinds
                        .Phone
                        .CONTENT_URI
                )

            contactPickerLauncher.launch(
                intent
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Unable to open contact picker",
                e
            )

            Toast.makeText(
                this,
                "Unable to open contacts.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }


    // ============================================================
    // READ SELECTED CONTACT
    // ============================================================

    private fun readSelectedContact(
        contactUri: Uri
    ) {

        try {

            val cursor =
                contentResolver.query(
                    contactUri,
                    arrayOf(
                        ContactsContract
                            .CommonDataKinds
                            .Phone
                            .DISPLAY_NAME,

                        ContactsContract
                            .CommonDataKinds
                            .Phone
                            .NUMBER
                    ),
                    null,
                    null,
                    null
                )

            cursor?.use {

                if (!it.moveToFirst()) {
                    return
                }

                val nameIndex =
                    it.getColumnIndex(
                        ContactsContract
                            .CommonDataKinds
                            .Phone
                            .DISPLAY_NAME
                    )

                val numberIndex =
                    it.getColumnIndex(
                        ContactsContract
                            .CommonDataKinds
                            .Phone
                            .NUMBER
                    )

                val guardianName =
                    if (nameIndex >= 0) {
                        it.getString(nameIndex)
                    } else {
                        "Guardian"
                    }

                val originalNumber =
                    if (numberIndex >= 0) {
                        it.getString(numberIndex)
                    } else {
                        ""
                    }

                if (originalNumber.isBlank()) {

                    Toast.makeText(
                        this,
                        "No phone number found.",
                        Toast.LENGTH_SHORT
                    ).show()

                    return
                }

                val cleanNumber =
                    originalNumber.replace(
                        Regex("[^+\\d]"),
                        ""
                    )

                AlertDialog.Builder(this)
                    .setTitle(
                        "Set Active Guardian"
                    )
                    .setMessage(
                        "Do you want to set " +
                                "$guardianName ($cleanNumber) " +
                                "as your active guardian?"
                    )
                    .setPositiveButton(
                        "Yes"
                    ) { _, _ ->

                        etGuardianNumber.setText(
                            cleanNumber
                        )

                        saveNewGuardianNumber(
                            cleanNumber
                        )

                        Toast.makeText(
                            this,
                            "Selected: $guardianName",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .setNegativeButton(
                        "Cancel",
                        null
                    )
                    .show()
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Contact reading error",
                e
            )

            Toast.makeText(
                this,
                "Unable to read contact.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }


    // ============================================================
    // SAVE GUARDIAN FROM INPUT
    // ============================================================

    private fun saveGuardianFromInput() {

        try {

            if (ccp.isValidFullNumber) {

                val guardianNumber =
                    ccp.fullNumberWithPlus

                saveNewGuardianNumber(
                    guardianNumber
                )

            } else {

                etGuardianNumber.error =
                    "Invalid phone number"

                Toast.makeText(
                    this,
                    "Please enter a valid phone number.",
                    Toast.LENGTH_SHORT
                ).show()
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Guardian validation error",
                e
            )
        }
    }


    // ============================================================
    // INITIALIZE ANGELGUARD AI
    // ============================================================

    private fun initAngelGuardAI() {

        try {

            aiEngine =
                AngelGuardAIEngine(

                    context = this,

                    onWakeWordDetected = {

                        voiceSessionActive = true

                        runOnUiThread {

                            Toast.makeText(
                                this,
                                "Yes, I'm listening.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },

                    onThreatDetected = {

                        runOnUiThread {
                            handleVoiceEmergency()
                        }
                    }
                )

            Log.d(
                TAG,
                "AngelGuard AI initialized"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "AI initialization failed",
                e
            )
        }
    }


    // ============================================================
    // START ANGELGUARD LISTENING
    // ============================================================

    private fun startAngelGuardListening() {

        try {

            if (!::aiEngine.isInitialized) {

                Log.e(
                    TAG,
                    "AI engine is not initialized"
                )

                return
            }

            if (
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {

                Log.w(
                    TAG,
                    "Microphone permission not granted"
                )

                return
            }

            aiEngine.startListening()

            Log.d(
                TAG,
                "AngelGuard wake-word standby started"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to start AngelGuard listening",
                e
            )
        }
    }


    // ============================================================
    // VOICE EMERGENCY
    // ============================================================

    private fun handleVoiceEmergency() {

        if (
            !emergencyInProgress.compareAndSet(
                false,
                true
            )
        ) {

            Log.d(
                TAG,
                "Emergency already in progress"
            )

            return
        }

        try {

            if (::aiEngine.isInitialized) {

                try {

                    aiEngine.stopConversation()

                } catch (e: Exception) {

                    Log.w(
                        TAG,
                        "Could not stop AI conversation",
                        e
                    )
                }
            }

            voiceSessionActive = false

            Toast.makeText(
                this,
                "CRITICAL: Emergency detected!",
                Toast.LENGTH_LONG
            ).show()

            triggerAngelGuardEmergency()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Voice emergency handling failed",
                e
            )

            emergencyInProgress.set(false)
        }
    }


    // ============================================================
    // TRIGGER ANGELGUARD EMERGENCY
    // ============================================================

    private fun triggerAngelGuardEmergency() {

        val fusedLocationClient =
            LocationServices
                .getFusedLocationProviderClient(
                    this
                )

        if (
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            Toast.makeText(
                this,
                "Location permission is required.",
                Toast.LENGTH_LONG
            ).show()

            triggerSos()

            emergencyInProgress.set(false)

            return
        }

        fusedLocationClient
            .lastLocation
            .addOnSuccessListener { location ->

                if (location == null) {

                    Toast.makeText(
                        this,
                        "Location unavailable. Starting SOS...",
                        Toast.LENGTH_LONG
                    ).show()

                    triggerSos()

                    emergencyInProgress.set(false)

                    return@addOnSuccessListener
                }

                sendEmergencyToManager(
                    location
                )
            }
            .addOnFailureListener { error ->

                Log.e(
                    TAG,
                    "Unable to get location",
                    error
                )

                Toast.makeText(
                    this,
                    "Unable to get location. Starting SOS...",
                    Toast.LENGTH_LONG
                ).show()

                triggerSos()

                emergencyInProgress.set(false)
            }
    }


    // ============================================================
    // SEND EMERGENCY TO EMERGENCY MANAGER
    // ============================================================

    private fun sendEmergencyToManager(
        location: Location
    ) {

        lifecycleScope.launch(
            Dispatchers.IO
        ) {

            try {

                val securePrefs =
                    getSecurePreferences()

                val activeNumber =
                    securePrefs.getString(
                        "guardian_number",
                        ""
                    ) ?: ""

                withContext(
                    Dispatchers.Main
                ) {

                    if (activeNumber.isBlank()) {

                        Toast.makeText(
                            this@MainActivity,
                            "No active guardian number found.",
                            Toast.LENGTH_LONG
                        ).show()

                        triggerSos()

                        emergencyInProgress.set(false)

                        return@withContext
                    }


                    // ====================================================
                    // 1. CREATE FIREBASE EMERGENCY + SEND SMS
                    // ====================================================

                    try {

                        EmergencyManager.triggerEmergency(
                            this@MainActivity,
                            activeNumber,
                            location.latitude,
                            location.longitude
                        )

                        Log.d(
                            TAG,
                            "EmergencyManager triggered successfully"
                        )

                    } catch (e: Exception) {

                        Log.e(
                            TAG,
                            "EmergencyManager failed",
                            e
                        )

                        Toast.makeText(
                            this@MainActivity,
                            "Emergency system error. Starting SOS...",
                            Toast.LENGTH_LONG
                        ).show()

                        triggerSos()

                        emergencyInProgress.set(false)

                        return@withContext
                    }


                    // ====================================================
                    // 2. CALL GUARDIAN
                    // ====================================================

                    callGuardianImmediately(
                        activeNumber
                    )
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Emergency preparation failed",
                    e
                )

                withContext(
                    Dispatchers.Main
                ) {

                    triggerSos()

                    emergencyInProgress.set(false)
                }
            }
        }
    }


    // ============================================================
    // CALL GUARDIAN IMMEDIATELY
    // ============================================================

    private fun callGuardianImmediately(
        guardianNumber: String
    ) {

        if (guardianNumber.isBlank()) {

            Log.e(
                TAG,
                "Cannot call guardian: number is empty"
            )

            return
        }


        // --------------------------------------------------------
        // CHECK CALL PHONE PERMISSION
        // --------------------------------------------------------

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CALL_PHONE
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            Log.e(
                TAG,
                "CALL_PHONE permission not granted"
            )

            Toast.makeText(
                this,
                "Phone call permission is required to call your guardian.",
                Toast.LENGTH_LONG
            ).show()

            emergencyInProgress.set(false)

            return
        }


        try {

            // ----------------------------------------------------
            // OPEN CALL TRAMPOLINE
            // ----------------------------------------------------

            val callIntent =
                Intent(
                    this,
                    CallTrampolineActivity::class.java
                ).apply {

                    putExtra(
                        "phone_number",
                        guardianNumber
                    )
                }


            startActivity(
                callIntent
            )


            Log.d(
                TAG,
                "Guardian call initiated through CallTrampolineActivity"
            )

            Toast.makeText(
                this,
                "Calling your guardian...",
                Toast.LENGTH_SHORT
            ).show()


        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to start guardian call",
                e
            )

            // ----------------------------------------------------
            // FALLBACK TO DIRECT CALL
            // ----------------------------------------------------

            try {

                val directCallIntent =
                    Intent(
                        Intent.ACTION_CALL
                    ).apply {

                        data =
                            Uri.parse(
                                "tel:$guardianNumber"
                            )
                    }


                startActivity(
                    directCallIntent
                )

                Log.d(
                    TAG,
                    "Fallback direct guardian call started"
                )

            } catch (callException: Exception) {

                Log.e(
                    TAG,
                    "Direct guardian call failed",
                    callException
                )

                Toast.makeText(
                    this,
                    "Unable to call guardian. SMS emergency alert was sent.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }


    // ============================================================
    // GOOGLE MAP
    // ============================================================

    override fun onMapReady(
        googleMap: GoogleMap
    ) {

        mMap = googleMap

        googleMap.uiSettings.isZoomControlsEnabled =
            true

        googleMap.uiSettings.isMapToolbarEnabled =
            true
    }


    // ============================================================
    // RESTORE APP MODE
    // ============================================================

    private fun restoreAppMode() {

        val prefs =
            getSharedPreferences(
                "app_prefs",
                MODE_PRIVATE
            )

        val isPublic =
            prefs.getBoolean(
                "is_public_mode",
                false
            )

        updateInterfaceVisibility(
            isPublic
        )
    }


    // ============================================================
    // UPDATE MODE UI
    // ============================================================

    private fun updateInterfaceVisibility(
        isPublicMode: Boolean
    ) {

        if (isPublicMode) {

            publicDispatchLayout.visibility =
                View.VISIBLE

            guardianModeLayout.visibility =
                View.GONE

        } else {

            publicDispatchLayout.visibility =
                View.GONE

            guardianModeLayout.visibility =
                View.VISIBLE
        }
    }


    // ============================================================
    // SOS WORKMANAGER
    // ============================================================

    private fun triggerSos() {

        try {

            vibrateDevice()

            val constraints =
                Constraints.Builder()
                    .setRequiredNetworkType(
                        NetworkType.CONNECTED
                    )
                    .build()

            val smsWorkRequest =
                OneTimeWorkRequestBuilder<SmsWorker>()
                    .setConstraints(
                        constraints
                    )
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        10,
                        TimeUnit.SECONDS
                    )
                    .build()

            WorkManager
                .getInstance(this)
                .enqueueUniqueWork(
                    "sos_work",
                    ExistingWorkPolicy.REPLACE,
                    smsWorkRequest
                )

            Toast.makeText(
                this,
                "SOS queued.",
                Toast.LENGTH_LONG
            ).show()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "SOS error",
                e
            )

            Toast.makeText(
                this,
                "Unable to queue SOS.",
                Toast.LENGTH_LONG
            ).show()
        }
    }


    // ============================================================
    // VIBRATION
    // ============================================================

    private fun vibrateDevice() {

        try {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.S
            ) {

                val vibratorManager =
                    getSystemService(
                        VibratorManager::class.java
                    )

                val vibrator =
                    vibratorManager?.defaultVibrator

                vibrator?.vibrate(
                    VibrationEffect.createOneShot(
                        500L,
                        VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )

            } else {

                @Suppress("DEPRECATION")
                val vibrator =
                    getSystemService(
                        VIBRATOR_SERVICE
                    ) as? Vibrator

                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.O
                ) {

                    vibrator?.vibrate(
                        VibrationEffect.createOneShot(
                            500L,
                            VibrationEffect.DEFAULT_AMPLITUDE
                        )
                    )

                } else {

                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(500L)
                }
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Vibration failed",
                e
            )
        }
    }


    // ============================================================
    // SECURE PREFERENCES
    // ============================================================

    private fun getSecurePreferences():
            SharedPreferences {

        val masterKey =
            MasterKey.Builder(this)
                .setKeyScheme(
                    MasterKey.KeyScheme.AES256_GCM
                )
                .build()

        return EncryptedSharedPreferences.create(
            this,
            "secure_guardian_prefs",
            masterKey,
            EncryptedSharedPreferences
                .PrefKeyEncryptionScheme
                .AES256_SIV,
            EncryptedSharedPreferences
                .PrefValueEncryptionScheme
                .AES256_GCM
        )
    }


    // ============================================================
    // GET CONTACT NAME
    // ============================================================

    private fun getContactName(
        phoneNumber: String
    ): String {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            return phoneNumber
        }

        return try {

            val uri =
                Uri.withAppendedPath(
                    ContactsContract
                        .PhoneLookup
                        .CONTENT_FILTER_URI,
                    Uri.encode(phoneNumber)
                )

            val projection =
                arrayOf(
                    ContactsContract
                        .PhoneLookup
                        .DISPLAY_NAME
                )

            contentResolver.query(
                uri,
                projection,
                null,
                null,
                null
            )?.use { cursor ->

                if (cursor.moveToFirst()) {

                    val index =
                        cursor.getColumnIndex(
                            ContactsContract
                                .PhoneLookup
                                .DISPLAY_NAME
                        )

                    if (index >= 0) {

                        return cursor.getString(
                            index
                        )
                    }
                }
            }

            phoneNumber

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Unable to get contact name",
                e
            )

            phoneNumber
        }
    }


    // ============================================================
    // REFRESH UI
    // ============================================================

    private fun refreshUI() {

        lifecycleScope.launch(
            Dispatchers.IO
        ) {

            try {

                val securePrefs =
                    getSecurePreferences()

                val activeNumber =
                    securePrefs.getString(
                        "guardian_number",
                        ""
                    ) ?: ""

                val historyString =
                    securePrefs.getString(
                        "guardian_history",
                        ""
                    ) ?: ""

                val historyList =
                    if (historyString.isBlank()) {

                        emptyList()

                    } else {

                        historyString
                            .split(",")
                            .filter {
                                it.isNotBlank()
                            }
                    }

                val activeName =
                    if (activeNumber.isNotBlank()) {

                        getContactName(
                            activeNumber
                        )

                    } else {

                        ""
                    }

                val historyNames =
                    historyList.map { number ->

                        number to getContactName(
                            number
                        )
                    }

                withContext(
                    Dispatchers.Main
                ) {

                    updateGuardianDisplay(
                        activeNumber,
                        activeName
                    )

                    updateHistoryUI(
                        historyNames
                    )
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "refreshUI error",
                    e
                )

                withContext(
                    Dispatchers.Main
                ) {

                    tvActiveNumberDisplay.text =
                        "Error loading data."
                }
            }
        }
    }


    // ============================================================
    // UPDATE GUARDIAN DISPLAY
    // ============================================================

    private fun updateGuardianDisplay(
        activeNumber: String,
        activeName: String
    ) {

        if (activeNumber.isNotBlank()) {

            val displayName =
                if (activeName.isBlank()) {
                    activeNumber
                } else {
                    activeName
                }

            tvActiveNumberDisplay.text =
                "Active Guardian: $displayName"

            etGuardianNumber.setText(
                activeNumber
            )

        } else {

            tvActiveNumberDisplay.text =
                "Active Guardian: None"

            etGuardianNumber.setText("")
        }
    }


    // ============================================================
    // UPDATE HISTORY
    // ============================================================

    private fun updateHistoryUI(
        historyNames: List<Pair<String, String>>
    ) {

        historyContainer.removeAllViews()

        if (historyNames.isEmpty()) {

            tvNoHistory.visibility =
                View.VISIBLE

            historyContainer.addView(
                tvNoHistory
            )

            return
        }

        tvNoHistory.visibility =
            View.GONE

        for (pair in historyNames) {

            val number =
                pair.first

            val name =
                pair.second

            val row =
                LinearLayout(this).apply {

                    orientation =
                        LinearLayout.HORIZONTAL

                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {

                            setMargins(
                                0,
                                0,
                                0,
                                16
                            )
                        }

                    setPadding(
                        32,
                        24,
                        32,
                        24
                    )

                    background =
                        ContextCompat.getDrawable(
                            this@MainActivity,
                            R.drawable.edit_text_bg
                        )

                    setOnClickListener {

                        saveNewGuardianNumber(
                            number,
                            true
                        )
                    }

                    setOnLongClickListener {

                        showHistoryOptions(
                            number
                        )

                        true
                    }
                }

            val tvNum =
                TextView(this).apply {

                    text =
                        if (name.isBlank()) {
                            number
                        } else {
                            name
                        }

                    textSize =
                        15f

                    setTextColor(
                        Color.parseColor(
                            "#3B3B98"
                        )
                    )
                }

            row.addView(
                tvNum
            )

            historyContainer.addView(
                row
            )
        }
    }


    // ============================================================
    // HISTORY OPTIONS
    // ============================================================

    private fun showHistoryOptions(
        number: String
    ) {

        val dialog =
            AlertDialog
                .Builder(this)
                .setTitle(
                    "Manage Number"
                )
                .setMessage(
                    "What would you like to do with this number?"
                )
                .setPositiveButton(
                    "Swap to Active"
                ) { _, _ ->

                    saveNewGuardianNumber(
                        number,
                        true
                    )
                }
                .setNegativeButton(
                    "Delete"
                ) { _, _ ->

                    deleteHistoryItem(
                        number
                    )
                }
                .setNeutralButton(
                    "Cancel",
                    null
                )
                .create()

        dialog.show()

        dialog.getButton(
            AlertDialog.BUTTON_POSITIVE
        )?.setTextColor(
            Color.parseColor(
                "#3B3B98"
            )
        )

        dialog.getButton(
            AlertDialog.BUTTON_NEGATIVE
        )?.setTextColor(
            Color.RED
        )
    }


    // ============================================================
    // SAVE GUARDIAN NUMBER
    // ============================================================

    private fun saveNewGuardianNumber(
        newNumber: String,
        isSwap: Boolean = false
    ) {

        val cleanedNumber =
            newNumber
                .trim()
                .replace(
                    Regex("[^+\\d]"),
                    ""
                )

        if (cleanedNumber.isBlank()) {

            Toast.makeText(
                this,
                "Invalid guardian number.",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        lifecycleScope.launch(
            Dispatchers.IO
        ) {

            try {

                val prefs =
                    getSecurePreferences()

                val currentActive =
                    prefs.getString(
                        "guardian_number",
                        ""
                    ) ?: ""

                val historyString =
                    prefs.getString(
                        "guardian_history",
                        ""
                    ) ?: ""

                val historyList =
                    if (historyString.isBlank()) {

                        mutableListOf()

                    } else {

                        historyString
                            .split(",")
                            .filter {
                                it.isNotBlank()
                            }
                            .toMutableList()
                    }

                if (
                    currentActive.isNotBlank() &&
                    currentActive != cleanedNumber
                ) {

                    historyList.remove(
                        currentActive
                    )

                    historyList.add(
                        0,
                        currentActive
                    )
                }

                historyList.remove(
                    cleanedNumber
                )

                val limitedHistory =
                    historyList
                        .take(4)
                        .joinToString(",")

                val saved =
                    prefs.edit()
                        .putString(
                            "guardian_number",
                            cleanedNumber
                        )
                        .putString(
                            "guardian_history",
                            limitedHistory
                        )
                        .commit()

                withContext(
                    Dispatchers.Main
                ) {

                    if (saved) {

                        refreshUI()

                        Toast.makeText(
                            this@MainActivity,
                            if (isSwap) {
                                "Swapped to: $cleanedNumber"
                            } else {
                                "Guardian saved!"
                            },
                            Toast.LENGTH_SHORT
                        ).show()

                    } else {

                        Toast.makeText(
                            this@MainActivity,
                            "Unable to save guardian.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Guardian save error",
                    e
                )

                withContext(
                    Dispatchers.Main
                ) {

                    Toast.makeText(
                        this@MainActivity,
                        "Unable to save guardian.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }


    // ============================================================
    // DELETE HISTORY
    // ============================================================

    private fun deleteHistoryItem(
        numberToDelete: String
    ) {

        lifecycleScope.launch(
            Dispatchers.IO
        ) {

            try {

                val prefs =
                    getSecurePreferences()

                val historyString =
                    prefs.getString(
                        "guardian_history",
                        ""
                    ) ?: ""

                val historyList =
                    if (historyString.isBlank()) {

                        mutableListOf()

                    } else {

                        historyString
                            .split(",")
                            .filter {
                                it.isNotBlank()
                            }
                            .toMutableList()
                    }

                val removed =
                    historyList.remove(
                        numberToDelete
                    )

                if (removed) {

                    prefs.edit()
                        .putString(
                            "guardian_history",
                            historyList.joinToString(",")
                        )
                        .commit()

                    withContext(
                        Dispatchers.Main
                    ) {

                        refreshUI()

                        Toast.makeText(
                            this@MainActivity,
                            "Deleted from history.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Delete history error",
                    e
                )
            }
        }
    }


    // ============================================================
    // PERMISSIONS
    // ============================================================

    private fun checkAndRequestPermissions() {

        val permissionsNeeded =
            mutableListOf(

                Manifest.permission.SEND_SMS,

                Manifest.permission.ACCESS_FINE_LOCATION,

                Manifest.permission.ACCESS_COARSE_LOCATION,

                Manifest.permission.READ_CONTACTS,

                Manifest.permission.RECORD_AUDIO,

                Manifest.permission.CALL_PHONE
            )

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            permissionsNeeded.add(
                Manifest.permission.POST_NOTIFICATIONS
            )
        }

        val ungranted =
            permissionsNeeded.filter { permission ->

                ContextCompat.checkSelfPermission(
                    this,
                    permission
                ) != PackageManager.PERMISSION_GRANTED
            }

        if (ungranted.isNotEmpty()) {

            ActivityCompat.requestPermissions(
                this,
                ungranted.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }


    // ============================================================
    // PERMISSION RESULT
    // ============================================================

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {

        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        when (requestCode) {

            PERMISSION_REQUEST_CODE -> {

                refreshUI()

                val locationGranted =
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED

                val microphoneGranted =
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED

                if (
                    microphoneGranted &&
                    ::aiEngine.isInitialized
                ) {

                    startAngelGuardListening()
                }

                val callGranted =
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.CALL_PHONE
                    ) == PackageManager.PERMISSION_GRANTED

                if (!callGranted) {

                    Log.w(
                        TAG,
                        "CALL_PHONE permission was not granted"
                    )
                }

                if (!locationGranted) {

                    Log.w(
                        TAG,
                        "Location permission was not granted"
                    )
                }
            }


            CONTACT_PERMISSION_REQUEST_CODE -> {

                if (
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.READ_CONTACTS
                    ) == PackageManager.PERMISSION_GRANTED
                ) {

                    openContactPicker()

                } else {

                    Toast.makeText(
                        this,
                        "Contacts permission is required.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }


    // ============================================================
    // SWITCH MODE
    // ============================================================

    private fun switchMode(
        isPublic: Boolean
    ) {

        getSharedPreferences(
            "app_prefs",
            MODE_PRIVATE
        )
            .edit()
            .putBoolean(
                "is_public_mode",
                isPublic
            )
            .apply()

        updateInterfaceVisibility(
            isPublic
        )
    }


    // ============================================================
    // MENU
    // ============================================================

    override fun onCreateOptionsMenu(
        menu: Menu
    ): Boolean {

        menuInflater.inflate(
            R.menu.menu_main,
            menu
        )

        return true
    }


    // ============================================================
    // MENU CLICK
    // ============================================================

    override fun onOptionsItemSelected(
        item: MenuItem
    ): Boolean {

        return when (item.itemId) {

            R.id.action_toggle_mode -> {

                togglePublicMode()

                true
            }

            else -> {

                super.onOptionsItemSelected(
                    item
                )
            }
        }
    }


    // ============================================================
    // TOGGLE PUBLIC MODE
    // ============================================================

    private fun togglePublicMode() {

        val prefs =
            getSharedPreferences(
                "app_prefs",
                MODE_PRIVATE
            )

        val currentMode =
            prefs.getBoolean(
                "is_public_mode",
                false
            )

        val newMode =
            !currentMode

        prefs.edit()
            .putBoolean(
                "is_public_mode",
                newMode
            )
            .apply()

        updateInterfaceVisibility(
            newMode
        )

        if (newMode) {

            findNearestPoliceStation()

        } else {

            Toast.makeText(
                this,
                "Guardian Mode enabled.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }


    // ============================================================
    // FIND NEAREST POLICE STATION
    // ============================================================

    private fun findNearestPoliceStation() {

        if (!Places.isInitialized()) {

            Toast.makeText(
                this,
                "Google Places is not initialized.",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        if (
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            Toast.makeText(
                this,
                "Location permission missing.",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        val fusedLocationClient =
            LocationServices
                .getFusedLocationProviderClient(
                    this
                )

        fusedLocationClient
            .lastLocation
            .addOnSuccessListener { location ->

                if (location == null) {

                    Toast.makeText(
                        this,
                        "Location not found. Please enable GPS.",
                        Toast.LENGTH_SHORT
                    ).show()

                    return@addOnSuccessListener
                }

                findNearbyPoliceStation(
                    location.latitude,
                    location.longitude
                )
            }
            .addOnFailureListener { error ->

                Log.e(
                    TAG,
                    "Unable to get current location",
                    error
                )

                Toast.makeText(
                    this,
                    "Unable to get current location.",
                    Toast.LENGTH_SHORT
                ).show()
            }
    }


    // ============================================================
    // PLACES API
    // ============================================================

    private fun findNearbyPoliceStation(
        lat: Double,
        lng: Double
    ) {

        if (!Places.isInitialized()) {

            Toast.makeText(
                this,
                "Google Places is not initialized.",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        try {

            val placesClient =
                Places.createClient(
                    this
                )

            val bounds =
                RectangularBounds.newInstance(

                    LatLng(
                        lat - 0.02,
                        lng - 0.02
                    ),

                    LatLng(
                        lat + 0.02,
                        lng + 0.02
                    )
                )

            val request =
                FindAutocompletePredictionsRequest
                    .builder()
                    .setQuery(
                        "police station"
                    )
                    .setLocationBias(
                        bounds
                    )
                    .build()

            placesClient
                .findAutocompletePredictions(
                    request
                )
                .addOnSuccessListener { response ->

                    if (
                        response
                            .autocompletePredictions
                            .isEmpty()
                    ) {

                        Toast.makeText(
                            this,
                            "No police stations found nearby.",
                            Toast.LENGTH_SHORT
                        ).show()

                        return@addOnSuccessListener
                    }

                    val prediction =
                        response
                            .autocompletePredictions[0]

                    val placeFields =
                        listOf(
                            Place.Field.NAME,
                            Place.Field.LAT_LNG,
                            Place.Field.ADDRESS
                        )

                    val fetchRequest =
                        FetchPlaceRequest
                            .newInstance(
                                prediction.placeId,
                                placeFields
                            )

                    placesClient
                        .fetchPlace(
                            fetchRequest
                        )
                        .addOnSuccessListener { fetchResponse ->

                            val place =
                                fetchResponse.place

                            val stationLatLng =
                                place.latLng

                            if (
                                stationLatLng == null
                            ) {

                                Toast.makeText(
                                    this,
                                    "Police station location unavailable.",
                                    Toast.LENGTH_SHORT
                                ).show()

                                return@addOnSuccessListener
                            }

                            mMap?.clear()

                            mMap?.addMarker(
                                MarkerOptions()
                                    .position(
                                        stationLatLng
                                    )
                                    .title(
                                        place.name
                                            ?: "Police Station"
                                    )
                            )

                            val results =
                                FloatArray(1)

                            Location.distanceBetween(

                                lat,
                                lng,

                                stationLatLng.latitude,
                                stationLatLng.longitude,

                                results
                            )

                            val distanceKm =
                                results[0] / 1000.0

                            Toast.makeText(
                                this,
                                "Nearest: " +
                                        "${place.name ?: "Police Station"} " +
                                        "(" +
                                        "%.2f".format(
                                            distanceKm
                                        ) +
                                        " km away)",
                                Toast.LENGTH_LONG
                            ).show()

                            mMap?.animateCamera(

                                CameraUpdateFactory
                                    .newLatLngZoom(
                                        stationLatLng,
                                        14f
                                    )
                            )
                        }
                        .addOnFailureListener { error ->

                            Toast.makeText(
                                this,
                                "Unable to get police station details.",
                                Toast.LENGTH_SHORT
                            ).show()

                            Log.e(
                                TAG,
                                "Fetch place failed",
                                error
                            )
                        }
                }
                .addOnFailureListener { error ->

                    Toast.makeText(
                        this,
                        "Places API Error: ${error.message}",
                        Toast.LENGTH_SHORT
                    ).show()

                    Log.e(
                        TAG,
                        "Places autocomplete failed",
                        error
                    )
                }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Police station search failed",
                e
            )

            Toast.makeText(
                this,
                "Police station search failed.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }


    // ============================================================
    // PIN VERIFICATION DIALOG
    // ============================================================

    private fun showPinVerificationDialog() {

        val input =
            EditText(this).apply {

                inputType =
                    InputType.TYPE_CLASS_NUMBER or
                            InputType.TYPE_NUMBER_VARIATION_PASSWORD

                filters =
                    arrayOf(
                        InputFilter.LengthFilter(4)
                    )

                hint =
                    "----"
            }

        val container =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    50,
                    20,
                    50,
                    10
                )

                addView(
                    input
                )
            }

        AlertDialog.Builder(this)
            .setTitle(
                "Security Verification"
            )
            .setMessage(
                "Enter your 4-digit Safety PIN to stop live location sharing:"
            )
            .setView(
                container
            )
            .setPositiveButton(
                "Confirm Stop"
            ) { _, _ ->

                verifyAndStopSharing(
                    input.text.toString()
                )
            }
            .setNegativeButton(
                "Cancel",
                null
            )
            .show()
    }


    // ============================================================
    // VERIFY PIN + STOP SHARING
    // ============================================================

    private fun verifyAndStopSharing(
        enteredPin: String
    ) {

        try {

            if (enteredPin.length != 4) {

                Toast.makeText(
                    this,
                    "PIN must be exactly 4 digits.",
                    Toast.LENGTH_SHORT
                ).show()

                return
            }

            val securePrefs =
                getSecurePreferences()

            val savedPin =
                securePrefs.getString(
                    "safety_pin",
                    null
                )

            if (
                savedPin.isNullOrEmpty() ||
                enteredPin != savedPin
            ) {

                Toast.makeText(
                    this,
                    "Incorrect PIN! Location sharing remains active.",
                    Toast.LENGTH_LONG
                ).show()

                return
            }

            stopEmergencySharing()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "PIN verification error",
                e
            )

            Toast.makeText(
                this,
                "Verification error: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }


    // ============================================================
    // STOP EMERGENCY SHARING
    // ============================================================

    private fun stopEmergencySharing() {

        val prefs =
            getSharedPreferences(
                "app_prefs",
                MODE_PRIVATE
            )

        prefs.edit()
            .putBoolean(
                "is_public_mode",
                false
            )
            .apply()

        updateInterfaceVisibility(
            false
        )

        val database =
            FirebaseDatabase
                .getInstance()

        val emergencyId =
            prefs.getString(
                "active_emergency_id",
                null
            )

        if (!emergencyId.isNullOrBlank()) {

            updateEmergencyById(
                database,
                emergencyId
            )

            return
        }

        val securePrefs =
            getSecurePreferences()

        val activeNumber =
            securePrefs.getString(
                "guardian_number",
                ""
            ) ?: ""

        if (activeNumber.isBlank()) {

            Toast.makeText(
                this,
                "PIN verified. No active emergency was found.",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        database
            .getReference(
                "emergencies"
            )
            .orderByChild(
                "phone"
            )
            .equalTo(
                activeNumber
            )
            .get()
            .addOnSuccessListener { snapshot ->

                if (!snapshot.exists()) {

                    Toast.makeText(
                        this,
                        "PIN verified. No active emergency found.",
                        Toast.LENGTH_LONG
                    ).show()

                    return@addOnSuccessListener
                }

                var updated =
                    false

                for (
                child in snapshot.children
                ) {

                    updateEmergencyChild(
                        child.ref
                    )

                    updated = true
                }

                if (updated) {

                    Toast.makeText(
                        this,
                        "PIN verified. Emergency alert terminated securely.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .addOnFailureListener { error ->

                Toast.makeText(
                    this,
                    "Firebase update failed: ${error.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
    }


    // ============================================================
    // UPDATE EMERGENCY BY ID
    // ============================================================

    private fun updateEmergencyById(
        database: FirebaseDatabase,
        emergencyId: String
    ) {

        database
            .getReference(
                "emergencies"
            )
            .child(
                emergencyId
            )
            .updateChildren(
                mapOf(
                    "sharing_active" to false,
                    "victim_toggle_tile" to false,
                    "termination_source" to "APP_SECURE_PIN"
                )
            )
            .addOnSuccessListener {

                Toast.makeText(
                    this,
                    "PIN verified. Emergency alert terminated securely.",
                    Toast.LENGTH_LONG
                ).show()
            }
            .addOnFailureListener { error ->

                Toast.makeText(
                    this,
                    "Firebase update failed: ${error.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
    }


    // ============================================================
    // UPDATE EMERGENCY CHILD
    // ============================================================

    private fun updateEmergencyChild(
        reference: DatabaseReference
    ) {

        reference.updateChildren(
            mapOf(
                "sharing_active" to false,
                "victim_toggle_tile" to false,
                "termination_source" to "APP_SECURE_PIN"
            )
        )
    }


    // ============================================================
    // CREATE SAFETY PIN
    // ============================================================

    private fun promptUserToSetPin() {

        val input =
            EditText(this).apply {

                inputType =
                    InputType.TYPE_CLASS_NUMBER or
                            InputType.TYPE_NUMBER_VARIATION_PASSWORD

                filters =
                    arrayOf(
                        InputFilter.LengthFilter(4)
                    )

                hint =
                    "----"
            }

        val container =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    50,
                    20,
                    50,
                    10
                )

                addView(
                    input
                )
            }

        AlertDialog.Builder(this)
            .setTitle(
                "Create Safety PIN"
            )
            .setMessage(
                "Enter a 4-digit PIN. You will need this PIN to stop an emergency alert."
            )
            .setView(
                container
            )
            .setPositiveButton(
                "Save PIN"
            ) { _, _ ->

                val newPin =
                    input.text.toString()

                if (newPin.length == 4) {

                    saveSafetyPin(
                        newPin
                    )

                } else {

                    Toast.makeText(
                        this,
                        "PIN must be exactly 4 digits.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(
                "Cancel",
                null
            )
            .show()
    }


    // ============================================================
    // SAVE PIN
    // ============================================================

    private fun saveSafetyPin(
        customPin: String
    ) {

        try {

            getSecurePreferences()
                .edit()
                .putString(
                    "safety_pin",
                    customPin
                )
                .apply()

            Toast.makeText(
                this,
                "Safety PIN saved successfully!",
                Toast.LENGTH_SHORT
            ).show()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Error saving PIN",
                e
            )

            Toast.makeText(
                this,
                "Error saving PIN: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }


    // ============================================================
    // CHECK PIN
    // ============================================================

    private fun checkIfPinIsSet() {

        try {

            val securePrefs =
                getSecurePreferences()

            val existingPin =
                securePrefs.getString(
                    "safety_pin",
                    null
                )

            if (existingPin.isNullOrEmpty()) {

                promptUserToSetPin()
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "PIN check error",
                e
            )
        }
    }


    // ============================================================
    // ACTIVITY RESUME
    // ============================================================

    override fun onResume() {

        super.onResume()

        if (
            ::aiEngine.isInitialized &&
            !voiceSessionActive &&
            !emergencyInProgress.get()
        ) {

            try {

                if (
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                ) {

                    aiEngine.startListening()
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Unable to resume AI listening",
                    e
                )
            }
        }
    }


    // ============================================================
    // ACTIVITY PAUSE
    // ============================================================

    override fun onPause() {

        super.onPause()
    }


    // ============================================================
    // ACTIVITY DESTROY
    // ============================================================

    override fun onDestroy() {

        try {

            if (::aiEngine.isInitialized) {

                voiceSessionActive = false

                aiEngine.terminateEngine()
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Error terminating AI engine",
                e
            )
        }

        mMap = null

        super.onDestroy()
    }
}