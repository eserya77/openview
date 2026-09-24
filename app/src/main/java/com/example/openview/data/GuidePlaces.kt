package com.example.openview.data

import android.content.Context
import com.example.openview.nav.GuideSpot

/**
 * El lugar que recorre la persona usuaria con «Iniciar»: sus puntos (destinos) en el orden de la línea guía, cada uno
 * con su baliza. Sale del escenario elegido en el panel de administración y de la baliza asignada a cada posición
 * (la que se usó por última vez en la prueba guiada o en la guía de prueba). Si ese escenario no tiene balizas
 * asignadas, se usa la ruta activa.
 */
object GuidePlaces {

    data class Place(val name: String, val spots: List<GuideSpot>)

    fun current(): Place? {
        val setup = TestSetupRepository.selected
        val beacons = TestSetupRepository.assignmentFor(setup.id)?.takeIf { it.size == setup.spots.size }
        if (beacons != null) {
            return Place(setup.name, setup.spots.mapIndexed { i, s -> GuideSpot(s.id, s.label, beacons[i], s.x, s.y) })
        }
        val g = RouteRepository.activeGraph
        val spots = g.nodes.filter { it.beaconName != null }.map { GuideSpot(it.id, it.label, it.beaconName!!, it.x, it.y) }
        return if (spots.size >= 2) Place("Ruta activa", spots) else null
    }

    /**
     * Ruta desde el punto donde estás ([fromIdx]) hasta el destino ([toIdx]), siguiendo el orden de la línea guía
     * en el sentido que haga falta.
     */
    fun route(place: Place, fromIdx: Int, toIdx: Int): List<GuideSpot> =
        if (fromIdx <= toIdx) place.spots.subList(fromIdx, toIdx + 1) else place.spots.subList(toIdx, fromIdx + 1).reversed()
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
