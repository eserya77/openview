package com.example.openview.nav

import com.example.openview.logging.CENTER_NODE_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTrackerV3Test {

    private val path = listOf("P1", "P2", "P3", "P4")

    private fun step(t: RouteTracker, scores: Map<String, Double>, fromMs: Long, toMs: Long, stepMs: Long = 100): RouteTracker.Decision {
        var last = t.update(scores, path, fromMs)
        var now = fromMs + stepMs
        while (now <= toMs) { last = t.update(scores, path, now); now += stepMs }
        return last
    }

    private fun s(p1: Double = -20.0, p2: Double = -20.0, p3: Double = -20.0, p4: Double = -20.0, center: Double? = null) =
        buildMap { put("P1", p1); put("P2", p2); put("P3", p3); put("P4", p4); center?.let { put(CENTER_NODE_ID, it) } }

    @Test fun firstNodeMustBeTheStartOfTheRoute() {
        val t = RouteTracker()
        // Desde el centro, P4 (la baliza "ruidosa") se ve mejor que las demás por 6 dB, pero la ruta empieza en P1.
        val d = step(t, s(p4 = -10.0), 0, 2500)
        assertNull("no debe aceptar P4 tan pronto", d.nodeId)
        // Si P1 pasa a ser la mejor, se acepta tras 0,5 s.
        assertEquals("P1", step(t, s(p1 = -8.0), 2600, 3400).nodeId)
    }

    @Test fun leakOfALaterNodeInTransitIsRejected() {
        val t = RouteTracker()
        step(t, s(p1 = -8.0), 0, 1000)                                   // en P1
        assertEquals("P1", step(t, s(p1 = -8.0), 1100, 1500).nodeId)
        // De camino a P2, P4 suena fuerte 2,5 s: es un salto de 2+ nodos (exige 3 s sostenidos) → no se acepta y, tras salir de P1, no hay nodo.
        val d = step(t, s(p1 = -30.0, p4 = -10.0), 1600, 4100)
        assertNull("en tránsito no hay nodo", d.nodeId)
        assertEquals(0, d.progressIdx)
        // Llega P2: se acepta el siguiente.
        assertEquals("P2", step(t, s(p2 = -9.0, p4 = -25.0), 4200, 5100).nodeId)
    }

    @Test fun aClearSustainedSkipIsAcceptedEventually() {
        val t = RouteTracker()
        step(t, s(p1 = -8.0), 0, 1000)
        // P2 no se oye (baliza apagada) y P3 domina claramente 4 s: se acepta el salto.
        val d = step(t, s(p1 = -40.0, p2 = -40.0, p3 = -9.0, p4 = -40.0), 1100, 5200)
        assertEquals("P3", d.nodeId)
    }

    @Test fun skipMustBeatTheIntermediateNodesToo() {
        val t = RouteTracker()
        step(t, s(p1 = -8.0), 0, 1000)
        // P4 solo 2 dB mejor que P2 (intermedio): no alcanza el margen de salto aunque dure mucho.
        val d = step(t, s(p1 = -40.0, p2 = -12.0, p4 = -10.0), 1100, 9000)
        assertNotEquals("P4", d.nodeId)
    }

    @Test fun nextNodeNeedsOnlyASmallMargin() {
        val t = RouteTracker()
        step(t, s(p1 = -8.0), 0, 1000)
        // P2 apenas 1 dB mejor que P1 (el nodo de progreso): basta para el siguiente esperado tras 0,5 s.
        val d = step(t, s(p1 = -9.0, p2 = -8.0), 1100, 2000)
        assertEquals("P2", d.nodeId)
    }

    @Test fun centerScoreMeansNoNode() {
        val t = RouteTracker()
        step(t, s(p1 = -8.0), 0, 1000)
        // "ningún nodo cerca" gana sostenidamente → se sale del nodo tras leaveHold (1 s).
        val d = step(t, s(p1 = -15.0, p2 = -20.0, p3 = -20.0, p4 = -20.0, center = -6.0), 1100, 3000)
        assertNull(d.nodeId)
        assertEquals(0, d.progressIdx)
    }

    @Test fun returningToTheSameNodeIsConfirmedQuickly() {
        val t = RouteTracker()
        step(t, s(p1 = -8.0), 0, 1000)
        step(t, s(p1 = -30.0, center = -6.0), 1100, 2500)                 // sale
        assertNull(t.update(s(p1 = -30.0, center = -6.0), path, 2600).nodeId)
        assertEquals("P1", step(t, s(p1 = -7.0), 2700, 3200).nodeId)      // vuelve (300 ms)
    }

    @Test fun stableTimeCountsFromConfirmation() {
        val t = RouteTracker()
        val d = step(t, s(p1 = -8.0), 0, 3000)
        assertEquals("P1", d.nodeId)
        assertTrue("stable ${d.stableMs}", d.stableMs in 2000..2600)
    }
}
