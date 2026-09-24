package com.example.openview.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/** Un lugar físico de la prueba (donde se coloca una baliza). Independiente de QUÉ baliza haya allí. */
data class TestSpot(val id: String, val label: String, val x: Double, val y: Double)

/**
 * Escenario de pruebas: las posiciones físicas (en metros) donde se colocan las balizas, en el
 * orden en que se recorren. Las balizas se asignan a estas posiciones con una [Rotation].
 *
 * [startX]/[startY]: punto de inicio ("centro"). Si son null se usa el centroide de las posiciones.
 * [power]: de qué se alimenta la baliza puesta en cada posición ("PC", "Cargador", "Otro"). Es un
 * factor a registrar: se queda con la posición cuando las placas se intercambian.
 */
data class TestSetup(
    val id: String,
    val name: String,
    val spots: List<TestSpot>,
    val startX: Double? = null,
    val startY: Double? = null,
    val power: Map<String, String> = emptyMap()
) {
    val center: Pair<Double, Double>
        get() = if (startX != null && startY != null) startX to startY
        else spots.map { it.x }.average() to spots.map { it.y }.average()
}

/** Asignación de balizas a posiciones: [beaconAt] tiene una baliza por posición, en el orden de [TestSetup.spots]. */
data class Rotation(val id: String, val name: String, val beaconAt: List<String>)

/**
 * Genera las rotaciones (permutaciones) disponibles para elegir en una prueba.
 * Con la base = [beacons] en orden (baliza i en la posición i):
 *  - R0: base.
 *  - R1..R(n-1): desplazamientos cíclicos. Cada placa pasa por cada posición exactamente una vez
 *    entre R0..R(n-1) (cuadrado latino), que es lo que permite separar "efecto de la placa" de
 *    "efecto de la posición".
 *  - Inversa, pares intercambiados (1↔2, 3↔4…) y una aleatoria.
 */
object Rotations {

    fun options(beacons: List<String>, random: Random = Random.Default): List<Rotation> {
        val n = beacons.size
        if (n < 2) return emptyList()
        val out = mutableListOf<Rotation>()
        out += Rotation("R0", "R0 · Base (sin mover)", beacons)
        for (k in 1 until n) {
            out += Rotation("R$k", "R$k · Desplazar $k posición(es)", List(n) { i -> beacons[Math.floorMod(i - k, n)] })
        }
        out += Rotation("INV", "Inversa (orden al revés)", beacons.reversed())
        out += Rotation("PAR", "Pares intercambiados (1↔2, 3↔4…)", List(n) { i -> beacons[if (i % 2 == 0 && i + 1 < n) i + 1 else if (i % 2 == 1) i - 1 else i] })
        val distinct = out.map { it.beaconAt }.toSet()
        var tries = 0
        while (tries < 20) {
            val shuffled = beacons.shuffled(random)
            if (shuffled !in distinct) { out += Rotation("RND", "Aleatoria", shuffled); break }
            tries++
        }
        // Sin duplicados (con n = 2 varias coinciden): se conserva la primera de cada asignación.
        return out.distinctBy { it.beaconAt }
    }

    fun custom(beaconAt: List<String>) = Rotation("CUS", "Personalizada", beaconAt)

    fun isValid(beaconAt: List<String>, beacons: List<String>): Boolean =
        beaconAt.size == beacons.size && beaconAt.toSet() == beacons.toSet()
}

/** Construye el grafo de navegación de una prueba: un nodo por posición, con la baliza que la rotación pone allí. */
object TestLayout {
    fun graph(setup: TestSetup, rotation: Rotation): NavGraph {
        val nodes = setup.spots.mapIndexed { i, s ->
            NavNode(s.id, s.label, s.x, s.y, beaconName = rotation.beaconAt.getOrNull(i), isDestination = i == setup.spots.lastIndex)
        }
        val edges = nodes.zipWithNext().map { (a, b) -> NavEdge(a.id, b.id) }
        return NavGraph(nodes, edges)
    }

    /** Clave de la disposición para las huellas: escenario + qué baliza en cada posición + tipo de parada. */
    fun key(setup: TestSetup, rotation: Rotation, stayId: String): String = "${setup.id}|${describe(setup, rotation)}|$stayId"

    /** Texto compacto para el log: "P1=NAV_BLE_03;P2=NAV_BLE_06;…". */
    fun describe(setup: TestSetup, rotation: Rotation): String =
        setup.spots.mapIndexed { i, s -> "${s.id}=${rotation.beaconAt.getOrNull(i)}" }.joinToString(";")
}

/** Escenarios guardados en el teléfono. Sobreviven al cierre de la app. */
object TestSetupRepository {
    private const val PREFS = "openview_test_setups"
    private const val KEY = "setups_v1"
    private var initialized = false
    private lateinit var appContext: Context

    /** [assignment]: última asignación de balizas usada por escenario (setupId -> baliza en cada posición). */
    data class State(val setups: List<TestSetup>, val selectedId: String, val laps: Map<String, Int>, val assignment: Map<String, List<String>> = emptyMap())

    private val _state = MutableStateFlow(State(seed(), SALA_ID, emptyMap()))
    val state: StateFlow<State> = _state.asStateFlow()

    const val SALA_ID = "sala_trapecio"
    const val CUARTO_ID = "cuarto_cuadrado"
    const val RECTA_ID = "sala_recta"

