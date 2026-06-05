import React from 'react';
import { NavigationContainer } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { TouchableOpacity, Text, StyleSheet, View } from 'react-native';

// Screens
import CameraScreen from '../screens/CameraScreen';
import EnrollmentScreen from '../screens/EnrollmentScreen';
import EnrollmentConfirmationScreen from '../screens/EnrollmentConfirmationScreen';
import ResultScreen from '../screens/ResultScreen';
import HistoryScreen from '../screens/HistoryScreen';

// Components
import SyncBadge from '../components/SyncBadge';

export type RootStackParamList = {
  Camera: undefined;
  Enrollment: undefined;
  EnrollmentConfirmation: { name: string; timestamp: string };
  Result: { success: boolean; name?: string; timestamp?: string; score?: number; reason?: string };
  History: undefined;
};

const Stack = createNativeStackNavigator<RootStackParamList>();

export const AppNavigator = () => {
  return (
    <NavigationContainer>
      <Stack.Navigator
        screenOptions={{
          headerStyle: {
            backgroundColor: '#121212',
          },
          headerTintColor: '#fff',
          headerTitleStyle: {
            fontWeight: 'bold',
          },
        }}
      >
        <Stack.Screen 
          name="Camera" 
          component={CameraScreen} 
          options={({ navigation }) => ({
            title: 'Face Auth',
            headerLeft: () => (
              <TouchableOpacity 
                style={styles.navButton} 
                onPress={() => navigation.navigate('History')}
              >
                <Text style={styles.navButtonText}>History</Text>
              </TouchableOpacity>
            ),
            headerRight: () => (
              <View style={{ flexDirection: 'row', alignItems: 'center' }}>
                <TouchableOpacity 
                  style={styles.navButton} 
                  onPress={() => navigation.navigate('Enrollment')}
                >
                  <Text style={[styles.navButtonText, { color: '#4CAF50' }]}>+ Enroll</Text>
                </TouchableOpacity>
                <SyncBadge />
              </View>
            ),
          })}
        />
        <Stack.Screen 
          name="Enrollment" 
          component={EnrollmentScreen} 
          options={{ title: 'Enrollment' }} 
        />
        <Stack.Screen 
          name="EnrollmentConfirmation" 
          component={EnrollmentConfirmationScreen} 
          options={{ title: 'Success', headerBackVisible: false }} 
        />
        <Stack.Screen 
          name="Result" 
          component={ResultScreen} 
          options={{ presentation: 'fullScreenModal', headerShown: false }} 
        />
        <Stack.Screen 
          name="History" 
          component={HistoryScreen} 
          options={{ title: 'Attendance Log' }} 
        />
      </Stack.Navigator>
    </NavigationContainer>
  );
};

const styles = StyleSheet.create({
  navButton: {
    marginRight: 10,
  },
  navButtonText: {
    color: '#2196f3',
    fontSize: 16,
    fontWeight: '600',
  }
});

export default AppNavigator;
