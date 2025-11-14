import { View, Text, StyleSheet, TouchableOpacity } from "react-native";
import { Ionicons as Icon } from "@expo/vector-icons";
import MaterialCommunityIcons from "@expo/vector-icons/MaterialCommunityIcons";
import { useApp } from "../context/AppContext";
import ScreenContainer from "../components/ScreenContainer";

export default function AlertsScreen() {
  const { alerts, setAlerts } = useApp();
  const removeAlert = (id) =>
    setAlerts((prev) => prev.filter((a) => a.id !== id));

  return (
    <ScreenContainer>
      <View style={{ flexDirection: "row", alignItems: "center" }}>
        <Icon
          name="alert-circle-outline"
          size={28}
          color="#FF3B30"
          style={{ marginRight: 8, marginBottom: 16 }}
        />
        <Text style={styles.contentTitle}>Alerts</Text>
      </View>
      {alerts.length === 0 ? (
        <View
          style={[
            styles.sensorSection,
            { paddingVertical: 24, alignItems: "center" },
          ]}
        >
          <MaterialCommunityIcons
            name="bell-alert-outline"
            size={48}
            color="#FF3B30"
          />
          <Text style={[styles.bodyText, { marginTop: 12 }]}>
            No alerts yet.
          </Text>
        </View>
      ) : (
        <View>
          {alerts
            .slice()
            .reverse()
            .map((a, idx) => {
              const color =
                a.severity === "high"
                  ? "#FF3B30"
                  : a.severity === "medium"
                  ? "#FF9500"
                  : a.severity === "low"
                  ? "#FFCC00"
                  : "#15d6a9";
              return (
                <View
                  key={a.id || idx}
                  style={[styles.alertItem, { borderLeftColor: color }]}
                >
                  <View
                    style={{
                      flexDirection: "row",
                      alignItems: "center",
                      marginBottom: 4,
                    }}
                  >
                    <MaterialCommunityIcons
                      name="alert"
                      size={18}
                      color={color}
                    />
                    <Text
                      style={[styles.alertTitle, { marginLeft: 6, flex: 1 }]}
                    >
                      {a.title || "Alert"}
                    </Text>
                    <TouchableOpacity onPress={() => removeAlert(a.id)}>
                      <Text style={styles.alertClose}>×</Text>
                    </TouchableOpacity>
                  </View>
                  {a.message ? (
                    <Text style={styles.alertMessage}>{a.message}</Text>
                  ) : null}
                  <Text style={styles.alertTime}>
                    {new Date(a.ts).toLocaleString()}
                  </Text>
                </View>
              );
            })}
          <TouchableOpacity
            style={[styles.actionButton, { backgroundColor: "#15d6a9" }]}
            onPress={() => setAlerts([])}
          >
            <Text style={styles.actionButtonText}>Clear All</Text>
          </TouchableOpacity>
        </View>
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
  sensorSection: {
    backgroundColor: "#222d3aff",
    borderRadius: 12,
    padding: 16,
    marginBottom: 36,
    borderLeftWidth: 4,
    borderLeftColor: "#15d6a9",
  },
  bodyText: {
    fontSize: 18,
    color: "#ccccccff",
    marginBottom: 15,
    fontWeight: "500",
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
  alertItem: {
    backgroundColor: "#222d3aff",
    borderRadius: 10,
    padding: 12,
    marginBottom: 12,
    borderLeftWidth: 4,
    borderLeftColor: "#FF3B30",
  },
  alertTitle: {
    fontSize: 16,
    color: "#ccccccff",
    fontFamily: "Archivo-Medium",
  },
  alertClose: {
    fontSize: 18,
    color: "#ccccccc0",
    paddingHorizontal: 6,
    lineHeight: 18,
  },
  alertMessage: { fontSize: 14, color: "#cccccc9e", marginBottom: 6 },
  alertTime: { fontSize: 12, color: "#cccccccf" },
});
