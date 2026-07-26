package com.example.angelguard

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri // Use this KTX extension to fix the warning

class CallTrampolineActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Get the number passed from the Service
        val phoneNumber = intent.getStringExtra("phone_number")

        // 2. Launch the Call directly
        if (!phoneNumber.isNullOrEmpty()) {
            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = "tel:$phoneNumber".toUri() // Using KTX extension
            }

            try {
                startActivity(callIntent)
            } catch (e: SecurityException) {
                // If permission fails, fallback to DIAL so the app doesn't crash
                val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                    data = "tel:$phoneNumber".toUri()
                }
                startActivity(dialIntent)
            }
        }

        // 3. Close the trampoline
        finish()
    }
}