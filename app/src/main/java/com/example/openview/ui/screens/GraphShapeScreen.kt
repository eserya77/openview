package com.example.openview.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.openview.data.ActiveGraph
import com.example.openview.data.GraphPreset
import com.example.openview.data.GraphPresets
import com.example.openview.ui.components.ScreenScaffold
import com.example.openview.ui.components.ScreenTitle
import com.example.openview.ui.components.SecondaryActionButton

@Composable
fun GraphShapeScreen(onSelected: (GraphPreset) -> Unit, onBack: () -> Unit) {
    var selectedId by remember { mutableStateOf(ActiveGraph.preset.id) }

    ScreenScaffold(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(8.dp))
        ScreenTitle("Forma de prueba")
        Text(
            "Elige el grafo de 3 balizas para probar. Solo cambia la geometría: " +
                "las balizas NAV_BLE_01/02/03 son las mismas.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        GraphPresets.all.forEach { preset ->
            ShapeCard(
                preset = preset,
                selected = preset.id == selectedId,
                onClick = {
                    selectedId = preset.id
                    ActiveGraph.select(preset)
                    onSelected(preset)
                }
            )
        }

        Spacer(Modifier.height(8.dp))
        SecondaryActionButton(
            text = "Volver",
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            onClick = onBack
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShapeCard(preset: GraphPreset, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .semantics(mergeDescendants = true) {}
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Icon(
                if (selected) Icons.Filled.CheckCircle else Icons.Filled.Timeline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.width(20.dp))
            Column {
                Text(
                    preset.name + if (selected) " (activa)" else "",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    preset.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
