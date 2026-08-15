package com.example.angelguard

import android.app.Application
import com.google.firebase.FirebaseApp

class AngelGuardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize Firebase here - this runs before any Activity or Receiver
        FirebaseApp.initializeApp(this)
    }
}