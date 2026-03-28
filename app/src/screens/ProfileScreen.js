import { View, Text, StyleSheet, ActivityIndicator, TextInput, TouchableOpacity, Alert, Clipboard } from "react-native";
import { useState, useEffect, useCallback } from "react";
import { Ionicons as Icon } from "@expo/vector-icons";
import MaterialIcons from "@expo/vector-icons/MaterialIcons";
import ScreenContainer from "../components/ScreenContainer";
import BackgroundService from "../services/BackgroundService";
import { getParticipant } from "../../awareAPI";

export default function ProfileScreen() {
  const [loading, setLoading] = useState(true);
  const [participant, setParticipant] = useState(null);
  const [deviceId, setDeviceId] = useState(null);
  const [error, setError] = useState(null);

  const fetchProfile = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      let devId = null;
      if (BackgroundService.isAvailable()) {
        devId = await BackgroundService.getDeviceId();
      }
      if (!devId) {
        devId = "demo-phone";
      }
      setDeviceId(devId);

      const data = await getParticipant(devId);
      setParticipant(data);
    } catch (e) {
      setError("Failed to load profile");
      console.warn("Profile fetch error:", e);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchProfile();
  }, [fetchProfile]);

  const copyToClipboard = (text, label) => {
    if (Clipboard?.setString) {
      Clipboard.setString(text);
      Alert.alert("Copied", `${label} copied to clipboard`);
    }
  };

  if (loading) {
    return (
      <ScreenContainer>
        <View style={styles.centerContainer}>
          <ActivityIndicator size="large" color="#15d6a9" />
          <Text style={styles.loadingText}>Loading profile...</Text>
        </View>
      </ScreenContainer>
    );
  }

  return (
    <ScreenContainer>
      {/* Header */}
      <View style={styles.headerRow}>
        <Icon name="person-outline" size={28} color="#15d6a9" style={{ marginRight: 8 }} />
        <Text style={styles.contentTitle}>User Profile</Text>
      </View>

      <View style={{ height: 1, backgroundColor: "#6464644a", marginVertical: 5 }} />

      {/* Connection Status */}
      <View style={[styles.statusBanner, participant ? styles.statusConnected : styles.statusDisconnected]}>
        <MaterialIcons
          name={participant ? "cloud-done" : "cloud-off"}
          size={18}
          color={participant ? "#32D74B" : "#FF453A"}
        />
        <Text style={[styles.statusText, { color: participant ? "#32D74B" : "#FF453A" }]}>
          {participant ? "Ready" : "Not Registered yet"}
        </Text>
        <TouchableOpacity onPress={fetchProfile} style={styles.refreshBtn}>
          <MaterialIcons name="refresh" size={18} color="#999" />
        </TouchableOpacity>
      </View>

      {/* Profile Card */}
      <View style={styles.profileCard}>
        {/* Avatar */}
        <View style={styles.avatarRow}>
          <View style={styles.avatar}>
            <Icon name="person" size={40} color="#15d6a9" />
          </View>
          <View style={{ flex: 1, marginLeft: 16 }}>
            <View style={styles.nameRow}>
              <Text style={styles.nameText}>{participant?.name || "Unknown"}</Text>
            </View>
            <Text style={styles.statusLabel}>
              {participant?.status === "active" ? "● Active" : "○ Inactive"}
            </Text>
          </View>
        </View>

        {/* Info Rows */}
        <View style={styles.divider} />

        <InfoRow
          icon="fingerprint"
          label="Participant ID"
          value={participant?.participant_id || "N/A"}
          copyable
          onCopy={() => copyToClipboard(participant?.participant_id, "Participant ID")}
        />
        <InfoRow
          icon="phone-android"
          label="Device ID"
          value={deviceId || "N/A"}
          copyable
          onCopy={() => copyToClipboard(deviceId, "Device ID")}
        />
        <InfoRow
          icon="devices"
          label="Device Type"
          value={participant?.device_type || "phone"}
        />
        <InfoRow
          icon="event"
          label="Registered"
          value={participant?.created_at ? formatDate(participant.created_at) : "N/A"}
        />
      </View>

      {/* Error Display */}
      {error && (
        <View style={styles.errorBox}>
          <MaterialIcons name="error-outline" size={18} color="#FF453A" />
          <Text style={styles.errorText}>{error}</Text>
        </View>
      )}

      {/* Not Registered Info */}
      {!participant && !loading && (
        <View style={styles.infoBox}>
          <MaterialIcons name="info-outline" size={18} color="#4A90D9" />
          <Text style={styles.infoText}>
            Your profile will appear here once your device is ready.
          </Text>
        </View>
      )}
    </ScreenContainer>
  );
}

function InfoRow({ icon, label, value, copyable, onCopy, valueStyle }) {
  return (
    <View style={styles.infoRow}>
      <MaterialIcons name={icon} size={20} color="#888" style={styles.infoIcon} />
      <View style={{ flex: 1 }}>
        <Text style={styles.infoLabel}>{label}</Text>
        <Text style={[styles.infoValue, valueStyle]} numberOfLines={1} ellipsizeMode="middle">
          {value}
        </Text>
      </View>
      {copyable && (
        <TouchableOpacity onPress={onCopy} style={styles.copyBtn}>
          <MaterialIcons name="content-copy" size={16} color="#666" />
        </TouchableOpacity>
      )}
    </View>
  );
}

