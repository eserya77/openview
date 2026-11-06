package com.example.openview.data

import com.example.openview.nav.GuideSpot
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/*
 * Un LUGAR (establecimiento) tal como lo entrega la empresa en su JSON y lo comparte el guardia: sus balizas con nombre
 * propio («Entrada», «Pasillo A», «Baño»), por dónde se puede caminar entre ellas y sus rutas.
 *
 * Kotlin puro (sin Android): el cálculo de rutas y de las indicaciones «ve recto / gira a la derecha» se prueba en tests.
 *
 * Plano: x hacia la derecha, y hacia delante (como un plano visto desde arriba), en metros. Ángulos en grados:
 * 0° = hacia +x, 90° = hacia +y (sentido antihorario). Un giro positivo es a la IZQUIERDA.
 */

/** Una baliza del lugar con su nombre para la persona usuaria. */
data class PlaceBeacon(
    /** Nombre BLE de la baliza, p. ej. NAV_BLE_03. */
    val beacon: String,
    /** Nombre del punto, p. ej. «Baño». */
    val name: String,
    val x: Double,
    val y: Double,
    /**
     * Hacia dónde mira (grados) quien está en este punto en su postura natural (p. ej. en la entrada, de espaldas a la
     * puerta). Si se conoce, la primera indicación puede ser «gira a la derecha y camina…». Opcional.
     */
    val facing: Double? = null,
    /** Frase extra al llegar (p. ej. «La puerta está a tu izquierda»). Opcional. */
    val note: String? = null
)

/** Una ruta publicada por la empresa: los puntos por los que pasa y, si quiere, sus propias indicaciones. */
data class PlaceRoute(
    val from: String,
    val to: String,
    val path: List<String>,
    /** Una indicación por tramo (path.size − 1). Si falta, se generan a partir del plano. */
    val directions: List<String>? = null
)

data class SharedPlace(
    val id: String,
    val name: String,
    val beacons: List<PlaceBeacon>,
    /** Pares de balizas entre las que se puede caminar en línea recta. Vacío = todas con todas. */
    val links: List<Pair<String, String>> = emptyList(),
    val routes: List<PlaceRoute> = emptyList(),
    /** Baliza de la entrada (por convención NAV_BLE_01). */
    val entrance: String? = null,
    val company: String? = null,
    val version: Int = 1
) {
    fun beacon(id: String): PlaceBeacon? = beacons.firstOrNull { it.beacon.equals(id, ignoreCase = true) }
    fun nameOf(id: String): String = beacon(id)?.name ?: id
}

/** Un tramo de la ruta ya explicado. */
data class RouteLeg(val from: PlaceBeacon, val to: PlaceBeacon, val meters: Double, val turn: Turn?, val text: String)

enum class Turn(val phrase: String) {
    STRAIGHT("sigue recto"),
    SLIGHT_LEFT("gira un poco a la izquierda"),
    LEFT("gira a la izquierda"),
    SLIGHT_RIGHT("gira un poco a la derecha"),
    RIGHT("gira a la derecha"),
    BACK("da media vuelta")
}

object PlacePlanner {

    private val es: Locale = Locale.forLanguageTag("es")

    fun distance(a: PlaceBeacon, b: PlaceBeacon): Double = hypot(b.x - a.x, b.y - a.y)

    /** Rumbo de a hacia b en grados (0° = +x, 90° = +y). */
    fun heading(a: PlaceBeacon, b: PlaceBeacon): Double = Math.toDegrees(atan2(b.y - a.y, b.x - a.x))

    /** Giro de un rumbo a otro, en −180..180 (positivo = izquierda). */
    fun turnAngle(fromHeading: Double, toHeading: Double): Double {
        var d = (toHeading - fromHeading) % 360.0
        if (d > 180) d -= 360.0
        if (d < -180) d += 360.0
        return d
    }

    fun turnOf(angle: Double): Turn = when {
        abs(angle) < 25 -> Turn.STRAIGHT
        abs(angle) >= 150 -> Turn.BACK
        angle > 0 && angle < 50 -> Turn.SLIGHT_LEFT
        angle > 0 -> Turn.LEFT
        angle > -50 -> Turn.SLIGHT_RIGHT
        else -> Turn.RIGHT
    }

    fun metersText(m: Double): String = when {
        m < 1.0 -> "${(m * 100).roundToInt()} centímetros"
        abs(m - m.roundToInt()) < 0.05 -> "${m.roundToInt()} ${if (m.roundToInt() == 1) "metro" else "metros"}"
        else -> "%.1f metros".format(es, m)
    }

