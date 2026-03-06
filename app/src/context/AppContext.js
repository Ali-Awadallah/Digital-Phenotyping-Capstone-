import {
  createContext,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { Platform, Alert, NativeModules } from "react-native";
import AsyncStorage from "@react-native-async-storage/async-storage";
import * as FileSystem from "expo-file-system/legacy";
import Constants from "expo-constants";
import * as Location from "expo-location";
import { Accelerometer, Gyroscope, Pedometer } from "expo-sensors";
import * as Battery from "expo-battery";
import { useScreenEventsEx2 } from "../hooks/useScreenEventsEx2";
import { useNotificationEventsEx2 } from "../hooks/useNotificationEventsEx2";
import BackgroundService from "../services/BackgroundService";
import { getApiBase } from "../../awareAPI";

// native modules
const AppUsageNative = NativeModules.AppUsage;
const NotificationAccessNative = NativeModules.NotificationAccess;

const AppContext = createContext(null);

const PREFS_KEY = "prefs_v1";

export function AppProvider({ children }) {
  const [activeTab, setActiveTab] = useState("Home");
  const [totalScreenTime, setTotalScreenTime] = useState(0);
  const [activityCount, setActivityCount] = useState(0);
  const [alerts, setAlerts] = useState([]);

  // Sensor states
  const [location, setLocation] = useState(null);
  const [accelerometerData, setAccelerometerData] = useState(null);
  const [gyroscopeData, setGyroscopeData] = useState(null);
  const [isLocationEnabled, setIsLocationEnabled] = useState(false);
  const [accelerometerActive, setAccelerometerActive] = useState(false);
  const [gyroscopeActive, setGyroscopeActive] = useState(false);

  // Collection toggles
  const [collectLocation, setCollectLocation] = useState(true);
  const [collectAccelerometer, setCollectAccelerometer] = useState(true);
  const [collectGyroscope, setCollectGyroscope] = useState(true);
  const [collectPedometer, setCollectPedometer] = useState(true);
  const [collectBattery, setCollectBattery] = useState(true);
  const [collectScreenEvents, setCollectScreenEvents] = useState(true);
  const [collectAppUsage, setCollectAppUsage] = useState(true);
  const [collectNotifications, setCollectNotifications] = useState(true);

  // Background service state
  const [isBackgroundServiceRunning, setIsBackgroundServiceRunning] = useState(false);
  const [backgroundServiceEnabled, setBackgroundServiceEnabled] = useState(true);

  // Load/save preferences
  const [prefsLoaded, setPrefsLoaded] = useState(false);
  useEffect(() => {
    (async () => {
      try {
        const raw = await AsyncStorage.getItem(PREFS_KEY);
        if (raw) {
          const p = JSON.parse(raw);
          if (typeof p.collectLocation === "boolean")
            setCollectLocation(p.collectLocation);
          if (typeof p.collectAccelerometer === "boolean")
            setCollectAccelerometer(p.collectAccelerometer);
          if (typeof p.collectGyroscope === "boolean")
            setCollectGyroscope(p.collectGyroscope);
          if (typeof p.collectPedometer === "boolean")
            setCollectPedometer(p.collectPedometer);
          if (typeof p.collectBattery === "boolean")
            setCollectBattery(p.collectBattery);
          if (typeof p.collectScreenEvents === "boolean")
            setCollectScreenEvents(p.collectScreenEvents);
          if (typeof p.collectAppUsage === "boolean")
            setCollectAppUsage(p.collectAppUsage);
          if (typeof p.collectNotifications === "boolean")
            setCollectNotifications(p.collectNotifications);
          if (typeof p.backgroundServiceEnabled === "boolean")
            setBackgroundServiceEnabled(p.backgroundServiceEnabled);
        }
      } catch { }

      // Sync saved server URL to native BackendAPIClient on every app launch
      try {
        const savedUrl = await getApiBase();
        if (savedUrl && BackgroundService.isAvailable()) {
          await BackgroundService.setAPIBaseURL(savedUrl);
        }
      } catch (e) {
        console.warn("Failed to sync server URL to native on startup:", e);
      }

      setPrefsLoaded(true);
    })();
  }, []);

  useEffect(() => {
    if (!prefsLoaded) return;
    const data = {
      collectLocation,
      collectAccelerometer,
      collectGyroscope,
      collectPedometer,
      collectBattery,
      collectScreenEvents,
      collectAppUsage,
      collectNotifications,
      backgroundServiceEnabled,
    };
    AsyncStorage.setItem(PREFS_KEY, JSON.stringify(data)).catch(() => { });
  }, [
    prefsLoaded,
    collectLocation,
    collectAccelerometer,
    collectGyroscope,
    collectPedometer,
    collectBattery,
    collectScreenEvents,
    collectAppUsage,
    collectNotifications,
    backgroundServiceEnabled,
  ]);

  // Background service management
  useEffect(() => {
    if (!prefsLoaded) return;
    // Check initial service status
    const checkStatus = async () => {
      if (BackgroundService.isAvailable()) {
        const running = await BackgroundService.isServiceRunning();
        setIsBackgroundServiceRunning(running);
      }
    };
    checkStatus();
  }, [prefsLoaded]);

  const startBackgroundService = async () => {
    if (!BackgroundService.isAvailable()) {
      console.warn('Background service not available on this platform');
      return false;
    }
    try {
      await BackgroundService.startBackgroundCollection();
      setIsBackgroundServiceRunning(true);
      setBackgroundServiceEnabled(true);
      return true;
    } catch (e) {
      console.error('Failed to start background service:', e);
      return false;
    }
  };

  const stopBackgroundService = async () => {
    if (!BackgroundService.isAvailable()) {
      return false;
    }
    try {
      await BackgroundService.stopBackgroundCollection();
      setIsBackgroundServiceRunning(false);
      setBackgroundServiceEnabled(false);
      return true;
    } catch (e) {
      console.error('Failed to stop background service:', e);
      return false;
    }
  };

  const getBackgroundSensorData = async (sensorType) => {
    if (!BackgroundService.isAvailable()) {
      return [];
    }
    return await BackgroundService.getCollectedData(sensorType);
  };

  // OS-level location services watcher: alert when device location is OFF
  useEffect(() => {
    let cancelled = false;
    let intervalId = null;
    const serviceAlertedRef = { current: false };
    const checkServices = async () => {
      try {
        const enabled = await Location.hasServicesEnabledAsync();
        if (!enabled && !serviceAlertedRef.current) {
          setAlerts((prev) => [
            ...prev,
            {
              id: Date.now(),
              ts: Date.now(),
              type: "device_location_off",
              severity: "medium",
              title: "Device Location Off",
              message: "System location services are disabled on this device.",
            },
          ]);
          serviceAlertedRef.current = true;
        }
        if (enabled) {
          serviceAlertedRef.current = false;
        }
      } catch { }
    };
    // initial and periodic checks
    checkServices();
    intervalId = setInterval(checkServices, 15000);
    return () => {
      cancelled = true;
      if (intervalId) clearInterval(intervalId);
    };
  }, []);

  // Screen events (toggle-aware)
  const {
    events: screenEvents,
    meta: screenMeta,
    refreshNow: refreshScreenEvents,
  } = useScreenEventsEx2(prefsLoaded && collectScreenEvents);

  // Notification events (toggle-aware)
  const {
    events: notificationEvents,
    meta: notificationMeta,
    refreshNow: refreshNotificationEvents,
  } = useNotificationEventsEx2(prefsLoaded && collectNotifications);

  // Create/remove native sentinel file to disable/enable screen events logging at source
  useEffect(() => {
    if (!prefsLoaded) return;
    if (Platform.OS !== "android") return;
    const pkg =
      (Constants?.expoConfig &&
        Constants.expoConfig.android &&
        Constants.expoConfig.android.package) ||
      "com.dp.demo";
    const candidates = [];
    if (FileSystem.documentDirectory)
      candidates.push(FileSystem.documentDirectory + "screen-events.disabled");
    candidates.push(`file:///data/user/0/${pkg}/files/screen-events.disabled`);
    (async () => {
      try {
        for (const p of candidates) {
          try {
            const info = await FileSystem.getInfoAsync(p);
            if (!collectScreenEvents) {
              if (!info.exists) await FileSystem.writeAsStringAsync(p, "off");
            } else {
              if (info.exists)
                await FileSystem.deleteAsync(p, { idempotent: true });
            }
          } catch { }
        }
      } catch { }
    })();
  }, [prefsLoaded, collectScreenEvents]);

  // Create/remove native sentinel file to disable/enable notifications logging at source
  useEffect(() => {
    if (!prefsLoaded) return;
    if (Platform.OS !== "android") return;
    const pkg =
      (Constants?.expoConfig &&
        Constants.expoConfig.android &&
        Constants.expoConfig.android.package) ||
      "com.dp.demo";
    const candidates = [];
    if (FileSystem.documentDirectory)
      candidates.push(
        FileSystem.documentDirectory + "notifications.disabled"
      );
    candidates.push(
      `file:///data/user/0/${pkg}/files/notifications.disabled`
    );
    (async () => {
      try {
        for (const p of candidates) {
          try {
            const info = await FileSystem.getInfoAsync(p);
            if (!collectNotifications) {
              if (!info.exists) await FileSystem.writeAsStringAsync(p, "off");
            } else {
              if (info.exists)
                await FileSystem.deleteAsync(p, { idempotent: true });
            }
          } catch { }
        }
      } catch { }
    })();
  }, [prefsLoaded, collectNotifications]);

  // Battery
  const [batteryLevel, setBatteryLevel] = useState(null);
  const [batteryState, setBatteryState] = useState(null);

  // Pedometer
  const [isPedometerAvailable, setIsPedometerAvailable] = useState(false);
  const [pedometerGranted, setPedometerGranted] = useState(null);
  const [stepsToday, setStepsToday] = useState(0);
  const [stepsSinceOpen, setStepsSinceOpen] = useState(0);
  const stepsSinceOpenRef = useRef(0);
  const pedoBaselineRef = useRef(0); // persisted baseline for today
  const pedoDateRef = useRef(null); // 'YYYY-MM-DD'

  const todayStr = () => {
    const d = new Date();
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(
      2,
      "0"
    )}-${String(d.getDate()).padStart(2, "0")}`;
  };

  // Load persisted pedometer snapshot (baseline for today)
  useEffect(() => {
    (async () => {
      try {
        const raw = await AsyncStorage.getItem("pedometer_v1");
        const today = todayStr();
        if (raw) {
          const { date, steps } = JSON.parse(raw);
          pedoDateRef.current = date || today;
          if (date === today && typeof steps === "number") {
            pedoBaselineRef.current = steps;
            setStepsToday(steps);
          } else {
            pedoBaselineRef.current = 0;
            pedoDateRef.current = today;
            await AsyncStorage.setItem(
              "pedometer_v1",
              JSON.stringify({ date: today, steps: 0 })
            );
          }
        } else {
          pedoBaselineRef.current = 0;
          pedoDateRef.current = today;
          await AsyncStorage.setItem(
            "pedometer_v1",
            JSON.stringify({ date: today, steps: 0 })
          );
        }
      } catch { }
    })();
  }, []);

  // Request location permissions and start/stop location tracking based on toggle
  const locationSubRef = useRef(null);
  useEffect(() => {
    let cancelled = false;
    const ensureLocation = async () => {
      if (!prefsLoaded) return;
      try {
        if (!collectLocation) {
          try {
            locationSubRef.current &&
              locationSubRef.current.remove &&
              locationSubRef.current.remove();
          } catch { }
          locationSubRef.current = null;
          if (!cancelled) setIsLocationEnabled(false);
          return;
        }
        const { status } = await Location.requestForegroundPermissionsAsync();
        if (status !== "granted") {
          if (!cancelled) setIsLocationEnabled(false);
          return;
        }
        try {
          locationSubRef.current &&
            locationSubRef.current.remove &&
            locationSubRef.current.remove();
        } catch { }
        const sub = await Location.watchPositionAsync(
          {
            accuracy: Location.Accuracy.High,
            timeInterval: 1000,
            distanceInterval: 1,
          },
          (newLocation) => {
            setLocation(newLocation.coords);
          }
        );
        locationSubRef.current = sub;
        if (!cancelled) setIsLocationEnabled(true);
      } catch (error) {
        if (!cancelled) setIsLocationEnabled(false);
      }
    };
    ensureLocation();
    return () => {
      cancelled = true;
      try {
        locationSubRef.current &&
          locationSubRef.current.remove &&
          locationSubRef.current.remove();
      } catch { }
      locationSubRef.current = null;
    };
  }, [prefsLoaded, collectLocation]);

  // Accelerometer on/off
  const accelerometerSubRef = useRef(null);
  useEffect(() => {
    let cancelled = false;
    const run = async () => {
      if (!prefsLoaded) return;
      try {
        const available = await Accelerometer.isAvailableAsync();
        if (!available || !collectAccelerometer) {
          try {
            accelerometerSubRef.current &&
              accelerometerSubRef.current.remove &&
              accelerometerSubRef.current.remove();
          } catch { }
          accelerometerSubRef.current = null;
          if (!cancelled) setAccelerometerActive(false);
          return;
        }
        Accelerometer.setUpdateInterval(100);
        try {
          accelerometerSubRef.current &&
            accelerometerSubRef.current.remove &&
            accelerometerSubRef.current.remove();
        } catch { }
        const sub = Accelerometer.addListener((data) =>
          setAccelerometerData(data)
        );
        accelerometerSubRef.current = sub;
        if (!cancelled) setAccelerometerActive(true);
      } catch {
        if (!cancelled) setAccelerometerActive(false);
      }
    };
    run();
    return () => {
      try {
        accelerometerSubRef.current &&
          accelerometerSubRef.current.remove &&
          accelerometerSubRef.current.remove();
      } catch { }
      accelerometerSubRef.current = null;
    };
  }, [prefsLoaded, collectAccelerometer]);

  // Gyroscope on/off
  const gyroscopeSubRef = useRef(null);
  useEffect(() => {
    let cancelled = false;
    const run = async () => {
      if (!prefsLoaded) return;
      try {
        const available = await Gyroscope.isAvailableAsync();
        if (!available || !collectGyroscope) {
          try {
            gyroscopeSubRef.current &&
              gyroscopeSubRef.current.remove &&
              gyroscopeSubRef.current.remove();
          } catch { }
          gyroscopeSubRef.current = null;
          if (!cancelled) setGyroscopeActive(false);
          return;
        }
        Gyroscope.setUpdateInterval(100);
        try {
          gyroscopeSubRef.current &&
            gyroscopeSubRef.current.remove &&
            gyroscopeSubRef.current.remove();
        } catch { }
        const sub = Gyroscope.addListener((data) => setGyroscopeData(data));
        gyroscopeSubRef.current = sub;
        if (!cancelled) setGyroscopeActive(true);
      } catch {
        if (!cancelled) setGyroscopeActive(false);
      }
    };
    run();
    return () => {
      try {
        gyroscopeSubRef.current &&
          gyroscopeSubRef.current.remove &&
          gyroscopeSubRef.current.remove();
      } catch { }
      gyroscopeSubRef.current = null;
    };
  }, [prefsLoaded, collectGyroscope]);

  // Battery info (toggle-aware)
  useEffect(() => {
    let levelSub = null;
    let stateSub = null;
    const init = async () => {
      try {
        if (!prefsLoaded) return;
        if (!collectBattery) {
          setBatteryLevel(null);
          setBatteryState(null);
          return;
        }
        const level = await Battery.getBatteryLevelAsync();
        setBatteryLevel(level);
        const st = await Battery.getBatteryStateAsync();
        setBatteryState(st);
      } catch (e) {
        // ignore
      }
    };
    init();
    try {
      if (prefsLoaded && collectBattery) {
        levelSub = Battery.addBatteryLevelListener(({ batteryLevel }) =>
          setBatteryLevel(batteryLevel)
        );
        stateSub = Battery.addBatteryStateListener(({ batteryState }) =>
          setBatteryState(batteryState)
        );
      }
    } catch (e) {
      // ignore
    }
    return () => {
      try {
        levelSub && levelSub.remove && levelSub.remove();
      } catch { }
      try {
        stateSub && stateSub.remove && stateSub.remove();
      } catch { }
    };
  }, [prefsLoaded, collectBattery]);

  // Pedometer steps (toggle-aware)
  useEffect(() => {
    let stepSub = null;
    let intervalId = null;
    const updateSteps = async () => {
      try {
        if (!prefsLoaded) return;
        // Reset baseline if day changed
        const t = todayStr();
        if (pedoDateRef.current && pedoDateRef.current !== t) {
          pedoDateRef.current = t;
          pedoBaselineRef.current = 0;
          stepsSinceOpenRef.current = 0;
          setStepsSinceOpen(0);
          try {
            await AsyncStorage.setItem(
              "pedometer_v1",
              JSON.stringify({ date: t, steps: 0 })
            );
          } catch { }
        }
        const end = new Date();
        const start = new Date();
        start.setHours(0, 0, 0, 0);
        let daily = 0;
        try {
          const result = await Pedometer.getStepCountAsync(start, end);
          if (result && typeof result.steps === "number") daily = result.steps;
        } catch (e) {
          // On many Android devices this isn't supported; fallback
        }
        if (daily > 0) {
          setStepsToday(daily);
          pedoBaselineRef.current = daily;
          pedoDateRef.current = t;
          try {
            await AsyncStorage.setItem(
              "pedometer_v1",
              JSON.stringify({ date: t, steps: daily })
            );
          } catch { }
        } else {
          const est =
            (pedoBaselineRef.current || 0) + (stepsSinceOpenRef.current || 0);
          setStepsToday(est);
          try {
            await AsyncStorage.setItem(
              "pedometer_v1",
              JSON.stringify({ date: pedoDateRef.current || t, steps: est })
            );
          } catch { }
        }
      } catch (e) {
        // ignore
      }
    };
    const init = async () => {
      try {
        if (!collectPedometer) {
          setIsPedometerAvailable(false);
          return;
        }
        if (Pedometer.requestPermissionsAsync) {
          try {
            const { status, granted } =
              await Pedometer.requestPermissionsAsync();
            setPedometerGranted(granted ?? status === "granted");
          } catch (e) {
            setPedometerGranted(false);
          }
        }
        const available = await Pedometer.isAvailableAsync();
        setIsPedometerAvailable(!!available && collectPedometer);
        if (!available) return;
        await updateSteps();
        stepSub = Pedometer.watchStepCount(({ steps }) => {
          const val = steps || 0;
          stepsSinceOpenRef.current = val;
          setStepsSinceOpen(val);
          updateSteps();
        });
        intervalId = setInterval(updateSteps, 30 * 1000);
      } catch (e) {
        setIsPedometerAvailable(false);
      }
    };
    init();
    return () => {
      try {
        stepSub && stepSub.remove && stepSub.remove();
      } catch { }
      if (intervalId) clearInterval(intervalId);
    };
  }, [prefsLoaded, collectPedometer]);

  // App usage (Android)
  const [appsUsage, setAppsUsage] = useState([]);
  const [hasUsageAccess, setHasUsageAccess] = useState(true);
  const [hasNotificationAccess, setHasNotificationAccess] = useState(false);

  const fetchUsage = async () => {
    try {
      if (Platform.OS !== "android" || !AppUsageNative) return;
      const allowed = await AppUsageNative.hasUsageAccess();
      setHasUsageAccess(!!allowed);
      if (!allowed) return;
      const res = await AppUsageNative.getUsageStatsForToday();
      const apps = res?.apps || [];
      setAppsUsage(apps);
      const totalMs = res?.totalMs || 0;
      setTotalScreenTime(Math.round(totalMs / 60000));
    } catch (e) {
      // ignore
    }
  };

  const openUsageAccess = async () => {
    try {
      AppUsageNative?.openUsageAccessSettings();
    } catch (e) { }
  };

  const openNotificationAccess = async () => {
    try {
      NotificationAccessNative?.openSettings?.();
    } catch (e) { }
  };

  const checkNotificationAccess = async () => {
    try {
      const granted = await NotificationAccessNative?.hasAccess?.();
      setHasNotificationAccess(!!granted);
    } catch (e) { }
  };

  useEffect(() => {
    if (Platform.OS === "android") {
      checkNotificationAccess();
    }
  }, []);

  useEffect(() => {
    if (!prefsLoaded || !collectAppUsage) {
      setAppsUsage([]);
      setTotalScreenTime(0);
      return;
    }
    fetchUsage();
    const id = setInterval(fetchUsage, 60 * 1000);
    return () => clearInterval(id);
  }, [prefsLoaded, collectAppUsage]);

  // Alert when total screen time is >= 4 hours (240 min) per day -> push to Alerts tab
  const lastScreenTimeAlertRef = useRef(null);
  useEffect(() => {
    if (!collectAppUsage || !hasUsageAccess) return;
    try {
      if (totalScreenTime >= 240) {
        const today = new Date().toDateString();
        if (lastScreenTimeAlertRef.current !== today) {
          setAlerts((prev) => [
            ...prev,
            {
              id: Date.now(),
              ts: Date.now(),
              type: "screen_time",
              severity: "high",
              title: "High Screen Time",
              message: "Your total screen time today exceeded 4 hours.",
            },
          ]);
          lastScreenTimeAlertRef.current = today;
        }
      }
    } catch (e) {
      // ignore
    }
  }, [totalScreenTime, collectAppUsage, hasUsageAccess]);

  const value = useMemo(
    () => ({
      // navigation helpers
      activeTab,
      setActiveTab,
      // alerts
      alerts,
      setAlerts,
      // usage
      totalScreenTime,
      appsUsage,
      hasUsageAccess,
      fetchUsage,
      openUsageAccess,
      hasNotificationAccess,
      openNotificationAccess,
      checkNotificationAccess,
      openNotificationAccess,
      // sensors
      location,
      accelerometerData,
      gyroscopeData,
      isLocationEnabled,
      accelerometerActive,
      gyroscopeActive,
      // toggles
      collectLocation,
      setCollectLocation,
      collectAccelerometer,
      setCollectAccelerometer,
      collectGyroscope,
      setCollectGyroscope,
      collectPedometer,
      setCollectPedometer,
      collectBattery,
      setCollectBattery,
      collectScreenEvents,
      setCollectScreenEvents,
      collectAppUsage,
      setCollectAppUsage,
      collectNotifications,
      setCollectNotifications,
      // battery
      batteryLevel,
      batteryState,
      // pedometer
      isPedometerAvailable,
      stepsToday,
      // screen events
      screenEvents,
      screenMeta,
      refreshScreenEvents,
      // notifications
      notificationEvents,
      notificationMeta,
      refreshNotificationEvents,
      // background service
      isBackgroundServiceRunning,
      backgroundServiceEnabled,
      startBackgroundService,
      stopBackgroundService,
      getBackgroundSensorData,
    }),
    [
      activeTab,
      alerts,
      totalScreenTime,
      appsUsage,
      hasUsageAccess,
      hasNotificationAccess,
      location,
      accelerometerData,
      gyroscopeData,
      isLocationEnabled,
      accelerometerActive,
      gyroscopeActive,
      collectLocation,
      collectAccelerometer,
      collectGyroscope,
      collectPedometer,
      collectBattery,
      collectScreenEvents,
      collectAppUsage,
      collectNotifications,
      batteryLevel,
      batteryState,
      isPedometerAvailable,
      stepsToday,
      screenEvents,
      screenMeta,
      notificationEvents,
      notificationMeta,
      isBackgroundServiceRunning,
      backgroundServiceEnabled,
    ]
  );

  return <AppContext.Provider value={value}>{children}</AppContext.Provider>;
}

export function useApp() {
  const ctx = useContext(AppContext);
  if (!ctx) throw new Error("useApp must be used within AppProvider");
  return ctx;
}
