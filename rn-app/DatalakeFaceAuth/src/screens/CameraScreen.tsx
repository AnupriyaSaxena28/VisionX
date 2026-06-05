import React, { useEffect, useRef, useState } from 'react';
import { View, StyleSheet, Text, ActivityIndicator } from 'react-native';
import {
  Camera,
  useCameraDevice,
  useCameraPermission,
  usePhotoOutput,
} from 'react-native-vision-camera';
import Svg, { Ellipse } from 'react-native-svg';
import { useNavigation, useIsFocused } from '@react-navigation/native';
import Animated, {
  useSharedValue,
  useAnimatedProps,
  withTiming,
  FadeIn,
  FadeOut,
  interpolateColor,
} from 'react-native-reanimated';
import { authenticatePhoto, getEnrolledCount } from '../services/FaceAuthService';

const AnimatedEllipse = Animated.createAnimatedComponent(Ellipse);

type LivenessState =
  | 'no_face'
  | 'face_detected'
  | 'challenge'
  | 'processing'
  | 'success'
  | 'failed';

export const CameraScreen = () => {
  const { hasPermission, requestPermission } = useCameraPermission();
  const device = useCameraDevice('front');
  const navigation = useNavigation<any>();
  const isFocused = useIsFocused();
  const photoOutput = usePhotoOutput();

  const [livenessState, setLivenessState] = useState<LivenessState>('no_face');
  const [instruction, setInstruction] = useState(
    'Position your face in the oval',
  );
  const [lastScore, setLastScore] = useState<number>(0);
  const [isCameraReady, setIsCameraReady] = useState(false);
  const [enrolledCount, setEnrolledCount] = useState<number | null>(null);

  // We use a ref for livenessState so the interval closure doesn't get stale
  const stateRef = useRef<LivenessState>('no_face');

  const syncState = (s: LivenessState) => {
    stateRef.current = s;
    setLivenessState(s);
  };

  const strokeColorProgress = useSharedValue(0);

  // ── Permission ────────────────────────────────────────────────────────────
  useEffect(() => {
    if (!hasPermission) {
      requestPermission();
    }
  }, [hasPermission, requestPermission]);

  // ── Fetch enrolled count on mount ─────────────────────────────────────────
  useEffect(() => {
    getEnrolledCount()
      .then(count => {
        setEnrolledCount(count);
        if (count === 0) {
          console.warn('[CameraScreen] ⚠️ No enrolled faces! Verification will never match. Please enroll a face first.');
        }
      })
      .catch(err => console.warn('[CameraScreen] Failed to get enrolled count:', err));
  }, []);

  // ── Oval colour + instruction text ────────────────────────────────────────
  useEffect(() => {
    switch (livenessState) {
      case 'no_face':
        strokeColorProgress.value = withTiming(0, { duration: 300 });
        setInstruction('Position your face in the oval');
        break;
      case 'face_detected':
        strokeColorProgress.value = withTiming(1, { duration: 300 });
        setInstruction('Please blink 👁️');
        break;
      case 'challenge':
        strokeColorProgress.value = withTiming(2, { duration: 300 });
        setInstruction('Now turn your head slightly ↩️');
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
        setInstruction('Not recognized. Move closer.');
        break;
    }
  }, [livenessState, strokeColorProgress]);

  const animatedProps = useAnimatedProps(() => ({
    stroke: interpolateColor(
      strokeColorProgress.value,
      [0, 1, 2],
      ['#555', '#4CAF50', '#FFC107'],
    ),
  }));

  // ── Frame processing loop ─────────────────────────────────────────────────
  useEffect(() => {
    if (!hasPermission || !device || !isCameraReady || !isFocused) return;

    let isProcessing = false;
    let cancelled = false;

    const processFrame = async () => {
      if (isProcessing) return;
      if (
        stateRef.current === 'success' ||
        stateRef.current === 'processing'
      )
        return;

      isProcessing = true;

      try {
        // Guard: don't capture if camera is no longer active
        if (!isFocused || !isCameraReady) {
          return;
        }

        // VisionCamera v5 API: capturePhoto() → save → dispose
        const photo = await photoOutput.capturePhoto(
          { enableShutterSound: false },
          {},
        );
        if (cancelled) {
          photo.dispose();
          return;
        }

        let cleanPath: string;
        try {
          cleanPath = await photo.saveToTemporaryFileAsync();
        } finally {
          photo.dispose();
        }
        if (cancelled) return;

        if (cleanPath.startsWith('file://')) {
          cleanPath = cleanPath.replace('file://', '');
        }

        const result = await authenticatePhoto(cleanPath);
        console.log('[CameraScreen] Frame auth result:', result);
        if (cancelled) return;

        setLastScore(result.score);

        if (result.matched && result.livenessPass) {
          syncState('success');
          setTimeout(() => {
            if (!cancelled) {
              navigation.navigate('Result', {
                success: true,
                name: result.name,
                score: result.score,
                timestamp: new Date().toISOString(),
              });
              syncState('no_face');
            }
          }, 1000);
        } else if (!result.livenessPass && result.score < 0.3) {
          // No face detected at all
          syncState('no_face');
        } else if (!result.livenessPass) {
          // Face detected but liveness not yet passed → drive the challenge UI
          syncState(
            stateRef.current === 'no_face' || stateRef.current === 'failed'
              ? 'face_detected'
              : stateRef.current === 'face_detected'
              ? 'challenge'
              : stateRef.current,
          );
        } else {
          // Liveness pass is TRUE, but score is below 0.6 (matched is false)
          syncState('failed');
        }
      } catch (err) {
        const errStr = String(err);
        // Suppress expected camera lifecycle errors
        if (
          errStr.includes('Not bound to a valid Camera') ||
          errStr.includes('Camera is closed') ||
          errStr.includes('ImageCaptureException') ||
          errStr.includes('session') ||
          errStr.includes('closed')
        ) {
          console.log('[CameraScreen] Camera lifecycle event, skipping frame...');
        } else {
          console.warn('[CameraScreen] Frame error:', err);
        }
      } finally {
        isProcessing = false;
      }
    };

    const interval = setInterval(processFrame, 1200);
    return () => {
      cancelled = true;
      clearInterval(interval);
    };
  }, [hasPermission, device, isCameraReady, isFocused, photoOutput, navigation]);

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

  const cameraActive =
    isFocused && livenessState !== 'processing' && livenessState !== 'success';

  return (
    <View style={styles.container}>
      <Camera
        style={StyleSheet.absoluteFill}
        device={device}
        isActive={cameraActive}
        outputs={[photoOutput]}
        onPreviewStarted={() => setIsCameraReady(true)}
        onPreviewStopped={() => setIsCameraReady(false)}
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

      {/* Debug score display */}
      <View style={styles.debugContainer}>
        <Text style={styles.debugText}>
          Score: {lastScore.toFixed(3)}
        </Text>
        <Text style={[styles.debugText, enrolledCount === 0 && styles.debugWarning]}>
          Enrolled: {enrolledCount !== null ? enrolledCount : '...'}
        </Text>
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
          <ActivityIndicator
            size="small"
            color="#fff"
            style={styles.spinner}
          />
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
    ...(StyleSheet.absoluteFill as any),
    justifyContent: 'center',
    alignItems: 'center',
  },
  debugContainer: {
    position: 'absolute',
    top: 50,
    right: 20,
    backgroundColor: 'rgba(0,0,0,0.5)',
    padding: 8,
    borderRadius: 8,
  },
  debugText: {
    color: '#0f0',
    fontSize: 14,
    fontWeight: 'bold',
  },
  debugWarning: {
    color: '#ff4444',
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
