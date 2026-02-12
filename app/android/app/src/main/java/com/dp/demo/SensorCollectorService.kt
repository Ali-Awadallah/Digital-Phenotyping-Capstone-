package com.dp.demo

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.time.Instant

/**
 * SensorCollectorService - Android Foreground Service for continuous sensor data collection.
 * 
 * Uses native Android APIs (no Google Play Services dependency).
 * Comprehensive error handling to prevent crashes.
 */
class SensorCollectorService : Service(), SensorEventListener {

    companion object {
        private const val TAG = "SensorCollectorService"
        private const val CHANNEL_ID = "dp_sensor_channel"
        private const val NOTIFICATION_ID = 1001
        
        // Intervals (ms)
        private const val LOCATION_INTERVAL_MS = 15000L
        private const val MOTION_BATCH_INTERVAL_MS = 5000L
        
        // Log file names
        private const val LOCATION_LOG = "location-events.log"
        private const val ACCEL_LOG = "accelerometer-events.log"
        private const val GYRO_LOG = "gyroscope-events.log"
        private const val STEPS_LOG = "pedometer-events.log"
        private const val BATTERY_LOG = "battery-events.log"
        
        // Battery polling every 5 minutes (60 batch cycles of 5 seconds)
        private const val BATTERY_POLL_COUNT = 60
        
        // Health Connect polling every 15 minutes (180 batch cycles of 5 seconds)
        private const val HEALTH_CONNECT_POLL_COUNT = 180
        private const val PREF_HC_LAST_SYNC = "hc_last_sync_timestamp"
        
        const val PREF_SERVICE_ENABLED = "background_service_enabled"
        const val PREF_AUTO_START_ON_BOOT = "auto_start_on_boot"
        const val PREF_SERVICE_RUNNING = "service_currently_running"
    }

    private var sensorManager: SensorManager? = null
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private var wakeLock: PowerManager.WakeLock? = null
    
    private var handlerThread: HandlerThread? = null
    private var batchHandler: Handler? = null
    private var mainHandler: Handler? = null
    
    private val accelReadings = mutableListOf<SensorReading>()
    private val gyroReadings = mutableListOf<SensorReading>()
    private var lastStepCount: Int = -1
    private var initialStepCount: Int = -1
    
    private var isRunning = false
    private var batteryPollCounter = 0
    private var healthConnectPollCounter = 0
    
