// src/sensors/SensorService.js
import React, { useEffect, useState, useRef } from "react";
import { AppState } from "react-native";
import * as Battery from "expo-battery";
import { Gyroscope, Accelerometer, Pedometer } from "expo-sensors";
import * as Location from "expo-location";
import * as FileSystem from "expo-file-system/legacy";
import Constants from "expo-constants";
import AsyncStorage from "@react-native-async-storage/async-storage";

import {
  sendBatteryReading,
  sendScreenState,
  sendGyroscope,
  sendAccelerometer,
  sendPedometer,
  sendLocation,
  sendNotification,
} from "./awareAPI"; // <-- adjust path to your awareAPI.js

import BackgroundService from "./src/services/BackgroundService";

// Key for storing synced notification timestamps
const SYNCED_NOTIFS_KEY = "synced_notification_timestamps";

// Default fallback device ID
const DEFAULT_DEVICE_ID = "demo-phone";

export default function SensorService() {
  const deviceIdRef = useRef(DEFAULT_DEVICE_ID);

  useEffect(() => {
    // Get device ID from native module on mount
    const initDeviceId = async () => {
      try {
        if (BackgroundService.isAvailable()) {
          const nativeDeviceId = await BackgroundService.getDeviceId();
          if (nativeDeviceId) {
            deviceIdRef.current = nativeDeviceId;
            console.log("Using device ID from native module:", nativeDeviceId);
          }
        }
      } catch (e) {
        console.log("Could not get native device ID, using default:", e.message);
      }
    };
    initDeviceId();
  }, []);

  useEffect(() => {
    let isMounted = true;
    let pollInterval = null;
    let locationInterval = null;
    let pedometerSub = null;

    // ---- BATTERY + SCREEN ----
    const poll = async () => {
      try {
        const level = await Battery.getBatteryLevelAsync();
        const percentage = Math.round(level * 100);

        // Get charging status
        const batteryState = await Battery.getBatteryStateAsync();
        let chargingStatus = "unknown";
        if (batteryState === Battery.BatteryState.CHARGING) {
          chargingStatus = "charging";
        } else if (batteryState === Battery.BatteryState.FULL) {
          chargingStatus = "full";
        } else if (batteryState === Battery.BatteryState.UNPLUGGED) {
          chargingStatus = "unplugged";
        }

        const appState = AppState.currentState;
        const screenOn = appState === "active";
        const screenState = screenOn ? "ON" : "OFF";

        console.log(
          `Battery: ${percentage}% (${chargingStatus}) | AppState: ${appState} | Screen: ${screenState}`
        );

        if (!isMounted) return;

        await sendBatteryReading(deviceIdRef.current, percentage, chargingStatus);
        await sendScreenState(deviceIdRef.current, screenState);
      } catch (e) {
        console.log("Sensor send error:", e.message);
      }
    };

    poll();
    pollInterval = setInterval(poll, 5000);

    // ---- GYRO ----
    Gyroscope.setUpdateInterval(5000);
    const gyroSub = Gyroscope.addListener(({ x, y, z }) => {
      const magnitude = Math.sqrt(x * x + y * y + z * z);
      const payload = { ts: Date.now(), x, y, z, magnitude };
      console.log("Gyro reading:", payload);
      sendGyroscope(deviceIdRef.current, payload).catch((e) =>
        console.log("Gyro send error:", e)
      );
    });

    // ---- ACCEL ----
    Accelerometer.setUpdateInterval(5000);
    const accelSub = Accelerometer.addListener(({ x, y, z }) => {
      const magnitude = Math.sqrt(x * x + y * y + z * z);
      const payload = { ts: Date.now(), x, y, z, magnitude };
      console.log("Accel reading:", payload);
      sendAccelerometer(deviceIdRef.current, payload).catch((e) =>
        console.log("Accel send error:", e)
      );
    });

    // ---- PEDOMETER ----
    Pedometer.isAvailableAsync().then((available) => {
      if (!available) {
        console.log("Pedometer not available");
        return;
      }
      pedometerSub = Pedometer.watchStepCount((result) => {
        const payload = { ts: Date.now(), steps: result.steps };
        console.log("Pedometer reading:", payload);
        sendPedometer(deviceIdRef.current, payload).catch((e) =>
          console.log("Pedometer send error:", e)
        );
      });
    });

    // ---- LOCATION ----
    (async () => {
      const { status } = await Location.requestForegroundPermissionsAsync();
      if (status !== "granted") {
        console.log("Location permission denied");
        return;
      }

      locationInterval = setInterval(async () => {
        try {
          const loc = await Location.getCurrentPositionAsync({});
          const { latitude, longitude, accuracy, altitude, speed } = loc.coords;

          const payload = {
            ts: Date.now(),
            latitude,
            longitude,
            accuracy: accuracy ?? 0,
            altitude: altitude ?? 0,
            speed: speed ?? 0,
          };

          console.log("Location reading:", payload);
          sendLocation(deviceIdRef.current, payload).catch((e) =>
            console.log("Location send error:", e)
          );
        } catch (e) {
          console.log("Location read error:", e);
        }
      }, 5000);
    })();

    // ---- NOTIFICATION SYNC ----
    // Sync notifications from local log to backend every 30 seconds
    const syncNotifications = async () => {
      try {
        const pkg =
          (Constants?.expoConfig &&
            Constants.expoConfig.android &&
            Constants.expoConfig.android.package) ||
          "com.dp.demo";

        // Try multiple paths for notification log
        const candidates = [];
        if (FileSystem.documentDirectory) {
          candidates.push(FileSystem.documentDirectory + "notification-events.log");
        }
        candidates.push(`file:///data/user/0/${pkg}/files/notification-events.log`);

        let notifications = [];
        for (const p of candidates) {
          try {
            const info = await FileSystem.getInfoAsync(p);
            if (info && info.exists) {
              let content = await FileSystem.readAsStringAsync(p);
              if (content && content.includes("\\n"))
                content = content.replace(/\\n/g, "\n");
              const lines = (content || "").split("\n").filter(Boolean);
              notifications = lines
                .map((l) => {
                  try {
                    return JSON.parse(l);
                  } catch {
                    return null;
                  }
                })
                .filter(Boolean);
              break;
            }
          } catch (e) {
            // Continue to next candidate
          }
        }

        if (notifications.length === 0) return;

        // Get already synced timestamps from AsyncStorage
        let syncedTimestamps = [];
        try {
          const stored = await AsyncStorage.getItem(SYNCED_NOTIFS_KEY);
          if (stored) syncedTimestamps = JSON.parse(stored);
        } catch (e) {
          syncedTimestamps = [];
        }

        // Filter to only unsynced notifications
        const unsynced = notifications.filter(
          (n) => n.ts && !syncedTimestamps.includes(n.ts)
        );

        if (unsynced.length === 0) return;

        console.log(`Syncing ${unsynced.length} notifications to backend...`);

        // Send each unsynced notification to backend
        for (const notif of unsynced) {
          try {
            await sendNotification(
              deviceIdRef.current,
              notif.appName || notif.packageName || "",
              notif.title || "",
              notif.text || notif.content || "",
              notif.category || "",
              notif.ts
            );
            syncedTimestamps.push(notif.ts);
          } catch (e) {
            console.log("Failed to sync notification:", e.message);
          }
        }

        // Save updated synced timestamps (keep last 1000 to avoid unbounded growth)
        const trimmedTimestamps = syncedTimestamps.slice(-1000);
        await AsyncStorage.setItem(
          SYNCED_NOTIFS_KEY,
          JSON.stringify(trimmedTimestamps)
        );
      } catch (e) {
        console.log("Notification sync error:", e.message);
      }
    };

    // Sync immediately and then every 30 seconds
    syncNotifications();
    const notificationSyncInterval = setInterval(syncNotifications, 30000);

    // CLEANUP when app closes / reloads
    return () => {
      isMounted = false;
      pollInterval && clearInterval(pollInterval);
      locationInterval && clearInterval(locationInterval);
      notificationSyncInterval && clearInterval(notificationSyncInterval);
      gyroSub && gyroSub.remove();
      accelSub && accelSub.remove();
      pedometerSub && pedometerSub.remove && pedometerSub.remove();
    };
  }, []);

  // No UI – this just runs in the background
  return null;
}
