import React, { useState, useEffect } from 'react';
import {
  View,
  Text,
  TouchableOpacity,
  ScrollView,
//  SafeAreaView,
  StyleSheet,
  Alert,
  NativeModules,
  Platform,
} from 'react-native';
import { SafeAreaProvider, SafeAreaView } from "react-native-safe-area-context";
import { Image } from 'react-native';
import MaterialCommunityIcons from '@expo/vector-icons/MaterialCommunityIcons';
import { Ionicons as Icon } from '@expo/vector-icons';
import MaterialIcons from '@expo/vector-icons/MaterialIcons';
import * as Location from 'expo-location';
import { Accelerometer, Gyroscope } from 'expo-sensors';
import * as FileSystem from 'expo-file-system/legacy';
import Constants from 'expo-constants';
import { DemoScreen } from './DemoScreen';

const AppUsageNative = NativeModules.AppUsage;

// Friendly names and fallback icons for known packages
const KNOWN_APPS = {
  'com.zhiliaoapp.musically': { name: 'TikTok', iconName: 'tiktok' },
  'com.instagram.android': { name: 'Instagram', iconName: 'instagram' },
  'com.facebook.katana': { name: 'Facebook', iconName: 'facebook' },
  'com.whatsapp': { name: 'WhatsApp', iconName: 'whatsapp' },
  'com.snapchat.android': { name: 'Snapchat', iconName: 'snapchat' },
};


// --- Sub-Components for Navigation Views ---

const HomeScreen = ({ totalScreenTime, appsUsage, hasUsageAccess, onRequestAccess, onRefreshUsage }) => (
  <View style={styles.contentView}>
    <View style={{ flexDirection: 'row', alignItems: 'center' }}>
      <MaterialIcons name="phone-iphone" size={28} color="#007AFF" style={{ marginRight: 8, marginBottom: 14 }} />
      <Text style={styles.contentTitle}>Activity Overview</Text>
    </View>

    <View style={styles.timeCard}>
      <Icon name="timer-outline" size={30} color="#007AFF" />
      <Text style={styles.timeValue}>{totalScreenTime} min</Text>
      <Text style={styles.timeLabel}>Total Screen Time Today</Text>
    </View>
    {!hasUsageAccess && Platform.OS === 'android' ? (
      <View style={styles.sensorSection}>
        <Text style={styles.sensorDisabled}>Usage access is not granted. Grant to show real app usage.</Text>
        <Text style={styles.sensorDisabled}>Close the app and Open it agian to view the usage</Text>
        <TouchableOpacity style={[styles.actionButton, { marginTop: 8, backgroundColor: '#007AFF' }]} onPress={onRequestAccess}>
          <Text style={styles.actionButtonText}>Open Usage Access Settings</Text>
        </TouchableOpacity>
      </View>
    ) : null}

    <Text style={styles.sectionHeader}>Top App Usage</Text>
    {(appsUsage && appsUsage.length > 0) ? (
      <View>
        {appsUsage.slice(0, 5).map((app, index) => {
          const pkg = app.package || '';
          const known = KNOWN_APPS[pkg];
          const displayName = (app.name && app.name !== pkg) ? app.name : (known?.name || pkg);
          return (
            <View key={index} style={styles.appItem}>
              {app.icon ? (
                <Image source={{ uri: app.icon }} style={{ width: 24, height: 24, borderRadius: 4, marginRight: 6 }} />
              ) : known?.iconName ? (
                <MaterialCommunityIcons name={known.iconName} size={24} color="#333" style={{ width: 30 }} />
              ) : (
                <Icon name={'apps-outline'} size={20} color="#333" style={{ width: 30 }} />
              )}
              <Text style={styles.appName}>{displayName}</Text>
              <Text style={styles.appTime}>{Math.round((app.ms || 0) / 60000)} min</Text>
              <View style={[styles.timeBar, { width: `${Math.min(100, (app.ms / (appsUsage[0].ms || 1)) * 100)}%` }]} />
            </View>
          );
        })}
        <TouchableOpacity style={[styles.actionButton, { marginTop: 12, backgroundColor: '#007AFF' }]} onPress={onRefreshUsage}>
          <Text style={styles.actionButtonText}>Refresh Usage</Text>
        </TouchableOpacity>
      </View>
    ) : (
      <Text style={styles.sensorDisabled}>No usage data yet.</Text>
    )}
  </View>
);