    data class SensorReading(
        val ts: Long,
        val x: Float,
        val y: Float,
        val z: Float,
        val magnitude: Float
    )

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        
        try {
            mainHandler = Handler(Looper.getMainLooper())
            
            // Initialize sensor manager
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            
            // Initialize location manager (native, no Google Play Services)
            locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            
            // Create handler thread for batching
            handlerThread = HandlerThread("SensorBatchThread").also { it.start() }
            batchHandler = Handler(handlerThread!!.looper)
            
            // Acquire wake lock
            try {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "DPDemo::SensorCollectorWakeLock"
                )?.apply {
                    acquire(24 * 60 * 60 * 1000L)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to acquire wake lock", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand")
        
        try {
            createNotificationChannel()
            val notification = createNotification()
            
            // Check if we have location permission to decide which foreground service type to use
            val hasLocationPermission = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            
            Log.d(TAG, "Location permission granted: $hasLocationPermission")
            
            // Start as foreground service - use location type ONLY if permission is granted
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // Android 14+: Must have permission for location type
                    val serviceType = if (hasLocationPermission) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    } else {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    }
                    startForeground(NOTIFICATION_ID, notification, serviceType)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val serviceType = if (hasLocationPermission) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    } else {
                        0 // Default type
                    }
                    if (serviceType != 0) {
                        startForeground(NOTIFICATION_ID, notification, serviceType)
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                Log.d(TAG, "Foreground service started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting foreground with type, trying without type", e)
                try {
                    // Last resort: try without any type
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                } catch (e2: Exception) {
                    Log.e(TAG, "Error starting foreground", e2)
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            
            isRunning = true
            
            // Start collection with delays to ensure everything is ready
            mainHandler?.postDelayed({
                startSensorCollection()
            }, 1000)
            
            mainHandler?.postDelayed({
                startLocationUpdates()
            }, 2000)
            
            mainHandler?.postDelayed({
                startBatchingLoop()
            }, 3000)
            
            // Initial Health Connect poll 30 seconds after start
            mainHandler?.postDelayed({
                pollHealthConnect()
            }, 30000)
            
            // Save preferences - set both running state AND auto-start for boot
            try {
                getSharedPreferences("dp_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(PREF_SERVICE_ENABLED, true)
                    .putBoolean(PREF_AUTO_START_ON_BOOT, true)
                    .putBoolean(PREF_SERVICE_RUNNING, true)
                    .apply()
            } catch (e: Exception) {
                Log.e(TAG, "Error saving preference", e)
            }
            
            Log.d(TAG, "Service started successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in onStartCommand", e)
            stopSelf()
            return START_NOT_STICKY
        }
        
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        isRunning = false
        
        try {
            sensorManager?.unregisterListener(this)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering sensor listener", e)
        }
        
        try {
            locationListener?.let { locationManager?.removeUpdates(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing location updates", e)
        }
        
        try {
            batchHandler?.removeCallbacksAndMessages(null)
            handlerThread?.quitSafely()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping handler thread", e)
        }
        
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wake lock", e)
        }
        
        // Only mark as not running, but KEEP auto-start preference for reboot
        try {
            getSharedPreferences("dp_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_SERVICE_RUNNING, false)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving preference", e)
        }
        
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Sensor Data Collection",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Continuous sensor monitoring for digital phenotyping"
                    setShowBadge(false)
                }
                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager?.createNotificationChannel(channel)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating notification channel", e)
        }
    }

    private fun createNotification(): Notification {
        val intent = packageManager?.getLaunchIntentForPackage(packageName)
        val pendingIntent = if (intent != null) {
            PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DP Demo")
            .setContentText("Collecting sensor data in background")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .apply { if (pendingIntent != null) setContentIntent(pendingIntent) }
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun startSensorCollection() {
        try {
            // Accelerometer
            sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensor ->
                val registered = sensorManager?.registerListener(
                    this, sensor,
                    SensorManager.SENSOR_DELAY_NORMAL,
                    batchHandler
                )
                Log.d(TAG, "Accelerometer registered: $registered")
            } ?: Log.w(TAG, "Accelerometer not available")
            
            // Gyroscope
            sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sensor ->
                val registered = sensorManager?.registerListener(
                    this, sensor,
                    SensorManager.SENSOR_DELAY_NORMAL,
                    batchHandler
                )
                Log.d(TAG, "Gyroscope registered: $registered")
            } ?: Log.w(TAG, "Gyroscope not available")
            
            // Step Counter
            sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let { sensor ->
                val registered = sensorManager?.registerListener(
                    this, sensor,
                    SensorManager.SENSOR_DELAY_NORMAL,
                    batchHandler
                )
                Log.d(TAG, "Step counter registered: $registered")
            } ?: Log.w(TAG, "Step counter not available")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting sensor collection", e)
        }
    }

    private fun startLocationUpdates() {
        try {
            // Check permission
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
                != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Location permission not granted")
                return
            }
            
            locationListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    logLocation(location)
                }
                
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            
            // Try GPS first, fall back to network
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            var registered = false
            
            for (provider in providers) {
                try {
                    if (locationManager?.isProviderEnabled(provider) == true) {
                        locationManager?.requestLocationUpdates(
                            provider,
                            LOCATION_INTERVAL_MS,
                            10f, // minimum distance in meters
                            locationListener!!,
                            Looper.getMainLooper()
                        )
                        Log.d(TAG, "Location updates started with provider: $provider")
                        registered = true
                        break
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error registering location provider: $provider", e)
                }
            }
            
            if (!registered) {
                Log.w(TAG, "No location provider available")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting location updates", e)
        }
    }

