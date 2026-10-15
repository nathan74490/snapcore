package com.snapcore.app.data

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.snapcore.app.domain.AuthService
import com.snapcore.app.domain.UserFacingException
import kotlinx.coroutines.tasks.await

// Firebase Authentication, fournisseur email/mot de passe uniquement (ADR-015).
class FirebaseAuthService(private val auth: FirebaseAuth) : AuthService {
    override val isSignedIn get() = auth.currentUser != null
    override val currentEmail get() = auth.currentUser?.email

    override suspend fun signUp(email: String, password: String) = translated {
        val user = auth.createUserWithEmailAndPassword(email.trim(), password).await().user
        // Gratuit et non bloquant : l'app reste utilisable sans confirmation (ADR-010).
        runCatching { user?.sendEmailVerification()?.await() }
        Unit
    }

    override suspend fun signIn(email: String, password: String) = translated {
        auth.signInWithEmailAndPassword(email.trim(), password).await()
        Unit
    }

    override fun signOut() = auth.signOut()

    override suspend fun isEmailVerified(): Boolean {
        val user = auth.currentUser ?: return false
        runCatching { user.reload().await() }
        return auth.currentUser?.isEmailVerified == true
    }

    override suspend fun resendVerificationEmail() = translated {
        auth.currentUser?.sendEmailVerification()?.await()
        Unit
    }

    private suspend fun <T> translated(block: suspend () -> T): T =
        try {
            block()
        } catch (e: FirebaseAuthUserCollisionException) {
            throw UserFacingException("Un compte existe déjà avec cette adresse email")
        } catch (e: FirebaseAuthWeakPasswordException) {
            throw UserFacingException("Mot de passe trop faible")
        } catch (e: FirebaseAuthInvalidUserException) {
            throw UserFacingException("Email ou mot de passe incorrect")
        } catch (e: FirebaseAuthInvalidCredentialsException) {
            throw UserFacingException("Email ou mot de passe incorrect")
        } catch (e: FirebaseNetworkException) {
            throw UserFacingException("Pas de connexion internet")
        } catch (e: FirebaseAuthException) {
            throw UserFacingException(e.localizedMessage ?: "Erreur d'authentification")
        }
}
