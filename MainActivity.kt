package com.example.angelguard

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.work.*
import android.content.Context
import android.view.View
import android.view.Menu
import android.view.MenuItem
// Google Maps Imports
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.LatLng
import android.location.Location
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.model.RectangularBounds
import android.app.Activity
import android.content.Intent


class MainActivity : AppCompatActivity(), OnMapReadyCallback {
    private lateinit var btnSave: Button
    private lateinit var tvActiveNumberDisplay: TextView
    private lateinit var etGuardianNumber: EditText
    private lateinit var historyContainer: LinearLayout
    private lateinit var tvNoHistory: TextView
    private lateinit var ccp: com.hbb20.CountryCodePicker
    private lateinit var guardianModeLayout: LinearLayout
    private lateinit var publicDispatchLayout: LinearLayout
    private lateinit var btnCancelAlert: Button
    private lateinit var btnPickContact: Button // ADDED: Button for searching/picking contact
    private lateinit var btnStopLiveLocation: Button // ADDED: Dedicated visible button to stop live location


    private var mMap: GoogleMap? = null

    // ADDED: Activity result launcher for native contact picker
    private val contactPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { contactUri ->
                val cursor = contentResolver.query(contactUri, arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ), null, null, null)

                cursor?.use {
                    if (it.moveToFirst()) {
                        val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                        val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                        val guardianName = if (nameIndex != -1) it.getString(nameIndex) else "Guardian"
                        val guardianNumber = if (numberIndex != -1) it.getString(numberIndex) else ""

                        if (!guardianNumber.isNullOrEmpty()) {
                            val cleanNum = guardianNumber.replace(Regex("[^+\\d]"), "")
                            android.app.AlertDialog.Builder(this)
                                .setTitle("Set Active Guardian")
                                .setMessage("Do you want to set $guardianName ($cleanNum) as your active guardian?")
                                .setPositiveButton("Yes") { _, _ ->
                                    etGuardianNumber.setText(cleanNum)
                                    saveNewGuardianNumber(cleanNum)
                                    Toast.makeText(this, "Selected: $guardianName", Toast.LENGTH_SHORT).show()
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                    }
                }
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) refreshUI()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_DarkActionBar)
        setContentView(R.layout.activity_main)

        // 1. Initialize Views FIRST
        guardianModeLayout = findViewById(R.id.layoutGuardianMode)
        publicDispatchLayout = findViewById(R.id.layoutPublicDispatch)
        btnCancelAlert = findViewById(R.id.btnCancelAlert)
        btnStopLiveLocation = findViewById(R.id.btnStopLiveLocation) // ADDED: Initialize visible stop button
        tvActiveNumberDisplay = findViewById(R.id.tvActiveNumberDisplay)
        etGuardianNumber = findViewById(R.id.etGuardianNumber)
        historyContainer = findViewById(R.id.historyContainer)
        tvNoHistory = findViewById(R.id.tvNoHistory)
        ccp = findViewById(R.id.ccp)
        btnSave = findViewById(R.id.btnSave)

        // 2. Now you can safely set click listeners on it
        btnCancelAlert.setOnClickListener {
            showPinVerificationDialog()
        }

        // ADDED: Click listener for the main screen visible stop live location button
        btnStopLiveLocation.setOnClickListener {
            showPinVerificationDialog()
        }

        // 3. Setup Map Fragment
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as? SupportMapFragment
        mapFragment?.getMapAsync(this)

        // 4. Setup Logic
        ccp.registerCarrierNumberEditText(etGuardianNumber)