    /** Vecinos caminables de cada baliza. */
    private fun neighbours(place: SharedPlace): Map<String, List<String>> {
        val ids = place.beacons.map { it.beacon }
        if (place.links.isEmpty()) return ids.associateWith { a -> ids.filter { it != a } }
        val m = ids.associateWith { mutableListOf<String>() }
        place.links.forEach { (a, b) ->
            val ka = ids.firstOrNull { it.equals(a, true) } ?: return@forEach
            val kb = ids.firstOrNull { it.equals(b, true) } ?: return@forEach
            m[ka]!! += kb; m[kb]!! += ka
        }
        return m
    }

    /**
     * Camino de [from] a [to]: la ruta que publicó la empresa si la hay (o la misma al revés), y si no, la más corta
     * por el plano (A* sobre las conexiones; heurística: distancia en línea recta al destino). Null si no hay camino.
     */
    fun path(place: SharedPlace, from: String, to: String): List<String>? {
        val a = place.beacon(from)?.beacon ?: return null
        val b = place.beacon(to)?.beacon ?: return null
        if (a == b) return listOf(a)
        place.routes.firstOrNull { it.from.equals(a, true) && it.to.equals(b, true) }?.let { r -> return r.path.map { place.beacon(it)?.beacon ?: it } }
        place.routes.firstOrNull { it.from.equals(b, true) && it.to.equals(a, true) }?.let { r -> return r.path.reversed().map { place.beacon(it)?.beacon ?: it } }
        val nb = neighbours(place)
        val goal = place.beacon(b)!!
        // Heurística admisible: la distancia en línea recta nunca supera la del camino real.
        fun h(id: String) = distance(place.beacon(id)!!, goal)
        val dist = mutableMapOf(a to 0.0)
        val prev = mutableMapOf<String, String>()
        val open = mutableSetOf(a)
        while (open.isNotEmpty()) {
            val u = open.minByOrNull { (dist[it] ?: Double.MAX_VALUE) + h(it) }!!
            open -= u
            if (u == b) break
            val pu = place.beacon(u)!!
            for (v in nb[u].orEmpty()) {
                val alt = dist[u]!! + distance(pu, place.beacon(v)!!)
                if (alt < (dist[v] ?: Double.MAX_VALUE)) { dist[v] = alt; prev[v] = u; open += v }
            }
        }
        if (b !in dist) return null
        val out = ArrayList<String>()
        var cur: String? = b
        while (cur != null) { out.add(0, cur); cur = prev[cur] }
        return out
    }

    /** Las indicaciones que la empresa escribió para este camino exacto (en ese sentido), si las hay. */
    private fun authored(place: SharedPlace, path: List<String>): List<String>? =
        place.routes.firstOrNull { r -> r.path.map { it.uppercase() } == path.map { it.uppercase() } }?.directions
            ?.takeIf { it.size == path.size - 1 }

    /**
     * Explica el camino tramo a tramo. [initialHeading]: hacia dónde mira la persona al empezar (el rumbo con el que
     * llegó al punto de salida, o el «facing» de ese punto). Sin él, la primera indicación no habla de girar.
     */
    fun legs(place: SharedPlace, path: List<String>, initialHeading: Double? = null): List<RouteLeg> {
        if (path.size < 2) return emptyList()
        val pts = path.map { place.beacon(it) ?: error("Baliza desconocida: $it") }
        val own = authored(place, path)
        var facing = initialHeading ?: pts[0].facing
        return (0 until pts.size - 1).map { i ->
            val a = pts[i]; val b = pts[i + 1]
            val h = heading(a, b)
            val m = distance(a, b)
            val turn = facing?.let { turnOf(turnAngle(it, h)) }
            facing = h
            val isLast = i == pts.size - 2
            val dest = if (isLast) "${b.name}, tu destino" else b.name
            val text = own?.get(i) ?: when {
                i == 0 && turn == null -> "Camina ${metersText(m)} hasta $dest. Los pitidos se aceleran si vas bien."
                i == 0 && turn == Turn.STRAIGHT -> "Camina recto ${metersText(m)} hasta $dest."
                i == 0 -> "${turn!!.phrase.replaceFirstChar { it.uppercase() }} y camina ${metersText(m)} hasta $dest."
                turn == Turn.STRAIGHT -> "Sigue recto ${metersText(m)} hasta $dest."
                else -> "${turn!!.phrase.replaceFirstChar { it.uppercase() }} y camina ${metersText(m)} hasta $dest."
            }
            RouteLeg(a, b, m, turn, text)
        }.let { legs ->
            // Si en el siguiente punto hay que girar, pedir que se detenga un momento: con más muestras el punto se
            // confirma mejor (simulación de capacidad: con 50 % de anuncios perdidos, el éxito sube de 70 % a 92,5 %).
            if (own != null) legs
            else legs.mapIndexed { i, l ->
                val next = legs.getOrNull(i + 1)
                if (next?.turn != null && next.turn != Turn.STRAIGHT) l.copy(text = l.text + " Al llegar, detente un momento.") else l
            }
        }
    }

