import React, { useEffect, useState } from "react";
import * as FileSystem from "expo-file-system/legacy";
import Constants from "expo-constants";

const LOG_FILE = (FileSystem.documentDirectory || "") + "notification-events.log";

// Safer reader that tries multiple paths and tolerates undefined documentDirectory
export function useNotificationEventsEx2(enabled = true) {
  const [meta, setMeta] = useState({
    targetPath: LOG_FILE,
    exists: false,
    lastRead: null,
    error: null,
  });
  const [events, setEvents] = useState([]);

  const readLog = React.useCallback(async () => {
    if (!enabled) return;
    const pkg =
      (Constants?.expoConfig &&
        Constants.expoConfig.android &&
        Constants.expoConfig.android.package) ||
      "com.dp.demo";
    const candidates = [];
    if (FileSystem.documentDirectory) {
      candidates.push(FileSystem.documentDirectory + "notification-events.log");
    }
    candidates.push(`file:///data/user/0/${pkg}/files/notification-events.log`);

    let lastErr = null;
    for (const p of candidates) {
      try {
        const info = await FileSystem.getInfoAsync(p);
        if (info && info.exists) {
          let content = await FileSystem.readAsStringAsync(p);
          if (content && content.includes("\\n"))
            content = content.replace(/\\n/g, "\n");
          const lines = (content || "")
            .split("\n")
            .filter(Boolean);
          const parsed = lines
            .map((l) => {
              try {
                return JSON.parse(l);
              } catch {
                return null;
              }
            })
            .filter(Boolean);
          setEvents(parsed);
          setMeta({
            targetPath: p,
            exists: true,
            lastRead: Date.now(),
            error: null,
          });
          return;
        }
      } catch (e) {
        lastErr = String(e?.message || e);
      }
    }

    setEvents([]);
    setMeta({
      targetPath: candidates[candidates.length - 1],
      exists: false,
      lastRead: Date.now(),
      error: lastErr,
    });
  }, [enabled]);

  useEffect(() => {
    if (!enabled) {
      setEvents([]);
      setMeta((m) => ({
        ...m,
        exists: false,
        lastRead: Date.now(),
        error: null,
      }));
      return;
    }
    const id = setInterval(() => {
      readLog();
    }, 5000);
    readLog();
    return () => clearInterval(id);
  }, [enabled, readLog]);

  return { events, meta, refreshNow: readLog };
}

