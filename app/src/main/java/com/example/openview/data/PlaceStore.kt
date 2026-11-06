package com.example.openview.data

import android.content.Context
import com.example.openview.share.ShareProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Lugares guardados en el teléfono.
 *
 * - Persona usuaria: los lugares que descargó del guardia (o cargó a mano). El ACTIVO es el que usa «Iniciar».
 * - Guardia: el lugar que comparte (el JSON de la empresa), guardado aparte.
 */
object PlaceStore {
    private const val PREFS = "openview_places"
    private var ctx: Context? = null

    data class Saved(val place: SharedPlace, val json: String, val hash: Int)

    private val _places = MutableStateFlow<List<Saved>>(emptyList())
    val places: StateFlow<List<Saved>> = _places.asStateFlow()
    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()
    private val _guard = MutableStateFlow<Saved?>(null)
    /** Lugar que comparte este teléfono en modo guardia. */
    val guard: StateFlow<Saved?> = _guard.asStateFlow()

    private fun dir() = File(ctx!!.filesDir, "lugares").apply { mkdirs() }
    private fun prefs() = ctx!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun initialize(context: Context) {
        ctx = context.applicationContext
        _places.value = dir().listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f -> runCatching { saved(f.readText()) }.getOrNull() }
            .sortedBy { it.place.name }
        _activeId.value = prefs().getString("active", null)?.takeIf { id -> _places.value.any { it.place.id == id } }
        _guard.value = File(ctx!!.filesDir, "guardia.json").takeIf { it.exists() }?.let { runCatching { saved(it.readText()) }.getOrNull() }
    }

    private fun saved(json: String) = Saved(PlaceJson.parse(json), json, ShareProtocol.hash(json.toByteArray()))

    val active: Saved? get() = _places.value.firstOrNull { it.place.id == _activeId.value }

    /** ¿Ya tengo este lugar, idéntico? */
    fun hasHash(hash: Int): Boolean = _places.value.any { it.hash == hash }

    /** Guarda (o reemplaza) un lugar y lo deja activo. Lanza [PlaceJson.InvalidPlace] si el JSON no sirve. */
    fun saveAndActivate(json: String): Saved {
        val s = saved(json)
        File(dir(), safe(s.place.id) + ".json").writeText(json)
        _places.value = (_places.value.filter { it.place.id != s.place.id } + s).sortedBy { it.place.name }
        setActive(s.place.id)
        return s
    }

    fun setActive(id: String?) {
        _activeId.value = id
        prefs().edit().putString("active", id).apply()
    }

    fun delete(id: String) {
        File(dir(), safe(id) + ".json").delete()
        _places.value = _places.value.filter { it.place.id != id }
        if (_activeId.value == id) setActive(null)
    }

    /** Modo guardia: fija el JSON de la empresa que se va a compartir. */
    fun setGuard(json: String): Saved {
        val s = saved(json)
        File(ctx!!.filesDir, "guardia.json").writeText(json)
        _guard.value = s
        return s
    }

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
}

/**
 * Hacia dónde miraba la persona al terminar la última ruta (el rumbo del último tramo). Si la siguiente ruta sale de
 * ese mismo punto, la primera indicación puede decir «gira a la derecha…» en vez de solo «camina hasta…».
 */
object LastHeading {
    private var beacon: String? = null
    private var heading = 0.0
    private var atMs = 0L

    fun set(beacon: String, heading: Double, nowMs: Long) { this.beacon = beacon; this.heading = heading; atMs = nowMs }

    /** El rumbo si terminaste en [beacon] hace menos de 10 minutos. */
    fun at(beacon: String, nowMs: Long): Double? =
        heading.takeIf { this.beacon.equals(beacon, true) && nowMs - atMs < 10 * 60_000L }
}
