// /DP_demo/demo/awareApi.js

import AsyncStorage from "@react-native-async-storage/async-storage";

const STORAGE_KEY = "@server_url";
const DEFAULT_URL = "http://192.168.10.8:8080/api";

// ---- Dynamic API Base URL ----

let _cachedBase = null; // in-memory cache so we don't hit AsyncStorage on every call

/**
 * Get the current API base URL (reads from cache or AsyncStorage).
 */
export async function getApiBase() {
  if (_cachedBase) return _cachedBase;
  try {
    const saved = await AsyncStorage.getItem(STORAGE_KEY);
    _cachedBase = saved || DEFAULT_URL;
  } catch {
    _cachedBase = DEFAULT_URL;
  }
  return _cachedBase;
}

/**
 * Save a new API base URL. Syncs to both JS (AsyncStorage) and native (SharedPreferences).
 */
export async function setApiBase(url) {
  const trimmed = url.trim().replace(/\/+$/, ""); // remove trailing slashes
  await AsyncStorage.setItem(STORAGE_KEY, trimmed);
  _cachedBase = trimmed;

  // Sync to native Android BackendAPIClient so background service uses same URL
  try {
    const { NativeModules, Platform } = require("react-native");
    if (Platform.OS === "android" && NativeModules.BackgroundService) {
      await NativeModules.BackgroundService.setAPIBaseURL(trimmed);
    }
  } catch (e) {
    console.warn("Failed to sync API base to native layer:", e);
  }
}

/**
 * Build URL from stored server and optional port input.
 * e.g. serverIp = "192.168.1.50", port = "8080"  ->  "http://192.168.1.50:8080/api"
 */
export function buildApiUrl(serverIp, port = "8080") {
  const ip = serverIp.trim();
  // If user typed a full URL, use it as-is
  if (ip.startsWith("http://") || ip.startsWith("https://")) {
    return ip.endsWith("/api") ? ip : `${ip.replace(/\/+$/, "")}/api`;
  }
  return `http://${ip}:${port}/api`;
}

// ---- API functions ----

export async function testConnection(baseUrl) {
  const base = baseUrl || (await getApiBase());
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 5000); // 5s timeout
  try {
    const res = await fetch(`${base}/testing`, { signal: controller.signal });
    clearTimeout(timeout);
    return { ok: true, data: await res.json() };
  } catch (e) {
    clearTimeout(timeout);
    return { ok: false, error: e.message || "Connection failed" };
  }
}

export async function sendEvent(device_id, value) {
  const API_BASE = await getApiBase();
  const body = {
    device_id,
    ts: Date.now(),
    value,
  };

  const res = await fetch(`${API_BASE}/events`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });

  return res.json(); // { ok: true }
}

export async function getEvents(device_id) {
  const API_BASE = await getApiBase();
  const now = Date.now();
  const res = await fetch(
    `${API_BASE}/events?device_id=${device_id}&start=0&end=${now}`
  );
  return res.json(); // returns an array of events
}

export async function sendBatteryReading(deviceId, percentage, chargingStatus = "unknown") {
  const API_BASE = await getApiBase();
  const res = await fetch(`${API_BASE}/battery`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      device_id: deviceId,
      percentage,
      charging_status: chargingStatus, // "charging", "unplugged", "full", or "unknown"
      ts: Date.now(),
    }),
  });
  return res.json();
}

export async function sendNotification(deviceId, appName, title, content, category, timestamp) {
  const API_BASE = await getApiBase();
  const res = await fetch(`${API_BASE}/notification`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      device_id: deviceId,
      app_name: appName,
      title: title,
      content: content,
      category: category,
      ts: timestamp || Date.now(),
    }),
  });
  return res.json();
}

export async function sendScreenState(deviceId, state) {
  const API_BASE = await getApiBase();
  const res = await fetch(`${API_BASE}/screen`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      device_id: deviceId,
      state, // "ON" or "OFF"
      ts: Date.now(),
    }),
  });
  return res.json();
}

export async function sendGyroscope(device_id, { ts, x, y, z, magnitude }) {
  const API_BASE = await getApiBase();
  const body = { device_id, ts, x, y, z, magnitude };
  //console.log("Sending gyro:", body);

  const res = await fetch(`${API_BASE}/gyroscope`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });

  return res.json();
}

export async function sendAccelerometer(device_id, { ts, x, y, z, magnitude }) {
  const API_BASE = await getApiBase();
  const body = { device_id, ts, x, y, z, magnitude };
  //console.log("Sending accel:", body);

  const res = await fetch(`${API_BASE}/accelerometer`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });

  return res.json();
}

export async function sendPedometer(device_id, { ts, steps }) {
  const API_BASE = await getApiBase();
  const body = { device_id, ts, steps };
  console.log("Sending pedometer:", body);

  const res = await fetch(`${API_BASE}/pedometer`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });

  return res.json();
}

export async function sendLocation(device_id, { ts, latitude, longitude, accuracy, altitude, speed }) {
  const API_BASE = await getApiBase();
  const body = { device_id, ts, latitude, longitude, accuracy, altitude, speed };
  //console.log("Sending location:", body);

  const res = await fetch(`${API_BASE}/location`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });

  return res.json();
}