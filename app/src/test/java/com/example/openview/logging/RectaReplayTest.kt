package com.example.openview.logging

import com.example.openview.data.NavEdge
import com.example.openview.data.NavGraph
import com.example.openview.data.NavNode
import com.example.openview.nav.Algorithm
import com.example.openview.nav.GuideEngine
import com.example.openview.nav.GuideEventKind
import com.example.openview.nav.GuideSpot
import com.example.openview.nav.TrackerParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas REALES de la sala recta del 24-sep-2026 (13:45–13:49, firmware v3) hechas con un APK que aún usaba la
 * calibración de +9 dBm de la 03 (−67 dBm a 1 m) frente a la global (−59) de 01/02: eso favorecía a la 03 en 8 dB y
 * produjo los fallos (se saltó la 02 al pasar, volvió a decir la 03, confundió la 02 con la 03 a 1 m).
 * Aquí se reproducen con la corrección: calibración caducada ⇒ la misma referencia para las tres.
 */
class RectaReplayTest {

    private val n1 = NavNode("P1", "Baliza 1", 0.0, 0.0, "NAV_BLE_01")
    private val n2 = NavNode("P2", "Baliza 2", 2.10, 0.0, "NAV_BLE_02")
    private val n3 = NavNode("P3", "Baliza 3", 4.70, 0.0, "NAV_BLE_03", isDestination = true)
    private val graph = NavGraph(listOf(n1, n2, n3), listOf(NavEdge("P1", "P2"), NavEdge("P2", "P3")))

    private fun load(name: String) = BleLogFile.parse(javaClass.getResourceAsStream("/logs/$name")!!.bufferedReader().use { it.readText() })

    private fun seqOf(frames: List<ReplayFrame>): List<String> =
        frames.mapNotNull { it.nodeId }.fold(mutableListOf()) { acc, n -> if (acc.lastOrNull() != n) acc += n; acc }

    private fun positioner(rows: List<BleLogRow>, path: List<String>, ref: (String) -> Int) =
        ReplayEngine.run(rows, TrackerParams(), graph, ref, Algorithm.V3, path, null)

    private val stale: (String) -> Int = { if (it == "NAV_BLE_03") -67 else -59 }   // lo que usó el APK de la prueba
    private val fixed: (String) -> Int = { -59 }                                     // con la corrección

    @Test fun pasarSinPararWithThePositionerV3() {
        // Lo que ocurrió con el APK de la prueba (posicionador v3 + calibración caducada de la 03):
        assertEquals(listOf("P1", "P3"), seqOf(positioner(load("recta_pasar_ida_v6.csv"), listOf("P1", "P2", "P3"), stale)))
        assertEquals(listOf("P3", "P2", "P3"), seqOf(positioner(load("recta_pasar_vuelta_v7.csv"), listOf("P3", "P2", "P1"), stale)))
        // Con la corrección tampoco basta: al pasar la 02 en la ida, la 03 (2,6 m más allá) se oye MÁS fuerte (−75 frente a
        // −80…−85 dBm). Es física (orientación / distancia al pasar la 02), no el algoritmo: ver GuideEngine abajo.
    }

    @Test fun parada1mNoLongerConfusesP2WithP3() {
        val rows = load("recta_1m_v8.csv")
        val path = listOf("P1", "P2", "P3")
        val summary = { ref: (String) -> Int ->
            SessionAnalyzer.analyze(rows, mapOf("P1" to "NAV_BLE_01", "P2" to "NAV_BLE_02"), positioner(rows, path, ref).map { Detection(it.timestampS, it.nodeId) })
        }
        val before = summary(stale).points.first { it.nodeId == "P2" }.accuracyPct
        val after = summary(fixed).points.first { it.nodeId == "P2" }.accuracyPct
        assertTrue("P2 antes $before %", before < 10.0)
        assertTrue("P2 con la corrección $after %", after >= 90.0)
    }

    private fun guideSeq(file: String, route: List<GuideSpot>): Pair<List<String?>, String> {
        val rows = load(file)
        val e = GuideEngine(route, { null }, { null })
        val ev = mutableListOf<com.example.openview.nav.GuideEvent>()
        var tick = 0L
        rows.filter { it.event == MarkEvent.SAMPLE && it.rssiRaw != null }.forEach { r ->
            val t = (r.timestampS * 1000).toLong()
            while (tick <= t) { ev += e.tick(tick, true); tick += 200L }
            e.onSample(r.beaconName, r.rssiRaw!!, t)
        }
        repeat(10) { ev += e.tick(tick, true); tick += 200L }
        return ev.filter { it.kind == GuideEventKind.PASSED || it.kind == GuideEventKind.AT_SPOT }.map { it.spotId } to
            ev.joinToString(" | ") { "${it.tMs} ${it.kind} ${it.spotId}" }
    }

    private val g1 = GuideSpot("P1", "Baliza 1", "NAV_BLE_01", 0.0, 0.0)
    private val g2 = GuideSpot("P2", "Baliza 2", "NAV_BLE_02", 2.1, 0.0)
    private val g3 = GuideSpot("P3", "Baliza 3", "NAV_BLE_03", 4.7, 0.0)

    @Test fun guideEngineWhenPassingWithoutStopping() {
        // Vuelta: los 3 puntos en orden.
        val (vuelta, d2) = guideSeq("recta_pasar_vuelta_v7.csv", listOf(g3, g2, g1))
        assertEquals(d2, listOf("P3", "P2", "P1"), vuelta)
        // Ida: la 02 no se distingue (ver arriba). Lo importante: la 03 se anuncia como «te acercas», y NUNCA hay un
        // «Llegaste» falso mientras se camina.
        val rows = load("recta_pasar_ida_v6.csv")
        val ev = com.example.openview.nav.GuideReplay.run(rows, listOf(g1, g2, g3), { null }, { null }, motion = { true })
        assertEquals(listOf("P1", "P3"), ev.filter { it.kind == GuideEventKind.PASSED || it.kind == GuideEventKind.AT_SPOT }.map { it.spotId })
        assertTrue(ev.none { it.kind == GuideEventKind.ARRIVED })
        assertTrue(ev.first { it.kind == GuideEventKind.AT_SPOT }.text.contains("Te acercas"))
    }

    @Test fun guideDetectsGoingBackInTheRealLap() {
        // Guía ida 13:47: pasa 01 y 02 y vuelve a la 01 a propósito. Debe avisar «vas hacia atrás» junto a la 01 y no dar llegada.
        val rows = load("guia_recta_ida_atras_134706.csv")
        val e = GuideEngine(listOf(GuideSpot("P1", "Baliza 1", "NAV_BLE_01", 0.0, 0.0), GuideSpot("P2", "Baliza 2", "NAV_BLE_02", 2.1, 0.0), GuideSpot("P3", "Baliza 3", "NAV_BLE_03", 4.7, 0.0)), { null }, { null })
        val ev = mutableListOf<com.example.openview.nav.GuideEvent>()
        var tick = 0L
        rows.filter { it.event == MarkEvent.SAMPLE && it.rssiRaw != null }.forEach { r ->
            val t = (r.timestampS * 1000).toLong()
            while (tick <= t) { ev += e.tick(tick, null); tick += 200L }
            e.onSample(r.beaconName, r.rssiRaw!!, t)
        }
        val dump = ev.joinToString(" | ") { "${it.tMs} ${it.kind} ${it.spotId}" }
        assertTrue(dump, ev.any { it.kind == GuideEventKind.WRONG_WAY && it.spotId == "P1" })
        assertTrue(dump, ev.none { it.kind == GuideEventKind.ARRIVED })
    }
}
