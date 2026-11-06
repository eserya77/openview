package com.example.openview.data

import android.content.Context
import com.example.openview.nav.GuideSpot

/**
 * El lugar que recorre la persona usuaria con «Iniciar».
 *
 * 1. El lugar ACTIVO descargado del guardia (o cargado a mano): balizas con nombre propio y sus rutas.
 * 2. Si no hay ninguno, el escenario de prueba del panel de administración con sus balizas asignadas (en línea).
 * 3. Si tampoco, la ruta activa del panel.
 */
object GuidePlaces {

    data class Place(val place: SharedPlace, val downloaded: Boolean) {
        val name: String get() = place.name
        val spots: List<GuideSpot> get() = place.beacons.map { GuideSpot(it.beacon, it.name, it.beacon, it.x, it.y) }
    }

    fun current(): Place? {
        PlaceStore.active?.let { return Place(it.place, downloaded = true) }
        val setup = TestSetupRepository.selected
        val beacons = TestSetupRepository.assignmentFor(setup.id)?.takeIf { it.size == setup.spots.size && it.toSet().size == it.size }
        val spots = if (beacons != null) setup.spots.mapIndexed { i, s -> PlaceBeacon(beacons[i], s.label, s.x, s.y) }
        else RouteRepository.activeGraph.nodes.filter { it.beaconName != null }.map { PlaceBeacon(it.beaconName!!, it.label, it.x, it.y) }
        if (spots.size < 2 || spots.map { it.beacon }.toSet().size != spots.size) return null
        val name = if (beacons != null) setup.name else "Ruta activa"
        return Place(SharedPlace("local_" + (if (beacons != null) setup.id else "ruta"), name, spots, spots.zipWithNext { a, b -> a.beacon to b.beacon }), downloaded = false)
    }
}

/** Configuración de voz y avisos de la persona usuaria. Se guarda en el teléfono. */
object VoicePrefs {
    private const val PREFS = "openview_voice"
    private var ctx: Context? = null

    fun initialize(context: Context) { ctx = context.applicationContext }

    fun load(): VoiceSettings {
        val p = ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return VoiceSettings()
        val d = VoiceSettings()
        return VoiceSettings(
            speed = p.getFloat("speed", d.speed), volume = p.getFloat("volume", d.volume),
            beeps = p.getBoolean("beeps", d.beeps), vibration = p.getBoolean("vibration", d.vibration)
        )
    }

    fun save(v: VoiceSettings) {
        ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putFloat("speed", v.speed)?.putFloat("volume", v.volume)
            ?.putBoolean("beeps", v.beeps)?.putBoolean("vibration", v.vibration)?.apply()
    }
}
