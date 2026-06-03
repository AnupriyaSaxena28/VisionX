import React, { useEffect, useRef, useState, useCallback } from 'react';
import { View, StyleSheet, Text, ActivityIndicator } from 'react-native';
import { Camera, useCameraDevices } from 'react-native-vision-camera';
import Svg, { Ellipse } from 'react-native-svg';
import { useNavigation } from '@react-navigation/native';
import Animated, {
  useSharedValue,
  useAnimatedProps,
  withTiming,
  withRepeat,
  withSequence,
  FadeIn,
  FadeOut,
  interpolateColor
} from 'react-native-reanimated';
import FaceAuthService from '../services/FaceAuthService';

const AnimatedEllipse = Animated.createAnimatedComponent(Ellipse);

type LivenessState = 'no_face' | 'face_detected' | 'challenge' | 'processing' | 'success' | 'failed';

export const CameraScreen = () => {
  const devices = useCameraDevices();
  const device = devices.find((d) => d.position === 'front');
  const camera = useRef<Camera>(null);
  const navigation = useNavigation<any>();

  const [hasPermission, setHasPermission] = useState(false);
  const [livenessState, setLivenessState] = useState<LivenessState>('no_face');
  const [instruction, setInstruction] = useState('Position your face in the oval');

  // Animation values
  const strokeColorProgress = useSharedValue(0); // 0: gray, 1: green, 2: amber

  useEffect(() => {
    (async () => {
      const status = await Camera.requestCameraPermission();
      setHasPermission(status === 'granted');
    })();
  }, []);

  // Animate color based on state
  useEffect(() => {
    switch (livenessState) {
      case 'no_face':
        strokeColorProgress.value = withTiming(0, { duration: 300 });
        setInstruction('Position your face in the oval');
        break;
      case 'face_detected':
        strokeColorProgress.value = withTiming(1, { duration: 300 });
        setInstruction('Please blink');
        break;
      case 'challenge':
        strokeColorProgress.value = withTiming(2, { duration: 300 });
        setInstruction('Now turn your head slightly');
        break;
      case 'processing':
        strokeColorProgress.value = withTiming(1, { duration: 300 });
        setInstruction('Verifying...');
        break;
      case 'success':
        strokeColorProgress.value = withTiming(1, { duration: 300 });
        setInstruction('Authenticated Successfully');
        break;
      case 'failed':
        strokeColorProgress.value = withTiming(0, { duration: 300 });
        setInstruction('Authentication Failed. Try again.');
        break;
    }
  }, [livenessState, strokeColorProgress]);

  const animatedProps = useAnimatedProps(() => {
    const stroke = interpolateColor(
      strokeColorProgress.value,
      [0, 1, 2],
      ['gray', 'green', '#FFC107'] // #FFC107 is amber
    );
    return {
      stroke,
    };
  });

  // Mock frame processing logic - periodically take snapshot and authenticate
  // In a real scenario with high performance needs, this would be a Frame Processor
  // running a native plugin, but to get JS base64 we use takePhoto or similar.
  useEffect(() => {
    if (!hasPermission || !device) return;
    
    let isProcessing = false;
    let interval: NodeJS.Timeout;

    const processFrame = async () => {
      if (isProcessing || !camera.current) return;
      
      // Stop processing if we reached a terminal state
      if (livenessState === 'success' || livenessState === 'processing') return;

      isProcessing = true;
      try {
        // We use takePhoto with low quality to simulate frame extraction
        const photo = await camera.current.takePhoto({
          qualityPrioritization: 'speed',
          enableShutterSound: false,
        });

        // Convert file path to base64 or pass file path to native module
        // We assume FaceAuthService handles the file path directly or we read it
        const result = await FaceAuthService.authenticate(photo.path);
        
        // Update states based on result
        if (result.matched && result.livenessPass) {
          setLivenessState('success');
          // Navigate to result screen after a brief delay
          setTimeout(() => {
            navigation.navigate('Result', { 
              success: true, 
              name: 'Aarav Patel', 
              timestamp: new Date().toISOString() 
            });
            // Reset state for when we come back
            setLivenessState('no_face');
          }, 1000);
        } else if (result.state === 'blink') {
          setLivenessState('face_detected');
        } else if (result.state === 'turn') {
          setLivenessState('challenge');
        } else {
          setLivenessState('no_face');
        }

      } catch (error) {
        console.warn('Frame processing error:', error);
      } finally {
        isProcessing = false;
      }
    };

    // Start a processing loop every 1 second
    interval = setInterval(processFrame, 1000);

    return () => {
      clearInterval(interval);
    };
  }, [hasPermission, device, livenessState]);

  if (!hasPermission) {
    return (
      <View style={styles.container}>
        <Text style={styles.text}>No Camera Permission</Text>
      </View>
    );
  }

  if (device == null) {
    return (
      <View style={styles.container}>
        <Text style={styles.text}>No Camera Found</Text>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <Camera
        ref={camera}
        style={StyleSheet.absoluteFill}
        device={device}
        isActive={true}
        photo={true}
      />
      
      {/* SVG Overlay */}
      <View style={styles.overlay} pointerEvents="none">
        <Svg height="100%" width="100%">
          <AnimatedEllipse
            cx="50%"
            cy="50%"
            rx="40%"
            ry="60%"
            strokeWidth="5"
            fill="transparent"
            animatedProps={animatedProps}
          />
        </Svg>
      </View>

      {/* Dynamic Instruction Text with Reanimated Fade Transitions */}
      <View style={styles.instructionContainer}>
        <Animated.Text
          key={instruction}
          entering={FadeIn.duration(400)}
          exiting={FadeOut.duration(400)}
          style={styles.instructionText}
        >
          {instruction}
        </Animated.Text>
        
        {livenessState === 'processing' && (
          <ActivityIndicator size="small" color="#fff" style={styles.spinner} />
        )}
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#000',
    justifyContent: 'center',
    alignItems: 'center',
  },
  text: {
    color: '#fff',
    fontSize: 18,
  },
  overlay: {
    ...StyleSheet.absoluteFillObject,
    justifyContent: 'center',
    alignItems: 'center',
  },
  instructionContainer: {
    position: 'absolute',
    bottom: 100,
    alignItems: 'center',
    flexDirection: 'row',
    backgroundColor: 'rgba(0,0,0,0.6)',
    paddingHorizontal: 20,
    paddingVertical: 12,
    borderRadius: 25,
  },
  instructionText: {
    color: '#fff',
    fontSize: 18,
    fontWeight: '600',
    textAlign: 'center',
  },
  spinner: {
    marginLeft: 10,
  },
});

export default CameraScreen;
