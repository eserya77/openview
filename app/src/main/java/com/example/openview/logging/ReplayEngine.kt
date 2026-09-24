package com.example.openview.logging

import com.example.openview.data.BleSignal
import com.example.openview.data.NavGraph
import com.example.openview.calibration.BeaconProfile
import com.example.openview.nav.Algorithm
import com.example.openview.nav.BlePositioner
import com.example.openview.nav.FingerprintSet
import com.example.openview.nav.RouteParams
import com.example.openview.nav.TrackerParams
import kotlin.math.hypot

/**
 * Un instante del replay: lo que [BlePositioner] habría calculado en ese
 * momento con los parámetros elegidos, más la verdad de terreno si se conoce
 * (marcas del modo guiado o toque en el mapa).
 */
data class ReplayFrame(
    val timestampS: Double,
    val computedX: Double?,
    val computedY: Double?,
    val nodeId: String?,
    val signal: BleSignal,
    val trueX: Double?,
    val trueY: Double?,
    val trueNode: String? = null,
    val stableMs: Long = 0L
) {
    /** Distancia en metros entre el punto estimado y la verdad marcada, si ambos existen. */
    val errorM: Double?
        get() {
            val cx = computedX; val cy = computedY; val tx = trueX; val ty = trueY
            return if (cx != null && cy != null && tx != null && ty != null) hypot(cx - tx, cy - ty) else null
        }
}

/**
 * Vuelve a correr el pipeline COMPLETO (filtro por baliza + calibración + NodeTracker)
 * sobre un log ya grabado — en memoria o cargado de un CSV — con parámetros distintos
 * a los usados al grabar. Responde "¿y si hubiera usado otra histéresis?" sin volver a caminar.
 *
 * Usa el mismo [BlePositioner] que la navegación real, con un reloj inyectado a partir del
 * `timestamp_s` del log, así el resultado es lo que la app habría mostrado, no una aproximación.
 *
 * Logs v1: cada fila repetía el último RSSI de cada baliza aunque no hubiera muestra nueva;
 * aquí una lectura solo cuenta como muestra si su valor cambió respecto a la anterior de esa baliza.
 * Logs v2: cada fila ES una muestra nueva.
 *
 * Nota: asume que las balizas del log están asignadas a los mismos nodos que en [graph] ahora mismo.
 */
object ReplayEngine {

    fun run(
        rows: List<BleLogRow>,
        params: TrackerParams,
        graph: NavGraph,
        reference: (String) -> Int,
        algorithm: Algorithm = Algorithm.V2,
        path: List<String>? = null,
        fingerprints: FingerprintSet? = null,
        profile: (String) -> BeaconProfile? = { null }
    ): List<ReplayFrame> {
        if (rows.isEmpty()) return emptyList()

        val filled = GroundTruth.fill(rows.sortedBy { it.timestampS })
        val marks = GroundTruth.marksFrom(filled)
        val positioner = BlePositioner(params, { graph }, reference, algorithm, RouteParams(), { path }, { fingerprints }, profile)

        val lastRaw = mutableMapOf<String, Int>()
        val frames = mutableListOf<ReplayFrame>()

        filled.groupBy { it.timestampS }.toSortedMap().forEach { (t, batch) ->
            val nowMs = (t * 1000.0).toLong()
            var result: BlePositioner.Result? = null
            batch.filter { it.rssiRaw != null && graph.nodeForBeacon(it.beaconName) != null }.forEach { row ->
                val rssi = row.rssiRaw!!
                val isNew = row.sampleNew ?: (lastRaw[row.beaconName] != rssi)
                lastRaw[row.beaconName] = rssi
                if (isNew) result = positioner.onSample(row.beaconName, rssi, nowMs)
            }
            val res = result ?: positioner.tick(nowMs)

            val rowTruth = batch.firstOrNull { it.trueXM != null && it.trueYM != null }
            val truth = if (marks.isNotEmpty()) GroundTruth.truthAt(t, marks) else null
            frames.add(
                ReplayFrame(
                    timestampS = t,
                    computedX = res.computedX, computedY = res.computedY,
                    nodeId = res.nodeId, signal = res.signal,
                    trueX = truth?.x ?: rowTruth?.trueXM, trueY = truth?.y ?: rowTruth?.trueYM,
                    trueNode = truth?.nodeId ?: rowTruth?.trueNode,
                    stableMs = res.stableMs
                )
            )
        }
        return frames
    }
}
