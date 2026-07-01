package com.example.openview.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.openview.tts.TtsController
import com.example.openview.ui.components.PrimaryActionButton
import com.example.openview.ui.components.ScreenScaffold
import com.example.openview.ui.components.ScreenTitle
import com.example.openview.ui.components.SecondaryActionButton

private const val EXPLANATION =
    "OpenView te ayuda a desplazarte dentro del edificio mediante instrucciones " +
    "auditivas paso a paso. Selecciona un destino y la app te guiará hasta llegar."

@Composable
fun OnboardingScreen(
    tts: TtsController,
    onStart: () -> Unit,
    onConfigureVoice: () -> Unit,
    onHelp: () -> Unit,
    onAdmin: () -> Unit,
    onChooseShape: () -> Unit,
    onSimulator: () -> Unit
) {
    ScreenScaffold(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Spacer(Modifier.height(16.dp))
        Icon(
            Icons.Filled.RecordVoiceOver,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp)
        )
        Text(
            "OpenView",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary
        )
        ScreenTitle("Navegación interior accesible")

        Text(
            text = EXPLANATION,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Start,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite }
        )

        Spacer(Modifier.height(8.dp))

        PrimaryActionButton(text = "Iniciar", icon = Icons.Filled.PlayArrow, onClick = onStart)
        SecondaryActionButton(
            text = "Repetir explicación",
            icon = Icons.Filled.RecordVoiceOver,
            onClick = { tts.speak(EXPLANATION) }
        )
        SecondaryActionButton(
            text = "Configurar voz",
            icon = Icons.Filled.Settings,
            onClick = onConfigureVoice
        )
        SecondaryActionButton(
            text = "Ayuda",
            icon = Icons.AutoMirrored.Filled.HelpOutline,
            onClick = onHelp
        )
        SecondaryActionButton(
            text = "Forma de prueba (grafo)",
            icon = Icons.Filled.Timeline,
            onClick = onChooseShape
        )
        SecondaryActionButton(
            text = "Simulador de ruta",
            icon = Icons.Filled.VolumeUp,
            onClick = onSimulator
        )
        SecondaryActionButton(
            text = "Panel BLE (admin)",
            icon = Icons.Filled.Bluetooth,
            onClick = onAdmin
        )
    }
}
