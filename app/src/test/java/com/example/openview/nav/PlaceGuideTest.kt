package com.example.openview.nav

import com.example.openview.data.DemoPlaces
import com.example.openview.data.PlacePlanner
import com.example.openview.data.SharedPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.random.Random

/**
 * Guía con los lugares de ejemplo (3 balizas a 2 m: recto, en L, triángulo) simulada en 2D con el modelo log-distancia
 * de la calibración real (A₁ₘ −80, n = 2, contacto −55, ruido σ 4 dB): indicaciones de giro, avisos de desvío y
 * vigilancia después de llegar.
 */
class PlaceGuideTest {

    private class Sim(val place: SharedPlace, val e: GuideEngine, seed: Int) {
        val rnd = Random(seed)
        var t = 0L
        var x = 0.0; var y = 0.0
        /** Distancia vertical/lateral mínima a la baliza (la franja está en la pared, el teléfono en la mano). */
        var gap = 0.45
        val events = mutableListOf<GuideEvent>()

        private fun gauss(): Double {
            val u = rnd.nextDouble().coerceAtLeast(1e-9); val v = rnd.nextDouble()
            return 4.0 * kotlin.math.sqrt(-2 * kotlin.math.ln(u)) * kotlin.math.cos(2 * Math.PI * v)
        }

        fun step(moving: Boolean) {
            place.beacons.forEachIndexed { i, b ->
                if (rnd.nextDouble() < 0.5) {
                    val d = hypot(hypot(x - b.x, y - b.y), gap).coerceAtLeast(0.05)
                    val rssi = (-80.0 - 20 * log10(d) + gauss()).toInt()
                    if (rssi > -100) e.onSample(b.beacon, rssi, t + i * 7L)
                }
            }
            t += 50L
            events += e.tick(t, moving)
        }

        fun walkTo(tx: Double, ty: Double, speed: Double = 0.7) {
            val ax = x; val ay = y
            val n = (hypot(tx - ax, ty - ay) / speed / 0.05).toInt().coerceAtLeast(1)
            for (i in 1..n) { x = ax + (tx - ax) * i / n; y = ay + (ty - ay) * i / n; step(true) }
        }

        fun stay(s: Double, closeGap: Double = 0.25) { val g = gap; gap = closeGap; repeat((s / 0.05).toInt()) { step(false) }; gap = g }

        fun kinds() = events.map { it.kind }
        fun dump() = events.joinToString(" | ") { "${it.tMs / 1000.0}s ${it.kind} ${it.text}" }
    }

    private fun sim(place: SharedPlace, from: String, to: String, seed: Int = 3): Sim {
        val path = PlacePlanner.path(place, from, to)!!
        val route = PlacePlanner.spots(place, path)
        val others = place.beacons.filter { b -> path.none { it == b.beacon } }.map { GuideSpot(it.beacon, it.name, it.beacon, it.x, it.y) }
        val legs = PlacePlanner.legs(place, path).map { it.text }
        val e = GuideEngine(route, { -80.0 }, { -55.0 }, others = others, legs = legs)
        return Sim(place, e, seed).also { val b = place.beacon(from)!!; it.x = b.x; it.y = b.y }
    }

    @Test fun `en L dice girar a la izquierda en Pasillo A y llega al Baño`() {
        for (seed in 1..5) {
            val s = sim(DemoPlaces.EN_L, "NAV_BLE_01", "NAV_BLE_03", seed)
            s.stay(1.5, closeGap = 0.3)
            s.walkTo(2.0, 0.0); s.walkTo(2.0, 2.0); s.stay(3.0)
            val passed = s.events.filter { it.kind == GuideEventKind.PASSED }
            assertTrue(s.dump(), passed.any { it.spotId == "NAV_BLE_02" && it.text.contains("Gira a la izquierda") && it.text.contains("Baño") })
            assertEquals(s.dump(), 1, s.events.count { it.kind == GuideEventKind.ARRIVED })
            assertTrue(s.dump(), s.events.none { it.kind == GuideEventKind.OFF_ROUTE || it.kind == GuideEventKind.WRONG_WAY })
            assertTrue(s.dump(), s.events.first().text.contains("Camina recto 2 metros hasta Pasillo A"))
        }
    }

