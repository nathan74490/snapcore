package com.snapcore.app.ui.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snapcore.app.BuildConfig
import com.snapcore.app.domain.Session
import com.snapcore.app.ui.components.PrimaryButton
import com.snapcore.app.ui.components.SnapHeader
import com.snapcore.app.ui.components.StatusBarIcons
import com.snapcore.app.ui.containerViewModel
import com.snapcore.app.ui.theme.SnapColors
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.flow.drop
import kotlin.math.abs

// Inscription façon Snapchat (design onboarding, iOS déc. 2024) : « Créer un compte · Étape X sur 5 »,
// un champ par écran avec son libellé au-dessus, bouton bleu grisé tant que l'étape n'est pas remplie
// (ADR-029).

/**
 * @param resuming compte Firebase déjà créé sans compte SnapCore : seules les étapes nom, naissance
 * et pseudo sont demandées, puis l'inscription est rejouée (ADR-015).
 */
@Composable
fun SignUpScreen(onBack: () -> Unit, onSession: (Session) -> Unit, resuming: Boolean = false) {
    val vm = containerViewModel { SignUpViewModel(it.auth, it.accounts, it.pendingRegistration, resuming, analytics = it.analytics) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.session) { state.session?.let(onSession) }
    val goBack = { if (!vm.back() && !resuming) onBack() }
    BackHandler(onBack = goBack)

    StatusBarIcons(dark = true)
    Column(Modifier.fillMaxSize().background(Color.White).navigationBarsPadding().imePadding()) {
        SnapHeader("Créer un compte", goBack, subtitle = "Étape ${state.step} sur ${state.steps}")
        Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(if (state.step == SignUpStep.USERNAME && !state.editingUsername) 0.dp else 72.dp))
            when (state.step) {
                SignUpStep.NAME -> NameStep(state, vm)
                SignUpStep.BIRTHDAY -> BirthdayField(state)
                SignUpStep.USERNAME -> UsernameStep(state, vm, Modifier.weight(1f))
                SignUpStep.PASSWORD -> PasswordStep(state, vm)
                else -> EmailStep(state, vm)
            }
            if (!(state.step == SignUpStep.USERNAME && !state.editingUsername)) Spacer(Modifier.weight(1f))
            state.error?.let { Text(it, color = SnapColors.SnapRed, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) }
            if (state.step == SignUpStep.NAME) LegalNotice()
            PrimaryButton(
                if (state.isLastStep) "Terminer" else "Continuer", vm::next,
                enabled = state.canContinue || state.loading, loading = state.loading,
                modifier = Modifier.padding(top = 12.dp, bottom = 16.dp), height = 46.dp,
            )
        }
        when (state.step) {
            SignUpStep.BIRTHDAY -> BirthdayWheel(state.birthday, vm::onBirthday)
            SignUpStep.EMAIL -> EmailDomains(vm::completeEmail)
            else -> Unit
        }
    }
}

@Composable
private fun NameStep(state: SignUpState, vm: SignUpViewModel) {
    // Reprise d'inscription : on rappelle avec quel compte on est connecté.
    vm.email?.takeIf { state.resuming }?.let {
        Text("Connecté avec $it", color = SnapColors.Grey, fontSize = 13.sp, modifier = Modifier.padding(bottom = 16.dp))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    SignUpField("Prénom", state.firstName, vm::onFirstName, "Prénom", error = state.firstNameError,
        capitalize = true, modifier = Modifier.focusRequester(focus))
    Spacer(Modifier.height(24.dp))
    SignUpField("Nom", state.lastName, vm::onLastName, "Nom (facultatif)", error = state.lastNameError, capitalize = true)
}

// Comme Snapchat : l'accord aux conditions se donne en touchant Continuer, à la première étape.
@Composable
private fun LegalNotice() {
    val cgu = BuildConfig.API_BASE_URL.trimEnd('/') + "/legal/cgu"
    val text = buildAnnotatedString {
        append("En touchant Continuer, tu reconnais avoir lu et accepté les ")
        withLink(LinkAnnotation.Url(cgu, TextLinkStyles(SpanStyle(color = SnapColors.ChatBlue)))) { append("Conditions d'utilisation") }
        append(" de SnapCore.")
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = SnapColors.Grey)
}

// --- Date de naissance --------------------------------------------------------------------------

private val BIRTHDAY_FORMAT = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH)

@Composable
private fun BirthdayField(state: SignUpState) {
    FieldLabel("Date de naissance")
    FieldBox(error = state.birthdayError != null) {
        Text(state.birthday?.format(BIRTHDAY_FORMAT).orEmpty(), fontSize = 17.sp, color = SnapColors.Ink)
    }
    FieldStatus(state.birthdayError, ok = false)
}

/**
 * Roue mois / jour / année en bas de l'écran, comme le sélecteur iOS de Snapchat. La date ne s'inscrit
 * dans le champ qu'au premier mouvement de la roue ; elle part de 18 ans en arrière.
 */
