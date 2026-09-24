package com.example.openview.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PassAnalyzerTest {

    private fun sample(t: Double, beacon: String, rssi: Int, node: String?) =
        BleLogRow(t, beacon, rssi, rssi.toDouble(), null, null, null, node, "stable")

    private fun mark(t: Double, node: String, kind: String) =
        BleLogRow(t, "_MARK_", null, null, null, null, null, null, "-", 0.0, 0.0, "", kind, node)

    @Test fun findsPeakAndDetectionAroundEachPass() {
        val rows = mutableListOf<BleLogRow>()
        // 01 hace pico a los 5 s, 02 a los 10 s; la app nombra P1 a los 5,5 s y P2 a los 10,8 s. Nunca nombra P3.
        var t = 0.0
        while (t < 15.0) {
            val node = when { t >= 10.8 -> "P2"; t >= 5.5 -> "P1"; else -> null }
            rows += sample(t, "NAV_BLE_01", (-60 - 6 * abs(t - 5.0)).toInt(), node)
            rows += sample(t + 0.05, "NAV_BLE_02", (-60 - 6 * abs(t - 10.0)).toInt(), node)
            t += 0.1
        }
        rows += mark(5.2, "P1", MarkEvent.ARRIVE); rows += mark(5.2, "P1", MarkEvent.DEPART)
        rows += mark(10.1, "P2", MarkEvent.ARRIVE); rows += mark(10.1, "P2", MarkEvent.DEPART)
        rows += mark(14.0, "P3", MarkEvent.ARRIVE); rows += mark(14.0, "P3", MarkEvent.DEPART)
        val s = PassAnalyzer.analyze(rows.sortedBy { it.timestampS }, mapOf("P1" to "NAV_BLE_01", "P2" to "NAV_BLE_02", "P3" to "NAV_BLE_03"))

        assertEquals(3, s.points.size)
        assertEquals(2, s.detected)
        assertEquals(-0.2, s.points[0].peakOffsetS!!, 0.15)
        assertEquals(0.3, s.points[0].detectLatencyS!!, 0.15)
        assertEquals(0.7, s.points[1].detectLatencyS!!, 0.15)
        assertNull(s.points[2].detectLatencyS)     // la app nunca dijo P3
        assertNull(s.points[2].peakOffsetS)        // y la 03 no se oyó
        assertTrue(!s.orderOk)                     // faltó P3 en la secuencia de la app
        assertEquals(listOf("P1", "P2"), s.appSequence)
    }
}
