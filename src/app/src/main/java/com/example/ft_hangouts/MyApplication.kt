package com.example.hangly

import android.app.Activity
import android.app.Application
import android.os.Bundle

class MyApplication : Application(), Application.ActivityLifecycleCallbacks {

    private var activityCount = 0

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        activityCount++
    }

    override fun onActivityStopped(activity: Activity) {
        activityCount--
        if (activityCount == 0) {
            // Uygulama gerçekten arka plana atıldı
            val prefs = getSharedPreferences("hangly_prefs", MODE_PRIVATE)
            prefs.edit().putLong("last_background_time", System.currentTimeMillis()).apply()
        }
    }

    // Diğer gereksiz metotları boş bırakabilirsin
    override fun onActivityCreated(a: Activity, b: Bundle?) {}
    override fun onActivityResumed(a: Activity) {}
    override fun onActivityPaused(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(a: Activity) {}
}