const SettingsScreen = () => (
  <View style={styles.contentView}>
    <View style={{ flexDirection: 'row', alignItems: 'center' }}>
      <Icon name="settings-outline" size={28} color="#007AFF" style={{ marginRight: 8, marginBottom: 14 }} />
      <Text style={styles.contentTitle}>Settings</Text>
    </View>

    <Text style={styles.infoText}>
      Mock settings screen. Implementation would involve state for various preferences.
    </Text>
    <TouchableOpacity
      style={styles.actionButton}
      onPress={() => Alert.alert('Action', 'Mock Setting Saved!')}
    >
      <Text style={styles.actionButtonText}>Save Preferences</Text>
    </TouchableOpacity>
  </View>
);

const ProfileScreen = ({ userActivityCount }) => (
  <View style={styles.contentView}>
    <View style={{ flexDirection: 'row', alignItems: 'center' }}>
      <Icon name="person-outline" size={28} color="#007AFF" style={{ marginRight: 8, marginBottom: 16 }} />
      <Text style={styles.contentTitle}>User Profile</Text>
    </View>
    <Text style={styles.profileText}>User: Anonymous Tracker</Text>
    <Text style={styles.profileText}>
      Total Tracked Actions: {userActivityCount}
    </Text>
    <Text style={styles.infoText}>
      This count tracks how many times the screen time was updated since launch.
    </Text>
  </View>
);

