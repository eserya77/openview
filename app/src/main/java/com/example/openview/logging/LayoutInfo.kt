package com.example.openview.logging

import com.example.openview.data.Rotation
import com.example.openview.data.TestLayout
import com.example.openview.data.TestSetup

/**
 * Lo que dice la fila LAYOUT de un log de la prueba guiada: escenario, rotación, qué baliza estaba en
 * cada posición, tipo de parada y modo. Permite reconstruir el grafo y la clave de huellas al reproducir el log.
 */
data class LayoutInfo(
    val setupId: String,
    val rotationId: String,
    /** posición ("P1"…) -> baliza. */
    val beaconAt: Map<String, String>,
    val stay: String,
    val mode: String?,
    val lap: Int?
) {
    /** Rotación en el orden de posiciones de [setup], o null si el log no cubre todas. */
    fun rotationFor(setup: TestSetup): Rotation? {
        val list = setup.spots.map { beaconAt[it.id] ?: return null }
        return Rotation(rotationId, rotationId, list)
    }

    fun keyFor(setup: TestSetup): String? = rotationFor(setup)?.let { TestLayout.key(setup, it, stay) }

    companion object {
        fun fromRows(rows: List<BleLogRow>): LayoutInfo? {
            val note = rows.firstOrNull { it.event == MarkEvent.LAYOUT }?.note ?: return null
            val kv = note.split(";").mapNotNull { p -> p.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
            val setup = kv["setup"] ?: return null
            val spots = kv.filterKeys { Regex("P\\d+").matches(it) }
            if (spots.isEmpty()) return null
            return LayoutInfo(setup, kv["rot"] ?: "?", spots, kv["stay"] ?: "contacto", kv["mode"], kv["lap"]?.toIntOrNull())
        }
    }
}
