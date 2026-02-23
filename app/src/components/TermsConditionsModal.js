import { useRef, useEffect } from "react";
import { Modal, View, Text, TouchableOpacity, ScrollView, StyleSheet, Animated } from "react-native";
import MaterialIcons from "@expo/vector-icons/MaterialIcons";

export default function TermsConditionsModal({ visible, onClose }) {
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
                            <MaterialIcons name="description" size={24} color="#4A90D9" />
                            <Text style={styles.title}>Terms & Conditions</Text>
                        </View>
                        <TouchableOpacity onPress={handleClose} style={styles.closeBtn}>
                            <MaterialIcons name="close" size={24} color="#8a9bb0" />
                        </TouchableOpacity>
                    </View>

                    {/* Content */}
                    <ScrollView style={styles.scroll} showsVerticalScrollIndicator={false}>
                        <Text style={styles.lastUpdated}>Last updated: February 2026</Text>

                        <Text style={styles.sectionTitle}>1. Acceptance of Terms</Text>
                        <Text style={styles.bodyText}>
                            By downloading, installing, or using the Digital Phenotyping application,
                            you agree to be bound by these Terms and Conditions. If you do not agree,
                            please do not use the application.
                        </Text>

                        <Text style={styles.sectionTitle}>2. Purpose of the Application</Text>
                        <Text style={styles.bodyText}>
                            This application supports two primary use cases:
                        </Text>
                        <Text style={styles.bulletItem}>• <Text style={styles.bold}>Clinical Use:</Text>
                            <Text style={styles.bodyText}>Licensed healthcare professionals and clinicians may use this app to analyze daily mobile behavioral data and identify mental health risks, digital behavioral risks, and substance-use indicators in their patients or study subjects.</Text>
                        </Text>
                        <Text style={styles.bulletItem}>• <Text style={styles.bold}>Academic Research:</Text>
                            <Text style={styles.bodyText}>Researchers and institutions may use this platform to conduct scientific studies in the field of digital phenotyping and behavioral health.</Text>
                        </Text>
                        <Text style={styles.bodyText}>
                            The app is a decision-support tool and does not replace professional clinical judgment.
                        </Text>

                        <Text style={styles.sectionTitle}>3. Eligibility</Text>
                        <Text style={styles.bodyText}>
                            You must be at least 18 years old to use this application. Healthcare
                            professionals accessing clinical features must hold a valid license in their
                            respective jurisdiction. By using the app, you confirm that you meet the
                            applicable eligibility requirements and have the legal capacity to enter
                            into this agreement.
                        </Text>

                        <Text style={styles.sectionTitle}>4. User Responsibilities</Text>
                        <Text style={styles.bodyText}>As a user of this application, you agree to:</Text>
                        <Text style={styles.bulletItem}>• Provide accurate information when requested</Text>
                        <Text style={styles.bulletItem}>• Keep the application updated to the latest version</Text>
                        <Text style={styles.bulletItem}>• Not attempt to reverse-engineer, modify, or tamper with the application</Text>
                        <Text style={styles.bulletItem}>• Not use the application for any unlawful purpose</Text>
                        <Text style={styles.bulletItem}>• Report any bugs or security vulnerabilities to the research team</Text>

                        <Text style={styles.sectionTitle}>5. Data Collection & Consent</Text>
                        <Text style={styles.bodyText}>
                            The application collects sensor data including but not limited to location,
                            motion, screen events, app usage, notifications metadata and health data from wearables. By accepting
                            these terms, you provide informed consent for this data collection. You may
                            withdraw consent at any time by disabling individual sensors in the app
                            Settings or by uninstalling the application.
                        </Text>

                        <Text style={styles.sectionTitle}>6. Intellectual Property</Text>
                        <Text style={styles.bodyText}>
                            All intellectual property rights in the application, including its design,
                            code, and research methodologies, belong to the research team and affiliated
                            institution. You are granted a limited, non-exclusive, non-transferable license
                            to use the application for its intended research purpose.
                        </Text>

                        <Text style={styles.sectionTitle}>7. Limitation of Liability & Medical Disclaimer</Text>
                        <Text style={styles.bodyText}>
                            This application is a behavioral data collection and analysis tool. It is
                            intended to support — not replace — the professional judgment of licensed
                            clinicians and researchers. Risk indicators produced by the app must be
                            reviewed and interpreted by a qualified professional before any clinical
                            action is taken. The development team is not liable for any harm arising
                            from misuse, misinterpretation of outputs, or reliance on app-generated
                            insights without professional review.
                        </Text>

                        <Text style={styles.sectionTitle}>8. Termination</Text>
                        <Text style={styles.bodyText}>
                            The research team reserves the right to terminate your access to the
                            application at any time. You may stop participating at any time by uninstalling
                            the application and requesting deletion of your data.
                        </Text>

                        <Text style={styles.sectionTitle}>9. Changes to Terms</Text>
                        <Text style={styles.bodyText}>
                            We reserve the right to modify these Terms & Conditions at any time. Continued
                            use of the application after changes constitutes acceptance of the revised
                            terms. You will be notified of significant changes through the application.
                        </Text>

                        <Text style={styles.sectionTitle}>10. Contact</Text>
                        <Text style={styles.bodyText}>
                            For questions about these Terms & Conditions, please contact the research team
                            at: research@digitalphenotyping.org
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
        borderColor: "#4A90D940",
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
        color: "#4A90D9",
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
    doneBtn: {
        backgroundColor: "#4A90D9",
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
