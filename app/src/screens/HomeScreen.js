import {
  View,
  Text,
  Image,
  TouchableOpacity,
  StyleSheet,
  Platform,
} from "react-native";
import { Ionicons as Icon } from "@expo/vector-icons";
import MaterialCommunityIcons from "@expo/vector-icons/MaterialCommunityIcons";
import MaterialIcons from "@expo/vector-icons/MaterialIcons";
import { useApp } from "../context/AppContext";
import ScreenContainer from "../components/ScreenContainer";

const KNOWN_APPS = {
  "com.zhiliaoapp.musically": { name: "TikTok", iconName: "tiktok" },
  "com.instagram.android": { name: "Instagram", iconName: "instagram" },
  "com.facebook.katana": { name: "Facebook", iconName: "facebook" },
  "com.whatsapp": { name: "WhatsApp", iconName: "whatsapp" },
  "com.snapchat.android": { name: "Snapchat", iconName: "snapchat" },
};

export default function HomeScreen() {
  const {
    totalScreenTime,
    appsUsage,
    hasUsageAccess,
    openUsageAccess,
    fetchUsage,
    collectAppUsage,
  } = useApp();

  const formatDuration = (mins) => {
    const safe = Math.max(0, parseInt(mins || 0, 10));
    const h = Math.floor(safe / 60);
    const m = safe % 60;
    if (h <= 0) return `${m}M`;
    return `${h}H ${m}M`;
  };

  return (
    <ScreenContainer>
      <View style={{ flexDirection: "row", alignItems: "center" }}>
        <MaterialIcons
          name="phone-iphone"
          size={28}
          color="#15d6a9"
          style={{ marginRight: 8, marginBottom: 14 }}
        />
        <Text style={styles.contentTitle}>Activity Overview</Text>
      </View>

      <View style={styles.timeCard}>
        <Icon name="time-outline" size={30} color="#15d6a9" />
        <Text style={styles.timeValue}>{formatDuration(totalScreenTime)}</Text>
        <Text style={styles.timeLabel}>Total Screen Time Today</Text>
      </View>

      {collectAppUsage && !hasUsageAccess && Platform.OS === "android" ? (
        <View style={styles.sensorSection}>
          <Text style={styles.sensorDisabled}>
            Usage access is not granted. Grant to show app usage.
          </Text>
          <Text style={styles.sensorDisabled}>
            Close the app and Open it again to view usage. Or wait a few
            minutes.
          </Text>
          <TouchableOpacity
            style={[
              styles.actionButton,
              { marginTop: 8, backgroundColor: "#15d6a9" },
            ]}
            onPress={openUsageAccess}
          >
            <Text style={styles.actionButtonText}>
              Open Usage Access Settings
            </Text>
          </TouchableOpacity>
        </View>
      ) : null}

      <Text style={styles.sectionHeader}>Top App Usage</Text>
      {!collectAppUsage ? (
        <Text style={styles.sensorDisabled}>App usage collection disabled</Text>
      ) : appsUsage && appsUsage.length > 0 ? (
        <View>
          {appsUsage.slice(0, 5).map((app, index) => {
            const pkg = app.package || "";
            const known = KNOWN_APPS[pkg];
            const displayName =
              app.name && app.name !== pkg ? app.name : known?.name || pkg;
            const minutes = Math.round((app.ms || 0) / 60000);
            const usageColor =
              minutes > 90
                ? "#FF3B30"
                : minutes > 60
                ? "#FF9500"
                : minutes > 30
                ? "#FFCC00"
                : "#32D74B";
            return (
              <View key={index} style={styles.appItem}>
                {app.icon ? (
                  <Image
                    source={{ uri: app.icon }}
                    style={{
                      width: 24,
                      height: 24,
                      borderRadius: 4,
                      marginRight: 6,
                    }}
                  />
                ) : known?.iconName ? (
                  <MaterialCommunityIcons
                    name={known.iconName}
                    size={24}
                    color="#333"
                    style={{ width: 30 }}
                  />
                ) : (
                  <Icon
                    name={"apps-outline"}
                    size={20}
                    color="#333"
                    style={{ width: 30 }}
                  />
                )}
                <Text style={styles.appName}>{displayName}</Text>
                <Text style={styles.appTime}>{minutes} min</Text>
                <View
                  style={[
                    styles.timeBar,
                    {
                      width: `${Math.min(
                        89,
                        (app.ms / (appsUsage[0].ms || 1)) * 100
                      )}%`,
                      backgroundColor: usageColor,
                    },
                  ]}
                />
              </View>
            );
          })}
          <TouchableOpacity
            style={[
              styles.actionButton,
              { marginTop: 12, backgroundColor: "#15d6a9" },
            ]}
            onPress={fetchUsage}
          >
            <Text style={styles.actionButtonText}>Refresh Usage</Text>
          </TouchableOpacity>
        </View>
      ) : (
        <Text style={styles.sensorDisabled}>No usage data yet.</Text>
      )}
    </ScreenContainer>
  );
}

const styles = StyleSheet.create({
  contentView: { flex: 1 },
  contentTitle: {
    fontSize: 24,
    marginBottom: 20,
    color: "#ccccccff",
    fontFamily: "Archivo-SemiBold",
  },
  timeCard: {
    backgroundColor: "#222d3aff",
    padding: 20,
    borderRadius: 10,
    alignItems: "center",
    marginBottom: 30,
    borderWidth: 1,
    borderColor: "#15d6a97a",
  },
  timeValue: {
    fontSize: 40,
    color: "#15d6a9",
    marginVertical: 5,
    fontFamily: "Archivo-SemiBold",
  },
  timeLabel: { fontSize: 16, color: "#ccccccff", fontFamily: "Archivo-Medium" },
  sectionHeader: {
    fontSize: 18,
    color: "#ccccccff",
    marginTop: 10,
    marginBottom: 15,
    fontFamily: "Archivo-SemiBold",
  },
  appItem: {
    flexDirection: "row",
    alignItems: "center",
    paddingVertical: 10,
    borderBottomWidth: 0.5,
    borderBottomColor: "#6464644a",
    position: "relative",
  },
  appName: {
    flex: 1,
    fontSize: 16,
    color: "#ccccccff",
    marginLeft: 10,
    fontFamily: "Archivo-SemiBold",
    paddingHorizontal: 5,
  },
  appTime: {
    fontSize: 16,
    color: "#15d6a9",
    zIndex: 2,
    paddingHorizontal: 5,
    fontFamily: "Archivo-Medium",
  },
  timeBar: {
    position: "absolute",
    left: 40,
    right: 0,
    height: "100%",
    opacity: 0.5,
    borderRadius: 5,
    zIndex: 1,
  },
  sensorSection: {
    backgroundColor: "#222d3aff",
    borderRadius: 12,
    padding: 16,
    marginBottom: 36,
    borderLeftWidth: 4,
    borderLeftColor: "#15d6a9",
  },
  sensorDisabled: {
    fontSize: 14,
    color: "#cd3c34ff",
    fontStyle: "italic",
    textAlign: "center",
    padding: 12,
    backgroundColor: "#222d3aff",
    borderRadius: 8,
    marginVertical: 5,
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
});
