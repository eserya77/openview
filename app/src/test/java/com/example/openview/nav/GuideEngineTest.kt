package com.example.openview.nav

import com.example.openview.logging.BleLogFile
import com.example.openview.logging.MarkEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.random.Random

/**
 * Motor de guía: pasillo recto 01 · 2,10 m · 02 · 2,60 m · 03 (la prueba de la sala del 24-sep-2026) simulado con el
 * modelo log-distancia de la calibración real (A₁ₘ −80, n = 2, contacto −55, ruido ±4 dB), y la vuelta REAL de la sala.
 */
class GuideEngineTest {

    private val p1 = GuideSpot("P1", "Baliza 1", "NAV_BLE_01", 0.0, 0.0)
    private val p2 = GuideSpot("P2", "Baliza 2", "NAV_BLE_02", 2.10, 0.0)
    private val p3 = GuideSpot("P3", "Baliza 3", "NAV_BLE_03", 4.70, 0.0)
    private val spots = listOf(p1, p2, p3)

    private fun engine(route: List<GuideSpot>) = GuideEngine(route, { -80.0 }, { -55.0 })

    /** Camina por los puntos [waypoints] (x en metros, a [lateral] m de la pared) y se queda quieto al final [stillS] s. */
    private fun walk(e: GuideEngine, waypoints: List<Double>, lateral: Double = 0.5, speed: Double = 0.7, stillS: Double = 3.0, seed: Int = 1, endLateral: Double = 0.3): List<Pair<Double, GuideEvent>> {
        val rnd = Random(seed)
        val out = mutableListOf<Pair<Double, GuideEvent>>()
        var t = 0L
        var x = waypoints.first()
        var lat = lateral
        fun step(moving: Boolean) {
            // ~10 muestras/s por baliza, en instantes desfasados
            spots.forEachIndexed { i, s ->
                if (rnd.nextDouble() < 0.5) {
                    val d = hypot(x - s.x, lat).coerceAtLeast(0.05)
                    val rssi = (-80.0 - 20 * log10(d) + rnd.nextGaussian4()).toInt()
                    if (rssi > -100) e.onSample(s.beacon, rssi, t + i * 7L)
                }
            }
            t += 50L
            e.tick(t, moving).forEach { out += x to it }
        }
        for (k in 0 until waypoints.size - 1) {
            val a = waypoints[k]; val b = waypoints[k + 1]
            val n = (kotlin.math.abs(b - a) / speed / 0.05).toInt().coerceAtLeast(1)
            for (i in 0..n) { x = a + (b - a) * i / n; step(true) }
        }
        lat = endLateral   // al detenerse se acerca a la baliza (toca la franja)
        repeat((stillS / 0.05).toInt()) { step(false) }
        return out
    }

    private fun Random.nextGaussian4(): Double {
        // Box–Muller, σ = 4 dB
        val u = nextDouble().coerceAtLeast(1e-9); val v = nextDouble()
        return 4.0 * kotlin.math.sqrt(-2 * kotlin.math.ln(u)) * kotlin.math.cos(2 * Math.PI * v)
    }

    @Test fun idaPassesMilestonesInOrderAndArrivesOnce() {
        repeat(5) { seed ->
            val ev = walk(engine(spots), listOf(-1.0, 4.7), seed = seed)
            val reached = ev.filter { it.second.kind == GuideEventKind.PASSED || it.second.kind == GuideEventKind.AT_SPOT }.map { it.second.spotId }
            assertEquals("seed $seed: $reached", listOf("P1", "P2", "P3"), reached)
            val arrivals = ev.filter { it.second.kind == GuideEventKind.ARRIVED }
            assertEquals(1, arrivals.size)
            assertTrue("llegada antes de estar junto a P3 (x=${arrivals[0].first})", arrivals[0].first > 4.0)
            assertTrue(ev.none { it.second.kind == GuideEventKind.WRONG_WAY })
        }
    }

