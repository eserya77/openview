package com.example.openview.logging

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Una fila del registro crudo de una sesión de calibración BLE.
 *
 * El esquema de columnas es deliberadamente compatible con el que genera
 * `ble.py` (el simulador de datos de escritorio), para poder comparar una
 * prueba real con una simulada usando exactamente las mismas herramientas.
 *
 * [trueXM] / [trueYM] se llenan cuando, durante la grabación, se toca el mapa
 * de "verdad de terreno" (ver [markTruePosition]) para decir "estoy parado
 * aquí ahora". Se aplican solo a las lecturas del instante en que se tocó,
 * no a las que vienen después.
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
    val note: String = ""
)

data class BleSessionState(
    val isRecording: Boolean = false,
    val sessionName: String = "",
    val rowCount: Int = 0,
    val trueMarkCount: Int = 0,
    val startedAtMs: Long = 0L
)

/**
 * Graba en memoria cada lectura BLE (cruda y ya procesada) durante una sesión
 * de prueba, y la exporta como CSV.
 *
 * Es un observador puro: no decide nada de navegación ni toca el escáner o el
 * positioner. Se alimenta desde el mismo punto donde ya se procesan las
 * lecturas en tiempo real (ver `OpenViewApp` en Navigation.kt), así que grabar
 * una sesión nunca cambia el comportamiento de la app para la persona usuaria.
 */
object BleSessionLogger {

    private const val HEADER =
        "timestamp_s,beacon_name,rssi_raw,rssi_smoothed,estimated_distance_m," +
            "computed_x_m,computed_y_m,detected_node_id,signal_state,true_x_m,true_y_m,note"

    private val rows = mutableListOf<BleLogRow>()
    private var sessionStartElapsedMs = 0L

    // Verdad de terreno pendiente: se aplica a todas las filas del PRÓXIMO
    // lote de lecturas (mismo timestamp_s) y luego se olvida, para no
    // etiquetar con un punto viejo las lecturas de segundos después.
    private var pendingTrue: Pair<Double, Double>? = null
    private var pendingBatchTimestamp: Double? = null

    private val _state = MutableStateFlow(BleSessionState())
    val state: StateFlow<BleSessionState> = _state.asStateFlow()

    /**
     * Empieza una sesión nueva y descarta cualquier registro previo sin exportar.
     * [conditions], si se llena, queda como la primera nota de la sesión
     * ("puerta principal cerrada", "hora punta", etc.) — metadata que se te
     * olvida anotar si no la pides al empezar.
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
        if (conditions.isNotBlank()) {
            addNote("Condiciones iniciales: ${conditions.trim()}")
        }
    }

    fun stop() {
        _state.update { it.copy(isRecording = false) }
    }

    /**
     * Marca "estoy físicamente parado aquí ahora mismo". Las coordenadas están
     * en el mismo marco de metros que usa el grafo activo. Se aplica a las
     * lecturas que lleguen a continuación, no a las pasadas.
     */
    fun markTruePosition(x: Double, y: Double) {
        if (!_state.value.isRecording) return
        pendingTrue = x to y
        pendingBatchTimestamp = null
        _state.update { it.copy(trueMarkCount = it.trueMarkCount + 1) }
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

    /**
     * Inserta una fila especial solo con una nota de texto libre, con la marca
     * de tiempo actual. Pensada para anotar contexto en el momento ("puerta
     * abierta", "baliza 2 caída de la pared") que se te olvida si lo dejas
     * para después.
     */
    fun addNote(note: String) {
        if (note.isBlank()) return
        log(
            BleLogRow(
                timestampS = elapsedSeconds(),
                beaconName = "_NOTE_",
                rssiRaw = null,
                rssiSmoothed = null,
                estimatedDistanceM = null,
                computedXM = null,
                computedYM = null,
                detectedNodeId = null,
                signalState = "-",
                note = note.trim()
            )
        )
    }

    fun elapsedSeconds(): Double =
        if (sessionStartElapsedMs == 0L) 0.0
        else (SystemClock.elapsedRealtime() - sessionStartElapsedMs) / 1000.0

    fun currentRows(): List<BleLogRow> = rows.toList()

    /** Escribe el CSV en la caché de la app y abre el selector de "compartir" del sistema. */
    fun exportAndShare(context: Context) {
        if (rows.isEmpty()) return
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
            rows.forEach { writer.write(toCsvLine(it)); writer.write("\n") }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Exportar registro BLE"))
    }

    private fun toCsvLine(r: BleLogRow): String {
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
            quoted(r.note)
        ).joinToString(",")
    }

    private fun defaultName(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
}
