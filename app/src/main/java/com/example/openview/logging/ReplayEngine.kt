package com.example.openview.logging

import com.example.openview.data.BleSignal
import com.example.openview.data.NavGraph
import com.example.openview.nav.BlePositioner
import kotlin.math.hypot

/**
 * Un instante del replay: lo que BlePositioner habría calculado en ese
 * momento con el `alpha` elegido, más la verdad de terreno si se marcó una
 * en ese instante (ver [BleSessionLogger.markTruePosition]).
 */
data class ReplayFrame(
    val timestampS: Double,
    val computedX: Double?,
    val computedY: Double?,
    val nodeId: String?,
    val signal: BleSignal,
    val trueX: Double?,
    val trueY: Double?
) {
    /** Distancia en metros entre el punto estimado y la verdad marcada, si ambos existen. */
    val errorM: Double?
        get() {
            val cx = computedX; val cy = computedY; val tx = trueX; val ty = trueY
            return if (cx != null && cy != null && tx != null && ty != null) hypot(cx - tx, cy - ty) else null
        }
}

/**
 * Vuelve a correr el suavizado (EMA) y la fusión de [BlePositioner] sobre un
 * log ya grabado — en memoria o cargado de un CSV exportado ([BleLogFile]) —
 * con un [alpha] distinto al que se usó al grabar. Sirve para responder
 * "¿y si hubiera usado alpha = 0.6?" sin caminar de nuevo el pasillo.
 *
 * Usa [BlePositioner.fuse], el mismo cálculo de centroide + nodo que la
 * navegación real, así el resultado es exactamente lo que la app habría
 * mostrado con ese alpha, no una aproximación aparte que se puede desviar.
 *
 * Nota: asume que las balizas del log están asignadas a los mismos nodos que
 * en [graph] ahora mismo. Si el grafo activo cambió desde que se grabó la
 * prueba, el replay puede no coincidir con lo que se vio en su momento.
 */
object ReplayEngine {

    fun run(rows: List<BleLogRow>, alpha: Double, graph: NavGraph): List<ReplayFrame> {
        if (rows.isEmpty()) return emptyList()

        val smoothed = mutableMapOf<String, Double>()
        val frames = mutableListOf<ReplayFrame>()

        // Solo filas con RSSI real (se descartan _NOTE_ y _NONE_) y cuya
        // baliza esté asignada a un nodo del grafo actual.
        val readingsByTimestamp = rows
            .filter { it.rssiRaw != null && graph.nodeForBeacon(it.beaconName) != null }
            .groupBy { it.timestampS }

        // Las marcas de verdad de terreno pueden caer en el mismo instante que
        // una fila _NONE_ (sin balizas visibles); se recogen aparte para no
        // perderlas si ese instante no tiene lecturas de RSSI utilizables.
        val truthByTimestamp = rows
            .filter { it.trueXM != null && it.trueYM != null }
            .associate { it.timestampS to (it.trueXM!! to it.trueYM!!) }

        val allTimestamps = (readingsByTimestamp.keys + truthByTimestamp.keys).toSortedSet()

        allTimestamps.forEach { t ->
            val batch = readingsByTimestamp[t]
            if (batch != null) {
                // Olvida balizas que no aparecen en este instante, igual que BlePositioner.update.
                val present = batch.map { it.beaconName }.toSet()
                smoothed.keys.retainAll(present)
                batch.forEach { row ->
                    val rssi = row.rssiRaw ?: return@forEach
                    val prev = smoothed[row.beaconName]
                    smoothed[row.beaconName] = if (prev == null) rssi.toDouble() else alpha * rssi + (1 - alpha) * prev
                }
            }
            // Si 't' es un instante "solo verdad" (sin lote de RSSI, caso raro),
            // se reutiliza el último estado suavizado en vez de vaciarlo.

            val fusion = BlePositioner.fuse(smoothed, graph)
            val truth = truthByTimestamp[t]
            frames.add(ReplayFrame(t, fusion.computedX, fusion.computedY, fusion.nodeId, fusion.signal, truth?.first, truth?.second))
        }

        return frames
    }
}
