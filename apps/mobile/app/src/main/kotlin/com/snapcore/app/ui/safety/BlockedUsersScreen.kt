package com.snapcore.app.ui.safety

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snapcore.app.domain.Friend
import com.snapcore.app.ui.components.Avatar
import com.snapcore.app.ui.components.ChipButton
import com.snapcore.app.ui.components.ChipStyle
import com.snapcore.app.ui.components.SnapCard
import com.snapcore.app.ui.components.SnapHeader
import com.snapcore.app.ui.components.StatusBarIcons
import com.snapcore.app.ui.containerViewModel
import com.snapcore.app.ui.theme.SnapColors

/**
 * Paramètres > Utilisateurs bloqués (ADR-012) : le profil d'un utilisateur bloqué est masqué partout
 * ailleurs, c'est donc le seul endroit d'où le débloquer (BUG-019 d'ADR-023).
 */
@Composable
fun BlockedUsersScreen(onBack: () -> Unit) {
    val vm = containerViewModel { BlockedUsersViewModel(it.safety) }
    val state by vm.state.collectAsStateWithLifecycle()
    var unblocking by remember { mutableStateOf<Friend?>(null) }
    StatusBarIcons(dark = true)

    unblocking?.let { user ->
        ConfirmActionDialog(
            title = "Débloquer ${user.displayName} ?",
            text = "Vous ne serez plus amis : il faudra renvoyer une demande pour vous écrire à nouveau.",
            confirm = "Débloquer", onConfirm = { vm.unblock(user) }, onDismiss = { unblocking = null },
        )
    }

    Column(Modifier.fillMaxSize().background(SnapColors.Background)) {
        SnapHeader("Utilisateurs bloqués", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.error?.let { Text(it, color = SnapColors.SnapRed) }
            when {
                state.loading -> Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() }
                state.users.isEmpty() -> Text("Tu n'as bloqué personne.", color = SnapColors.Grey, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 32.dp))
                else -> SnapCard {
                    state.users.forEach { user ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 66.dp).padding(horizontal = 16.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Avatar(user.displayName, user.pictureUrl, size = 48.dp)
                            Column(Modifier.weight(1f)) {
                                Text(user.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("@${user.username}", style = MaterialTheme.typography.bodySmall, color = SnapColors.Grey,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            ChipButton("Débloquer", { unblocking = user }, style = ChipStyle.Gray)
                        }
                    }
                }
            }
        }
    }
}
