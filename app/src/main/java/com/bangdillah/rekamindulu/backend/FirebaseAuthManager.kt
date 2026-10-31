package com.bangdillah.rekamindulu.backend

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.bangdillah.rekamindulu.R
import com.bangdillah.rekamindulu.utils.AppPreferences
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class AuthStatus {
    object Idle : AuthStatus()
    object Loading : AuthStatus()
    data class Authenticated(val user: FirebaseUser, val profile: UserProfile?) : AuthStatus()
    data class Error(val message: String) : AuthStatus()
    object Unauthenticated : AuthStatus()
}

/**
 * FirebaseAuthManager - Pengelola otentikasi Google Sign-In & sesi Firebase Auth
 * menggunakan Jetpack CredentialManager.
 */
class FirebaseAuthManager private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "FirebaseAuthManager"

        @Volatile
        private var INSTANCE: FirebaseAuthManager? = null

        fun getInstance(context: Context): FirebaseAuthManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FirebaseAuthManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val credentialManager = CredentialManager.create(appContext)

    private val _authStatus = MutableStateFlow<AuthStatus>(AuthStatus.Idle)
    val authStatus: StateFlow<AuthStatus> = _authStatus.asStateFlow()

    val currentUser: FirebaseUser?
        get() = auth.currentUser

    val isUserSignedIn: Boolean
        get() = auth.currentUser != null

    init {
        auth.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            if (user != null) {
                _authStatus.value = AuthStatus.Authenticated(user, null)
                syncUserProfile(user)
            } else {
                _authStatus.value = AuthStatus.Unauthenticated
            }
        }
    }

    /**
     * Memulai proses interaktif Google Sign-In menggunakan Jetpack CredentialManager
     */
    fun signInWithGoogle(activity: Activity, onComplete: ((Boolean, String?) -> Unit)? = null) {
        val serverClientId = try {
            appContext.getString(R.string.default_web_client_id)
        } catch (e: Exception) {
            ""
        }
        if (serverClientId.isBlank()) {
            val errMsg = "Web Client ID belum dikonfigurasi di strings.xml"
            _authStatus.value = AuthStatus.Error(errMsg)
            onComplete?.invoke(false, errMsg)
            return
        }

        _authStatus.value = AuthStatus.Loading

        val googleOption = GetSignInWithGoogleOption.Builder(serverClientId)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleOption)
            .build()

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val result = credentialManager.getCredential(activity, request)
                val credential = result.credential
                if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    val idToken = googleIdTokenCredential.idToken
                    val firebaseCred = GoogleAuthProvider.getCredential(idToken, null)
                    val authResult = auth.signInWithCredential(firebaseCred).await()
                    val user = authResult.user
                    if (user != null) {
                        syncUserProfile(user)
                        _authStatus.value = AuthStatus.Authenticated(user, null)
                        onComplete?.invoke(true, null)
                    } else {
                        val err = "Gagal mengambil data user dari Google"
                        _authStatus.value = AuthStatus.Error(err)
                        onComplete?.invoke(false, err)
                    }
                } else {
                    val err = "Tipe kredensial tidak sesuai"
                    _authStatus.value = AuthStatus.Error(err)
                    onComplete?.invoke(false, err)
                }
            } catch (e: GetCredentialCancellationException) {
                Log.i(TAG, "Google Sign-In dibatalkan oleh pengguna")
                _authStatus.value = if (auth.currentUser != null) {
                    AuthStatus.Authenticated(auth.currentUser!!, null)
                } else {
                    AuthStatus.Unauthenticated
                }
                onComplete?.invoke(false, "Login dibatalkan")
            } catch (e: GetCredentialException) {
                Log.e(TAG, "Credential Manager error: ${e.message}", e)
                val err = "Gagal login Google: ${e.localizedMessage ?: "Koneksi terganggu"}"
                _authStatus.value = AuthStatus.Error(err)
                onComplete?.invoke(false, err)
            } catch (e: Exception) {
                Log.e(TAG, "Firebase auth exception: ${e.message}", e)
                val err = "Gagal terhubung ke Firebase: ${e.localizedMessage}"
                _authStatus.value = AuthStatus.Error(err)
                onComplete?.invoke(false, err)
            }
        }
    }

    /**
     * Silent sign-in otomatis jika pengguna sebelumnya sudah mengizinkan akun
     */
    fun attemptSilentSignIn(activity: Activity) {
        if (auth.currentUser != null) return

        val serverClientId = try {
            appContext.getString(R.string.default_web_client_id)
        } catch (e: Exception) {
            ""
        }
        if (serverClientId.isBlank()) return

        val silentOption = GetGoogleIdOption.Builder()
            .setServerClientId(serverClientId)
            .setFilterByAuthorizedAccounts(true)
            .setAutoSelectEnabled(true)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(silentOption)
            .build()

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val result = credentialManager.getCredential(activity, request)
                val credential = result.credential
                if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    val idToken = googleIdTokenCredential.idToken
                    val firebaseCred = GoogleAuthProvider.getCredential(idToken, null)
                    val authResult = auth.signInWithCredential(firebaseCred).await()
                    authResult.user?.let { syncUserProfile(it) }
                }
            } catch (ignored: Exception) {
                // Silent login gagal adalah hal wajar jika pertama kali buka
            }
        }
    }

    fun signOut(onComplete: (() -> Unit)? = null) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                auth.signOut()
                credentialManager.clearCredentialState(androidx.credentials.ClearCredentialStateRequest())
            } catch (e: Exception) {
                Log.w(TAG, "Clear credential notice: ${e.message}")
            } finally {
                _authStatus.value = AuthStatus.Unauthenticated
                onComplete?.invoke()
            }
        }
    }

    private fun syncUserProfile(user: FirebaseUser) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = try {
                    val dbId = appContext.getString(R.string.firestore_database_id)
                    if (dbId.isNotBlank()) FirebaseFirestore.getInstance(dbId) else FirebaseFirestore.getInstance()
                } catch (e: Exception) {
                    FirebaseFirestore.getInstance()
                }
                val userDocRef = db.collection("users").document(user.uid)
                val snapshot = userDocRef.get().await()

                if (!snapshot.exists()) {
                    val timeStamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).format(Date())
                    val prefs = AppPreferences(appContext)
                    val newProfile = UserProfile(
                        uid = user.uid,
                        displayName = user.displayName ?: "Perekam Handal",
                        email = user.email ?: "",
                        photoUrl = user.photoUrl?.toString() ?: "",
                        createdAt = timeStamp,
                        totalRecordings = 0,
                        totalDurationSeconds = 0L,
                        preferredResolution = prefs.resolution,
                        preferredFps = prefs.fps,
                        gamingModeEnabled = prefs.isGamingMode
                    )
                    userDocRef.set(newProfile, SetOptions.merge()).await()
                    _authStatus.value = AuthStatus.Authenticated(user, newProfile)
                } else {
                    val profile = snapshot.toObject(UserProfile::class.java)
                    if (profile != null) {
                        val prefs = AppPreferences(appContext)
                        if (profile.preferredResolution.isNotBlank()) {
                            prefs.resolution = profile.preferredResolution
                        }
                        if (profile.preferredFps > 0) {
                            prefs.fps = profile.preferredFps
                        }
                        prefs.isGamingMode = profile.gamingModeEnabled
                    }
                    _authStatus.value = AuthStatus.Authenticated(user, profile)
                }
            } catch (e: Exception) {
                Log.w(TAG, "UserProfile sync warning: ${e.message}")
            }
        }
    }

    /**
     * Memperbarui konfigurasi preferensi pengguna ke Firestore agar tersimpan permanen di cloud.
     */
    fun updateUserPreferences(resolution: String? = null, fps: Int? = null, gamingMode: Boolean? = null) {
        val user = auth.currentUser ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = try {
                    val dbId = appContext.getString(R.string.firestore_database_id)
                    if (dbId.isNotBlank()) FirebaseFirestore.getInstance(dbId) else FirebaseFirestore.getInstance()
                } catch (e: Exception) {
                    FirebaseFirestore.getInstance()
                }
                val updates = mutableMapOf<String, Any>()
                if (resolution != null) updates["preferredResolution"] = resolution
                if (fps != null) updates["preferredFps"] = fps
                if (gamingMode != null) updates["gamingModeEnabled"] = gamingMode
                if (updates.isNotEmpty()) {
                    db.collection("users").document(user.uid).set(updates, SetOptions.merge()).await()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Gagal backup preferensi pengguna ke cloud: ${e.message}")
            }
        }
    }
}
