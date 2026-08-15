package com.example.angelguard

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.IBinder
import com.google.firebase.database.FirebaseDatabase

class BatteryService : Service() {

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val batteryPct = ((level.toFloat() / scale.toFloat()) * 100).toInt()

            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val isEmergencyActive = prefs.getBoolean("is_public_mode", false) // or victim_toggle_tile state
            val emergencyId = prefs.getString("active_emergency_id", null)

            // 1. Push battery telemetry if an emergency session is active
            if (isEmergencyActive && !emergencyId.isNullOrEmpty()) {
                val dbRef = FirebaseDatabase.getInstance().getReference("emergencies/$emergencyId")
                dbRef.child("victim_battery").setValue(batteryPct)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(batteryReceiver)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}