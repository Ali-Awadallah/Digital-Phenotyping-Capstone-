// /DP_demo/demo/awareApi.js

import AsyncStorage from "@react-native-async-storage/async-storage";

const STORAGE_KEY = "@server_url";
const INGEST_KEY_STORAGE = "@ingest_api_key";
const DEFAULT_URL = "http://192.168.10.8:8080/api";

// ---- Dynamic API Base URL ----

let _cachedBase = null; // in-memory cache so we don't hit AsyncStorage on every call
let _cachedIngestKey = null;

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

export async function getApiIngestKey() {
  if (_cachedIngestKey !== null) return _cachedIngestKey;
  try {
    const saved = await AsyncStorage.getItem(INGEST_KEY_STORAGE);
    _cachedIngestKey = saved || "";
  } catch {
    _cachedIngestKey = "";
  }
  return _cachedIngestKey;
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

export async function setApiIngestKey(key) {
  const normalized = (key || "").trim();
  await AsyncStorage.setItem(INGEST_KEY_STORAGE, normalized);
  _cachedIngestKey = normalized;

  // Sync to native Android sender used by background service.
  try {
    const { NativeModules, Platform } = require("react-native");
    if (Platform.OS === "android" && NativeModules.BackgroundService) {
      await NativeModules.BackgroundService.setAPIIngestKey(normalized);
    }
  } catch (e) {
    console.warn("Failed to sync ingest key to native layer:", e);
  }
}

async function buildAuthHeaders() {
  const ingestKey = await getApiIngestKey();
  const headers = { "Content-Type": "application/json" };
  if (ingestKey) headers["X-API-Key"] = ingestKey;
  return { headers, ingestKey };
}

async function addApiKeyToUrl(url) {
  const ingestKey = await getApiIngestKey();
  if (!ingestKey) return url;
  const sep = url.includes("?") ? "&" : "?";
  return `${url}${sep}api_key=${encodeURIComponent(ingestKey)}`;
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
  const { headers, ingestKey } = await buildAuthHeaders();
  if (ingestKey) body.api_key = ingestKey;

  const res = await fetch(`${API_BASE}/events`, {
    method: "POST",
    headers,
    body: JSON.stringify(body),
  });

  return res.json(); // { ok: true }
}

export async function getEvents(device_id) {
  const API_BASE = await getApiBase();
  const now = Date.now();
  const { headers } = await buildAuthHeaders();
  const url = await addApiKeyToUrl(
    `${API_BASE}/events?device_id=${encodeURIComponent(device_id)}&start=0&end=${now}`
  );
  const res = await fetch(url, { headers });
  return res.json(); // returns an array of events
}

export async function sendBatteryReading(deviceId, percentage, chargingStatus = "unknown") {
  const API_BASE = await getApiBase();
  const { headers, ingestKey } = await buildAuthHeaders();
  const payload = {
    device_id: deviceId,
    percentage,
    charging_status: chargingStatus, // "charging", "unplugged", "full", or "unknown"
    ts: Date.now(),
  };
  if (ingestKey) payload.api_key = ingestKey;
  const res = await fetch(`${API_BASE}/battery`, {
    method: "POST",
    headers,
    body: JSON.stringify(payload),
  });
  return res.json();
}

export async function sendNotification(deviceId, appName, title, content, category, timestamp) {
  const API_BASE = await getApiBase();
  const { headers, ingestKey } = await buildAuthHeaders();
  const payload = {
    device_id: deviceId,
    app_name: appName,
    title: title,
    content: content,
    category: category,
    ts: timestamp || Date.now(),
  };
  if (ingestKey) payload.api_key = ingestKey;
  const res = await fetch(`${API_BASE}/notification`, {
    method: "POST",
    headers,
    body: JSON.stringify(payload),
  });
  return res.json();
}

export async function sendScreenState(deviceId, state) {
  const API_BASE = await getApiBase();
  const { headers, ingestKey } = await buildAuthHeaders();
  const payload = {
    device_id: deviceId,
    state, // "ON" or "OFF"
    ts: Date.now(),
  };
  if (ingestKey) payload.api_key = ingestKey;
  const res = await fetch(`${API_BASE}/screen`, {
    method: "POST",
    headers,
    body: JSON.stringify(payload),
  });
  return res.json();
}

export async function sendGyroscope(device_id, { ts, x, y, z, magnitude }) {
  const API_BASE = await getApiBase();
  const body = { device_id, ts, x, y, z, magnitude };
  const { headers, ingestKey } = await buildAuthHeaders();
  if (ingestKey) body.api_key = ingestKey;
  //console.log("Sending gyro:", body);

  const res = await fetch(`${API_BASE}/gyroscope`, {
    method: "POST",
    headers,
    body: JSON.stringify(body),
  });

  return res.json();
}

export async function sendAccelerometer(device_id, { ts, x, y, z, magnitude }) {
  const API_BASE = await getApiBase();
  const body = { device_id, ts, x, y, z, magnitude };
  const { headers, ingestKey } = await buildAuthHeaders();
  if (ingestKey) body.api_key = ingestKey;
  //console.log("Sending accel:", body);

  const res = await fetch(`${API_BASE}/accelerometer`, {
    method: "POST",
    headers,
    body: JSON.stringify(body),
  });

  return res.json();
}

export async function sendPedometer(device_id, { ts, steps }) {
  const API_BASE = await getApiBase();
  const body = { device_id, ts, steps };
  const { headers, ingestKey } = await buildAuthHeaders();
  if (ingestKey) body.api_key = ingestKey;
  console.log("Sending pedometer:", body);

  const res = await fetch(`${API_BASE}/pedometer`, {
    method: "POST",
    headers,
    body: JSON.stringify(body),
  });

  return res.json();
}

export async function sendLocation(device_id, { ts, latitude, longitude, accuracy, altitude, speed }) {
  const API_BASE = await getApiBase();
  const body = { device_id, ts, latitude, longitude, accuracy, altitude, speed };
  const { headers, ingestKey } = await buildAuthHeaders();
  if (ingestKey) body.api_key = ingestKey;
  //console.log("Sending location:", body);

  const res = await fetch(`${API_BASE}/location`, {
    method: "POST",
    headers,
    body: JSON.stringify(body),
  });

  return res.json();
}

/**
 * Fetch participant info from the backend by device ID.
 * @returns {Promise<Object|null>} participant object or null if not found
 */
export async function getParticipant(deviceId) {
  const API_BASE = await getApiBase();
  try {
    const { headers } = await buildAuthHeaders();
    const url = await addApiKeyToUrl(`${API_BASE}/participants/${encodeURIComponent(deviceId)}`);
    const res = await fetch(url, { headers });
    if (res.status === 404) return null;
    if (!res.ok) return null;
    return await res.json();
  } catch (e) {
    console.warn("getParticipant error:", e);
    return null;
  }
}
