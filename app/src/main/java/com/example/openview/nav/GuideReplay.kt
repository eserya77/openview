package com.example.openview.nav

import com.example.openview.logging.BleLogRow
import com.example.openview.logging.MarkEvent

/**
 * Reproduce un log grabado con el motor de la guía ([GuideEngine]), con un tic cada [tickMs]. Lo usan los tests y la
 * prueba guiada (modo «pasar sin parar»), para evaluar lo que diría la guía del producto y no solo el posicionador.
 */
object GuideReplay {

    /** Movimiento registrado por la guía en las notas GUIA_ESTADO ("mov=true|false"), si el log las trae. */
    fun motionFromNotes(rows: List<BleLogRow>): (Long) -> Boolean? {
        val m = rows.filter { it.event == MarkEvent.NOTE && it.note.startsWith("GUIA_ESTADO") }
            .map { r -> (r.timestampS * 1000).toLong() to Regex("mov=(true|false)").find(r.note)?.groupValues?.get(1)?.toBoolean() }
        return { t -> m.lastOrNull { it.first <= t }?.second }
    }

    fun run(
        rows: List<BleLogRow>,
        route: List<GuideSpot>,
        refAt1m: (String) -> Double?,
        contactOf: (String) -> Double?,
        motion: (Long) -> Boolean? = motionFromNotes(rows),
        tickMs: Long = 200L,
        params: GuideParams = GuideParams()
    ): List<GuideEvent> {
        if (route.isEmpty()) return emptyList()
        val e = GuideEngine(route, refAt1m, contactOf, params)
        val out = mutableListOf<GuideEvent>()
        var tick = 0L
        rows.filter { it.event == MarkEvent.SAMPLE && it.rssiRaw != null }.sortedBy { it.timestampS }.forEach { r ->
            val t = (r.timestampS * 1000).toLong()
            while (tick <= t) { out += e.tick(tick, motion(tick)); tick += tickMs }
            e.onSample(r.beaconName, r.rssiRaw!!, t)
        }
        repeat(10) { out += e.tick(tick, motion(tick)); tick += tickMs }
        return out
    }
}
