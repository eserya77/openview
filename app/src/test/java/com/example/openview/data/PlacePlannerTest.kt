package com.example.openview.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rutas e indicaciones «ve recto / gira a la derecha» de los lugares de ejemplo (3 balizas a 2 m). */
class PlacePlannerTest {

    private fun legs(p: SharedPlace, from: String, to: String, heading: Double? = null) =
        PlacePlanner.legs(p, PlacePlanner.path(p, from, to)!!, heading)

    @Test fun `recto - caminos por el medio y siempre recto`() {
        val p = DemoPlaces.RECTO
        assertEquals(listOf("NAV_BLE_01", "NAV_BLE_02", "NAV_BLE_03"), PlacePlanner.path(p, "NAV_BLE_01", "NAV_BLE_03"))
        val l = legs(p, "NAV_BLE_01", "NAV_BLE_03")
        assertEquals("Camina recto 2 metros hasta Pasillo A.", l[0].text)
        assertEquals("Sigue recto 2 metros hasta Baño, tu destino.", l[1].text)
    }

    @Test fun `en L - a la ida gira a la izquierda y a la vuelta a la derecha`() {
        val p = DemoPlaces.EN_L
        val ida = legs(p, "NAV_BLE_01", "NAV_BLE_03")
        assertEquals(Turn.LEFT, ida[1].turn)
        assertEquals("Gira a la izquierda y camina 2 metros hasta Baño, tu destino.", ida[1].text)
        // Vuelta: sale del Baño sin saber hacia dónde mira; en Pasillo A gira a la derecha.
        val vuelta = legs(p, "NAV_BLE_03", "NAV_BLE_01")
        assertEquals(null, vuelta[0].turn)
        assertTrue(vuelta[0].text.startsWith("Camina 2 metros hasta Pasillo A"))
        assertEquals(Turn.RIGHT, vuelta[1].turn)
        assertEquals("Gira a la derecha y camina 2 metros hasta Entrada, tu destino.", vuelta[1].text)
    }

    @Test fun `en L - si acaba de llegar al Baño, la vuelta empieza con media vuelta`() {
        val p = DemoPlaces.EN_L
        // Llegó al Baño caminando hacia +y (90°); para volver a Pasillo A hay que ir hacia −y.
        val l = legs(p, "NAV_BLE_03", "NAV_BLE_02", heading = 90.0)
        assertEquals(Turn.BACK, l[0].turn)
        assertEquals("Da media vuelta y camina 2 metros hasta Pasillo A, tu destino.", l[0].text)
    }

    @Test fun `triangulo - caminos directos y giros desde la entrada`() {
        val p = DemoPlaces.TRIANGULO
        assertEquals(listOf("NAV_BLE_01", "NAV_BLE_03"), PlacePlanner.path(p, "NAV_BLE_01", "NAV_BLE_03"))
        // En la entrada se mira hacia +x (0°): Baño queda a 60° a la izquierda.
        val l = legs(p, "NAV_BLE_01", "NAV_BLE_03")
        assertEquals(Turn.LEFT, l[0].turn)
        assertEquals("Gira a la izquierda y camina 2 metros hasta Baño, tu destino.", l[0].text)
        assertEquals("Camina recto 2 metros hasta Pasillo A, tu destino.", legs(p, "NAV_BLE_01", "NAV_BLE_02")[0].text)
    }

    @Test fun `destinos desde cada punto - la entrada al final`() {
        val p = DemoPlaces.EN_L
        assertEquals(listOf("Pasillo A", "Baño"), PlacePlanner.destinationsFrom(p, "NAV_BLE_01").map { it.name })
        assertEquals(listOf("Baño", "Entrada"), PlacePlanner.destinationsFrom(p, "NAV_BLE_02").map { it.name })
    }

    @Test fun `indicaciones escritas por la empresa mandan sobre las generadas`() {
        val base = DemoPlaces.EN_L
        val own = base.copy(routes = listOf(PlaceRoute("NAV_BLE_01", "NAV_BLE_03", listOf("NAV_BLE_01", "NAV_BLE_02", "NAV_BLE_03"),
            listOf("Sigue la pared derecha hasta el pasillo.", "Gira a la izquierda; el baño es la segunda puerta."))))
        val l = legs(own, "NAV_BLE_01", "NAV_BLE_03")
        assertEquals("Gira a la izquierda; el baño es la segunda puerta.", l[1].text)
        // Al revés no hay texto propio: se genera.
        assertTrue(legs(own, "NAV_BLE_03", "NAV_BLE_01")[1].text.startsWith("Gira a la derecha"))
    }

    @Test fun `sin conexion no hay camino`() {
        val p = SharedPlace("x", "X", DemoPlaces.RECTO.beacons, links = listOf("NAV_BLE_01" to "NAV_BLE_02"))
        assertNull(PlacePlanner.path(p, "NAV_BLE_01", "NAV_BLE_03"))
    }

    @Test fun `giros por angulo`() {
        assertEquals(Turn.STRAIGHT, PlacePlanner.turnOf(10.0))
        assertEquals(Turn.SLIGHT_LEFT, PlacePlanner.turnOf(40.0))
        assertEquals(Turn.RIGHT, PlacePlanner.turnOf(-90.0))
        assertEquals(Turn.BACK, PlacePlanner.turnOf(175.0))
        assertEquals(-90.0, PlacePlanner.turnAngle(180.0, 90.0), 1e-9)
        assertEquals(90.0, PlacePlanner.turnAngle(-90.0, 0.0), 1e-9)
    }
}
