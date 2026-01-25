// src/sensors/SensorService.js
import React, { useEffect, useState, useRef } from "react";
import { AppState } from "react-native";
import * as Battery from "expo-battery";
import { Gyroscope, Accelerometer, Pedometer } from "expo-sensors";
import * as Location from "expo-location";

import {
  sendBatteryReading,
  sendScreenState,
  sendGyroscope,
  sendAccelerometer,
  sendPedometer,
  sendLocation,
} from "./awareAPI"; // <-- adjust path to your awareAPI.js

import BackgroundService from "./src/services/BackgroundService";

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

        const appState = AppState.currentState;
        const screenOn = appState === "active";
        const screenState = screenOn ? "ON" : "OFF";

        console.log(
          `Battery: ${percentage}% | AppState: ${appState} | Screen: ${screenState}`
        );

        if (!isMounted) return;

        await sendBatteryReading(deviceIdRef.current, percentage);
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

    // CLEANUP when app closes / reloads
    return () => {
      isMounted = false;
      pollInterval && clearInterval(pollInterval);
      locationInterval && clearInterval(locationInterval);
      gyroSub && gyroSub.remove();
      accelSub && accelSub.remove();
      pedometerSub && pedometerSub.remove && pedometerSub.remove();
    };
  }, []);

  // No UI – this just runs in the background
  return null;
}
