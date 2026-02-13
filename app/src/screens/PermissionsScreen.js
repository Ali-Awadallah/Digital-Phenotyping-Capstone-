import { useState, useEffect, useCallback } from "react";
import {
    View,
    Text,
    StyleSheet,
    TouchableOpacity,
    AppState,
    Platform,
    PermissionsAndroid,
    StatusBar,
    ActivityIndicator,
    ScrollView,
} from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";
import MaterialIcons from "@expo/vector-icons/MaterialIcons";
import HealthConnectService from "../services/HealthConnectService";
import { useApp } from "../context/AppContext";
import { NativeModules } from "react-native";
import * as Location from "expo-location";

const AppUsageNative = NativeModules.AppUsage;
const NotificationAccessNative = NativeModules.NotificationAccess;

const PERMISSIONS = [
    {
        key: "location",
        label: "Location Access",
        description: "Track GPS location in foreground & background",
        icon: "location-on",
        color: "#4CAF50",
    },
    {
        key: "activity",
        label: "Physical Activity",
        description: "Detect motion via accelerometer & pedometer",
        icon: "directions-run",
        color: "#2196F3",
    },
    {
        key: "healthConnect",
        label: "Health Connect",
        description: "Read heart rate, steps, sleep & more from wearables",
        icon: "watch",
        color: "#E91E63",
    },
    {
        key: "notifications",
        label: "Notification Access",
        description: "Monitor notification activity for digital phenotyping",
        icon: "notifications-active",
        color: "#FF9800",
    },
    {
        key: "appUsage",
        label: "App Usage Access",
        description: "Collect screen time and app usage statistics",
        icon: "phone-android",
        color: "#9C27B0",
    },
];

