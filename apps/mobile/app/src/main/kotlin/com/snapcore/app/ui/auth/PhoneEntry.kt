package com.snapcore.app.ui.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snapcore.app.domain.Countries
import com.snapcore.app.domain.Country
import com.snapcore.app.domain.PhoneCodeSent
import com.snapcore.app.ui.components.SnapSearchBar
import com.snapcore.app.ui.components.rememberScreenReaderOn
import com.snapcore.app.ui.theme.SnapColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Numéro de téléphone et code reçu par SMS (ADR-034), partagés par l'inscription et la connexion.

const val PHONE_CODE_LENGTH = 6

data class PhoneEntry(
    val country: Country,
    val national: String = "",
    val numberError: String? = null,
    /** Numéro auquel le dernier code a été envoyé (format international). */
    val sentTo: String? = null,
    val code: String = "",
    val codeError: String? = null,
    /** Code renvoyé par l'opérateur SMS fictif : montré comme un SMS reçu. */
    val simulatedCode: String? = null,
    /** Secondes à attendre avant de pouvoir redemander un code, à partir de l'envoi. */
    val resendInSeconds: Int = 0,
    /** Incrémenté à chaque envoi, pour relancer le compte à rebours. */
    val sendCount: Int = 0,
) {
    val international get() = country.international(national)
    val numberComplete get() = national.count(Char::isDigit) >= 6
    val codeComplete get() = code.length == PHONE_CODE_LENGTH

    fun withCountry(country: Country) = copy(country = country, numberError = null)
    fun withNational(value: String) = copy(national = value.filter { it.isDigit() || it == ' ' }, numberError = null)
    fun withCode(value: String) = copy(code = value.filter(Char::isDigit).take(PHONE_CODE_LENGTH), codeError = null)
    fun codeSent(sent: PhoneCodeSent) = copy(sentTo = sent.phoneNumber, code = "", codeError = null, numberError = null,
        simulatedCode = sent.simulatedCode, resendInSeconds = sent.resendInSeconds, sendCount = sendCount + 1)
}

/** Numéro lisible : « +33 6 12 34 56 78 ». */
fun formatPhone(international: String): String {
    val country = Countries.all().filter { international.startsWith("+" + it.dialCode) }.maxByOrNull { it.dialCode.length }
        ?: return international
    val national = international.removePrefix("+" + country.dialCode)
    val grouped = if (country.dialCode == "33" && national.length == 9) national.take(1) + " " + national.drop(1).chunked(2).joinToString(" ")
        else national.chunked(3).joinToString(" ")
    return "+${country.dialCode} $grouped"
}

/** Pays (bouton qui ouvre la liste) et numéro national. */
@Composable
fun PhoneNumberFields(entry: PhoneEntry, onPickCountry: () -> Unit, onNational: (String) -> Unit, autoFocus: Boolean = true) {
    val focus = remember { FocusRequester() }
    val screenReader = rememberScreenReaderOn()
    LaunchedEffect(Unit) { if (autoFocus && !screenReader) runCatching { focus.requestFocus() } }
    FieldLabel("Pays")
    Box(Modifier.clickable(role = Role.Button, onClick = onPickCountry).clearAndSetSemantics {
        contentDescription = "Pays : ${entry.country.name}, indicatif +${entry.country.dialCode}"
        role = Role.Button
        onClick("changer de pays") { onPickCountry(); true }
    }) {
        FieldBox {
            Text("${entry.country.flag}  ${entry.country.name}", fontSize = 17.sp, color = SnapColors.Ink, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.KeyboardArrowDown, null, tint = SnapColors.Muted, modifier = Modifier.padding(end = 12.dp))
        }
    }
    Spacer(Modifier.height(20.dp))
    FieldLabel("Numéro de téléphone")
    FieldBox(error = entry.numberError != null) {
        Text("+${entry.country.dialCode}", fontSize = 17.sp, color = SnapColors.ChatBlue, modifier = Modifier.clearAndSetSemantics {})
        Box(Modifier.padding(horizontal = 14.dp).width(1.dp).height(24.dp).background(SnapColors.Hairline))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (entry.national.isEmpty()) Text("6 12 34 56 78", fontSize = 17.sp, color = SnapColors.Muted, modifier = Modifier.clearAndSetSemantics {})
            BasicTextField(entry.national, onNational, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, color = SnapColors.Ink),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), cursorBrush = SolidColor(SnapColors.ChatBlue),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics {
                    contentDescription = "Numéro de téléphone, sans l'indicatif +${entry.country.dialCode}"
                    entry.numberError?.let { message -> error(message) }
                })
        }
    }
    FieldStatus(entry.numberError, ok = false)
}

/**
 * Six cases pour le code, un seul champ en réalité (le clavier numérique et la saisie automatique marchent).
 * En dessous : « Renvoyer le code », avec un compte à rebours.
 */