function formatDate(timestamp) {
  if (!timestamp) return "N/A";
  try {
    let ts = timestamp;
    if (typeof ts === "string") {
      if (!ts.includes("T")) ts = ts.replace(" ", "T");
      if (!ts.endsWith("Z") && !/[+-]\d{2}:\d{2}$/.test(ts)) ts += "Z";
    }
    const d = new Date(ts);
    if (isNaN(d.getTime())) return String(timestamp);
    return d.toLocaleDateString("en-US", {
      year: "numeric", month: "short", day: "numeric",
    });
  } catch {
    return String(timestamp);
  }
}

const styles = StyleSheet.create({
  centerContainer: {
    flex: 1,
    justifyContent: "center",
    alignItems: "center",
  },
  loadingText: {
    color: "#999",
    marginTop: 12,
    fontSize: 14,
    fontFamily: "Archivo",
  },
  headerRow: {
    flexDirection: "row",
    alignItems: "center",
    marginBottom: 8,
  },
  contentTitle: {
    fontSize: 24,
    color: "#ccccccff",
    fontFamily: "Archivo-SemiBold",
  },
  statusBanner: {
    flexDirection: "row",
    alignItems: "center",
    borderRadius: 10,
    paddingVertical: 10,
    paddingHorizontal: 14,
    marginVertical: 16,
  },
  statusConnected: {
    backgroundColor: "#32d74b15",
    borderWidth: 1,
    borderColor: "#32d74b40",
  },
  statusDisconnected: {
    backgroundColor: "#ff453a15",
    borderWidth: 1,
    borderColor: "#ff453a40",
  },
  statusText: {
    fontSize: 13,
    fontFamily: "Archivo-SemiBold",
    marginLeft: 8,
    flex: 1,
  },
  refreshBtn: {
    padding: 4,
  },
  profileCard: {
    backgroundColor: "#1a222bff",
    borderRadius: 14,
    borderWidth: 1,
    borderColor: "#15d6a940",
    padding: 20,
    marginBottom: 16,
  },
  avatarRow: {
    flexDirection: "row",
    alignItems: "center",
    marginBottom: 16,
  },
  avatar: {
    width: 64,
    height: 64,
    borderRadius: 32,
    backgroundColor: "#15d6a918",
    borderWidth: 2,
    borderColor: "#15d6a950",
    justifyContent: "center",
    alignItems: "center",
  },
  nameRow: {
    flexDirection: "row",
    alignItems: "center",
  },
  nameText: {
    fontSize: 20,
    color: "#fff",
    fontFamily: "Archivo-SemiBold",
  },
  editBtn: {
    marginLeft: 10,
    padding: 4,
  },
  nameEditRow: {
    flexDirection: "row",
    alignItems: "center",
  },
  nameInput: {
    flex: 1,
    backgroundColor: "#0f151bff",
    borderWidth: 1,
    borderColor: "#4a90d940",
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 8,
    color: "#fff",
    fontSize: 16,
    fontFamily: "Archivo",
  },
  saveBtn: {
    backgroundColor: "#15d6a9",
    borderRadius: 8,
    padding: 8,
    marginLeft: 8,
  },
  cancelBtn: {
    padding: 8,
    marginLeft: 4,
  },
  statusLabel: {
    fontSize: 13,
    color: "#32D74B",
    fontFamily: "Archivo",
    marginTop: 4,
  },
  divider: {
    height: 1,
    backgroundColor: "#ffffff15",
    marginVertical: 12,
  },
  infoRow: {
    flexDirection: "row",
    alignItems: "center",
    paddingVertical: 10,
  },
  infoIcon: {
    marginRight: 14,
    width: 24,
    textAlign: "center",
  },
  infoLabel: {
    fontSize: 11,
    color: "#888",
    fontFamily: "Archivo",
    textTransform: "uppercase",
    letterSpacing: 0.5,
  },
  infoValue: {
    fontSize: 15,
    color: "#ddd",
    fontFamily: "Archivo-Medium",
    marginTop: 2,
  },
  copyBtn: {
    padding: 8,
  },
  riskHigh: { color: "#FF453A" },
  riskMedium: { color: "#FFD60A" },
  riskLow: { color: "#32D74B" },
  errorBox: {
    flexDirection: "row",
    alignItems: "center",
    backgroundColor: "#ff453a15",
    borderRadius: 10,
    padding: 12,
    borderWidth: 1,
    borderColor: "#ff453a40",
    marginBottom: 12,
  },
  errorText: {
    color: "#FF453A",
    fontSize: 13,
    fontFamily: "Archivo",
    marginLeft: 8,
    flex: 1,
  },
  infoBox: {
    flexDirection: "row",
    alignItems: "flex-start",
    backgroundColor: "#4a90d915",
    borderRadius: 10,
    padding: 12,
    borderWidth: 1,
    borderColor: "#4a90d940",
    marginBottom: 12,
  },
  infoText: {
    color: "#4A90D9",
    fontSize: 13,
    fontFamily: "Archivo",
    marginLeft: 8,
    flex: 1,
    lineHeight: 19,
  },
});
