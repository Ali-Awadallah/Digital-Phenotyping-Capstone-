import React, { useEffect } from "react";
import {
  View,
  Text,
  TouchableOpacity,
  StyleSheet,
  Platform,
} from "react-native";
import { Ionicons as Icon } from "@expo/vector-icons";
import MaterialIcons from "@expo/vector-icons/MaterialIcons";
import * as Battery from "expo-battery";
import { useApp } from "../context/AppContext";
import * as FileSystem from "expo-file-system/legacy";
import ScreenContainer from "../components/ScreenContainer";

export default function SensorsScreen() {
  const {
    location,
    accelerometerData,
    gyroscopeData,
    isLocationEnabled,
    accelerometerActive,
    gyroscopeActive,
    batteryLevel,
    batteryState,
    collectBattery,
    isPedometerAvailable,
    stepsToday,
    screenEvents,
    screenMeta,
    refreshScreenEvents,
    collectScreenEvents,
    notificationEvents,
    refreshNotificationEvents,
    hasNotificationAccess,
    openNotificationAccess,
    checkNotificationAccess,
    collectNotifications,
  } = useApp();

  useEffect(() => {
    if (Platform.OS === "android" && checkNotificationAccess) {
      checkNotificationAccess();
    }
  }, [checkNotificationAccess]);

  return (
    <ScreenContainer>
      <View style={{ flexDirection: "row", alignItems: "center" }}>
        <MaterialIcons
          name="sensors"
          size={28}
          color="#15d6a9"
          style={{ marginRight: 8, marginBottom: 16 }}
        />
        <Text style={styles.contentTitle}>Sensor Data</Text>
      </View>
      <Text style={styles.infoText}>
        Real-time sensor data for digital phenotyping analysis. You can
        Opt-in/out from any certain data collection from the Settings Tab
      </Text>

      <Text style={styles.Title}>Device Sensors</Text>

      {/* Location */}
      <View style={styles.sensorSection}>
        <View style={styles.sensorHeader}>
          <Icon name="location-outline" size={24} color="#15d6a9" />
          <Text style={styles.sensorTitle}>Location</Text>
          <View
            style={[
              styles.statusIndicator,
              { backgroundColor: isLocationEnabled ? "#32D74B" : "#FF3B30" },
            ]}
          />
        </View>
        {isLocationEnabled && location ? (
          <View style={styles.sensorData}>
            <Text style={styles.sensorLabel}>
              Latitude:{" "}
              <Text style={styles.sensorValue}>
                {location.latitude.toFixed(6)}
              </Text>
            </Text>
            <Text style={styles.sensorLabel}>
              Longitude:{" "}
              <Text style={styles.sensorValue}>
                {location.longitude.toFixed(6)}
              </Text>
            </Text>
            <Text style={styles.sensorLabel}>
              Accuracy:{" "}
              <Text style={styles.sensorValue}>
                {location.accuracy?.toFixed(2)}m
              </Text>
            </Text>
            <Text style={styles.sensorLabel}>
              Altitude:{" "}
              <Text style={styles.sensorValue}>
                {location.altitude?.toFixed(2)}m
              </Text>
            </Text>
            <Text style={styles.sensorLabel}>
              Speed:{" "}
              <Text style={styles.sensorValue}>
                {location.speed?.toFixed(2)}m/s
              </Text>
            </Text>
          </View>
        ) : (
          <Text style={styles.sensorDisabled}>
            Location access denied or unavailable
          </Text>
        )}
      </View>

      {/* Accelerometer */}
      <View style={styles.sensorSection}>
        <View style={styles.sensorHeader}>
          <Icon name="phone-portrait-outline" size={24} color="#FF9500" />
          <Text style={styles.sensorTitle}>Accelerometer</Text>
          <View
            style={[
              styles.statusIndicator,
              { backgroundColor: accelerometerActive ? "#32D74B" : "#FF3B30" },
            ]}
          />
        </View>
        {accelerometerActive && accelerometerData ? (
          <View style={styles.sensorData}>
            <Text style={styles.sensorLabel}>
              X-axis:{" "}
              <Text style={styles.sensorValue}>
                {accelerometerData.x.toFixed(3)}g
              </Text>
            </Text>
            <Text style={styles.sensorLabel}>
              Y-axis:{" "}
              <Text style={styles.sensorValue}>
                {accelerometerData.y.toFixed(3)}g
              </Text>
            </Text>
            <Text style={styles.sensorLabel}>
              Z-axis:{" "}
              <Text style={styles.sensorValue}>
                {accelerometerData.z.toFixed(3)}g
              </Text>
            </Text>
            <Text style={styles.sensorLabel}>
              Magnitude:{" "}
              <Text style={styles.sensorValue}>
                {Math.sqrt(
                  accelerometerData.x ** 2 +
                    accelerometerData.y ** 2 +
                    accelerometerData.z ** 2
                ).toFixed(3)}
                g
              </Text>
            </Text>
          </View>
        ) : (
          <Text style={styles.sensorDisabled}>
            Accelerometer disabled or not available
          </Text>
        )}
      </View>

      {/* Gyroscope */}
      <View style={styles.sensorSection}>
        <View style={styles.sensorHeader}>
          <MaterialIcons name="screen-rotation" size={24} color="#32D74B" />
          <Text style={styles.sensorTitle}>Gyroscope</Text>
          <View
            style={[
              styles.statusIndicator,
              { backgroundColor: gyroscopeActive ? "#32D74B" : "#FF3B30" },
            ]}
          />
        </View>
        {gyroscopeActive && gyroscopeData ? (
          <View style={styles.sensorData}>
            <Text style={styles.sensorLabel}>
              X-axis:{" "}
              <Text style={styles.sensorValue}>
                {gyroscopeData.x.toFixed(3)} rad/s
              </Text>
            </Text>
            <Text style={styles.sensorLabel}>
              Y-axis:{" "}
              <Text style={styles.sensorValue}>
                {gyroscopeData.y.toFixed(3)} rad/s
              </Text>
            </Text>
            <Text style={styles.sensorLabel}>
              Z-axis:{" "}
              <Text style={styles.sensorValue}>
                {gyroscopeData.z.toFixed(3)} rad/s
              </Text>
            </Text>
            <Text style={styles.sensorLabel}>
              Magnitude:{" "}
              <Text style={styles.sensorValue}>
                {Math.sqrt(
                  gyroscopeData.x ** 2 +
                    gyroscopeData.y ** 2 +
                    gyroscopeData.z ** 2
                ).toFixed(3)}{" "}
                rad/s
              </Text>
            </Text>
          </View>
        ) : (
          <Text style={styles.sensorDisabled}>
            Gyroscope disabled or not available
          </Text>
        )}
      </View>

      {/* Pedometer */}
      <View style={styles.sensorSection}>
        <View style={styles.sensorHeader}>
          <Icon name="walk-outline" size={24} color="#5856D6" />
          <Text style={styles.sensorTitle}>Pedometer</Text>
          <View
            style={[
              styles.statusIndicator,
              { backgroundColor: isPedometerAvailable ? "#32D74B" : "#FF3B30" },
            ]}
          />
        </View>
        {isPedometerAvailable ? (
          <View style={styles.sensorData}>
            <Text style={styles.sensorLabel}>
              Steps today: <Text style={styles.sensorValue}>{stepsToday}</Text>
            </Text>
          </View>
        ) : (
          <Text style={styles.sensorDisabled}>
            Pedometer not available or permission denied
          </Text>
        )}
      </View>

      <Text style={styles.Title}>Software Sensors</Text>

      {/* Screen Power Events */}
      <View style={styles.sensorSection}>
        <View style={styles.sensorHeader}>
          <Icon name="power-outline" size={24} color="#cd2ad3ff" />
          <Text style={styles.sensorTitle}>Screen Events</Text>
          <View
            style={[
              styles.statusIndicator,
              {
                backgroundColor: !collectScreenEvents
                  ? "#FF3B30"
                  : screenEvents && screenEvents.length
                  ? "#32D74B"
                  : "#FFCC00",
              },
            ]}
          />
        </View>
        {!collectScreenEvents ? (
          <Text style={styles.sensorDisabled}>
            Screen events collection disabled
          </Text>
        ) : screenEvents && screenEvents.length > 0 ? (
          <View style={styles.sensorData}>
            <Text style={styles.sensorLabel}>
              Total events:{" "}
              <Text style={styles.sensorValue}>{screenEvents.length}</Text>
            </Text>
            {screenEvents.slice(-10).map((evt, idx) => (
              <Text key={idx} style={styles.sensorLabel}>
                {new Date(evt.ts).toLocaleString()} —{" "}
                <Text style={styles.sensorValue}>{evt.event}</Text>
              </Text>
            ))}
            <TouchableOpacity
              style={[
                styles.actionButton,
                { marginTop: 12, backgroundColor: "#15d6a9" },
              ]}
              onPress={refreshScreenEvents}
            >
              <Text style={styles.actionButtonText}>Refresh Now</Text>
            </TouchableOpacity>
          </View>
        ) : (
          <View>
            <Text style={styles.sensorDisabled}>
              No events yet. Lock/unlock the device to generate events.
            </Text>
            <Text style={[styles.sensorLabel, { marginTop: 8 }]}>
              Doc dir:{" "}
              <Text style={styles.sensorValue}>
                {FileSystem.documentDirectory}
              </Text>
            </Text>
            {screenMeta ? (
              <>
                <Text style={styles.sensorLabel}>
                  Path tried:{" "}
                  <Text style={styles.sensorValue}>
                    {screenMeta.targetPath || "n/a"}
                  </Text>{" "}
                  {`exists: ${screenMeta.exists ? "yes" : "no"}`}
                </Text>
                <Text style={styles.sensorLabel}>
                  Last read:{" "}
                  <Text style={styles.sensorValue}>
                    {screenMeta.lastRead
                      ? new Date(screenMeta.lastRead).toLocaleTimeString()
                      : "n/a"}
                  </Text>{" "}
                  {screenMeta.error ? `  err: ${screenMeta.error}` : ""}
                </Text>
              </>
            ) : null}
            <TouchableOpacity
              style={[
                styles.actionButton,
                { marginTop: 12, backgroundColor: "#15d6a9" },
              ]}
              onPress={refreshScreenEvents}
            >
              <Text style={styles.actionButtonText}>Refresh Now</Text>
            </TouchableOpacity>
          </View>
        )}
      </View>

      {/* Notifications */}
      <View style={styles.sensorSection}>
        <View style={styles.sensorHeader}>
          <Icon name="notifications-outline" size={24} color="#f5a623" />
          <Text style={styles.sensorTitle}>Notifications</Text>
          {(() => {
            const today = new Date();
            today.setHours(0, 0, 0, 0);
            const start = today.getTime();
            const todays = (notificationEvents || []).filter(
              (n) => n && typeof n.ts === "number" && n.ts >= start
            );
            const hasAny = todays.length > 0;
            const color = !collectNotifications
              ? "#FF3B30"
              : !hasNotificationAccess
              ? "#FF3B30"
              : hasAny
              ? "#32D74B"
              : "#FFCC00";
            return (
              <View
                style={[styles.statusIndicator, { backgroundColor: color }]}
              />
            );
          })()}
        </View>
        {(() => {
          const today = new Date();
          today.setHours(0, 0, 0, 0);
          const start = today.getTime();
          const todays = (notificationEvents || []).filter(
            (n) => n && typeof n.ts === "number" && n.ts >= start
          );

          if (!collectNotifications) {
            return (
              <View style={styles.sensorData}>
                <Text style={styles.sensorDisabled}>
                  Notification tracking is disabled in Settings.
                </Text>
              </View>
            );
          }

          if (!hasNotificationAccess) {
            return (
              <View style={styles.sensorData}>
                <Text style={styles.sensorDisabled}>
                  Notification access is not granted. Enable it to track
                  notification activity.
                </Text>
                <TouchableOpacity
                  style={[
                    styles.actionButton,
                    { marginTop: 12, backgroundColor: "#f5a623" },
                  ]}
                  onPress={openNotificationAccess}
                >
                  <Text style={styles.actionButtonText}>
                    Open Notification Access Settings
                  </Text>
                </TouchableOpacity>
              </View>
            );
          }

          if (!todays.length) {
            return (
              <Text style={styles.sensorDisabled}>
                No notifications logged today.
              </Text>
            );
          }

          const counts = {};
          todays.forEach((n) => {
            if (n.kind !== "posted") return;
            const cat = n.category || "other";
            counts[cat] = (counts[cat] || 0) + 1;
          });
          const rows = Object.entries(counts).sort((a, b) => b[1] - a[1]);

          const recent = todays.slice().reverse().slice(0, 5);

          return (
            <View style={styles.sensorData}>
              <Text
                style={[
                  styles.sensorLabel,
                  { marginTop: 8, fontFamily: "Archivo-SemiBold" },
                ]}
              >
                Total events today:{" "}
                <Text style={styles.sensorValue}>{todays.length}</Text>
              </Text>
              <Text
                style={[
                  styles.sensorLabel,
                  { marginTop: 8, fontFamily: "Archivo-SemiBold" },
                ]}
              >
                Total per Category:
              </Text>
              {rows.map(([cat, count]) => (
                <Text key={cat} style={styles.sensorLabel}>
                  {cat}: <Text style={styles.sensorValue}>{count}</Text>
                </Text>
              ))}
              <Text
                style={[
                  styles.sensorLabel,
                  { marginTop: 8, fontFamily: "Archivo-SemiBold" },
                ]}
              >
                Recent events:
              </Text>
              {recent.map((n, idx) => (
                <Text key={idx} style={styles.sensorLabel}>
                  {new Date(n.ts).toLocaleTimeString()} — {n.appName} (
                  {n.category || "other"}){" "}
                  {n.kind === "removed" ? "[removed]" : ""}
                  {n.title ? `: ${n.title}${n.text ? " - " + n.text : ""}` : ""}
                </Text>
              ))}
            </View>
          );
        })()}
      </View>

      {/* Battery */}
      <View style={styles.sensorSection}>
        <View style={styles.sensorHeader}>
          <Icon name="battery-charging-outline" size={24} color="#00ad03ff" />
          <Text style={styles.sensorTitle}>Battery</Text>
          <View
            style={[
              styles.statusIndicator,
              {
                backgroundColor: !collectBattery
                  ? "#FF3B30"
                  : batteryLevel != null && batteryLevel <= 0.2
                  ? "#FF3B30"
                  : batteryState === Battery.BatteryState.CHARGING ||
                    batteryState === Battery.BatteryState.FULL
                  ? "#32D74B"
                  : "#FFCC00",
              },
            ]}
          />
        </View>
        {collectBattery ? (
          <View style={styles.sensorData}>
            <Text style={styles.sensorLabel}>
              Level:{" "}
              <Text style={styles.sensorValue}>
                {batteryLevel != null ? Math.round(batteryLevel * 100) : "—"}%
              </Text>
            </Text>
            <Text style={styles.sensorLabel}>
              State:{" "}
              <Text style={styles.sensorValue}>
                {batteryState === Battery.BatteryState.CHARGING
                  ? "Charging"
                  : batteryState === Battery.BatteryState.FULL
                  ? "Full"
                  : batteryState === Battery.BatteryState.UNPLUGGED
                  ? "Unplugged"
                  : "Unknown"}
              </Text>
            </Text>
          </View>
        ) : (
          <Text style={styles.sensorDisabled}>Battery collection disabled</Text>
        )}
      </View>
    </ScreenContainer>
  );
}

