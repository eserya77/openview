package com.example.openview.logging

/** Tipo de marca de verdad de terreno del modo prueba guiado. */
object MarkEvent {
    const val SAMPLE = "SAMPLE"   // lectura normal de una baliza
    const val NOTE = "NOTE"       // nota de texto
    const val ARRIVE = "ARRIVE"   // "llegué a este nodo"
    const val DEPART = "DEPART"   // "salgo de este nodo" (fin de la estancia)
    const val NONE = "NONE"       // instante sin balizas visibles
}

/** Una marca de "estoy aquí" en el tiempo de la sesión (ya corregida por el retraso con que se tocó). */
data class TruthMark(
    val tS: Double,
    val kind: String,           // MarkEvent.ARRIVE o MarkEvent.DEPART
    val nodeId: String,
    val x: Double,
    val y: Double
)

/** Posición real conocida en un instante: dentro de un nodo, o en tránsito entre dos. */
data class TruthPoint(val x: Double, val y: Double, val nodeId: String?)

/**
 * Convierte las marcas ARRIVE/DEPART en verdad de terreno para CUALQUIER instante:
 *  - entre ARRIVE y su DEPART: estás en ese nodo;
 *  - entre un DEPART y el siguiente ARRIVE: en tránsito, con posición interpolada
 *    linealmente entre los dos nodos;
 *  - fuera de esos tramos: desconocida.
 *
 * Kotlin puro: la usan el exportador de la app, el replay y el analizador.
 */
object GroundTruth {

    fun marksFrom(rows: List<BleLogRow>): List<TruthMark> =
        rows.filter { it.event == MarkEvent.ARRIVE || it.event == MarkEvent.DEPART }
            .mapNotNull { r ->
                val id = r.trueNode ?: return@mapNotNull null
                val x = r.trueXM ?: return@mapNotNull null
                val y = r.trueYM ?: return@mapNotNull null
                TruthMark(r.timestampS, r.event, id, x, y)
            }
            .sortedBy { it.tS }

    fun truthAt(t: Double, marks: List<TruthMark>): TruthPoint? {
        if (marks.isEmpty()) return null
        val idx = marks.indexOfLast { it.tS <= t }
        if (idx < 0) return null
        val m = marks[idx]
        if (m.kind == MarkEvent.ARRIVE) return TruthPoint(m.x, m.y, m.nodeId)
        val next = marks.drop(idx + 1).firstOrNull { it.kind == MarkEvent.ARRIVE } ?: return null
        val span = next.tS - m.tS
        if (span <= 0.0) return TruthPoint(next.x, next.y, null)
        val f = ((t - m.tS) / span).coerceIn(0.0, 1.0)
        return TruthPoint(m.x + (next.x - m.x) * f, m.y + (next.y - m.y) * f, null)
    }

    /** Rellena true_x/true_y/true_node en las filas de lectura que no lo tengan, según las marcas. */
    fun fill(rows: List<BleLogRow>): List<BleLogRow> {
        val marks = marksFrom(rows)
        if (marks.isEmpty()) return rows
        return rows.map { r ->
            if (r.event != MarkEvent.SAMPLE && r.event != MarkEvent.NONE) return@map r
            if (r.trueXM != null) return@map r
            val p = truthAt(r.timestampS, marks) ?: return@map r
            r.copy(trueXM = p.x, trueYM = p.y, trueNode = p.nodeId)
        }
    }
}
