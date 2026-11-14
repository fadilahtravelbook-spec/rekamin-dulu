package com.bangdillah.rekamindulu

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.bangdillah.rekamindulu.utils.AppLockHelper

/**
 * Application class untuk memantau siklus foreground/background aplikasi secara global
 * sehingga kunci PIN otomatis aktif kembali saat pengguna meninggalkan aplikasi.
 */
class RekaminDuluApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

            override fun onActivityStarted(activity: Activity) {
                AppLockHelper.sessionTracker.onActivityStarted()
            }

            override fun onActivityResumed(activity: Activity) {}

            override fun onActivityPaused(activity: Activity) {}

            override fun onActivityStopped(activity: Activity) {
                AppLockHelper.sessionTracker.onActivityStopped(activity.isChangingConfigurations)
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
