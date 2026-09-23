package com.example.openview.nav

import com.example.openview.ble.estimateMetersFromRssi
import com.example.openview.data.ActiveGraph
import com.example.openview.data.BleSignal
import com.example.openview.data.NavGraph
import com.example.openview.data.RouteRepository
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Estima en qué nodo del grafo está el usuario a partir de las señales BLE.
 *
 * Pipeline (v2):
 *  1. Cada muestra nueva de una baliza entra a su [BeaconFilter] (mediana + EMA).
 *  2. El RSSI suavizado se compara contra la referencia a 1 m de ESA baliza
 *     (calibración por baliza): así una baliza más "ruidosa" o más débil por
 *     hardware no gana ni pierde por sí sola.
 *  3. [NodeTracker] decide el nodo con histéresis y prior de vecinos del grafo.
 *
 * Se alimenta por muestras ([onSample]) y por un reloj ([tick]) que hace caducar
 * las balizas que dejaron de oírse. No conoce Android: el reloj y la calibración
 * se inyectan, lo que permite reproducir un log grabado con exactamente esta lógica.
 */
class BlePositioner(
    val params: TrackerParams = TrackerParams(),
    private val graphProvider: () -> NavGraph = { ActiveGraph.graph },
    private val reference: (String) -> Int = { RouteRepository.calibrationFor(it) }
) {

    private val pipeline = SignalPipeline(params)
    private val tracker = NodeTracker(params)

    /** Lectura individual de una baliza, cruda y ya procesada. Solo para diagnóstico/registro. */
    data class BeaconReading(
        val beaconName: String,
        val nodeId: String?,
        val rssiRaw: Int,
        val rssiSmoothed: Double,
        val estimatedDistanceM: Double,
        /** RSSI suavizado menos la referencia a 1 m de esta baliza (0 = a 1 m; más alto = más cerca). */
        val relDb: Double
    )

    data class Result(
        val nodeId: String?,
        val beaconName: String?,
        val rssi: Int?,
        val signal: BleSignal,
        /** Centroide ponderado (con RSSI calibrado), en metros. Solo para dibujar el mapa de diagnóstico; NO decide el nodo. */
        val computedX: Double? = null,
        val computedY: Double? = null,
        /** Una entrada por cada baliza del grafo activo que se oye ahora mismo. */
        val readings: List<BeaconReading> = emptyList(),
        /** Cuánto lleva confirmado el nodo actual. La navegación exige un mínimo para dar la llegada. */
        val stableMs: Long = 0L,
        /** 0..1: qué tan clara es la decisión. */
        val confidence: Double = 0.0,
        /** Ventaja en dB de la baliza más fuerte sobre la segunda. */
        val marginDb: Double? = null
    )

    /** Procesa una muestra nueva de [beacon] y devuelve el estado resultante. */
    fun onSample(beacon: String, rssi: Int, nowMs: Long): Result {
        if (graphProvider().nodeForBeacon(beacon) != null) pipeline.onSample(beacon, rssi, nowMs)
        return compute(nowMs)
    }

    /** Solo avanza el reloj (caducidad de balizas, confirmación por tiempo). */
    fun tick(nowMs: Long): Result = compute(nowMs)

    fun reset() {
        pipeline.reset()
        tracker.reset()
    }

    private fun compute(nowMs: Long): Result {
        val graph = graphProvider()
        val smoothed = pipeline.smoothedAt(nowMs).filterKeys { graph.nodeForBeacon(it) != null }
        if (smoothed.isEmpty()) {
            tracker.update(emptyMap(), graph, nowMs)
            return Result(null, null, null, BleSignal.LOST)
        }

        val rel = smoothed.mapValues { (name, s) -> s - reference(name) }
        val decision = tracker.update(rel, graph, nowMs)

        val readings = smoothed.map { (name, s) ->
            BeaconReading(
                beaconName = name,
                nodeId = graph.nodeForBeacon(name)?.id,
                rssiRaw = pipeline.lastRaw(name) ?: s.roundToInt(),
                rssiSmoothed = s,
                estimatedDistanceM = estimateMetersFromRssi(s.roundToInt(), reference(name)).coerceAtMost(MAX_DISTANCE_M),
                relDb = rel.getValue(name)
            )
        }

        // Señal por RSSI absoluto de la baliza más fuerte (misma regla que la v1).
        val strongestAbs = smoothed.maxOf { it.value }
        val signal = when {
            strongestAbs >= -80 -> BleSignal.STABLE
            strongestAbs >= -92 -> BleSignal.WEAK
            else -> BleSignal.LOST
        }

        val (cx, cy) = centroid(rel, graph)
        val best = decision.bestBeacon
        return Result(
            nodeId = decision.nodeId,
            beaconName = best,
            rssi = best?.let { smoothed[it]?.roundToInt() },
            signal = signal,
            computedX = cx,
            computedY = cy,
            readings = readings,
            stableMs = decision.stableMs,
            confidence = decision.confidence,
            marginDb = decision.marginDb
        )
    }

    private fun centroid(rel: Map<String, Double>, graph: NavGraph): Pair<Double?, Double?> {
        var sumW = 0.0; var sumX = 0.0; var sumY = 0.0
        rel.forEach { (name, r) ->
            val node = graph.nodeForBeacon(name) ?: return@forEach
            val w = 10.0.pow(r / 10.0)
            sumW += w; sumX += w * node.x; sumY += w * node.y
        }
        return if (sumW > 0) (sumX / sumW) to (sumY / sumW) else (null to null)
    }

    private companion object {
        const val MAX_DISTANCE_M = 15.0
    }
}
