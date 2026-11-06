package com.example.openview.nav

import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * Motor de la GUÍA para la persona usuaria (propuestas v4: B1 zonas + tendencia, A1 frío/caliente, A4 llegada
 * automática, A5 guía continua). No calcula coordenadas: para cada baliza estima su nivel y su pendiente
 * (Kalman de 2 estados), la clasifica en una ZONA y detecta cuándo pasas junto a cada punto de la ruta («hitos»).
 *
 * Kotlin puro con reloj inyectado: la misma lógica corre en vivo y en los tests con logs reales.
 */
enum class Zone(val label: String) { LEJOS("lejos"), SALA("en la sala"), CERCA("cerca"), TOCANDO("tocando") }

/** Un punto de la ruta con la baliza que lo marca. */
data class GuideSpot(val id: String, val label: String, val beacon: String, val x: Double, val y: Double)

data class GuideParams(
    /** Referencia a 1 m por defecto si la baliza no está calibrada (medida con el Redmi Note 14 en 04/05/06). */
    val defaultRefAt1m: Double = -80.0,
    /** Contacto ≈ referencia a 1 m + esto, si no hay perfil de contacto. */
    val contactAboveRef: Double = 25.0,
    /** TOCANDO: a menos de esto del nivel de contacto. */
    val touchBelowContact: Double = 8.0,
    /** CERCA (≈ 1 m o menos): nivel ≥ referencia a 1 m + esto. */
    val nearAboveRef: Double = 2.0,
    /** Por debajo de esto no se considera oída. */
    val floorDbm: Double = -95.0,
    val hysteresisDb: Double = 2.0,
    /** Una baliza sin muestras durante este tiempo se da por perdida. */
    val expireMs: Long = 3_000L,
    /** Para decir "estás junto a X": nivel relativo (nivel − ref. a 1 m) de al menos esto (≈ 1,25 m o menos)… */
    val spotMinRelDb: Double = -2.0,
    /** …superando al de las demás balizas en esto… */
    val dominanceDb: Double = 4.0,
    /** …durante este tiempo. */
    val spotHoldMs: Long = 500L,
    /** Volver a un punto ANTERIOR exige más: evita falsos "vas hacia atrás" por una baliza que se cuela. */
    val backDominanceDb: Double = 6.0,
    val backHoldMs: Long = 1_200L,
    /** Saltarse puntos (o empezar por uno que no es el primero) también exige más. */
    val skipDominanceDb: Double = 6.0,
    val skipHoldMs: Long = 1_500L,
    /** Una baliza recién oída no decide nada hasta tener estas muestras y este tiempo (el filtro arranca inestable). */
    val warmupSamples: Int = 5,
    val warmupMs: Long = 1_000L,
    /** Llegada al destino: tocando este tiempo, o quieto este otro, o (sin acelerómetro) junto a él este otro. */
    val arriveTouchMs: Long = 500L,
    val arriveStillMs: Long = 1_000L,
    val arriveUnknownMotionMs: Long = 1_500L,
    /**
     * «Llegaste» exige además estar realmente al lado: nivel ≥ ref. a 1 m + esto (≈ 50 cm). En las pruebas reales las
     * llegadas daban +10…+20 dB y el falso destino al pasar sin parar (2 m antes), +5 dB.
     */
    val arriveMinRelDb: Double = 6.0,
    /** "Te alejas": pendiente del objetivo por debajo de esto (dB/s) sostenida, caminando. */
    val colderSlope: Double = -1.5,
    val colderHoldMs: Long = 2_000L,
    val colderRepeatMs: Long = 8_000L,
    val wrongWayRepeatMs: Long = 6_000L,
    /** Sin ninguna baliza de la ruta durante este tiempo: "sin señal". */
    val lostMs: Long = 4_000L,
    /** Desvío hacia una baliza del lugar que NO está en la ruta: tiene que dominar a las de la ruta este tiempo. */
    val offRouteHoldMs: Long = 1_000L,
    val offRouteRepeatMs: Long = 8_000L,
    /**
     * Fuera de ruta «por distancia»: ya dejaste el punto anterior y el siguiente suena más flojo de lo que sonaría a la
     * longitud del tramo (modelo n = 2) menos esto, durante este tiempo. En tramos cortos (2 m) la señal baja demasiado
     * despacio para el aviso por pendiente; esto detecta p. ej. no girar en una L.
     */
    val offRouteFarMarginDb: Double = 3.0,
    val offRouteFarHoldMs: Long = 2_000L,
    /** «Vuelves a la ruta»: el objetivo vuelve a subir (dB/s) durante este tiempo. */
    val backOnRouteSlope: Double = 0.5,
    val backOnRouteHoldMs: Long = 1_500L,
    /** Después de llegar: «te alejas del destino» si su nivel relativo baja de esto (≈ 1,8 m) durante este tiempo. */
    val leaveRelDb: Double = -5.0,
    val leaveHoldMs: Long = 1_500L,
    val leaveRepeatMs: Long = 8_000L,
    // Kalman (nivel + pendiente): ruido de proceso y de medida.
    val qLevel: Double = 2.0,
    val qSlope: Double = 6.0,
    val rMeas: Double = 16.0
)

