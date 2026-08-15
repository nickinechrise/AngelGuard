package com.example.angelguard

import android.content.Context
import android.telephony.SmsManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SmsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val masterKey = MasterKey.Builder(applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        val prefs = EncryptedSharedPreferences.create(
            applicationContext, "secure_guardian_prefs", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        val guardianNumber = prefs.getString("guardian_number", null)

        if (guardianNumber.isNullOrEmpty()) {
            return Result.failure()
        }

        return try {
            // Updated context-safe initialization for modern Android
            val smsManager = applicationContext.getSystemService(SmsManager::class.java)
            smsManager?.sendTextMessage(guardianNumber, null, "SOS: Emergency Alert from AngelGuard. Location queued for delivery.", null, null)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}