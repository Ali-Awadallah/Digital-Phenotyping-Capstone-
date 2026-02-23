import { useState } from "react";
import {
    View,
    Text,
    TouchableOpacity,
    StyleSheet,
    StatusBar,
    ScrollView,
} from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";
import MaterialIcons from "@expo/vector-icons/MaterialIcons";
import AsyncStorage from "@react-native-async-storage/async-storage";
import PrivacyPolicyModal from "../components/PrivacyPolicyModal";
import TermsConditionsModal from "../components/TermsConditionsModal";

export const CONSENT_KEY = "consent_accepted";

export default function ConsentScreen({ navigation }) {
    const [agreePrivacy, setAgreePrivacy] = useState(false);
    const [agreeTerms, setAgreeTerms] = useState(false);
    const [showPrivacyModal, setShowPrivacyModal] = useState(false);
    const [showTermsModal, setShowTermsModal] = useState(false);

    const bothAccepted = agreePrivacy && agreeTerms;

    const handleContinue = async () => {
        try {
            await AsyncStorage.setItem(CONSENT_KEY, "true");
        } catch (e) {
            console.error("Error saving consent:", e);
        }
        navigation.replace("Permissions");
    };

    return (
        <SafeAreaView style={styles.container} edges={["top", "bottom"]}>
            <StatusBar barStyle="light-content" backgroundColor="#12181f" />

            <ScrollView
                contentContainerStyle={styles.scrollContent}
                showsVerticalScrollIndicator={false}
            >
                {/* Header Icon */}
                <View style={styles.header}>
                    <View style={styles.iconCircle}>
                        <MaterialIcons name="verified-user" size={40} color="#15d6a9" />
                    </View>
                    <Text style={styles.title}>Your Privacy Matters</Text>
                    <Text style={styles.subtitle}>
                        Before you begin, please review and accept our policies
                    </Text>
                </View>

                {/* Data Collection Info Card */}
                <View style={styles.infoCard}>
                    <View style={styles.infoHeader}>
                        <MaterialIcons name="info-outline" size={20} color="#15d6a9" />
                        <Text style={styles.infoTitle}>About Data Collection</Text>
                    </View>
                    <Text style={styles.infoText}>
                        This application collects behavioral and sensor data from your
                        device. Including real-time location, motion, screen events & app usage patterns, notifications metadata, and health data from
                        wearables to support scientific studies in digital phenotyping & clinical use.
                    </Text>
                    <Text style={styles.infoText}>
                        Your data is encrypted, stored securely, and used exclusively for research, voluntary clinical analysis, and risk detection. It will never be sold or shared for commercial purposes. You can
                        control which sensors are active at any time from Settings. No personal data, screenshots or any sensitive data are collected.
                    </Text>
                </View>

                {/* Consent Checkboxes */}
                <View style={styles.consentSection}>
                    {/* Privacy Policy Checkbox */}
                    <TouchableOpacity
                        style={styles.checkboxRow}
                        onPress={() => setAgreePrivacy(!agreePrivacy)}
                        activeOpacity={0.7}
                    >
                        <View style={[styles.checkbox, agreePrivacy && styles.checkboxChecked]}>
                            {agreePrivacy && (
                                <MaterialIcons name="check" size={18} color="#fff" />
                            )}
                        </View>
                        <Text style={styles.checkboxLabel}>
                            I agree to the{" "}
                            <Text
                                style={styles.linkText}
                                onPress={() => setShowPrivacyModal(true)}
                            >
                                Privacy Policy
                            </Text>
                        </Text>
                    </TouchableOpacity>

                    {/* Terms & Conditions Checkbox */}
                    <TouchableOpacity
                        style={styles.checkboxRow}
                        onPress={() => setAgreeTerms(!agreeTerms)}
                        activeOpacity={0.7}
                    >
                        <View style={[styles.checkbox, agreeTerms && styles.checkboxChecked]}>
                            {agreeTerms && (
                                <MaterialIcons name="check" size={18} color="#fff" />
                            )}
                        </View>
                        <Text style={styles.checkboxLabel}>
                            I agree to the{" "}
                            <Text
                                style={styles.linkText}
                                onPress={() => setShowTermsModal(true)}
                            >
                                Terms & Conditions
                            </Text>
                        </Text>
                    </TouchableOpacity>
                </View>
            </ScrollView>

            {/* Continue Button */}
            <View style={styles.footer}>
                <TouchableOpacity
                    style={[styles.continueButton, !bothAccepted && styles.continueDisabled]}
                    onPress={handleContinue}
                    disabled={!bothAccepted}
                >
                    <Text style={styles.continueText}>
                        {bothAccepted ? "Continue" : "Please accept both policies to continue"}
                    </Text>
                    {bothAccepted && (
                        <MaterialIcons name="arrow-forward" size={20} color="#fff" />
                    )}
                </TouchableOpacity>
            </View>

            {/* Modals */}
            <PrivacyPolicyModal
                visible={showPrivacyModal}
                onClose={() => setShowPrivacyModal(false)}
            />
            <TermsConditionsModal
                visible={showTermsModal}
                onClose={() => setShowTermsModal(false)}
            />
        </SafeAreaView>
    );
}

