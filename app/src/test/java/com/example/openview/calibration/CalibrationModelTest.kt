package com.example.openview.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10

class CalibrationModelTest {

    private fun result(kind: ScenarioKind, d: Double, angle: Int, median: Double, sd: Double = 1.5, rate: Double = 8.0) =
        ScenarioResult("${kind.name}_${d}_$angle", kind, d, angle, 80, median, sd, rate)

    /** Curva ideal RSSI = A − 10·n·log10(d) para las distancias dadas, más contacto y ángulos coherentes. */
    private fun ideal(a: Double, n: Double, contact: Double = -40.0, ring: Double = 0.0): List<ScenarioResult> {
        val out = mutableListOf(result(ScenarioKind.CONTACT, 0.0, 0, contact))
        listOf(0.3, 1.0, 2.0, 3.0, 4.0, 6.0).forEach { d -> out += result(ScenarioKind.FRONT, d, 0, a - 10 * n * log10(d)) }
        listOf(90, 180, 270).forEach { ang -> out += result(ScenarioKind.ANGLE, 1.0, ang, a - ring) }
        return out
    }

    @Test fun scenariosRespectMaxDistance() {
        val s6 = CalibrationScenarios.forMaxDistance(6.0)
        assertEquals("contact", s6.first().id)
        assertEquals(listOf(0.3, 1.0, 2.0, 3.0, 4.0, 6.0), s6.filter { it.kind == ScenarioKind.FRONT }.map { it.distanceM })
        assertEquals(3, s6.count { it.kind == ScenarioKind.ANGLE })
        val s2 = CalibrationScenarios.forMaxDistance(2.0, includeAngles = false)
        assertEquals(listOf(0.3, 1.0, 2.0), s2.filter { it.kind == ScenarioKind.FRONT }.map { it.distanceM })
        assertTrue(s2.none { it.kind == ScenarioKind.ANGLE })
        assertEquals(1, CalibrationScenarios.forMaxDistance(3.0, includeBody = true).count { it.kind == ScenarioKind.BODY })
    }

    @Test fun fitRecoversAKnownCurve() {
        val p = ProfileFitter.build("NAV_BLE_04", "test", 0L, ideal(a = -62.0, n = 2.2))
        assertEquals(-62.0, p.a1m!!, 0.05)
        assertEquals(2.2, p.pathLossN!!, 0.02)
        assertTrue(p.fitResidualSd!! < 0.1)
        assertEquals(Quality.A, p.quality)
        assertEquals(-40.0, p.contactRssi!!, 0.001)
    }

    @Test fun flatCurveIsNotReliable() {
        // n ≈ 0,3: la señal casi no baja con la distancia (como la baliza 03 oída desde lejos).
        val p = ProfileFitter.build("NAV_BLE_03", "test", 0L, ideal(a = -62.0, n = 0.3))
        assertEquals(Quality.C, p.quality)
        assertTrue(p.reasons.any { it.contains("plana") })
    }

    @Test fun asymmetricPatternIsFlagged() {
        val res = ideal(a = -62.0, n = 2.0).toMutableList()
        res.removeAll { it.kind == ScenarioKind.ANGLE }
        listOf(90 to -70.0, 180 to -80.0, 270 to -64.0).forEach { (ang, v) -> res += result(ScenarioKind.ANGLE, 1.0, ang, v) }
        val p = ProfileFitter.build("NAV_BLE_05", "test", 0L, res)
        assertTrue("asimetría ${p.asymmetryDb}", p.asymmetryDb!! > 15)
        assertEquals(Quality.C, p.quality)
    }

    @Test fun missingOneMeterIsNotReliableAndFewDistancesDowngrade() {
        val res = listOf(
            result(ScenarioKind.CONTACT, 0.0, 0, -40.0),
            result(ScenarioKind.FRONT, 0.3, 0, -50.0),
            result(ScenarioKind.FRONT, 2.0, 0, -70.0)
        )
        val p = ProfileFitter.build("NAV_BLE_06", "test", 0L, res)
        assertNull(p.pathLossN)
        assertEquals(Quality.C, p.quality)
    }

    @Test fun lowSampleRateDowngrades() {
        val slow = ideal(a = -62.0, n = 2.0).map { it.copy(ratePerS = 2.0) }
        assertEquals(Quality.C, ProfileFitter.build("NAV_BLE_06", "t", 0L, slow).quality)
        val mid = ideal(a = -62.0, n = 2.0).map { it.copy(ratePerS = 4.0) }
        assertEquals(Quality.B, ProfileFitter.build("NAV_BLE_06", "t", 0L, mid).quality)
    }

    @Test fun summarizeNeedsEnoughSamplesAndUsesMedian() {
        val sc = CalibrationScenarios.forMaxDistance(6.0)[2]
        assertNull(ProfileFitter.summarize(sc, listOf(-60, -61), 10.0))
        val r = ProfileFitter.summarize(sc, listOf(-60, -62, -61, -90, -61, -60, -62, -61), 10.0)
        assertNotNull(r)
        assertEquals(-61.0, r!!.median, 0.001)      // el pico de -90 no arrastra la mediana
        assertEquals(0.8, r.ratePerS, 0.001)
    }

    @Test fun stabilityDetector() {
        assertTrue(StabilityDetector.isStable(List(12) { -60 + (it % 2) }))
        assertTrue(!StabilityDetector.isStable(List(12) { if (it % 2 == 0) -50 else -70 }))
        assertTrue(!StabilityDetector.isStable(listOf(-60, -60, -60)))   // pocas muestras
    }

    @Test fun distanceUsesTheProfileFit() {
        val p = ProfileFitter.build("NAV_BLE_04", "test", 0L, ideal(a = -62.0, n = 2.0))
        assertEquals(2.0, ProfileFitter.distanceFor(p, -62.0 - 20 * log10(2.0))!!, 0.05)
        val flat = ProfileFitter.build("NAV_BLE_03", "test", 0L, ideal(a = -62.0, n = 0.3))
        assertNull(ProfileFitter.distanceFor(flat, -70.0))
    }

    @Test fun exportLinesHaveOneRowPerScenario() {
        val p = ProfileFitter.build("NAV_BLE_04", "Xiaomi, test", 0L, ideal(a = -62.0, n = 2.0))
        val lines = ProfileExport.lines(listOf(p))
        assertEquals(1 + p.results.size, lines.size)
        assertTrue(lines[1].startsWith("NAV_BLE_04,Xiaomi  test,A,"))
    }
}
