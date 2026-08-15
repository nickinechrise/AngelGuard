package com.example.angelguard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class CallTrampolineActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CallTrampolineActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Get guardian number from the emergency flow
        val phoneNumber = intent.getStringExtra("phone_number")
            ?.trim()

        if (phoneNumber.isNullOrEmpty()) {
            Log.e(TAG, "No guardian phone number received.")
            finish()
            return
        }

        makeGuardianCall(phoneNumber)
    }

    private fun makeGuardianCall(phoneNumber: String) {

        val callUri = Uri.parse(
            "tel:${Uri.encode(phoneNumber)}"
        )

        // Direct CALL requires CALL_PHONE permission
        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED
        ) {

            try {

                val callIntent = Intent(
                    Intent.ACTION_CALL,
                    callUri
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                startActivity(callIntent)

                Log.d(
                    TAG,
                    "Guardian call started: $phoneNumber"
                )

            } catch (e: SecurityException) {

                Log.e(
                    TAG,
                    "CALL_PHONE permission/security error",
                    e
                )

                openDialer(callUri)

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Unable to start guardian call",
                    e
                )

                openDialer(callUri)
            }

        } else {

            // No CALL_PHONE permission.
            // Open the dialer instead of crashing.
            Log.w(
                TAG,
                "CALL_PHONE permission not granted."
            )

            openDialer(callUri)
        }

        // This activity is only a trampoline.
        finish()
    }

    private fun openDialer(
        callUri: Uri
    ) {

        try {

            val dialIntent = Intent(
                Intent.ACTION_DIAL,
                callUri
            )

            startActivity(dialIntent)

            Log.d(
                TAG,
                "Dialer opened."
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Unable to open phone dialer",
                e
            )
        }
    }
}