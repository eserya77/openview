package com.example.openview.logging

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.content.FileProvider
import com.example.openview.data.NavNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Una fila del registro de una sesión de calibración BLE.
 *
 * Desde la v2 hay una fila por cada MUESTRA nueva de una baliza (antes había
 * una por baliza en cada actualización de la lista, repitiendo valores viejos),
 * más filas de evento: notas, llegadas/salidas del modo prueba guiado.
 *
 * Las 12 primeras columnas son las del formato v1 y de `ble.py`; las nuevas van
 * detrás de `note` y [BleLogFile] lee ambos formatos.
 */
data class BleLogRow(
    val timestampS: Double,
    val beaconName: String,
    val rssiRaw: Int?,
    val rssiSmoothed: Double?,
    val estimatedDistanceM: Double?,
    val computedXM: Double?,
    val computedYM: Double?,
    val detectedNodeId: String?,
    val signalState: String,
    val trueXM: Double? = null,
    val trueYM: Double? = null,
    val note: String = "",
    // --- v2 ---
    val event: String = MarkEvent.SAMPLE,
    val trueNode: String? = null,
    /** RSSI suavizado menos la referencia a 1 m de la baliza. */
    val relDb: Double? = null,
    val confidence: Double? = null,
    val stableMs: Long? = null,
    val fwVersion: Int? = null,
    val txLevel: Int? = null,
    /**
     * true = fila de una muestra realmente nueva (log v2); null = no se sabe (log v1, con valores
     * repetidos): el replay las deduce por cambio de valor. No se exporta como columna.
     */
    val sampleNew: Boolean? = true
)

data class BleSessionState(
    val isRecording: Boolean = false,
    val sessionName: String = "",
    val rowCount: Int = 0,
    val trueMarkCount: Int = 0,
    val startedAtMs: Long = 0L
)

/**
 * Graba en memoria cada muestra BLE (cruda y ya procesada) durante una sesión
 * de prueba, y la exporta como CSV.
 *
 * Es un observador puro: no decide nada de navegación ni toca el escáner o el
 * positioner. Se alimenta desde el mismo punto donde ya se procesan las
 * lecturas en tiempo real (ver `OpenViewApp` en Navigation.kt), así que grabar
 * una sesión nunca cambia el comportamiento de la app para la persona usuaria.
 */
object BleSessionLogger {

    const val HEADER =
        "timestamp_s,beacon_name,rssi_raw,rssi_smoothed,estimated_distance_m," +
            "computed_x_m,computed_y_m,detected_node_id,signal_state,true_x_m,true_y_m,note," +
            "event,true_node,rel_db,confidence,stable_ms,fw_version,tx_level"

    private val rows = mutableListOf<BleLogRow>()
    private var sessionStartElapsedMs = 0L

    // Verdad de terreno "de un toque" del mapa (legado): se aplica a todas las filas del
    // PRÓXIMO lote de lecturas (mismo timestamp_s) y luego se olvida.
    private var pendingTrue: Pair<Double, Double>? = null
    private var pendingBatchTimestamp: Double? = null

    private val _state = MutableStateFlow(BleSessionState())
    val state: StateFlow<BleSessionState> = _state.asStateFlow()

