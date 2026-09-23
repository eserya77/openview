package com.example.openview.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.openview.ble.BleScanner
import com.example.openview.data.ActiveGraph
import com.example.openview.data.RouteRepository
import com.example.openview.input.VolumeUpDispatcher
import com.example.openview.logging.BleSessionLogger
import com.example.openview.logging.MarkEvent
import com.example.openview.logging.SessionAnalyzer
import com.example.openview.logging.SessionSummary
import com.example.openview.nav.LivePosition
import com.example.openview.ui.components.PrimaryActionButton
import com.example.openview.ui.components.ScreenScaffold
import com.example.openview.ui.components.ScreenTitle
import com.example.openview.ui.components.SecondaryActionButton
import com.example.openview.ui.theme.FailureRed
import com.example.openview.ui.theme.SuccessGreen
import kotlinx.coroutines.delay

private enum class GuidedPhase { SETUP, WALK, DWELL, DONE }

private val LAG_OPTIONS = listOf(3, 5, 10)
private val DWELL_OPTIONS = listOf(6, 10, 15)
private val QUICK_NOTES = listOf("Puerta abierta", "Gente pasando", "Baliza movida", "Cambié la postura")

/**
 * Prueba guiada: recorre los puntos de la ruta activa, en orden, y graba el log con la
 * verdad de terreno resuelta SIN escribir ni tocar un mapa.
 *
 * Para cada punto: "Ve a X, párate a 1 m de la baliza" -> tocas LLEGUÉ (o Vol+) -> te quedas
 * quieto unos segundos (cuenta atrás) -> la app marca la salida sola y te lleva al siguiente.
 * Si tocaste tarde, los botones "hace 3/5/10 s" corrigen la hora de la llegada.
 * Al terminar muestra el acierto por punto y permite guardar la calibración a 1 m.
 */
