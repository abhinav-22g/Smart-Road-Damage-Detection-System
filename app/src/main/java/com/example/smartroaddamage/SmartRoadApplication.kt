package com.example.smartroaddamage

import android.app.Application
import com.mappls.sdk.maps.Mappls

import com.mappls.sdk.services.account.MapplsAccountManager
import com.example.smartroaddamage.workers.HazardUploadScheduler

/**
 * Smart Road Application entry point.
 * 2026 Perfect Integration: SDK is initialized once at the global level.
 * Mappls Gradle Plugin is incompatible with AGP 8.0+, so we initialize 
 * MapplsAccountManager explicitly instead of relying on mappls.json.
 */
class SmartRoadApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // ── MapmyIndia (Mappls) Global Initialization ───────────────────────────
        
        MapplsAccountManager.getInstance().restAPIKey = BuildConfig.MAPPLS_MAP_REST_API_KEY
        MapplsAccountManager.getInstance().mapSDKKey = BuildConfig.MAPPLS_MAP_REST_API_KEY
        MapplsAccountManager.getInstance().atlasClientId = BuildConfig.MAPPLS_ATLAS_CLIENT_ID
        MapplsAccountManager.getInstance().atlasClientSecret = BuildConfig.MAPPLS_ATLAS_CLIENT_SECRET

        Mappls.getInstance(this)
        HazardUploadScheduler.enqueue(this)
    }
}
