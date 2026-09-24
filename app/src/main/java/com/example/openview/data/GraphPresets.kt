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
    /*
     * Las formas siguen disponibles para las demostraciones, pero la navegación
     * normal lee la ruta administrada por RouteRepository. Así, cambiar una
     * baliza o activar una ruta desde el panel no exige recompilar la app.
     */
    private var previewPreset: GraphPreset? = null

    // Grafo temporal de la prueba guiada (posiciones físicas + rotación de balizas). Tiene prioridad
    // sobre todo mientras esa pantalla está abierta, y se limpia al salir.
    private var temporaryGraph: NavGraph? = null
    private var temporaryPath: List<String>? = null

    /** Ruta que está recorriendo la persona en la navegación normal (ids de nodo, en orden). Vacía si no hay ruta. */
    @Volatile var routePath: List<String> = emptyList()

    /** Se incrementa para pedir al posicionador que olvide su progreso (p. ej. al empezar una vuelta de prueba). */
    @Volatile var resetToken: Int = 0

    /**
     * Orden esperado de los puntos: el de la prueba guiada si está abierta; si no, la ruta activa
     * (si tiene al menos 2 nodos). Null = no hay orden conocido y el posicionador usa el seguidor por vecinos.
     */
    val expectedPath: List<String>? get() = temporaryPath ?: routePath.takeIf { it.size >= 2 }

    val preset: GraphPreset get() = previewPreset ?: GraphPresets.default
    val graph: NavGraph get() = temporaryGraph ?: previewPreset?.graph ?: RouteRepository.activeGraph
    val startNode: String get() = graph.nodes.firstOrNull()?.id ?: "inicio"
    val destinationNode: String get() = graph.nodes.lastOrNull()?.id ?: "destino"

    fun useTemporary(g: NavGraph?, path: List<String>? = g?.nodes?.map { it.id }) { temporaryGraph = g; temporaryPath = if (g == null) null else path }
    fun select(p: GraphPreset) { previewPreset = p }
    fun useConfiguredRoute() { previewPreset = null }
}
