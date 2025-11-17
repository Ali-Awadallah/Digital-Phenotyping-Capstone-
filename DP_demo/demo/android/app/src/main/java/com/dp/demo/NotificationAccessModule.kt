package com.dp.demo

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod

class NotificationAccessModule(private val reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

  override fun getName(): String = "NotificationAccess"

  @ReactMethod
  fun openSettings() {
    try {
      val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      reactContext.startActivity(intent)
    } catch (_: Exception) {}
  }

  @ReactMethod
  fun hasAccess(promise: Promise) {
    try {
      val cn = ComponentName(reactContext, NotificationLoggerService::class.java)
      val flat = cn.flattenToString()
      val enabled = Settings.Secure.getString(
        reactContext.contentResolver,
        "enabled_notification_listeners"
      )
      val granted = enabled != null && enabled.contains(flat)
      promise.resolve(granted)
    } catch (e: Exception) {
      promise.reject("E_CHECK_NOTIF", e)
    }
  }
}