    @Test fun vueltaWorksWithTheReversedRoute() {
        val ev = walk(engine(spots.reversed()), listOf(5.7, 0.0))
        val reached = ev.filter { it.second.kind == GuideEventKind.PASSED || it.second.kind == GuideEventKind.AT_SPOT }.map { it.second.spotId }
        assertEquals(listOf("P3", "P2", "P1"), reached)
        assertEquals(1, ev.count { it.second.kind == GuideEventKind.ARRIVED })
    }

    @Test fun goingBackIsDetected() {
        // Pasa P1 y P2 y vuelve hacia P1: debe decir "vas hacia atrás" y no dar llegada.
        val ev = walk(engine(spots), listOf(-1.0, 2.3, -0.3), stillS = 1.0)
        assertTrue(ev.joinToString(" | ") { "x=%.2f ${it.second.kind} ${it.second.spotId}".format(it.first) }, ev.any { it.second.kind == GuideEventKind.WRONG_WAY && it.second.spotId == "P1" })
        assertFalse(ev.any { it.second.kind == GuideEventKind.ARRIVED })
    }

    @Test fun passingFarFromTheWallStillCountsMilestones() {
        // A 0,8 m de la pared (peor caso de la franja guía).
        val ev = walk(engine(spots), listOf(-1.0, 4.7), lateral = 0.8, seed = 3)
        val reached = ev.filter { it.second.kind == GuideEventKind.PASSED || it.second.kind == GuideEventKind.AT_SPOT }.map { it.second.spotId }
        assertEquals(listOf("P1", "P2", "P3"), reached)
    }

    @Test fun realSalaLapFollowsTheRouteAndArrivesAtTheEnd() {
        // Vuelta real de la sala (R0: P1=03, P2=04, P3=05, P4=06), paradas en contacto. Sin acelerómetro (null).
        val rows = BleLogFile.parse(javaClass.getResourceAsStream("/logs/sala_20260924_023630.csv")!!.bufferedReader().use { it.readText() })
        val route = listOf(
            GuideSpot("P1", "Posición 1", "NAV_BLE_03", 0.8, 0.0), GuideSpot("P2", "Posición 2", "NAV_BLE_04", 2.3, 0.0),
            GuideSpot("P3", "Posición 3", "NAV_BLE_05", 4.0, 2.7), GuideSpot("P4", "Posición 4", "NAV_BLE_06", 0.0, 1.9)
        )
        // Referencias a 1 m y contactos con que se hizo esa vuelta.
        val ref = mapOf("NAV_BLE_03" to -67.0, "NAV_BLE_04" to -84.0, "NAV_BLE_05" to -84.0, "NAV_BLE_06" to -79.0)
        val contact = mapOf("NAV_BLE_03" to -36.0, "NAV_BLE_04" to -54.0, "NAV_BLE_05" to -54.0, "NAV_BLE_06" to -55.0)
        val e = GuideEngine(route, { ref[it] }, { contact[it] })
        val events = mutableListOf<GuideEvent>()
        var nextTick = 0L
        rows.filter { it.event == MarkEvent.SAMPLE && it.rssiRaw != null }.forEach { r ->
            val t = (r.timestampS * 1000).toLong()
            while (nextTick <= t) { events += e.tick(nextTick, null); nextTick += 200L }
            e.onSample(r.beaconName, r.rssiRaw!!, t)
        }
        events += e.tick(nextTick, null)
        val reached = events.filter { it.kind == GuideEventKind.PASSED || it.kind == GuideEventKind.AT_SPOT }.map { it.spotId }
        assertEquals(events.joinToString(" | ") { "${it.tMs} ${it.kind} ${it.spotId} ${it.text}" }, listOf("P1", "P2", "P3", "P4"), reached)
        val arrived = events.single { it.kind == GuideEventKind.ARRIVED }
        assertTrue("llegó en t=${arrived.tMs} ms; la marca de P4 es a los 66,8 s", arrived.tMs >= 64_000L)
        assertTrue(events.none { it.kind == GuideEventKind.WRONG_WAY })
    }
}