export default function PermissionsScreen({ navigation }) {
    const { startBackgroundService } = useApp();
    const [statuses, setStatuses] = useState({
        location: false,
        activity: false,
        healthConnect: false,
        notifications: false,
        appUsage: false,
    });
    const [loading, setLoading] = useState(true);
    const [granting, setGranting] = useState(null); // which key is currently being granted

    const allGranted = Object.values(statuses).every(Boolean);

    // ── Check all permissions ──────────────────────────────────────
    const checkAllPermissions = useCallback(async () => {
        try {
            const results = { ...statuses };

            // 1. Location (fine + background)
            if (Platform.OS === "android") {
                const fine = await PermissionsAndroid.check(
                    PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION
                );
                let background = false;
                if (Platform.Version >= 29) {
                    background = await PermissionsAndroid.check(
                        PermissionsAndroid.PERMISSIONS.ACCESS_BACKGROUND_LOCATION
                    );
                } else {
                    background = fine; // pre-Q doesn't need explicit background
                }
                results.location = fine && background;
            }

            // 2. Physical Activity
            if (Platform.OS === "android" && Platform.Version >= 29) {
                results.activity = await PermissionsAndroid.check(
                    PermissionsAndroid.PERMISSIONS.ACTIVITY_RECOGNITION
                );
            } else {
                results.activity = true;
            }

            // 3. Health Connect
            try {
                const hcAvailable = await HealthConnectService.isAvailable();
                if (hcAvailable) {
                    const granted = await HealthConnectService.checkPermissions();
                    results.healthConnect = !!granted;
                } else {
                    // HC not available on device — skip this requirement
                    results.healthConnect = true;
                }
            } catch {
                results.healthConnect = false;
            }

            // 4. Notification Access
            try {
                const notifGranted = await NotificationAccessNative?.hasAccess?.();
                results.notifications = !!notifGranted;
            } catch {
                results.notifications = false;
            }

            // 5. App Usage Access
            try {
                const usageGranted = await AppUsageNative?.hasUsageAccess?.();
                results.appUsage = !!usageGranted;
            } catch {
                results.appUsage = false;
            }

            setStatuses(results);
        } catch (e) {
            console.error("Error checking permissions:", e);
        } finally {
            setLoading(false);
        }
    }, []);

    // Check on mount
    useEffect(() => {
        checkAllPermissions();
    }, [checkAllPermissions]);

    // Re-check when app returns to foreground (user may have been in system settings)
    useEffect(() => {
        const sub = AppState.addEventListener("change", (state) => {
            if (state === "active") {
                checkAllPermissions();
            }
        });
        return () => sub.remove();
    }, [checkAllPermissions]);

    // Auto-navigate if all granted
    useEffect(() => {
        if (allGranted && !loading) {
            // Small delay so user can see green checks
            const timer = setTimeout(() => {
                navigation.replace("Root");
            }, 600);
            return () => clearTimeout(timer);
        }
    }, [allGranted, loading, navigation]);

    // ── Grant handlers ──────────────────────────────────────────────
    const handleGrant = async (key) => {
        setGranting(key);
        try {
            switch (key) {
                case "location":
                    await requestLocation();
                    break;
                case "activity":
                    await requestActivity();
                    break;
                case "healthConnect":
                    await requestHealthConnect();
                    break;
                case "notifications":
                    requestNotificationAccess();
                    break;
                case "appUsage":
                    requestAppUsage();
                    break;
            }
            // Re-check after granting
            await checkAllPermissions();
        } catch (e) {
            console.error(`Error granting ${key}:`, e);
        }
        setGranting(null);
    };

    const requestLocation = async () => {
        // Request fine location first
        const fine = await PermissionsAndroid.request(
            PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION,
            {
                title: "Location Permission",
                message:
                    "This app needs access to your location to collect movement data for digital phenotyping research.",
                buttonPositive: "Grant",
            }
        );
        // Then background location (Android 10+)
        if (
            fine === PermissionsAndroid.RESULTS.GRANTED &&
            Platform.Version >= 29
        ) {
            await PermissionsAndroid.request(
                PermissionsAndroid.PERMISSIONS.ACCESS_BACKGROUND_LOCATION,
                {
                    title: "Background Location",
                    message:
                        'Allow "All the time" location access so data collection continues when the app is in the background.',
                    buttonPositive: "Grant",
                }
            );
        }
    };

    const requestActivity = async () => {
        await PermissionsAndroid.request(
            PermissionsAndroid.PERMISSIONS.ACTIVITY_RECOGNITION,
            {
                title: "Physical Activity",
                message:
                    "This app needs activity recognition to detect motion and count steps.",
                buttonPositive: "Grant",
            }
        );
    };

    const requestHealthConnect = async () => {
        try {
            await HealthConnectService.requestPermissions();
        } catch (e) {
            console.error("Health Connect permission error:", e);
        }
    };

    const requestNotificationAccess = () => {
        try {
            NotificationAccessNative?.openSettings?.();
        } catch (e) {
            console.error("Error opening notification settings:", e);
        }
    };

    const requestAppUsage = () => {
        try {
            AppUsageNative?.openUsageAccessSettings?.();
        } catch (e) {
            console.error("Error opening usage settings:", e);
        }
    };

    // ── Handle Continue ─────────────────────────────────────────────
    const handleContinue = async () => {
        try {
            await startBackgroundService();
        } catch (e) {
            console.error("Error starting background service:", e);
        }
        navigation.replace("Root");
    };

    // ── Render ──────────────────────────────────────────────────────
    if (loading) {
        return (
            <View style={styles.loadingContainer}>
                <StatusBar barStyle="light-content" backgroundColor="#1a1a2e" />
                <ActivityIndicator size="large" color="#15d6a9" />
            </View>
        );
    }

    return (
        <SafeAreaView style={styles.container} edges={["top", "bottom"]}>
            <StatusBar barStyle="light-content" backgroundColor="#1a1a2e" />

            {/* Header */}
            <View style={styles.header}>
                <View style={styles.iconCircle}>
                    <MaterialIcons name="security" size={36} color="#15d6a9" />
                </View>
                <Text style={styles.title}>Permissions Required</Text>
                <Text style={styles.subtitle}>
                    Our app requires the following permissions to collect digital
                    phenotyping data. Please grant all permissions to continue.
                </Text>
            </View>

            {/* Permission rows */}
            <ScrollView style={styles.permissionsList}>
                {PERMISSIONS.map((perm) => {
                    const granted = statuses[perm.key];
                    const isGranting = granting === perm.key;
                    return (
                        <View key={perm.key} style={styles.permRow}>
                            <View
                                style={[
                                    styles.permIconCircle,
                                    { backgroundColor: perm.color + "20" },
                                ]}
                            >
                                <MaterialIcons name={perm.icon} size={24} color={perm.color} />
                            </View>
                            <View style={styles.permInfo}>
                                <Text style={styles.permLabel}>{perm.label}</Text>
                                <Text style={styles.permDesc}>{perm.description}</Text>
                            </View>
                            {granted ? (
                                <View style={styles.grantedBadge}>
                                    <MaterialIcons
                                        name="check-circle"
                                        size={28}
                                        color="#15d6a9"
                                    />
                                </View>
                            ) : (
                                <TouchableOpacity
                                    style={styles.grantButton}
                                    onPress={() => handleGrant(perm.key)}
                                    disabled={isGranting}
                                >
                                    {isGranting ? (
                                        <ActivityIndicator size="small" color="#fff" />
                                    ) : (
                                        <Text style={styles.grantButtonText}>Grant</Text>
                                    )}
                                </TouchableOpacity>
                            )}
                        </View>
                    );
                })}
            </ScrollView>

            {/* Progress indicator */}
            <View style={styles.progressContainer}>
                <View style={styles.progressBar}>
                    <View
                        style={[
                            styles.progressFill,
                            {
                                width: `${(Object.values(statuses).filter(Boolean).length /
                                    Object.keys(statuses).length) *
                                    100
                                    }%`,
                            },
                        ]}
                    />
                </View>
                <Text style={styles.progressText}>
                    {Object.values(statuses).filter(Boolean).length} of{" "}
                    {Object.keys(statuses).length} permissions granted
                </Text>
            </View>

            {/* Continue button */}
            <TouchableOpacity
                style={[styles.continueButton, !allGranted && styles.continueDisabled]}
                onPress={handleContinue}
                disabled={!allGranted}
            >
                <Text style={styles.continueText}>
                    {allGranted ? "Continue" : "Grant All Permissions to Continue"}
                </Text>
                {allGranted && (
                    <MaterialIcons name="arrow-forward" size={20} color="#fff" />
                )}
            </TouchableOpacity>
        </SafeAreaView>
    );
}

