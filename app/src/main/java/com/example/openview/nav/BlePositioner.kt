package com.example.openview.nav

import com.example.openview.calibration.BeaconProfile
import com.example.openview.ble.estimateMetersFromRssi
import com.example.openview.data.ActiveFingerprints
import com.example.openview.data.ActiveGraph
import com.example.openview.data.BleSignal
import com.example.openview.data.NavGraph
import com.example.openview.data.RouteRepository
import com.example.openview.logging.CENTER_NODE_ID
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Estima en qué nodo del grafo está el usuario a partir de las señales BLE.
 *
 * Pipeline:
 *  1. Cada muestra nueva de una baliza entra a su [BeaconFilter] (mediana + EMA) y caduca a los 3 s.
 *  2. Puntuación de cada nodo de la ruta:
 *       a. con HUELLAS entrenadas para esta disposición: −distancia entre las lecturas actuales y la huella del nodo;
 *       b. si no, con el PERFIL de calibración de cada baliza: RSSI menos su nivel de contacto (y "ningún nodo"
 *          cuando ninguna está cerca);
 *       c. si no hay nada de eso, RSSI relativo a la referencia a 1 m de cada baliza.
 *  3. Decisión:
 *       [Algorithm.V3] con orden de ruta conocido → [RouteTracker] (solo avanza al siguiente; hay "ningún nodo");
 *       si no → [NodeTracker] (histéresis + prior de vecinos) como en la v2.
 *
 * No conoce Android: el reloj, el grafo, la calibración, las huellas y el orden se inyectan, así que un log
 * grabado se reproduce con exactamente esta lógica (ver ReplayEngine).
 */
class BlePositioner(
    val params: TrackerParams = TrackerParams(),
    private val graphProvider: () -> NavGraph = { ActiveGraph.graph },
    private val reference: (String) -> Int = { RouteRepository.calibrationFor(it) },
    private val algorithm: Algorithm = Algorithm.V3,
    private val routeParams: RouteParams = RouteParams(),
    private val pathProvider: () -> List<String>? = { ActiveGraph.expectedPath },
    private val fingerprintProvider: () -> FingerprintSet? = { ActiveFingerprints.current() },
    private val profileProvider: (String) -> BeaconProfile? = { RouteRepository.profileFor(it) },
    /** El perfil de calibración da el nivel de CONTACTO: solo sirve si la parada es de contacto (o se desconoce). */
    private val contactModeProvider: () -> Boolean = { ActiveFingerprints.key?.endsWith("|contacto") != false },
    private val resetTokenProvider: () -> Int = { ActiveGraph.resetToken }
) {

    private var lastResetToken = resetTokenProvider()
    private var lastPathKey: String? = null

    private val pipeline = SignalPipeline(params)
    private val tracker = NodeTracker(params)
    private val routeTracker = RouteTracker(routeParams)

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
        /** Ventaja en dB del mejor candidato sobre el segundo. */
        val marginDb: Double? = null,
        /** Método que decidió: "huella", "perfil", "relativo" (v3) o "v2". */
        val method: String = "v2"
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
        routeTracker.reset()
    }

    private data class Verdict(val nodeId: String?, val bestBeacon: String?, val margin: Double?, val confidence: Double, val stableMs: Long, val method: String)

    private fun compute(nowMs: Long): Result {
        // Vuelta nueva o ruta distinta: se olvida el progreso anterior.
        val token = resetTokenProvider()
        val pathKey = pathProvider()?.joinToString("|")
        if (token != lastResetToken || pathKey != lastPathKey) {
            lastResetToken = token; lastPathKey = pathKey
            tracker.reset(); routeTracker.reset()
        }
        val graph = graphProvider()
        val smoothed = pipeline.smoothedAt(nowMs).filterKeys { graph.nodeForBeacon(it) != null }
        val path = pathProvider()?.filter { id -> graph.nodes.any { it.id == id } }
        val useV3 = algorithm == Algorithm.V3 && path != null && path.size >= 2

        if (smoothed.isEmpty()) {
            if (useV3) routeTracker.update(emptyMap(), path!!, nowMs) else tracker.update(emptyMap(), graph, nowMs)
            return Result(null, null, null, BleSignal.LOST)
        }

        val rel = smoothed.mapValues { (name, s) -> s - reference(name) }
        val verdict = if (useV3) decideV3(graph, path!!, smoothed, rel, nowMs) else decideV2(graph, rel, nowMs)

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
        return Result(
            nodeId = verdict.nodeId,
            beaconName = verdict.bestBeacon,
            rssi = verdict.bestBeacon?.let { smoothed[it]?.roundToInt() },
            signal = signal,
            computedX = cx,
            computedY = cy,
            readings = readings,
            stableMs = verdict.stableMs,
            confidence = verdict.confidence,
            marginDb = verdict.margin,
            method = verdict.method
        )
    }

    private fun decideV2(graph: NavGraph, rel: Map<String, Double>, nowMs: Long): Verdict {
        val d = tracker.update(rel, graph, nowMs)
        return Verdict(d.nodeId, d.bestBeacon, d.marginDb, d.confidence, d.stableMs, "v2")
    }

    private fun decideV3(graph: NavGraph, path: List<String>, smoothed: Map<String, Double>, rel: Map<String, Double>, nowMs: Long): Verdict {
        val fp = fingerprintProvider()
        val scores = mutableMapOf<String, Double>()
        val method: String

        if (fp != null && path.all { fp.has(it) }) {
            // a) Huellas: cuanto más cerca de la huella del nodo, mejor.
            method = "huella"
            path.forEach { id -> scores[id] = -Fingerprints.distance(fp.nodes.getValue(id), smoothed) }
            fp.nodes[CENTER_NODE_ID]?.let { scores[CENTER_NODE_ID] = -Fingerprints.distance(it, smoothed) }
        } else {
            val beacons = path.associateWith { graph.node(it).beaconName }
            val profiles = beacons.mapValues { (_, b) -> b?.let(profileProvider) }
            val allProfiled = profiles.values.all { it?.contactRssi != null } && contactModeProvider()
            if (allProfiled) {
                // b) Perfil: cercanía respecto al nivel de contacto de cada baliza; si ninguna está cerca → ningún nodo.
                method = "perfil"
                var minDelta = Double.MAX_VALUE
                path.forEach { id ->
                    val p = profiles.getValue(id)!!
                    val delta = max(6.0, 2.0 * (p.contactSd ?: 0.0))
                    minDelta = minOf(minDelta, delta)
                    val s = smoothed[beacons.getValue(id)] ?: Fingerprints.FLOOR_DBM
                    scores[id] = s - p.contactRssi!!
                }
                scores[CENTER_NODE_ID] = -minDelta
            } else {
                // c) Sin huellas ni perfiles: RSSI relativo a la referencia a 1 m de cada baliza.
                method = "relativo"
                path.forEach { id ->
                    val b = beacons[id]
                    scores[id] = b?.let { rel[it] } ?: -99.0
                }
            }
        }

        val d = routeTracker.update(scores, path, nowMs)
        return Verdict(d.nodeId, d.candidate?.let { graph.node(it).beaconName }, d.marginDb, d.confidence, d.stableMs, method)
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
