package com.example.openview.calibration

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Modelo del asistente de calibración por baliza. Kotlin puro (sin Android): se prueba en JVM.
 *
 * El asistente guía a la persona por una lista de ESCENARIOS (contacto, distancias en línea recta,
 * ángulos a 1 m…), mide durante unos segundos en cada uno y de ahí calcula el perfil de la baliza.
 */
enum class ScenarioKind { CONTACT, FRONT, ANGLE, BODY }

data class CalibrationScenario(
    val id: String,
    val kind: ScenarioKind,
    /** Distancia a la baliza en metros (0 = contacto). */
    val distanceM: Double,
    /** Ángulo alrededor de la baliza en grados (0 = de frente, al lado de la antena). */
    val angleDeg: Int,
    /** Segundos que se mide en este escenario. */
    val holdS: Int,
    val title: String,
    val instruction: String
)

object CalibrationScenarios {

    private val LINE_DISTANCES = listOf(0.3, 1.0, 2.0, 3.0, 4.0, 6.0)

    /**
     * Escenarios según el espacio disponible en línea recta ([maxDistanceM], p. ej. 6 m de pasillo).
     * Orden pensado para caminar lo menos posible: contacto → distancias crecientes → ángulos a 1 m.
     */
    fun forMaxDistance(maxDistanceM: Double, includeAngles: Boolean = true, includeBody: Boolean = false): List<CalibrationScenario> {
        val out = mutableListOf<CalibrationScenario>()
        out += CalibrationScenario(
            "contact", ScenarioKind.CONTACT, 0.0, 0, 10, "Contacto",
            "Apoya el móvil sobre la baliza, en el extremo de la antena (el lado opuesto al conector USB). Siempre el mismo lado del móvil."
        )
        LINE_DISTANCES.filter { it <= maxDistanceM + 1e-9 }.forEach { d ->
            val label = if (d < 1.0) "${(d * 100).toInt()} cm" else "${"%.0f".format(d)} m"
            out += CalibrationScenario(
                "front_$label", ScenarioKind.FRONT, d, 0, if (d <= 1.0) 12 else 15, "A $label de frente",
                "Ponte a $label de la baliza, de frente, con el móvil en la mano como lo llevarás normalmente. Mide la distancia con la cinta."
            )
        }
        if (includeAngles) {
            listOf(90, 180, 270).forEach { a ->
                out += CalibrationScenario(
                    "angle_$a", ScenarioKind.ANGLE, 1.0, a, 12, "A 1 m, ángulo $a°",
                    "Camina alrededor de la baliza, siempre a 1 m, hasta quedar a $a° respecto de tu posición de frente (0° = de frente)."
                )
            }
        }
        if (includeBody) {
            out += CalibrationScenario(
                "body", ScenarioKind.BODY, 1.0, 0, 12, "A 1 m con el cuerpo en medio",
                "Ponte a 1 m con tu cuerpo entre la baliza y el móvil (de espaldas a la baliza)."
            )
        }
        return out
    }
}

/** Estadística de una medición: muestras reales recibidas durante el escenario. */
data class ScenarioResult(
    val scenarioId: String,
    val kind: ScenarioKind,
    val distanceM: Double,
    val angleDeg: Int,
    val n: Int,
    val median: Double,
    val sd: Double,
    /** Muestras nuevas por segundo de esa baliza durante la medición. */
    val ratePerS: Double
)

enum class Quality(val label: String) { A("A · fiable"), B("B · usable con cuidado"), C("C · no fiable") }

data class BeaconProfile(
    val beacon: String,
    val phoneModel: String,
    val createdAtMs: Long,
    val results: List<ScenarioResult>,
    /** RSSI mediano en contacto; base del umbral de "llegué". */
    val contactRssi: Double?,
    val contactSd: Double?,
    /** RSSI a 1 m según el ajuste log-distancia (A₁ₘ) y exponente de pérdida (n). Null si faltan distancias. */
    val a1m: Double?,
    val pathLossN: Double?,
    val fitResidualSd: Double?,
    /** Máximo − mínimo del RSSI a 1 m entre los ángulos medidos (0°, 90°, 180°, 270°). */
    val asymmetryDb: Double?,
    val meanRatePerS: Double,
    val quality: Quality,
    val reasons: List<String>
)

/** Decide cuándo la señal está "estable" para empezar a medir sola. */
object StabilityDetector {
    fun isStable(lastSamples: List<Int>, minSamples: Int = 10, maxSdDb: Double = 2.5): Boolean {
        if (lastSamples.size < minSamples) return false
        return sd(lastSamples.map { it.toDouble() }) <= maxSdDb
    }

    fun sd(v: List<Double>): Double {
        if (v.size < 2) return 0.0
        val m = v.average()
        return sqrt(v.sumOf { (it - m) * (it - m) } / (v.size - 1))
    }
}

object ProfileFitter {

