import {SafeAreaView, View, StyleSheet, Text as RNText } from "react-native";
import { SafeAreaProvider } from "react-native-safe-area-context";
import { AppProvider } from "./src/context/AppContext";
import RootNavigator from "./src/navigation/RootNavigator";
import { useFonts, Archivo_400Regular, Archivo_500Medium, Archivo_600SemiBold } from '@expo-google-fonts/archivo'
import { setCustomText } from 'react-native-global-props'
import SensorService from "./SensorsService.js"; //
import { AppState } from "react-native";


export default function App() {

  //custom font
  const [fontsLoaded] = useFonts({
    'Archivo': Archivo_400Regular,
    'Archivo-Medium': Archivo_500Medium,
    'Archivo-SemiBold': Archivo_600SemiBold,
  });

  if (fontsLoaded) {
    try {
      setCustomText({ style: { fontFamily: 'Archivo' } });
    } catch (e) {
      if (!RNText.defaultProps) RNText.defaultProps = {};
      RNText.defaultProps.style = [RNText.defaultProps.style, { fontFamily: 'Archivo' }];
    }
  }

  //Main App
  // ✅ MAIN APP + BACKGROUND SENSORS
  return (
    <SafeAreaProvider>
      <View style={styles.container}>
        <AppProvider>
          {/* This runs in background, no UI */}
          <SensorService />

          {/* This is your real app UI */}
          <RootNavigator />
        </AppProvider>
      </View>
    </SafeAreaProvider>
  );
}


const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: "#f0f4f8" },
});