    /** Los puntos del camino para el motor de guía. */
    fun spots(place: SharedPlace, path: List<String>): List<GuideSpot> =
        path.map { id -> place.beacon(id)!!.let { GuideSpot(it.beacon, it.name, it.beacon, it.x, it.y) } }

    /** Destinos a los que se puede ir desde [from] (todos los demás con camino), la entrada al final. */
    fun destinationsFrom(place: SharedPlace, from: String?): List<PlaceBeacon> =
        place.beacons.filter { !it.beacon.equals(from, true) && (from == null || path(place, from, it.beacon) != null) }
            .sortedBy { if (it.beacon.equals(place.entrance, true)) 1 else 0 }

    /** Resumen hablado de la ruta completa, p. ej. antes de empezar. */
    fun summary(place: SharedPlace, path: List<String>, initialHeading: Double? = null): String {
        val l = legs(place, path, initialHeading)
        if (l.isEmpty()) return ""
        val total = l.sumOf { it.meters }
        return "Ruta de ${metersText(total)}" + (if (l.size > 1) ", pasando por ${l.dropLast(1).joinToString { it.to.name }}." else ".")
    }
}

/** Lugares de ejemplo con las 3 balizas disponibles (NAV_BLE_01..03) a 2 m: recto, en L y en triángulo. */
object DemoPlaces {
    private val NAMES = listOf("NAV_BLE_01" to "Entrada", "NAV_BLE_02" to "Pasillo A", "NAV_BLE_03" to "Baño")

    private fun make(id: String, name: String, pts: List<Pair<Double, Double>>, links: List<Pair<String, String>>, desc: String): SharedPlace {
        val beacons = NAMES.mapIndexed { i, (b, n) ->
            PlaceBeacon(b, n, pts[i].first, pts[i].second, facing = if (i == 0) 0.0 else null,
                note = if (i == 0) "Estás en la entrada. De espaldas a la puerta, el interior queda de frente." else null)
        }
        val place = SharedPlace(id, name, beacons, links, entrance = "NAV_BLE_01", company = desc)
        val routes = beacons.flatMap { a -> beacons.filter { it != a }.map { b -> PlaceRoute(a.beacon, b.beacon, PlacePlanner.path(place, a.beacon, b.beacon)!!) } }
        return place.copy(routes = routes)
    }

    private val chain = listOf("NAV_BLE_01" to "NAV_BLE_02", "NAV_BLE_02" to "NAV_BLE_03")

    /** 01 ─2 m─ 02 ─2 m─ 03 en línea. */
    val RECTO = make("demo_recto", "Plaza San Miguel (prueba recta)", listOf(0.0 to 0.0, 2.0 to 0.0, 4.0 to 0.0), chain, "Ejemplo: 3 balizas en línea recta, 2 m entre cada una.")
    /** 01 ─2 m─ 02, y desde 02 giro a la izquierda 2 m hasta 03. */
    val EN_L = make("demo_l", "Plaza San Miguel (prueba en L)", listOf(0.0 to 0.0, 2.0 to 0.0, 2.0 to 2.0), chain, "Ejemplo: 3 balizas en L, 2 m cada tramo; en Pasillo A se gira.")
    /** Triángulo equilátero de 2 m: se puede ir directo entre cualquier par. */
    val TRIANGULO = make("demo_triangulo", "Plaza San Miguel (prueba en triángulo)", listOf(0.0 to 0.0, 2.0 to 0.0, 1.0 to 1.732), emptyList(), "Ejemplo: 3 balizas en triángulo de 2 m de lado; caminos directos.")

    val ALL = listOf(RECTO, EN_L, TRIANGULO)
}
