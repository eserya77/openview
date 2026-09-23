package com.example.openview.logging

import com.example.openview.data.NavEdge
import com.example.openview.data.NavGraph
import com.example.openview.data.NavNode
import com.example.openview.nav.TrackerParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regresión con la prueba real del 23-sep-2026 (cuarto de ~1,9 x 2,3 m, balizas 03-06,
 * recorrido 06 -> 05 -> 04 -> 03). Con el algoritmo v1 esa prueba daba: nodo 03 el 66 % del
 * tiempo, 85 cambios de nodo en 130 s y 88 % de los primeros 15 s en el nodo destino.
 *
 * Las ventanas "pegado a la baliza" se definieron a mano mirando el RSSI (las notas se
 * escribieron con retraso). La referencia a 1 m por baliza sale de un ajuste sobre ESTA
 * misma prueba, así que el resultado es optimista: sirve como red de seguridad contra
 * regresiones, no como validación. La validación real es repetir la caminata en modo guiado.
 */
class LogRegressionTest {

    private val n06 = NavNode("node_1790200467446", "Punto 1", 0.0, 0.0, "NAV_BLE_06")
    private val n05 = NavNode("node_1790200806256", "Punto 2", 0.0, 2.25, "NAV_BLE_05")
    private val n04 = NavNode("node_1790200867951", "Punto 3", 1.85, 2.2, "NAV_BLE_04")
    private val n03 = NavNode("node_1790200921966", "Punto 4", 1.65, 0.0, "NAV_BLE_03", isDestination = true)
    private val graph = NavGraph(
        listOf(n06, n05, n04, n03),
        listOf(NavEdge(n06.id, n05.id), NavEdge(n05.id, n04.id), NavEdge(n04.id, n03.id))
    )

    // RSSI a 1 m ajustado con la propia prueba: la 03 es ~18 dB más "ruidosa" que las demás.
    private val calibration = mapOf(
        "NAV_BLE_03" to -62, "NAV_BLE_04" to -79, "NAV_BLE_05" to -82, "NAV_BLE_06" to -82
    )
    private val reference = { name: String -> calibration[name] ?: -59 }

    private fun loadRows(): List<BleLogRow> {
        val text = javaClass.getResourceAsStream("/logs/prueba_cuarto_20260923.csv")!!
            .bufferedReader().use { it.readText() }
        return BleLogFile.parse(text)
    }

    private fun mark(kind: String, t: Double, n: NavNode) = BleLogRow(
        timestampS = t, beaconName = "_MARK_", rssiRaw = null, rssiSmoothed = null,
        estimatedDistanceM = null, computedXM = null, computedYM = null, detectedNodeId = null,
        signalState = "-", trueXM = n.x, trueYM = n.y, event = kind, trueNode = n.id
    )

    private fun withMarks(rows: List<BleLogRow>) = rows + listOf(
        mark(MarkEvent.ARRIVE, 7.0, n06), mark(MarkEvent.DEPART, 15.0, n06),
        mark(MarkEvent.ARRIVE, 54.0, n05), mark(MarkEvent.DEPART, 60.0, n05),
        mark(MarkEvent.ARRIVE, 88.0, n04), mark(MarkEvent.DEPART, 96.0, n04),
        mark(MarkEvent.ARRIVE, 118.0, n03), mark(MarkEvent.DEPART, 129.0, n03)
    )

    private fun summarize(params: TrackerParams, ref: (String) -> Int): SessionSummary {
        val rows = withMarks(loadRows())
        val frames = ReplayEngine.run(rows, params, graph, ref)
        val detections = frames.map { Detection(it.timestampS, it.nodeId) }
        val beaconOfNode = graph.nodes.associate { it.id to it.beaconName!! }
        return SessionAnalyzer.analyze(rows, beaconOfNode, detections)
    }

    @Test fun logLoads() {
        val rows = loadRows()
        assertTrue(rows.size > 11000)
        assertEquals(setOf("NAV_BLE_03", "NAV_BLE_04", "NAV_BLE_05", "NAV_BLE_06"),
            rows.map { it.beaconName }.filter { it.startsWith("NAV_") }.toSet())
    }

