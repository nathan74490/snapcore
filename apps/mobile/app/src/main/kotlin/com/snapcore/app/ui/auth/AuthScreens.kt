package com.snapcore.app.ui.auth

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.sp
import com.snapcore.app.ui.components.FigmaAsset
import com.snapcore.app.ui.components.SnapHeader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snapcore.app.domain.Session
import com.snapcore.app.ui.components.PrimaryButton
import com.snapcore.app.ui.components.SnapTextField
import com.snapcore.app.ui.components.StatusBarIcons
import com.snapcore.app.ui.containerViewModel
import com.snapcore.app.ui.theme.SnapColors

@Composable
fun StartScreen(onSession: (Session) -> Unit) {
    val vm = containerViewModel { StartViewModel(it.sessionResolver) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state) { (state as? StartState.Resolved)?.let { onSession(it.session) } }
    StatusBarIcons(dark = true) // fond jaune : icônes sombres, quel que soit le thème du téléphone

    Box(Modifier.fillMaxSize().background(SnapColors.Yellow), contentAlignment = Alignment.Center) {
        when (val s = state) {
            is StartState.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                Text(s.message, color = SnapColors.Black, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                PrimaryButton("Réessayer", vm::resolve, container = SnapColors.Black)
            }
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Logo()
                Spacer(Modifier.height(24.dp))
                CircularProgressIndicator(color = SnapColors.Black)
            }
        }
    }
}

@Composable
private fun Logo() {
    FigmaAsset("13-241_imgImageGhostLogo")
}

