package com.snapcore.app.ui.safety

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.snapcore.app.domain.ReportReason
import com.snapcore.app.ui.theme.SnapColors

/** Confirmation d'une action qu'on ne peut pas défaire d'un geste (retirer un ami, bloquer, supprimer). */
@Composable
fun ConfirmActionDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm, color = SnapColors.SnapRed, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/** Choix du motif d'un signalement (ADR-021) ; [subject] décrit ce qui est signalé (« Léa », « ce message »…). */
@Composable
fun ReportDialog(subject: String, onReport: (ReportReason) -> Unit, onDismiss: () -> Unit) {
    var reason by remember { mutableStateOf<ReportReason?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Signaler $subject") },
        text = {
            Column {
                Text("Pourquoi ? Ton signalement est anonyme et sera examiné par la modération.", color = SnapColors.Grey)
                ReportReason.entries.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().clickable { reason = option }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = reason == option, onClick = { reason = option },
                            colors = RadioButtonDefaults.colors(selectedColor = SnapColors.ChatBlue))
                        Text(option.label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { reason?.let { onDismiss(); onReport(it) } }, enabled = reason != null) {
                Text("Signaler", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/** Message affiché une fois l'action faite. */
fun SafetyOutcome.message(name: String) = when (this) {
    SafetyOutcome.REMOVED -> "$name ne fait plus partie de tes amis"
    SafetyOutcome.BLOCKED -> "Tu as bloqué $name"
    SafetyOutcome.REPORTED -> "Merci, ton signalement a été envoyé à la modération"
}