/** Estado de una baliza para el panel detallado. */
data class BeaconTrack(
    val beacon: String,
    val level: Double,
    /** dB/s: positivo = te acercas. */
    val slope: Double,
    val zone: Zone,
    /** Nivel − referencia a 1 m de esa baliza (0 ≈ a 1 m). */
    val relDb: Double,
    /** 0 = frío (suelo) … 1 = tocando. */
    val temperature: Double,
    val samplesPerS: Double,
    val lastRaw: Int,
    val contactDbm: Double,
    val refAt1m: Double
)

enum class GuideEventKind {
    START, AT_SPOT, PASSED, ARRIVED, WRONG_WAY, COLDER, LOST, FOUND,
    /** Te sales de la ruta (hacia otra baliza del lugar, o alejándote de la anterior y de la siguiente). */
    OFF_ROUTE,
    /** Vuelves a la ruta o, tras llegar, otra vez al destino. */
    RETURNED,
    /** Tras llegar: te alejas del destino. */
    LEAVING
}

data class GuideEvent(val tMs: Long, val kind: GuideEventKind, val text: String, val spotId: String? = null)

data class GuideState(
    val tracks: List<BeaconTrack> = emptyList(),
    /** Índice del último punto alcanzado (−1 = ninguno aún). */
    val progress: Int = -1,
    /** Índice del punto al que te diriges. */
    val target: Int = 0,
    /** Punto junto al que estás ahora (o null). */
    val atSpot: String? = null,
    val targetTemperature: Double = 0.0,
    val targetSlope: Double = 0.0,
    val targetZone: Zone = Zone.LEJOS,
    val arrived: Boolean = false,
    val instruction: String = "",
    val moving: Boolean? = null,
    val lost: Boolean = false,
    /** Fuera de la ruta ahora mismo. */
    val offRoute: Boolean = false,
    /** Ya llegaste, pero ahora estás lejos del destino. */
    val away: Boolean = false
)

