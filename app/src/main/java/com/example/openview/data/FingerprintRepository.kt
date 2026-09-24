package com.example.openview.data

import android.content.Context
import com.example.openview.nav.FingerprintSet
import com.example.openview.nav.Fingerprints
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Vueltas de entrenamiento guardadas en el teléfono. La clave describe la DISPOSICIÓN completa
 * (escenario + qué baliza en cada posición + tipo de parada): si mueves las placas o cambias de contacto
 * a 1 m, es otra huella. Cada vuelta aporta, por punto, el RSSI mediano de cada baliza.
 */
object FingerprintRepository {
    private const val PREFS = "openview_fingerprints"
    private const val KEY = "laps_v1"
    private const val MAX_LAPS = 8
    private var initialized = false
    private lateinit var appContext: Context

    /** clave de disposición -> punto -> lista de vueltas (baliza -> RSSI mediano). */
    private val _laps = MutableStateFlow<Map<String, Map<String, List<Map<String, Double>>>>>(emptyMap())
    val laps: StateFlow<Map<String, Map<String, List<Map<String, Double>>>>> = _laps.asStateFlow()

    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        initialized = true
        load()?.let { _laps.value = it }
    }

    /** Huellas de la disposición [key], o null si nunca se entrenó. */
    fun setFor(key: String): FingerprintSet? {
        val snapshot = _laps.value
        // Se consulta en cada muestra (~20 Hz): se reutiliza el resultado mientras no cambien las vueltas.
        if (key == cacheKey && snapshot === cacheSnapshot) return cacheSet
        val byNode = snapshot[key]
        val set = if (byNode == null || byNode.isEmpty()) null
        else FingerprintSet(key, byNode.mapValues { (node, laps) -> Fingerprints.merge(node, laps) })
        cacheKey = key; cacheSnapshot = snapshot; cacheSet = set
        return set
    }

    @Volatile private var cacheKey: String? = null
    @Volatile private var cacheSnapshot: Any? = null
    @Volatile private var cacheSet: FingerprintSet? = null

    fun lapCount(key: String): Int = _laps.value[key]?.values?.maxOfOrNull { it.size } ?: 0

    /** Agrega una vuelta de entrenamiento (punto -> baliza -> RSSI). Se conservan las últimas [MAX_LAPS]. */
    fun addLap(key: String, vectors: Map<String, Map<String, Double>>) {
        if (vectors.isEmpty()) return
        val current = _laps.value[key] ?: emptyMap()
        val merged = current.toMutableMap()
        vectors.forEach { (node, vec) -> merged[node] = ((current[node] ?: emptyList()) + listOf(vec)).takeLast(MAX_LAPS) }
        _laps.value = _laps.value + (key to merged)
        save()
    }

    fun clear(key: String) {
        _laps.value = _laps.value - key
        save()
    }

    private fun save() {
        if (!initialized) return
        val root = JSONObject()
        _laps.value.forEach { (key, byNode) ->
            root.put(key, JSONObject().also { n ->
                byNode.forEach { (node, laps) ->
                    n.put(node, org.json.JSONArray().also { arr ->
                        laps.forEach { vec -> arr.put(JSONObject().also { o -> vec.forEach { (b, v) -> o.put(b, v) } }) }
                    })
                }
            })
        }
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, root.toString()).apply()
    }

    private fun load(): Map<String, Map<String, List<Map<String, Double>>>>? = runCatching {
        val raw = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        val root = JSONObject(raw)
        root.keys().asSequence().associateWith { key ->
            val n = root.getJSONObject(key)
            n.keys().asSequence().associateWith { node ->
                val arr = n.getJSONArray(node)
                (0 until arr.length()).map { i -> arr.getJSONObject(i).let { o -> o.keys().asSequence().associateWith { b -> o.getDouble(b) } } }
            }
        }
    }.getOrNull()
}

/** Qué disposición de huellas está activa ahora (la fija la prueba guiada mientras está abierta). */
object ActiveFingerprints {
    @Volatile var key: String? = null

    fun current(): FingerprintSet? = key?.let { FingerprintRepository.setFor(it) }
}

