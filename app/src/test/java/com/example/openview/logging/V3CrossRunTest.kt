package com.example.openview.logging

import com.example.openview.data.NavEdge
import com.example.openview.data.NavGraph
import com.example.openview.data.NavNode
import com.example.openview.nav.Algorithm
import com.example.openview.nav.FingerprintSet
import com.example.openview.nav.Fingerprints
import com.example.openview.nav.NodeFingerprint
import com.example.openview.nav.TrackerParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validación cruzada del algoritmo v3 con las DOS vueltas guiadas reales del 23-sep-2026 (cuarto de 2 m,
 * baliza a baliza 06 → 05 → 04 → 03): se entrenan las huellas con una vuelta y se prueba con la otra.
 *
 * Referencia (app v2 tal como corrió): 75 % de acierto en ambas vueltas y 24,5 s / 10,1 s de "falso destino".
 */
class V3CrossRunTest {

    private val n06 = NavNode("node_1790200467446", "Punto 1", 0.0, 0.0, "NAV_BLE_06")
    private val n05 = NavNode("node_1790200806256", "Punto 2", 0.0, 2.25, "NAV_BLE_05")
    private val n04 = NavNode("node_1790200867951", "Punto 3", 1.85, 2.2, "NAV_BLE_04")
    private val n03 = NavNode("node_1790200921966", "Punto 4", 1.65, 0.0, "NAV_BLE_03", isDestination = true)
    private val graph = NavGraph(listOf(n06, n05, n04, n03), listOf(NavEdge(n06.id, n05.id), NavEdge(n05.id, n04.id), NavEdge(n04.id, n03.id)))
    private val path = graph.nodes.map { it.id }

    private fun load(name: String): List<BleLogRow> =
        BleLogFile.parse(javaClass.getResourceAsStream("/logs/$name")!!.bufferedReader().use { it.readText() })

    private fun fingerprintsFrom(rows: List<BleLogRow>): FingerprintSet {
        val vectors = Fingerprints.trainFromRows(rows)
        return FingerprintSet("test", vectors.mapValues { (node, vec) -> Fingerprints.merge(node, listOf(vec)) })
    }

    private fun evaluate(test: List<BleLogRow>, train: List<BleLogRow>, algorithm: Algorithm = Algorithm.V3): Triple<SessionSummary, List<ReplayFrame>, Double> {
        val frames = ReplayEngine.run(test, TrackerParams(), graph, { -59 }, algorithm, path, fingerprintsFrom(train))
        val detections = frames.map { Detection(it.timestampS, it.nodeId) }
        val summary = SessionAnalyzer.analyze(test, graph.nodes.associate { it.id to it.beaconName!! }, detections)
        // Falso destino tras llegar a 06 (el inicio en el centro no cuenta), igual que en el informe v2.
        val marks = GroundTruth.marksFrom(test)
        val start = marks.first { it.kind == MarkEvent.ARRIVE }.tS
        val dep04 = marks.first { it.kind == MarkEvent.DEPART && it.nodeId == n04.id }.tS
        val falseDest = frames.zipWithNext().filter { (a, _) -> a.timestampS in start..dep04 && a.nodeId == n03.id }.sumOf { (a, b) -> b.timestampS - a.timestampS }
        return Triple(summary, frames, falseDest)
    }

    @Test fun trainOnRun1TestOnRun2() {
        val (s, _, fd) = evaluate(load("guiada_20260923_210336.csv"), load("guiada_20260923_210143.csv"))
        println("== v3 entrena V1 → prueba V2: acierto %.0f %% | cambios %d | falso destino %.1f s".format(s.meanAccuracyPct, s.totalFlips, fd))
        s.points.forEach { println("   ${it.beaconName}: %.0f %% (confunde con ${it.wrongNode})".format(it.accuracyPct)) }
        assertTrue("acierto ${s.meanAccuracyPct}", s.meanAccuracyPct >= 95.0)
        s.points.forEach { assertTrue("${it.beaconName} ${it.accuracyPct}", it.accuracyPct >= 90.0) }
        assertTrue("falso destino $fd s", fd < 0.5)
    }

    @Test fun trainOnRun2TestOnRun1() {
        val (s, _, fd) = evaluate(load("guiada_20260923_210143.csv"), load("guiada_20260923_210336.csv"))
        println("== v3 entrena V2 → prueba V1: acierto %.0f %% | cambios %d | falso destino %.1f s".format(s.meanAccuracyPct, s.totalFlips, fd))
        assertTrue("acierto ${s.meanAccuracyPct}", s.meanAccuracyPct >= 95.0)
        assertTrue("falso destino $fd s", fd < 0.5)
    }

    @Test fun sequenceIsTheRouteOrderWithoutPhantomDestination() {
        val (_, frames, _) = evaluate(load("guiada_20260923_210336.csv"), load("guiada_20260923_210143.csv"))
        val seq = mutableListOf<String>()
        frames.forEach { f ->
            val name = graph.nodes.firstOrNull { it.id == f.nodeId }?.beaconName?.takeLast(2) ?: return@forEach
            if (seq.lastOrNull() != name) seq.add(name)
        }
        println("== secuencia v3 (vuelta 2): ${seq.joinToString(" ")}")
        // El orden de la ruta se respeta: sin saltos 05 → 03 ni 06 → 03.
        val idx = seq.map { listOf("06", "05", "04", "03").indexOf(it) }
        assertTrue("secuencia $seq", idx.zipWithNext().all { (a, b) -> b - a <= 1 })
        assertEquals("03", seq.last())
    }

    @Test fun v2StillFailsOnTheSameData() {
        // Documenta la mejora: el algoritmo v2 con las mismas huellas/calibración NO acierta todo.
        val (s, _, fd) = evaluate(load("guiada_20260923_210336.csv"), load("guiada_20260923_210143.csv"), Algorithm.V2)
        println("== v2 (misma prueba): acierto %.0f %% | falso destino %.1f s".format(s.meanAccuracyPct, fd))
        assertTrue(s.meanAccuracyPct < 95.0 || fd > 1.0)
    }

    @Test fun withoutTrainingNothingCrashesAndFallsBackToRelative() {
        val test = load("guiada_20260923_210143.csv")
        val frames = ReplayEngine.run(test, TrackerParams(), graph, { -59 }, Algorithm.V3, path, null)
        assertTrue(frames.isNotEmpty())
        assertFalse(frames.all { it.nodeId == null })
    }

    @Test fun fingerprintMathBasics() {
        val fp = Fingerprints.merge("P1", listOf(mapOf("a" to -50.0, "b" to -80.0), mapOf("a" to -54.0, "b" to -84.0), mapOf("a" to -52.0, "b" to -82.0)))
        assertEquals(-52.0, fp.mean.getValue("a"), 0.001)
        assertTrue(fp.sd.getValue("a") >= 3.0)                               // piso de 3 dB
        assertEquals(0.0, Fingerprints.distance(fp, mapOf("a" to -52.0, "b" to -82.0)), 0.001)
        // Una baliza que no se oye cuenta como -100 dBm.
        assertTrue(Fingerprints.distance(fp, mapOf("a" to -52.0)) > 8.0)
        val empty = NodeFingerprint("x", emptyMap(), emptyMap(), 0)
        assertEquals(Double.MAX_VALUE, Fingerprints.distance(empty, mapOf("a" to -50.0)), 0.0)
    }
}
