package com.dp.demo

import android.content.Context
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * BackendAPIClient - Singleton HTTP client for sending sensor data to backend.
 * Uses OkHttp for reliable background network operations.
 */
object BackendAPIClient {
    
    private const val TAG = "BackendAPIClient"
    private const val PREF_NAME = "dp_prefs"
    private const val PREF_API_BASE = "api_base_url"
    private const val PREF_DEVICE_ID = "device_id"
    private const val DEFAULT_API_BASE = "http://192.168.10.3:8080/api"
    
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
    
    private fun getApiBase(context: Context): String {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(PREF_API_BASE, DEFAULT_API_BASE) ?: DEFAULT_API_BASE
    }
    
    fun setApiBase(context: Context, url: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_API_BASE, url)
            .apply()
    }
    
    fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        var deviceId = prefs.getString(PREF_DEVICE_ID, null)
        
        if (deviceId == null) {
            // Generate a unique device ID
            deviceId = "android_${android.os.Build.MODEL}_${System.currentTimeMillis()}"
            prefs.edit().putString(PREF_DEVICE_ID, deviceId).apply()
        }
        
        return deviceId
    }
    
    fun setDeviceId(context: Context, deviceId: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_DEVICE_ID, deviceId)
            .apply()
    }
    
    /**
     * Send accelerometer data to backend
     */
    fun sendAccelerometer(
        context: Context,
        ts: Long,
        x: Float,
        y: Float,
        z: Float,
        magnitude: Float
    ) {
        val deviceId = getDeviceId(context)
        val apiBase = getApiBase(context)
        
        val json = JSONObject().apply {
            put("device_id", deviceId)
            put("ts", ts)
            put("x", x)
            put("y", y)
            put("z", z)
            put("magnitude", magnitude)
        }
        
        sendRequest("$apiBase/accelerometer", json)
    }
    
    /**
     * Send gyroscope data to backend
     */
    fun sendGyroscope(
        context: Context,
        ts: Long,
        x: Float,
        y: Float,
        z: Float,
        magnitude: Float
    ) {
        val deviceId = getDeviceId(context)
        val apiBase = getApiBase(context)
        
        val json = JSONObject().apply {
            put("device_id", deviceId)
            put("ts", ts)
            put("x", x)
            put("y", y)
            put("z", z)
            put("magnitude", magnitude)
        }
        
        sendRequest("$apiBase/gyroscope", json)
    }
    
    /**
     * Send location data to backend
     */
    fun sendLocation(
        context: Context,
        ts: Long,
        latitude: Double,
        longitude: Double,
        accuracy: Float,
        altitude: Double,
        speed: Float
    ) {
        val deviceId = getDeviceId(context)
        val apiBase = getApiBase(context)
        
        val json = JSONObject().apply {
            put("device_id", deviceId)
            put("ts", ts)
            put("latitude", latitude)
            put("longitude", longitude)
            put("accuracy", accuracy)
            put("altitude", altitude)
            put("speed", speed)
        }
        
        sendRequest("$apiBase/location", json)
    }
    
    /**
     * Send pedometer data to backend
     */
    fun sendPedometer(
        context: Context,
        ts: Long,
        steps: Int
    ) {
        val deviceId = getDeviceId(context)
        val apiBase = getApiBase(context)
        
        val json = JSONObject().apply {
            put("device_id", deviceId)
            put("ts", ts)
            put("steps", steps)
        }
        
        sendRequest("$apiBase/pedometer", json)
    }
    
    /**
     * Send battery data to backend
     */
    fun sendBattery(
        context: Context,
        ts: Long,
        percentage: Int,
        chargingStatus: String
    ) {
        val deviceId = getDeviceId(context)
        val apiBase = getApiBase(context)
        
        val json = JSONObject().apply {
            put("device_id", deviceId)
            put("ts", ts)
            put("percentage", percentage)
            put("charging_status", chargingStatus)
        }
        
        sendRequest("$apiBase/battery", json)
    }
    
    /**
     * Send screen event to backend
     */
    fun sendScreenEvent(
        context: Context,
        ts: Long,
        state: String
    ) {
        val deviceId = getDeviceId(context)
        val apiBase = getApiBase(context)
        
        val json = JSONObject().apply {
            put("device_id", deviceId)
            put("ts", ts)
            put("state", state)
        }
        
        sendRequest("$apiBase/screen", json)
    }
    
    /**
     * Send notification to backend
     * @param kind "posted" or "removed"
     * @param dismissedAt timestamp when notification was dismissed (only for removed notifications)
     */
    fun sendNotification(
        context: Context,
        ts: Long,
        appName: String,
        title: String,
        content: String,
        category: String,
        kind: String,
        dismissedAt: Long? = null
    ) {
        val deviceId = getDeviceId(context)
        val apiBase = getApiBase(context)
        
        val json = JSONObject().apply {
            put("device_id", deviceId)
            put("ts", ts)
            put("app_name", appName)
            put("title", title)
            put("content", content)
            put("category", category)
            put("kind", kind)
            if (dismissedAt != null) {
                put("dismissed_at", dismissedAt)
            }
        }
        
        sendRequest("$apiBase/notification", json)
    }
    
    /**
     * Internal method to send HTTP POST request asynchronously
     */
    private fun sendRequest(url: String, json: JSONObject) {
        val body = json.toString().toRequestBody(JSON_MEDIA_TYPE)
        
        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("Content-Type", "application/json")
            .build()
        
        // Async call - doesn't block the sensor collection thread
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Failed to send data to $url: ${e.message}")
            }
            
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        Log.d(TAG, "Data sent successfully to $url")
                    } else {
                        Log.w(TAG, "Server returned ${response.code} for $url")
                    }
                }
            }
        })
    }
}