        etGuardianNumber.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
            contactPickerLauncher.launch(intent)
        }

        btnSave.setOnClickListener {
            if (ccp.isValidFullNumber) {
                saveNewGuardianNumber(ccp.fullNumberWithPlus)
            } else {
                etGuardianNumber.error = "Invalid phone number"
            }
        }

        // 5. Background Initialization
        lifecycleScope.launch {
            com.google.firebase.FirebaseApp.initializeApp(this@MainActivity)
            checkAndRequestPermissions()
            refreshUI()
        }

        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val isPublic = prefs.getBoolean("is_public_mode", false)
        updateInterfaceVisibility(isPublic)

        checkIfPinIsSet()
    }

    // ADDED: Method to handle contact searching/picking intent securely
    public fun openContactPicker() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS), 102)
        } else {
            val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
            contactPickerLauncher.launch(intent)
        }
    }

    // Google Maps Callback
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
    }

    private fun updateInterfaceVisibility(isPublicMode: Boolean) {
        if (isPublicMode) {
            publicDispatchLayout.visibility = View.VISIBLE
            guardianModeLayout.visibility = View.GONE
        } else {
            publicDispatchLayout.visibility = View.GONE
            guardianModeLayout.visibility = View.VISIBLE
        }
    }

    private fun triggerSos() {
        val vibrator = getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(android.os.VibrationEffect.createOneShot(500, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator.vibrate(500)
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val smsWorkRequest = OneTimeWorkRequestBuilder<SmsWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(this).enqueueUniqueWork(
            "sos_work",
            ExistingWorkPolicy.REPLACE,
            smsWorkRequest
        )

        Toast.makeText(this, "SOS Queued. Phone will vibrate on trigger.", Toast.LENGTH_LONG).show()
    }

    private fun getContactName(phoneNumber: String): String {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return phoneNumber
        }
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                if (index != -1) return cursor.getString(index)
            }
        }
        return phoneNumber
    }

    private fun refreshUI() {
        try {
            val masterKey = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val securePrefs = EncryptedSharedPreferences.create(
                this, "secure_guardian_prefs", masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            val activeNumber = securePrefs.getString("guardian_number", "")
            if (!activeNumber.isNullOrEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val name = getContactName(activeNumber)
                    withContext(Dispatchers.Main) {
                        tvActiveNumberDisplay.text = "Active Guardian: $name"
                    }
                }
                etGuardianNumber.setText(activeNumber)
            } else {
                tvActiveNumberDisplay.text = "Active Guardian: None"
            }

            historyContainer.removeAllViews()
            val historyString = securePrefs.getString("guardian_history", "") ?: ""
            val historyList = historyString.split(",").filter { it.isNotEmpty() }

            if (historyList.isEmpty()) {
                historyContainer.addView(tvNoHistory)
            } else {
                for (num in historyList) {
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 16) }
                        setPadding(32, 24, 32, 24)
                        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.edit_text_bg)
                        setOnClickListener { saveNewGuardianNumber(num, true) }
                        setOnLongClickListener {
                            val builder = android.app.AlertDialog.Builder(this@MainActivity)
                            builder.setTitle("Manage Number")
                            builder.setMessage("What would you like to do with this number?")
                            builder.setPositiveButton("Swap to Active") { _, _ -> saveNewGuardianNumber(num, true) }
                            builder.setNegativeButton("Delete") { _, _ -> deleteHistoryItem(num) }
                            builder.setNeutralButton("Cancel", null)
                            val dialog = builder.create()
                            dialog.show()
                            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setTextColor(Color.parseColor("#3B3B98"))
                            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.parseColor("#FF0000"))
                            true
                        }
                    }
                    val tvNum = TextView(this).apply {
                        textSize = 15f
                        setTextColor(Color.parseColor("#3B3B98"))
                    }
                    lifecycleScope.launch(Dispatchers.IO) {
                        val name = getContactName(num)
                        withContext(Dispatchers.Main) { tvNum.text = name }
                    }
                    row.addView(tvNum)
                    historyContainer.addView(row)
                }
            }
        } catch (e: Exception) {
            tvActiveNumberDisplay.text = "Error loading data."
        }
    }

    private fun saveNewGuardianNumber(newNumber: String, isSwap: Boolean = false) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val masterKey = MasterKey.Builder(this@MainActivity).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                val sharedPreferences = EncryptedSharedPreferences.create(
                    this@MainActivity, "secure_guardian_prefs", masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                val editor = sharedPreferences.edit()
                val currentActive = sharedPreferences.getString("guardian_number", "") ?: ""
                if (currentActive.isNotEmpty() && currentActive != newNumber) {
                    val historyString = sharedPreferences.getString("guardian_history", "") ?: ""
                    val historyList = historyString.split(",").filter { it.isNotEmpty() }.toMutableList()
                    if (!historyList.contains(currentActive)) {
                        historyList.add(0, currentActive)
                    }
                    editor.putString("guardian_history", historyList.take(4).joinToString(","))
                }
                editor.putString("guardian_number", newNumber)
                editor.commit()
                withContext(Dispatchers.Main) {
                    refreshUI()
                    Toast.makeText(this@MainActivity, if (isSwap) "Swapped to: $newNumber" else "Saved!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                val file = File(this@MainActivity.filesDir.parent + "/shared_prefs/secure_guardian_prefs.xml")
                if (file.exists()) file.delete()
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsNeeded = mutableListOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
        val ungranted = permissionsNeeded.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (ungranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, ungranted.toTypedArray(), 101)
        }
    }

    private fun deleteHistoryItem(numberToDelete: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val masterKey = MasterKey.Builder(this@MainActivity).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                val prefs = EncryptedSharedPreferences.create(
                    this@MainActivity, "secure_guardian_prefs", masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                val historyString = prefs.getString("guardian_history", "") ?: ""
                val historyList = historyString.split(",").filter { it.isNotEmpty() }.toMutableList()
                if (historyList.remove(numberToDelete)) {
                    prefs.edit().putString("guardian_history", historyList.joinToString(",")).apply()
                    withContext(Dispatchers.Main) {
                        refreshUI()
                        Toast.makeText(this@MainActivity, "Deleted from history", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun switchMode(isPublic: Boolean) {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_public_mode", isPublic).apply()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    private fun showPinVerificationDialog() {
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("Security Verification")
        builder.setMessage("Enter your 4-digit Safety PIN to stop live location sharing:")

        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(android.text.InputFilter.LengthFilter(4))
            hint = "----"
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 20, 50, 10)
            addView(input)
        }
        builder.setView(container)

        builder.setPositiveButton("Confirm Stop") { _, _ ->
            val enteredPin = input.text.toString()
            verifyAndStopSharing(enteredPin)
        }
        builder.setNegativeButton("Cancel", null)

        val dialog = builder.create()
        dialog.show()
    }

    private fun verifyAndStopSharing(enteredPin: String) {
        try {
            val masterKey = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val securePrefs = EncryptedSharedPreferences.create(
                this, "secure_guardian_prefs", masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            val savedPin = securePrefs.getString("safety_pin", "1234")

            if (enteredPin == savedPin) {
                val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("is_public_mode", false).apply()
                updateInterfaceVisibility(false)

                val database = com.google.firebase.database.FirebaseDatabase.getInstance()

                // Fetch the explicitly saved emergency unique ID to perform a direct update (avoids search/index rule failures)
                val emergencyId = prefs.getString("active_emergency_id", null)

                if (!emergencyId.isNullOrEmpty()) {
                    database.getReference("emergencies").child(emergencyId)
                        .updateChildren(mapOf(
                            "sharing_active" to false,
                            "victim_toggle_tile" to false,
                            "termination_source" to "APP_SECURE_PIN"
                        ))
                        .addOnSuccessListener {
                            Toast.makeText(this, "PIN Verified. Emergency Alert Terminated Securely.", Toast.LENGTH_LONG).show()
                        }
                        .addOnFailureListener { e ->
                            Toast.makeText(this, "Failed to update Firebase: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                } else {
                    // Fallback to query method if direct ID wasn't stored locally
                    val activeNumber = securePrefs.getString("guardian_number", "")
                    if (!activeNumber.isNullOrEmpty()) {
                        database.getReference("emergencies")
                            .orderByChild("phone")
                            .equalTo(activeNumber)
                            .get()
                            .addOnSuccessListener { snapshot ->
                                for (child in snapshot.children) {
                                    child.ref.updateChildren(mapOf(
                                        "sharing_active" to false,
                                        "victim_toggle_tile" to false,
                                        "termination_source" to "APP_SECURE_PIN"
                                    ))
                                }
                            }
                    }
                    Toast.makeText(this, "PIN Verified. Emergency Alert Terminated Securely.", Toast.LENGTH_LONG).show()
                }

            } else {
                Toast.makeText(this, "Incorrect PIN! Location sharing remains active.", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Verification error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun promptUserToSetPin() {
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("Create Safety PIN")
        builder.setMessage("Enter a 4-digit PIN. You will need this PIN to stop an emergency alert.")

        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(android.text.InputFilter.LengthFilter(4))
            hint = "----"
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 20, 50, 10)
            addView(input)
        }
        builder.setView(container)

        builder.setPositiveButton("Save PIN") { _, _ ->
            val newPin = input.text.toString()
            if (newPin.length == 4) {
                saveSafetyPin(newPin)
            } else {
                Toast.makeText(this, "PIN must be exactly 4 digits.", Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    private fun saveSafetyPin(customPin: String) {
        try {
            val masterKey = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val securePrefs = EncryptedSharedPreferences.create(
                this, "secure_guardian_prefs", masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            securePrefs.edit().putString("safety_pin", customPin).apply()
            Toast.makeText(this, "Safety PIN Saved Successfully!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error saving PIN: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkIfPinIsSet() {
        try {
            val masterKey = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val securePrefs = EncryptedSharedPreferences.create(
                this, "secure_guardian_prefs", masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            val existingPin = securePrefs.getString("safety_pin", null)
            if (existingPin.isNullOrEmpty()) {
                promptUserToSetPin()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_toggle_mode -> {
                val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val currentMode = prefs.getBoolean("is_public_mode", false)
                val newMode = !currentMode

                prefs.edit().putBoolean("is_public_mode", newMode).apply()
                updateInterfaceVisibility(newMode)

                if (newMode) {
                    val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                            if (location != null) {
                                findNearbyPoliceStation(location.latitude, location.longitude)
                            } else {
                                Toast.makeText(this, "Location not found, please ensure GPS is on.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        Toast.makeText(this, "Location permission missing", Toast.LENGTH_SHORT).show()
                    }
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun findNearbyPoliceStation(lat: Double, lng: Double) {
        val placesClient = Places.createClient(this)

        val request = FindAutocompletePredictionsRequest.builder()
            .setQuery("police station")
            .setLocationBias(RectangularBounds.newInstance(
                LatLng(lat - 0.02, lng - 0.02),
                LatLng(lat + 0.02, lng + 0.02)
            ))
            .build()

        placesClient.findAutocompletePredictions(request).addOnSuccessListener { response ->
            if (response.autocompletePredictions.isNotEmpty()) {
                val prediction = response.autocompletePredictions[0]

                val placeFields = listOf(Place.Field.NAME, Place.Field.LAT_LNG, Place.Field.ADDRESS)
                val fetchRequest = FetchPlaceRequest.newInstance(prediction.placeId, placeFields)

                placesClient.fetchPlace(fetchRequest).addOnSuccessListener { fetchResponse ->
                    val place = fetchResponse.place
                    val stationLatLng = place.latLng ?: return@addOnSuccessListener

                    mMap?.addMarker(com.google.android.gms.maps.model.MarkerOptions()
                        .position(stationLatLng)
                        .title(place.name))

                    val results = FloatArray(1)
                    Location.distanceBetween(lat, lng, stationLatLng.latitude, stationLatLng.longitude, results)
                    val distanceKm = results[0] / 1000

                    Toast.makeText(this, "Nearest: ${place.name} (${"%.2f".format(distanceKm)} km away)", Toast.LENGTH_LONG).show()
                    mMap?.animateCamera(com.google.android.gms.maps.CameraUpdateFactory.newLatLngZoom(stationLatLng, 14f))
                }
            } else {
                Toast.makeText(this, "No police stations found nearby.", Toast.LENGTH_SHORT).show()
            }
        }.addOnFailureListener {
            Toast.makeText(this, "API Error: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }
}