package com.example.openview.nav

import com.example.openview.logging.CENTER_NODE_ID

enum class Algorithm { V2, V3 }

/**
 * Parámetros del seguidor de ruta (v3). Los márgenes están en dB de "distancia de huella" o de RSSI
 * relativo, según cómo se puntúen los nodos; los valores salen de las pruebas del 23-sep-2026.
 */
data class RouteParams(
    /** Ventaja mínima para pasar al SIGUIENTE nodo de la ruta. Pequeña: es lo esperado. */
    val nextMarginDb: Double = 0.5,
    val holdMs: Long = 500L,
    /** Tiempo seguido sin ser el nodo actual antes de darse por "en tránsito" (ningún nodo). */
    val leaveHoldMs: Long = 1_000L,
    /** Ventaja para volver a un nodo anterior de la ruta. */
    val backMarginDb: Double = 2.0,
    /** Ventaja y tiempo para saltar 2 o más nodos (un salto es sospechoso: suele ser una baliza "que se cuela"). */
    val skipMarginDb: Double = 4.0,
    val skipHoldMs: Long = 3_000L,
    /** Tiempo para confirmar de nuevo el nodo donde ya estabas (tras salir un momento). */
    val sameHoldMs: Long = 300L
)

/**
 * Seguidor de ruta: la app conoce el ORDEN de los puntos, así que solo acepta avanzar al siguiente.
 * Distingue dos cosas:
 *  - progreso: hasta qué punto de la ruta llegaste;
 *  - dónde estás AHORA: un nodo confirmado, o ninguno (en tránsito, o en el centro).
 *
 * Recibe una puntuación por nodo (mayor = mejor): -distancia a la huella, o RSSI relativo a su nivel
 * de contacto. La clave [CENTER_NODE_ID] representa "ningún nodo cerca": si gana, no hay nodo.
 * Kotlin puro, con reloj inyectado.
 */
class RouteTracker(private val p: RouteParams = RouteParams()) {

    data class Decision(
        val nodeId: String?,
        /** Nodo con mejor puntuación en este instante (aunque aún no esté confirmado). */
        val candidate: String?,
        val marginDb: Double?,
        val confidence: Double,
        val stableMs: Long,
        val progressIdx: Int
    )

    private var progress = -1
    private var at = -1
    private var challenger = -2
    private var challengerSince = 0L
    private var leaveSince = -1L
    private var confirmedAt = 0L

    fun reset() { progress = -1; at = -1; challenger = -2; leaveSince = -1L }

    fun update(scores: Map<String, Double>, path: List<String>, nowMs: Long): Decision {
        if (scores.isEmpty() || path.isEmpty()) { reset(); return Decision(null, null, null, 0.0, 0L, -1) }

        val best = scores.maxByOrNull { it.value }!!
        val c = path.indexOf(best.key)                       // -1 si gana "ningún nodo"
        val sorted = scores.values.sortedDescending()
        val margin = if (sorted.size >= 2) sorted[0] - sorted[1] else null
        val confidence = margin?.let { (it / 4.0).coerceIn(0.0, 1.0) } ?: 0.5

        // 1) Dejar el nodo actual: cuando lleva un rato sin ser el mejor candidato.
        if (at >= 0 && c != at) {
            if (leaveSince < 0) leaveSince = nowMs
            if (nowMs - leaveSince >= p.leaveHoldMs) at = -1
        } else leaveSince = -1L

        // 2) Aceptar un candidato nuevo según su posición respecto al progreso.
        if (c >= 0 && at != c) {
            val sc = scores.getValue(path[c])
            val ref = if (progress >= 0) scores[path[progress]] ?: -1e9 else -1e9
            var adv = sc - ref
            val req: Double
            val hold: Long
            when {
                progress == -1 && c == 0 -> { req = 0.0; hold = p.holdMs }
                progress == -1 -> {                      // primer nodo que no es el inicio de la ruta: hace falta claridad
                    val second = scores.filterKeys { it != path[c] }.values.maxOrNull() ?: -1e9
                    adv = sc - second; req = p.skipMarginDb; hold = p.skipHoldMs
                }
                c == progress + 1 -> { req = p.nextMarginDb; hold = p.holdMs }
                c == progress -> { req = 0.0; hold = p.sameHoldMs }
                c < progress -> { req = p.backMarginDb; hold = p.holdMs }
                else -> {                                // salto de 2+ nodos: debe superar también a los nodos intermedios
                    adv = sc - (progress until c).maxOf { scores[path[it]] ?: -1e9 }
                    req = p.skipMarginDb; hold = p.skipHoldMs
                }
            }
            if (adv >= req) {
                if (challenger == c) {
                    if (nowMs - challengerSince >= hold) {
                        progress = c; at = c; challenger = -2; leaveSince = -1L; confirmedAt = nowMs
                    }
                } else { challenger = c; challengerSince = nowMs }
            } else challenger = -2
        } else if (c < 0 || at == c) challenger = -2

        val stable = if (at >= 0) nowMs - confirmedAt else 0L
        return Decision(path.getOrNull(at), path.getOrNull(c), margin, confidence, stable, progress)
    }
}
