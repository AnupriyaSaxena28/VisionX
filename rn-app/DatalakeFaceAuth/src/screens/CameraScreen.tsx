import React, { useEffect, useRef, useState } from 'react';
import { View, StyleSheet, Text, ActivityIndicator } from 'react-native';
import { Camera, useCameraDevices } from 'react-native-vision-camera';
import Svg, { Ellipse } from 'react-native-svg';
import { useNavigation } from '@react-navigation/native';
import Animated, {
  useSharedValue,
  useAnimatedProps,
  withTiming,
  FadeIn,
  FadeOut,
  interpolateColor
} from 'react-native-reanimated';
import { authenticatePhoto } from '../services/FaceAuthService';

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

  const strokeColorProgress = useSharedValue(0);

  useEffect(() => {
    (async () => {
      const status = await Camera.requestCameraPermission();
      setHasPermission(status === 'granted');
    })();
  }, []);

  // Map liveness state → animated oval colour + instruction text
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
        setInstruction('Authenticated Successfully ✓');
        break;
      case 'failed':
        strokeColorProgress.value = withTiming(0, { duration: 300 });
        setInstruction('Authentication Failed. Try again.');
        break;
    }
  }, [livenessState, strokeColorProgress]);

  const animatedProps = useAnimatedProps(() => ({
    stroke: interpolateColor(
      strokeColorProgress.value,
      [0, 1, 2],
      ['#555', '#4CAF50', '#FFC107']
    ),
  }));

  // ── Frame processing loop ─────────────────────────────────────────────────
  // Calls the real native module via authenticateFromPath (no base64 conversion).
  // The native module runs BlazeFace + EAR liveness + MobileFaceNet in one call.
  useEffect(() => {
    if (!hasPermission || !device) return;
    if (livenessState === 'success' || livenessState === 'processing') return;

    let isProcessing = false;

    const processFrame = async () => {
      if (isProcessing || !camera.current) return;
      isProcessing = true;

      try {
        const photo = await camera.current.takePhoto({
          qualityPrioritization: 'speed',
          enableShutterSound: false,
        });

        // ── M3 integration: call authenticateFromPath (not base64) ──────────
        // photo.path is the absolute file path returned by VisionCamera.
        // FaceAuthService.authenticatePhoto() → FaceAuthModule.authenticateFromPath()
        // → native pipeline: BlazeFace → FaceMesh EAR → MobileFaceNet → cosine sim
        const result = await authenticatePhoto(photo.path);

        if (result.matched && result.livenessPass) {
          setLivenessState('success');
          setTimeout(() => {
            navigation.navigate('Result', {
              success: true,
              name: result.name,
              score: result.score,
              timestamp: new Date().toISOString(),
            });
            setLivenessState('no_face');
          }, 1000);
        } else if (!result.livenessPass && result.score < 0.3) {
          // No face detected at all
          setLivenessState('no_face');
        } else if (!result.livenessPass) {
          // Face detected but liveness not yet passed → drive the challenge UI
          // The native LivenessChallengeManager drives blink→turn internally.
          // We map the score proxy to the UX states:
          setLivenessState(prev =>
            prev === 'no_face' || prev === 'failed' ? 'face_detected' :
            prev === 'face_detected' ? 'challenge' : prev
          );
        } else {
          // Liveness pass but score below threshold → not recognized
          setLivenessState('face_detected');
        }
      } catch (err) {
        console.warn('[CameraScreen] Frame error:', err);
      } finally {
        isProcessing = false;
      }
    };

    const interval = setInterval(processFrame, 1200);
    return () => clearInterval(interval);
  }, [hasPermission, device, livenessState]);

  if (!hasPermission) {
    return (
      <View style={styles.container}>
        <Text style={styles.text}>Camera permission required</Text>
      </View>
    );
  }

  if (!device) {
    return (
      <View style={styles.container}>
        <Text style={styles.text}>No front camera found</Text>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <Camera
        ref={camera}
        style={StyleSheet.absoluteFill}
        device={device}
        isActive={livenessState !== 'processing' && livenessState !== 'success'}
        photo={true}
      />

      {/* Animated face oval overlay */}
      <View style={styles.overlay} pointerEvents="none">
        <Svg height="100%" width="100%">
          <AnimatedEllipse
            cx="50%"
            cy="45%"
            rx="38%"
            ry="48%"
            strokeWidth="4"
            fill="transparent"
            animatedProps={animatedProps}
          />
        </Svg>
      </View>

      {/* Instruction banner */}
      <View style={styles.instructionContainer}>
        <Animated.Text
          key={instruction}
          entering={FadeIn.duration(300)}
          exiting={FadeOut.duration(300)}
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
  text: { color: '#fff', fontSize: 18 },
  overlay: {
    ...StyleSheet.absoluteFill as any,
    justifyContent: 'center',
    alignItems: 'center',
  },
  instructionContainer: {
    position: 'absolute',
    bottom: 100,
    alignItems: 'center',
    flexDirection: 'row',
    backgroundColor: 'rgba(0,0,0,0.65)',
    paddingHorizontal: 24,
    paddingVertical: 14,
    borderRadius: 28,
  },
  instructionText: {
    color: '#fff',
    fontSize: 17,
    fontWeight: '600',
    textAlign: 'center',
  },
  spinner: { marginLeft: 10 },
});

export default CameraScreen;
