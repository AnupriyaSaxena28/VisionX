import React, { useEffect } from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { useNavigation, useRoute } from '@react-navigation/native';
import Animated, { FadeIn, FadeOut } from 'react-native-reanimated';

export type ResultScreenParams = {
  success: boolean;
  name?: string;
  timestamp?: string;
  reason?: 'face not found' | 'liveness failed' | 'not recognised';
};

export const ResultScreen = () => {
  const navigation = useNavigation<any>();
  const route = useRoute<any>();
  const params = route.params as ResultScreenParams;

  const { success, name, timestamp, reason } = params || { success: false, reason: 'face not found' };

  useEffect(() => {
    // Auto-dismiss after 3 seconds
    const timer = setTimeout(() => {
      navigation.goBack(); // Back to camera
    }, 3000);

    return () => clearTimeout(timer);
  }, [navigation]);

  return (
    <View style={[styles.container, success ? styles.bgSuccess : styles.bgFailure]}>
      <Animated.View 
        entering={FadeIn.duration(500)} 
        exiting={FadeOut.duration(500)}
        style={styles.card}
      >
        {success ? (
          <>
            <View style={styles.iconContainerSuccess}>
              <Text style={styles.iconText}>✓</Text>
            </View>
            <Text style={styles.title}>Authenticated</Text>
            <Text style={styles.subtitle}>{name}</Text>
            <Text style={styles.timestamp}>{new Date(timestamp || Date.now()).toLocaleString()}</Text>
          </>
        ) : (
          <>
            <View style={styles.iconContainerFailure}>
              <Text style={styles.iconText}>✕</Text>
            </View>
            <Text style={styles.title}>Authentication Failed</Text>
            <Text style={styles.subtitleReason}>{reason}</Text>
          </>
        )}
      </Animated.View>
      <Text style={styles.autoDismissText}>Auto-closing in 3s...</Text>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 20,
  },
  bgSuccess: {
    backgroundColor: '#0a2e0b', // dark green
  },
  bgFailure: {
    backgroundColor: '#3b0909', // dark red
  },
  card: {
    backgroundColor: 'rgba(0, 0, 0, 0.6)',
    padding: 40,
    borderRadius: 20,
    alignItems: 'center',
    width: '100%',
  },
  iconContainerSuccess: {
    width: 100,
    height: 100,
    borderRadius: 50,
    backgroundColor: '#4caf50',
    justifyContent: 'center',
    alignItems: 'center',
    marginBottom: 20,
  },
  iconContainerFailure: {
    width: 100,
    height: 100,
    borderRadius: 50,
    backgroundColor: '#f44336',
    justifyContent: 'center',
    alignItems: 'center',
    marginBottom: 20,
  },
  iconText: {
    fontSize: 50,
    color: '#fff',
    fontWeight: 'bold',
  },
  title: {
    fontSize: 28,
    fontWeight: 'bold',
    color: '#fff',
    marginBottom: 10,
    textAlign: 'center',
  },
  subtitle: {
    fontSize: 22,
    color: '#e0e0e0',
    marginBottom: 8,
  },
  subtitleReason: {
    fontSize: 20,
    color: '#ffcdd2',
    textTransform: 'capitalize',
  },
  timestamp: {
    fontSize: 16,
    color: '#9e9e9e',
  },
  autoDismissText: {
    position: 'absolute',
    bottom: 40,
    color: '#aaa',
    fontSize: 14,
  }
});

export default ResultScreen;
