import { View, Text, TouchableOpacity, StyleSheet } from "react-native";
import {
  SafeAreaView,
  useSafeAreaInsets,
} from "react-native-safe-area-context";
import { NavigationContainer } from "@react-navigation/native";
import { createBottomTabNavigator } from "@react-navigation/bottom-tabs";
import { createNativeStackNavigator } from "@react-navigation/native-stack";
import { Ionicons as Icon } from "@expo/vector-icons";
import HomeScreen from "../screens/HomeScreen";
import SensorsScreen from "../screens/SensorsScreen";
import SettingsScreen from "../screens/SettingsScreen";
import ProfileScreen from "../screens/ProfileScreen";
import AlertsScreen from "../screens/AlertsScreen";
import PermissionsScreen from "../screens/PermissionsScreen";
import NotificationPing from "../components/NotificationPing";
import { useApp } from "../context/AppContext";

const Tab = createBottomTabNavigator();
const Stack = createNativeStackNavigator();

function MyTabBar({ state, descriptors, navigation }) {
  const { alerts } = useApp();
  const insets = useSafeAreaInsets();
  return (
    <SafeAreaView
      edges={["bottom"]}
      style={[styles.bottomNav, { paddingBottom: insets.bottom }]}
    >
      {state.routes.map((route, index) => {
        const { options } = descriptors[route.key];
        const label =
          options.tabBarLabel !== undefined
            ? options.tabBarLabel
            : options.title !== undefined
              ? options.title
              : route.name;

        const isFocused = state.index === index;
        const isCenter = route.name === "Home";
        const iconName =
          route.name === "Home"
            ? "home-outline"
            : route.name === "Sensors"
              ? "analytics-outline"
              : route.name === "Settings"
                ? "settings-outline"
                : route.name === "Profile"
                  ? "person-outline"
                  : "alert-circle-outline";
        const showAlertPing =
          route.name === "Alerts" && alerts && alerts.length > 0;

        const onPress = () => {
          const event = navigation.emit({
            type: "tabPress",
            target: route.key,
            canPreventDefault: true,
          });
          if (!isFocused && !event.defaultPrevented)
            navigation.navigate(route.name);
        };

        return (
          <TouchableOpacity
            key={route.key}
            accessibilityRole="button"
            onPress={onPress}
            style={[styles.bottomNavItem, isCenter && styles.bottomNavCenter]}
          >
            <View style={styles.navIconWrap}>
              <Icon
                name={iconName}
                size={isCenter ? 32 : 28}
                color={
                  isFocused
                    ? isCenter
                      ? "#FFF"
                      : "#15d6a9"
                    : isCenter
                      ? "#222d3aff"
                      : "#b0b0b0ff"
                }
              />
              {!isCenter && showAlertPing ? <NotificationPing /> : null}
            </View>
            {!isCenter && (
              <Text
                style={[
                  styles.bottomNavText,
                  isFocused && { color: "#15d6a9", fontWeight: "600" },
                ]}
              >
                {label}
              </Text>
            )}
          </TouchableOpacity>
        );
      })}
    </SafeAreaView>
  );
}

function Tabs() {
  return (
    <Tab.Navigator
      tabBar={(props) => <MyTabBar {...props} />}
      screenOptions={{ headerShown: false }}
      initialRouteName="Home"
    >
      <Tab.Screen name="Sensors" component={SensorsScreen} />
      <Tab.Screen name="Alerts" component={AlertsScreen} />
      <Tab.Screen name="Home" component={HomeScreen} />
      <Tab.Screen name="Settings" component={SettingsScreen} />
      <Tab.Screen name="Profile" component={ProfileScreen} />
    </Tab.Navigator>
  );
}

export default function RootNavigator() {
  return (
    <NavigationContainer>
      <Stack.Navigator
        screenOptions={{
          headerShown: false,
          animation: "slide_from_right",
          gestureEnabled: true,
          fullScreenGestureEnabled: true,
        }}
      >
        <Stack.Screen name="Permissions" component={PermissionsScreen} />
        <Stack.Screen name="Root" component={Tabs} />
      </Stack.Navigator>
    </NavigationContainer>
  );
}

const styles = StyleSheet.create({
  bottomNav: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-around",
    paddingVertical: 8,
    paddingHorizontal: 8,
    marginBottom: -15,
    backgroundColor: "#222d3aff",
    borderTopWidth: 1,
    borderTopColor: "#15d6a9",
  },
  bottomNavItem: {
    flex: 1,
    alignItems: "center",
    justifyContent: "center",
    paddingVertical: 6,
  },
  bottomNavText: { fontSize: 12, color: "#b0b0b0ff", marginTop: 2 },
  bottomNavCenter: {
    backgroundColor: "#15d6a9",
    width: 106,
    height: 76,
    borderRadius: 28,
    alignItems: "center",
    justifyContent: "center",
    marginTop: -45,
    shadowColor: "#000",
    shadowOpacity: 0.15,
    shadowRadius: 6,
    shadowOffset: { width: 0, height: 3 },
    elevation: 4,
  },
  navIconWrap: {
    position: "relative",
    alignItems: "center",
    justifyContent: "center",
  },
});