const SensorsScreen = ({
  location,
  accelerometerData,
  gyroscopeData,
  isLocationEnabled,
  isSensorsEnabled,
  screenEvents,
  screenMeta,
  onRefreshScreenEvents,
}) => (
  <View style={styles.contentView}>
    <View style={{ flexDirection: 'row', alignItems: 'center' }}>
      <MaterialIcons name="sensors" size={28} color="#007AFF" style={{ marginRight: 8, marginBottom: 16 }} />

      <Text style={styles.contentTitle}>Sensor Data</Text>
    </View>
    <Text style={styles.infoText}>
      Real-time sensor data for digital phenotyping analysis.
    </Text>

    <Text style={styles.Title}>
      Device Sensors
    </Text>

    {/* Location Section */}
    <View style={styles.sensorSection}>
      <View style={styles.sensorHeader}>
        <Icon name="location-outline" size={24} color="#007AFF" />
        <Text style={styles.sensorTitle}>Location</Text>
        <View style={[styles.statusIndicator, { backgroundColor: isLocationEnabled ? '#32D74B' : '#FF3B30' }]} />
      </View>

      {isLocationEnabled && location ? (
        <View style={styles.sensorData}>
          <Text style={styles.sensorLabel}>Latitude: <Text style={styles.sensorValue}>{location.latitude.toFixed(6)}</Text></Text>
          <Text style={styles.sensorLabel}>Longitude: <Text style={styles.sensorValue}>{location.longitude.toFixed(6)}</Text></Text>
          <Text style={styles.sensorLabel}>Accuracy: <Text style={styles.sensorValue}>{location.accuracy?.toFixed(2)}m</Text></Text>
          <Text style={styles.sensorLabel}>Altitude: <Text style={styles.sensorValue}>{location.altitude?.toFixed(2)}m</Text></Text>
          <Text style={styles.sensorLabel}>Speed: <Text style={styles.sensorValue}>{location.speed?.toFixed(2)}m/s</Text></Text>
        </View>
      ) : (
        <Text style={styles.sensorDisabled}>Location access denied or unavailable</Text>
      )}
    </View>

    {/* Accelerometer Section */}
    <View style={styles.sensorSection}>
      <View style={styles.sensorHeader}>
        <Icon name="phone-portrait-outline" size={24} color="#FF9500" />
        <Text style={styles.sensorTitle}>Accelerometer</Text>
        <View style={[styles.statusIndicator, { backgroundColor: isSensorsEnabled ? '#32D74B' : '#FF3B30' }]} />
      </View>

      {isSensorsEnabled && accelerometerData ? (
        <View style={styles.sensorData}>
          <Text style={styles.sensorLabel}>X-axis: <Text style={styles.sensorValue}>{accelerometerData.x.toFixed(3)}g</Text></Text>
          <Text style={styles.sensorLabel}>Y-axis: <Text style={styles.sensorValue}>{accelerometerData.y.toFixed(3)}g</Text></Text>
          <Text style={styles.sensorLabel}>Z-axis: <Text style={styles.sensorValue}>{accelerometerData.z.toFixed(3)}g</Text></Text>
          <Text style={styles.sensorLabel}>Magnitude: <Text style={styles.sensorValue}>{Math.sqrt(accelerometerData.x ** 2 + accelerometerData.y ** 2 + accelerometerData.z ** 2).toFixed(3)}g</Text></Text>
        </View>
      ) : (
        <Text style={styles.sensorDisabled}>Accelerometer not available</Text>
      )}
    </View>

    {/* Gyroscope Section */}
    <View style={styles.sensorSection}>
      <View style={styles.sensorHeader}>
        <MaterialIcons name="screen-rotation" size={24} color="#32D74B" />
        <Text style={styles.sensorTitle}>Gyroscope</Text>
        <View style={[styles.statusIndicator, { backgroundColor: isSensorsEnabled ? '#32D74B' : '#FF3B30' }]} />
      </View>

      {isSensorsEnabled && gyroscopeData ? (
        <View style={styles.sensorData}>
          <Text style={styles.sensorLabel}>X-axis: <Text style={styles.sensorValue}>{gyroscopeData.x.toFixed(3)} rad/s</Text></Text>
          <Text style={styles.sensorLabel}>Y-axis: <Text style={styles.sensorValue}>{gyroscopeData.y.toFixed(3)} rad/s</Text></Text>
          <Text style={styles.sensorLabel}>Z-axis: <Text style={styles.sensorValue}>{gyroscopeData.z.toFixed(3)} rad/s</Text></Text>
          <Text style={styles.sensorLabel}>Magnitude: <Text style={styles.sensorValue}>{Math.sqrt(gyroscopeData.x ** 2 + gyroscopeData.y ** 2 + gyroscopeData.z ** 2).toFixed(3)} rad/s</Text></Text>
        </View>
      ) : (
        <Text style={styles.sensorDisabled}>Gyroscope not available</Text>
      )}
    </View>

    <Text style={styles.Title}>
      Software Sensors
    </Text>

    {/* Screen Power Events Section */}
    <View style={styles.SoftwereSensorSection}>
      <View style={styles.sensorHeader}>
        <Icon name="power-outline" size={24} color="#a700adff" />
        <Text style={styles.sensorTitle}>Screen Events</Text>
        <View style={[styles.statusIndicator, { backgroundColor: (screenEvents && screenEvents.length) ? '#32D74B' : '#FFCC00' }]} />
      </View>
      {screenEvents && screenEvents.length > 0 ? (
        <View style={styles.sensorData}>
          <Text style={styles.sensorLabel}>Total events: <Text style={styles.sensorValue}>{screenEvents.length}</Text></Text>
          {screenEvents.slice(-10).map((evt, idx) => (
            <Text key={idx} style={styles.sensorLabel}>
              {new Date(evt.ts).toLocaleString()} — <Text style={styles.sensorValue}>{evt.event}</Text>
            </Text>
          ))}
          <TouchableOpacity style={[styles.actionButton, { marginTop: 12, backgroundColor: '#a700adff' }]} onPress={onRefreshScreenEvents}>
            <Text style={styles.actionButtonText}>Refresh Now</Text>
          </TouchableOpacity>
        </View>
      ) : (
        <View>
          <Text style={styles.sensorDisabled}>No events yet. Lock/unlock the device to generate events.</Text>
          <Text style={[styles.sensorLabel, { marginTop: 8 }]}>Doc dir: <Text style={styles.sensorValue}>{FileSystem.documentDirectory}</Text></Text>
          {screenMeta ? (
            <>
              <Text style={styles.sensorLabel}>
                Path tried: <Text style={styles.sensorValue}>{screenMeta.targetPath || 'n/a'}</Text> {`exists: ${screenMeta.exists ? 'yes' : 'no'}`}
              </Text>
              <Text style={styles.sensorLabel}>
                Last read: <Text style={styles.sensorValue}>{screenMeta.lastRead ? new Date(screenMeta.lastRead).toLocaleTimeString() : 'n/a'}</Text>
                {screenMeta.error ? `  err: ${screenMeta.error}` : ''}
              </Text>
            </>
          ) : null}
          <TouchableOpacity style={[styles.actionButton, { marginTop: 12, backgroundColor: '#a700adff' }]} onPress={onRefreshScreenEvents}>
            <Text style={styles.actionButtonText}>Refresh Now</Text>
          </TouchableOpacity>
        </View>
      )}
    </View>
  </View>
);

