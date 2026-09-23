package com.example.openview.logging

/** Qué nodo decía la app en un instante. */
data class Detection(val tS: Double, val nodeId: String?)

/** Resultado de un punto de la prueba guiada (una estancia en un nodo). */
data class PointResult(
    val nodeId: String,
    val beaconName: String?,
    /** % del tiempo de la estancia (tras el tiempo de asentamiento) en que la app decía el nodo correcto. */
    val accuracyPct: Double,
    /** Segundos desde "llegué" hasta que la app dijo el nodo correcto por primera vez; null si nunca. */
    val latencyS: Double?,
    /** Cambios de nodo durante la estancia (idealmente 0). */
    val flips: Int,
    /** RSSI crudo mediano de la baliza de ese nodo durante la estancia. */
    val medianRssi: Int?,
    /** Nodo equivocado que más tiempo apareció, si hubo. */
    val wrongNode: String?
)

data class SessionSummary(
    val points: List<PointResult>,
    val meanAccuracyPct: Double,
    val totalFlips: Int,
    /** Segundos, en estancias de puntos que NO son el último, en que la app decía "destino". Más de 1 s = riesgo de falsa llegada. */
    val wrongDestinationS: Double,
    val destinationNode: String?
) {
    val falseArrival: Boolean get() = wrongDestinationS > 1.0
}

/**
 * Calcula, a partir de las marcas ARRIVE/DEPART y de lo que la app detectó, las métricas
 * de la prueba: acierto por punto, latencia, parpadeo y riesgo de falsa llegada. Kotlin puro.
 */
object SessionAnalyzer {

    const val SETTLE_S = 1.5
    private const val STEP_S = 0.1

    fun detectionsFrom(rows: List<BleLogRow>): List<Detection> =
        rows.filter { it.event == MarkEvent.SAMPLE || it.event == MarkEvent.NONE }
            .map { Detection(it.timestampS, it.detectedNodeId) }
            .sortedBy { it.tS }

    /**
     * @param beaconOfNode nodeId -> nombre de su baliza (para la mediana de RSSI propia).
     * @param detections  lo que la app decía; por defecto sale de las propias filas.
     */
    fun analyze(
        rows: List<BleLogRow>,
        beaconOfNode: Map<String, String>,
        detections: List<Detection> = detectionsFrom(rows)
    ): SessionSummary {
        val marks = GroundTruth.marksFrom(rows)
        val arrivals = marks.filter { it.kind == MarkEvent.ARRIVE }
        if (arrivals.isEmpty() || detections.isEmpty()) return SessionSummary(emptyList(), 0.0, 0, 0.0, null)

        val lastT = detections.last().tS
        val destination = arrivals.last().nodeId

        fun stateAt(t: Double): String? {
            var lo = 0; var hi = detections.size - 1; var ans = -1
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                if (detections[mid].tS <= t) { ans = mid; lo = mid + 1 } else hi = mid - 1
            }
            return if (ans >= 0) detections[ans].nodeId else null
        }

        var wrongDestS = 0.0
        val points = arrivals.mapIndexed { i, a ->
            val end = marks.firstOrNull { it.tS > a.tS && it.kind == MarkEvent.DEPART && it.nodeId == a.nodeId }?.tS
                ?: arrivals.getOrNull(i + 1)?.tS ?: lastT
            val evalStart = if (end - a.tS > SETTLE_S + 0.5) a.tS + SETTLE_S else a.tS

            var okS = 0.0
            var totalS = 0.0
            val wrong = mutableMapOf<String, Double>()
            var t = evalStart
            while (t < end) {
                val s = stateAt(t)
                totalS += STEP_S
                if (s == a.nodeId) okS += STEP_S
                else if (s != null) wrong[s] = (wrong[s] ?: 0.0) + STEP_S
                if (i != arrivals.lastIndex && s == destination) wrongDestS += STEP_S
                t += STEP_S
            }

            var latency: Double? = null
            t = a.tS
            while (t < end) {
                if (stateAt(t) == a.nodeId) { latency = t - a.tS; break }
                t += STEP_S
            }

            val inWindow = detections.filter { it.tS in evalStart..end }
            val flips = inWindow.zipWithNext().count { (p, q) -> p.nodeId != q.nodeId }

            val beacon = beaconOfNode[a.nodeId]
            val rssis = rows
                .filter { it.event == MarkEvent.SAMPLE && it.beaconName == beacon && it.timestampS in evalStart..end }
                .mapNotNull { it.rssiRaw }
                .sorted()

            PointResult(
                nodeId = a.nodeId,
                beaconName = beacon,
                accuracyPct = if (totalS > 0) 100.0 * okS / totalS else 0.0,
                latencyS = latency,
                flips = flips,
                medianRssi = rssis.getOrNull(rssis.size / 2),
                wrongNode = wrong.maxByOrNull { it.value }?.key
            )
        }

        return SessionSummary(
            points = points,
            meanAccuracyPct = points.map { it.accuracyPct }.average(),
            totalFlips = points.sumOf { it.flips },
            wrongDestinationS = wrongDestS,
            destinationNode = destination
        )
    }
}
