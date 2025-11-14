import { View, Text, StyleSheet, Switch, Alert } from "react-native";
import { Ionicons as Icon } from "@expo/vector-icons";
import MaterialIcons from "@expo/vector-icons/MaterialIcons";
import { useApp } from "../context/AppContext";
import ScreenContainer from "../components/ScreenContainer";

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
  } = useApp();

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
    marginLeft: 8,
    fontSize: 16,
    color: "#ccccccff",
    fontFamily: "Archivo-SemiBold",
  },
});
