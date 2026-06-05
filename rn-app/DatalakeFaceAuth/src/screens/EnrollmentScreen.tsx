import React, { useState, useRef, useEffect } from 'react';
import { View, Text, TextInput, StyleSheet, TouchableOpacity, ActivityIndicator, Alert } from 'react-native';
import { Camera, useCameraDevices } from 'react-native-vision-camera';
import { useNavigation } from '@react-navigation/native';
import { enrollFace } from '../services/FaceAuthService';

export const EnrollmentScreen = () => {
  const devices = useCameraDevices();
  const device = devices.find((d) => d.position === 'front');
  const camera = useRef<Camera>(null);
  const navigation = useNavigation<any>();

  const [hasPermission, setHasPermission] = useState(false);
  const [name, setName] = useState('');
  const [isCapturing, setIsCapturing] = useState(false);
  const [isEnrolling, setIsEnrolling] = useState(false);
  const [captureCount, setCaptureCount] = useState(0);
  const [instruction, setInstruction] = useState('Enter your name and press Capture');

  useEffect(() => {
    (async () => {
      const status = await Camera.requestCameraPermission();
      setHasPermission(status === 'granted');
    })();
  }, []);

  const handleCapture = async () => {
    if (!name.trim()) {
      Alert.alert('Validation Error', 'Please enter a personnel name before capturing.');
      return;
    }
    if (!camera.current) return;

    setIsCapturing(true);
    setCaptureCount(0);
    const imagePaths: string[] = [];

    setInstruction('Look straight ahead...');

    try {
      for (let i = 0; i < 5; i++) {
        setCaptureCount(i + 1);
        if (i === 1) setInstruction('Tilt head slightly up...');
        if (i === 2) setInstruction('Tilt head slightly down...');
        if (i === 3) setInstruction('Turn head slightly left...');
        if (i === 4) setInstruction('Turn head slightly right...');

        const photo = await camera.current.takePhoto({
          qualityPrioritization: 'speed',
          enableShutterSound: true,
        });
        imagePaths.push(photo.path);
        if (i < 4) await new Promise(r => setTimeout(() => r(undefined), 800));
      }

      setInstruction('Processing enrollment...');
      setIsCapturing(false);
      setIsEnrolling(true);

      // ── M3 integration: call real native enrollFace ─────────────────────
      // Returns { success: boolean, id: string } — id is the UUID from SQLite.
      // timestamp is generated locally since the native contract doesn't include it.
      const result = await enrollFace(name.trim(), imagePaths);
      const enrolledAt = new Date().toISOString();

      if (result.success) {
        navigation.navigate('EnrollmentConfirmation', {
          name: name.trim(),
          timestamp: enrolledAt,   // local timestamp; id = result.id stored in DB
        });
      } else {
        Alert.alert('Enrollment Failed', 'Could not enroll user. Please try again.');
        setInstruction('Enter your name and press Capture');
      }
    } catch (error) {
      console.error('[EnrollmentScreen]', error);
      Alert.alert('Error', 'An error occurred during enrollment.');
      setInstruction('Enter your name and press Capture');
    } finally {
      setIsCapturing(false);
      setIsEnrolling(false);
    }
  };


  if (!hasPermission) {
    return (
      <View style={styles.centerContainer}>
        <Text style={styles.text}>No Camera Permission</Text>
      </View>
    );
  }

  if (device == null) {
    return (
      <View style={styles.centerContainer}>
        <Text style={styles.text}>No Camera Found</Text>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <Camera
        ref={camera}
        style={styles.cameraPreview}
        device={device}
        isActive={!isEnrolling}
        photo={true}
      />
      
      <View style={styles.uiOverlay}>
        <View style={styles.header}>
          <Text style={styles.headerTitle}>New Enrollment</Text>
        </View>

        <View style={styles.instructionCard}>
          <Text style={styles.instructionText}>{instruction}</Text>
          {isCapturing && (
            <Text style={styles.progressText}>Captured: {captureCount}/5</Text>
          )}
        </View>

        <View style={styles.formContainer}>
          <TextInput
            style={styles.input}
            placeholder="Personnel Name"
            placeholderTextColor="#aaa"
            value={name}
            onChangeText={setName}
            editable={!isCapturing && !isEnrolling}
          />

          <TouchableOpacity
            style={[styles.captureButton, (isCapturing || isEnrolling || !name) && styles.captureButtonDisabled]}
            onPress={handleCapture}
            disabled={isCapturing || isEnrolling || !name}
          >
            {isCapturing || isEnrolling ? (
              <ActivityIndicator color="#fff" />
            ) : (
              <Text style={styles.captureButtonText}>Start Capture</Text>
            )}
          </TouchableOpacity>
        </View>
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  centerContainer: {
    flex: 1,
    backgroundColor: '#000',
    justifyContent: 'center',
    alignItems: 'center',
  },
  container: {
    flex: 1,
    backgroundColor: '#000',
  },
  cameraPreview: {
    flex: 1,
  },
  uiOverlay: {
    ...StyleSheet.absoluteFill,
    justifyContent: 'space-between',
    padding: 20,
    paddingTop: 60,
  },
  header: {
    alignItems: 'center',
  },
  headerTitle: {
    fontSize: 22,
    fontWeight: 'bold',
    color: '#fff',
    textShadowColor: 'rgba(0, 0, 0, 0.75)',
    textShadowOffset: {width: -1, height: 1},
    textShadowRadius: 10
  },
  instructionCard: {
    backgroundColor: 'rgba(0,0,0,0.6)',
    padding: 16,
    borderRadius: 12,
    alignItems: 'center',
  },
  instructionText: {
    color: '#fff',
    fontSize: 18,
    fontWeight: '600',
    textAlign: 'center',
  },
  progressText: {
    color: '#4caf50',
    fontSize: 16,
    marginTop: 8,
    fontWeight: 'bold',
  },
  formContainer: {
    backgroundColor: 'rgba(25, 25, 25, 0.9)',
    padding: 20,
    borderRadius: 16,
    marginBottom: 20,
  },
  input: {
    backgroundColor: '#333',
    color: '#fff',
    borderRadius: 8,
    padding: 14,
    fontSize: 16,
    marginBottom: 16,
  },
  captureButton: {
    backgroundColor: '#2196f3',
    padding: 16,
    borderRadius: 8,
    alignItems: 'center',
  },
  captureButtonDisabled: {
    backgroundColor: '#555',
  },
  captureButtonText: {
    color: '#fff',
    fontSize: 18,
    fontWeight: 'bold',
  },
  text: {
    color: '#fff',
    fontSize: 18,
  },
});

export default EnrollmentScreen;
