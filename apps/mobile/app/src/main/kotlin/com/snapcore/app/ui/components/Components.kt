package com.snapcore.app.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.snapcore.app.ui.theme.SnapColors

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    container: Color = SnapColors.ChatBlue,
    content: Color = Color.White,
    height: Dp = 50.dp,
    textStyle: TextStyle? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = RoundedCornerShape(25.dp),
        // Gris « Disabled » (Button 9:43) seulement quand l'action est indisponible ; pendant le chargement
        // le bouton garde sa couleur et affiche la roue.
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content,
            disabledContainerColor = if (loading && enabled) container else SnapColors.Disabled,
            disabledContentColor = if (loading && enabled) content else Color.White),
        modifier = modifier.fillMaxWidth().height(height),
    ) {
        if (loading) CircularProgressIndicator(color = content, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
        else Text(text, style = textStyle ?: androidx.compose.material3.MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SnapTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    enabled: Boolean = true,
    supporting: String? = null,
) {

    Column(modifier.fillMaxWidth()) {
        // Libellé et champ : composant Input 9:78 de la maquette (libellé Bold 15/18, rayon 8, bordure #C9CED3).
        Text(label, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium.copy(lineHeight = 18.sp), fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(9.dp))

        var focused by remember { mutableStateOf(false) }
        BasicTextField(value, onValueChange, singleLine = true, enabled = enabled,
            textStyle = androidx.compose.material3.MaterialTheme.typography.bodyLarge.copy(color = SnapColors.Ink),
            visualTransformation = if(password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if(password) KeyboardType.Password else keyboardType),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(SnapColors.ChatBlue),
            modifier = Modifier.fillMaxWidth().height(50.dp).onFocusChanged { focused = it.isFocused }
                .border(if(focused) 1.5.dp else 1.dp, if(error != null) SnapColors.SnapRed else if(focused) SnapColors.Grey else SnapColors.Field, RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp),
            decorationBox = { field -> Box(contentAlignment = Alignment.CenterStart) {
                if(value.isEmpty()) Text(label, color = SnapColors.Muted, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
                field()
            } },
        )
        (error ?: supporting)?.let {
            Text(it, color = if (error != null) SnapColors.SnapRed else SnapColors.Grey,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        }
    }

}

// Avatar par défaut généré à l'affichage : initiales sur fond coloré, aucune image stockée (ADR-014).
@Composable
fun Avatar(username: String, photoUrl: String?, size: Dp, modifier: Modifier = Modifier) {
    val palette = listOf(SnapColors.ChatBlue, SnapColors.SnapRed, SnapColors.SnapPurple, Color(0xFF2ECC71), Color(0xFFFF9F1C))
    val background = palette[Math.floorMod(username.hashCode(), palette.size)]
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(size).clip(CircleShape).background(background),
    ) {
        if (photoUrl != null) {
            AsyncImage(model = photoUrl, contentDescription = "Photo de profil", contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        } else {
            Text(
                text = username.take(2).uppercase(),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.38f).sp,
            )
        }
    }
}
