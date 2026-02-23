import { useRef, useEffect } from "react";
import { Modal, View, Text, TouchableOpacity, ScrollView, StyleSheet, Animated } from "react-native";
import MaterialIcons from "@expo/vector-icons/MaterialIcons";

export default function PrivacyPolicyModal({ visible, onClose }) {
    const overlayOpacity = useRef(new Animated.Value(0)).current;
    const cardTranslateY = useRef(new Animated.Value(600)).current;

    useEffect(() => {
        if (visible) {
            Animated.parallel([
                Animated.timing(overlayOpacity, { toValue: 1, duration: 250, useNativeDriver: true }),
                Animated.spring(cardTranslateY, { toValue: 0, useNativeDriver: true, bounciness: 4, speed: 14 }),
            ]).start();
        } else {
            overlayOpacity.setValue(0);
            cardTranslateY.setValue(600);
        }
    }, [visible]);

    const handleClose = () => {
        Animated.parallel([
            Animated.timing(overlayOpacity, { toValue: 0, duration: 220, useNativeDriver: true }),
            Animated.timing(cardTranslateY, { toValue: 600, duration: 220, useNativeDriver: true }),
        ]).start(() => onClose());
    };
    return (
        <Modal
            visible={visible}
            animationType="none"
            transparent={true}
            onRequestClose={handleClose}
        >
            <Animated.View style={[styles.overlay, { opacity: overlayOpacity }]}>
                <Animated.View style={[styles.container, { transform: [{ translateY: cardTranslateY }] }]}>
                    {/* Header */}
                    <View style={styles.header}>
                        <View style={styles.headerLeft}>
                            <MaterialIcons name="privacy-tip" size={24} color="#15d6a9" />
                            <Text style={styles.title}>Privacy Policy</Text>
                        </View>
                        <TouchableOpacity onPress={handleClose} style={styles.closeBtn}>
                            <MaterialIcons name="close" size={24} color="#8a9bb0" />
                        </TouchableOpacity>
                    </View>

                    {/* Content */}
                    <ScrollView style={styles.scroll} showsVerticalScrollIndicator={false}>
                        <Text style={styles.lastUpdated}>Last updated: February 2026</Text>

                        <Text style={styles.sectionTitle}>1. Introduction</Text>
                        <Text style={styles.bodyText}>
                            This Privacy Policy explains how our Digital Phenotyping application collects,
                            uses, stores, and protects your personal data. The application is used for
                            academic research and clinical analysis by healthcare professionals and
                            researchers to identify mental health risks, digital behavioral risks, and
                            substance-use indicators from daily mobile behavioral patterns. By using this
                            application, you consent to the data practices described in this policy.
                        </Text>

                        <Text style={styles.sectionTitle}>2. Data We Collect</Text>
                        <Text style={styles.bodyText}>
                            Our application collects the following types of data for digital phenotyping
                            research purposes:
                        </Text>
                        <Text style={styles.bulletItem}>• <Text style={styles.bold}>Location Data:</Text> GPS coordinates collected in foreground and background to analyze mobility patterns.</Text>
                        <Text style={styles.bulletItem}>• <Text style={styles.bold}>Motion Sensors:</Text> Accelerometer and gyroscope data to detect physical activity and movement patterns.</Text>
                        <Text style={styles.bulletItem}>• <Text style={styles.bold}>Pedometer:</Text> Step count data to track daily physical activity.</Text>
                        <Text style={styles.bulletItem}>• <Text style={styles.bold}>Screen Events:</Text> Screen on/off events and unlock patterns.</Text>
                        <Text style={styles.bulletItem}>• <Text style={styles.bold}>App Usage:</Text> Application usage statistics and screen time data.</Text>
                        <Text style={styles.bulletItem}>• <Text style={styles.bold}>Notifications:</Text> Notification metadata (not content) for interaction pattern analysis.</Text>
                        <Text style={styles.bulletItem}>• <Text style={styles.bold}>Battery Status:</Text> Battery level and charging state.</Text>
                        <Text style={styles.bulletItem}>• <Text style={styles.bold}>Wearable Data:</Text> Health data from connected wearables via Health Connect (heart rate, sleep, etc.).</Text>

                        <Text style={styles.sectionTitle}>3. How We Use Your Data</Text>
                        <Text style={styles.bodyText}>
                            Collected data is used for the following purposes:
                        </Text>
                        <Text style={styles.bulletItem}>• <Text style={styles.bold}>Clinical Analysis:</Text> Authorized healthcare professionals may analyze your behavioral data to identify mental health risks, digital behavioral risks, and substance-use indicators.</Text>
                        <Text style={styles.bulletItem}>• <Text style={styles.bold}>Academic Research:</Text> Anonymized data may be used in scientific studies on digital phenotyping and behavioral health.</Text>
                        <Text style={styles.bulletItem}>• <Text style={styles.bold}>Risk Detection:</Text> Automated analysis to flag behavioral patterns associated with mental health concerns or substance use for review by qualified professionals.</Text>
                        <Text style={styles.bodyText}>
                            Your data will never be sold to third parties or used for advertising purposes.
                        </Text>

                        <Text style={styles.sectionTitle}>4. Data Storage & Security</Text>
                        <Text style={styles.bodyText}>
                            Your data is stored securely on our research servers with encryption in transit
                            and at rest. Access to identifiable data is restricted to authorized research
                            personnel only. We implement industry-standard security measures to protect
                            your information.
                        </Text>

                        <Text style={styles.sectionTitle}>5. Data Retention</Text>
                        <Text style={styles.bodyText}>
                            Research data is retained for the duration of the study and may be kept in
                            anonymized form for future research. You may request deletion of your data at
                            any time by contacting the research team.
                        </Text>

                        <Text style={styles.sectionTitle}>6. Your Rights</Text>
                        <Text style={styles.bodyText}>
                            You have the right to:
                        </Text>
                        <Text style={styles.bulletItem}>• Access, correct, or delete your personal data</Text>
                        <Text style={styles.bulletItem}>• Withdraw consent at any time by disabling sensors in Settings</Text>
                        <Text style={styles.bulletItem}>• Request a copy of your collected data</Text>
                        <Text style={styles.bulletItem}>• File a complaint with a data protection authority</Text>

                        <Text style={styles.sectionTitle}>7. Contact</Text>
                        <Text style={styles.bodyText}>
                            For questions about this Privacy Policy or your data, please contact the
                            research team at: research@digitalphenotyping.org
                        </Text>

                        <View style={{ height: 24 }} />
                    </ScrollView>

                    {/* Close Button */}
                    <TouchableOpacity style={styles.doneBtn} onPress={handleClose}>
                        <Text style={styles.doneBtnText}>Close</Text>
                    </TouchableOpacity>
                </Animated.View>
            </Animated.View>
        </Modal>
    );
}

