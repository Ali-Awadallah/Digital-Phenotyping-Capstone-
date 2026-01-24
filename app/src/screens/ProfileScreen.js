import { View, Text, StyleSheet } from "react-native";
import { Ionicons as Icon } from "@expo/vector-icons";
import ScreenContainer from "../components/ScreenContainer";

export default function ProfileScreen({ userActivityCount = 0 }) {
  return (
    <ScreenContainer>
      <View style={{ flexDirection: "row", alignItems: "center" }}>
        <Icon
          name="person-outline"
          size={28}
          color="#15d6a9"
          style={{ marginRight: 8, marginBottom: 16 }}
        />
        <Text style={styles.contentTitle}>User Profile</Text>
      </View>
      <Text style={styles.profileText}>User: Test User</Text>
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
  profileText: {
    fontSize: 16,
    color: "#ccccccff",
    marginBottom: 8,
    fontFamily: "Archivo-Medium",
  },
});
