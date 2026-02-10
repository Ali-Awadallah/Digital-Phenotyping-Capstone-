import { View, Text, StyleSheet, Switch, Alert, TouchableOpacity, ActivityIndicator } from "react-native";
import { useState } from "react";
import { Ionicons as Icon } from "@expo/vector-icons";
import MaterialIcons from "@expo/vector-icons/MaterialIcons";
import { useApp } from "../context/AppContext";
import ScreenContainer from "../components/ScreenContainer";
import HealthConnectService from "../services/HealthConnectService";

export default function SettingsScreen() {
  const {
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
    isBackgroundServiceRunning,
    startBackgroundService,
    stopBackgroundService,
  } = useApp();

  // Health Connect state
  const [hcAvailable, setHcAvailable] = useState(null);
  const [hcPermissions, setHcPermissions] = useState(null);
  const [hcLoading, setHcLoading] = useState(false);
  const [hcData, setHcData] = useState(null);

  const handleBackgroundServiceToggle = async (next) => {
    if (next) {
      await startBackgroundService();
    } else {
      Alert.alert(
        "Disable Background Collection?",
        "This will stop sensor data collection when the app is minimized. Data accuracy may be affected.",
        [
          { text: "Cancel", style: "cancel" },
          {
            text: "Turn off",
            style: "destructive",
            onPress: async () => await stopBackgroundService(),
          },
        ]
      );
    }
  };

  const handleCheckAvailability = async () => {
    setHcLoading(true);
    try {
      const available = await HealthConnectService.isAvailable();
      setHcAvailable(available);
      Alert.alert(
        "Health Connect",
        available
          ? "✅ Health Connect is available on this device!"
          : "❌ Health Connect is NOT available. Please install it from the Play Store."
      );
    } catch (e) {
      Alert.alert("Error", e.message || "Failed to check availability");
    }
    setHcLoading(false);
  };

  const handleRequestPermissions = async () => {
    setHcLoading(true);
    try {
      const available = await HealthConnectService.isAvailable();
      if (!available) {
        Alert.alert("Health Connect", "Health Connect is not available on this device. Please install it first.");
        setHcLoading(false);
        return;
      }

      await HealthConnectService.requestPermissions();

      // Check permission status after requesting
      const status = await HealthConnectService.getPermissionStatus();
      setHcPermissions(status);

      const granted = Object.entries(status)
        .filter(([, v]) => v)
        .map(([k]) => k);
      const denied = Object.entries(status)
        .filter(([, v]) => !v)
        .map(([k]) => k);

      if (denied.length === 0) {
        Alert.alert("Permissions", "✅ All Health Connect permissions granted!");
      } else {
        Alert.alert(
          "Permissions",
          `Granted: ${granted.join(", ") || "none"}\n\nDenied: ${denied.join(", ") || "none"}\n\nYou may need to open Health Connect settings to grant all permissions.`
        );
      }
    } catch (e) {
      Alert.alert("Error", e.message || "Failed to request permissions");
    }
    setHcLoading(false);
  };

  const handleFetchData = async () => {
    setHcLoading(true);
    try {
      const hasPermissions = await HealthConnectService.checkPermissions();
      if (!hasPermissions) {
        Alert.alert("Health Connect", "Please grant permissions first before fetching data.");
        setHcLoading(false);
        return;
      }

      const data = await HealthConnectService.getLast24HoursData();
      setHcData(data);

      const summary = [
        `❤️ Heart Rate: ${data.heartRate?.length || 0} samples`,
        `👣 Steps: ${data.steps?.length || 0} records`,
        `😴 Sleep: ${data.sleep?.length || 0} sessions`,
        `🩸 Blood Pressure: ${data.bloodPressure?.length || 0} readings`,
        `⚖️ Weight: ${data.weight?.length || 0} records`,
        `🫁 SpO2: ${data.oxygenSaturation?.length || 0} readings`,
        `💨 Respiratory: ${data.respiratoryRate?.length || 0} readings`,
      ].join("\n");

      // Show step total if available
      let extraInfo = "";
      if (data.steps?.length > 0) {
        const totalSteps = data.steps.reduce((sum, s) => sum + s.count, 0);
        extraInfo += `\n\nTotal steps (24h): ${totalSteps}`;
      }
      if (data.heartRate?.length > 0) {
        const lastHR = data.heartRate[data.heartRate.length - 1];
        extraInfo += `\nLatest heart rate: ${lastHR.bpm} bpm`;
      }

      Alert.alert("Last 24 Hours Data", summary + extraInfo);
    } catch (e) {
      Alert.alert("Error", e.message || "Failed to fetch health data");
    }
    setHcLoading(false);
  };

  const handleOpenSettings = async () => {
    try {
      await HealthConnectService.openSettings();
    } catch (e) {
      Alert.alert("Error", "Could not open Health Connect settings");
    }
  };

  return (
    <ScreenContainer>
      <View style={{ flexDirection: "row", alignItems: "center" }}>
        <Icon
          name="settings-outline"
          size={28}
          color="#15d6a9"
          style={{ marginRight: 8, marginBottom: 14 }}
        />
        <Text style={styles.contentTitle}>Settings</Text>
      </View>

      <Text style={styles.infoText}>
        Choose which sensors to collect. Turning a sensor off stops data
        collection immediately.
      </Text>

      {/* Background Service Toggle */}
      <Text style={styles.sectionTitle}>Background Collection</Text>
      <View style={[styles.sensorSection, { borderLeftColor: isBackgroundServiceRunning ? '#32D74B' : '#FF9500' }]}>
        <View style={styles.settingsRow}>
          <View style={styles.settingsLabelWrap}>
            <MaterialIcons
              name={isBackgroundServiceRunning ? "sync" : "sync-disabled"}
              size={20}
              color={isBackgroundServiceRunning ? "#32D74B" : "#FF9500"}
            />
            <View style={{ marginLeft: 8 }}>
              <Text style={styles.settingsLabel}>Background Service</Text>
              <Text style={styles.settingsSubLabel}>
                {isBackgroundServiceRunning ? "Running - collecting data in background" : "Stopped - only collecting when app is open"}
              </Text>
            </View>
          </View>
          <Switch
            value={isBackgroundServiceRunning}
            onValueChange={handleBackgroundServiceToggle}
            trackColor={{ false: '#767577', true: '#32D74B' }}
          />
        </View>
      </View>

      <Text style={styles.sectionTitle}>Sensor Toggles</Text>
      <View style={styles.sensorSection}>
        <Row
          label="Location"
          icon={<Icon name="location-outline" size={20} color="#15d6a9" />}
          value={collectLocation}
          onChange={setCollectLocation}
        />
        <Row
          label="Accelerometer"
          icon={
            <Icon name="phone-portrait-outline" size={20} color="#FF9500" />
          }
          value={collectAccelerometer}
          onChange={setCollectAccelerometer}
        />
        <Row
          label="Gyroscope"
          icon={
            <MaterialIcons name="screen-rotation" size={20} color="#32D74B" />
          }
          value={collectGyroscope}
          onChange={setCollectGyroscope}
        />
        <Row
          label="Pedometer"
          icon={<Icon name="walk-outline" size={20} color="#5856D6" />}
          value={collectPedometer}
          onChange={setCollectPedometer}
        />
        <Row
          label="Battery"
          icon={
            <Icon name="battery-charging-outline" size={20} color="#00ad03ff" />
          }
          value={collectBattery}
          onChange={setCollectBattery}
        />
        <Row
          label="Screen Events"
          icon={<Icon name="power-outline" size={20} color="#a700adff" />}
          value={collectScreenEvents}
          onChange={setCollectScreenEvents}
        />
        <Row
          label="Apps Usage"
          icon={<Icon name="apps-outline" size={20} color="#15d6a9" />}
          value={collectAppUsage}
          onChange={setCollectAppUsage}
        />
        <Row
          label="Notifications"
          icon={
            <Icon
              name="notifications-outline"
              size={20}
              color="#f5a623"
            />
          }
          value={collectNotifications}
          onChange={setCollectNotifications}
        />
      </View>

      {/* Health Connect / Wearables Section */}
      <Text style={styles.sectionTitle}>Wearables (Health Connect)</Text>
      <View style={[styles.sensorSection, { borderLeftColor: '#E91E63' }]}>
        <Text style={styles.hcDescription}>
          Connect to smartwatches and fitness trackers via Health Connect to collect heart rate, steps, sleep, and more. This is optional.
        </Text>

        {hcLoading && (
          <View style={styles.hcLoadingRow}>
            <ActivityIndicator size="small" color="#15d6a9" />
            <Text style={styles.hcLoadingText}>Working...</Text>
          </View>
        )}

        <TouchableOpacity style={styles.hcButton} onPress={handleCheckAvailability} disabled={hcLoading}>
          <MaterialIcons name="check-circle-outline" size={20} color="#15d6a9" />
          <Text style={styles.hcButtonText}>Check Availability</Text>
          {hcAvailable !== null && (
            <Text style={[styles.hcStatusBadge, { backgroundColor: hcAvailable ? '#1b5e20' : '#b71c1c' }]}>
              {hcAvailable ? 'Available' : 'Not Available'}
            </Text>
          )}
        </TouchableOpacity>

        <TouchableOpacity style={styles.hcButton} onPress={handleRequestPermissions} disabled={hcLoading}>
          <MaterialIcons name="security" size={20} color="#FF9500" />
          <Text style={styles.hcButtonText}>Request Permissions</Text>
        </TouchableOpacity>

        <TouchableOpacity style={styles.hcButton} onPress={handleFetchData} disabled={hcLoading}>
          <MaterialIcons name="download" size={20} color="#5856D6" />
          <Text style={styles.hcButtonText}>Fetch Last 24h Data</Text>
        </TouchableOpacity>

        <TouchableOpacity style={styles.hcButton} onPress={handleOpenSettings} disabled={hcLoading}>
          <MaterialIcons name="settings" size={20} color="#888" />
          <Text style={styles.hcButtonText}>Open Health Connect Settings</Text>
        </TouchableOpacity>

        {/* Permission Status Display */}
        {hcPermissions && (
          <View style={styles.hcPermGrid}>
            <Text style={styles.hcPermTitle}>Permission Status:</Text>
            {Object.entries(hcPermissions).map(([key, granted]) => (
              <View key={key} style={styles.hcPermRow}>
                <Text style={styles.hcPermIcon}>{granted ? '✅' : '❌'}</Text>
                <Text style={styles.hcPermLabel}>{key}</Text>
              </View>
            ))}
          </View>
        )}
      </View>
    </ScreenContainer>
  );
}