// --- Main App Component ---

const ActivityTrackerApp = () => {
  return (
    <SafeAreaProvider>
      <SafeAreaView style={{ flex: 1 }}>
        <DemoScreen />
      </SafeAreaView>
    </SafeAreaProvider>
  );

  const [activeTab, setActiveTab] = useState('Home');
  const [totalScreenTime, setTotalScreenTime] = useState(0);
  const [activityCount, setActivityCount] = useState(0);
  const { events: screenEvents, meta: screenMeta, refreshNow: refreshScreenEvents } = useScreenEventsEx2();

  // Sensor states
  const [location, setLocation] = useState(null);
  const [accelerometerData, setAccelerometerData] = useState(null);
  const [gyroscopeData, setGyroscopeData] = useState(null);
  const [isLocationEnabled, setIsLocationEnabled] = useState(false);
  const [isSensorsEnabled, setIsSensorsEnabled] = useState(false);

  // Request location permissions and start location tracking
  useEffect(() => {
    const requestLocationPermission = async () => {
      try {
        const { status } = await Location.requestForegroundPermissionsAsync();
        if (status === 'granted') {
          setIsLocationEnabled(true);
          // Start location tracking
          const locationSubscription = await Location.watchPositionAsync(
            {
              accuracy: Location.Accuracy.High,
              timeInterval: 1000, // Update every second
              distanceInterval: 1, // Update every meter
            },
            (newLocation) => {
              setLocation(newLocation.coords);
            }
          );

          // Store subscription for cleanup
          return locationSubscription;
        } else {
          Alert.alert('Permission Denied', 'Location permission is required for digital phenotyping features.');
        }
      } catch (error) {
        console.error('Location permission error:', error);
        Alert.alert('Error', 'Failed to request location permission.');
      }
    };

    requestLocationPermission();
  }, []);

  // Start accelerometer and gyroscope sensors
  useEffect(() => {
    const startSensors = async () => {
      try {
        // Check if sensors are available
        const accelerometerAvailable = await Accelerometer.isAvailableAsync();
        const gyroscopeAvailable = await Gyroscope.isAvailableAsync();

        if (accelerometerAvailable && gyroscopeAvailable) {
          setIsSensorsEnabled(true);

          // Set update intervals (60Hz for smooth data)
          Accelerometer.setUpdateInterval(16); // ~60fps
          Gyroscope.setUpdateInterval(16); // ~60fps

          // Subscribe to accelerometer updates
          const accelerometerSubscription = Accelerometer.addListener((data) => {
            setAccelerometerData(data);
          });

          // Subscribe to gyroscope updates
          const gyroscopeSubscription = Gyroscope.addListener((data) => {
            setGyroscopeData(data);
          });

          // Store subscriptions for cleanup
          return { accelerometerSubscription, gyroscopeSubscription };
        } else {
          Alert.alert('Sensors Unavailable', 'Accelerometer or gyroscope sensors are not available on this device.');
        }
      } catch (error) {
        console.error('Sensor initialization error:', error);
        Alert.alert('Error', 'Failed to initialize sensors.');
      }
    };

    startSensors();
  }, []);

  // App usage (Android)
  const [appsUsage, setAppsUsage] = useState([]);
  const [hasUsageAccess, setHasUsageAccess] = useState(true);

  const fetchUsage = async () => {
    try {
      if (Platform.OS !== 'android' || !AppUsageNative) return;
      const allowed = await AppUsageNative.hasUsageAccess();
      setHasUsageAccess(!!allowed);
      if (!allowed) return;
      const res = await AppUsageNative.getUsageStatsForToday();
      const apps = (res?.apps || []);
      setAppsUsage(apps);
      const totalMs = res?.totalMs || 0;
      setTotalScreenTime(Math.round(totalMs / 60000));
    } catch (e) {
      // ignore
    }
  };

  const openUsageAccess = async () => { try { AppUsageNative?.openUsageAccessSettings(); } catch (e) { } };

  useEffect(() => {
    fetchUsage();
    const id = setInterval(fetchUsage, 60 * 1000);
    return () => clearInterval(id);
  }, []);

  // Simple rendering logic based on activeTab
  const renderContent = () => {
    switch (activeTab) {
      case 'Home':
        return <HomeScreen totalScreenTime={totalScreenTime} appsUsage={appsUsage} hasUsageAccess={hasUsageAccess} onRequestAccess={openUsageAccess} onRefreshUsage={fetchUsage} />;
      case 'Settings':
        return <SettingsScreen />;
      case 'Profile':
        return <ProfileScreen userActivityCount={activityCount} />;
      case 'Sensors':
        return <SensorsScreen
          location={location}
          accelerometerData={accelerometerData}
          gyroscopeData={gyroscopeData}
          isLocationEnabled={isLocationEnabled}
          isSensorsEnabled={isSensorsEnabled}
          screenEvents={screenEvents}
          screenMeta={screenMeta}
          onRefreshScreenEvents={refreshScreenEvents}
        />;
      default:
        return <HomeScreen totalScreenTime={totalScreenTime} appsUsage={appsUsage} hasUsageAccess={hasUsageAccess} onRequestAccess={openUsageAccess} onRefreshUsage={fetchUsage} />;
    }
  };

  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.mainLayout}>
        {/* Vertical Side Bar (Mocked Navigation) */}
        <View style={styles.sidebar}>
          <Text style={styles.logo}>DP demo</Text>
          {['Home', 'Sensors', 'Settings', 'Profile'].map(tab => (
            <TouchableOpacity
              key={tab}
              style={[
                styles.sidebarItem,
                activeTab === tab && styles.sidebarItemActive,
              ]}
              onPress={() => setActiveTab(tab)}
            >
              <Icon
                name={
                  tab === 'Home'
                    ? 'home-outline'
                    : tab === 'Sensors'
                      ? 'analytics-outline'
                      : tab === 'Settings'
                        ? 'settings-outline'
                        : 'person-outline'
                }
                size={24}
                color={activeTab === tab ? '#FFF' : '#333'}
              />
              <Text
                style={[
                  styles.sidebarText,
                  activeTab === tab && styles.sidebarTextActive,
                ]}
              >
                {tab}
              </Text>
            </TouchableOpacity>
          ))}
        </View>

        {/* Main Content Area */}
        <ScrollView style={styles.contentArea}>
          {renderContent()}
        </ScrollView>
      </View>
    </SafeAreaView>
  );

};