@Composable
private fun BirthdayWheel(value: LocalDate?, onChange: (LocalDate) -> Unit) {
    val today = remember { LocalDate.now() }
    val start = value ?: remember { today.minusYears(18) }
    var month by remember { mutableIntStateOf(start.monthValue) }
    var day by remember { mutableIntStateOf(start.dayOfMonth) }
    var year by remember { mutableIntStateOf(start.year) }
    val years = remember { (today.year - 100..today.year).toList() }
    val months = remember { (1..12).map { java.time.Month.of(it).getDisplayName(TextStyle.FULL_STANDALONE, Locale.FRENCH).replaceFirstChar(Char::uppercase) } }
    val daysInMonth = YearMonth.of(year, month).lengthOfMonth()

    fun emit(m: Int = month, d: Int = day, y: Int = year) {
        val clamped = d.coerceAtMost(YearMonth.of(y, m).lengthOfMonth())
        month = m; day = clamped; year = y
        onChange(LocalDate.of(y, m, clamped))
    }

    Box(Modifier.fillMaxWidth().height(WHEEL_ITEM * WHEEL_VISIBLE).padding(horizontal = 8.dp)) {
        Box(Modifier.align(Alignment.Center).fillMaxWidth().height(WHEEL_ITEM).background(Color(0xFFF2F2F7), RoundedCornerShape(8.dp)))
        Row(Modifier.fillMaxSize()) {
            Wheel(months, month - 1, { emit(m = it + 1) }, Modifier.weight(1.6f))
            Wheel((1..daysInMonth).map(Int::toString), day - 1, { emit(d = it + 1) }, Modifier.weight(.8f))
            Wheel(years.map(Int::toString), years.indexOf(year), { emit(y = years[it]) }, Modifier.weight(1f))
        }
    }
}

private val WHEEL_ITEM = 36.dp
private const val WHEEL_VISIBLE = 5

@Composable
private fun Wheel(items: List<String>, selected: Int, onSelected: (Int) -> Unit, modifier: Modifier) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = selected.coerceIn(0, items.lastIndex))
    val latest by rememberUpdatedState(onSelected)
    // Fin du défilement : l'élément au centre est choisi (la roue s'y cale grâce au snapping).
    LaunchedEffect(list) {
        // drop(1) : l'état initial (immobile) ne compte pas, seule la roue tournée par l'utilisateur choisit.
        snapshotFlow { list.isScrollInProgress }.drop(1).collect { scrolling ->
            if (!scrolling) latest(list.firstVisibleItemIndex.coerceIn(0, items.lastIndex))
        }
    }
    // Choix imposé de l'extérieur (jour ramené au dernier du mois) : la roue suit.
    LaunchedEffect(selected, items.size) {
        if (!list.isScrollInProgress && list.firstVisibleItemIndex != selected) list.scrollToItem(selected.coerceIn(0, items.lastIndex))
    }
    LazyColumn(
        modifier.fillMaxHeight(),
        state = list,
        flingBehavior = rememberSnapFlingBehavior(list),
        contentPadding = PaddingValues(vertical = WHEEL_ITEM * (WHEEL_VISIBLE / 2)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        itemsIndexed(items) { index, label ->
            val distance = abs(index - list.firstVisibleItemIndex)
            Box(Modifier.height(WHEEL_ITEM).fillMaxWidth().graphicsLayer { alpha = (1f - distance * .28f).coerceAtLeast(.15f) }, Alignment.Center) {
                Text(label, fontSize = if (distance == 0) 21.sp else 18.sp, color = SnapColors.Ink, maxLines = 1)
            }
        }
    }
}

// --- Pseudo -------------------------------------------------------------------------------------

@Composable
private fun UsernameStep(state: SignUpState, vm: SignUpViewModel, modifier: Modifier) {
    if (!state.editingUsername) {
        // Pseudo proposé d'office, modifiable d'un appui (Snapchat : « Your username is… »).
        Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("Ton pseudo est", color = SnapColors.Grey, fontSize = 15.sp)
            Text(state.username, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = SnapColors.Ink, modifier = Modifier.padding(vertical = 6.dp))
            Text("Changer mon pseudo", color = SnapColors.ChatBlue, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                modifier = Modifier.clickable(onClick = vm::editUsername).padding(6.dp))
            Text("Tu pourras le changer plus tard dans les paramètres", color = SnapColors.Muted, fontSize = 13.sp)
        }
        return
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    SignUpField("Pseudo", state.username, vm::onUsername, "Pseudo", modifier = Modifier.focusRequester(focus),
        error = (state.usernameStatus as? UsernameStatus.Unavailable)?.reason) {
        IconButton(onClick = vm::suggestAnother) { Icon(Icons.Filled.Refresh, "Proposer un autre pseudo", tint = SnapColors.Muted) }
    }
    when (val status = state.usernameStatus) {
        UsernameStatus.Available -> FieldStatus("Pseudo disponible", ok = true)
        UsernameStatus.Checking -> Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = SnapColors.Muted)
            Text("Vérification…", fontSize = 13.sp, color = SnapColors.Grey, modifier = Modifier.padding(start = 6.dp))
        }
        is UsernameStatus.Unavailable -> FieldStatus(status.reason, ok = false)
        UsernameStatus.Unverified -> Unit
    }
}

