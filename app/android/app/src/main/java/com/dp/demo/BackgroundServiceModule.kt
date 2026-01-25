package com.dp.demo

import android.content.Context
import android.content.Intent
import android.os.Build
import com.facebook.react.bridge.*
import org.json.JSONArray
import java.io.File

/**
 * BackgroundServiceModule - React Native bridge for controlling the background sensor service.
 * 
 * Provides JavaScript API for:
 * - Starting/stopping the background service
 * - Checking service status
 * - Reading collected sensor data from log files
 */
class BackgroundServiceModule(private val reactContext: ReactApplicationContext) : 
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        private const val TAG = "BackgroundServiceModule"
    }

    override fun getName(): String = "BackgroundService"

    @ReactMethod
    fun startService(promise: Promise) {
        try {
            val intent = Intent(reactContext, SensorCollectorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                reactContext.startForegroundService(intent)
            } else {
                reactContext.startService(intent)
            }
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("E_START_SERVICE", "Failed to start service: ${e.message}", e)
        }
    }

    @ReactMethod
    fun stopService(promise: Promise) {
        try {
            val intent = Intent(reactContext, SensorCollectorService::class.java)
            reactContext.stopService(intent)
            
            // Clear both running state AND auto-start preference when user explicitly stops
            reactContext.getSharedPreferences("dp_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean(SensorCollectorService.PREF_SERVICE_ENABLED, false)
                .putBoolean(SensorCollectorService.PREF_AUTO_START_ON_BOOT, false)
                .putBoolean(SensorCollectorService.PREF_SERVICE_RUNNING, false)
                .apply()
            
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("E_STOP_SERVICE", "Failed to stop service: ${e.message}", e)
        }
    }

    @ReactMethod
    fun isServiceRunning(promise: Promise) {
        try {
            val prefs = reactContext.getSharedPreferences("dp_prefs", Context.MODE_PRIVATE)
            val isRunning = prefs.getBoolean(SensorCollectorService.PREF_SERVICE_ENABLED, false)
            promise.resolve(isRunning)
        } catch (e: Exception) {
            promise.reject("E_CHECK_STATUS", "Failed to check service status: ${e.message}", e)
        }
    }

    @ReactMethod
    fun getCollectedData(sensorType: String, promise: Promise) {
        try {
            val filename = when (sensorType.lowercase()) {
                "location" -> "location-events.log"
                "accelerometer", "accel" -> "accelerometer-events.log"
                "gyroscope", "gyro" -> "gyroscope-events.log"
                "pedometer", "steps" -> "pedometer-events.log"
                else -> {
                    promise.reject("E_INVALID_SENSOR", "Unknown sensor type: $sensorType")
                    return
                }
            }
            
            val file = File(reactContext.filesDir, filename)
            if (!file.exists()) {
                promise.resolve(Arguments.createArray())
                return
            }
            
            val lines = file.readLines()
            val result = Arguments.createArray()
            
            // Return last 100 entries to avoid memory issues
            val recentLines = lines.takeLast(100)
            for (line in recentLines) {
                if (line.isBlank()) continue
                try {
                    val json = org.json.JSONObject(line)
                    val map = Arguments.createMap()
                    json.keys().forEach { key ->
                        when (val value = json.get(key)) {
                            is String -> map.putString(key, value)
                            is Int -> map.putInt(key, value)
                            is Long -> map.putDouble(key, value.toDouble())
                            is Double -> map.putDouble(key, value)
                            is Float -> map.putDouble(key, value.toDouble())
                            is Boolean -> map.putBoolean(key, value)
                            else -> map.putString(key, value.toString())
                        }
                    }
                    result.pushMap(map)
                } catch (_: Exception) {
                    // Skip malformed lines
                }
            }
            
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("E_READ_DATA", "Failed to read sensor data: ${e.message}", e)
        }
    }

    @ReactMethod
    fun clearCollectedData(sensorType: String, promise: Promise) {
        try {
            val filename = when (sensorType.lowercase()) {
                "location" -> "location-events.log"
                "accelerometer", "accel" -> "accelerometer-events.log"
                "gyroscope", "gyro" -> "gyroscope-events.log"
                "pedometer", "steps" -> "pedometer-events.log"
                "all" -> null
                else -> {
                    promise.reject("E_INVALID_SENSOR", "Unknown sensor type: $sensorType")
                    return
                }
            }
            
            if (filename == null) {
                // Clear all logs
                listOf(
                    "location-events.log",
                    "accelerometer-events.log",
                    "gyroscope-events.log",
                    "pedometer-events.log"
                ).forEach { name ->
                    File(reactContext.filesDir, name).delete()
                }
            } else {
                File(reactContext.filesDir, filename).delete()
            }
            
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("E_CLEAR_DATA", "Failed to clear sensor data: ${e.message}", e)
        }
    }

    @ReactMethod
    fun setAutoStartOnBoot(enabled: Boolean, promise: Promise) {
        try {
            reactContext.getSharedPreferences("dp_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean(SensorCollectorService.PREF_SERVICE_ENABLED, enabled)
                .apply()
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("E_SET_AUTOSTART", "Failed to set auto-start preference: ${e.message}", e)
        }
    }

    /**
     * Returns status info for each sensor log file: exists, size, last modified, entry count.
     * Useful for verifying background collection is working.
     */
    @ReactMethod
    fun getDataStatus(promise: Promise) {
        try {
            val result = Arguments.createMap()
            val logFiles = mapOf(
                "location" to "location-events.log",
                "accelerometer" to "accelerometer-events.log",
                "gyroscope" to "gyroscope-events.log",
                "pedometer" to "pedometer-events.log"
            )
            
            for ((sensor, filename) in logFiles) {
                val file = File(reactContext.filesDir, filename)
                val info = Arguments.createMap()
                
                if (file.exists()) {
                    info.putBoolean("exists", true)
                    info.putDouble("sizeBytes", file.length().toDouble())
                    info.putDouble("lastModified", file.lastModified().toDouble())
                    
                    // Count lines (entries)
                    val lineCount = try { file.readLines().count { it.isNotBlank() } } catch (_: Exception) { 0 }
                    info.putInt("entryCount", lineCount)
                } else {
                    info.putBoolean("exists", false)
                    info.putDouble("sizeBytes", 0.0)
                    info.putDouble("lastModified", 0.0)
                    info.putInt("entryCount", 0)
                }
                
                result.putMap(sensor, info)
            }
            
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("E_DATA_STATUS", "Failed to get data status: ${e.message}", e)
        }
    }

    /**
     * Set the device ID used for backend API calls
     */
    @ReactMethod
    fun setDeviceId(deviceId: String, promise: Promise) {
        try {
            BackendAPIClient.setDeviceId(reactContext, deviceId)
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("E_SET_DEVICE_ID", "Failed to set device ID: ${e.message}", e)
        }
    }

    /**
     * Get the current device ID
     */
    @ReactMethod
    fun getDeviceId(promise: Promise) {
        try {
            val deviceId = BackendAPIClient.getDeviceId(reactContext)
            promise.resolve(deviceId)
        } catch (e: Exception) {
            promise.reject("E_GET_DEVICE_ID", "Failed to get device ID: ${e.message}", e)
        }
    }

    /**
     * Set the backend API base URL
     */
    @ReactMethod
    fun setAPIBaseURL(url: String, promise: Promise) {
        try {
            BackendAPIClient.setApiBase(reactContext, url)
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("E_SET_API_URL", "Failed to set API base URL: ${e.message}", e)
        }
    }
}
