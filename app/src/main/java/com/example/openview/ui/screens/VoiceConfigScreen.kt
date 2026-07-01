package com.example.openview.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.openview.tts.TtsController
import com.example.openview.ui.NavigationUiState
import com.example.openview.ui.components.PrimaryActionButton
import com.example.openview.ui.components.ScreenScaffold
import com.example.openview.ui.components.ScreenTitle
import com.example.openview.ui.components.SecondaryActionButton
import kotlin.math.roundToInt

private const val VOICE_SAMPLE = "Avanza 8 metros y gira a la derecha."

@Composable
fun VoiceConfigScreen(
    state: NavigationUiState,
    tts: TtsController,
    onSpeedChange: (Float) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onTestVoice: () -> Unit,
    onDismissTestMessage: () -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit
) {
    val settings = state.voiceSettings

    ScreenScaffold(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Spacer(Modifier.height(8.dp))
        ScreenTitle("Configurar voz")

        SliderSetting(
            label = "Velocidad de voz",
            value = settings.speed,
            valueText = "${(settings.speed * 100).roundToInt()} %",
            onChange = onSpeedChange
        )

        SliderSetting(
            label = "Volumen",
            value = settings.volume,
            valueText = "${(settings.volume * 100).roundToInt()} %",
            onChange = onVolumeChange
        )

        if (state.lastVoiceTestMessage != null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = state.lastVoiceTestMessage,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .padding(16.dp)
                        .semantics { liveRegion = LiveRegionMode.Assertive }
                )
            }
        }

        PrimaryActionButton(
            text = "Probar voz",
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            onClick = {
                onTestVoice()
                tts.speak(VOICE_SAMPLE)
            }
        )
        PrimaryActionButton(
            text = "Guardar configuración",
            icon = Icons.Filled.Save,
            containerColor = MaterialTheme.colorScheme.tertiary,
            contentColor = MaterialTheme.colorScheme.onTertiary,
            onClick = {
                onDismissTestMessage()
                onSave()
            }
        )
        SecondaryActionButton(text = "Volver", icon = Icons.AutoMirrored.Filled.ArrowBack, onClick = onBack)
    }
}

@Composable
private fun SliderSetting(
    label: String,
    value: Float,
    valueText: String,
    onChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "$label: $valueText",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Slider(
            value = value,
            onValueChange = onChange,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "$label, $valueText" }
        )
    }
}
