package com.example.openview.nav

import com.example.openview.logging.BleLogRow
import com.example.openview.logging.CENTER_NODE_ID
import com.example.openview.logging.GroundTruth
import com.example.openview.logging.MarkEvent
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Huella de un punto: RSSI típico de CADA baliza (incluidas las ajenas) cuando estás parado allí.
 * Mira el conjunto, no una sola baliza: así una baliza "ruidosa" que se oye fuerte desde todas partes
 * (como la 03 en las pruebas) no engaña, porque su nivel "normal" en cada punto ya está en la huella.
 */
data class NodeFingerprint(
    val nodeId: String,
    val mean: Map<String, Double>,
    val sd: Map<String, Double>,
    /** Cuántas vueltas de entrenamiento la formaron. */
    val laps: Int
)

/** Huellas de todos los puntos de UNA disposición (escenario + qué baliza en cada posición + tipo de parada). */
data class FingerprintSet(val key: String, val nodes: Map<String, NodeFingerprint>) {
    val laps: Int get() = nodes.values.maxOfOrNull { it.laps } ?: 0
    fun has(nodeId: String) = nodes.containsKey(nodeId)
}

object Fingerprints {

    /** RSSI que se asume para una baliza que no se oye (por debajo del piso de sensibilidad del teléfono). */
    const val FLOOR_DBM = -100.0
    private const val MIN_SD_DB = 3.0

    /** Distancia RMS (dB) entre las lecturas actuales [x] y la huella [fp]. Una baliza que no se oye cuenta como [FLOOR_DBM]. */
    fun distance(fp: NodeFingerprint, x: Map<String, Double>): Double {
        if (fp.mean.isEmpty()) return Double.MAX_VALUE
        var sum = 0.0
        fp.mean.forEach { (beacon, mu) ->
            val v = x[beacon] ?: FLOOR_DBM
            sum += (v - mu) * (v - mu)
        }
        return sqrt(sum / fp.mean.size)
    }

    /** Junta las vueltas de entrenamiento de un punto: mediana por baliza y dispersión entre vueltas (con piso de 3 dB). */
    fun merge(nodeId: String, laps: List<Map<String, Double>>): NodeFingerprint {
        val beacons = laps.flatMap { it.keys }.toSet()
        val mean = mutableMapOf<String, Double>()
        val sd = mutableMapOf<String, Double>()
        beacons.forEach { b ->
            val v = laps.mapNotNull { it[b] }.sorted()
            if (v.isEmpty()) return@forEach
            mean[b] = if (v.size % 2 == 1) v[v.size / 2] else (v[v.size / 2 - 1] + v[v.size / 2]) / 2.0
            val m = v.average()
            sd[b] = max(MIN_SD_DB, if (v.size < 2) 0.0 else sqrt(v.sumOf { (it - m) * (it - m) } / (v.size - 1)))
        }
        return NodeFingerprint(nodeId, mean, sd, laps.size)
    }

    /**
     * Saca de una vuelta grabada la huella de cada punto (mediana del RSSI crudo de cada baliza durante la
     * estancia, sin el primer [settleS]). Incluye el centro ([CENTER_NODE_ID]) si se marcó. Solo cuentan las
     * balizas con al menos [minSamples] muestras en esa estancia; las demás quedan fuera (se tratan como "no se oye").
     */
    fun trainFromRows(rows: List<BleLogRow>, settleS: Double = 1.5, minSamples: Int = 5): Map<String, Map<String, Double>> {
        val marks = GroundTruth.marksFrom(rows)
        val samples = rows.filter { it.event == MarkEvent.SAMPLE && it.rssiRaw != null }
        val out = linkedMapOf<String, Map<String, Double>>()
        marks.filter { it.kind == MarkEvent.ARRIVE }.forEach { a ->
            val end = marks.firstOrNull { it.tS > a.tS && it.kind == MarkEvent.DEPART && it.nodeId == a.nodeId }?.tS ?: return@forEach
            val from = if (end - a.tS > settleS + 0.5) a.tS + settleS else a.tS
            val vec = mutableMapOf<String, Double>()
            samples.filter { it.timestampS in from..end }.groupBy { it.beaconName }.forEach { (b, list) ->
                if (list.size >= minSamples) {
                    val v = list.map { it.rssiRaw!! }.sorted()
                    vec[b] = if (v.size % 2 == 1) v[v.size / 2].toDouble() else (v[v.size / 2 - 1] + v[v.size / 2]) / 2.0
                }
            }
            if (vec.isNotEmpty()) out[a.nodeId] = vec
        }
        return out
    }
}
