package com.example.openview.nav

import com.example.openview.data.Destination
import com.example.openview.data.NavGraph
import com.example.openview.data.RouteStep
import com.example.openview.data.SimulatedRoute
import kotlin.math.atan2
import kotlin.math.roundToInt

/** Velocidad de caminata estimada para el tiempo de ruta (m/s). */
private const val WALK_SPEED = 0.8

/**
 * Motor de ruta: A* sobre el grafo + generación de instrucciones auditivas
 * cuyos giros se derivan de la geometría (no están escritos a mano).
 */
object RouteEngine {

    /** A*: camino mínimo de nodos desde [startId] hasta [goalId]. */
    fun shortestPath(graph: NavGraph, startId: String, goalId: String): List<String> {
        if (startId == goalId) return listOf(startId)

        val open = mutableSetOf(startId)
        val cameFrom = mutableMapOf<String, String>()
        val gScore = mutableMapOf(startId to 0.0)
        val fScore = mutableMapOf(startId to graph.distance(startId, goalId))

        while (open.isNotEmpty()) {
            val current = open.minByOrNull { fScore[it] ?: Double.MAX_VALUE }!!
            if (current == goalId) return reconstruct(cameFrom, current)
            open.remove(current)

            for (next in graph.neighbors(current)) {
                val tentative = (gScore[current] ?: Double.MAX_VALUE) + graph.distance(current, next)
                if (tentative < (gScore[next] ?: Double.MAX_VALUE)) {
                    cameFrom[next] = current
                    gScore[next] = tentative
                    fScore[next] = tentative + graph.distance(next, goalId)
                    open.add(next)
                }
            }
        }
        return emptyList() // sin ruta
    }

    private fun reconstruct(cameFrom: Map<String, String>, goal: String): List<String> {
        val path = mutableListOf(goal)
        var cur = goal
        while (cameFrom.containsKey(cur)) {
            cur = cameFrom.getValue(cur)
            path.add(0, cur)
        }
        return path
    }

    private enum class Turn { LEFT, RIGHT, STRAIGHT }

    /**
     * Dirección del giro en [curr] al venir de [prev] y seguir hacia [next],
     * usando el signo del producto cruzado de los vectores de movimiento.
     */
    private fun turnAt(graph: NavGraph, prev: String, curr: String, next: String): Turn {
        val p = graph.node(prev)
        val c = graph.node(curr)
        val n = graph.node(next)
        val v1x = c.x - p.x; val v1y = c.y - p.y
        val v2x = n.x - c.x; val v2y = n.y - c.y
        val cross = v1x * v2y - v1y * v2x
        val dot = v1x * v2x + v1y * v2y
        val angleDeg = Math.toDegrees(atan2(cross, dot))
        return when {
            angleDeg > 30 -> Turn.LEFT
            angleDeg < -30 -> Turn.RIGHT
            else -> Turn.STRAIGHT
        }
    }

    /**
     * Construye la ruta (instrucciones) a partir de un camino de nodos.
     * Genera un paso por cada transición; el último nodo corresponde a la llegada.
     */
    fun buildRoute(graph: NavGraph, path: List<String>, destination: Destination): SimulatedRoute {
        val steps = mutableListOf<RouteStep>()
        var totalMeters = 0.0

        for (i in 0 until path.size - 1) {
            val curr = path[i]
            val next = path[i + 1]
            val meters = graph.distance(curr, next)
            totalMeters += meters
            val d = meters.roundToInt()
            val nextLabel = graph.node(next).label

            val instruction = if (i == 0) {
                "Avanza recto unos $d metros hasta $nextLabel."
            } else {
                when (turnAt(graph, path[i - 1], curr, next)) {
                    Turn.LEFT -> "Gira a la izquierda y avanza unos $d metros hasta $nextLabel."
                    Turn.RIGHT -> "Gira a la derecha y avanza unos $d metros hasta $nextLabel."
                    Turn.STRAIGHT -> "Sigue recto unos $d metros hasta $nextLabel."
                }
            }
            steps.add(RouteStep(instruction, nextLabel))
        }

        val minutes = (totalMeters / WALK_SPEED / 60.0).roundToInt().coerceAtLeast(1)
        val originLabel = path.firstOrNull()?.let { graph.node(it).label } ?: "tu ubicación"

        return SimulatedRoute(
            origin = originLabel,
            destination = destination,
            steps = if (steps.isEmpty()) listOf(RouteStep("Has llegado a tu destino.", originLabel)) else steps,
            estimatedMinutes = minutes
        )
    }
}
