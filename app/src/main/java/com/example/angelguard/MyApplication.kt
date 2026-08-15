package com.example.angelguard

import android.app.Application
import com.google.android.libraries.places.api.Places

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!Places.isInitialized()) {
            Places.initialize(applicationContext, "AIzaSyC1X2e_t_qeT839BK76laxXHE_WK12EmeA")
        }
    }
}