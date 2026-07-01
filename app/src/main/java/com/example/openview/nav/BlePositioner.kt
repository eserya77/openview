package com.example.openview.nav

import com.example.openview.ble.BleDeviceInfo
import com.example.openview.data.ActiveGraph
import com.example.openview.data.BleSignal
import kotlin.math.pow

/**
 * Estima en qué nodo del grafo está el usuario a partir de las señales BLE.
 *
 * Método: centroide ponderado por potencia de señal (RSSI suavizado con EMA) de
 * todas las balizas visibles, y luego "snap" al nodo más cercano del grafo.
 * Robusto con 1, 2 o 3 balizas y sin saltos al cambiar cuántas se ven.
 */
class BlePositioner(private val alpha: Double = 0.4) {

    private val smoothed = mutableMapOf<String, Double>()

    data class Result(
        val nodeId: String?,
        val beaconName: String?,
        val rssi: Int?,
        val signal: BleSignal
    )

    fun update(devices: List<BleDeviceInfo>): Result {
        val graph = ActiveGraph.graph

        // Solo balizas conocidas del grafo (NAV_BLE_01/02/03).
        val targets = devices.filter { d ->
            d.name != null && graph.nodeForBeacon(d.name) != null
        }

        // Suaviza el RSSI por baliza y olvida las que ya no se ven.
        val present = targets.mapNotNull { it.name }.toSet()
        smoothed.keys.retainAll(present)
        targets.forEach { d ->
            val name = d.name!!
            val prev = smoothed[name]
            smoothed[name] = if (prev == null) d.rssi.toDouble() else alpha * d.rssi + (1 - alpha) * prev
        }

        if (smoothed.isEmpty()) {
            return Result(null, null, null, BleSignal.LOST)
        }

        // Centroide ponderado por potencia lineal (10^(rssi/10)).
        var sumW = 0.0
        var sumX = 0.0
        var sumY = 0.0
        smoothed.forEach { (name, rssi) ->
            val node = graph.nodeForBeacon(name) ?: return@forEach
            val w = 10.0.pow(rssi / 10.0)
            sumW += w
            sumX += w * node.x
            sumY += w * node.y
        }
        val node = if (sumW > 0) graph.nearestNode(sumX / sumW, sumY / sumW) else null

        // Calidad de señal según la baliza más fuerte.
        val best = smoothed.maxByOrNull { it.value }!!
        val signal = when {
            best.value >= -80 -> BleSignal.STABLE
            best.value >= -92 -> BleSignal.WEAK
            else -> BleSignal.LOST
        }

        return Result(node?.id, best.key, best.value.toInt(), signal)
    }

    fun reset() = smoothed.clear()
}
