package com.example.openview.nav

import com.example.openview.ble.BleDeviceInfo
import com.example.openview.ble.estimateMetersFromRssi
import com.example.openview.data.ActiveGraph
import com.example.openview.data.BleSignal
import com.example.openview.data.NavGraph
import com.example.openview.data.RouteRepository
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

    /** Lectura individual de una baliza, cruda y ya procesada. Solo para diagnóstico/registro. */
    data class BeaconReading(
        val beaconName: String,
        val nodeId: String?,
        val rssiRaw: Int,
        val rssiSmoothed: Double,
        val estimatedDistanceM: Double
    )

    data class Result(
        val nodeId: String?,
        val beaconName: String?,
        val rssi: Int?,
        val signal: BleSignal,
        /** Centroide ponderado, en metros del grafo activo, antes del snap al nodo. Null si no hay balizas visibles. */
        val computedX: Double? = null,
        val computedY: Double? = null,
        /** Una entrada por cada baliza del grafo activo que se ve ahora mismo. Vacía si ninguna coincide con el grafo. */
        val readings: List<BeaconReading> = emptyList()
    )

    /** Centroide + nodo detectado + calidad de señal, a partir de RSSI ya suavizado por baliza. */
    data class Fusion(
        val nodeId: String?,
        val computedX: Double?,
        val computedY: Double?,
        val signal: BleSignal,
        val strongestBeacon: String?,
        val strongestRssi: Double?
    )

    companion object {
        /**
         * El corazón matemático del positioner, sin estado ni dependencia del
         * escáner: centroide ponderado por potencia lineal (10^(rssi/10)) y
         * "snap" al nodo más cercano del grafo. Recibe el RSSI YA suavizado.
         *
         * Vive aquí, y no solo dentro de [update], para que
         * [com.example.openview.logging.ReplayEngine] pueda re-simular un log
         * grabado con un `alpha` distinto usando exactamente esta misma
         * fórmula — no una copia que se puede desincronizar.
         */
        fun fuse(smoothed: Map<String, Double>, graph: NavGraph): Fusion {
            if (smoothed.isEmpty()) return Fusion(null, null, null, BleSignal.LOST, null, null)

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
            val cx = if (sumW > 0) sumX / sumW else null
            val cy = if (sumW > 0) sumY / sumW else null
            val node = if (cx != null && cy != null) graph.nearestNode(cx, cy) else null

            val best = smoothed.maxByOrNull { it.value }!!
            val signal = when {
                best.value >= -80 -> BleSignal.STABLE
                best.value >= -92 -> BleSignal.WEAK
                else -> BleSignal.LOST
            }
            return Fusion(node?.id, cx, cy, signal, best.key, best.value)
        }
    }

    fun update(devices: List<BleDeviceInfo>): Result {
        val graph = ActiveGraph.graph

        // Solo balizas asignadas a nodos de la ruta activa. La cantidad no está
        // fijada: el administrador puede configurar tres, seis o más.
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

        // Lecturas por baliza para diagnóstico/registro (no afecta la navegación).
        val readings = targets.mapNotNull { d ->
            val name = d.name ?: return@mapNotNull null
            BeaconReading(
                beaconName = name,
                nodeId = graph.nodeForBeacon(name)?.id,
                rssiRaw = d.rssi,
                rssiSmoothed = smoothed[name] ?: d.rssi.toDouble(),
                estimatedDistanceM = estimateMetersFromRssi(d.rssi, RouteRepository.calibrationFor(name))
            )
        }

        if (smoothed.isEmpty()) {
            return Result(null, null, null, BleSignal.LOST, readings = readings)
        }

        val fusion = fuse(smoothed, graph)
        return Result(fusion.nodeId, fusion.strongestBeacon, fusion.strongestRssi?.toInt(), fusion.signal, fusion.computedX, fusion.computedY, readings)
    }

    fun reset() = smoothed.clear()
}
