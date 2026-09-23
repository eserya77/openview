package com.example.openview.logging

import android.content.Context
import android.net.Uri

/**
 * Lee un CSV exportado por [BleSessionLogger.exportAndShare] y lo convierte
 * de vuelta en [BleLogRow]. Entiende los dos formatos:
 *  - v1: 12 columnas (una fila por baliza en cada actualización, con valores repetidos);
 *  - v2: las mismas 12 + event, true_node, rel_db, confidence, stable_ms, fw_version, tx_level.
 *
 * Se leen por NOMBRE de columna (encabezado), así que el orden no importa, y
 * el campo `note` puede ir entre comillas con comas dentro.
 */
object BleLogFile {

    fun parse(text: String): List<BleLogRow> {
        val lines = text.lineSequence()
            .map { it.trimEnd('\r') }
            .filterNot { it.isBlank() }
            .filterNot { it.startsWith("sep=") }
            .toList()
        if (lines.size <= 1) return emptyList()

        val header = splitCsv(lines.first().trimStart('﻿')).map { it.trim() }
        val col = header.withIndex().associate { it.value to it.index }
        if ("timestamp_s" !in col || "beacon_name" !in col) return emptyList()

        return lines.drop(1).mapNotNull { parseLine(it, col) }
    }

    private fun parseLine(line: String, col: Map<String, Int>): BleLogRow? {
        val p = splitCsv(line)
        fun str(name: String): String? = col[name]?.let { p.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }
        fun double(name: String): Double? = str(name)?.toDoubleOrNull()
        fun int(name: String): Int? = str(name)?.toIntOrNull()

        val timestamp = double("timestamp_s") ?: return null
        val beacon = str("beacon_name") ?: return null
        val defaultEvent = when (beacon) {
            "_NOTE_" -> MarkEvent.NOTE
            "_NONE_" -> MarkEvent.NONE
            else -> MarkEvent.SAMPLE
        }

        return BleLogRow(
            timestampS = timestamp,
            beaconName = beacon,
            rssiRaw = int("rssi_raw"),
            rssiSmoothed = double("rssi_smoothed"),
            estimatedDistanceM = double("estimated_distance_m"),
            computedXM = double("computed_x_m"),
            computedYM = double("computed_y_m"),
            detectedNodeId = str("detected_node_id"),
            signalState = str("signal_state") ?: "",
            trueXM = double("true_x_m"),
            trueYM = double("true_y_m"),
            note = str("note") ?: "",
            event = str("event") ?: defaultEvent,
            trueNode = str("true_node"),
            relDb = double("rel_db"),
            confidence = double("confidence"),
            stableMs = str("stable_ms")?.toLongOrNull(),
            fwVersion = int("fw_version"),
            txLevel = int("tx_level"),
            // Un log con columna "event" es v2 (una fila = una muestra nueva); sin ella es v1.
            sampleNew = if ("event" in col) true else null
        )
    }

    /** Divide una línea CSV respetando campos entre comillas (con "" como comilla escapada). */
    internal fun splitCsv(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { out.add(cur.toString()); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out.add(cur.toString())
        return out
    }

    /** Lee el contenido de texto de un archivo elegido por el usuario (selector del sistema). */
    fun readText(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
    }.getOrNull()
}
