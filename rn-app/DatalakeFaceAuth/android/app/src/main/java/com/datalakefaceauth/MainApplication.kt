package com.datalakefaceauth

import android.app.Application
import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost

class MainApplication : Application(), ReactApplication {

  override val reactHost: ReactHost by lazy {
    getDefaultReactHost(
      context = applicationContext,
      packageList =
        PackageList(this).packages.apply {
          // ── M3 Integration: Register FaceAuthModule native bridge ──────────
          // NativeModule.ts (M3→M4 contract) depends on this registration.
          // JS accesses it as: NativeModules.FaceAuthModule
          add(FaceAuthPackage())
        },
    )
  }

  override fun onCreate() {
    super.onCreate()
    loadReactNative(this)

    // ── M3 Integration: Schedule background attendance sync ────────────────
    // WorkManager picks this up immediately and re-enqueues every 15 minutes
    // when network is available. Uses ACK-before-purge pattern (SyncService.kt).
    SyncService.schedulePeriodic(this)
  }
}