    private fun startBatchingLoop() {
        try {
            val batchRunnable = object : Runnable {
                override fun run() {
                    if (!isRunning) return
                    try {
                        flushAccelBatch()
                        flushGyroBatch()
                        
                        // Poll battery every 5 minutes (60 * 5 seconds)
                        batteryPollCounter++
                        if (batteryPollCounter >= BATTERY_POLL_COUNT) {
                            batteryPollCounter = 0
                            pollBattery()
                        }
                        
                        // Poll Health Connect every 15 minutes
                        healthConnectPollCounter++
                        if (healthConnectPollCounter >= HEALTH_CONNECT_POLL_COUNT) {
                            healthConnectPollCounter = 0
                            pollHealthConnect()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in batch flush", e)
                    }
                    batchHandler?.postDelayed(this, MOTION_BATCH_INTERVAL_MS)
                }
            }
            batchHandler?.postDelayed(batchRunnable, MOTION_BATCH_INTERVAL_MS)
            Log.d(TAG, "Batching loop started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting batching loop", e)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isRunning) return
        event ?: return
        
        try {
            val ts = System.currentTimeMillis()
            
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]
                    val magnitude = kotlin.math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
                    synchronized(accelReadings) {
                        accelReadings.add(SensorReading(ts, x, y, z, magnitude))
                    }
                }
                Sensor.TYPE_GYROSCOPE -> {
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]
                    val magnitude = kotlin.math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
                    synchronized(gyroReadings) {
                        gyroReadings.add(SensorReading(ts, x, y, z, magnitude))
                    }
                }
                Sensor.TYPE_STEP_COUNTER -> {
                    val steps = event.values[0].toInt()
                    if (initialStepCount == -1) {
                        initialStepCount = steps
                    }
                    if (steps != lastStepCount) {
                        lastStepCount = steps
                        logSteps(ts, steps - initialStepCount)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onSensorChanged", e)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun flushAccelBatch() {
        try {
            val batch: List<SensorReading>
            synchronized(accelReadings) {
                if (accelReadings.isEmpty()) return
                batch = accelReadings.toList()
                accelReadings.clear()
            }
            
            val avgX = batch.map { it.x }.average().toFloat()
            val avgY = batch.map { it.y }.average().toFloat()
            val avgZ = batch.map { it.z }.average().toFloat()
            val avgMag = batch.map { it.magnitude }.average().toFloat()
            val ts = System.currentTimeMillis()
            
            // Log to local file
            logSensorData(ACCEL_LOG, ts, avgX, avgY, avgZ, avgMag, batch.size)
            
            // Send to backend API
            try {
                BackendAPIClient.sendAccelerometer(this, ts, avgX, avgY, avgZ, avgMag)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending accel to backend", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error flushing accel batch", e)
        }
    }

    private fun flushGyroBatch() {
        try {
            val batch: List<SensorReading>
            synchronized(gyroReadings) {
                if (gyroReadings.isEmpty()) return
                batch = gyroReadings.toList()
                gyroReadings.clear()
            }
            
            val avgX = batch.map { it.x }.average().toFloat()
            val avgY = batch.map { it.y }.average().toFloat()
            val avgZ = batch.map { it.z }.average().toFloat()
            val avgMag = batch.map { it.magnitude }.average().toFloat()
            val ts = System.currentTimeMillis()
            
            // Log to local file
            logSensorData(GYRO_LOG, ts, avgX, avgY, avgZ, avgMag, batch.size)
            
            // Send to backend API
            try {
                BackendAPIClient.sendGyroscope(this, ts, avgX, avgY, avgZ, avgMag)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending gyro to backend", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error flushing gyro batch", e)
        }
    }

    private fun logSensorData(filename: String, ts: Long, x: Float, y: Float, z: Float, magnitude: Float, sampleCount: Int) {
        try {
            val obj = JSONObject().apply {
                put("ts", ts)
                put("x", x)
                put("y", y)
                put("z", z)
                put("magnitude", magnitude)
                put("samples", sampleCount)
            }
            appendToLog(filename, obj)
        } catch (e: Exception) {
            Log.e(TAG, "Error logging sensor data", e)
        }
    }

    private fun logLocation(location: Location) {
        try {
            val ts = System.currentTimeMillis()
            val obj = JSONObject().apply {
                put("ts", ts)
                put("latitude", location.latitude)
                put("longitude", location.longitude)
                put("accuracy", location.accuracy)
                put("altitude", location.altitude)
                put("speed", location.speed)
            }
            
            // Log to local file
            appendToLog(LOCATION_LOG, obj)
            Log.d(TAG, "Location logged: ${location.latitude}, ${location.longitude}")
            
            // Send to backend API
            try {
                BackendAPIClient.sendLocation(
                    this, ts,
                    location.latitude, location.longitude,
                    location.accuracy, location.altitude, location.speed
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error sending location to backend", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error logging location", e)
        }
    }

    private fun logSteps(ts: Long, steps: Int) {
        try {
            val obj = JSONObject().apply {
                put("ts", ts)
                put("steps", steps)
            }
            
            // Log to local file
            appendToLog(STEPS_LOG, obj)
            Log.d(TAG, "Steps logged: $steps")
            
            // Send to backend API
            try {
                BackendAPIClient.sendPedometer(this, ts, steps)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending steps to backend", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error logging steps", e)
        }
    }

    private fun pollBattery() {
        try {
            val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val percentage = if (scale > 0) (level * 100) / scale else level
            
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val chargingStatus = when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                BatteryManager.BATTERY_STATUS_FULL -> "full"
                BatteryManager.BATTERY_STATUS_DISCHARGING, 
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "unplugged"
                else -> "unknown"
            }
            
            val ts = System.currentTimeMillis()
            
            // Log to local file
            val obj = JSONObject().apply {
                put("ts", ts)
                put("percentage", percentage)
                put("charging_status", chargingStatus)
            }
            appendToLog(BATTERY_LOG, obj)
            Log.d(TAG, "Battery logged: $percentage% ($chargingStatus)")
            
            // Send to backend API
            try {
                BackendAPIClient.sendBattery(this, ts, percentage, chargingStatus)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending battery to backend", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error polling battery", e)
        }
    }

    /**
     * Poll Health Connect for wearable data and send to backend.
     * Reads records since last sync timestamp to avoid duplicate sends.
     */
    private fun pollHealthConnect() {
        try {
            val status = HealthConnectClient.getSdkStatus(this)
            if (status != HealthConnectClient.SDK_AVAILABLE) {
                Log.d(TAG, "Health Connect not available, skipping wearable poll")
                return
            }

            val client = HealthConnectClient.getOrCreate(this)
            val prefs = getSharedPreferences("dp_prefs", Context.MODE_PRIVATE)
            val lastSync = prefs.getLong(PREF_HC_LAST_SYNC, System.currentTimeMillis() - 15 * 60 * 1000)
            val now = System.currentTimeMillis()

            val startTime = Instant.ofEpochMilli(lastSync)
            val endTime = Instant.ofEpochMilli(now)
            val timeRange = TimeRangeFilter.between(startTime, endTime)

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // Check if we have permissions
                    val granted = client.permissionController.getGrantedPermissions()
                    if (granted.isEmpty()) {
                        Log.d(TAG, "No Health Connect permissions granted, skipping")
                        return@launch
                    }

                    var totalRecordsSent = 0

                    // Heart Rate
                    try {
                        val hrRecords = client.readRecords(
                            ReadRecordsRequest(HeartRateRecord::class, timeRangeFilter = timeRange)
                        )
                        for (record in hrRecords.records) {
                            for (sample in record.samples) {
                                BackendAPIClient.sendWearableHeartRate(
                                    this@SensorCollectorService,
                                    sample.time.toEpochMilli(),
                                    sample.beatsPerMinute.toInt()
                                )
                                totalRecordsSent++
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading heart rate", e)
                    }

                    // Steps
                    try {
                        val stepsRecords = client.readRecords(
                            ReadRecordsRequest(StepsRecord::class, timeRangeFilter = timeRange)
                        )
                        for (record in stepsRecords.records) {
                            BackendAPIClient.sendWearableSteps(
                                this@SensorCollectorService,
                                record.startTime.toEpochMilli(),
                                record.endTime.toEpochMilli(),
                                record.count.toInt()
                            )
                            totalRecordsSent++
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading steps", e)
                    }

                    // Sleep
                    try {
                        val sleepRecords = client.readRecords(
                            ReadRecordsRequest(SleepSessionRecord::class, timeRangeFilter = timeRange)
                        )
                        for (record in sleepRecords.records) {
                            BackendAPIClient.sendWearableSleep(
                                this@SensorCollectorService,
                                record.startTime.toEpochMilli(),
                                record.endTime.toEpochMilli(),
                                record.title ?: "Sleep",
                                record.notes ?: ""
                            )
                            totalRecordsSent++
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading sleep", e)
                    }

                    // Blood Pressure
                    try {
                        val bpRecords = client.readRecords(
                            ReadRecordsRequest(BloodPressureRecord::class, timeRangeFilter = timeRange)
                        )
                        for (record in bpRecords.records) {
                            BackendAPIClient.sendWearableBloodPressure(
                                this@SensorCollectorService,
                                record.time.toEpochMilli(),
                                record.systolic.inMillimetersOfMercury,
                                record.diastolic.inMillimetersOfMercury
                            )
                            totalRecordsSent++
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading blood pressure", e)
                    }

                    // Weight
                    try {
                        val weightRecords = client.readRecords(
                            ReadRecordsRequest(WeightRecord::class, timeRangeFilter = timeRange)
                        )
                        for (record in weightRecords.records) {
                            BackendAPIClient.sendWearableWeight(
                                this@SensorCollectorService,
                                record.time.toEpochMilli(),
                                record.weight.inKilograms
                            )
                            totalRecordsSent++
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading weight", e)
                    }

                    // Oxygen Saturation
                    try {
                        val oxygenRecords = client.readRecords(
                            ReadRecordsRequest(OxygenSaturationRecord::class, timeRangeFilter = timeRange)
                        )
                        for (record in oxygenRecords.records) {
                            BackendAPIClient.sendWearableOxygen(
                                this@SensorCollectorService,
                                record.time.toEpochMilli(),
                                record.percentage.value
                            )
                            totalRecordsSent++
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading oxygen saturation", e)
                    }

                    // Respiratory Rate
                    try {
                        val respRecords = client.readRecords(
                            ReadRecordsRequest(RespiratoryRateRecord::class, timeRangeFilter = timeRange)
                        )
                        for (record in respRecords.records) {
                            BackendAPIClient.sendWearableRespiratory(
                                this@SensorCollectorService,
                                record.time.toEpochMilli(),
                                record.rate
                            )
                            totalRecordsSent++
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading respiratory rate", e)
                    }

                    // Update last sync timestamp
                    prefs.edit().putLong(PREF_HC_LAST_SYNC, now).apply()
                    Log.d(TAG, "Health Connect sync complete: $totalRecordsSent records sent")

                } catch (e: Exception) {
                    Log.e(TAG, "Error in Health Connect poll", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Health Connect poll", e)
        }
    }

    private fun appendToLog(filename: String, data: JSONObject) {
        try {
            val file = File(filesDir, filename)
            FileWriter(file, true).use { writer ->
                writer.write(data.toString())
                writer.write("\n")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error appending to log: $filename", e)
        }
    }
}