    /** Balizas por defecto de cada escenario de fábrica (posición i -> baliza). */
    val DEFAULT_BEACONS: Map<String, List<String>> = mapOf(RECTA_ID to listOf("NAV_BLE_01", "NAV_BLE_02", "NAV_BLE_03"))

    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        initialized = true
        load()?.let { _state.value = it }
    }

    val selected: TestSetup get() = _state.value.setups.firstOrNull { it.id == _state.value.selectedId } ?: _state.value.setups.first()

    fun select(id: String) = update { it.copy(selectedId = id) }

    fun updateSetup(setup: TestSetup) = update { st -> st.copy(setups = st.setups.map { if (it.id == setup.id) setup else it }) }

    fun resetSetup(id: String) = update { st ->
        val original = seed().firstOrNull { it.id == id } ?: return@update st
        st.copy(setups = st.setups.map { if (it.id == id) original else it })
    }

    /** Número de vuelta siguiente para (escenario, rotación): 1, 2, 3… */
    fun nextLap(setupId: String, rotationId: String): Int = (_state.value.laps["$setupId|$rotationId"] ?: 0) + 1

    /** Recuerda qué baliza hay en cada posición (lo usa la guía de usuario para no volver a preguntarlo). */
    fun rememberAssignment(setupId: String, beaconAt: List<String>) = update { it.copy(assignment = it.assignment + (setupId to beaconAt)) }

    fun assignmentFor(setupId: String): List<String>? = _state.value.assignment[setupId] ?: DEFAULT_BEACONS[setupId]

    fun lapDone(setupId: String, rotationId: String) = update { st ->
        val k = "$setupId|$rotationId"
        st.copy(laps = st.laps + (k to (st.laps[k] ?: 0) + 1))
    }

    private fun update(change: (State) -> State) {
        _state.value = change(_state.value)
        if (initialized) save(_state.value)
    }

    private fun seed(): List<TestSetup> = listOf(
        TestSetup(
            SALA_ID, "Sala (trapecio, 4 posiciones)",
            listOf(
                TestSpot("P1", "Posición 1", 0.8, 0.0),
                TestSpot("P2", "Posición 2", 2.3, 0.0),
                TestSpot("P3", "Posición 3", 4.0, 2.7),
                TestSpot("P4", "Posición 4", 0.0, 1.9)
            )
        ),
        // Prueba del 24-sep-2026: sala en línea recta, 01 · 2,10 m · 02 · 2,60 m · 03 (firmware v3). Inicio 1 m antes de la 01.
        TestSetup(
            RECTA_ID, "Sala recta (01 · 2,10 m · 02 · 2,60 m · 03)",
            listOf(
                TestSpot("P1", "Baliza 1", 0.0, 0.0),
                TestSpot("P2", "Baliza 2", 2.10, 0.0),
                TestSpot("P3", "Baliza 3", 4.70, 0.0)
            ),
            startX = -1.0, startY = 0.0
        ),
        TestSetup(
            CUARTO_ID, "Cuarto pequeño (cuadrado)",
            listOf(
                TestSpot("P1", "Posición 1", 0.0, 0.0),
                TestSpot("P2", "Posición 2", 0.0, 2.25),
                TestSpot("P3", "Posición 3", 1.85, 2.2),
                TestSpot("P4", "Posición 4", 1.65, 0.0)
            )
        )
    )

    private fun save(st: State) {
        val root = JSONObject().put("selected", st.selectedId)
        root.put("laps", JSONObject().also { o -> st.laps.forEach { (k, v) -> o.put(k, v) } })
        root.put("assignment", JSONObject().also { o -> st.assignment.forEach { (k, v) -> o.put(k, JSONArray(v)) } })
        root.put("setups", JSONArray().also { arr ->
            st.setups.forEach { s ->
                arr.put(JSONObject().apply {
                    put("id", s.id); put("name", s.name)
                    put("startX", s.startX ?: JSONObject.NULL); put("startY", s.startY ?: JSONObject.NULL)
                    put("spots", JSONArray().also { a -> s.spots.forEach { sp -> a.put(JSONObject().put("id", sp.id).put("label", sp.label).put("x", sp.x).put("y", sp.y)) } })
                    put("power", JSONObject().also { o -> s.power.forEach { (k, v) -> o.put(k, v) } })
                })
            }
        })
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, root.toString()).apply()
    }

    private fun load(): State? = runCatching {
        val raw = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        val root = JSONObject(raw)
        val setups = root.getJSONArray("setups").let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val spots = o.getJSONArray("spots").let { a ->
                    (0 until a.length()).map { j -> a.getJSONObject(j).let { TestSpot(it.getString("id"), it.getString("label"), it.getDouble("x"), it.getDouble("y")) } }
                }
                val power = o.optJSONObject("power")?.let { p -> p.keys().asSequence().associateWith { k -> p.getString(k) } } ?: emptyMap()
                TestSetup(
                    o.getString("id"), o.getString("name"), spots,
                    if (o.isNull("startX")) null else o.getDouble("startX"),
                    if (o.isNull("startY")) null else o.getDouble("startY"),
                    power
                )
            }
        }
        val laps = root.optJSONObject("laps")?.let { l -> l.keys().asSequence().associateWith { k -> l.getInt(k) } } ?: emptyMap()
        val assignment = root.optJSONObject("assignment")?.let { a ->
            a.keys().asSequence().associateWith { k -> a.getJSONArray(k).let { arr -> (0 until arr.length()).map { arr.getString(it) } } }
        } ?: emptyMap()
        // Los escenarios de fábrica que falten (versiones nuevas) se agregan al final.
        val merged = setups + seed().filter { s -> setups.none { it.id == s.id } }
        if (merged.isEmpty()) null else State(merged, root.optString("selected", merged.first().id), laps, assignment)
    }.getOrNull()
}
