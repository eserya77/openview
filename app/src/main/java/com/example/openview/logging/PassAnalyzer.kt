package com.example.openview.logging

import kotlin.math.abs

/** Resultado de pasar junto a un punto SIN detenerse (marca «paso junto a ella» en el instante de pasar). */
data class PassPoint(
    val nodeId: String,
    val beaconName: String?,
    /** Segundos entre tu marca y el pico de RSSI de esa baliza (negativo = el pico fue antes). Null si no se oyó. */
    val peakOffsetS: Double?,
    val peakRssi: Int?,
    /** Segundos entre tu marca y la primera vez que la app dijo ese punto, dentro de la ventana. Null = no lo detectó. */
    val detectLatencyS: Double?
)

data class PassSummary(
    val points: List<PassPoint>,
    val detected: Int,
    /** La app nombró los puntos en el mismo orden que los pasaste (ignorando "ningún nodo"). */
    val orderOk: Boolean,
    val appSequence: List<String>,
    val meanAbsPeakOffsetS: Double?
)

/**
 * Análisis del modo «pasar sin parar» de la prueba guiada: por cada marca ARRIVE (el instante en que la baliza
 * quedaba a tu lado), busca el pico de su RSSI en ±[windowS] y si la app nombró ese punto cerca de la marca.
 * Kotlin puro.
 */
object PassAnalyzer {

    fun analyze(
        rows: List<BleLogRow>,
        beaconOfNode: Map<String, String>,
        detections: List<Detection> = SessionAnalyzer.detectionsFrom(rows),
        windowS: Double = 3.0,
        detectBeforeS: Double = 2.5,
        detectAfterS: Double = 3.5
    ): PassSummary {
        val marks = GroundTruth.marksFrom(rows).filter { it.kind == MarkEvent.ARRIVE && it.nodeId != CENTER_NODE_ID }
        val samples = rows.filter { it.event == MarkEvent.SAMPLE && it.rssiRaw != null }
        val points = marks.map { m ->
            val beacon = beaconOfNode[m.nodeId]
            val near = samples.filter { it.beaconName == beacon && abs(it.timestampS - m.tS) <= windowS }.sortedBy { it.timestampS }
            // Mediana de 3 para que un pico aislado no decida.
            val smooth = near.indices.map { i ->
                val w = near.subList((i - 1).coerceAtLeast(0), (i + 2).coerceAtMost(near.size)).map { it.rssiRaw!! }.sorted()
                near[i].timestampS to w[w.size / 2]
            }
            val peak = smooth.maxByOrNull { it.second }
            val det = detections.firstOrNull { it.nodeId == m.nodeId && it.tS >= m.tS - detectBeforeS && it.tS <= m.tS + detectAfterS }
            PassPoint(m.nodeId, beacon, peak?.let { it.first - m.tS }, peak?.second, det?.let { it.tS - m.tS })
        }
        val seq = detections.mapNotNull { it.nodeId }.filter { it != CENTER_NODE_ID }
            .fold(mutableListOf<String>()) { acc, n -> if (acc.lastOrNull() != n) acc += n; acc }
        val expected = marks.map { it.nodeId }
        val offsets = points.mapNotNull { it.peakOffsetS?.let(::abs) }
        return PassSummary(
            points = points,
            detected = points.count { it.detectLatencyS != null },
            orderOk = seq == expected,
            appSequence = seq,
            meanAbsPeakOffsetS = if (offsets.isEmpty()) null else offsets.average()
        )
    }
}