    /**
     * Empieza una sesión nueva y descarta cualquier registro previo sin exportar.
     * [conditions], si se llena, queda como una nota de la sesión. Siempre se anota
     * el modelo de teléfono, porque el RSSI cambia de un teléfono a otro.
     */
    fun start(sessionName: String, conditions: String = "") {
        rows.clear()
        pendingTrue = null
        pendingBatchTimestamp = null
        sessionStartElapsedMs = SystemClock.elapsedRealtime()
        _state.value = BleSessionState(
            isRecording = true,
            sessionName = sessionName.trim().ifBlank { defaultName() },
            rowCount = 0,
            trueMarkCount = 0,
            startedAtMs = System.currentTimeMillis()
        )
        addNote("Teléfono: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
        if (conditions.isNotBlank()) {
            addNote("Condiciones iniciales: ${conditions.trim()}")
        }
    }

    fun stop() {
        _state.update { it.copy(isRecording = false) }
    }

    /**
     * Marca "estoy físicamente parado aquí ahora mismo" tocando el mapa (modo antiguo).
     * Aplica a las lecturas que lleguen a continuación, no a las pasadas.
     * Para pruebas nuevas usa el modo guiado ([markNode]).
     */
    fun markTruePosition(x: Double, y: Double) {
        if (!_state.value.isRecording) return
        pendingTrue = x to y
        pendingBatchTimestamp = null
        _state.update { it.copy(trueMarkCount = it.trueMarkCount + 1) }
    }

    /**
     * Marca de llegada o salida de un nodo del grafo. [lagS] > 0 corrige el retraso
     * con que se tocó el botón ("llegué hace 5 s"): la marca queda en `ahora − lagS`.
     * Devuelve el tiempo de sesión (s) al que quedó la marca.
     */
    fun markNode(kind: String, node: NavNode, lagS: Double = 0.0, label: String = node.label): Double {
        val t = (elapsedSeconds() - lagS).coerceAtLeast(0.0)
        if (!_state.value.isRecording) return t
        val verb = if (kind == MarkEvent.ARRIVE) "LLEGUÉ" else "SALGO"
        val lag = if (lagS > 0.0) " (hace ${"%.0f".format(Locale.US, lagS)} s)" else ""
        rows.add(
            BleLogRow(
                timestampS = t, beaconName = "_MARK_", rssiRaw = null, rssiSmoothed = null,
                estimatedDistanceM = null, computedXM = null, computedYM = null,
                detectedNodeId = null, signalState = "-",
                trueXM = node.x, trueYM = node.y, note = "$verb $label$lag",
                event = kind, trueNode = node.id
            )
        )
        _state.update { it.copy(rowCount = rows.size, trueMarkCount = it.trueMarkCount + if (kind == MarkEvent.ARRIVE) 1 else 0) }
        return t
    }

    /** Agrega una lectura. No hace nada si no hay una sesión activa. */
    fun log(row: BleLogRow) {
        if (!_state.value.isRecording) return

        var finalRow = row
        val truth = pendingTrue
        if (truth != null) {
            if (pendingBatchTimestamp == null) pendingBatchTimestamp = row.timestampS
            if (row.timestampS == pendingBatchTimestamp) {
                finalRow = row.copy(trueXM = truth.first, trueYM = truth.second)
            } else {
                // Ya pasó el instante marcado: se deja de aplicar esa verdad de terreno.
                pendingTrue = null
                pendingBatchTimestamp = null
            }
        }

        rows.add(finalRow)
        _state.update { it.copy(rowCount = rows.size) }
    }

    /** Registra la configuración de la prueba (escenario, rotación, factores) como una fila LAYOUT. */
    fun addLayout(description: String) {
        if (description.isBlank()) return
        log(
            BleLogRow(
                timestampS = elapsedSeconds(), beaconName = "_LAYOUT_",
                rssiRaw = null, rssiSmoothed = null, estimatedDistanceM = null,
                computedXM = null, computedYM = null, detectedNodeId = null,
                signalState = "-", note = description.trim(), event = MarkEvent.LAYOUT
            )
        )
    }

    /** Inserta una fila de nota de texto libre con la marca de tiempo actual. */
    fun addNote(note: String) {
        if (note.isBlank()) return
        log(
            BleLogRow(
                timestampS = elapsedSeconds(),
                beaconName = "_NOTE_",
                rssiRaw = null, rssiSmoothed = null, estimatedDistanceM = null,
                computedXM = null, computedYM = null, detectedNodeId = null,
                signalState = "-",
                note = note.trim(),
                event = MarkEvent.NOTE
            )
        )
    }

    fun elapsedSeconds(): Double =
        if (sessionStartElapsedMs == 0L) 0.0
        else (SystemClock.elapsedRealtime() - sessionStartElapsedMs) / 1000.0

    /** Filas de la sesión ordenadas por tiempo y con la verdad de terreno rellenada según las marcas. */
    fun currentRows(): List<BleLogRow> = GroundTruth.fill(rows.sortedBy { it.timestampS })

    /** Escribe el CSV en la caché de la app y abre el selector de "compartir" del sistema. */
    fun exportAndShare(context: Context) {
        val export = currentRows()
        if (export.isEmpty()) return
        val snapshot = _state.value
        val safeName = snapshot.sessionName.ifBlank { defaultName() }
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
        val stamp = if (snapshot.startedAtMs > 0L) {
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(snapshot.startedAtMs))
        } else defaultName()
        val file = File(context.cacheDir, "openview_ble_${safeName}_$stamp.csv")
        file.bufferedWriter().use { writer ->
            // Misma directiva que ble.py: ayuda a Excel en configuraciones ES
            // a detectar la coma como separador.
            writer.write("sep=,\n")
            writer.write(HEADER)
            writer.write("\n")
            export.forEach { writer.write(toCsvLine(it)); writer.write("\n") }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Exportar registro BLE"))
    }

    fun toCsvLine(r: BleLogRow): String {
        fun num(v: Double?) = v?.let { "%.3f".format(Locale.US, it) } ?: ""
        fun int(v: Int?) = v?.toString() ?: ""
        fun quoted(v: String) =
            if (v.contains(",") || v.contains("\"")) "\"${v.replace("\"", "\"\"")}\"" else v

        return listOf(
            "%.2f".format(Locale.US, r.timestampS),
            r.beaconName,
            int(r.rssiRaw),
            num(r.rssiSmoothed),
            num(r.estimatedDistanceM),
            num(r.computedXM),
            num(r.computedYM),
            r.detectedNodeId ?: "",
            r.signalState,
            num(r.trueXM),
            num(r.trueYM),
            quoted(r.note),
            r.event,
            r.trueNode ?: "",
            num(r.relDb),
            num(r.confidence),
            r.stableMs?.toString() ?: "",
            int(r.fwVersion),
            int(r.txLevel)
        ).joinToString(",")
    }

    private fun defaultName(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
}