    /** Resultado de un escenario a partir de sus muestras; null si no hay suficientes. */
    fun summarize(scenario: CalibrationScenario, samples: List<Int>, durationS: Double, minSamples: Int = 6): ScenarioResult? {
        if (samples.size < minSamples) return null
        val sorted = samples.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2].toDouble()
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        return ScenarioResult(
            scenario.id, scenario.kind, scenario.distanceM, scenario.angleDeg,
            samples.size, median, StabilityDetector.sd(samples.map { it.toDouble() }),
            if (durationS > 0) samples.size / durationS else 0.0
        )
    }

    /**
     * Ajusta RSSI = A₁ₘ − 10·n·log10(d) con los escenarios de frente (d ≥ 0,3 m) y calcula
     * asimetría, contacto y una nota de calidad con sus motivos.
     */
    fun build(beacon: String, phoneModel: String, atMs: Long, results: List<ScenarioResult>): BeaconProfile {
        val contact = results.firstOrNull { it.kind == ScenarioKind.CONTACT }
        val front = results.filter { it.kind == ScenarioKind.FRONT && it.distanceM >= 0.3 }.sortedBy { it.distanceM }
        val reasons = mutableListOf<String>()
        var worst = Quality.A
        fun downgrade(q: Quality, reason: String) {
            reasons += reason
            if (q.ordinal > worst.ordinal) worst = q
        }

        // --- Ajuste log-distancia por mínimos cuadrados ---
        var a1m: Double? = null
        var n: Double? = null
        var resid: Double? = null
        val distinct = front.map { it.distanceM }.distinct().size
        if (distinct >= 3) {
            val xs = front.map { -10.0 * log10(it.distanceM) }
            val ys = front.map { it.median }
            val mx = xs.average(); val my = ys.average()
            val sxx = xs.sumOf { (it - mx) * (it - mx) }
            if (sxx > 1e-9) {
                val slope = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / sxx
                n = slope
                a1m = my - slope * mx
                resid = sqrt(xs.indices.sumOf { i -> val e = ys[i] - (a1m + slope * xs[i]); e * e } / max(1, xs.size - 2))
            }
        } else {
            front.firstOrNull { it.distanceM == 1.0 }?.let { a1m = it.median }
            downgrade(Quality.B, "Faltan distancias para ajustar la curva (se necesitan al menos 3 distintas desde 30 cm).")
        }
        if (front.none { it.distanceM == 1.0 }) downgrade(Quality.C, "Falta la medición a 1 m.")

        n?.let {
            when {
                it < 1.0 -> downgrade(Quality.C, "Curva plana (n = ${"%.1f".format(it)}): la señal casi no baja con la distancia. No usar para estimar distancia; solo sirve la cercanía.")
                it > 4.5 -> downgrade(Quality.B, "Curva muy inclinada (n = ${"%.1f".format(it)}): posible obstáculo o antena tapada.")
            }
        }
        resid?.let {
            when {
                it > 7.0 -> downgrade(Quality.C, "El ajuste es malo (residuo ${"%.1f".format(it)} dB): mediciones inconsistentes o multitrayecto fuerte.")
                it > 4.0 -> downgrade(Quality.B, "Ajuste regular (residuo ${"%.1f".format(it)} dB).")
            }
        }

        // --- Asimetría del patrón: 1 m de frente y a los demás ángulos ---
        val ring = results.filter { it.distanceM == 1.0 && (it.kind == ScenarioKind.ANGLE || (it.kind == ScenarioKind.FRONT && it.angleDeg == 0)) }
        val asym = if (ring.size >= 3) ring.maxOf { it.median } - ring.minOf { it.median } else null
        asym?.let {
            when {
                it > 15 -> downgrade(Quality.C, "El patrón de la antena es muy asimétrico (${"%.0f".format(it)} dB entre ángulos): la señal depende del lado. Orienta la baliza hacia donde vas a caminar.")
                it > 8 -> downgrade(Quality.B, "Patrón asimétrico (${"%.0f".format(it)} dB entre ángulos).")
            }
        }

        // --- Tasa de muestras ---
        val rate = if (results.isEmpty()) 0.0 else results.map { it.ratePerS }.average()
        when {
            results.isNotEmpty() && rate < 3.0 -> downgrade(Quality.C, "Muy pocas muestras (${"%.1f".format(rate)}/s): revisa alimentación, altura y distancia.")
            results.isNotEmpty() && rate < 5.0 -> downgrade(Quality.B, "Pocas muestras (${"%.1f".format(rate)}/s).")
        }

        // --- Estabilidad de cada escenario ---
        results.filter { it.sd > 5.0 }.forEach {
            downgrade(Quality.B, "Medición inestable en ${describe(it)} (desviación ${"%.1f".format(it.sd)} dB): repítela sin moverte.")
        }
        if (contact != null && contact.median < -60) downgrade(Quality.B, "Contacto débil (${"%.0f".format(contact.median)} dBm): ¿apoyaste en el extremo de la antena?")
        if (contact == null) downgrade(Quality.B, "Falta la medición de contacto.")

        return BeaconProfile(
            beacon, phoneModel, atMs, results,
            contact?.median, contact?.sd, a1m, n, resid, asym, rate,
            worst, if (reasons.isEmpty()) listOf("Sin observaciones: la curva y el patrón son coherentes.") else reasons
        )
    }

    private fun describe(r: ScenarioResult): String = when (r.kind) {
        ScenarioKind.CONTACT -> "contacto"
        ScenarioKind.FRONT -> if (r.distanceM < 1.0) "${(r.distanceM * 100).toInt()} cm" else "${"%.0f".format(r.distanceM)} m"
        ScenarioKind.ANGLE -> "1 m a ${r.angleDeg}°"
        ScenarioKind.BODY -> "1 m con el cuerpo en medio"
    }

    /** Distancia estimada con el ajuste del perfil (o null si no hay ajuste fiable). */
    fun distanceFor(profile: BeaconProfile, rssi: Double): Double? {
        val a = profile.a1m ?: return null
        val n = profile.pathLossN ?: return null
        if (n < 0.5) return null
        return Math.pow(10.0, (a - rssi) / (10.0 * n)).coerceIn(0.05, 30.0)
    }
}