    @Test fun `tras llegar sigue vigilando - avisa al alejarse y al volver`() {
        for (seed in 1..5) {
            val s = sim(DemoPlaces.RECTO, "NAV_BLE_02", "NAV_BLE_03", seed)
            s.stay(1.5, closeGap = 0.3)
            s.walkTo(4.0, 0.0); s.stay(3.0)
            assertTrue(s.dump(), GuideEventKind.ARRIVED in s.kinds())
            s.walkTo(4.0, -3.5); s.stay(3.0, closeGap = 0.45)
            val leave = s.events.indexOfFirst { it.kind == GuideEventKind.LEAVING }
            assertTrue(s.dump(), leave >= 0 && s.events[leave].text.contains("Te estás alejando de Baño"))
            s.walkTo(4.0, 0.0); s.stay(2.5)
            assertTrue(s.dump(), s.events.drop(leave).any { it.kind == GuideEventKind.RETURNED && it.text.contains("otra vez en Baño") })
            assertEquals(s.dump(), 1, s.events.count { it.kind == GuideEventKind.ARRIVED })
        }
    }

    @Test fun `quieto en el destino no da falsos avisos de alejarse`() {
        for (seed in 1..5) {
            val s = sim(DemoPlaces.RECTO, "NAV_BLE_02", "NAV_BLE_03", seed)
            s.stay(1.5, closeGap = 0.3)
            s.walkTo(4.0, 0.0); s.stay(20.0, closeGap = 0.5)
            assertTrue(s.dump(), GuideEventKind.ARRIVED in s.kinds())
            assertTrue(s.dump(), GuideEventKind.LEAVING !in s.kinds())
        }
    }

    @Test fun `triangulo - desviarse hacia otra baliza avisa fuera de ruta`() {
        for (seed in 1..5) {
            // Ruta directa Entrada → Baño; la persona se va hacia Pasillo A.
            val s = sim(DemoPlaces.TRIANGULO, "NAV_BLE_01", "NAV_BLE_03", seed)
            s.stay(1.5, closeGap = 0.3)
            s.walkTo(2.0, 0.0); s.stay(2.0, closeGap = 0.3)
            val off = s.events.firstOrNull { it.kind == GuideEventKind.OFF_ROUTE }
            assertTrue(s.dump(), off != null && off.text.contains("Pasillo A") && off.text.contains("Baño"))
            assertTrue(s.dump(), GuideEventKind.ARRIVED !in s.kinds())
            // Vuelve hacia el Baño: «vuelves a la ruta» y llega.
            s.walkTo(1.0, 1.732); s.stay(3.0)
            assertTrue(s.dump(), s.events.any { it.kind == GuideEventKind.RETURNED })
            assertTrue(s.dump(), GuideEventKind.ARRIVED in s.kinds())
        }
    }

    @Test fun `en L - pasar Pasillo A y seguir recto en vez de girar avisa`() {
        for (seed in 1..5) {
            val s = sim(DemoPlaces.EN_L, "NAV_BLE_01", "NAV_BLE_03", seed)
            s.stay(1.5, closeGap = 0.3)
            s.walkTo(2.0, 0.0)
            // No gira: sigue recto (y se aleja de Pasillo A y del Baño).
            s.walkTo(5.5, -2.0); s.stay(2.0, closeGap = 0.45)
            assertTrue(s.dump(), s.events.any { (it.kind == GuideEventKind.OFF_ROUTE || it.kind == GuideEventKind.COLDER) && it.text.contains("Baño") })
            assertTrue(s.dump(), GuideEventKind.ARRIVED !in s.kinds())
        }
    }
}