// --- Stylesheet ---
const styles = StyleSheet.create({
  Title: {
    fontSize: 20,
    marginVertical: 10,
    fontWeight: "bold"
  },
  container: {
    flex: 1,
    backgroundColor: '#f0f4f8',
    width: '100%',
  },
  mainLayout: {
    flex: 1,
    flexDirection: 'row',
  },
  sidebar: {
    width: 120,
    backgroundColor: '#fff',
    paddingTop: 20,
    borderRightWidth: 1,
    borderRightColor: '#eee',
    alignItems: 'center',
  },
  logo: {
    fontSize: 20,
    fontWeight: 'bold',
    color: '#007AFF',
    marginBottom: 30,
    marginTop: 12,
  },
  sidebarItem: {
    paddingVertical: 15,
    paddingHorizontal: 10,
    marginBottom: 10,
    width: '100%',
    alignItems: 'center',
    flexDirection: 'column',
  },
  sidebarItemActive: {
    backgroundColor: '#007AFF',
    borderRadius: 8,
  },
  sidebarText: {
    fontSize: 14,
    color: '#333',
    marginTop: 5,
  },
  sidebarTextActive: {
    color: 'white',
    fontWeight: 'bold',
  },
  contentArea: {
    flex: 1,
    padding: 20,
    paddingTop: 40,
  },
  contentView: {
    flex: 1,
  },
  contentTitle: {
    fontSize: 24,
    fontWeight: 'bold',
    marginBottom: 20,
    color: '#333',
  },
  timeCard: {
    backgroundColor: '#e0f7fa',
    padding: 20,
    borderRadius: 10,
    alignItems: 'center',
    marginBottom: 30,
    borderLeftWidth: 5,
    borderLeftColor: '#00BCD4',
  },
  timeValue: {
    fontSize: 40,
    fontWeight: 'bold',
    color: '#007AFF',
    marginVertical: 5,
  },
  timeLabel: {
    fontSize: 16,
    color: '#666',
  },
  sectionHeader: {
    fontSize: 18,
    fontWeight: 'bold',
    color: '#333',
    marginTop: 10,
    marginBottom: 15,
  },
  appItem: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 10,
    borderBottomWidth: 1,
    borderBottomColor: '#eee',
    position: 'relative',
  },
  appName: {
    flex: 1,
    fontSize: 16,
    color: '#333',
    marginLeft: 10,
  },
  appTime: {
    fontSize: 16,
    fontWeight: 'bold',
    color: '#007AFF',
    zIndex: 2,
    paddingHorizontal: 5,
  },
  timeBar: {
    position: 'absolute',
    left: 40,
    right: 0,
    height: '100%',
    backgroundColor: '#d0e0ff',
    opacity: 0.5,
    borderRadius: 5,
    zIndex: 1,
  },
  infoText: {
    fontSize: 16,
    color: '#666',
    lineHeight: 24,
    marginVertical: 10,
    padding: 10,
    backgroundColor: '#fff',
    borderRadius: 8,
    borderLeftWidth: 4,
    borderLeftColor: '#FF9500',
  },
  profileText: {
    fontSize: 18,
    color: '#333',
    marginBottom: 15,
    fontWeight: '500',
  },
  actionButton: {
    marginTop: 20,
    backgroundColor: '#32D74B',
    padding: 12,
    borderRadius: 8,
    alignItems: 'center',
  },
  actionButtonText: {
    color: 'white',
    fontSize: 16,
    fontWeight: '600',
  },
  // Sensor-specific styles
  sensorSection: {
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 16,
    marginBottom: 36,
    borderLeftWidth: 4,
    borderLeftColor: '#007AFF',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 3,
  },
  SoftwereSensorSection: {
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 16,
    marginBottom: 56,
    borderLeftWidth: 4,
    borderLeftColor: '#a700adff',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 3,
  },
  sensorHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 12,
  },
  sensorTitle: {
    fontSize: 18,
    fontWeight: 'bold',
    color: '#333',
    marginLeft: 8,
    flex: 1,
  },
  statusIndicator: {
    width: 12,
    height: 12,
    borderRadius: 6,
  },
  sensorData: {
    backgroundColor: '#f8f9fa',
    borderRadius: 8,
    padding: 12,
  },
  sensorLabel: {
    fontSize: 14,
    color: '#666',
    marginBottom: 4,
  },
  sensorValue: {
    fontSize: 14,
    fontWeight: 'bold',
    color: '#007AFF',
  },
  sensorDisabled: {
    fontSize: 14,
    color: '#FF3B30',
    fontStyle: 'italic',
    textAlign: 'center',
    padding: 12,
    backgroundColor: '#fff5f5',
    borderRadius: 8,
    marginVertical: 5
  },
});

