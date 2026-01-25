package com.dp.demo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * BootReceiver - Starts SensorCollectorService after device boot.
 * Uses longer delay and multiple retry attempts to handle slow boot scenarios.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val INITIAL_DELAY_MS = 30000L // 30 seconds after boot
        private const val RETRY_DELAY_MS = 15000L // 15 seconds between retries
        private const val MAX_RETRIES = 3
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) {
            Log.e(TAG, "Context or intent is null")
            return
        }
        
        val action = intent.action ?: return
        
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "android.intent.action.LOCKED_BOOT_COMPLETED") {
            
            Log.d(TAG, "Boot completed, action: $action")
            
            try {
                val prefs = context.getSharedPreferences("dp_prefs", Context.MODE_PRIVATE)
                // Check the auto-start preference (not the running state)
                val autoStartEnabled = prefs.getBoolean(SensorCollectorService.PREF_AUTO_START_ON_BOOT, false)
                
                if (autoStartEnabled) {
                    Log.d(TAG, "Auto-start on boot is enabled, scheduling service start")
                    scheduleServiceStart(context.applicationContext, INITIAL_DELAY_MS, 0)
                } else {
                    Log.d(TAG, "Auto-start on boot is disabled, skipping")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in boot receiver", e)
            }
        }
    }
    
    private fun scheduleServiceStart(context: Context, delayMs: Long, attempt: Int) {
        try {
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    startServiceSafely(context, attempt)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start service on attempt $attempt", e)
                    if (attempt < MAX_RETRIES) {
                        Log.d(TAG, "Scheduling retry ${attempt + 1}")
                        scheduleServiceStart(context, RETRY_DELAY_MS, attempt + 1)
                    }
                }
            }, delayMs)
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling service start", e)
        }
    }
    
    private fun startServiceSafely(context: Context, attempt: Int) {
        try {
            Log.d(TAG, "Starting service, attempt: $attempt")
            val serviceIntent = Intent(context, SensorCollectorService::class.java)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.d(TAG, "SensorCollectorService started successfully after boot")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SensorCollectorService on attempt $attempt", e)
            throw e // Re-throw to trigger retry
        }
    }
}
