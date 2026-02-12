package com.dp.demo

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.facebook.react.bridge.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant

class HealthConnectModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    companion object {
        private const val TAG = "HealthConnectModule"
        
        val PERMISSIONS = setOf(
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(BloodPressureRecord::class),
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getReadPermission(OxygenSaturationRecord::class),
            HealthPermission.getReadPermission(RespiratoryRateRecord::class)
        )
    }

    override fun getName(): String = "HealthConnectModule"

    private fun getHealthConnectClient(): HealthConnectClient? {
        return try {
            HealthConnectClient.getOrCreate(reactApplicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Health Connect not available", e)
            null
        }
    }

    @ReactMethod
    fun isAvailable(promise: Promise) {
        try {
            val status = HealthConnectClient.getSdkStatus(reactApplicationContext)
            when (status) {
                HealthConnectClient.SDK_AVAILABLE -> promise.resolve(true)
                HealthConnectClient.SDK_UNAVAILABLE -> promise.resolve(false)
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                    promise.resolve(false)
                }
                else -> promise.resolve(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Health Connect availability", e)
            promise.resolve(false)
        }
    }

    @ReactMethod
    fun requestPermissions(promise: Promise) {
        val client = getHealthConnectClient()
        if (client == null) {
            promise.reject("UNAVAILABLE", "Health Connect is not available")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Check if we already have all permissions
                val granted = client.permissionController.getGrantedPermissions()
                if (PERMISSIONS.all { it in granted }) {
                    promise.resolve(true)
                    return@launch
                }

                // Open Health Connect app permissions page for this app
                val intent = Intent("androidx.health.ACTION_MANAGE_HEALTH_PERMISSIONS").apply {
                    putExtra(Intent.EXTRA_PACKAGE_NAME, reactApplicationContext.packageName)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                try {
                    reactApplicationContext.startActivity(intent)
                } catch (e: Exception) {
                    // Fallback to general Health Connect settings
                    val fallbackIntent = Intent().apply {
                        action = "androidx.health.ACTION_HEALTH_CONNECT_SETTINGS"
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    reactApplicationContext.startActivity(fallbackIntent)
                }
                promise.resolve(false) // User needs to grant permissions in the opened screen
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting permissions", e)
                promise.reject("ERROR", e.message)
            }
        }
    }

    @ReactMethod
    fun openHealthConnectSettings(promise: Promise) {
        try {
            val intent = Intent().apply {
                action = "androidx.health.ACTION_HEALTH_CONNECT_SETTINGS"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            reactApplicationContext.startActivity(intent)
            promise.resolve(true)
        } catch (e: Exception) {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                reactApplicationContext.startActivity(intent)
                promise.resolve(true)
            } catch (e2: Exception) {
                promise.reject("ERROR", "Could not open Health Connect settings")
            }
        }
    }

    @ReactMethod
    fun checkPermissions(promise: Promise) {
        val client = getHealthConnectClient()
        if (client == null) {
            promise.resolve(false)
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val granted = client.permissionController.getGrantedPermissions()
                val hasAll = PERMISSIONS.all { it in granted }
                val grantedCount = PERMISSIONS.count { it in granted }
                Log.i(TAG, "Permissions: $grantedCount/${PERMISSIONS.size} granted")
                promise.resolve(hasAll)
            } catch (e: Exception) {
                Log.e(TAG, "Error checking permissions", e)
                promise.resolve(false)
            }
        }
    }

    @ReactMethod
    fun getPermissionStatus(promise: Promise) {
        val client = getHealthConnectClient()
        if (client == null) {
            promise.reject("UNAVAILABLE", "Health Connect is not available")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val granted = client.permissionController.getGrantedPermissions()
                val result = Arguments.createMap().apply {
                    putBoolean("heartRate", HealthPermission.getReadPermission(HeartRateRecord::class) in granted)
                    putBoolean("steps", HealthPermission.getReadPermission(StepsRecord::class) in granted)
                    putBoolean("sleep", HealthPermission.getReadPermission(SleepSessionRecord::class) in granted)
                    putBoolean("bloodPressure", HealthPermission.getReadPermission(BloodPressureRecord::class) in granted)
                    putBoolean("weight", HealthPermission.getReadPermission(WeightRecord::class) in granted)
                    putBoolean("oxygenSaturation", HealthPermission.getReadPermission(OxygenSaturationRecord::class) in granted)
                    putBoolean("respiratoryRate", HealthPermission.getReadPermission(RespiratoryRateRecord::class) in granted)
                }
                promise.resolve(result)
            } catch (e: Exception) {
                Log.e(TAG, "Error getting permission status", e)
                promise.reject("ERROR", e.message)
            }
        }
    }

    @ReactMethod
    fun getHeartRateRecords(startTimeMs: Double, endTimeMs: Double, promise: Promise) {
        val client = getHealthConnectClient()
        if (client == null) {
            promise.reject("UNAVAILABLE", "Health Connect is not available")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val startTime = Instant.ofEpochMilli(startTimeMs.toLong())
                val endTime = Instant.ofEpochMilli(endTimeMs.toLong())

                val request = ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                )

                val response = client.readRecords(request)
                val result = Arguments.createArray()

                for (record in response.records) {
                    for (sample in record.samples) {
                        val obj = Arguments.createMap().apply {
                            putDouble("timestamp", sample.time.toEpochMilli().toDouble())
                            putInt("bpm", sample.beatsPerMinute.toInt())
                        }
                        result.pushMap(obj)
                    }
                }

                promise.resolve(result)
            } catch (e: Exception) {
                Log.e(TAG, "Error reading heart rate", e)
                promise.reject("ERROR", e.message)
            }
        }
    }

    @ReactMethod
    fun getStepsRecords(startTimeMs: Double, endTimeMs: Double, promise: Promise) {
        val client = getHealthConnectClient()
        if (client == null) {
            promise.reject("UNAVAILABLE", "Health Connect is not available")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val startTime = Instant.ofEpochMilli(startTimeMs.toLong())
                val endTime = Instant.ofEpochMilli(endTimeMs.toLong())

                val request = ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                )

                val response = client.readRecords(request)
                val result = Arguments.createArray()

                for (record in response.records) {
                    val obj = Arguments.createMap().apply {
                        putDouble("startTime", record.startTime.toEpochMilli().toDouble())
                        putDouble("endTime", record.endTime.toEpochMilli().toDouble())
                        putInt("count", record.count.toInt())
                    }
                    result.pushMap(obj)
                }

                promise.resolve(result)
            } catch (e: Exception) {
                Log.e(TAG, "Error reading steps", e)
                promise.reject("ERROR", e.message)
            }
        }
    }

    @ReactMethod
    fun getSleepSessions(startTimeMs: Double, endTimeMs: Double, promise: Promise) {
        val client = getHealthConnectClient()
        if (client == null) {
            promise.reject("UNAVAILABLE", "Health Connect is not available")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val startTime = Instant.ofEpochMilli(startTimeMs.toLong())
                val endTime = Instant.ofEpochMilli(endTimeMs.toLong())

                val request = ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                )

                val response = client.readRecords(request)
                val result = Arguments.createArray()

                for (record in response.records) {
                    val obj = Arguments.createMap().apply {
                        putDouble("startTime", record.startTime.toEpochMilli().toDouble())
                        putDouble("endTime", record.endTime.toEpochMilli().toDouble())
                        putString("title", record.title ?: "Sleep")
                        putString("notes", record.notes ?: "")
                    }
                    result.pushMap(obj)
                }

                promise.resolve(result)
            } catch (e: Exception) {
                Log.e(TAG, "Error reading sleep sessions", e)
                promise.reject("ERROR", e.message)
            }
        }
    }

    @ReactMethod
    fun getBloodPressureRecords(startTimeMs: Double, endTimeMs: Double, promise: Promise) {
        val client = getHealthConnectClient()
        if (client == null) {
            promise.reject("UNAVAILABLE", "Health Connect is not available")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val startTime = Instant.ofEpochMilli(startTimeMs.toLong())
                val endTime = Instant.ofEpochMilli(endTimeMs.toLong())

                val request = ReadRecordsRequest(
                    recordType = BloodPressureRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                )

                val response = client.readRecords(request)
                val result = Arguments.createArray()

                for (record in response.records) {
                    val obj = Arguments.createMap().apply {
                        putDouble("timestamp", record.time.toEpochMilli().toDouble())
                        putDouble("systolic", record.systolic.inMillimetersOfMercury)
                        putDouble("diastolic", record.diastolic.inMillimetersOfMercury)
                    }
                    result.pushMap(obj)
                }

                promise.resolve(result)
            } catch (e: Exception) {
                Log.e(TAG, "Error reading blood pressure", e)
                promise.reject("ERROR", e.message)
            }
        }
    }

    @ReactMethod
    fun getWeightRecords(startTimeMs: Double, endTimeMs: Double, promise: Promise) {
        val client = getHealthConnectClient()
        if (client == null) {
            promise.reject("UNAVAILABLE", "Health Connect is not available")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val startTime = Instant.ofEpochMilli(startTimeMs.toLong())
                val endTime = Instant.ofEpochMilli(endTimeMs.toLong())

                val request = ReadRecordsRequest(
                    recordType = WeightRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                )

                val response = client.readRecords(request)
                val result = Arguments.createArray()

                for (record in response.records) {
                    val obj = Arguments.createMap().apply {
                        putDouble("timestamp", record.time.toEpochMilli().toDouble())
                        putDouble("weightKg", record.weight.inKilograms)
                    }
                    result.pushMap(obj)
                }

                promise.resolve(result)
            } catch (e: Exception) {
                Log.e(TAG, "Error reading weight", e)
                promise.reject("ERROR", e.message)
            }
        }
    }

    @ReactMethod
    fun getOxygenSaturationRecords(startTimeMs: Double, endTimeMs: Double, promise: Promise) {
        val client = getHealthConnectClient()
        if (client == null) {
            promise.reject("UNAVAILABLE", "Health Connect is not available")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val startTime = Instant.ofEpochMilli(startTimeMs.toLong())
                val endTime = Instant.ofEpochMilli(endTimeMs.toLong())

                val request = ReadRecordsRequest(
                    recordType = OxygenSaturationRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                )

                val response = client.readRecords(request)
                val result = Arguments.createArray()

                for (record in response.records) {
                    val obj = Arguments.createMap().apply {
                        putDouble("timestamp", record.time.toEpochMilli().toDouble())
                        putDouble("percentage", record.percentage.value)
                    }
                    result.pushMap(obj)
                }

                promise.resolve(result)
            } catch (e: Exception) {
                Log.e(TAG, "Error reading oxygen saturation", e)
                promise.reject("ERROR", e.message)
            }
        }
    }

    @ReactMethod
    fun getRespiratoryRateRecords(startTimeMs: Double, endTimeMs: Double, promise: Promise) {
        val client = getHealthConnectClient()
        if (client == null) {
            promise.reject("UNAVAILABLE", "Health Connect is not available")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val startTime = Instant.ofEpochMilli(startTimeMs.toLong())
                val endTime = Instant.ofEpochMilli(endTimeMs.toLong())

                val request = ReadRecordsRequest(
                    recordType = RespiratoryRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                )

                val response = client.readRecords(request)
                val result = Arguments.createArray()

                for (record in response.records) {
                    val obj = Arguments.createMap().apply {
                        putDouble("timestamp", record.time.toEpochMilli().toDouble())
                        putDouble("rate", record.rate)
                    }
                    result.pushMap(obj)
                }

                promise.resolve(result)
            } catch (e: Exception) {
                Log.e(TAG, "Error reading respiratory rate", e)
                promise.reject("ERROR", e.message)
            }
        }
    }

    @ReactMethod
    fun syncToBackend(promise: Promise) {
        val client = getHealthConnectClient()
        if (client == null) {
            promise.reject("UNAVAILABLE", "Health Connect is not available")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val granted = client.permissionController.getGrantedPermissions()
                if (granted.isEmpty()) {
                    promise.reject("NO_PERMISSIONS", "No Health Connect permissions granted")
                    return@launch
                }

                val context = reactApplicationContext
                val prefs = context.getSharedPreferences("dp_prefs", android.content.Context.MODE_PRIVATE)
                val lastSync = prefs.getLong("hc_last_sync_timestamp", System.currentTimeMillis() - 24 * 60 * 60 * 1000)
                val nowMs = System.currentTimeMillis()
                val start = Instant.ofEpochMilli(lastSync)
                val now = Instant.ofEpochMilli(nowMs)
                val timeRange = TimeRangeFilter.between(start, now)
                var total = 0

                // Heart Rate
                try {
                    val records = client.readRecords(ReadRecordsRequest(HeartRateRecord::class, timeRangeFilter = timeRange))
                    for (record in records.records) {
                        for (sample in record.samples) {
                            BackendAPIClient.sendWearableHeartRate(context, sample.time.toEpochMilli(), sample.beatsPerMinute.toInt())
                            total++
                        }
                    }
                } catch (e: Exception) { Log.e(TAG, "Sync HR error", e) }

                // Steps
                try {
                    val records = client.readRecords(ReadRecordsRequest(StepsRecord::class, timeRangeFilter = timeRange))
                    for (record in records.records) {
                        BackendAPIClient.sendWearableSteps(context, record.startTime.toEpochMilli(), record.endTime.toEpochMilli(), record.count.toInt())
                        total++
                    }
                } catch (e: Exception) { Log.e(TAG, "Sync steps error", e) }

                // Sleep
                try {
                    val records = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, timeRangeFilter = timeRange))
                    for (record in records.records) {
                        BackendAPIClient.sendWearableSleep(context, record.startTime.toEpochMilli(), record.endTime.toEpochMilli(), record.title ?: "Sleep", record.notes ?: "")
                        total++
                    }
                } catch (e: Exception) { Log.e(TAG, "Sync sleep error", e) }

                // Blood Pressure
                try {
                    val records = client.readRecords(ReadRecordsRequest(BloodPressureRecord::class, timeRangeFilter = timeRange))
                    for (record in records.records) {
                        BackendAPIClient.sendWearableBloodPressure(context, record.time.toEpochMilli(), record.systolic.inMillimetersOfMercury, record.diastolic.inMillimetersOfMercury)
                        total++
                    }
                } catch (e: Exception) { Log.e(TAG, "Sync BP error", e) }

                // Weight
                try {
                    val records = client.readRecords(ReadRecordsRequest(WeightRecord::class, timeRangeFilter = timeRange))
                    for (record in records.records) {
                        BackendAPIClient.sendWearableWeight(context, record.time.toEpochMilli(), record.weight.inKilograms)
                        total++
                    }
                } catch (e: Exception) { Log.e(TAG, "Sync weight error", e) }

                // Oxygen
                try {
                    val records = client.readRecords(ReadRecordsRequest(OxygenSaturationRecord::class, timeRangeFilter = timeRange))
                    for (record in records.records) {
                        BackendAPIClient.sendWearableOxygen(context, record.time.toEpochMilli(), record.percentage.value)
                        total++
                    }
                } catch (e: Exception) { Log.e(TAG, "Sync oxygen error", e) }

                // Respiratory
                try {
                    val records = client.readRecords(ReadRecordsRequest(RespiratoryRateRecord::class, timeRangeFilter = timeRange))
                    for (record in records.records) {
                        BackendAPIClient.sendWearableRespiratory(context, record.time.toEpochMilli(), record.rate)
                        total++
                    }
                } catch (e: Exception) { Log.e(TAG, "Sync respiratory error", e) }

                // Update last sync timestamp so next sync doesn't re-send
                prefs.edit().putLong("hc_last_sync_timestamp", nowMs).apply()
                promise.resolve("Synced $total records to backend")

            } catch (e: Exception) {
                Log.e(TAG, "Error syncing to backend", e)
                promise.reject("ERROR", e.message)
            }
        }
    }
}