class GuideEngine(
    private val route: List<GuideSpot>,
    /** Referencia a 1 m calibrada de cada baliza (o null si no está calibrada). */
    private val refAt1m: (String) -> Double?,
    /** Nivel de contacto del perfil de cada baliza (o null). */
    private val contactOf: (String) -> Double?,
    val params: GuideParams = GuideParams(),
    /** Las demás balizas del lugar (no están en la ruta): sirven para avisar «te desvías hacia…». */
    others: List<GuideSpot> = emptyList(),
    /** Indicación de cada tramo (del punto i al i+1): «Gira a la izquierda y camina 2 metros hasta Baño». Opcional. */
    private val legs: List<String> = emptyList()
) {
    init { require(route.isNotEmpty()) { "La ruta necesita al menos un punto" } }

    private class Kalman(z: Double, t: Long) {
        var level = z; var slope = 0.0; var lastT = t
        var p00 = 25.0; var p01 = 0.0; var p11 = 25.0
        fun update(z: Double, t: Long, qL: Double, qS: Double, r: Double) {
            val dt = ((t - lastT).coerceAtLeast(1L)) / 1000.0; lastT = t
            // predicción
            level += slope * dt
            val n00 = p00 + dt * (2 * p01 + dt * p11) + qL * dt
            val n01 = p01 + dt * p11
            val n11 = p11 + qS * dt
            // corrección
            val s = n00 + r; val k0 = n00 / s; val k1 = n01 / s
            val y = z - level
            level += k0 * y; slope += k1 * y
            p00 = (1 - k0) * n00; p01 = (1 - k0) * n01; p11 = n11 - k1 * n01
        }
    }

    private class Beacon(val kf: Kalman, var lastMs: Long, var lastRaw: Int, val firstMs: Long) {
        val times = ArrayDeque<Long>()
        var count = 0
        var zone = Zone.SALA
    }

    private val beacons = mutableMapOf<String, Beacon>()
    private val spotOfBeacon = route.withIndex().associate { (i, s) -> s.beacon to i }
    private val otherSpots = others.filter { it.beacon !in spotOfBeacon }.associateBy { it.beacon }
    private val events = ArrayList<GuideEvent>()

    private var started = false
    private var progress = -1
    private var target = 0
    private var at = -1
    private var candidate = -1
    private var candidateSince = 0L
    private var arrived = false
    private var atSince = 0L
    private var touchSince = -1L
    private var stillSince = -1L
    private var colderSince = -1L
    private var lastColderMs = -1_000_000L
    private var lastWrongWayMs = -1_000_000L
    private var lastHeardMs = -1L
    private var lost = false
    private var moving: Boolean? = null
    private var instruction = ""
    private var offRoute = false
    private var strayBeacon: String? = null
    private var straySince = 0L
    private var lastOffRouteMs = -1_000_000L
    private var backOnSince = -1L
    private var farSince = -1L
    private var away = false
    private var leaveSince = -1L
    private var returnSince = -1L
    private var lastLeaveMs = -1_000_000L

    var state: GuideState = GuideState(); private set

    fun ref(b: String): Double = refAt1m(b) ?: params.defaultRefAt1m
    fun contact(b: String): Double = contactOf(b) ?: (ref(b) + params.contactAboveRef)

    /** Una muestra nueva de una baliza. Las que no son de la ruta ni del lugar se ignoran. */
    fun onSample(beacon: String, rssi: Int, tMs: Long) {
        if (beacon !in spotOfBeacon && beacon !in otherSpots) return
        val z = rssi.toDouble().coerceAtLeast(-105.0)
        val b = beacons.getOrPut(beacon) { Beacon(Kalman(z, tMs), tMs, rssi, tMs) }
        b.kf.update(z, tMs, params.qLevel, params.qSlope, params.rMeas)
        b.lastMs = tMs; b.lastRaw = rssi; b.count++
        b.times.addLast(tMs)
        while (b.times.isNotEmpty() && tMs - b.times.first() > 2_000L) b.times.removeFirst()
        lastHeardMs = tMs
    }

    /**
     * Avanza el reloj. [isMoving]: true caminando, false quieto, null desconocido (sin acelerómetro).
     * Devuelve los eventos NUEVOS (para hablar / vibrar / registrar).
     */
    fun tick(tMs: Long, isMoving: Boolean? = null): List<GuideEvent> {
        val fresh = ArrayList<GuideEvent>()
        fun emit(kind: GuideEventKind, text: String, spot: String? = null) {
            val e = GuideEvent(tMs, kind, text, spot); fresh += e; events += e
            if (kind != GuideEventKind.COLDER) instruction = text
        }
        moving = isMoving
        if (!started) {
            started = true
            val dest = route.last()
            emit(GuideEventKind.START, when {
                route.size == 1 -> "Guía iniciada. Destino: ${dest.label}."
                legs.isNotEmpty() -> "Guía iniciada hacia ${dest.label}. ${legs[0]}"
                else -> "Guía iniciada. Destino: ${dest.label}. Primero, ${route[0].label}."
            })
        }

        // Caducidad
        beacons.entries.removeAll { tMs - it.value.lastMs > params.expireMs }

        // Zonas con histéresis
        val tracks = beacons.map { (name, b) ->
            val c = contact(name); val r = ref(name); val lvl = b.kf.level
            val h = params.hysteresisDb
            fun above(th: Double, currentlyIn: Boolean) = lvl >= th - (if (currentlyIn) h else 0.0)
            b.zone = when {
                above(c - params.touchBelowContact, b.zone == Zone.TOCANDO) -> Zone.TOCANDO
                above(r + params.nearAboveRef, b.zone >= Zone.CERCA) -> Zone.CERCA
                lvl >= params.floorDbm -> Zone.SALA
                else -> Zone.LEJOS
            }
            val temp = ((lvl - params.floorDbm) / (c - params.floorDbm)).coerceIn(0.0, 1.0)
            BeaconTrack(name, lvl, b.kf.slope, b.zone, lvl - r, temp, b.times.size / 2.0, b.lastRaw, c, r)
        }.sortedBy { spotOfBeacon[it.beacon] ?: 99 }
        val routeTracks = tracks.filter { it.beacon in spotOfBeacon }

        // Sin señal
        if (lastHeardMs >= 0 && tMs - lastHeardMs >= params.lostMs && !lost) {
            lost = true; emit(GuideEventKind.LOST, "Sin señal de las balizas. Quédate quieto un momento.")
        } else if (lost && beacons.isNotEmpty()) {
            lost = false; emit(GuideEventKind.FOUND, "Señal recuperada.")
        }

        // ¿Junto a qué punto estás? El de mayor nivel relativo (cerca, ≈ 1,25 m o menos), dominando a los demás.
        val warm = tracks.filter { tr -> beacons[tr.beacon]?.let { it.count >= params.warmupSamples && tMs - it.firstMs >= params.warmupMs } == true }
        val best = warm.filter { it.beacon in spotOfBeacon && (it.relDb >= params.spotMinRelDb || it.zone >= Zone.CERCA) }.maxByOrNull { it.relDb }
        val second = tracks.filter { it.beacon != best?.beacon }.maxOfOrNull { it.relDb } ?: -99.0
        val c = best?.let { spotOfBeacon[it.beacon] } ?: -1
        val dom = best?.let { it.relDb - second } ?: 0.0
        val isBack = c in 0 until progress
        val isSkip = c > target
        val reqDom = when { isBack -> params.backDominanceDb; isSkip -> params.skipDominanceDb; else -> params.dominanceDb }
        val reqHold = when { isBack -> params.backHoldMs; isSkip -> params.skipHoldMs; else -> params.spotHoldMs }
        // Saltarse puntos solo si el nuevo está de verdad CERCA (evita anunciar el destino 2 m antes por una baliza fuerte).
        val eligible = c >= 0 && dom >= reqDom && (!isSkip || best!!.zone >= Zone.CERCA)
        if (eligible) {
            if (candidate != c) { candidate = c; candidateSince = tMs }
            if (at != c && tMs - candidateSince >= reqHold) {
                at = c; atSince = tMs; touchSince = -1L
                onReachedSpot(c, tMs, ::emit)
            }
        } else candidate = -1
        if (at >= 0) {
            val t = tracks.firstOrNull { spotOfBeacon[it.beacon] == at }
            if (t == null || (t.zone < Zone.CERCA && t.relDb < params.spotMinRelDb - params.hysteresisDb)) at = -1
        }

        // Llegada al destino: junto a él y (tocando, o quieto, o un rato si no se sabe si te mueves).
        if (!arrived && at == route.lastIndex && progress == route.lastIndex) {
            val t = tracks.firstOrNull { spotOfBeacon[it.beacon] == at }
            val beside = t != null && (t.zone == Zone.TOCANDO || t.relDb >= params.arriveMinRelDb)
            if (t?.zone == Zone.TOCANDO) { if (touchSince < 0) touchSince = tMs } else touchSince = -1L
            if (isMoving == false && beside) { if (stillSince < 0) stillSince = tMs } else stillSince = -1L
            if (!beside) atSince = tMs
            val ok = (touchSince >= 0 && tMs - touchSince >= params.arriveTouchMs) ||
                (stillSince >= 0 && tMs - stillSince >= params.arriveStillMs) ||
                (isMoving == null && tMs - atSince >= params.arriveUnknownMotionMs)
            if (ok) { arrived = true; emit(GuideEventKind.ARRIVED, "Llegaste a ${route.last().label}.", route.last().id) }
        }

        val tgt = route.getOrNull(target)
        val tt = tgt?.let { s -> tracks.firstOrNull { it.beacon == s.beacon } }

        // Tras llegar la guía NO termina: vigila si te alejas del destino y si vuelves.
        if (arrived) {
            val dest = route.last()
            val dt = tracks.firstOrNull { it.beacon == dest.beacon }
            val nearDest = dt != null && (dt.zone >= Zone.CERCA || dt.relDb >= params.spotMinRelDb)
            val farDest = dt == null || (dt.zone < Zone.CERCA && dt.relDb < params.leaveRelDb)
            if (!away) {
                if (farDest) {
                    if (leaveSince < 0) leaveSince = tMs
                    if (tMs - leaveSince >= params.leaveHoldMs) {
                        away = true; lastLeaveMs = tMs; returnSince = -1L
                        emit(GuideEventKind.LEAVING, "Te estás alejando de ${dest.label}. Da la vuelta y vuelve; los pitidos te guían.", dest.id)
                    }
                } else leaveSince = -1L
            } else if (nearDest) {
                if (returnSince < 0) returnSince = tMs
                if (tMs - returnSince >= params.spotHoldMs) {
                    away = false; leaveSince = -1L; returnSince = -1L
                    emit(GuideEventKind.RETURNED, "Estás otra vez en ${dest.label}.", dest.id)
                }
            } else {
                returnSince = -1L
                if (tMs - lastLeaveMs >= params.leaveRepeatMs && (dt == null || dt.slope < 0)) {
                    lastLeaveMs = tMs
                    emit(GuideEventKind.LEAVING, "Sigues alejándote de ${dest.label}. Da la vuelta.", dest.id)
                }
            }
        }

        // Desvío hacia una baliza del lugar que no es de la ruta (p. ej. vas al Baño y te metes en el Pasillo A).
        val stray = warm.filter { it.beacon in otherSpots && (it.relDb >= params.spotMinRelDb || it.zone >= Zone.CERCA) }.maxByOrNull { it.relDb }
        val routeBest = routeTracks.maxOfOrNull { it.relDb } ?: -99.0
        if (!arrived && tgt != null && stray != null && stray.relDb - routeBest >= params.dominanceDb) {
            if (strayBeacon != stray.beacon) { strayBeacon = stray.beacon; straySince = tMs }
            if (tMs - straySince >= params.offRouteHoldMs && tMs - lastOffRouteMs >= params.offRouteRepeatMs) {
                lastOffRouteMs = tMs; offRoute = true; backOnSince = -1L
                emit(GuideEventKind.OFF_ROUTE, "Te desvías hacia ${otherSpots[stray.beacon]!!.label}, que no está en tu camino. Vuelve hacia ${tgt.label}.", tgt.id)
            }
        } else strayBeacon = null

        // Frío/caliente y "te alejas" respecto al punto objetivo. Si además te alejas del punto anterior, te sales de la ruta.
        if (!arrived && tt != null && isMoving != false && tt.slope <= params.colderSlope && tt.temperature < 0.8) {
            if (colderSince < 0) colderSince = tMs
            if (tMs - colderSince >= params.colderHoldMs && tMs - lastColderMs >= params.colderRepeatMs) {
                lastColderMs = tMs
                val prev = route.getOrNull(progress)?.takeIf { progress in 0 until target }
                val pt = prev?.let { p -> tracks.firstOrNull { it.beacon == p.beacon } }
                if (prev != null && pt != null && pt.slope <= params.colderSlope / 2 && pt.zone < Zone.CERCA) {
                    offRoute = true; backOnSince = -1L; lastOffRouteMs = tMs
                    emit(GuideEventKind.OFF_ROUTE, "Te estás saliendo de la ruta. Vuelve hacia ${prev.label} y desde ahí sigue hacia ${tgt.label}.", tgt.id)
                } else emit(GuideEventKind.COLDER, "Te alejas de ${tgt.label}. Da la vuelta.", tgt.id)
            }
        } else colderSince = -1L

        // Fuera de ruta por distancia: lejos del punto anterior y más lejos del siguiente que la longitud del tramo.
        val prevSpot = route.getOrNull(progress)?.takeIf { progress in 0 until target }
        val prevTrack = prevSpot?.let { p -> tracks.firstOrNull { it.beacon == p.beacon } }
        if (!arrived && !offRoute && tgt != null && prevSpot != null) {
            val legLen = hypot(tgt.x - prevSpot.x, tgt.y - prevSpot.y).coerceAtLeast(0.5)
            val expected = -20.0 * log10(legLen)
            val leftPrev = prevTrack == null || (prevTrack.zone < Zone.CERCA && prevTrack.relDb < params.spotMinRelDb)
            val tooFar = tt == null || tt.relDb < expected - params.offRouteFarMarginDb
            if (leftPrev && tooFar) {
                if (farSince < 0) farSince = tMs
                if (tMs - farSince >= params.offRouteFarHoldMs && tMs - lastOffRouteMs >= params.offRouteRepeatMs) {
                    lastOffRouteMs = tMs; offRoute = true; backOnSince = -1L; farSince = -1L
                    emit(GuideEventKind.OFF_ROUTE, "Te estás saliendo de la ruta. Vuelve hacia ${prevSpot.label} y desde ahí sigue hacia ${tgt.label}.", tgt.id)
                }
            } else farSince = -1L
        } else farSince = -1L

        // ¿Volviste a la ruta? El objetivo vuelve a subir un rato (o estás junto a un punto de la ruta) y nada te desvía.
        if (offRoute) {
            val recovering = strayBeacon == null && ((tt != null && tt.slope >= params.backOnRouteSlope) || at >= 0)
            if (recovering) {
                if (backOnSince < 0) backOnSince = tMs
                if (tMs - backOnSince >= params.backOnRouteHoldMs || at >= 0) {
                    offRoute = false; backOnSince = -1L
                    if (!arrived) emit(GuideEventKind.RETURNED, "Bien, vuelves a la ruta hacia ${tgt?.label ?: route.last().label}.", tgt?.id)
                }
            } else backOnSince = -1L
        }

        state = GuideState(
            tracks = tracks, progress = progress, target = target, atSpot = route.getOrNull(at)?.id,
            targetTemperature = tt?.temperature ?: 0.0, targetSlope = tt?.slope ?: 0.0, targetZone = tt?.zone ?: Zone.LEJOS,
            arrived = arrived, instruction = instruction, moving = isMoving, lost = lost, offRoute = offRoute, away = away
        )
        return fresh
    }

    private fun onReachedSpot(i: Int, tMs: Long, emit: (GuideEventKind, String, String?) -> Unit) {
        val s = route[i]
        when {
            i == target || i > target -> {
                val skipped = route.subList(target.coerceAtMost(i), i)
                progress = i
                if (i == route.lastIndex) {
                    target = i
                    emit(GuideEventKind.AT_SPOT, (if (skipped.isNotEmpty()) "Pasaste ${skipped.joinToString { it.label }}. " else "") +
                        "Te acercas a ${s.label}, tu destino. Sigue despacio.", s.id)
                } else {
                    val first = progress <= 0 && i == 0
                    target = i + 1
                    val next = route[target]
                    val leg = legs.getOrNull(i)
                    // En el punto de salida con indicaciones se repite tal cual la del arranque (la sesión no la dice dos veces).
                    emit(GuideEventKind.PASSED, (if (skipped.isNotEmpty()) "Pasaste ${skipped.joinToString { it.label }}. " else "") + when {
                        leg != null && first -> leg
                        leg != null -> "${s.label}. $leg"
                        else -> "${s.label}. Sigue: ${next.label} a ${meters(s, next)}."
                    }, s.id)
                }
            }
            i < progress -> {
                if (tMs - lastWrongWayMs >= params.wrongWayRepeatMs) {
                    lastWrongWayMs = tMs
                    val tgt = route[target]
                    emit(GuideEventKind.WRONG_WAY, "Vas hacia atrás: estás junto a ${s.label}. Da la vuelta; ${tgt.label} está a ${meters(s, tgt)}.", s.id)
                }
            }
            // i == progress: vuelves al punto donde ya estabas: nada que decir.
        }
    }

    /** Últimos eventos (más reciente al final). */
    fun history(max: Int = 60): List<GuideEvent> = events.takeLast(max)

    companion object {
        fun meters(a: GuideSpot, b: GuideSpot): String {
            val d = hypot(a.x - b.x, a.y - b.y)
            return if (d < 1.0) "${(d * 100).roundToInt()} centímetros" else "%.1f metros".format(java.util.Locale.forLanguageTag("es"), d)
        }
    }
}
