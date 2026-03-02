import { useRef, useEffect, useState } from "react";
import { Modal, View, Text, TouchableOpacity, StyleSheet, Animated, ActivityIndicator } from "react-native";
import { WebView } from "react-native-webview";
import MaterialIcons from "@expo/vector-icons/MaterialIcons";

const PRIVACY_POLICY_URL =
    "https://baraalomari.github.io/Digital-Phenotyping-Capstone-PrivacyPolicy/";

export default function PrivacyPolicyModal({ visible, onClose }) {
    const overlayOpacity = useRef(new Animated.Value(0)).current;
    const cardTranslateY = useRef(new Animated.Value(600)).current;
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        if (visible) {
            setLoading(true);
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

                    {/* WebView Content */}
                    <View style={styles.webviewContainer}>
                        {loading && (
                            <View style={styles.loadingOverlay}>
                                <ActivityIndicator size="large" color="#15d6a9" />
                                <Text style={styles.loadingText}>Loading Privacy Policy...</Text>
                            </View>
                        )}
                        <WebView
                            source={{ uri: PRIVACY_POLICY_URL }}
                            style={styles.webview}
                            onLoadEnd={() => setLoading(false)}
                            startInLoadingState={false}
                            javaScriptEnabled={true}
                            domStorageEnabled={true}
                        />
                    </View>

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
        maxHeight: "90%",
        height: "90%",
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
    webviewContainer: {
        flex: 1,
        position: "relative",
    },
    webview: {
        flex: 1,
        backgroundColor: "transparent",
    },
    loadingOverlay: {
        ...StyleSheet.absoluteFillObject,
        backgroundColor: "#1a222b",
        alignItems: "center",
        justifyContent: "center",
        zIndex: 10,
    },
    loadingText: {
        color: "#8a9bb0",
        fontSize: 14,
        fontFamily: "Archivo",
        marginTop: 12,
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
