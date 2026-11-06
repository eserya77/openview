package com.example.openview.nav

import com.example.openview.data.DemoPlaces
import com.example.openview.data.PlacePlanner
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.random.Random

/**
 * Simulación de CAPACIDAD y ROBUSTEZ (plan de validación, OE4): ¿qué pasa si llegan menos anuncios (colisiones por
 * muchos dispositivos BLE cerca) o si la señal es más ruidosa (gente que se cruza, reflexiones)?
 *
 * Recorrido en L de los ejemplos (Entrada → Pasillo A → Baño, 2 m por tramo) con el motor de guía REAL, modelo
 * log-distancia de la calibración (A₁ₘ −80 dBm, n = 2) y una tasa base de 3,5 muestras/s por baliza (la medida con el
 * Redmi en las pruebas de campo). Para cada combinación de pérdida y ruido se repite con 40 semillas y se mide:
 *  - éxito: punto intermedio anunciado con su giro, una sola llegada y sin avisos falsos de desvío o de «vas hacia atrás»;
 *  - latencia de llegada: desde que la persona se detiene en el destino hasta «Llegaste».
 * Los resultados se escriben en build/capacidad_simulacion.csv.
 */
class CapacitySimulationTest {

    private val place = DemoPlaces.EN_L
    private val path = PlacePlanner.path(place, "NAV_BLE_01", "NAV_BLE_03")!!

    data class Run(val ok: Boolean, val falseAlarm: Boolean, val arrivalS: Double?, val missedTurn: Boolean, val noArrival: Boolean)

    private fun run(loss: Double, sigma: Double, seed: Int, pauseAtTurnS: Double = 0.0): Run {
        val rnd = Random(seed)
        val route = PlacePlanner.spots(place, path)
        val legs = PlacePlanner.legs(place, path).map { it.text }
        val e = GuideEngine(route, { -80.0 }, { -55.0 }, legs = legs)
        val events = mutableListOf<GuideEvent>()
        var t = 0L
        var x = 0.0; var y = 0.0; var gap = 0.3
        val pSample = 3.5 * 0.05 * (1 - loss)   // muestras por paso de 50 ms y baliza
        fun gauss(): Double {
            val u = rnd.nextDouble().coerceAtLeast(1e-9); val v = rnd.nextDouble()
            return sigma * kotlin.math.sqrt(-2 * kotlin.math.ln(u)) * kotlin.math.cos(2 * Math.PI * v)
        }
        fun step(moving: Boolean) {
            place.beacons.forEachIndexed { i, b ->
                if (rnd.nextDouble() < pSample) {
                    val d = hypot(hypot(x - b.x, y - b.y), gap).coerceAtLeast(0.05)
                    val rssi = (-80.0 - 20 * log10(d) + gauss()).toInt()
                    if (rssi > -100) e.onSample(b.beacon, rssi, t + i * 7L)
                }
            }
            t += 50L
            events += e.tick(t, moving)
        }
        fun walk(tx: Double, ty: Double) {
            val ax = x; val ay = y
            val n = (hypot(tx - ax, ty - ay) / 0.7 / 0.05).toInt()
            for (k in 1..n) { x = ax + (tx - ax) * k / n; y = ay + (ty - ay) * k / n; step(true) }
        }
        repeat(30) { step(false) }                    // 1,5 s quieto en la entrada
        gap = 0.45
        walk(2.0, 0.0)
        repeat((pauseAtTurnS / 0.05).toInt()) { step(false) }   // se detiene un momento donde hay que girar
        walk(2.0, 2.0)
        gap = 0.25
        val stopAt = t
        repeat(160) { step(false) }                   // 8 s quieto en el destino
        val passedTurn = events.any { it.kind == GuideEventKind.PASSED && it.spotId == "NAV_BLE_02" && it.text.contains("izquierda") }
        val arrivals = events.filter { it.kind == GuideEventKind.ARRIVED }
        val falseAlarm = events.any { it.kind == GuideEventKind.OFF_ROUTE || it.kind == GuideEventKind.WRONG_WAY }
        val arrivalS = arrivals.firstOrNull()?.let { (it.tMs - stopAt) / 1000.0 }
        return Run(passedTurn && arrivals.size == 1 && !falseAlarm, falseAlarm, arrivalS, !passedTurn, arrivals.size != 1)
    }

    @Test fun `capacidad y robustez del motor de guia`() {
        val losses = listOf(0.0, 0.25, 0.5, 0.75, 0.9)
        val sigmas = listOf(4.0, 6.0, 8.0)
        val seeds = 40
        val rows = mutableListOf("perdida_muestras,ruido_sigma_db,exito_pct,falsas_alarmas_pct,giro_no_anunciado_pct,llegada_fallida_pct,llegada_mediana_s")
        val table = mutableMapOf<Pair<Double, Double>, Double>()
        for (s in sigmas) for (l in losses) {
            val runs = (1..seeds).map { run(l, s, it) }
            val ok = runs.count { it.ok } * 100.0 / seeds
            val fa = runs.count { it.falseAlarm } * 100.0 / seeds
            val arr = runs.mapNotNull { it.arrivalS }.sorted()
            val med = if (arr.isEmpty()) Double.NaN else arr[arr.size / 2]
            table[l to s] = ok
            rows += String.format(Locale.US, "%.2f,%.0f,%.1f,%.1f,%.1f,%.1f,%.2f", l, s, ok, fa, runs.count { it.missedTurn } * 100.0 / seeds, runs.count { it.noArrival } * 100.0 / seeds, med)
        }
        // Variante de diseño: detenerse 1,5 s en el punto donde hay que girar (más muestras para confirmarlo).
        rows += ""
        rows += "variante,perdida_muestras,ruido_sigma_db,exito_pct,giro_no_anunciado_pct"
        for (s in listOf(4.0, 6.0)) for (l in listOf(0.0, 0.25, 0.5, 0.75)) {
            val runs = (1..seeds).map { run(l, s, it, pauseAtTurnS = 1.5) }
            rows += String.format(Locale.US, "pausa_1.5s_en_giro,%.2f,%.0f,%.1f,%.1f", l, s, runs.count { it.ok } * 100.0 / seeds, runs.count { it.missedTurn } * 100.0 / seeds)
        }
        // Colisiones de anuncios (modelo ALOHA sin ranuras, Ghamari et al. 2018): paquete de 0,376 ms, intervalo 50 ms
        // + retardo aleatorio medio de 5 ms. p = 1 − exp(−2·(N−1)·T/I).
        rows += ""
        rows += "dispositivos_ble_cerca,prob_colision_pct"
        for (n in listOf(3, 6, 10, 20, 50, 100, 200)) {
            val p = 1 - exp(-2.0 * (n - 1) * 0.376 / 55.0)
            rows += String.format(Locale.US, "%d,%.1f", n, p * 100)
        }
        val out = File("build/capacidad_simulacion.csv")
        out.writeText(rows.joinToString("\n"))
        println(rows.joinToString("\n"))
        // Sin pérdidas y con el ruido medido en campo, el recorrido debe salir bien casi siempre.
        assertTrue("éxito base = ${table[0.0 to 4.0]}", table[0.0 to 4.0]!! >= 90.0)
    }
}
