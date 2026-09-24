package com.example.openview.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.example.openview.ui.components.rememberScreenReader

private const val EXPLANATION =
    "OpenView te guía con la voz, pitidos y vibración hasta el lugar que elijas, caminando junto a la pared guía."

/** Se anuncia la pantalla de inicio solo la primera vez que se abre la app (no cada vez que se vuelve a ella). */
private var introSpoken = false

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
    val reader = rememberScreenReader(tts)
    var more by rememberSaveable { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!introSpoken && reader.active) {
            introSpoken = true
            kotlinx.coroutines.delay(500)
            reader.say("OpenView. $EXPLANATION")
            reader.sayQueued("El botón Iniciar ocupa la parte de arriba de la pantalla. Debajo: Configurar voz y Ayuda.")
        }
    }

    ScreenScaffold(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.height(8.dp))
        Icon(Icons.Filled.RecordVoiceOver, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
        Text("OpenView", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
        ScreenTitle("Navegación interior accesible")
        Text(
            text = EXPLANATION, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }
        )

        // El botón principal, muy grande: es lo que busca la persona usuaria.
        PrimaryActionButton(text = "Iniciar", icon = Icons.Filled.PlayArrow, modifier = Modifier.heightIn(min = 120.dp),
            onClick = reader.click("Iniciar", onStart))
        SecondaryActionButton(text = "Configurar voz", icon = Icons.Filled.Settings, onClick = reader.click("Configurar voz", onConfigureVoice))
        SecondaryActionButton(text = "Ayuda", icon = Icons.AutoMirrored.Filled.HelpOutline, onClick = reader.click("Ayuda", onHelp))
        SecondaryActionButton(text = "Repetir explicación", icon = Icons.Filled.RecordVoiceOver, onClick = { tts.speak(EXPLANATION) })

        // Herramientas para quien instala y prueba el sistema: plegadas para no estorbar.
        TextButton(onClick = { more = !more }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(if (more) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
            Text(if (more) "Ocultar opciones de instalación" else "Opciones de instalación y pruebas")
        }
        if (more) {
            SecondaryActionButton(text = "Panel de administración", icon = Icons.Filled.Bluetooth, onClick = onAdmin)
            SecondaryActionButton(text = "Simulador de ruta", icon = Icons.Filled.VolumeUp, onClick = onSimulator)
            SecondaryActionButton(text = "Forma de prueba (grafo)", icon = Icons.Filled.Timeline, onClick = onChooseShape)
        }
    }
}