@Composable
fun GuidedTestScreen(scanner: BleScanner, onStartScan: () -> Unit, onBack: () -> Unit) {
    val scan by scanner.state.collectAsState()
    val live by LivePosition.current.collectAsState()
    val catalog by RouteRepository.catalog.collectAsState()   // se recompone al guardar calibraciones
    val session by BleSessionLogger.state.collectAsState()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    val graph = ActiveGraph.graph
    val nodes = graph.nodes

    var phase by remember { mutableStateOf(GuidedPhase.SETUP) }
    var index by remember { mutableIntStateOf(0) }
    var dwellSeconds by remember { mutableIntStateOf(10) }
    var dwellEndMs by remember { mutableLongStateOf(0L) }
    var nowMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var summary by remember { mutableStateOf<SessionSummary?>(null) }
    var savedCalibration by remember { mutableStateOf(false) }
    var lastNote by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { if (!scan.isScanning) onStartScan() }

    fun finishTest() {
        BleSessionLogger.stop()
        val beaconOfNode = nodes.mapNotNull { n -> n.beaconName?.let { n.id to it } }.toMap()
        summary = SessionAnalyzer.analyze(BleSessionLogger.currentRows(), beaconOfNode)
        savedCalibration = false
        phase = GuidedPhase.DONE
    }

    fun finishDwell() {
        BleSessionLogger.markNode(MarkEvent.DEPART, nodes[index])
        if (index >= nodes.lastIndex) finishTest() else { index += 1; phase = GuidedPhase.WALK }
    }

    fun arrive(lagS: Int) {
        BleSessionLogger.markNode(MarkEvent.ARRIVE, nodes[index], lagS.toDouble())
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val remaining = (dwellSeconds - lagS).coerceAtLeast(3)
        dwellEndMs = SystemClock.elapsedRealtime() + remaining * 1000L
        phase = GuidedPhase.DWELL
    }

    // Reloj de la pantalla + fin automático de la estancia.
    LaunchedEffect(phase, dwellEndMs) {
        while (phase == GuidedPhase.WALK || phase == GuidedPhase.DWELL) {
            nowMs = SystemClock.elapsedRealtime()
            if (phase == GuidedPhase.DWELL && nowMs >= dwellEndMs) { finishDwell(); break }
            delay(200)
        }
    }

    // Vol+ = LLEGUÉ (sirve con el móvil en la mano sin mirar la pantalla).
    val onVolumeUp by rememberUpdatedState({ if (phase == GuidedPhase.WALK) arrive(0) })
    DisposableEffect(Unit) {
        val listener = { onVolumeUp() }
        VolumeUpDispatcher.register(listener)
        onDispose { VolumeUpDispatcher.unregister(listener) }
    }

    ScreenScaffold(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenTitle("Prueba guiada")

        when (phase) {
            GuidedPhase.SETUP -> SetupPhase(
                nodeLabels = nodes.map { it.label to it.beaconName },
                scanTargets = scan.targets,
                dwellSeconds = dwellSeconds,
                onDwell = { dwellSeconds = it },
                catalogVersion = catalog.beacons.hashCode(),
                canStart = nodes.size >= 2 && nodes.all { it.beaconName != null },
                onStart = {
                    index = 0
                    summary = null
                    BleSessionLogger.start("guiada", "Prueba guiada, ${nodes.size} puntos, estancia ${dwellSeconds} s")
                    phase = GuidedPhase.WALK
                },
                onBack = onBack
            )

            GuidedPhase.WALK, GuidedPhase.DWELL -> {
                val node = nodes[index]
                val elapsed = BleSessionLogger.elapsedSeconds()
                Text(
                    "Punto ${index + 1} de ${nodes.size}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "${node.label} · ${node.beaconName}",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    "Grabando · ${"%.0f".format(elapsed)} s · ${session.rowCount} muestras",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (phase == GuidedPhase.WALK) {
                    Text(
                        "Ve a este punto y párate a 1 m de la baliza, con el móvil en la mano como siempre. Toca LLEGUÉ (o Vol+) apenas te detengas.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                    Button(
                        onClick = { arrive(0) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp)
                    ) { Text("LLEGUÉ", fontSize = 34.sp, fontWeight = FontWeight.Bold) }
                    Text("¿Tocaste tarde? Llegué hace…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        LAG_OPTIONS.forEach { lag ->
                            OutlinedButton(onClick = { arrive(lag) }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("$lag s") }
                        }
                    }
                } else {
                    val remainingMs = (dwellEndMs - nowMs).coerceAtLeast(0L)
                    Text(
                        "Quédate quieto: ${(remainingMs + 999) / 1000} s",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    LinearProgressIndicator(
                        progress = { 1f - (remainingMs / (dwellSeconds * 1000f)).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(10.dp)
                    )
                    Text(
                        "Al terminar, la app marca sola la salida y te dirá cuál es el siguiente punto.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                LiveVerdict(expectedNodeId = node.id, expectedLabel = node.label, nodesById = nodes.associateBy { it.id }, live = live)

                Text("Notas rápidas (un toque)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth())
                QUICK_NOTES.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        pair.forEach { text ->
                            OutlinedButton(
                                onClick = { BleSessionLogger.addNote(text); lastNote = text },
                                modifier = Modifier.weight(1f)
                            ) { Text(text, style = MaterialTheme.typography.labelMedium) }
                        }
                    }
                }
                if (lastNote.isNotEmpty()) Text("Anotado: $lastNote", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                if (phase == GuidedPhase.WALK) {
                    SecondaryActionButton("Saltar este punto (baliza apagada)", onClick = {
                        if (index >= nodes.lastIndex) finishTest() else index += 1
                    })
                }
                SecondaryActionButton("Terminar ahora", icon = Icons.Filled.Stop, onClick = { finishTest() })
            }

            GuidedPhase.DONE -> DonePhase(
                summary = summary,
                nodeLabels = nodes.associate { it.id to it.label },
                savedCalibration = savedCalibration,
                onSaveCalibration = {
                    summary?.points?.forEach { p ->
                        val beacon = p.beaconName; val rssi = p.medianRssi
                        if (beacon != null && rssi != null) RouteRepository.setBeaconCalibration(beacon, rssi)
                    }
                    savedCalibration = true
                },
                onExport = { BleSessionLogger.exportAndShare(context) },
                onAgain = { phase = GuidedPhase.SETUP; summary = null },
                onBack = onBack
            )
        }
    }
}

@Composable
private fun SetupPhase(
    nodeLabels: List<Pair<String, String?>>,
    scanTargets: List<com.example.openview.ble.BleDeviceInfo>,
    dwellSeconds: Int,
    onDwell: (Int) -> Unit,
    catalogVersion: Int,
    canStart: Boolean,
    onStart: () -> Unit,
    onBack: () -> Unit
) {
    @Suppress("UNUSED_VARIABLE") val keepRecomposing = catalogVersion
    Text(
        "La app te lleva punto por punto y graba la posición real sin que escribas nada. Se usa el orden de la ruta activa.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    val byName = scanTargets.filter { it.name != null }.associateBy { it.name!! }
    val now = SystemClock.elapsedRealtime()
    val visibleDevices = nodeLabels.mapNotNull { (_, beacon) -> beacon?.let { byName[it] } }
    val fwVersions = visibleDevices.mapNotNull { it.fwVersion }.toSet()
    val txLevels = visibleDevices.mapNotNull { it.txLevel }.toSet()

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Puntos de la prueba", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            nodeLabels.forEachIndexed { i, (label, beacon) ->
                val dev = beacon?.let { byName[it] }
                val visible = dev != null && now - dev.lastSeenElapsedMs <= 5_000L
                val calibrated = beacon?.let { RouteRepository.isCalibrated(it) } == true
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (visible) Icons.Filled.CheckCircle else Icons.Filled.Warning, null,
                        tint = if (visible) SuccessGreen else FailureRed
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${i + 1}. $label · $beacon", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        val detail = buildString {
                            append(if (visible) "Detectada · ${dev?.rssi} dBm" else "No detectada todavía")
                            if (dev?.fwVersion != null) append(" · fw v${dev.fwVersion}")
                            if (dev?.txLevel != null) append(" · TX ${-12 + 3 * dev.txLevel} dBm")
                            append(if (calibrated) " · calibrada" else " · SIN calibrar")
                        }
                        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (fwVersions.size > 1 || txLevels.size > 1) {
                Text(
                    "Aviso: las balizas anuncian versiones de firmware o niveles de potencia distintos. Reflashea todas con el mismo .ino antes de comparar.",
                    color = FailureRed, style = MaterialTheme.typography.bodyMedium
                )
            }
            val uncalibrated = nodeLabels.count { (_, b) -> b != null && !RouteRepository.isCalibrated(b) }
            if (uncalibrated > 0) {
                Text(
                    "$uncalibrated baliza(s) sin calibrar: sin referencia por baliza, la más \"ruidosa\" puede ganar aunque estés en otra. Haz esta prueba y al final toca \"Guardar calibración\"; luego repítela para validar.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    Text("Tiempo quieto en cada punto", style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        DWELL_OPTIONS.forEach { s ->
            if (s == dwellSeconds) Button(onClick = { onDwell(s) }, modifier = Modifier.weight(1f)) { Text("$s s") }
            else OutlinedButton(onClick = { onDwell(s) }, modifier = Modifier.weight(1f)) { Text("$s s") }
        }
    }

    PrimaryActionButton("Empezar prueba", icon = Icons.Filled.FiberManualRecord, onClick = onStart)
    if (!canStart) Text("Necesitas una ruta activa con al menos 2 puntos, cada uno con su baliza.", color = FailureRed, style = MaterialTheme.typography.bodyMedium)
    SecondaryActionButton("Volver", icon = Icons.AutoMirrored.Filled.ArrowBack, onClick = onBack)
}

/** Lo que dice la app AHORA frente a lo que debería decir, más el RSSI relativo de cada baliza. */
@Composable
private fun LiveVerdict(
    expectedNodeId: String,
    expectedLabel: String,
    nodesById: Map<String, com.example.openview.data.NavNode>,
    live: com.example.openview.nav.BlePositioner.Result?
) {
    val detected = live?.nodeId
    val ok = detected == expectedNodeId
    val color = if (ok) SuccessGreen else FailureRed
    Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = .12f)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                when {
                    detected == null -> "La app todavía no detecta ningún punto"
                    ok -> "✓ La app dice: $expectedLabel (estable ${"%.1f".format((live?.stableMs ?: 0L) / 1000.0)} s)"
                    else -> "✗ La app dice: ${nodesById[detected]?.label ?: detected} (debería ser $expectedLabel)"
                },
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color
            )
            live?.readings?.sortedByDescending { it.relDb }?.forEach { r ->
                val calibrated = RouteRepository.isCalibrated(r.beaconName)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(r.beaconName + if (r.nodeId == expectedNodeId) "  ◀" else "", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${"%.0f".format(r.rssiSmoothed)} dBm · ${"%+.0f".format(r.relDb)} dB" + if (calibrated) "" else " (sin calibrar)",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DonePhase(
    summary: SessionSummary?,
    nodeLabels: Map<String, String>,
    savedCalibration: Boolean,
    onSaveCalibration: () -> Unit,
    onExport: () -> Unit,
    onAgain: () -> Unit,
    onBack: () -> Unit
) {
    if (summary == null || summary.points.isEmpty()) {
        Text("No hubo llegadas marcadas: no hay nada que evaluar.", style = MaterialTheme.typography.bodyLarge)
    } else {
        val good = summary.meanAccuracyPct >= 95 && !summary.falseArrival
        Card(
            colors = CardDefaults.cardColors(containerColor = (if (good) SuccessGreen else FailureRed).copy(alpha = .12f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Acierto medio: ${"%.0f".format(summary.meanAccuracyPct)} %",
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold
                )
                Text("Cambios de nodo estando quieto: ${summary.totalFlips}", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (summary.falseArrival)
                        "⚠ Falsa llegada: la app decía \"destino\" ${"%.1f".format(summary.wrongDestinationS)} s mientras estabas en otro punto."
                    else "Sin falsa llegada al destino.",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
        summary.points.forEach { p ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "${nodeLabels[p.nodeId] ?: p.nodeId} · ${p.beaconName ?: "?"}: ${"%.0f".format(p.accuracyPct)} %",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        color = if (p.accuracyPct >= 95) SuccessGreen else FailureRed
                    )
                    Text(
                        "Tardó ${p.latencyS?.let { "%.1f".format(it) + " s" } ?: "— (nunca)"} en detectarlo · ${p.flips} cambios · RSSI mediano ${p.medianRssi ?: "—"} dBm" +
                            (p.wrongNode?.let { " · lo confundió con ${nodeLabels[it] ?: it}" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Text(
            "Si estuviste a ~1 m de cada baliza, el RSSI mediano de cada punto sirve como calibración a 1 m. Guárdala y REPITE la prueba: esa segunda vuelta sí valida el resultado.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (savedCalibration) Text("✓ Calibración guardada en este teléfono.", color = SuccessGreen, fontWeight = FontWeight.Bold)
        else PrimaryActionButton("Guardar calibración con esta prueba", icon = Icons.Filled.Tune, onClick = onSaveCalibration)
    }
    SecondaryActionButton("Exportar CSV", icon = Icons.Filled.Share, onClick = onExport)
    SecondaryActionButton("Nueva prueba", onClick = onAgain)
    SecondaryActionButton("Volver", icon = Icons.AutoMirrored.Filled.ArrowBack, onClick = onBack)
    Spacer(Modifier.padding(4.dp))
}
