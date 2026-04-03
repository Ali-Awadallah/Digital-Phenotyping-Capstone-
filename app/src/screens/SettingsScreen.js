import { View, Text, StyleSheet, Switch, Alert, TouchableOpacity, ActivityIndicator, TextInput } from "react-native";
import { useState, useEffect } from "react";
import { Ionicons as Icon } from "@expo/vector-icons";
import MaterialIcons from "@expo/vector-icons/MaterialIcons";
import { useApp } from "../context/AppContext";
import ScreenContainer from "../components/ScreenContainer";
import HealthConnectService from "../services/HealthConnectService";
import { getApiBase, setApiBase, getApiIngestKey, setApiIngestKey, buildApiUrl, testConnection } from "../../awareAPI";
import PrivacyPolicyModal from "../components/PrivacyPolicyModal";
import TermsConditionsModal from "../components/TermsConditionsModal";

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

  // Server config state
  const [serverIp, setServerIp] = useState("");
  const [serverPort, setServerPort] = useState("8080");
  const [ingestApiKey, setIngestApiKeyState] = useState("");
  const [serverStatus, setServerStatus] = useState(null); // null | 'testing' | 'connected' | 'failed'
  const [serverError, setServerError] = useState("");

  // Privacy modals state
  const [showPrivacyModal, setShowPrivacyModal] = useState(false);
  const [showTermsModal, setShowTermsModal] = useState(false);

  // Load saved server URL on mount
  useEffect(() => {
    (async () => {
      const saved = await getApiBase();
      const savedIngestKey = await getApiIngestKey();
      // Parse IP and port from saved URL like "http://192.168.1.50:8080/api"
      try {
        const url = new URL(saved);
        setServerIp(url.hostname);
        setServerPort(url.port || "8080");
      } catch {
        setServerIp(saved);
      }
      setIngestApiKeyState(savedIngestKey || "");
    })();
  }, []);

  const handleSaveAndTest = async () => {
    if (!serverIp.trim()) {
      Alert.alert("Error", "Please enter a server IP address");
      return;
    }
    const newUrl = buildApiUrl(serverIp, serverPort);
    setServerStatus("testing");
    setServerError("");

    const result = await testConnection(newUrl);
    if (result.ok) {
      await setApiBase(newUrl);
      await setApiIngestKey(ingestApiKey || "");
      setServerStatus("connected");
      Alert.alert("✅ Connected!", `Server at ${newUrl} is reachable.\nURL saved successfully.`);
    } else {
      setServerStatus("failed");
      setServerError(result.error);
      Alert.alert("❌ Connection Failed", `Could not reach ${newUrl}\n\n${result.error}\n\nURL was NOT saved.`);
    }
  };

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

  const handleSyncToBackend = async () => {
    setHcLoading(true);
    try {
      const result = await HealthConnectService.syncToBackend();
      Alert.alert("Sync Complete", result);
    } catch (e) {
      Alert.alert("Sync Error", e.message || "Failed to sync wearable data");
    }
    setHcLoading(false);
  };

  return (
    <ScreenContainer>
      <View style={{ flexDirection: "row", alignItems: "center" }}>
        <Icon
          name="settings-outline"
          size={28}
          color="#15d6a9"
          style={{ marginRight: 8, marginBottom: 8 }}
        />
        <Text style={styles.contentTitle}>Settings</Text>
      </View>

      <View style={{ height: 1, backgroundColor: "#6464644a", marginVertical: 5 }} />

      {/* Server Configuration */}
      <View style={{ flexDirection: "row", alignItems: "center" }}>
        <MaterialIcons name="dns" size={20} color="#4A90D9" style={{ marginTop: 8 }} />
        <Text style={styles.sectionTitle}>Server Configuration [DEV]</Text>
      </View>
      <View style={[styles.sensorSection, { borderColor: '#4a90d967' }]}>
        <Text style={styles.hcDescription}>
          Set your backend server IP address. Changes take effect immediately for all data collection.
        </Text>

        <View style={styles.serverInputRow}>
          <View style={{ flex: 3 }}>
            <Text style={styles.serverInputLabel}>Server IP</Text>
            <TextInput
              style={styles.serverInput}
              value={serverIp}
              onChangeText={setServerIp}
              placeholder="192.168.1.110"
              placeholderTextColor="#555"
              keyboardType="url"
              autoCapitalize="none"
              autoCorrect={false}
            />
          </View>
          <View style={{ width: 12 }} />
          <View style={{ flex: 1 }}>
            <Text style={styles.serverInputLabel}>Port</Text>
            <TextInput
              style={styles.serverInput}
              value={serverPort}
              onChangeText={setServerPort}
              placeholder="8080"
              placeholderTextColor="#555"
              keyboardType="number-pad"
            />
          </View>
        </View>

        <View style={{ marginTop: 12 }}>
          <Text style={styles.serverInputLabel}>Ingest API Key (optional)</Text>
          <TextInput
            style={styles.serverInput}
            value={ingestApiKey}
            onChangeText={setIngestApiKeyState}
            placeholder="Set to backend API_KEY_INGEST when auth is enabled"
            placeholderTextColor="#555"
            autoCapitalize="none"
            autoCorrect={false}
            secureTextEntry
          />
        </View>

        {/* Status indicator */}
        {serverStatus && (
          <View style={styles.serverStatusRow}>
            {serverStatus === "testing" && (
              <><ActivityIndicator size="small" color="#4A90D9" /><Text style={[styles.serverStatusText, { color: '#4A90D9' }]}>Testing connection...</Text></>
            )}
            {serverStatus === "connected" && (
              <><MaterialIcons name="check-circle" size={18} color="#32D74B" /><Text style={[styles.serverStatusText, { color: '#32D74B' }]}>Connected</Text></>
            )}
            {serverStatus === "failed" && (
              <><MaterialIcons name="error" size={18} color="#FF453A" /><Text style={[styles.serverStatusText, { color: '#FF453A' }]}>{serverError || 'Connection failed'}</Text></>
            )}
          </View>
        )}

        <TouchableOpacity
          style={[styles.serverTestBtn, serverStatus === 'testing' && { opacity: 0.5 }]}
          onPress={handleSaveAndTest}
          disabled={serverStatus === 'testing'}
        >
          <MaterialIcons name="wifi-tethering" size={20} color="#fff" />
          <Text style={styles.serverTestBtnText}>Save & Test Connection</Text>
        </TouchableOpacity>
      </View>

      {/* Background Service Toggle */}
      <Text style={styles.sectionTitle}>Background Collection</Text>
      <View style={[styles.sensorSection, { borderColor: isBackgroundServiceRunning ? '#32d74b5d' : '#ff950067' }]}>
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
                {isBackgroundServiceRunning ? "Running - collecting data in background" : "Stopped - only collecting when app is running"}
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
        <Text style={styles.hcDescription}>
          Choose which sensors to collect. Turning a sensor off stops data collection immediately.        </Text>
        <Row
          label="Location"
          icon={<Icon name="location-outline" size={20} color="#15d6a9" style={{ marginRight: 8 }} />}
          value={collectLocation}
          onChange={setCollectLocation}
        />
        <Row
          label="Accelerometer"
          icon={
            <Icon name="phone-portrait-outline" size={20} color="#FF9500" style={{ marginRight: 8 }} />
          }
          value={collectAccelerometer}
          onChange={setCollectAccelerometer}
        />
        <Row
          label="Gyroscope"
          icon={
            <MaterialIcons name="screen-rotation" size={20} color="#32D74B" style={{ marginRight: 8 }} />
          }
          value={collectGyroscope}
          onChange={setCollectGyroscope}
        />
        <Row
          label="Pedometer"
          icon={<Icon name="footsteps-outline" size={20} color="#5856D6" style={{ marginRight: 8 }} />}
          value={collectPedometer}
          onChange={setCollectPedometer}
        />
        <Row
          label="Battery"
          icon={
            <Icon name="battery-charging-outline" size={20} color="#00ad03ff" style={{ marginRight: 8 }} />
          }
          value={collectBattery}
          onChange={setCollectBattery}
        />
        <Row
          label="Screen Events"
          icon={<Icon name="power-outline" size={20} color="#a700adff" style={{ marginRight: 8 }} />}
          value={collectScreenEvents}
          onChange={setCollectScreenEvents}
        />
        <Row
          label="Apps Usage"
          icon={<Icon name="apps-outline" size={20} color="#15d6a9" style={{ marginRight: 8 }} />}
          value={collectAppUsage}
          onChange={setCollectAppUsage}
        />
        <Row
          label="Notifications"
          icon={<Icon name="notifications-outline" size={20} color="#f5a623" style={{ marginRight: 8 }} />}
          value={collectNotifications}
          onChange={setCollectNotifications}
        />
      </View>

      {/* Health Connect / Wearables Section */}
      <View style={{ flexDirection: "row", alignItems: "center" }}>
        <MaterialIcons name="watch" size={20} color="#E91E63" />
        <Text style={styles.sectionTitle}>Wearables (Health Connect)</Text>
      </View>
      <View style={[styles.sensorSection, { borderColor: '#e91e6267' }]}>
        <Text style={styles.hcDescription}>
          Connect to smartwatches and fitness trackers via Health Connect to collect heart rate, steps, sleep, and more.
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
          <Text style={styles.hcButtonText}>Grant Permissions</Text>
        </TouchableOpacity>

        <TouchableOpacity style={styles.hcButton} onPress={handleOpenSettings} disabled={hcLoading}>
          <MaterialIcons name="settings" size={20} color="#888" />
          <Text style={styles.hcButtonText}>Open Health Connect</Text>
        </TouchableOpacity>

        <TouchableOpacity style={[styles.hcButton, { borderColor: '#34C759' }]} onPress={handleSyncToBackend} disabled={hcLoading}>
          <MaterialIcons name="cloud-upload" size={20} color="#34C759" />
          <Text style={[styles.hcButtonText, { color: '#34C759' }]}>Sync Wearable Data</Text>
        </TouchableOpacity>
      </View>

      {/* Privacy Section */}
      <View style={{ flexDirection: "row", alignItems: "center" }}>
        <MaterialIcons name="shield" size={20} color="#15d6a9" />
        <Text style={styles.sectionTitle}>Privacy</Text>
      </View>
      <View style={[styles.sensorSection, { borderColor: '#15d6a96e' }]}>
        <Text style={styles.hcDescription}>
          Review our data collection policies and your rights as a participant.
        </Text>

        <TouchableOpacity style={styles.hcButton} onPress={() => setShowPrivacyModal(true)}>
          <MaterialIcons name="privacy-tip" size={20} color="#15d6a9" />
          <Text style={styles.hcButtonText}>Privacy Policy</Text>
          <MaterialIcons name="chevron-right" size={20} color="#555" />
        </TouchableOpacity>

        <TouchableOpacity style={[styles.hcButton, { borderBottomWidth: 0 }]} onPress={() => setShowTermsModal(true)}>
          <MaterialIcons name="description" size={20} color="#4A90D9" />
          <Text style={styles.hcButtonText}>Terms & Conditions</Text>
          <MaterialIcons name="chevron-right" size={20} color="#555" />
        </TouchableOpacity>
      </View>

      {/* Privacy Modals */}
      <PrivacyPolicyModal
        visible={showPrivacyModal}
        onClose={() => setShowPrivacyModal(false)}
      />
      <TermsConditionsModal
        visible={showTermsModal}
        onClose={() => setShowTermsModal(false)}
      />
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
    marginBottom: 8,
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
    marginBottom: 12,
    borderWidth: 1,
    borderColor: "#15d6a96e",
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
    marginLeft: 8,
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
  serverInputRow: {
    flexDirection: "row",
    alignItems: "flex-end",
    marginBottom: 12,
  },
  serverInputLabel: {
    color: "#999",
    fontSize: 12,
    fontFamily: "Archivo-SemiBold",
    marginBottom: 4,
    marginLeft: 2,
  },
  serverInput: {
    backgroundColor: "#1a222bff",
    borderWidth: 1,
    borderColor: "#4a90d940",
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 10,
    color: "#fff",
    fontSize: 15,
    fontFamily: "Archivo",
  },
  serverStatusRow: {
    flexDirection: "row",
    alignItems: "center",
    marginBottom: 12,
    paddingVertical: 4,
  },
  serverStatusText: {
    fontSize: 13,
    fontFamily: "Archivo",
    marginLeft: 8,
  },
  serverTestBtn: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "center",
    backgroundColor: "#4A90D9",
    borderRadius: 10,
    paddingVertical: 12,
    paddingHorizontal: 16,
    marginTop: 4,
  },
  serverTestBtnText: {
    color: "#fff",
    fontSize: 15,
    fontFamily: "Archivo-SemiBold",
    marginLeft: 8,
  },
});
