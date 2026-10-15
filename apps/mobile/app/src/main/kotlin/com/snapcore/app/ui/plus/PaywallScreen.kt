package com.snapcore.app.ui.plus

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.snapcore.app.ui.components.FigmaAsset
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snapcore.app.domain.Account
import com.snapcore.app.domain.SnapCorePlus
import com.snapcore.app.ui.components.StatusBarIcons
import com.snapcore.app.ui.containerViewModel
import com.snapcore.app.ui.theme.SnapColors
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH)

// Paywall SnapCore+ (ADR-025). `returnedFromCheckout` : ouvert par le lien de retour du paiement.
@Composable
fun PaywallScreen(returnedFromCheckout: Boolean, onBack: () -> Unit, onAccountChanged: (Account) -> Unit) {
    val vm = containerViewModel { PaywallViewModel(it.accounts, it.subscriptions) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(returnedFromCheckout) { if (returnedFromCheckout) vm.returnedFromCheckout() }
    LaunchedEffect(state.account) { state.account?.let(onAccountChanged) }
    // Retour du navigateur sans passer par le lien de retour (bouton Retour) : on vérifie quand même.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.checkActivation() }
    LaunchedEffect(state.checkoutUrl) {
        val url = state.checkoutUrl ?: return@LaunchedEffect
        vm.consumeCheckoutUrl()
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            // Aucun navigateur : rien à ouvrir, l'attente de l'abonnement n'a pas de sens.
        }
    }

    StatusBarIcons(dark = false)
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF2E2612), Color(0xFF14120C), Color(0xFF0B0A07)))),
    ) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth()) {
                IconButton(onClick = onBack) { FigmaAsset("14-704_imgIconChevronDown", "Retour", tint = Color.White) }
            }
            Spacer(Modifier.height(12.dp))
            FigmaAsset("14-704_imgImageGoldGhost")
            Spacer(Modifier.height(28.dp))
            Text("Découvre SnapCore+", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)

            val account = state.account
            val active = account?.isPremium == true
            Text(
                when {
                    active -> "Merci d'être abonné !"
                    else -> "${SnapCorePlus.PRICE_LABEL} · Sans engagement"
                },
                color = Color(0xFFCFC9BC),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))


            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                val titles = listOf("Relectures illimitées", "Garde tes snaps", "Ton badge SnapCore+")
                val icons = listOf("14-588_imgIconFlip", "14-588_imgIconMemories", "17-1408_imgIconGhost")
                SnapCorePlus.BENEFITS.forEachIndexed { index, benefit ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 74.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2D2A23))
                        .then(if(index == 0) Modifier.border(1.5.dp, SnapColors.Gold, RoundedCornerShape(10.dp)) else Modifier)
                        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(44.dp), Alignment.Center) { FigmaAsset(icons[index], tint = SnapColors.Gold) }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(titles[index], color = Color.White, fontSize = 18.sp)
                            Text(benefit, color = Color(0xFFCFC9BC), fontSize = 14.sp, lineHeight = 18.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))

            when {
                state.loading -> CircularProgressIndicator(color = Color.White)
                active -> {
                    val until = account?.premiumUntil?.atZone(ZoneId.systemDefault())?.format(DATE)
                    Text(
                        if (until != null) "SnapCore+ actif jusqu'au $until" else "SnapCore+ actif",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "Le renouvellement se gère depuis l'email de confirmation du paiement.",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                state.awaitingActivation -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = SnapColors.Yellow)
                    Text("Activation de SnapCore+…", color = Color.White, modifier = Modifier.padding(top = 12.dp))
                }
                else -> {
                    Button(
                        onClick = vm::subscribe,
                        enabled = !state.opening,
                        colors = ButtonDefaults.buttonColors(containerColor = SnapColors.Yellow, contentColor = SnapColors.Black),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        if (state.opening) CircularProgressIndicator(color = SnapColors.Black, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        else Text("S'abonner — ${SnapCorePlus.PRICE_LABEL}", fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "Paiement sécurisé dans le navigateur. Mode test : aucun débit réel.",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            state.error?.let {
                Text(it, color = SnapColors.Yellow, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
            }
        }
    }
}
