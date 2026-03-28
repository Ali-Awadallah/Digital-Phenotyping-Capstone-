import { useEffect, useMemo } from "react";
import {
  View,
  Text,
  TouchableOpacity,
  StyleSheet,
  Platform,
} from "react-native";
import { Ionicons as Icon, MaterialCommunityIcons } from "@expo/vector-icons";
import MaterialIcons from "@expo/vector-icons/MaterialIcons";
import * as Battery from "expo-battery";
import { useApp } from "../context/AppContext";
import ScreenContainer from "../components/ScreenContainer";

export default function SensorsScreen() {
  const {
    isLocationEnabled,
    accelerometerActive,
    gyroscopeActive,
    batteryLevel,
    batteryState,
    collectBattery,
    isPedometerAvailable,
    stepsToday,
    screenEvents,
    refreshScreenEvents,
    collectScreenEvents,
    notificationEvents,
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

  // Computed: Screen event summary for today
  const screenSummary = useMemo(() => {
    if (!screenEvents || !screenEvents.length)
      return { unlocks: 0, locks: 0, total: 0, lastUnlock: null, lastLock: null };
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    const start = today.getTime();
    const todayEvts = screenEvents.filter((e) => e.ts >= start);
    let unlocks = 0, locks = 0, lastUnlock = null, lastLock = null;
    todayEvts.forEach((e) => {
      const evt = (e.event || "").toUpperCase();
      if (evt === "SCREEN_ON" || evt === "USER_PRESENT") {
        unlocks++;
        if (!lastUnlock || e.ts > lastUnlock) lastUnlock = e.ts;
      } else if (evt === "SCREEN_OFF") {
        locks++;
        if (!lastLock || e.ts > lastLock) lastLock = e.ts;
      }
    });
    return { unlocks, locks, total: todayEvts.length, lastUnlock, lastLock };
  }, [screenEvents]);

  // Computed: Notification summary for today
  const notifSummary = useMemo(() => {
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    const start = today.getTime();
    const todays = (notificationEvents || []).filter(
      (n) => n && typeof n.ts === "number" && n.ts >= start
    );
    const posted = todays.filter((n) => n.kind === "posted");
    const apps = {};
    posted.forEach((n) => {
      if (n.appName) apps[n.appName] = (apps[n.appName] || 0) + 1;
    });
    const topApps = Object.entries(apps)
      .sort((a, b) => b[1] - a[1])
      .slice(0, 5);
    return { total: todays.length, posted: posted.length, topApps };
  }, [notificationEvents]);

  const fmtTime = (ts) =>
    ts
      ? new Date(ts).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })
      : "—";

  return (
    <ScreenContainer>
      {/* Privacy Hero Section */}
      <View style={styles.heroSection}>
        <Text style={styles.heroHeading}>
          Your data is a{"\n"}
          <Text style={styles.heroAccent}>sacred </Text>
          narrative.
        </Text>
        <Text style={styles.heroBody}>
          Digital phenotyping translates your sensor data into wellness insights.
          We encrypt every byte locally. You hold the master key—decide exactly
          what to share and when to stop.
        </Text>
        <View style={styles.encryptedCard}>
          <View style={styles.encryptedIconRow}>
            <MaterialCommunityIcons
              name="shield-check"
              size={22}
              color="#15d6a9"
            />
            <Text style={styles.encryptedTitle}>End-to-End Encrypted</Text>
          </View>
          <Text style={styles.encryptedBody}>
            Only your device holds the decryption keys. Even we can't see your
            raw sensor patterns.
          </Text>
        </View>
      </View>

      {/* Today's Overview */}
      <View style={{ flexDirection: "row", alignItems: "center" }}>
        <MaterialIcons
          name="dashboard"
          size={28}
          color="#15d6a9"
          style={{ marginRight: 8, marginBottom: 16 }}
        />
        <Text style={styles.contentTitle}>Today's Overview</Text>
      </View>

      {/* Summary Tiles */}
      <View style={styles.tilesRow}>
        <View style={styles.tile}>
          <Icon name="footsteps-outline" size={26} color="#5856D6" />
          <Text style={styles.tileNumber}>{stepsToday.toLocaleString()}</Text>
          <Text style={styles.tileLabel}>Steps</Text>
        </View>
        <View style={styles.tile}>
          <Icon name="lock-open-outline" size={26} color="#cd2ad3ff" />
          <Text style={styles.tileNumber}>{screenSummary.unlocks}</Text>
          <Text style={styles.tileLabel}>Unlocks</Text>
        </View>
      </View>
      <View style={styles.tilesRow}>
        <View style={styles.tile}>
          <Icon name="notifications-outline" size={26} color="#f5a623" />
          <Text style={styles.tileNumber}>{notifSummary.posted}</Text>
          <Text style={styles.tileLabel}>Notifications</Text>
        </View>
        <View style={styles.tile}>
          <Icon name="battery-charging-outline" size={26} color="#00ad03ff" />
          <Text style={styles.tileNumber}>
            {batteryLevel != null ? Math.round(batteryLevel * 100) + "%" : "—"}
          </Text>
          <Text style={styles.tileLabel}>Battery</Text>
        </View>
      </View>

      {/* Screen Activity */}
      <Text style={styles.sectionTitle}>Screen Activity</Text>
      <View style={styles.sensorSection}>
        {!collectScreenEvents ? (
          <Text style={styles.sensorDisabled}>
            Screen events collection disabled
          </Text>
        ) : (
          <View>
            <View style={styles.statRow}>
              <View style={styles.statItem}>
                <Icon name="lock-open-outline" size={20} color="#cd2ad3ff" />
                <Text style={styles.statNumber}>{screenSummary.unlocks}</Text>
                <Text style={styles.statLabel}>Unlocks</Text>
              </View>
              <View style={styles.statDivider} />
              <View style={styles.statItem}>
                <Icon name="lock-closed-outline" size={20} color="#FF3B30" />
                <Text style={styles.statNumber}>{screenSummary.locks}</Text>
                <Text style={styles.statLabel}>Locks</Text>
              </View>
              <View style={styles.statDivider} />
              <View style={styles.statItem}>
                <Icon name="time-outline" size={20} color="#15d6a9" />
                <Text style={styles.statNumber}>{screenSummary.total}</Text>
                <Text style={styles.statLabel}>Total</Text>
              </View>
            </View>
            <View style={styles.timeRow}>
              <View style={styles.timeItem}>
                <Text style={styles.timeLabel}>Last Unlock</Text>
                <Text style={styles.timeValue}>
                  {fmtTime(screenSummary.lastUnlock)}
                </Text>
              </View>
              <View style={styles.timeItem}>
                <Text style={styles.timeLabel}>Last Lock</Text>
                <Text style={styles.timeValue}>
                  {fmtTime(screenSummary.lastLock)}
                </Text>
              </View>
            </View>
            <TouchableOpacity
              style={[
                styles.refreshBtn,
                { backgroundColor: "#15d6a9" },
              ]}
              onPress={refreshScreenEvents}
            >
              <Text style={styles.refreshBtnText}>Refresh</Text>
            </TouchableOpacity>
          </View>
        )}
      </View>

      {/* Notifications */}
      <Text style={styles.sectionTitle}>Notifications</Text>
      <View style={styles.sensorSection}>
        {!collectNotifications ? (
          <Text style={styles.sensorDisabled}>
            Notification tracking is disabled in Settings.
          </Text>
        ) : !hasNotificationAccess ? (
          <View>
            <Text style={styles.sensorDisabled}>
              Notification access is not granted.
            </Text>
            <TouchableOpacity
              style={[styles.refreshBtn, { backgroundColor: "#f5a623" }]}
              onPress={openNotificationAccess}
            >
              <Text style={styles.refreshBtnText}>Grant Access</Text>
            </TouchableOpacity>
          </View>
        ) : (
          <View>
            <View style={styles.statRow}>
              <View style={styles.statItem}>
                <Icon name="notifications" size={20} color="#f5a623" />
                <Text style={styles.statNumber}>{notifSummary.posted}</Text>
                <Text style={styles.statLabel}>Received</Text>
              </View>
              <View style={styles.statDivider} />
              <View style={styles.statItem}>
                <Icon name="albums-outline" size={20} color="#15d6a9" />
                <Text style={styles.statNumber}>{notifSummary.total}</Text>
                <Text style={styles.statLabel}>Total Events</Text>
              </View>
            </View>
            {notifSummary.topApps.length > 0 && (
              <View style={styles.topAppsWrap}>
                <Text style={styles.topAppsTitle}>Top Apps</Text>
                {notifSummary.topApps.map(([app, count]) => (
                  <View key={app} style={styles.topAppRow}>
                    <Text style={styles.topAppName} numberOfLines={1}>
                      {app}
                    </Text>
                    <View style={styles.topAppBarBg}>
                      <View
                        style={[
                          styles.topAppBarFill,
                          {
                            width: `${Math.min(
                              100,
                              (count / (notifSummary.posted || 1)) * 100
                            )}%`,
                          },
                        ]}
                      />
                    </View>
                    <Text style={styles.topAppCount}>{count}</Text>
                  </View>
                ))}
              </View>
            )}
          </View>
        )}
      </View>

      {/* Sensor Status */}
      <Text style={styles.sectionTitle}>Sensor Status</Text>
      <View style={styles.sensorSection}>
        <View style={styles.statusRow}>
          <Icon name="location-outline" size={20} color="#15d6a9" />
          <Text style={styles.statusName}>Location</Text>
          <View
            style={[
              styles.statusBadge,
              {
                backgroundColor: isLocationEnabled ? "#1a3d2a" : "#3d1a1a",
              },
            ]}
          >
            <Text
              style={[
                styles.statusBadgeText,
                { color: isLocationEnabled ? "#32D74B" : "#FF3B30" },
              ]}
            >
              {isLocationEnabled ? "Active" : "Off"}
            </Text>
          </View>
        </View>
        <View style={styles.statusRow}>
          <Icon name="phone-portrait-outline" size={20} color="#FF9500" />
          <Text style={styles.statusName}>Accelerometer</Text>
          <View
            style={[
              styles.statusBadge,
              {
                backgroundColor: accelerometerActive ? "#1a3d2a" : "#3d1a1a",
              },
            ]}
          >
            <Text
              style={[
                styles.statusBadgeText,
                { color: accelerometerActive ? "#32D74B" : "#FF3B30" },
              ]}
            >
              {accelerometerActive ? "Active" : "Off"}
            </Text>
          </View>
        </View>
        <View style={styles.statusRow}>
          <MaterialIcons name="screen-rotation" size={20} color="#32D74B" />
          <Text style={styles.statusName}>Gyroscope</Text>
          <View
            style={[
              styles.statusBadge,
              {
                backgroundColor: gyroscopeActive ? "#1a3d2a" : "#3d1a1a",
              },
            ]}
          >
            <Text
              style={[
                styles.statusBadgeText,
                { color: gyroscopeActive ? "#32D74B" : "#FF3B30" },
              ]}
            >
              {gyroscopeActive ? "Active" : "Off"}
            </Text>
          </View>
        </View>
        <View style={styles.statusRow}>
          <Icon name="footsteps-outline" size={20} color="#5856D6" />
          <Text style={styles.statusName}>Pedometer</Text>
          <View
            style={[
              styles.statusBadge,
              {
                backgroundColor: isPedometerAvailable ? "#1a3d2a" : "#3d1a1a",
              },
            ]}
          >
            <Text
              style={[
                styles.statusBadgeText,
                { color: isPedometerAvailable ? "#32D74B" : "#FF3B30" },
              ]}
            >
              {isPedometerAvailable ? "Active" : "Off"}
            </Text>
          </View>
        </View>
        <View style={[styles.statusRow, { borderBottomWidth: 0 }]}>
          <Icon name="battery-charging-outline" size={20} color="#00ad03ff" />
          <Text style={styles.statusName}>Battery</Text>
          <View
            style={[
              styles.statusBadge,
              {
                backgroundColor: collectBattery ? "#1a3d2a" : "#3d1a1a",
              },
            ]}
          >
            <Text
              style={[
                styles.statusBadgeText,
                { color: collectBattery ? "#32D74B" : "#FF3B30" },
              ]}
            >
              {collectBattery
                ? batteryLevel != null
                  ? Math.round(batteryLevel * 100) + "%"
                  : "Active"
                : "Off"}
            </Text>
          </View>
        </View>
      </View>
    </ScreenContainer>
  );
}

