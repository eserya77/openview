package com.example.openview.data

/**
 * Formas de prueba con 3 nodos (3 balizas) para validar el motor de navegación.
 *
 * Todas reutilizan las mismas balizas y los mismos ids de nodo
 * (inicio = NAV_BLE_01, esquina = NAV_BLE_02, destino = NAV_BLE_03); solo cambia
 * la geometría. Con 3 nodos siempre hay una sola esquina, así que lo que varía
 * entre formas es el ángulo y la dirección del giro. Las coordenadas están en
 * metros pero solo importan de forma relativa: definen la forma, no la escala real.
 */
data class GraphPreset(
    val id: String,
    val name: String,
    val description: String,
    val graph: NavGraph
)

private fun preset(
    id: String,
    name: String,
    description: String,
    esquinaX: Double, esquinaY: Double,
    destinoX: Double, destinoY: Double
) = GraphPreset(
    id = id,
    name = name,
    description = description,
    graph = NavGraph(
        nodes = listOf(
            NavNode("inicio", "Posición 1", 0.0, 0.0, beaconName = "NAV_BLE_01"),
            NavNode("esquina", "Posición 2", esquinaX, esquinaY, beaconName = "NAV_BLE_02"),
            NavNode("destino", "Posición 3", destinoX, destinoY, beaconName = "NAV_BLE_03", isDestination = true),
        ),
        edges = listOf(NavEdge("inicio", "esquina"), NavEdge("esquina", "destino"))
    )
)

object GraphPresets {
    val all: List<GraphPreset> = listOf(
        preset(
            "recta", "Línea recta",
            "Tres balizas en línea. La voz debe decir siempre «sigue recto», sin giros.",
            esquinaX = 0.0, esquinaY = 4.0, destinoX = 0.0, destinoY = 8.0
        ),
        preset(
            "l_derecha", "L a la derecha",
            "Avanzas y en la esquina giras a la derecha. Giro de 90° a la derecha.",
            esquinaX = 0.0, esquinaY = 8.0, destinoX = 6.0, destinoY = 8.0
        ),
        preset(
            "l_izquierda", "L a la izquierda",
            "Avanzas y en la esquina giras a la izquierda. Giro de 90° a la izquierda.",
            esquinaX = 0.0, esquinaY = 8.0, destinoX = -6.0, destinoY = 8.0
        ),
        preset(
            "triangulo", "Triángulo",
            "La ruta forma un pico: giro pronunciado a la derecha en la esquina.",
            esquinaX = 4.0, esquinaY = 7.0, destinoX = 8.0, destinoY = 0.0
        ),
    )

    val default: GraphPreset = all.first { it.id == "l_derecha" }
}

/**
 * Grafo activo seleccionado para las pruebas. El positioner, el ViewModel y la
 * lista de destinos leen de aquí, de modo que cambiar de forma reconfigura toda
 * la app sin tocar las balizas.
 */
object ActiveGraph {
    var preset: GraphPreset = GraphPresets.default
        private set

    val graph: NavGraph get() = preset.graph
    val startNode: String get() = "inicio"
    val destinationNode: String get() = "destino"

    fun select(p: GraphPreset) { preset = p }
}
