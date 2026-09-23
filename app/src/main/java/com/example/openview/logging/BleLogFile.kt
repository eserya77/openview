package com.example.openview.logging

import android.content.Context
import android.net.Uri

/**
 * Lee un CSV exportado por [BleSessionLogger.exportAndShare] y lo convierte
 * de vuelta en [BleLogRow]. El formato es el que escribe ese exportador:
 * una línea `sep=,` opcional, una de encabezado, y luego una fila por lectura
 * con el campo `note` entre comillas solo si contiene comas o comillas.
 */
object BleLogFile {

    fun parse(text: String): List<BleLogRow> {
        val lines = text.lineSequence()
            .map { it.trimEnd('\r') }
            .filterNot { it.isBlank() }
            .filterNot { it.startsWith("sep=") }
            .toList()
        if (lines.size <= 1) return emptyList()
        // La primera línea restante es el encabezado de columnas; se descarta.
        return lines.drop(1).mapNotNull(::parseLine)
    }

    private fun parseLine(line: String): BleLogRow? {
        val parts = line.split(",", limit = 12)
        if (parts.size < 12) return null

        fun double(i: Int): Double? = parts[i].trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        fun int(i: Int): Int? = parts[i].trim().takeIf { it.isNotEmpty() }?.toIntOrNull()

        val timestamp = double(0) ?: return null
        val note = parts[11].trim().let { raw ->
            if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
                raw.substring(1, raw.length - 1).replace("\"\"", "\"")
            } else raw
        }

        return BleLogRow(
            timestampS = timestamp,
            beaconName = parts[1].trim(),
            rssiRaw = int(2),
            rssiSmoothed = double(3),
            estimatedDistanceM = double(4),
            computedXM = double(5),
            computedYM = double(6),
            detectedNodeId = parts[7].trim().ifBlank { null },
            signalState = parts[8].trim(),
            trueXM = double(9),
            trueYM = double(10),
            note = note
        )
    }

    /** Lee el contenido de texto de un archivo elegido por el usuario (selector del sistema). */
    fun readText(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
    }.getOrNull()
}
