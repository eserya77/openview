package com.example.openview.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.openview.data.ActiveGraph
import com.example.openview.ui.components.PrimaryActionButton
import com.example.openview.ui.components.ScreenScaffold
import com.example.openview.ui.components.ScreenTitle
import com.example.openview.ui.components.SecondaryActionButton

@Composable
fun SimulatorMenuScreen(
    onChooseDestination: () -> Unit,
    onChooseShape: () -> Unit,
    onBack: () -> Unit
) {
    ScreenScaffold(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.height(8.dp))
        Icon(
            Icons.Filled.VolumeUp,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp)
        )
        ScreenTitle("Simulador de ruta")

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ),
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {}
        ) {
            Text(
                text = "Grafo activo: ${ActiveGraph.preset.name}",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
            )
        }

        Text(
            text = "Este modo usa las mismas pantallas de ruta, pero no depende de caminar ni de la señal real. " +
                "Cuando estés navegando, presiona volumen arriba para avanzar a la siguiente baliza: " +
                "NAV_BLE_01, NAV_BLE_02 y NAV_BLE_03.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth()
        )

        PrimaryActionButton(
            text = "Elegir destino simulado",
            icon = Icons.Filled.Route,
            onClick = onChooseDestination
        )
        SecondaryActionButton(
            text = "Cambiar forma del grafo",
            icon = Icons.Filled.Timeline,
            onClick = onChooseShape
        )
        SecondaryActionButton(
            text = "Volver",
            icon = Icons.Filled.ArrowBack,
            onClick = onBack
        )
    }
}
