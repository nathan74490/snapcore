package com.snapcore.app.data

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.snapcore.app.data.api.PhoneCodeRequest
import com.snapcore.app.data.api.PhoneCodeVerification
import com.snapcore.app.data.api.SnapCoreApi
import com.snapcore.app.data.api.apiCall
import com.snapcore.app.domain.AuthService
import com.snapcore.app.domain.PhoneCodeSent
import com.snapcore.app.domain.UserFacingException
import kotlinx.coroutines.tasks.await

// Firebase Authentication (ADR-015). Connexion par code SMS (ADR-034) : le backend vérifie le code et renvoie
// un jeton personnalisé, échangé ici contre une session Firebase. L'email et le mot de passe ne servent plus
// qu'aux comptes créés avant.
class FirebaseAuthService(private val auth: FirebaseAuth, private val api: SnapCoreApi) : AuthService {
    override val isSignedIn get() = auth.currentUser != null
    override val currentEmail get() = auth.currentUser?.email

    override suspend fun requestPhoneCode(phoneNumber: String): PhoneCodeSent = apiCall {
        val sent = api.sendPhoneCode(PhoneCodeRequest(phoneNumber))
        PhoneCodeSent(sent.phoneNumber, sent.resendInSeconds, sent.simulatedCode)
    }

    override suspend fun signInWithPhoneCode(phoneNumber: String, code: String) {
        val token = apiCall { api.verifyPhoneCode(PhoneCodeVerification(phoneNumber, code)) }.customToken
        translated { auth.signInWithCustomToken(token).await(); Unit }
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