@Composable
fun PhoneCodeFields(entry: PhoneEntry, onCode: (String) -> Unit, onResend: () -> Unit, enabled: Boolean = true) {
    val focus = remember { FocusRequester() }
    val screenReader = rememberScreenReaderOn()
    LaunchedEffect(entry.sendCount) { if (!screenReader) runCatching { focus.requestFocus() } }
    BasicTextField(entry.code, onCode, singleLine = true, enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics {
            contentDescription = "Code à ${PHONE_CODE_LENGTH} chiffres reçu par SMS"
            entry.codeError?.let { message -> error(message) }
        },
        decorationBox = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(PHONE_CODE_LENGTH) { index ->
                    val digit = entry.code.getOrNull(index)?.toString().orEmpty()
                    val current = index == entry.code.length
                    val border = when {
                        entry.codeError != null -> SnapColors.SnapRed.copy(alpha = .6f)
                        current -> SnapColors.ChatBlue
                        else -> SnapColors.Field
                    }
                    Box(Modifier.weight(1f).height(56.dp).border(if (current) 2.dp else 1.dp, border, RoundedCornerShape(10.dp)),
                        Alignment.Center) {
                        Text(digit, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = SnapColors.Ink)
                    }
                }
            }
        })
    FieldStatus(entry.codeError, ok = false)
    // Compte à rebours avant de pouvoir redemander un code.
    var wait by remember(entry.sendCount) { mutableIntStateOf(entry.resendInSeconds) }
    LaunchedEffect(entry.sendCount) { while (wait > 0) { delay(1_000); wait-- } }
    Text(if (wait > 0) "Renvoyer le code dans $wait s" else "Renvoyer le code",
        color = if (wait > 0) SnapColors.Muted else SnapColors.ChatBlue, fontWeight = FontWeight.Bold, fontSize = 14.sp,
        modifier = Modifier.padding(top = 14.dp).clip(RoundedCornerShape(8.dp))
            .clickable(enabled = wait == 0 && enabled, role = Role.Button, onClick = onResend).padding(vertical = 6.dp))
}

/**
 * SMS simulé (ADR-034) : aucun vrai SMS ne part, le code arrive dans une bannière qui imite une notification
 * de l'app Messages. « Utiliser le code » le remplit, comme la saisie automatique d'Android.
 */
@Composable
fun SimulatedSms(code: String?, onUse: () -> Unit, modifier: Modifier = Modifier) {
    var shown by remember(code) { mutableStateOf(false) }
    LaunchedEffect(code) { if (code != null) { delay(700); shown = true } }
    AnimatedVisibility(code != null && shown, modifier, enter = slideInVertically { -it } + fadeIn()) {
        Column(Modifier.fillMaxWidth().shadow(10.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).background(Color.White)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2ECC71)), Alignment.Center) {
                    Text("✉", color = Color.White, fontSize = 12.sp, modifier = Modifier.clearAndSetSemantics {})
                }
                Text("Messages · SMS simulé", fontSize = 13.sp, color = SnapColors.Grey, modifier = Modifier.padding(start = 8.dp).weight(1f))
                Text("maintenant", fontSize = 12.sp, color = SnapColors.Muted)
            }
            Text("SnapCore", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = SnapColors.Ink, modifier = Modifier.padding(top = 8.dp))
            Text("Ton code de connexion est ${code.orEmpty()}. Il expire dans 5 minutes. Ne le partage avec personne.",
                fontSize = 14.sp, color = SnapColors.Ink)
            Text("Utiliser le code", color = SnapColors.ChatBlue, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onUse)
                    .padding(vertical = 6.dp))
        }
    }
}

/** Liste des pays avec recherche et index alphabétique (« Country or Region » de Snapchat). */
@Composable
fun CountryPicker(onPick: (Country) -> Unit, onClose: () -> Unit) {
    val all = remember { Countries.all() }
    var query by remember { mutableStateOf("") }
    val shown = remember(query) { all.filter { it.name.contains(query, ignoreCase = true) || it.dialCode.startsWith(query.trimStart('+')) } }
    val groups = remember(shown) { shown.groupBy { it.name.first().uppercaseChar().let { c -> java.text.Normalizer.normalize("$c", java.text.Normalizer.Form.NFD).first() } } }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Position de chaque lettre dans la liste (en-tête + pays), pour l'index de droite.
    val headerIndex = remember(groups) {
        var position = 0
        groups.mapValues { (_, countries) -> position.also { position += countries.size + 1 } }
    }
    Column(Modifier.fillMaxSize().background(SnapColors.Background).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Box(Modifier.fillMaxWidth().height(56.dp)) {
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart)) { Icon(Icons.Filled.KeyboardArrowDown, "Fermer", tint = SnapColors.Ink) }
            Text("Pays ou région", style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                modifier = Modifier.align(Alignment.Center).semantics { heading() })
        }
        SnapSearchBar(query, { query = it }, "Rechercher un pays", Modifier.fillMaxWidth().padding(horizontal = 12.dp))
        Row(Modifier.weight(1f)) {
            LazyColumn(Modifier.weight(1f).padding(start = 12.dp, top = 8.dp), state = list) {
                groups.forEach { (letter, countries) ->
                    item(key = "h$letter") { Text("$letter", fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp).semantics { heading() }) }
                    items(countries, key = { it.code }) { country ->
                        Row(Modifier.fillMaxWidth().background(Color.White).clickable(role = Role.Button) { onPick(country) }
                            .clearAndSetSemantics {
                                contentDescription = "${country.name}, +${country.dialCode}"
                                role = Role.Button
                                onClick { onPick(country); true }
                            }.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("${country.flag}  ${country.name}", fontSize = 16.sp, modifier = Modifier.weight(1f))
                            Text("+${country.dialCode}", color = SnapColors.Muted, fontSize = 14.sp)
                        }
                    }
                }
            }
            Column(Modifier.fillMaxHeight().padding(horizontal = 4.dp).clearAndSetSemantics {}, verticalArrangement = Arrangement.Center) {
                headerIndex.forEach { (letter, index) ->
                    Text("$letter", fontSize = 11.sp, color = SnapColors.Grey,
                        modifier = Modifier.clickable { scope.launch { list.scrollToItem(index) } }.padding(horizontal = 4.dp, vertical = 1.dp))
                }
            }
        }
    }
}