const styles = StyleSheet.create({
    container: {
        flex: 1,
        backgroundColor: "#12181f",
    },
    scrollContent: {
        paddingHorizontal: 20,
        paddingBottom: 20,
    },
    header: {
        alignItems: "center",
        marginTop: 24,
        marginBottom: 28,
    },
    iconCircle: {
        width: 80,
        height: 80,
        borderRadius: 40,
        backgroundColor: "rgba(21,214,169,0.12)",
        alignItems: "center",
        justifyContent: "center",
        marginBottom: 20,
        borderWidth: 1,
        borderColor: "rgba(21,214,169,0.25)",
    },
    title: {
        fontSize: 26,
        fontFamily: "Archivo-SemiBold",
        color: "#fff",
        marginBottom: 8,
    },
    subtitle: {
        fontSize: 15,
        fontFamily: "Archivo",
        color: "#8a9bb0",
        textAlign: "center",
        lineHeight: 22,
        paddingHorizontal: 12,
    },
    infoCard: {
        backgroundColor: "#222d3a",
        borderRadius: 16,
        padding: 18,
        marginBottom: 28,
        borderWidth: 1,
        borderColor: "#15d6a930",
    },
    infoHeader: {
        flexDirection: "row",
        alignItems: "center",
        gap: 8,
        marginBottom: 12,
    },
    infoTitle: {
        fontSize: 16,
        fontFamily: "Archivo-SemiBold",
        color: "#15d6a9",
    },
    infoText: {
        fontSize: 14,
        fontFamily: "Archivo",
        color: "#b0bec5",
        lineHeight: 22,
        marginBottom: 10,
    },
    consentSection: {
        gap: 14,
    },
    checkboxRow: {
        flexDirection: "row",
        alignItems: "center",
        backgroundColor: "#222d3a",
        borderRadius: 14,
        padding: 16,
        borderWidth: 1,
        borderColor: "#2a3545",
    },
    checkbox: {
        width: 26,
        height: 26,
        borderRadius: 6,
        borderWidth: 2,
        borderColor: "#4a5568",
        backgroundColor: "transparent",
        alignItems: "center",
        justifyContent: "center",
        marginRight: 14,
    },
    checkboxChecked: {
        backgroundColor: "#15d6a9",
        borderColor: "#15d6a9",
    },
    checkboxLabel: {
        flex: 1,
        fontSize: 15,
        fontFamily: "Archivo",
        color: "#ccc",
        lineHeight: 22,
    },
    linkText: {
        color: "#15d6a9",
        fontFamily: "Archivo-SemiBold",
        textDecorationLine: "underline",
    },
    footer: {
        paddingHorizontal: 20,
        paddingBottom: 12,
        paddingTop: 8,
    },
    continueButton: {
        backgroundColor: "#15d6a9",
        paddingVertical: 16,
        borderRadius: 14,
        alignItems: "center",
        justifyContent: "center",
        flexDirection: "row",
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