const styles = StyleSheet.create({
  contentTitle: {
    fontSize: 24,
    marginBottom: 20,
    color: "#ccccccff",
    fontFamily: "Archivo-SemiBold",
  },
  sectionTitle: {
    fontSize: 20,
    marginTop: 8,
    marginBottom: 12,
    fontFamily: "Archivo-Medium",
    color: "#ccccccff",
  },
  sensorSection: {
    backgroundColor: "#222d3aff",
    borderRadius: 12,
    padding: 16,
    marginBottom: 20,
    elevation: 3,
  },
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
  // Summary tiles
  tilesRow: {
    flexDirection: "row",
    justifyContent: "space-between",
    marginBottom: 12,
  },
  tile: {
    flex: 1,
    backgroundColor: "#222d3aff",
    borderRadius: 12,
    padding: 16,
    alignItems: "center",
    marginHorizontal: 4,
    elevation: 3,
  },
  tileNumber: {
    fontSize: 28,
    color: "#ccccccff",
    fontFamily: "Archivo-SemiBold",
    marginTop: 8,
  },
  tileLabel: {
    fontSize: 13,
    color: "#abababff",
    fontFamily: "Archivo-Medium",
    marginTop: 2,
  },
  // Stat row (unlock/lock/total)
  statRow: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-around",
    backgroundColor: "#293646ff",
    borderRadius: 10,
    paddingVertical: 14,
    paddingHorizontal: 8,
    marginBottom: 12,
  },
  statItem: {
    alignItems: "center",
    flex: 1,
  },
  statNumber: {
    fontSize: 22,
    color: "#ccccccff",
    fontFamily: "Archivo-SemiBold",
    marginTop: 4,
  },
  statLabel: {
    fontSize: 12,
    color: "#abababff",
    fontFamily: "Archivo-Medium",
    marginTop: 2,
  },
  statDivider: {
    width: 1,
    height: 36,
    backgroundColor: "#3a4a5aff",
  },
  // Time row (last unlock/lock)
  timeRow: {
    flexDirection: "row",
    justifyContent: "space-around",
    marginBottom: 4,
  },
  timeItem: {
    alignItems: "center",
  },
  timeLabel: {
    fontSize: 12,
    color: "#abababff",
    fontFamily: "Archivo-Medium",
  },
  timeValue: {
    fontSize: 16,
    color: "#15d6a9",
    fontFamily: "Archivo-SemiBold",
    marginTop: 2,
  },
  // Refresh button
  refreshBtn: {
    marginTop: 12,
    padding: 10,
    borderRadius: 8,
    alignItems: "center",
  },
  refreshBtnText: {
    color: "#222d3aff",
    fontSize: 14,
    fontFamily: "Archivo-Medium",
  },
  // Top apps (notifications)
  topAppsWrap: {
    backgroundColor: "#293646ff",
    borderRadius: 10,
    padding: 12,
    marginTop: 4,
  },
  topAppsTitle: {
    fontSize: 14,
    color: "#abababff",
    fontFamily: "Archivo-SemiBold",
    marginBottom: 8,
  },
  topAppRow: {
    flexDirection: "row",
    alignItems: "center",
    marginBottom: 8,
  },
  topAppName: {
    width: 100,
    fontSize: 13,
    color: "#ccccccff",
    fontFamily: "Archivo-Medium",
  },
  topAppBarBg: {
    flex: 1,
    height: 8,
    backgroundColor: "#1a2a3aff",
    borderRadius: 4,
    marginHorizontal: 8,
    overflow: "hidden",
  },
  topAppBarFill: {
    height: 8,
    backgroundColor: "#f5a623",
    borderRadius: 4,
  },
  topAppCount: {
    fontSize: 13,
    color: "#f5a623",
    fontFamily: "Archivo-SemiBold",
    width: 30,
    textAlign: "right",
  },
  // Sensor status rows
  statusRow: {
    flexDirection: "row",
    alignItems: "center",
    paddingVertical: 12,
    borderBottomWidth: 1,
    borderBottomColor: "#293646ff",
  },
  statusName: {
    flex: 1,
    fontSize: 15,
    color: "#ccccccff",
    fontFamily: "Archivo-Medium",
    marginLeft: 12,
  },
  statusBadge: {
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 12,
  },
  statusBadgeText: {
    fontSize: 12,
    fontFamily: "Archivo-SemiBold",
  },
  // Hero section
  heroSection: {
    marginBottom: 24,
  },
  heroHeading: {
    fontSize: 28,
    color: "#ccccccff",
    fontFamily: "Archivo-SemiBold",
    lineHeight: 36,
    marginBottom: 12,
  },
  heroAccent: {
    fontStyle: "italic",
    color: "#15d6a9",
    fontFamily: "Archivo-SemiBold",
  },
  heroBody: {
    fontSize: 15,
    color: "#abababff",
    lineHeight: 22,
    fontFamily: "Archivo-Medium",
    marginBottom: 16,
  },
  encryptedCard: {
    backgroundColor: "#222d3aff",
    borderRadius: 12,
    padding: 16,
    borderWidth: 1,
    borderColor: "#15d6a933",
  },
  encryptedIconRow: {
    flexDirection: "row",
    alignItems: "center",
    marginBottom: 8,
  },
  encryptedTitle: {
    fontSize: 16,
    color: "#ccccccff",
    fontFamily: "Archivo-SemiBold",
    marginLeft: 8,
  },
  encryptedBody: {
    fontSize: 14,
    color: "#abababff",
    lineHeight: 20,
    fontFamily: "Archivo",
  },
});