function Row({ label, icon, value, onChange }) {
  const handleToggle = (next) => {
    if (value && !next) {
      Alert.alert(
        `Disable ${label} tracking?`,
        `This will be affecting data collection process. And lead to inaccurate analysis.`,
        [
          { text: "Cancel", style: "cancel" },
          {
            text: "Turn off",
            style: "destructive",
            onPress: () => onChange(false),
          },
        ]
      );
    } else {
      onChange(true);
    }
  };

  return (
    <View style={styles.settingsRow}>
      <View style={styles.settingsLabelWrap}>
        {icon}
        <Text style={styles.settingsLabel}>{label}</Text>
      </View>
      <Switch value={value} onValueChange={handleToggle} />
    </View>
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
  sensorSection: {
    backgroundColor: "#222d3aff",
    borderRadius: 12,
    padding: 16,
    marginBottom: 36,
    borderLeftWidth: 4,
    borderLeftColor: "#15d6a9",
    elevation: 3,
  },
  settingsRow: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    paddingVertical: 10,
    borderBottomWidth: 1,
    borderBottomColor: "#6464644a",
  },
  settingsLabelWrap: { flexDirection: "row", alignItems: "center" },
  settingsLabel: {
    fontSize: 16,
    color: "#ccccccff",
    fontFamily: "Archivo-SemiBold",
  },
  settingsSubLabel: {
    fontSize: 12,
    color: "#888888",
    fontFamily: "Archivo",
    marginTop: 2,
  },
  sectionTitle: {
    fontSize: 18,
    color: "#ccccccff",
    fontFamily: "Archivo-Medium",
    marginTop: 16,
    marginBottom: 8,
  },
  // Health Connect styles
  hcDescription: {
    fontSize: 14,
    color: "#999",
    fontFamily: "Archivo",
    marginBottom: 12,
    lineHeight: 20,
  },
  hcLoadingRow: {
    flexDirection: "row",
    alignItems: "center",
    paddingVertical: 8,
    marginBottom: 4,
  },
  hcLoadingText: {
    color: "#15d6a9",
    fontSize: 14,
    fontFamily: "Archivo",
    marginLeft: 8,
  },
  hcButton: {
    flexDirection: "row",
    alignItems: "center",
    paddingVertical: 12,
    paddingHorizontal: 4,
    borderBottomWidth: 1,
    borderBottomColor: "#6464644a",
  },
  hcButtonText: {
    color: "#ccccccff",
    fontSize: 15,
    fontFamily: "Archivo-SemiBold",
    marginLeft: 10,
    flex: 1,
  },
  hcStatusBadge: {
    color: "#fff",
    fontSize: 11,
    fontFamily: "Archivo-SemiBold",
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: 10,
    overflow: "hidden",
  },
  hcPermGrid: {
    marginTop: 12,
    paddingTop: 8,
    borderTopWidth: 1,
    borderTopColor: "#6464644a",
  },
  hcPermTitle: {
    color: "#aaa",
    fontSize: 13,
    fontFamily: "Archivo-SemiBold",
    marginBottom: 6,
  },
  hcPermRow: {
    flexDirection: "row",
    alignItems: "center",
    paddingVertical: 3,
  },
  hcPermIcon: {
    fontSize: 14,
    marginRight: 8,
  },
  hcPermLabel: {
    color: "#ccccccff",
    fontSize: 14,
    fontFamily: "Archivo",
    textTransform: "capitalize",
  },
});
