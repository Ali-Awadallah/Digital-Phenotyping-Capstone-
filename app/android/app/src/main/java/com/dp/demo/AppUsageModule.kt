package com.dp.demo

import android.app.AppOpsManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.app.usage.UsageEvents
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
import android.os.Build
import android.provider.Settings
import com.facebook.react.bridge.*
import java.util.Calendar

class AppUsageModule(private val reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {
  override fun getName(): String = "AppUsage"

  @ReactMethod
  fun hasUsageAccess(promise: Promise) {
    try {
      val appOps = reactContext.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
      val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), reactContext.packageName)
      } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), reactContext.packageName)
      }
      promise.resolve(mode == AppOpsManager.MODE_ALLOWED)
    } catch (e: Exception) {
      promise.reject("E_CHECK", e)
    }
  }

  @ReactMethod
  fun openUsageAccessSettings() {
    try {
      val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
      reactContext.startActivity(intent)
    } catch (_: Exception) {}
  }

  @ReactMethod
  fun getUsageStatsForToday(promise: Promise) {
    try {
      val usm = reactContext.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
      val cal = Calendar.getInstance()
      val end = cal.timeInMillis
      cal.set(Calendar.HOUR_OF_DAY, 0)
      cal.set(Calendar.MINUTE, 0)
      cal.set(Calendar.SECOND, 0)
      cal.set(Calendar.MILLISECOND, 0)
      val start = cal.timeInMillis

      val pm: PackageManager = reactContext.packageManager

      // Build exact aggregation for [start, end] using UsageEvents to avoid any bucket bleed across midnight
      val byPkg = HashMap<String, Long>()
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        val events = usm.queryEvents(start, end)
        val lastForeground = HashMap<String, Long>()
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
          events.getNextEvent(event)
          val pkg = event.packageName ?: continue
          val ts = event.timeStamp
          val type = event.eventType

          val isFg = when (type) {
            UsageEvents.Event.MOVE_TO_FOREGROUND, UsageEvents.Event.ACTIVITY_RESUMED -> true
            else -> false
          }
          val isBg = when (type) {
            UsageEvents.Event.MOVE_TO_BACKGROUND, UsageEvents.Event.ACTIVITY_PAUSED -> true
            else -> false
          }

          if (isFg) {
            lastForeground[pkg] = ts
          } else if (isBg) {
            val startTs = lastForeground.remove(pkg)
            if (startTs != null && ts > startTs) {
              val clampedStart = maxOf(startTs, start)
              val clampedEnd = minOf(ts, end)
              if (clampedEnd > clampedStart) {
                val prev = byPkg[pkg] ?: 0L
                byPkg[pkg] = prev + (clampedEnd - clampedStart)
              }
            }
          }
        }

        // Close any sessions still in foreground at "end"
        for ((pkg, startTs) in lastForeground) {
          val clampedStart = maxOf(startTs, start)
          val clampedEnd = end
          if (clampedEnd > clampedStart) {
            val prev = byPkg[pkg] ?: 0L
            byPkg[pkg] = prev + (clampedEnd - clampedStart)
          }
        }
      } else {
        // Very old devices: best-effort fallback to daily stats
        val stats: List<UsageStats> = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end) ?: emptyList()
        for (u in stats) {
          if (u.totalTimeInForeground > 0) {
            val pkg = u.packageName
            val prev = byPkg[pkg] ?: 0L
            byPkg[pkg] = prev + u.totalTimeInForeground
          }
        }
      }

      val list = WritableNativeArray()
      var totalMs = 0L
      for (v in byPkg.values) totalMs += v

      // Sort by summed time and take top 10
      val top = byPkg.entries.sortedByDescending { it.value }.take(10)
      for (e in top) {
        val pkg = e.key
        val ms = e.value
        val map = WritableNativeMap()
        val appName = try {
          val appInfo = pm.getApplicationInfo(pkg, 0)
          pm.getApplicationLabel(appInfo)?.toString() ?: pkg
        } catch (_: Exception) { pkg }
        map.putString("package", pkg)
        map.putString("name", appName)
        map.putDouble("ms", ms.toDouble())
        // icon as base64 data URI
        try {
          val d = pm.getApplicationIcon(pkg)
          val b64 = drawableToBase64(d)
          if (b64 != null) map.putString("icon", "data:image/png;base64,$b64")
        } catch (_: Exception) {}
        list.pushMap(map)
      }

      val out = WritableNativeMap()
      out.putDouble("totalMs", totalMs.toDouble())
      out.putArray("apps", list)
      promise.resolve(out)
    } catch (e: Exception) {
      promise.reject("E_USAGE", e)
    }
  }

  private fun drawableToBase64(drawable: Drawable?): String? {
    if (drawable == null) return null
    try {
      val bitmap: Bitmap = when (drawable) {
        is BitmapDrawable -> drawable.bitmap
        else -> {
          val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
          val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
          val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
          val canvas = Canvas(bmp)
          drawable.setBounds(0, 0, canvas.width, canvas.height)
          drawable.draw(canvas)
          bmp
        }
      }
      val stream = java.io.ByteArrayOutputStream()
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
      val bytes = stream.toByteArray()
      return Base64.encodeToString(bytes, Base64.NO_WRAP)
    } catch (_: Exception) {
      return null
    }
  }
}
