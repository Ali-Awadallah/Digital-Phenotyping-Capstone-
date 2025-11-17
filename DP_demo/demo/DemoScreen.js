import React, { useEffect, useState } from "react";
import { View, Text, Button, FlatList } from "react-native";

const API_BASE = "http://192.168.10.10:8080/api";

async function testConnection() {
  const r = await fetch(`${API_BASE}/testing`);
  return r.json(); // { ok: true }
}
async function sendEvent(device_id, value) {
  const r = await fetch(`${API_BASE}/events`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ device_id, ts: Date.now(), value }),
  });
  return r.json();
}
async function getEvents(device_id) {
  const now = Date.now();
  const r = await fetch(`${API_BASE}/events?device_id=${device_id}&start=0&end=${now}`);
  return r.json();
}

export function DemoScreen() {
  // ✅ define state INSIDE the component
  const [connected, setConnected] = useState(null); // null = loading
  const [events, setEvents] = useState([]);

  useEffect(() => {
    (async () => {
      try {
        const j = await testConnection();
        console.log("Backend /testing:", j);
        setConnected(j?.ok === true);
      } catch (e) {
        console.log("Backend /testing error:", e.message);
        setConnected(false);
      }
    })();
  }, []);

  const handleSend = async () => {
    try {
      await sendEvent("demo-phone", "hello-from-app");
      const data = await getEvents("demo-phone");
      setEvents(Array.isArray(data) ? data : []);
    } catch (e) {
      console.log("send/get error:", e.message);
    }
  };

  return (
    <View style={{ flex: 1, padding: 30 }}>
      <Text>
        Backend Connected: {connected === null ? "…" : connected ? "✅" : "❌"}
      </Text>
      <Button title="Send Event" onPress={handleSend} />
      <FlatList
        data={events}
        keyExtractor={(_, i) => String(i)}
        renderItem={({ item }) => <Text>{JSON.stringify(item, null, 2)}</Text>}
      />
    </View>
  );
}