const styles = StyleSheet.create({
    overlay: {
        flex: 1,
        backgroundColor: "rgba(0,0,0,0.7)",
        justifyContent: "center",
        alignItems: "center",
        padding: 20,
    },
    container: {
        width: "100%",
        maxHeight: "85%",
        backgroundColor: "#1a222b",
        borderRadius: 20,
        borderWidth: 1,
        borderColor: "#15d6a940",
        overflow: "hidden",
    },
    header: {
        flexDirection: "row",
        alignItems: "center",
        justifyContent: "space-between",
        paddingHorizontal: 20,
        paddingVertical: 16,
        borderBottomWidth: 1,
        borderBottomColor: "#2a3545",
    },
    headerLeft: {
        flexDirection: "row",
        alignItems: "center",
        gap: 10,
    },
    title: {
        fontSize: 20,
        fontFamily: "Archivo-SemiBold",
        color: "#fff",
    },
    closeBtn: {
        padding: 4,
    },
    scroll: {
        paddingHorizontal: 20,
        paddingTop: 16,
    },
    lastUpdated: {
        fontSize: 12,
        fontFamily: "Archivo",
        color: "#6a7b8e",
        marginBottom: 16,
    },
    sectionTitle: {
        fontSize: 16,
        fontFamily: "Archivo-SemiBold",
        color: "#15d6a9",
        marginTop: 16,
        marginBottom: 8,
    },
    bodyText: {
        fontSize: 14,
        fontFamily: "Archivo",
        color: "#b0bec5",
        lineHeight: 22,
        marginBottom: 8,
    },
    bulletItem: {
        fontSize: 14,
        fontFamily: "Archivo",
        color: "#b0bec5",
        lineHeight: 22,
        marginBottom: 4,
        paddingLeft: 8,
    },
    bold: {
        fontFamily: "Archivo-SemiBold",
        color: "#ccc",
    },
    doneBtn: {
        backgroundColor: "#15d6a9",
        marginHorizontal: 20,
        marginVertical: 16,
        paddingVertical: 14,
        borderRadius: 12,
        alignItems: "center",
    },
    doneBtnText: {
        fontSize: 16,
        fontFamily: "Archivo-SemiBold",
        color: "#fff",
    },
});
