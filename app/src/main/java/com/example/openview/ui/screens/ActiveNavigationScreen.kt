package com.example.openview.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WrongLocation
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.openview.data.BleSignal
import com.example.openview.tts.TtsController
import com.example.openview.ui.NavigationUiState
import com.example.openview.ui.components.PrimaryActionButton
import com.example.openview.ui.components.SecondaryActionButton

@Composable
fun ActiveNavigationScreen(
    state: NavigationUiState,
    tts: TtsController,
    onNextStep: () -> Unit,
    onTogglePause: () -> Unit,
    onArrived: () -> Unit,
    onDeviated: () -> Unit,
    onCancel: () -> Unit
) {
    val step = state.currentStep

    // Reproduce la instrucción actual con TTS cada vez que cambia el paso.
    LaunchedEffect(state.currentStepIndex, state.isPaused) {
        if (!state.isPaused) step?.instruction?.let { tts.speak(it) }
    }

    // Avisa por voz cuando se pierde la señal por completo.
    LaunchedEffect(state.bleSignal) {
        if (state.bleSignal == BleSignal.LOST) {
            tts.speak("Señal perdida. Acércate a la baliza más cercana.")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Estado superior: progreso + señal BLE
        LinearProgressIndicator(
            progress = { state.progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
        )

        BleStatusChip(signal = state.bleSignal, onClick = null)

        if (state.isSimulationMode) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                        .semantics(mergeDescendants = true) {}
                ) {
                    Icon(Icons.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Simulación activa: presiona volumen arriba para avanzar al siguiente nodo.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        state.detectedBeaconName?.let { beacon ->
            Text(
                text = if (state.isSimulationMode) "Nodo simulado: $beacon" else "Detectado: $beacon",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Aviso no bloqueante cuando se pierde la señal por completo.
        if (state.bleSignal == BleSignal.LOST) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Señal perdida. Acércate a la baliza más cercana para continuar.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .semantics { liveRegion = LiveRegionMode.Assertive }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Instrucción principal grande
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (state.isPaused) "Navegación en pausa" else (step?.instruction ?: "—"),
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp)
                    .semantics { liveRegion = LiveRegionMode.Assertive }
            )
        }

        Text(
            text = "Próximo punto: ${step?.nextPoint ?: "—"}",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        // Acciones
        PrimaryActionButton(
            text = "Repetir instrucción",
            icon = Icons.Filled.Replay,
            onClick = { step?.instruction?.let { tts.speak(it) } }
        )
        if (!state.isLastStep) {
            PrimaryActionButton(
                text = if (state.isSimulationMode) "Siguiente nodo" else "Siguiente indicación",
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
                onClick = onNextStep
            )
        } else {
            PrimaryActionButton(
                text = "He llegado",
                icon = Icons.Filled.Check,
                containerColor = MaterialTheme.colorScheme.tertiary,
                contentColor = MaterialTheme.colorScheme.onTertiary,
                onClick = onArrived
            )
        }

        SecondaryActionButton(
            text = if (state.isPaused) "Reanudar" else "Pausar",
            icon = if (state.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            onClick = onTogglePause
        )
        SecondaryActionButton(
            text = "He perdido el camino",
            icon = Icons.Filled.WrongLocation,
            onClick = onDeviated
        )
        SecondaryActionButton(text = "Cancelar ruta", icon = Icons.Filled.Close, onClick = onCancel)
    }
}

@Composable
private fun BleStatusChip(signal: BleSignal, onClick: (() -> Unit)?) {
    val (bg, fg) = when (signal) {
        BleSignal.STABLE -> MaterialTheme.colorScheme.tertiary to MaterialTheme.colorScheme.onTertiary
        BleSignal.WEAK -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        BleSignal.LOST -> MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.onError
    }
    val icon = when (signal) {
        BleSignal.STABLE -> Icons.Filled.BluetoothConnected
        BleSignal.WEAK -> Icons.AutoMirrored.Filled.BluetoothSearching
        BleSignal.LOST -> Icons.Filled.BluetoothDisabled
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = bg, contentColor = fg),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Estado BLE: ${signal.label}",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = fg
                )
            }
        }
    }
}
