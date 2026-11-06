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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import com.example.openview.data.PlaceStore
import com.example.openview.share.ShareHub
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
    onRoutes: () -> Unit,
    onGuard: () -> Unit,
    onChooseShape: () -> Unit,
    onSimulator: () -> Unit
) {
    val reader = rememberScreenReader(tts)
    var more by rememberSaveable { mutableStateOf(false) }
    val hub by ShareHub.state.collectAsState()
    val activeId by PlaceStore.activeId.collectAsState()
    val activeName = PlaceStore.places.collectAsState().value.firstOrNull { it.place.id == activeId }?.place?.name
    // Escenario 1: un guardia cercano anuncia rutas que aún no tengo.
    val offer = hub.offers.firstOrNull { !PlaceStore.hasHash(it.hash) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!introSpoken && reader.active) {
            introSpoken = true
            kotlinx.coroutines.delay(500)
            reader.say("OpenView. $EXPLANATION")
            reader.sayQueued("El botón Iniciar ocupa la parte de arriba de la pantalla. Debajo: Rutas del lugar, Configurar voz y Ayuda.")
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

        if (offer != null) {
            Card(
                onClick = reader.click("Rutas disponibles", onRoutes),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(18.dp)) {
                    Icon(Icons.Filled.Storefront, null, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Rutas disponibles en", style = MaterialTheme.typography.titleMedium)
                        Text(offer.name ?: "un establecimiento cercano", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("Toca para descargarlas y usarlas.", style = MaterialTheme.typography.bodyLarge)
                    }
                    Icon(Icons.Filled.Download, null, modifier = Modifier.size(32.dp))
                }
            }
        }
        if (activeName != null) Text("Lugar: $activeName", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())

        // El botón principal, muy grande: es lo que busca la persona usuaria.
        PrimaryActionButton(text = "Iniciar", icon = Icons.Filled.PlayArrow, modifier = Modifier.heightIn(min = 120.dp),
            onClick = reader.click("Iniciar", onStart))
        SecondaryActionButton(text = "Rutas del lugar", icon = Icons.Filled.Download, onClick = reader.click("Rutas del lugar", onRoutes))
        SecondaryActionButton(text = "Configurar voz", icon = Icons.Filled.Settings, onClick = reader.click("Configurar voz", onConfigureVoice))
        SecondaryActionButton(text = "Ayuda", icon = Icons.AutoMirrored.Filled.HelpOutline, onClick = reader.click("Ayuda", onHelp))
        SecondaryActionButton(text = "Repetir explicación", icon = Icons.Filled.RecordVoiceOver, onClick = { tts.speak(EXPLANATION) })

        // Herramientas para quien instala y prueba el sistema: plegadas para no estorbar.
        TextButton(onClick = { more = !more }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(if (more) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
            Text(if (more) "Ocultar opciones de instalación" else "Opciones de instalación y pruebas")
        }
        if (more) {
            SecondaryActionButton(text = "Modo guardia (compartir rutas)", icon = Icons.Filled.Security, onClick = onGuard)
            SecondaryActionButton(text = "Panel de administración", icon = Icons.Filled.Bluetooth, onClick = onAdmin)
            SecondaryActionButton(text = "Simulador de ruta", icon = Icons.Filled.VolumeUp, onClick = onSimulator)
            SecondaryActionButton(text = "Forma de prueba (grafo)", icon = Icons.Filled.Timeline, onClick = onChooseShape)
        }
    }
}
