import React, { useEffect, useRef } from 'react';
import { View, Animated, Easing, StyleSheet } from 'react-native';

export default function NotificationPing() {
  const pulse = useRef(new Animated.Value(0)).current;
  useEffect(() => {
    const loop = Animated.loop(
      Animated.timing(pulse, {
        toValue: 1,
        duration: 1200,
        easing: Easing.out(Easing.quad),
        useNativeDriver: true,
      })
    );
    pulse.setValue(0);
    loop.start();
    return () => loop.stop();
  }, [pulse]);

  const scale = pulse.interpolate({ inputRange: [0, 1], outputRange: [0.6, 1.6] });
  const opacity = pulse.interpolate({ inputRange: [0, 1], outputRange: [0.8, 0] });

  return (
    <View style={styles.pingWrap} pointerEvents="none">
      <Animated.View style={[styles.pingRing, { transform: [{ scale }], opacity }]} />
      <View style={styles.pingDot} />
    </View>
  );
}

const styles = StyleSheet.create({
  pingWrap: {
    position: 'absolute',
    top: -2,
    right: -2,
    width: 18,
    height: 18,
    alignItems: 'center',
    justifyContent: 'center',
  },
  pingRing: {
    position: 'absolute',
    width: 19,
    height: 19,
    borderRadius: 10,
    backgroundColor: 'rgba(255, 58, 48, 0.55)',
  },
  pingDot: {
    width: 11,
    height: 11,
    borderRadius: 8,
    backgroundColor: '#FF3B30',
    borderWidth: 1,
    borderColor: '#fff',
  },
});