    @Test fun calibratedPipelineFindsEveryPointWithoutFalseArrival() {
        val s = summarize(TrackerParams(), reference)
        println("== Calibrado (default): acierto medio %.1f %% | cambios %d | segundos en destino equivocado %.1f".format(s.meanAccuracyPct, s.totalFlips, s.wrongDestinationS))
        s.points.forEach { println("   ${it.beaconName}: acierto %.0f %%, latencia %s, cambios %d, RSSI mediano %s, confunde con %s".format(it.accuracyPct, it.latencyS, it.flips, it.medianRssi, it.wrongNode)) }
        assertTrue("acierto medio ${s.meanAccuracyPct}", s.meanAccuracyPct >= 90.0)
        s.points.forEach { assertTrue("${it.beaconName} acierto ${it.accuracyPct}", it.accuracyPct >= 80.0) }
        assertFalse("falsa llegada: ${s.wrongDestinationS} s", s.falseArrival)
        assertTrue("cambios de nodo ${s.totalFlips}", s.totalFlips <= 6)
    }

    @Test fun noFalseDestinationAtTheStartAndReadableSequence() {
        val frames = ReplayEngine.run(withMarks(loadRows()), TrackerParams(), graph, reference)
        val first15 = frames.filter { it.timestampS < 15.0 }
        val shareDest = first15.count { it.nodeId == n03.id }.toDouble() / first15.size
        // v1: 88 % de los primeros 15 s decía "destino". Ahora debe ser ~0.
        assertTrue("destino en los primeros 15 s: $shareDest", shareDest < 0.05)

        val seq = mutableListOf<String>()
        frames.forEach { f ->
            val name = graph.nodes.firstOrNull { it.id == f.nodeId }?.beaconName?.takeLast(2) ?: "--"
            if (seq.lastOrNull() != name) seq.add(name)
        }
        println("== Secuencia de nodos detectados (esperada ~ 06 05 04 03): ${seq.joinToString(" ")}")
        assertTrue("demasiados cambios: $seq", seq.size <= 14)
    }

    @Test fun withoutCalibrationTheLoudBeaconStillMisleads() {
        // Documenta POR QUÉ hace falta calibrar: sin referencias por baliza la 03 gana en el punto 06.
        val s = summarize(TrackerParams(), { -59 })
        println("== Sin calibrar: acierto medio %.1f %% | segundos en destino equivocado %.1f".format(s.meanAccuracyPct, s.wrongDestinationS))
        val p06 = s.points.first { it.beaconName == "NAV_BLE_06" }
        assertTrue("sin calibrar debería fallar en 06, dio ${p06.accuracyPct}", p06.accuracyPct < 50.0)
    }

    @Test fun groundTruthInterpolatesInTransit() {
        val marks = GroundTruth.marksFrom(withMarks(emptyList()))
        val dwell = GroundTruth.truthAt(56.0, marks)!!
        assertEquals(n05.id, dwell.nodeId)
        val transit = GroundTruth.truthAt(74.0, marks)!!          // mitad del tramo 05 -> 04 (60..88 s => 74 s)
        assertEquals(null, transit.nodeId)
        assertEquals((n05.x + n04.x) / 2, transit.x, 0.01)
        assertEquals((n05.y + n04.y) / 2, transit.y, 0.01)
    }

    @Test fun csvRoundTripKeepsMarksAndQuotedNotes() {
        val row = BleLogRow(
            timestampS = 12.5, beaconName = "_MARK_", rssiRaw = null, rssiSmoothed = null,
            estimatedDistanceM = null, computedXM = null, computedYM = null, detectedNodeId = null,
            signalState = "-", trueXM = 1.0, trueYM = 2.0, note = "LLEGUÉ Punto 2, hace 5 s",
            event = MarkEvent.ARRIVE, trueNode = "node_a"
        )
        val text = "sep=,\n${BleSessionLoggerHeader.HEADER}\n${BleSessionLoggerHeader.line(row)}\n"
        val parsed = BleLogFile.parse(text).single()
        assertEquals(MarkEvent.ARRIVE, parsed.event)
        assertEquals("node_a", parsed.trueNode)
        assertEquals("LLEGUÉ Punto 2, hace 5 s", parsed.note)
    }
}

/** BleSessionLogger es un object que usa Android; para el test reutilizamos solo su formato de línea. */
private object BleSessionLoggerHeader {
    const val HEADER = BleSessionLogger.HEADER
    fun line(r: BleLogRow): String = BleSessionLogger.toCsvLine(r)
}
