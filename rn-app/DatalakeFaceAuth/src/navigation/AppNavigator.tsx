import React from 'react';
import { NavigationContainer } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { TouchableOpacity, Text, StyleSheet } from 'react-native';

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
  Result: { success: boolean; name?: string; timestamp?: string; reason?: string };
  History: undefined;
};

const Stack = createNativeStackNavigator<RootStackParamList>();

export const AppNavigator = () => {
  return (
    <NavigationContainer>
      <Stack.Navigator
        screenOptions={({ navigation }) => ({
          headerStyle: {
            backgroundColor: '#121212',
          },
          headerTintColor: '#fff',
          headerTitleStyle: {
            fontWeight: 'bold',
          },
          headerRight: () => <SyncBadge />,
        })}
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
    marginRight: 15,
  },
  navButtonText: {
    color: '#2196f3',
    fontSize: 16,
    fontWeight: '600',
  }
});

export default AppNavigator;
