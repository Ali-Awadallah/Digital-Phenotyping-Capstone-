// /DP_demo/demo/awareApi.js

const API_BASE = "http://192.168.10.3:8080/api"; // Put your own machine IP here or VM-backend IP

export async function testConnection() {
  const res = await fetch(`${API_BASE}/testing`);
  return res.json(); // { ok: true }
}

export async function sendEvent(device_id, value) {
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
  const now = Date.now();
  const res = await fetch(
    `${API_BASE}/events?device_id=${device_id}&start=0&end=${now}`
  );
  return res.json(); // returns an array of events
}

export async function sendBatteryReading(deviceId, percentage, chargingStatus = "unknown") {
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
  const body = { device_id, ts, x, y, z, magnitude };
  console.log("Sending gyro:", body);

  const res = await fetch(`${API_BASE}/gyroscope`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });

  return res.json();
}

export async function sendAccelerometer(device_id, { ts, x, y, z, magnitude }) {
  const body = { device_id, ts, x, y, z, magnitude };
  console.log("Sending accel:", body);

  const res = await fetch(`${API_BASE}/accelerometer`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });

  return res.json();
}

export async function sendPedometer(device_id, { ts, steps }) {
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
  const body = { device_id, ts, latitude, longitude, accuracy, altitude, speed };
  console.log("Sending location:", body);

  const res = await fetch(`${API_BASE}/location`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });

  return res.json();
}