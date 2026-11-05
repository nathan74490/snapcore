package com.snapcore.app.ui.auth

import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.semantics.isTraversalGroup
import com.snapcore.app.ui.components.rememberScreenReaderOn
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
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
import android.telephony.TelephonyManager
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.snapcore.app.ui.onboarding.GhostLines
import com.snapcore.app.ui.components.ChunkyButton
import com.snapcore.app.ui.components.GhostMascot
import com.snapcore.app.ui.components.GhostMood
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import com.snapcore.app.ui.onboarding.GhostSays
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged

@Composable
fun StartScreen(onSession: (Session) -> Unit) {
    val vm = containerViewModel { StartViewModel(it.sessionResolver) }
    val state by vm.state.collectAsStateWithLifecycle()
    StatusBarIcons(dark = false)

    Box(Modifier.fillMaxSize().background(Color(0xFF150F25)), contentAlignment = Alignment.Center) {
        when (val s = state) {
            is StartState.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                Text(s.message, color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                PrimaryButton("Réessayer", vm::resolve, container = SnapColors.Black)
            }
            else -> HalloweenStartup(ready = s is StartState.Resolved) {
                (s as? StartState.Resolved)?.let { onSession(it.session) }
            }
        }
    }
}

@Composable
private fun Logo() {
    FigmaAsset("13-241_imgImageGhostLogo")
}

/**
 * Accueil façon Duolingo, aux couleurs de SnapCore : le fantôme animé au centre, le nom et l'accroche, puis
 * « C'est parti ! » (inscription) et « J'ai déjà un compte » (connexion). Toucher le fantôme change son humeur.
 */
@Composable
fun WelcomeScreen(onLogin: () -> Unit, onSignUp: () -> Unit) {
    StatusBarIcons(dark = true)
    val moods = remember { listOf(GhostMood.WAVE, GhostMood.HAPPY, GhostMood.LOVE, GhostMood.CELEBRATE, GhostMood.CURIOUS) }
    var mood by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(SnapColors.Yellow).systemBarsPadding().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        GhostMascot(moods[mood], Modifier.size(220.dp).clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = "le faire réagir",
        ) { mood = (mood + 1) % moods.size }.clearAndSetSemantics {})
        Text("snapcore", fontFamily = WordmarkFont, fontWeight = FontWeight.Black, fontSize = 46.sp, color = SnapColors.Ink,
            modifier = Modifier.padding(top = 8.dp).semantics { heading(); contentDescription = "SnapCore" })
        Text("Tes snaps, tes stories et tes amis.\nAu même endroit.", fontSize = 19.sp, lineHeight = 26.sp,
            color = SnapColors.Ink.copy(alpha = .62f), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
        Spacer(Modifier.weight(1.3f))
        ChunkyButton("Je m'inscris", onSignUp, container = SnapColors.ChatBlue, edge = Color(0xFF0A87C8), content = Color.White)
        Spacer(Modifier.height(14.dp))
        ChunkyButton("J'ai déjà un compte", onLogin, container = SnapColors.SnapRed, edge = Color(0xFFC42A43), content = Color.White)
        Spacer(Modifier.height(20.dp))
    }
}

/** Nunito en graisse Black pour le nom de l'app, comme le logotype arrondi de Duolingo. */
private val WordmarkFont = FontFamily(Font(com.snapcore.app.R.font.nunito_sans, FontWeight.Black,
    variationSettings = FontVariation.Settings(FontVariation.weight(950), FontVariation.Setting("YTLC", 500f))))

/**
 * Connexion guidée par le fantôme, comme l'inscription (ADR-029) : il pose la question, se cache les yeux
 * pendant la saisie du mot de passe et annonce lui-même les erreurs.
 */
@Composable
fun LoginScreen(onBack: () -> Unit, onSession: (Session) -> Unit) {
    val context = LocalContext.current
    val vm = containerViewModel {
        val sim = context.getSystemService(TelephonyManager::class.java)?.simCountryIso?.takeIf { code -> code.isNotBlank() }
        LoginViewModel(it.auth, it.sessionResolver, it.analytics, countryCode = sim ?: java.util.Locale.getDefault().country)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.session) { state.session?.let(onSession) }
    val goBack = { if (!vm.back()) onBack() }
    BackHandler(onBack = goBack)
    var passwordFocused by remember { mutableStateOf(false) }
    var reveal by remember { mutableStateOf(false) }
    var pickingCountry by remember { mutableStateOf(false) }
    if (pickingCountry) {
        CountryPicker(onPick = { vm.onCountry(it); pickingCountry = false }, onClose = { pickingCountry = false })
        BackHandler { pickingCountry = false }
        return
    }

    StatusBarIcons(dark = true)
    // TalkBack : la phrase du fantôme, les champs et le bouton d'abord, le retour en dernier.
    Column(Modifier.fillMaxSize().background(Color.White).navigationBarsPadding().imePadding().semantics { isTraversalGroup = true }) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp).semantics { traversalIndex = 2f },
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = goBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour", tint = SnapColors.Muted) }
            Text("Connexion", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = SnapColors.Ink, modifier = Modifier.semantics { heading() })
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            val line = GhostLines.login(state, passwordFocused)
            GhostSays(line.text, line.mood, Modifier.padding(top = 8.dp, bottom = 24.dp), ghostSize = 88.dp)
            when (state.mode) {
                LoginMode.PHONE -> {
                    PhoneNumberFields(state.phone, { pickingCountry = true }, vm::onNational)
                    // Comptes créés avec un email, avant la connexion par SMS (ADR-034).
                    Text("Se connecter avec un email", color = SnapColors.ChatBlue, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        modifier = Modifier.padding(top = 20.dp).clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button) { vm.useEmail(true) }.padding(vertical = 6.dp))
                }
                LoginMode.CODE -> {
                    SimulatedSms(state.phone.simulatedCode, vm::useSimulatedCode, Modifier.padding(bottom = 20.dp))
                    PhoneCodeFields(state.phone, vm::onCode, vm::resendCode, enabled = !state.loading)
                }
                LoginMode.EMAIL -> {
                    SignUpField("Email", state.email, vm::onEmail, "ton.email@exemple.fr", keyboardType = KeyboardType.Email)
                    Spacer(Modifier.height(20.dp))
                    SignUpField("Mot de passe", state.password, vm::onPassword, "Ton mot de passe", keyboardType = KeyboardType.Password,
                        password = !reveal, modifier = Modifier.onFocusChanged { passwordFocused = it.isFocused }) {
                        if (state.password.isNotEmpty()) IconButton(onClick = { reveal = !reveal }) {
                            Icon(if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                if (reveal) "Masquer le mot de passe" else "Afficher le mot de passe", tint = SnapColors.Muted)
                        }
                    }
                    Text("Se connecter avec mon numéro", color = SnapColors.ChatBlue, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        modifier = Modifier.padding(top = 20.dp).clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button) { vm.useEmail(false) }.padding(vertical = 6.dp))
                }
            }
        }
        PrimaryButton(when (state.mode) {
            LoginMode.PHONE -> "Recevoir le code par SMS"
            LoginMode.CODE -> "Valider"
            LoginMode.EMAIL -> "Connexion"
        }, vm::submit, enabled = state.canSubmit || state.loading,
            loading = state.loading, modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp), height = 46.dp)
    }
}
