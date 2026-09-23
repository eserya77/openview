package com.example.openview.nav

import com.example.openview.data.NavEdge
import com.example.openview.data.NavGraph
import com.example.openview.data.NavNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeTrackerTest {

    // Cadena 06 - 05 - 04 - 03 (cada uno vecino solo del siguiente).
    private val graph = NavGraph(
        nodes = listOf(
            NavNode("n06", "P1", 0.0, 0.0, "NAV_BLE_06"),
            NavNode("n05", "P2", 0.0, 2.0, "NAV_BLE_05"),
            NavNode("n04", "P3", 2.0, 2.0, "NAV_BLE_04"),
            NavNode("n03", "P4", 2.0, 0.0, "NAV_BLE_03", isDestination = true)
        ),
        edges = listOf(NavEdge("n06", "n05"), NavEdge("n05", "n04"), NavEdge("n04", "n03"))
    )

    private fun tracker() = NodeTracker(TrackerParams())

    private fun feed(t: NodeTracker, rel: Map<String, Double>, fromMs: Long, toMs: Long, stepMs: Long = 100): NodeTracker.Decision {
        var last = t.update(rel, graph, fromMs)
        var now = fromMs + stepMs
        while (now <= toMs) { last = t.update(rel, graph, now); now += stepMs }
        return last
    }

    @Test fun firstNodeNeedsToHold() {
        val t = tracker()
        val rel = mapOf("NAV_BLE_06" to 10.0, "NAV_BLE_03" to -5.0)
        assertNull(t.update(rel, graph, 0).nodeId)              // aún no
        assertEquals("n06", feed(t, rel, 100, 1000).nodeId)     // tras holdMs ya sí
    }

    @Test fun calibratedRelativeDbLetsWeakBeaconWin() {
        // 06 crudo -72 con referencia -82 (rel +10); 03 crudo -61 con referencia -62 (rel +1):
        // con RSSI crudo ganaría 03; con relativo gana 06.
        val t = tracker()
        val rel = mapOf("NAV_BLE_06" to -72.0 - -82.0, "NAV_BLE_03" to -61.0 - -62.0)
        assertEquals("n06", feed(t, rel, 0, 1500).nodeId)
    }

    @Test fun flickerWithinMarginDoesNotSwitch() {
        val t = tracker()
        feed(t, mapOf("NAV_BLE_06" to 8.0, "NAV_BLE_05" to 0.0), 0, 1000)
        // 05 se acerca a 2 dB de 06 (menos que el margen de 4 dB): no cambia nunca.
        val d = feed(t, mapOf("NAV_BLE_06" to 4.0, "NAV_BLE_05" to 6.0), 1100, 5000)
        assertEquals("n06", d.nodeId)
    }

    @Test fun neighborSwitchesAfterMarginAndHold() {
        val t = tracker()
        feed(t, mapOf("NAV_BLE_06" to 8.0, "NAV_BLE_05" to 0.0), 0, 1000)
        val rel = mapOf("NAV_BLE_06" to 0.0, "NAV_BLE_05" to 8.0)
        assertEquals("n06", t.update(rel, graph, 1100).nodeId)   // el retador aún no aguanta holdMs
        assertEquals("n05", feed(t, rel, 1200, 2200).nodeId)
    }

    @Test fun nonNeighborNeedsBiggerJump() {
        val t = tracker()
        feed(t, mapOf("NAV_BLE_06" to 8.0, "NAV_BLE_03" to 0.0), 0, 1000)
        // 03 no es vecino de 06: 6 dB de ventaja no bastan...
        assertEquals("n06", feed(t, mapOf("NAV_BLE_06" to 0.0, "NAV_BLE_03" to 6.0), 1100, 4000).nodeId)
        // ...pero 12 dB sostenidos sí.
        assertEquals("n03", feed(t, mapOf("NAV_BLE_06" to -6.0, "NAV_BLE_03" to 6.0), 4100, 5500).nodeId)
    }

    @Test fun noBeaconsClearsNode() {
        val t = tracker()
        feed(t, mapOf("NAV_BLE_06" to 8.0), 0, 1000)
        assertNull(t.update(emptyMap(), graph, 1100).nodeId)
    }

    @Test fun positionerExpiresSilentBeacon() {
        val ref = { _: String -> -60 }
        val p = BlePositioner(TrackerParams(expiryMs = 3000), { graph }, ref)
        var now = 0L
        repeat(20) { p.onSample("NAV_BLE_06", -55, now); now += 100 }
        assertEquals("n06", p.tick(now).nodeId)
        val later = p.tick(now + 3500)    // 3,5 s sin muestras: la baliza caduca
        assertNull(later.nodeId)
        assertTrue(later.readings.isEmpty())
    }
}
