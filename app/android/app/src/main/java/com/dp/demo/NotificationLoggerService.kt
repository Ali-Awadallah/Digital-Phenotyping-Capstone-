package com.dp.demo

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import android.content.pm.PackageManager

class NotificationLoggerService : NotificationListenerService() {

  override fun onNotificationPosted(sbn: StatusBarNotification?) {
    if (sbn == null) return
    logEvent("posted", sbn)
  }

  override fun onNotificationRemoved(sbn: StatusBarNotification?) {
    if (sbn == null) return
    logEvent("removed", sbn)
  }

  private fun logEvent(kind: String, sbn: StatusBarNotification) {
    try {
      // Respect JS-side toggle: if sentinel file exists, skip logging entirely
      val dir: File = applicationContext.filesDir
      val disabled = File(dir, "notifications.disabled")
      if (disabled.exists()) return

      val n: Notification = sbn.notification ?: return
      val extras = n.extras
      val rawTitle = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
      val rawText = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
      val visibility = n.visibility

      // Respect protected notifications: hide content for SECRET/PRIVATE
      val safeTitle: String
      val safeText: String
      if (visibility == Notification.VISIBILITY_SECRET || visibility == Notification.VISIBILITY_PRIVATE) {
        safeTitle = ""
        safeText = ""
      } else {
        safeTitle = rawTitle
        safeText = if (rawText.length > 200) rawText.substring(0, 200) else rawText
      }

      val pkg = sbn.packageName ?: "unknown"
      val pm: PackageManager = applicationContext.packageManager
      val appName = try {
        val appInfo = pm.getApplicationInfo(pkg, 0)
        pm.getApplicationLabel(appInfo)?.toString() ?: pkg
      } catch (_: Exception) { pkg }

      val category = n.category ?: "other"
      val ts = System.currentTimeMillis()

      val obj = JSONObject()
      obj.put("ts", ts)
      obj.put("kind", kind)
      obj.put("pkg", pkg)
      obj.put("appName", appName)
      obj.put("category", category)
      obj.put("title", safeTitle)
      obj.put("text", safeText)

      val log = File(dir, "notification-events.log")
      FileWriter(log, true).use { w ->
        w.write(obj.toString())
        w.write("\n")
      }
    } catch (_: Exception) {
      // ignore all logging errors to avoid impacting notifications
    }
  }
}
