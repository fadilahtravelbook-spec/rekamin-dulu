package com.bangdillah.rekamindulu

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import com.bangdillah.rekamindulu.notification.NotificationHelper
import com.bangdillah.rekamindulu.utils.AppLockHelper
import com.bangdillah.rekamindulu.utils.AppPreferences
import com.bangdillah.rekamindulu.utils.StorageHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RekaminDuluRobolectricTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        MainActivity.isSessionUnlocked = false
        AppLockHelper.clearBackgroundLockSuppression()
    }

    @After
    fun tearDown() {
        AppLockHelper.clearBackgroundLockSuppression()
        MainActivity.isSessionUnlocked = false
    }

    @Test
    fun `manifest disables allowBackup and exposes expected app name`() {
        val appInfo = context.applicationInfo
        assertEquals("Rekamin Dulu", context.getString(R.string.app_name))
        assertEquals(0, appInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    @Test
    fun `appPreferences and legacy PIN migration work on real SharedPreferences`() {
        val prefs = AppPreferences(context)
        prefs.isAppLockEnabled = true
        prefs.appLockPin = "2580"

        assertFalse(AppLockHelper.isHashedPin(prefs.appLockPin))
        assertTrue(AppLockHelper.verifyPin("2580", prefs.appLockPin))

        AppLockHelper.migrateLegacyPinIfNeeded(prefs, "2580")
        assertTrue(AppLockHelper.isHashedPin(prefs.appLockPin))
        assertTrue(AppLockHelper.verifyPin("2580", prefs.appLockPin))
        assertFalse(AppLockHelper.verifyPin("0000", prefs.appLockPin))

        prefs.resetToDefaults()
        assertEquals("1080p", prefs.resolution)
        assertEquals(60, prefs.fps)
        assertEquals("BOTH", prefs.audioSource)
    }

    @Test
    fun `notificationHelper registers recording and completed channels`() {
        NotificationHelper(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val recordingChannel = nm.getNotificationChannel(NotificationHelper.CHANNEL_RECORDING)
        val completedChannel = nm.getNotificationChannel(NotificationHelper.CHANNEL_COMPLETED)

        assertNotNull(recordingChannel)
        assertNotNull(completedChannel)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, recordingChannel?.importance)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, completedChannel?.importance)
    }

    @Test
    fun `storageHelper cleans corrupt small files and keeps valid mp4s`() {
        val recDir = StorageHelper.getRecordingDirectory(context)
        assertTrue(recDir.exists() && recDir.isDirectory)

        val corruptFile = File(recDir, "corrupt_test.mp4").apply { writeBytes(ByteArray(128)) }
        val validFile = File(recDir, "valid_test.mp4").apply { writeBytes(ByteArray(20_000)) }
        try {
            val files = StorageHelper.getAllVideoFiles(context)
            assertFalse(corruptFile.exists())
            assertTrue(files.any { it.absolutePath == validFile.absolutePath })
        } finally {
            corruptFile.delete()
            validFile.delete()
        }
    }

    @Test
    fun `launchExternalWithLockBypass clears suppression if activity launch fails`() {
        val failingContext = object : android.content.ContextWrapper(context) {
            override fun startActivity(intent: Intent?) {
                throw ActivityNotFoundException("No activity handler")
            }
        }
        val missingIntent = Intent("com.bangdillah.rekamindulu.NON_EXISTENT_ACTION").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        var caught = false
        try {
            AppLockHelper.launchExternalWithLockBypass(failingContext, missingIntent)
        } catch (_: ActivityNotFoundException) {
            caught = true
        }
        assertTrue(caught)
        assertFalse(AppLockHelper.sessionTracker.suppressNextBackgroundLock)
    }

    @Test
    fun `launchExternalWithLockBypass keeps suppression active when launch succeeds`() {
        val intent = Intent("com.bangdillah.rekamindulu.EXTERNAL_VIEW").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        AppLockHelper.launchExternalWithLockBypass(context, intent)
        assertTrue(AppLockHelper.sessionTracker.suppressNextBackgroundLock)
    }

    @Test
    fun `splashActivity launches cleanly under Robolectric`() {
        val controller = Robolectric.buildActivity(SplashActivity::class.java).setup()
        val activity = controller.get()
        assertNotNull(activity)
        assertFalse(activity.isFinishing)
        controller.pause().stop().destroy()
    }
}
