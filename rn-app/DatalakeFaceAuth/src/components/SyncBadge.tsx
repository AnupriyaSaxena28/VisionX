import React, { useEffect, useState } from 'react';
import { View, Text, StyleSheet } from 'react-native';
import NetInfo from '@react-native-community/netinfo';
import Animated, { 
  useSharedValue, 
  useAnimatedStyle, 
  withRepeat, 
  withTiming,
  withSequence,
  Easing 
} from 'react-native-reanimated';
import FaceAuthService from '../services/FaceAuthService';

export const SyncBadge = () => {
  const [isConnected, setIsConnected] = useState<boolean | null>(true);
  const [pendingCount, setPendingCount] = useState<number>(0);

  // Animation for the pulsing dot
  const opacity = useSharedValue(1);

  useEffect(() => {
    // Subscribe to network state
    const unsubscribeNetInfo = NetInfo.addEventListener(state => {
      setIsConnected(state.isConnected && state.isInternetReachable !== false);
    });

    // Poll for pending records count
    const fetchPendingCount = async () => {
      const count = await FaceAuthService.getPendingRecordsCount();
      setPendingCount(count);
    };

    fetchPendingCount();
    const interval = setInterval(fetchPendingCount, 5000);

    return () => {
      unsubscribeNetInfo();
      clearInterval(interval);
    };
  }, []);

  useEffect(() => {
    if (isConnected && pendingCount > 0) {
      // Pulse animation
      opacity.value = withRepeat(
        withSequence(
          withTiming(0.3, { duration: 800, easing: Easing.inOut(Easing.ease) }),
          withTiming(1, { duration: 800, easing: Easing.inOut(Easing.ease) })
        ),
        -1, // infinite
        true // reverse
      );
    } else {
      opacity.value = withTiming(1);
    }
  }, [isConnected, pendingCount, opacity]);

  const animatedDotStyle = useAnimatedStyle(() => ({
    opacity: opacity.value,
  }));

  const getBadgeState = () => {
    if (pendingCount === 0) {
      return {
        text: 'All synced',
        dotColor: '#888', // gray
        animate: false,
      };
    }
    
    if (isConnected) {
      return {
        text: 'Online — syncing',
        dotColor: '#4caf50', // green
        animate: true,
      };
    }
    
    return {
      text: `Offline — ${pendingCount} records pending`,
      dotColor: '#FFC107', // amber
      animate: false,
    };
  };

  const badgeState = getBadgeState();

  return (
    <View style={styles.container}>
      <Animated.View 
        style={[
          styles.dot, 
          { backgroundColor: badgeState.dotColor },
          badgeState.animate && animatedDotStyle
        ]} 
      />
      <Text style={styles.text}>{badgeState.text}</Text>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: 'rgba(30, 30, 30, 0.8)',
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 16,
    marginRight: 10,
  },
  dot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    marginRight: 6,
  },
  text: {
    color: '#e0e0e0',
    fontSize: 12,
    fontWeight: '500',
  }
});

export default SyncBadge;
