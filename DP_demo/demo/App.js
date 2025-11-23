import {SafeAreaView, View, StyleSheet, Text as RNText } from "react-native";
import { SafeAreaProvider } from "react-native-safe-area-context";
import { AppProvider } from "./src/context/AppContext";
import RootNavigator from "./src/navigation/RootNavigator";
import { useFonts, Archivo_400Regular, Archivo_500Medium, Archivo_600SemiBold } from '@expo-google-fonts/archivo'
import { setCustomText } from 'react-native-global-props'
import { DemoScreen } from './DemoScreen';
import { AppState } from "react-native";


export default function App() {

  //Backend connection test (comment main app code to test or put above)
//  return (
//    <SafeAreaProvider>
//      <SafeAreaView style={{ flex: 1 }}>
//        <DemoScreen />
//      </SafeAreaView>
//    </SafeAreaProvider>
//  );

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
  return (
    <SafeAreaProvider>
      <View style={styles.container}>
        <AppProvider>
          <RootNavigator />
        </AppProvider>
      </View>
    </SafeAreaProvider>
  );


}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: "#f0f4f8" },
});
