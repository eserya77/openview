package com.example.openview.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Una ruta editable y guardada en el teléfono. Las aristas son pasillos transitables. */
data class SavedRoute(
    val id: String,
    val name: String,
    val nodes: List<NavNode>,
    val edges: List<NavEdge>
) {
    fun graph(enabledBeacons: Set<String>): NavGraph {
        val usableNodes = nodes.filter { it.beaconName == null || it.beaconName in enabledBeacons }
        val usableIds = usableNodes.map { it.id }.toSet()
        return NavGraph(usableNodes, edges.filter { it.a in usableIds && it.b in usableIds })
    }
}

data class ManagedBeacon(val name: String, val enabled: Boolean = true)

data class RouteCatalog(
    val routes: List<SavedRoute>,
    val activeRouteId: String,
    val beacons: List<ManagedBeacon>
)

/**
 * Almacén local del panel administrador. No requiere cuenta ni red: queda en
 * SharedPreferences y sobrevive al cierre de la aplicación.
 */
object RouteRepository {
    private const val PREFS = "openview_route_admin"
    private const val KEY = "catalog_v1"
    private var initialized = false
    private lateinit var appContext: Context
    private val _catalog = MutableStateFlow(seedCatalog())
    val catalog: StateFlow<RouteCatalog> = _catalog.asStateFlow()

    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        initialized = true
        _catalog.value = load() ?: seedCatalog()
    }

    val activeRoute: SavedRoute
        get() = _catalog.value.routes.firstOrNull { it.id == _catalog.value.activeRouteId }
            ?: _catalog.value.routes.first()

    val activeGraph: NavGraph
        get() = activeRoute.graph(enabledBeaconNames())

    fun enabledBeaconNames(): Set<String> = _catalog.value.beacons.filter { it.enabled }.map { it.name }.toSet()

    fun createRoute(name: String): String {
        val id = "route_${System.currentTimeMillis()}"
        updateCatalog { it.copy(routes = it.routes + SavedRoute(id, name.trim().ifBlank { "Nueva ruta" }, emptyList(), emptyList())) }
        return id
    }

    fun renameRoute(routeId: String, name: String) = updateRoute(routeId) { it.copy(name = name.trim().ifBlank { it.name }) }

    fun deleteRoute(routeId: String) {
        val current = _catalog.value
        if (current.routes.size <= 1) return
        val remaining = current.routes.filterNot { it.id == routeId }
        updateCatalog { it.copy(routes = remaining, activeRouteId = if (it.activeRouteId == routeId) remaining.first().id else it.activeRouteId) }
    }

    fun addNode(routeId: String, label: String, beaconName: String, x: Double, y: Double) {
        rememberBeacon(beaconName)
        updateRoute(routeId) { route ->
            val node = NavNode(
                id = "node_${System.currentTimeMillis()}",
                label = label.trim().ifBlank { "Punto ${route.nodes.size + 1}" },
                x = x, y = y, beaconName = beaconName.trim().uppercase().ifBlank { null },
                isDestination = true
            )
            val previous = route.nodes.lastOrNull()
            val nodes = route.nodes.map { it.copy(isDestination = false) } + node
            route.copy(nodes = nodes, edges = if (previous == null) route.edges else route.edges + NavEdge(previous.id, node.id))
        }
    }

    fun updateNode(routeId: String, nodeId: String, label: String, beaconName: String, x: Double, y: Double) {
        rememberBeacon(beaconName)
        updateRoute(routeId) { route ->
            route.copy(nodes = route.nodes.map { node ->
                if (node.id == nodeId) node.copy(label = label.trim().ifBlank { node.label }, x = x, y = y,
                    beaconName = beaconName.trim().uppercase().ifBlank { null }) else node
            })
        }
    }

    fun removeNode(routeId: String, nodeId: String) = updateRoute(routeId) { route ->
        val remaining = route.nodes.filterNot { it.id == nodeId }
        route.copy(nodes = remaining, edges = route.edges.filterNot { it.a == nodeId || it.b == nodeId })
    }

    fun connect(routeId: String, a: String, b: String) = updateRoute(routeId) { route ->
        if (a == b || route.edges.any { (it.a == a && it.b == b) || (it.a == b && it.b == a) }) route
        else route.copy(edges = route.edges + NavEdge(a, b))
    }

    fun disconnect(routeId: String, edge: NavEdge) = updateRoute(routeId) { route ->
        route.copy(edges = route.edges.filterNot { (it.a == edge.a && it.b == edge.b) || (it.a == edge.b && it.b == edge.a) })
    }

    fun setBeaconEnabled(name: String, enabled: Boolean) = updateCatalog { catalog ->
        val normalized = name.uppercase()
        val current = catalog.beacons
        val updated = if (current.any { it.name == normalized }) {
            current.map { if (it.name == normalized) it.copy(enabled = enabled) else it }
        } else current + ManagedBeacon(normalized, enabled)
        catalog.copy(beacons = updated.sortedBy { it.name })
    }

    fun rememberBeacon(name: String) {
        val normalized = name.uppercase()
        if (!normalized.startsWith("NAV_BLE_")) return
        if (_catalog.value.beacons.none { it.name == normalized }) updateCatalog { it.copy(beacons = it.beacons + ManagedBeacon(normalized)) }
    }

    fun activate(routeId: String): List<String> {
        val problems = validate(routeId)
        if (problems.isEmpty()) {
            updateCatalog { it.copy(activeRouteId = routeId) }
            ActiveGraph.useConfiguredRoute()
        }
        return problems
    }

    /** Errores que impedirían guiar a alguien de manera segura. */
    fun validate(routeId: String): List<String> {
        val route = _catalog.value.routes.firstOrNull { it.id == routeId } ?: return listOf("La ruta ya no existe.")
        val problems = mutableListOf<String>()
        if (route.nodes.size < 3) problems += "Agrega al menos 3 nodos para activar esta ruta."
        if (route.nodes.any { it.beaconName.isNullOrBlank() }) problems += "Asigna una baliza a cada nodo."
        val repeated = route.nodes.mapNotNull { it.beaconName }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (repeated.isNotEmpty()) problems += "Una misma baliza no puede representar dos nodos: ${repeated.joinToString()}."
        val disabled = route.nodes.mapNotNull { it.beaconName }.filter { it !in enabledBeaconNames() }
        if (disabled.isNotEmpty()) problems += "Hay balizas marcadas como no utilizables: ${disabled.distinct().joinToString()}."
        if (route.edges.isEmpty()) problems += "Conecta los nodos mediante al menos un pasillo."
        val graph = route.graph(enabledBeaconNames())
        if (graph.nodes.isNotEmpty() && graph.nodes.size != reachableCount(graph, graph.nodes.first().id)) {
            problems += "Todos los nodos deben quedar conectados con el punto de inicio."
        }
        return problems
    }

    private fun reachableCount(graph: NavGraph, start: String): Int {
        val visited = mutableSetOf(start)
        val queue = ArrayDeque<String>().apply { add(start) }
        while (queue.isNotEmpty()) graph.neighbors(queue.removeFirst()).forEach { if (visited.add(it)) queue.add(it) }
        return visited.size
    }

    private fun updateRoute(routeId: String, change: (SavedRoute) -> SavedRoute) = updateCatalog { catalog ->
        catalog.copy(routes = catalog.routes.map { if (it.id == routeId) change(it) else it })
    }

    private fun updateCatalog(change: (RouteCatalog) -> RouteCatalog) {
        _catalog.value = change(_catalog.value)
        if (initialized) save(_catalog.value)
    }

    private fun save(catalog: RouteCatalog) {
        val root = JSONObject().put("active", catalog.activeRouteId)
        root.put("beacons", JSONArray().apply { catalog.beacons.forEach { put(JSONObject().put("name", it.name).put("enabled", it.enabled)) } })
        root.put("routes", JSONArray().apply {
            catalog.routes.forEach { route -> put(JSONObject().apply {
                put("id", route.id); put("name", route.name)
                put("nodes", JSONArray().apply { route.nodes.forEach { node -> put(JSONObject().apply {
                    put("id", node.id); put("label", node.label); put("x", node.x); put("y", node.y)
                    put("beacon", node.beaconName); put("destination", node.isDestination)
                }) } })
                put("edges", JSONArray().apply { route.edges.forEach { edge -> put(JSONObject().put("a", edge.a).put("b", edge.b)) } })
            }) }
        })
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, root.toString()).apply()
    }

    private fun load(): RouteCatalog? = runCatching {
        val raw = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        val root = JSONObject(raw)
        val beacons = root.getJSONArray("beacons").mapObjects { ManagedBeacon(it.getString("name"), it.optBoolean("enabled", true)) }
        val routes = root.getJSONArray("routes").mapObjects { obj ->
            val nodes = obj.getJSONArray("nodes").mapObjects { node -> NavNode(node.getString("id"), node.getString("label"), node.getDouble("x"), node.getDouble("y"), node.optString("beacon").ifBlank { null }, node.optBoolean("destination")) }
            val edges = obj.getJSONArray("edges").mapObjects { edge -> NavEdge(edge.getString("a"), edge.getString("b")) }
            SavedRoute(obj.getString("id"), obj.getString("name"), nodes, edges)
        }
        if (routes.isEmpty()) null else RouteCatalog(routes, root.optString("active", routes.first().id), beacons)
    }.getOrNull()

    private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
        (0 until length()).map { transform(getJSONObject(it)) }

    private fun seedCatalog(): RouteCatalog {
        val route = SavedRoute("pilot_l", "Ruta piloto en L", GraphPresets.default.graph.nodes, GraphPresets.default.graph.edges)
        return RouteCatalog(listOf(route), route.id, (1..6).map { ManagedBeacon("NAV_BLE_%02d".format(it)) })
    }
}
