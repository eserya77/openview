package com.example.openview.nav

import com.example.openview.data.NavGraph

/**
 * Decide en qué nodo del grafo está la persona a partir del RSSI de cada
 * baliza YA calibrado ([rel] = RSSI suavizado − referencia a 1 m de esa baliza).
 *
 * Reglas (todas para evitar el parpadeo y la "baliza ruidosa que gana siempre"):
 *  - El nodo es el de la baliza con mayor RSSI relativo (no un centroide).
 *  - Para quitarle el nodo al actual, el retador debe ganar por [TrackerParams.marginDb]
 *    de forma continua durante [TrackerParams.holdMs].
 *  - Si el retador NO es vecino del nodo actual en el grafo, se exige un margen
 *    mayor ([TrackerParams.jumpMarginDb]): saltar de 06 a 03 sin pasar por 05/04 es sospechoso.
 *  - El primer nodo también necesita sostenerse [TrackerParams.holdMs].
 *
 * Kotlin puro y con reloj inyectado ([nowMs]) para poder probarlo y reproducirlo.
 */
class NodeTracker(private val params: TrackerParams = TrackerParams()) {

    data class Decision(
        val nodeId: String?,
        /** Baliza más fuerte (calibrada) en este instante; puede diferir del nodo confirmado mientras el retador aguanta el tiempo. */
        val bestBeacon: String?,
        /** Ventaja en dB de la 1.ª sobre la 2.ª baliza. Null si solo hay una visible. */
        val marginDb: Double?,
        /** 0..1: qué tan clara es la decisión (margen y cantidad de balizas visibles). */
        val confidence: Double,
        /** Cuánto lleva confirmado el nodo actual. */
        val stableMs: Long
    )

    private var currentNode: String? = null
    private var confirmedAtMs = 0L
    private var challengerNode: String? = null
    private var challengerSinceMs = 0L

    fun reset() {
        currentNode = null; challengerNode = null
    }

    fun update(rel: Map<String, Double>, graph: NavGraph, nowMs: Long): Decision {
        // Solo balizas asignadas a nodos del grafo activo.
        val candidates = rel.mapNotNull { (name, v) -> graph.nodeForBeacon(name)?.let { Triple(name, it.id, v) } }
        if (candidates.isEmpty()) {
            reset()
            return Decision(null, null, null, 0.0, 0L)
        }
        val sorted = candidates.sortedByDescending { it.third }
        val best = sorted[0]
        val second = sorted.getOrNull(1)
        val margin = second?.let { best.third - it.third }
        val confidence = when {
            margin == null -> 0.5
            else -> (margin / 8.0).coerceIn(0.0, 1.0)
        }

        // Si el grafo cambió y el nodo actual ya no existe, se olvida.
        val cur = currentNode
        val curEntry = cur?.let { id -> candidates.firstOrNull { it.second == id } }
        if (cur != null && graph.nodes.none { it.id == cur }) reset()

        if (currentNode == null) {
            // Primer nodo: el mejor debe mantenerse holdMs.
            if (challengerNode == best.second) {
                if (nowMs - challengerSinceMs >= params.holdMs) {
                    currentNode = best.second; confirmedAtMs = nowMs; challengerNode = null
                }
            } else {
                challengerNode = best.second; challengerSinceMs = nowMs
            }
        } else if (best.second == currentNode) {
            challengerNode = null
        } else {
            // Ventaja del retador sobre el nodo actual (si el actual ya no se ve, ventaja "infinita").
            val advantage = if (curEntry == null) Double.MAX_VALUE else best.third - curEntry.third
            val isNeighbor = graph.neighbors(currentNode!!).contains(best.second)
            val required = if (isNeighbor) params.marginDb else params.jumpMarginDb
            if (advantage >= required) {
                if (challengerNode == best.second) {
                    if (nowMs - challengerSinceMs >= params.holdMs) {
                        currentNode = best.second; confirmedAtMs = nowMs; challengerNode = null
                    }
                } else {
                    challengerNode = best.second; challengerSinceMs = nowMs
                }
            } else {
                challengerNode = null
            }
        }

        val stable = if (currentNode != null) nowMs - confirmedAtMs else 0L
        return Decision(currentNode, best.first, margin, confidence, stable)
    }
}
