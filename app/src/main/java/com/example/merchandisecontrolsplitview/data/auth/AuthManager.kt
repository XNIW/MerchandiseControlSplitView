package com.example.merchandisecontrolsplitview.data.auth

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await

object AuthManager {
    private val auth = FirebaseAuth.getInstance()

    private val _uid = MutableStateFlow(auth.currentUser?.uid)
    val uid: StateFlow<String?> = _uid

    /** Login con Google: nessun sign-in anonimo. */
    suspend fun signInWithGoogle(idToken: String): String {
        val cred = GoogleAuthProvider.getCredential(idToken, null)
        val res = auth.signInWithCredential(cred).await()
        val id = res.user?.uid ?: error("No UID after Google sign-in")
        _uid.value = id
        return id
    }

    fun signOut() {
        auth.signOut()
        _uid.value = null
    }

    /** UID corrente oppure null se non loggato. */
    fun currentUid(): String? = _uid.value
}