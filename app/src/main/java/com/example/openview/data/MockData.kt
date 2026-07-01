package com.example.openview.data

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.TurnRight

/**
 * Destinos seleccionables = nodos reales del grafo activo, cada uno ligado a su
 * baliza BLE. No hay lugares ficticios: se generan de la forma seleccionada.
 */
object MockData {

    val destinations: List<Destination>
        get() = ActiveGraph.graph.nodes.map { node ->
            val icon = when {
                node.isDestination -> Icons.Filled.Flag
                node.id == ActiveGraph.startNode -> Icons.Filled.Place
                else -> Icons.Filled.TurnRight
            }
            Destination(node.id, node.label, "Baliza ${node.beaconName} · ${node.id}", icon)
        }

    fun destinationFor(nodeId: String): Destination? = destinations.firstOrNull { it.id == nodeId }
}
