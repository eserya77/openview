package com.example.openview.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.example.openview.guide.Haptics
import com.example.openview.guide.SonarPlayer
import com.example.openview.tts.TtsController
import com.example.openview.ui.NavigationUiState
import com.example.openview.ui.components.AnnounceOnEnter
import com.example.openview.ui.components.PrimaryActionButton
import com.example.openview.ui.components.ScreenScaffold
import com.example.openview.ui.components.ScreenTitle
import com.example.openview.ui.components.SecondaryActionButton
import com.example.openview.ui.components.rememberScreenReader
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val VOICE_SAMPLE = "Baliza 2. Sigue: Baliza 3 a 2,6 metros."

private fun pct(v: Float) = "${(v * 100).roundToInt()} por ciento"
private fun onOff(b: Boolean) = if (b) "activados" else "desactivados"

/**
 * Configuración de voz y avisos. Pensada para usarse sin ver la pantalla: al entrar dice los valores actuales, cada
 * cambio se anuncia en voz alta (con la voz ya cambiada, para oír el efecto) y los interruptores dicen si quedan
 * activados o no. Con TalkBack activado, lo lee TalkBack.
 */
@Composable
fun VoiceConfigScreen(
    state: NavigationUiState,
    tts: TtsController,
    onSpeedChange: (Float) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onBeepsChange: (Boolean) -> Unit,
    onVibrationChange: (Boolean) -> Unit,
    onTestVoice: () -> Unit,
    onDismissTestMessage: () -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit
) {
    val settings = state.voiceSettings
    val reader = rememberScreenReader(tts)
    val context = LocalContext.current
    val haptics = remember { Haptics(context.applicationContext) }
    val sonar = remember { SonarPlayer() }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { sonar.release() } }
    // Valores al entrar: sus cambios se anuncian, el valor inicial no (ya lo dice el anuncio de la pantalla).
    val initialSpeed = remember { settings.speed }
    val initialVolume = remember { settings.volume }

    AnnounceOnEnter(
        reader,
        "Configurar voz.",
        "Velocidad: ${pct(settings.speed)}. Volumen: ${pct(settings.volume)}.",
        "Pitidos ${onOff(settings.beeps)}. Vibración ${if (settings.vibration) "activada" else "desactivada"}.",
        "De arriba abajo: velocidad, volumen, pitidos, vibración, probar voz, guardar y volver."
    )

    // Anuncia el valor cuando dejas de mover el control (no en cada paso).
    LaunchedEffect(settings.speed) { if (settings.speed != initialSpeed) { delay(450); reader.say("Velocidad ${pct(settings.speed)}. $VOICE_SAMPLE") } }
    LaunchedEffect(settings.volume) { if (settings.volume != initialVolume) { delay(450); reader.say("Volumen ${pct(settings.volume)}") } }

    ScreenScaffold(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.height(8.dp))
        ScreenTitle("Configurar voz")

        SliderSetting("Velocidad de voz", settings.speed, pct(settings.speed), onSpeedChange)
        SliderSetting("Volumen", settings.volume, pct(settings.volume), onVolumeChange)

        ToggleSetting(
            "Pitidos de cercanía", "Más rápidos y agudos al acercarte al siguiente punto.", settings.beeps
        ) { on ->
            onBeepsChange(on)
            reader.say("Pitidos ${onOff(on)}")
            if (on) sonar.beep(0.8)
        }
        ToggleSetting(
            "Vibración", "Corta en cada punto, triple al llegar, larga para corregir.", settings.vibration
        ) { on ->
            onVibrationChange(on)
            reader.say("Vibración ${if (on) "activada" else "desactivada"}")
            if (on) haptics.milestone()
        }

        if (state.lastVoiceTestMessage != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = state.lastVoiceTestMessage,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
        }

        PrimaryActionButton(
            text = "Probar voz",
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            onClick = { onTestVoice(); tts.speak(VOICE_SAMPLE) }
        )
        PrimaryActionButton(
            text = "Guardar configuración",
            icon = Icons.Filled.Save,
            containerColor = MaterialTheme.colorScheme.tertiary,
            contentColor = MaterialTheme.colorScheme.onTertiary,
            onClick = { onDismissTestMessage(); tts.speak("Configuración guardada."); onSave() }
        )
        SecondaryActionButton(text = "Volver", icon = Icons.AutoMirrored.Filled.ArrowBack, onClick = reader.click("Volver", onBack))
    }
}

@Composable
private fun SliderSetting(label: String, value: Float, valueText: String, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text("$label: $valueText", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        Slider(
            value = value,
            onValueChange = onChange,
            steps = 9,   // pasos de 10 %: más fácil de manejar con TalkBack (subir/bajar volumen)
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { contentDescription = label; stateDescription = valueText }
        )
    }
}

@Composable
private fun ToggleSetting(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth().heightIn(min = 72.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .semantics(mergeDescendants = true) { stateDescription = if (checked) "activado" else "desactivado" }
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}
