//
//  FaceAuthModule.m
//  DatalakeFaceAuth
//
//  Objective-C bridging file that registers the Swift FaceAuthModule
//  with React Native's bridge infrastructure.
//

#import <React/RCTBridgeModule.h>

@interface RCT_EXTERN_MODULE(FaceAuthModule, NSObject)

// 1. initialize(modelPath) → Promise<void>
RCT_EXTERN_METHOD(initialize:(NSString *)modelPath
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)

// 2. enrollFace(name, imagePaths) → Promise<{success, id}>
RCT_EXTERN_METHOD(enrollFace:(NSString *)name
                  imagePaths:(NSArray<NSString *> *)imagePaths
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)

// 3. authenticate(frameBase64) → Promise<{matched, name, score, livenessPass}>
RCT_EXTERN_METHOD(authenticate:(NSString *)frameBase64
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)

// 4. startLivenessChallenge() → Promise<{step, progress}>
RCT_EXTERN_METHOD(startLivenessChallenge:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)

// 5. getLivenessChallengeState() → Promise<{step, progress}>
RCT_EXTERN_METHOD(getLivenessChallengeState:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)

@end
