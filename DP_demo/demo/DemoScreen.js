import React, { useEffect, useState } from "react";
import { View, Text, Button, FlatList } from "react-native";
import * as Battery from "expo-battery";
import {
  sendBatteryReading,
  sendScreenState,
  sendGyroscope,
  sendAccelerometer,
  sendPedometer,
  sendLocation,
} from "./awareAPI";
import { AppState } from "react-native";
import { Gyroscope, Accelerometer, Pedometer } from "expo-sensors";
import * as Location from "expo-location";

const API_BASE = "http://192.168.10.78:8080/api";   // IP address of the VM (back-end Aware mirco server)
const DEVICE_ID = "demo-phone";  // for testing

async function testConnection() {   // this function tests the connection to the back-end server
  const r = await fetch(`${API_BASE}/testing`);
  return r.json(); // { ok: true }
}

async function sendEvent(device_id, value) {   // (dummy function only for testing) this function sends an event to the back-end
  const r = await fetch(`${API_BASE}/events`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ device_id, ts: Date.now(), value }),
  });
  return r.json();
}

async function getEvents(device_id) {   // (dummy function only for testing) this function gets all events from the back-end db
  const now = Date.now();
  const r = await fetch(
    `${API_BASE}/events?device_id=${device_id}&start=0&end=${now}`
  );
  return r.json();
}

export function DemoScreen() {   // created this demo screen just for testing & showing if APIs are working
  const [connected, setConnected] = useState(null); // null = loading
  const [events, setEvents] = useState([]);

  useEffect(() => {
    let isMounted = true;
    let interval = null;
    let gyroSub = null;
    let accelSub = null;
    let pedometerSub = null;
    let locationInterval = null;

    // ---- BACKEND TEST ----
    (async () => {
      try {
        const j = await testConnection();
        console.log("Backend /testing:", j);
        if (isMounted) {
          setConnected(j?.ok === true);
        }
      } catch (e) {
        console.log("Backend /testing error:", e.message);
        if (isMounted) {
          setConnected(false);
        }
      }
    })();

    // ---- BATTERY + SCREEN STATE POLL ----
    const poll = async () => {
      try {
        // 1) Real battery level (0–1 -> 0–100%)
        const level = await Battery.getBatteryLevelAsync();
        const percentage = Math.round(level * 100);

        // 2) Approximate screen state (app active vs not)
        const appState = AppState.currentState; // "active", "background", "inactive"
        const screenOn = appState === "active";
        const screenState = screenOn ? "ON" : "OFF";

        // 3) Log locally in Expo terminal
        console.log(
          `Battery: ${percentage}% | AppState: ${appState} | Screen: ${screenState}`
        );

        if (!isMounted) return;

        // 4) Send to backend (just logs there, no DB insertion initially)
        const batteryRes = await sendBatteryReading(DEVICE_ID, percentage);
        const screenRes = await sendScreenState(DEVICE_ID, screenState);

        console.log("Backend /battery echo:", batteryRes);
        console.log("Backend /screen echo:", screenRes);
      } catch (e) {
        console.log("Sensor send error:", e.message);
      }
    };

    // run immediately and then every 5s
    poll();
    interval = setInterval(poll, 5000);

    // ---- GYROSCOPE ----
    Gyroscope.setUpdateInterval(5000); // 5 second
    gyroSub = Gyroscope.addListener(({ x, y, z }) => {
      const magnitude = Math.sqrt(x * x + y * y + z * z);
      const payload = {
        ts: Date.now(),
        x,
        y,
        z,
        magnitude,
      };
      console.log("Gyro reading:", payload);
      sendGyroscope(DEVICE_ID, payload).catch((e) =>
        console.log("Gyro send error:", e)
      );
    });

    // ---- ACCELEROMETER ----
    Accelerometer.setUpdateInterval(5000);
    accelSub = Accelerometer.addListener(({ x, y, z }) => {
      const magnitude = Math.sqrt(x * x + y * y + z * z);
      const payload = {
        ts: Date.now(),
        x,
        y,
        z,
        magnitude,
      };
      console.log("Accel reading:", payload);
      sendAccelerometer(DEVICE_ID, payload).catch((e) =>
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
        const payload = {
          ts: Date.now(),
          steps: result.steps,
        };
        console.log("Pedometer reading:", payload);
        sendPedometer(DEVICE_ID, payload).catch((e) =>
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
          sendLocation(DEVICE_ID, payload).catch((e) =>
            console.log("Location send error:", e)
          );
        } catch (e) {
          console.log("Location read error:", e);
        }
      }, 5000); // every 5 seconds
    })();

    // ---- CLEANUP ON UNMOUNT ----
    return () => {
      isMounted = false;

      interval && clearInterval(interval);
      locationInterval && clearInterval(locationInterval);

      gyroSub && gyroSub.remove();
      accelSub && accelSub.remove();
      pedometerSub &&
        pedometerSub.remove &&
        pedometerSub.remove(); // some versions use .remove()
    };
  }, []);

  const handleSend = async () => {
    try {
      await sendEvent(DEVICE_ID, "hello-from-app");
      const data = await getEvents(DEVICE_ID);
      setEvents(Array.isArray(data) ? data : []);
    } catch (e) {
      console.log("send/get error:", e.message);
    }
  };

  return (
    <View style={{ flex: 1, padding: 30 }}>
      <Text>
        Backend Connected:{" "}
        {connected === null ? "…" : connected ? "✅" : "❌"}
      </Text>
      <Text>Streaming real battery, screen, gyro, accel, pedometer & location.</Text>
      <Text>Check Expo terminal + backend logs.</Text>

      <Button title="Send Event" onPress={handleSend} />

      <FlatList
        data={events}
        keyExtractor={(_, i) => String(i)}
        renderItem={({ item }) => (
          <Text style={{ marginTop: 8 }}>
            {JSON.stringify(item, null, 2)}
          </Text>
        )}
      />
    </View>
  );
}
