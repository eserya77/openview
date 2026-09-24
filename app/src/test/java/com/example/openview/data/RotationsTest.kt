package com.example.openview.data

import com.example.openview.logging.BleLogRow
import com.example.openview.logging.CENTER_NODE_ID
import com.example.openview.logging.Detection
import com.example.openview.logging.MarkEvent
import com.example.openview.logging.SessionAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RotationsTest {

    private val beacons = listOf("NAV_BLE_03", "NAV_BLE_04", "NAV_BLE_05", "NAV_BLE_06")

    @Test fun cyclicRotationsFormALatinSquare() {
        val opts = Rotations.options(beacons, Random(1))
        val cyc = listOf("R0", "R1", "R2", "R3").map { id -> opts.first { it.id == id } }
        assertEquals(beacons, cyc[0].beaconAt)
        // Cada baliza pasa por cada posición exactamente una vez entre R0..R3.
        for (spot in 0 until 4) {
            assertEquals(beacons.toSet(), cyc.map { it.beaconAt[spot] }.toSet())
        }
        // R1 desplaza cada placa una posición hacia delante: la última pasa a la primera.
        assertEquals(listOf("NAV_BLE_06", "NAV_BLE_03", "NAV_BLE_04", "NAV_BLE_05"), cyc[1].beaconAt)
    }

    @Test fun everyOptionIsAValidDistinctPermutation() {
        val opts = Rotations.options(beacons, Random(7))
        assertTrue(opts.size >= 6)
        opts.forEach { assertTrue(it.name, Rotations.isValid(it.beaconAt, beacons)) }
        assertEquals(opts.size, opts.map { it.beaconAt }.toSet().size)
        assertNotNull(opts.firstOrNull { it.id == "INV" })
        assertEquals(beacons.reversed(), opts.first { it.id == "INV" }.beaconAt)
        assertEquals(listOf("NAV_BLE_04", "NAV_BLE_03", "NAV_BLE_06", "NAV_BLE_05"), opts.first { it.id == "PAR" }.beaconAt)
    }

    @Test fun customRotationValidation() {
        assertTrue(Rotations.isValid(listOf("NAV_BLE_06", "NAV_BLE_05", "NAV_BLE_04", "NAV_BLE_03"), beacons))
        assertTrue(!Rotations.isValid(listOf("NAV_BLE_06", "NAV_BLE_06", "NAV_BLE_04", "NAV_BLE_03"), beacons))
    }

    @Test fun graphPutsTheRotatedBeaconOnEachSpot() {
        val setup = TestSetup("s", "S", listOf(TestSpot("P1", "P1", 0.8, 0.0), TestSpot("P2", "P2", 2.3, 0.0), TestSpot("P3", "P3", 4.0, 2.7), TestSpot("P4", "P4", 0.0, 1.9)))
        val rot = Rotations.options(beacons, Random(1)).first { it.id == "R1" }
        val g = TestLayout.graph(setup, rot)
        assertEquals("P1", g.nodeForBeacon("NAV_BLE_06")!!.id)
        assertEquals("P4", g.nodeForBeacon("NAV_BLE_05")!!.id)
        assertEquals(listOf("P2", "P4"), g.neighbors("P3").sorted().let { listOf(it[0], it[1]) }.let { it })
        assertEquals("P1=NAV_BLE_06;P2=NAV_BLE_03;P3=NAV_BLE_04;P4=NAV_BLE_05", TestLayout.describe(setup, rot))
    }

    @Test fun centroidIsTheDefaultStart() {
        val setup = TestSetup("s", "S", listOf(TestSpot("P1", "P1", 0.8, 0.0), TestSpot("P2", "P2", 2.3, 0.0), TestSpot("P3", "P3", 4.0, 2.7), TestSpot("P4", "P4", 0.0, 1.9)))
        assertEquals(1.775, setup.center.first, 0.001)
        assertEquals(1.15, setup.center.second, 0.001)
        assertEquals(1.0 to 2.0, setup.copy(startX = 1.0, startY = 2.0).center)
    }

    // ---- El centro espera "ningún nodo" ----
    private fun mark(kind: String, t: Double, id: String, x: Double = 0.0) = BleLogRow(
        timestampS = t, beaconName = "_MARK_", rssiRaw = null, rssiSmoothed = null, estimatedDistanceM = null,
        computedXM = null, computedYM = null, detectedNodeId = null, signalState = "-",
        trueXM = x, trueYM = 0.0, event = kind, trueNode = id
    )

    @Test fun analyzerExpectsNoNodeAtTheCenter() {
        val rows = listOf(
            mark(MarkEvent.ARRIVE, 0.0, CENTER_NODE_ID), mark(MarkEvent.DEPART, 6.0, CENTER_NODE_ID),
            mark(MarkEvent.ARRIVE, 10.0, "P1", 1.0), mark(MarkEvent.DEPART, 16.0, "P1", 1.0)
        )
        // La app dice "P1" también en el centro (como hacía la v2) → el centro debe salir mal.
        val alwaysP1 = (0..170).map { Detection(it / 10.0, "P1") }
        val bad = SessionAnalyzer.analyze(rows, mapOf("P1" to "NAV_BLE_03"), alwaysP1)
        assertEquals(0.0, bad.points.first { it.nodeId == CENTER_NODE_ID }.accuracyPct, 0.1)
        assertEquals(100.0, bad.points.first { it.nodeId == "P1" }.accuracyPct, 5.0)
        // Si dice "ninguno" en el centro y P1 en P1, todo bien.
        val good = (0..170).map { Detection(it / 10.0, if (it < 80) null else "P1") }
        val ok = SessionAnalyzer.analyze(rows, mapOf("P1" to "NAV_BLE_03"), good)
        assertEquals(100.0, ok.points.first { it.nodeId == CENTER_NODE_ID }.accuracyPct, 1.0)
        assertEquals("P1", ok.destinationNode)
    }
}