@Composable
fun WelcomeScreen(onLogin: () -> Unit, onSignUp: () -> Unit) {
    StatusBarIcons(dark = true)

    BoxWithConstraints(Modifier.fillMaxSize().background(SnapColors.Yellow)) {
        FigmaAsset("13-241_imgImageGhostLogo", modifier = Modifier.align(Alignment.TopCenter).padding(top = maxHeight * (262f / 852f)))
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(80.dp).background(SnapColors.SnapRed).clickable(onClick = onLogin), Alignment.Center) {
                Text("CONNEXION", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
            Box(Modifier.fillMaxWidth().background(SnapColors.ChatBlue).navigationBarsPadding().height(66.dp).clickable(onClick = onSignUp), Alignment.Center) {
                Text("INSCRIPTION", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun FormScaffold(
    title: String,
    onBack: () -> Unit,
    progress: Float? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {

    StatusBarIcons(dark = true)
    Column(Modifier.fillMaxSize().background(Color.White).navigationBarsPadding().imePadding()) {
        SnapHeader(title, onBack, subtitle = progress?.let { "Étape ${(it * SignUpState.STEPS).toInt()} sur ${SignUpState.STEPS}" })
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) { content() }
        action?.let { Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) { it() } }
    }
}

@Composable
fun LoginScreen(onBack: () -> Unit, onSession: (Session) -> Unit) {
    val vm = containerViewModel { LoginViewModel(it.auth, it.sessionResolver) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.session) { state.session?.let(onSession) }


    StatusBarIcons(dark = true)
    Column(Modifier.fillMaxSize().background(Color.White).safeDrawingPadding().imePadding().verticalScroll(rememberScrollState())) {
        IconButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) {
            FigmaAsset("19-1518_imgIconChevronLeft", "Retour")
        }
        Text("Connexion", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Normal,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 36.dp), textAlign = TextAlign.Center)
        Column(Modifier.padding(horizontal = 53.dp)) {
            LoginField(state.email, vm::onEmail, "ADRESSE EMAIL", false)
            Spacer(Modifier.height(12.dp))
            LoginField(state.password, vm::onPassword, "MOT DE PASSE", true)
            state.error?.let { Text(it, color = SnapColors.SnapRed, modifier = Modifier.padding(top = 12.dp)) }
        }
        Spacer(Modifier.height(150.dp))
        PrimaryButton("Connexion", vm::submit, enabled = state.email.isNotBlank() && state.password.isNotBlank(),
            loading = state.loading, modifier = Modifier.padding(horizontal = 75.dp),
            // Bouton « Log In » de la maquette (19:1534) : 48 de haut, libellé Regular 19.
            height = 48.dp, textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 19.sp))
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LoginField(value: String, change: (String) -> Unit, label: String, password: Boolean) {

    var reveal by remember { mutableStateOf(false) }
    Text(label, color = SnapColors.Muted, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = .96.sp)
    Row(Modifier.fillMaxWidth().height(38.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(value, change, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, color = SnapColors.Ink),
            visualTransformation = if(password && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if(password) KeyboardType.Password else KeyboardType.Email),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(SnapColors.ChatBlue), modifier = Modifier.weight(1f))
        if(password) IconButton(onClick = { reveal = !reveal }, modifier = Modifier.size(36.dp)) {
            if(reveal) Icon(Icons.Filled.Visibility, "Masquer le mot de passe", tint = SnapColors.Muted, modifier = Modifier.size(22.dp))
            else FigmaAsset("19-1518_imgIconEyeOff", "Afficher le mot de passe")
        }
    }
    HorizontalDivider(color = SnapColors.Hairline, thickness = 1.dp)
}

@Composable
fun SignUpScreen(onBack: () -> Unit, onSession: (Session) -> Unit) {
    val vm = containerViewModel { SignUpViewModel(it.auth, it.accounts, it.pendingRegistration) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.session) { state.session?.let(onSession) }

    val title = when (state.step) {
        1 -> "Choisis ton pseudo"
        2 -> "Tes coordonnées"
        else -> if (state.firebaseAccountCreated) "Finalise ton inscription" else "Choisis un mot de passe"
    }
    FormScaffold(title, onBack = { if (state.step == 1) onBack() else vm.back() }, progress = state.step / SignUpState.STEPS.toFloat(),
        action = { PrimaryButton(if (state.step < SignUpState.STEPS) "Continuer" else "S'inscrire", vm::next, enabled = state.canContinue, loading = state.loading) }) {
        when (state.step) {
            1 -> SnapTextField(
                state.username,
                vm::onUsername,
                "Pseudo",
                error = state.usernameError,
                supporting = "Tu pourras le changer plus tard",
            )
            2 -> {
                SnapTextField(
                    state.email,
                    vm::onEmail,
                    "Email",
                    error = state.emailError,
                    keyboardType = KeyboardType.Email,
                    enabled = !state.firebaseAccountCreated,
                    supporting = "Un email de confirmation te sera envoyé",
                )
                SnapTextField(
                    state.phone,
                    vm::onPhone,
                    "Téléphone (facultatif)",
                    error = state.phoneError,
                    keyboardType = KeyboardType.Phone,
                )
            }
            else -> if (!state.firebaseAccountCreated) {
                SnapTextField(
                    state.password,
                    vm::onPassword,
                    "Mot de passe",
                    error = state.passwordError,
                    password = true,
                    supporting = "Au moins 6 caractères",
                )
            } else {
                Text("Ton compte est créé, il ne reste plus qu'à l'enregistrer.", textAlign = TextAlign.Center)
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
    }
}

@Composable
fun CompleteRegistrationScreen(onSession: (Session) -> Unit) {
    val vm = containerViewModel { CompleteRegistrationViewModel(it.auth, it.accounts, it.pendingRegistration) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.session) { state.session?.let(onSession) }

    FormScaffold("Termine ton inscription", onBack = vm::signOut) {
        Text("Connecté avec ${vm.email ?: "ton compte"}", color = SnapColors.Grey, textAlign = TextAlign.Center)
        SnapTextField(state.username, vm::onUsername, "Pseudo", error = state.usernameError)
        SnapTextField(state.phone, vm::onPhone, "Téléphone (facultatif)", error = state.phoneError, keyboardType = KeyboardType.Phone)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        PrimaryButton("Terminer", vm::submit, loading = state.loading)
        TextButton(onClick = vm::signOut) { Text("Utiliser un autre compte") }
    }
}
