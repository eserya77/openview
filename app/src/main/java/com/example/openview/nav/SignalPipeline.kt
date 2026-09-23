package com.example.openview.nav

/**
 * Parámetros del pipeline de señal + el seguidor de nodo. Viven juntos para
 * poder probarlos desde el replay y el sandbox sin tocar código.
 */
data class TrackerParams(
    /** Peso de la muestra nueva en el EMA (0..1). Más alto = más rápido pero más nervioso. */
    val alpha: Double = 0.5,
    /** Ventana de la mediana previa al EMA: recorta picos aislados de ±10 dB. Debe ser impar. */
    val medianSize: Int = 3,
    /** Tras este tiempo sin muestras nuevas, una baliza deja de contar. */
    val expiryMs: Long = 3_000L,
    /** Cuánto debe ganar un retador (dB, ya calibrado) para quitarle el nodo al actual. */
    val marginDb: Double = 4.0,
    /** Cuánto tiempo seguido debe ganar por ese margen antes de aceptar el cambio. */
    val holdMs: Long = 700L,
    /** Margen exigido para pasar a un nodo que NO es vecino del actual en el grafo. */
    val jumpMarginDb: Double = 10.0
)

/**
 * Filtro de una sola baliza: mediana de las últimas N muestras y luego EMA.
 *
 * Se alimenta SOLO con muestras nuevas (una por cada anuncio recibido). La v1
 * de la app promediaba el mismo valor repetido ~22 veces por segundo, así que
 * el "suavizado" era casi inexistente.
 */
class BeaconFilter(private val alpha: Double, private val medianSize: Int) {
    private val window = ArrayDeque<Int>()

    var smoothed: Double? = null
        private set
    var lastRaw: Int? = null
        private set
    var lastSampleMs: Long = Long.MIN_VALUE
        private set

    fun add(rssi: Int, tMs: Long) {
        window.addLast(rssi)
        while (window.size > medianSize) window.removeFirst()
        val median = window.sorted()[window.size / 2].toDouble()
        val prev = smoothed
        smoothed = if (prev == null) median else alpha * median + (1 - alpha) * prev
        lastRaw = rssi
        lastSampleMs = tMs
    }
}

/**
 * Mantiene un [BeaconFilter] por baliza y sabe cuáles siguen "vivas".
 * Es Kotlin puro (sin Android): lo usan igual la app en vivo y el replay, así
 * que un log grabado reproduce exactamente lo que la app habría calculado.
 */
class SignalPipeline(private val params: TrackerParams = TrackerParams()) {
    private val filters = mutableMapOf<String, BeaconFilter>()

    fun onSample(beacon: String, rssi: Int, tMs: Long) {
        filters.getOrPut(beacon) { BeaconFilter(params.alpha, params.medianSize) }.add(rssi, tMs)
    }

    /** RSSI suavizado de las balizas con una muestra más reciente que [TrackerParams.expiryMs]. */
    fun smoothedAt(nowMs: Long): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        filters.forEach { (name, f) ->
            val s = f.smoothed
            if (s != null && nowMs - f.lastSampleMs <= params.expiryMs) out[name] = s
        }
        return out
    }

    fun lastRaw(beacon: String): Int? = filters[beacon]?.lastRaw

    fun reset() = filters.clear()
}
