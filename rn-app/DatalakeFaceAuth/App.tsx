import React, { useEffect } from 'react';
import { StatusBar, useColorScheme, LogBox } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import AppNavigator from './src/navigation/AppNavigator';
import { initializeFaceAuth } from './src/services/FaceAuthService';

// Ignore specific warnings if necessary
LogBox.ignoreLogs(['Worklets']);

function App() {
  const isDarkMode = useColorScheme() === 'dark';

  useEffect(() => {
    // M3 integration: boot the native face-auth module
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