// --- Mot de passe -------------------------------------------------------------------------------

@Composable
private fun PasswordStep(state: SignUpState, vm: SignUpViewModel) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    var reveal by remember { mutableStateOf(false) }
    SignUpField("Choisis un mot de passe", state.password, vm::onPassword, "Ton mot de passe",
        keyboardType = KeyboardType.Password, password = !reveal, error = if (state.passwordHint == PasswordHint.TOO_SHORT) "" else null,
        modifier = Modifier.focusRequester(focus)) {
        if (state.password.isNotEmpty()) IconButton(onClick = { reveal = !reveal }) {
            Icon(if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                if (reveal) "Masquer le mot de passe" else "Afficher le mot de passe", tint = SnapColors.Muted)
        }
    }
    when (state.passwordHint) {
        PasswordHint.EMPTY -> FieldStatus("Ton mot de passe doit contenir au moins 8 caractères", ok = null)
        PasswordHint.TOO_SHORT -> FieldStatus("Ton mot de passe doit contenir au moins 8 caractères.", ok = false)
        PasswordHint.OK -> FieldStatus("Mot de passe correct", ok = true)
    }
}

// --- Email --------------------------------------------------------------------------------------

@Composable
private fun EmailStep(state: SignUpState, vm: SignUpViewModel) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    SignUpField("Email", state.email, vm::onEmail, "", keyboardType = KeyboardType.Email, error = state.emailError,
        modifier = Modifier.focusRequester(focus))
    Text("Un email de confirmation te sera envoyé.", fontSize = 13.sp, color = SnapColors.Grey, modifier = Modifier.padding(top = 8.dp))
}

/** Raccourcis de domaine au-dessus du clavier, comme les suggestions du clavier iOS de Snapchat. */
@Composable
private fun EmailDomains(onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EMAIL_DOMAINS.forEach { domain ->
            Text(domain, fontSize = 14.sp, color = SnapColors.Ink,
                modifier = Modifier.background(SnapColors.Control, RoundedCornerShape(8.dp)).clickable { onPick(domain) }
                    .padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}

// --- Champ façon Snapchat -----------------------------------------------------------------------

@Composable
fun FieldLabel(text: String) {
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = SnapColors.Ink, modifier = Modifier.padding(bottom = 8.dp))
}

/** Cadre arrondi gris, noir à la saisie, rouge en erreur. */
@Composable
fun FieldBox(error: Boolean = false, focused: Boolean = false, height: Dp = 48.dp, content: @Composable RowScope.() -> Unit) {
    val border = when {
        error -> SnapColors.SnapRed.copy(alpha = .6f)
        focused -> SnapColors.Grey
        else -> SnapColors.Field
    }
    Row(Modifier.fillMaxWidth().height(height).border(1.dp, border, RoundedCornerShape(8.dp)).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
fun SignUpField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    capitalize: Boolean = false,
    trailing: @Composable () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    // Curseur en fin de texte, y compris quand le texte change de l'extérieur (pseudo proposé, domaine email).
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    if (field.text != value) field = TextFieldValue(value, TextRange(value.length))
    FieldLabel(label)
    FieldBox(error = error != null, focused = focused) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, fontSize = 17.sp, color = SnapColors.Muted)
            BasicTextField(
                field, { field = it; if (it.text != value) onChange(it.text) }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, color = SnapColors.Ink),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType,
                    capitalization = if (capitalize) KeyboardCapitalization.Words else KeyboardCapitalization.None, autoCorrectEnabled = false),
                cursorBrush = SolidColor(SnapColors.ChatBlue),
                modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            )
        }
        trailing()
    }
    if (!error.isNullOrEmpty() && label != "Pseudo") FieldStatus(error, ok = false)
}

/** Ligne d'état sous un champ : coche verte, point d'exclamation rouge, ou consigne grise (ok = null). */
@Composable
fun FieldStatus(text: String?, ok: Boolean?) {
    if (text.isNullOrEmpty()) return
    val color = when (ok) {
        true -> Color(0xFF2ECC71)
        false -> SnapColors.SnapRed
        null -> SnapColors.Muted
    }
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (ok == false) Icons.Filled.Error else Icons.Filled.CheckCircle, null, tint = color, modifier = Modifier.size(15.dp))
        Text(text, fontSize = 13.sp, color = if (ok == false) SnapColors.SnapRed else SnapColors.Grey, modifier = Modifier.padding(start = 6.dp))
    }
}