const styles = StyleSheet.create({
  Title: {
    fontSize: 20,
    marginVertical: 10,
    fontFamily: "Archivo-Medium",
    color: "#ccccccff",
  },
  contentView: { flex: 1 },
  contentTitle: {
    fontSize: 24,
    marginBottom: 20,
    color: "#ccccccff",
    fontFamily: "Archivo-SemiBold",
  },
  infoText: {
    fontSize: 16,
    color: "#ccccccff",
    lineHeight: 24,
    marginVertical: 10,
    padding: 10,
    backgroundColor: "#222d3aff",
    borderRadius: 8,
    borderLeftWidth: 4,
    borderLeftColor: "#FF9500",
    fontFamily: "Archivo",
  },
  actionButton: {
    marginTop: 20,
    backgroundColor: "#32D74B",
    padding: 12,
    borderRadius: 8,
    alignItems: "center",
  },
  actionButtonText: {
    color: "#222d3aff",
    fontSize: 16,
    fontWeight: "600",
    fontFamily: "Archivo-Medium",
  },
  sensorSection: {
    backgroundColor: "#222d3aff",
    borderRadius: 12,
    padding: 16,
    marginBottom: 36,
    borderLeftWidth: 4,
    borderLeftColor: "#15d6a9",
    elevation: 3,
  },
  sensorHeader: {
    flexDirection: "row",
    alignItems: "center",
    marginBottom: 12,
  },
  sensorTitle: {
    fontSize: 18,
    color: "#ccccccff",
    marginLeft: 8,
    flex: 1,
    fontFamily: "Archivo",
  },
  statusIndicator: { width: 12, height: 12, borderRadius: 6 },
  sensorData: {
    backgroundColor: "#293646ff",
    borderRadius: 8,
    padding: 12,
    fontFamily: "Archivo",
  },
  sensorLabel: {
    fontSize: 14,
    color: "#abababff",
    marginBottom: 4,
    fontFamily: "Archivo-Medium",
  },
  sensorValue: { fontSize: 14, color: "#15d6a9", fontFamily: "Archivo-Medium" },
  sensorDisabled: {
    fontSize: 14,
    color: "#cd3c34ff",
    fontStyle: "italic",
    textAlign: "center",
    padding: 12,
    backgroundColor: "#293646ff",
    borderRadius: 8,
    marginVertical: 5,
    fontFamily: "Archivo",
  },
});