const styles = StyleSheet.create({
    loadingContainer: {
        flex: 1,
        backgroundColor: "#12181fff",
        alignItems: "center",
        justifyContent: "center",
    },
    container: {
        flex: 1,
        backgroundColor: "#12181fff",
        paddingHorizontal: 20,
    },
    header: {
        alignItems: "center",
        marginTop: 16,
        marginBottom: 24,
    },
    iconCircle: {
        width: 72,
        height: 72,
        borderRadius: 36,
        backgroundColor: "rgba(21,214,169,0.15)",
        alignItems: "center",
        justifyContent: "center",
        marginBottom: 16,
    },
    title: {
        fontSize: 24,
        fontFamily: "Archivo-SemiBold",
        color: "#fff",
        marginBottom: 8,
    },
    subtitle: {
        fontSize: 14,
        fontFamily: "Archivo",
        color: "#8a9bb0",
        textAlign: "center",
        lineHeight: 20,
        paddingHorizontal: 12,
    },
    permissionsList: {
        flex: 1,
    },
    permRow: {
        flexDirection: "row",
        alignItems: "center",
        backgroundColor: "#222d3aff",
        borderRadius: 14,
        padding: 14,
        marginBottom: 10,
    },
    permIconCircle: {
        width: 44,
        height: 44,
        borderRadius: 22,
        alignItems: "center",
        justifyContent: "center",
        marginRight: 12,
    },
    permInfo: {
        flex: 1,
        marginRight: 8,
    },
    permLabel: {
        fontSize: 15,
        fontFamily: "Archivo-Medium",
        color: "#fff",
        marginBottom: 2,
    },
    permDesc: {
        fontSize: 12,
        fontFamily: "Archivo",
        color: "#7a8b9e",
        lineHeight: 16,
    },
    grantedBadge: {
        width: 40,
        alignItems: "center",
        justifyContent: "center",
    },
    grantButton: {
        backgroundColor: "#15d6a9",
        paddingHorizontal: 16,
        paddingVertical: 8,
        borderRadius: 8,
        minWidth: 70,
        alignItems: "center",
    },
    grantButtonText: {
        color: "#fff",
        fontSize: 14,
        fontFamily: "Archivo-SemiBold",
    },
    progressContainer: {
        alignItems: "center",
        marginVertical: 16,
    },
    progressBar: {
        width: "100%",
        height: 6,
        backgroundColor: "#2a3545",
        borderRadius: 3,
        overflow: "hidden",
        marginBottom: 8,
    },
    progressFill: {
        height: "100%",
        backgroundColor: "#15d6a9",
        borderRadius: 3,
    },
    progressText: {
        fontSize: 13,
        fontFamily: "Archivo",
        color: "#7a8b9e",
    },
    continueButton: {
        backgroundColor: "#15d6a9",
        paddingVertical: 16,
        borderRadius: 14,
        alignItems: "center",
        justifyContent: "center",
        flexDirection: "row",
        marginBottom: 12,
        gap: 8,
    },
    continueDisabled: {
        backgroundColor: "#2a3545",
    },
    continueText: {
        color: "#fff",
        fontSize: 16,
        fontFamily: "Archivo-SemiBold",
    },
});
