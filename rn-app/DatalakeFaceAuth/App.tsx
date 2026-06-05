import React, { useEffect } from 'react';
import { StatusBar, useColorScheme } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import AppNavigator from './src/navigation/AppNavigator';
import { initializeFaceAuth } from './src/services/FaceAuthService';

function App() {
  const isDarkMode = useColorScheme() === 'dark';

  // ── M3 integration: boot the native face-auth module ──────────────────────
  // initialize() loads BlazeFace + MobileFaceNet + FaceMesh from the models/
  // directory and opens the SQLCipher database. Must complete before any
  // authentication or enrollment call is made.
  useEffect(() => {
    initializeFaceAuth().catch(err =>
      console.error('[App] FaceAuth init error:', err)
    );
  }, []);

  return (
    <SafeAreaProvider>
      <StatusBar barStyle={isDarkMode ? 'light-content' : 'dark-content'} backgroundColor="#121212" />
      <AppNavigator />
    </SafeAreaProvider>
  );
}

export default App;

