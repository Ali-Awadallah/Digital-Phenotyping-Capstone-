// /DP_demo/demo/awareApi.js

const API_BASE = "http://192.168.10.10:8080/api"; // VM-backend IP

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
