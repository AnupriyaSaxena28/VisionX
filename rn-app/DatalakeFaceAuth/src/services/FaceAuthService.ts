import { NativeModules } from 'react-native';

// Assuming Member 3 will export a strongly-typed NativeModule from this path
// or that they will link it via NativeModules directly. We will provide a 
// mock import or type definition here for the error handling wrapper.
// import FaceAuthNative from '../../../../native-module/NativeModule';

const { FaceAuthNativeModule } = NativeModules;

export interface FaceEmbedding {
  id: string;
  embedding: number[];
}

export interface LivenessResult {
  isLive: boolean;
  score: number;
}

export interface AttendanceLog {
  id: string;
  name: string;
  timestamp: string;
  score: number;
  synced: boolean;
}

// Mock state for sequence testing
let authCallCount = 0;
let isEnrolled = false;

export const FaceAuthService = {
  async detectFace(frameBase64: string): Promise<any> {
    return { detected: true };
  },

  async extractEmbedding(frameBase64: string): Promise<number[]> {
    return [0.1, 0.2, 0.3];
  },

  async checkLiveness(frameBase64: string): Promise<LivenessResult> {
    return { isLive: true, score: 0.98 };
  },

  async authenticate(frameBase64: string): Promise<{ livenessPass: boolean, matched: boolean, state: string }> {
    // Simulate network/processing delay
    await new Promise(resolve => setTimeout(resolve, 200));

    authCallCount++;

    // 0-2: "Please blink" (face_detected)
    if (authCallCount <= 3) {
      return { livenessPass: false, matched: false, state: 'blink' };
    }
    // 4-6: "Now turn your head slightly" (challenge)
    if (authCallCount <= 6) {
      return { livenessPass: false, matched: false, state: 'turn' };
    }
    
    // Reset counter for next time
    authCallCount = 0;

    // 7+: Success
    return { livenessPass: true, matched: true, state: 'success' };
  },

  async enrollFace(name: string, imagePaths: string[]): Promise<{ success: boolean, timestamp: string }> {
    await new Promise(resolve => setTimeout(resolve, 1500)); // Simulate processing
    isEnrolled = true;
    return { success: true, timestamp: new Date().toISOString() };
  },

  async syncWithAWS(): Promise<boolean> {
    await new Promise(resolve => setTimeout(resolve, 1000));
    return true;
  },

  async getAttendanceLog(): Promise<AttendanceLog[]> {
    return [
      {
        id: '1',
        name: 'Aarav Patel',
        timestamp: new Date(Date.now() - 3600000).toISOString(),
        score: 0.98,
        synced: true,
      },
      {
        id: '2',
        name: 'Priya Sharma',
        timestamp: new Date(Date.now() - 1800000).toISOString(),
        score: 0.95,
        synced: false,
      },
      {
        id: '3',
        name: isEnrolled ? 'New User (You)' : 'Rohan Kumar',
        timestamp: new Date().toISOString(),
        score: 0.99,
        synced: false,
      }
    ];
  },

  async getPendingRecordsCount(): Promise<number> {
    // Return 2 to test the amber syncing state
    return 2;
  },

  async getLastSyncTime(): Promise<string> {
    return new Date(Date.now() - 7200000).toISOString();
  }
};

export default FaceAuthService;