// Screen power events reader (requires expo-file-system)
const LOG_FILE = FileSystem.documentDirectory + 'screen-events.log';

function useScreenEvents() {
  const [events, setEvents] = useState([]);
  useEffect(() => {
    let mounted = true;
    const readLog = async () => {
      try {
        // try the standard documentDirectory path
        let targetPath = LOG_FILE;
        let info = await FileSystem.getInfoAsync(targetPath);

        // Fallback: absolute internal files dir (helps if documentDirectory is scoped)
        if (!info.exists) {
          const pkg = (Constants?.expoConfig && Constants.expoConfig.android && Constants.expoConfig.android.package) || 'com.dp.demo';
          const abs = `file:///data/user/0/${pkg}/files/screen-events.log`;
          info = await FileSystem.getInfoAsync(abs);
          if (info.exists) targetPath = abs;
        }

        if (!info.exists) return;
        let content = await FileSystem.readAsStringAsync(targetPath);
        // Handle files that used literal "\n" instead of newlines
        if (content.includes('\\n')) {
          content = content.replace(/\\n/g, '\n');
        }
        const lines = content.split('\n').filter(Boolean);
        const parsed = lines.map((l) => {
          try { return JSON.parse(l); } catch { return null; }
        }).filter(Boolean);
        if (mounted) setEvents(parsed);
      } catch (e) {
        // ignore errors
      }
    };
    readLog();
    const id = setInterval(readLog, 5000);
    return () => { mounted = false; clearInterval(id); };
  }, []);
  return events;
}

