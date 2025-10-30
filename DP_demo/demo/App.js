import React, { useState, useEffect } from 'react';
import {
  View,
  Text,
  TouchableOpacity,
  ScrollView,
  SafeAreaView,
  StyleSheet,
  Alert,
} from 'react-native';
import { Ionicons as Icon } from '@expo/vector-icons';
import * as Location from 'expo-location';
import { Accelerometer, Gyroscope } from 'expo-sensors';

// Mock Data for App Usage
const MOCK_APP_USAGE = [
  { name: 'Browser', time: 125, icon: 'globe-outline' },
  { name: 'Social', time: 90, icon: 'chatbubbles-outline' },
  { name: 'Media Player', time: 55, icon: 'play-circle-outline' },
  { name: 'Work', time: 30, icon: 'briefcase-outline' },
];

// --- Sub-Components for Navigation Views ---

const HomeScreen = ({ totalScreenTime }) => (
  <View style={styles.contentView}>
    <Text style={styles.contentTitle}>📊 Activity Overview</Text>
    <View style={styles.timeCard}>
      <Icon name="timer-outline" size={30} color="#007AFF" />
      <Text style={styles.timeValue}>{totalScreenTime} min</Text>
      <Text style={styles.timeLabel}>Total Screen Time Today (Mock)</Text>
    </View>

    <Text style={styles.sectionHeader}>Top App Usage (Mock)</Text>
    {MOCK_APP_USAGE.map((app, index) => (
      <View key={index} style={styles.appItem}>
        <Icon name={app.icon} size={20} color="#333" style={{ width: 30 }} />
        <Text style={styles.appName}>{app.name}</Text>
        <Text style={styles.appTime}>{app.time} min</Text>
        <View style={[styles.timeBar, { width: `${(app.time / 150) * 100}%` }]} />
      </View>
    ))}
  </View>
);

const SettingsScreen = () => (
  <View style={styles.contentView}>
    <Text style={styles.contentTitle}>⚙️ Settings</Text>
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
    <Text style={styles.contentTitle}>👤 User Profile</Text>
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
  isSensorsEnabled 
}) => (
  <View style={styles.contentView}>
    <Text style={styles.contentTitle}>📡 Sensor Data</Text>
    
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
          <Text style={styles.sensorLabel}>Magnitude: <Text style={styles.sensorValue}>{Math.sqrt(accelerometerData.x**2 + accelerometerData.y**2 + accelerometerData.z**2).toFixed(3)}g</Text></Text>
        </View>
      ) : (
        <Text style={styles.sensorDisabled}>Accelerometer not available</Text>
      )}
    </View>

    {/* Gyroscope Section */}
    <View style={styles.sensorSection}>
      <View style={styles.sensorHeader}>
        <Icon name="refresh-outline" size={24} color="#32D74B" />
        <Text style={styles.sensorTitle}>Gyroscope</Text>
        <View style={[styles.statusIndicator, { backgroundColor: isSensorsEnabled ? '#32D74B' : '#FF3B30' }]} />
      </View>
      
      {isSensorsEnabled && gyroscopeData ? (
        <View style={styles.sensorData}>
          <Text style={styles.sensorLabel}>X-axis: <Text style={styles.sensorValue}>{gyroscopeData.x.toFixed(3)} rad/s</Text></Text>
          <Text style={styles.sensorLabel}>Y-axis: <Text style={styles.sensorValue}>{gyroscopeData.y.toFixed(3)} rad/s</Text></Text>
          <Text style={styles.sensorLabel}>Z-axis: <Text style={styles.sensorValue}>{gyroscopeData.z.toFixed(3)} rad/s</Text></Text>
          <Text style={styles.sensorLabel}>Magnitude: <Text style={styles.sensorValue}>{Math.sqrt(gyroscopeData.x**2 + gyroscopeData.y**2 + gyroscopeData.z**2).toFixed(3)} rad/s</Text></Text>
        </View>
      ) : (
        <Text style={styles.sensorDisabled}>Gyroscope not available</Text>
      )}
    </View>

    <Text style={styles.infoText}>
      Real-time sensor data for digital phenotyping analysis. Location data helps track mobility patterns, 
      while accelerometer and gyroscope data provide insights into physical activity and device orientation.
    </Text>
  </View>
);

// --- Main App Component ---

const ActivityTrackerApp = () => {
  const [activeTab, setActiveTab] = useState('Home');
  const [totalScreenTime, setTotalScreenTime] = useState(0);
  const [activityCount, setActivityCount] = useState(0);
  
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

  // Mock screen time update using useEffect (similar to the original timer)
  useEffect(() => {
    const interval = setInterval(() => {
      // Increment mock screen time by a random amount
      const increment = Math.floor(Math.random() * 5) + 1; 
      setTotalScreenTime(prev => prev + increment);
      setActivityCount(prev => prev + 1);
    }, 5000); // Updates every 5 seconds

    return () => clearInterval(interval);
  }, []);

  // Simple rendering logic based on activeTab
  const renderContent = () => {
    switch (activeTab) {
      case 'Home':
        return <HomeScreen totalScreenTime={totalScreenTime} />;
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
        />;
      default:
        return <HomeScreen totalScreenTime={totalScreenTime} />;
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
    paddingTop: 10,
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
    marginTop: 10,
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
    marginBottom: 16,
    borderLeftWidth: 4,
    borderLeftColor: '#007AFF',
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
  },
});

export default ActivityTrackerApp;
