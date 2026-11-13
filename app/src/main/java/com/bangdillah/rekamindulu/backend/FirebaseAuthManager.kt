package com.bangdillah.rekamindulu.backend

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LocalCloudUser(
    val uid: String,
    val displayName: String? = null,
    val email: String? = null,
    val photoUrl: String? = null
)

sealed class AuthStatus {
    object Idle : AuthStatus()
    object Loading : AuthStatus()
    data class Authenticated(val user: LocalCloudUser, val profile: UserProfile?) : AuthStatus()
    data class Error(val message: String) : AuthStatus()
    object Unauthenticated : AuthStatus()
}

/**
 * Local offline stub for FirebaseAuthManager when Firebase is not configured.
 */
class FirebaseAuthManager private constructor(@Suppress("UNUSED_PARAMETER") appContext: Context) {

    companion object {
        @Volatile
        private var INSTANCE: FirebaseAuthManager? = null

        fun getInstance(context: Context): FirebaseAuthManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FirebaseAuthManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val _authStatus = MutableStateFlow<AuthStatus>(AuthStatus.Unauthenticated)
    val authStatus: StateFlow<AuthStatus> = _authStatus.asStateFlow()

    val currentUser: LocalCloudUser?
        get() = (_authStatus.value as? AuthStatus.Authenticated)?.user

    val isUserSignedIn: Boolean
        get() = currentUser != null

    fun signInWithGoogle(@Suppress("UNUSED_PARAMETER") activity: Activity, onComplete: ((Boolean, String?) -> Unit)? = null) {
        val msg = "Mode lokal aktif (sinkronisasi cloud Firebase dinonaktifkan)"
        _authStatus.value = AuthStatus.Error(msg)
        onComplete?.invoke(false, msg)
    }

    fun attemptSilentSignIn(@Suppress("UNUSED_PARAMETER") activity: Activity) {
        // No-op in local mode
    }

    fun signOut(onComplete: (() -> Unit)? = null) {
        _authStatus.value = AuthStatus.Unauthenticated
        onComplete?.invoke()
    }

    fun updateUserPreferences(resolution: String? = null, fps: Int? = null, gamingMode: Boolean? = null) {
        // Preferences are already persisted locally via AppPreferences
    }
}
