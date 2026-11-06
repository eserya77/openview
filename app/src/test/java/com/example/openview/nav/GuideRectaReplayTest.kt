package com.example.openview.nav

import com.example.openview.logging.BleLogFile
import com.example.openview.logging.MarkEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las 3 vueltas REALES de la guía en la sala recta (24-sep-2026, firmware v3: 01 · 2,10 m · 02 · 2,60 m · 03),
 * reproducidas con el motor actual. El movimiento sale de las notas GUIA_ESTADO (acelerómetro real).
 * Referencias: las por defecto (−80 dBm a 1 m), que es lo que usará la app ahora que la calibración de +9 dBm de la 03
 * se reconoce como caducada.
 */
class GuideRectaReplayTest {

    private val p1 = GuideSpot("P1", "Baliza 1", "NAV_BLE_01", 0.0, 0.0)
    private val p2 = GuideSpot("P2", "Baliza 2", "NAV_BLE_02", 2.10, 0.0)
    private val p3 = GuideSpot("P3", "Baliza 3", "NAV_BLE_03", 4.70, 0.0)

    private fun replay(file: String, route: List<GuideSpot>): List<GuideEvent> {
        val rows = BleLogFile.parse(javaClass.getResourceAsStream("/logs/$file")!!.bufferedReader().use { it.readText() })
        // Movimiento registrado: "mov=true|false" en cada GUIA_ESTADO (1 por segundo).
        val motion = rows.filter { it.event == MarkEvent.NOTE && it.note.startsWith("GUIA_ESTADO") }
            .map { r -> (r.timestampS * 1000).toLong() to Regex("mov=(true|false)").find(r.note)?.groupValues?.get(1)?.toBoolean() }
        fun movingAt(t: Long): Boolean? = motion.lastOrNull { it.first <= t }?.second
        val e = GuideEngine(route, { null }, { null })
        val out = mutableListOf<GuideEvent>()
        var tick = 0L
        rows.filter { it.event == MarkEvent.SAMPLE && it.rssiRaw != null }.forEach { r ->
            val t = (r.timestampS * 1000).toLong()
            while (tick <= t) { out += e.tick(tick, movingAt(tick)); tick += 200L }
            e.onSample(r.beaconName, r.rssiRaw!!, t)
        }
        repeat(10) { out += e.tick(tick, movingAt(tick)); tick += 200L }
        return out
    }

    private fun check(file: String, route: List<GuideSpot>) {
        val ev = replay(file, route)
        val dump = ev.joinToString(" | ") { "${it.tMs} ${it.kind} ${it.spotId}" }
        val reached = ev.filter { it.kind == GuideEventKind.PASSED || it.kind == GuideEventKind.AT_SPOT }.map { it.spotId }
        assertEquals(dump, route.map { it.id }, reached)
        assertEquals(dump, 1, ev.count { it.kind == GuideEventKind.ARRIVED })
        assertTrue(dump, ev.none { it.kind == GuideEventKind.WRONG_WAY })
        // Vueltas correctas: el aviso nuevo de «fuera de ruta» no debe saltar.
        assertTrue(dump, ev.none { it.kind == GuideEventKind.OFF_ROUTE })
    }

    @Test fun ida() = check("guia_recta_ida_124628.csv", listOf(p1, p2, p3))
    @Test fun vuelta1() = check("guia_recta_vuelta_124800.csv", listOf(p3, p2, p1))
    @Test fun vuelta2() = check("guia_recta_vuelta_125925.csv", listOf(p3, p2, p1))
}