// Extended hook with diagnostics + manual refresh
function useScreenEventsEx() {
  const [meta, setMeta] = useState({ targetPath: LOG_FILE, exists: false, lastRead: null, error: null });
  const [events, setEvents] = useState([]);

  const readLog = React.useCallback(async () => {
    try {
      let targetPath = LOG_FILE;
      let info = await FileSystem.getInfoAsync(targetPath);
      if (!info.exists) {
        const pkg = (Constants?.expoConfig && Constants.expoConfig.android && Constants.expoConfig.android.package) || 'com.dp.demo';
        const abs = `file:///data/user/0/${pkg}/files/screen-events.log`;
        info = await FileSystem.getInfoAsync(abs);
        if (info.exists) targetPath = abs;
      }

      if (!info.exists) {
        setMeta({ targetPath, exists: false, lastRead: Date.now(), error: null });
        setEvents([]);
        return;
      }

      let content = await FileSystem.readAsStringAsync(targetPath);
      if (content.includes('\\n')) content = content.replace(/\\n/g, '\n');
      const lines = content.split('\n').filter(Boolean);
      const parsed = lines.map(l => { try { return JSON.parse(l); } catch { return null; } }).filter(Boolean);
      setEvents(parsed);
      setMeta({ targetPath, exists: true, lastRead: Date.now(), error: null });
    } catch (e) {
      setMeta(m => ({ ...m, lastRead: Date.now(), error: String(e?.message || e) }));
    }
  }, []);

  useEffect(() => {
    const id = setInterval(() => { readLog(); }, 2000);
    readLog();
    return () => clearInterval(id);
  }, [readLog]);

  return { events, meta, refreshNow: readLog };
}

export default ActivityTrackerApp;

// Safer reader that tries multiple paths and tolerates undefined documentDirectory
function useScreenEventsEx2() {
  const [meta, setMeta] = useState({ targetPath: LOG_FILE, exists: false, lastRead: null, error: null });
  const [events, setEvents] = useState([]);

  const readLog = React.useCallback(async () => {
    const pkg = (Constants?.expoConfig && Constants.expoConfig.android && Constants.expoConfig.android.package) || 'com.dp.demo';
    const candidates = [];
    if (FileSystem.documentDirectory) {
      candidates.push(FileSystem.documentDirectory + 'screen-events.log');
    }
    candidates.push(`file:///data/user/0/${pkg}/files/screen-events.log`);

    let lastErr = null;
    for (const p of candidates) {
      try {
        const info = await FileSystem.getInfoAsync(p);
        if (info && info.exists) {
          let content = await FileSystem.readAsStringAsync(p);
          if (content && content.includes('\\n')) content = content.replace(/\\n/g, '\n');
          const lines = (content || '').split('\n').filter(Boolean);
          const parsed = lines.map((l) => { try { return JSON.parse(l); } catch { return null; } }).filter(Boolean);
          setEvents(parsed);
          setMeta({ targetPath: p, exists: true, lastRead: Date.now(), error: null });
          return;
        }
      } catch (e) {
        lastErr = String(e?.message || e);
      }
    }

    setEvents([]);
    setMeta({ targetPath: candidates[candidates.length - 1], exists: false, lastRead: Date.now(), error: lastErr });
  }, []);

  useEffect(() => {
    const id = setInterval(() => { readLog(); }, 2000);
    readLog();
    return () => clearInterval(id);
  }, [readLog]);

  return { events, meta, refreshNow: readLog };
}