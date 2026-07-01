package com.example.openview.data

import kotlin.math.hypot

/**
 * Nodo del grafo de navegación: un lugar o punto de decisión del edificio.
 * Las coordenadas (x, y) están en metros, en un marco local donde
 * x = derecha/este, y = adelante/norte respecto al punto de inicio.
 */
data class NavNode(
    val id: String,
    val label: String,
    val x: Double,
    val y: Double,
    val beaconName: String? = null,
    val isDestination: Boolean = false
)

/** Arista no dirigida (camino transitable) entre dos nodos. */
data class NavEdge(val a: String, val b: String)

/** Grafo de navegación: nodos + aristas. La geometría permite derivar giros con A*. */
class NavGraph(val nodes: List<NavNode>, val edges: List<NavEdge>) {

    private val byId = nodes.associateBy { it.id }

    fun node(id: String): NavNode = byId.getValue(id)

    fun neighbors(id: String): List<String> =
        edges.mapNotNull {
            when (id) {
                it.a -> it.b
                it.b -> it.a
                else -> null
            }
        }

    /** Distancia euclidiana entre dos nodos, en metros. */
    fun distance(a: String, b: String): Double {
        val na = node(a)
        val nb = node(b)
        return hypot(na.x - nb.x, na.y - nb.y)
    }

    fun nodeForBeacon(beaconName: String): NavNode? =
        nodes.firstOrNull { it.beaconName == beaconName }

    /** Nodo más cercano a una coordenada (x, y) arbitraria. */
    fun nearestNode(x: Double, y: Double): NavNode =
        nodes.minByOrNull { hypot(it.x - x, it.y - y) }!!
}

/**
 * Grafo del entorno piloto: una ruta en L con tres balizas.
 *
 *   inicio (0,0) ──8 m──► esquina (0,8) ──6 m──► destino (6,8)
 *
 * Al ir inicio → esquina → destino, la geometría produce un giro a la derecha
 * en la esquina de forma automática (no está codificado el "derecha").
 */
object PilotGraph {
    const val START_NODE = "inicio"
    const val DESTINATION_NODE = "destino"

    val graph = NavGraph(
        nodes = listOf(
            NavNode("inicio", "Posición 1", 0.0, 0.0, beaconName = "NAV_BLE_01"),
            NavNode("esquina", "Posición 2", 0.0, 8.0, beaconName = "NAV_BLE_02"),
            NavNode("destino", "Posición 3", 6.0, 8.0, beaconName = "NAV_BLE_03", isDestination = true),
        ),
        edges = listOf(
            NavEdge("inicio", "esquina"),
            NavEdge("esquina", "destino"),
        )
    )
}
