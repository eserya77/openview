package com.example.openview.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.openview.ble.BleScanner
import com.example.openview.data.ActiveFingerprints
import com.example.openview.data.ActiveGraph
import com.example.openview.data.RouteRepository
import com.example.openview.data.Rotation
import com.example.openview.data.TestLayout
import com.example.openview.data.TestSetupRepository
import com.example.openview.guide.Haptics
import com.example.openview.guide.MotionDetector
import com.example.openview.guide.SonarPlayer
import com.example.openview.input.VolumeUpDispatcher
import com.example.openview.logging.BleSessionLogger
import com.example.openview.logging.MarkEvent
import com.example.openview.logging.PassAnalyzer
import com.example.openview.logging.PassSummary
import com.example.openview.nav.GuideState
import com.example.openview.guide.GuideFeedbackOptions
import com.example.openview.guide.GuideSession
import kotlinx.coroutines.flow.MutableStateFlow
import com.example.openview.nav.GuideEvent
import com.example.openview.nav.GuideEventKind
import com.example.openview.nav.GuideSpot
import com.example.openview.nav.Zone
import com.example.openview.tts.rememberTtsController
import com.example.openview.ui.components.PrimaryActionButton
import com.example.openview.ui.components.ScreenScaffold
import com.example.openview.ui.components.ScreenTitle
import com.example.openview.ui.components.SecondaryActionButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

private enum class GuidePhase { SETUP, RUN, DONE }

private fun zoneColor(z: Zone) = when (z) {
    Zone.TOCANDO -> AdminColors.good
    Zone.CERCA -> AdminColors.goodFill
    Zone.SALA -> AdminColors.warn
    Zone.LEJOS -> AdminColors.bad
}

private fun tempWord(t: Double) = when {
    t >= 0.85 -> "Tocando"
    t >= 0.6 -> "Caliente"
    t >= 0.35 -> "Templado"
    else -> "Frío"
}

/**
 * Guía de usuario en modo prueba: lo MISMO que oirá y sentirá la persona ciega (voz corta, pitidos «frío/caliente»,
 * vibraciones, llegada automática, Vol+ = repetir), con un panel detallado debajo para quien prueba: zonas y pendiente
 * de cada baliza, movimiento, filtro del escaneo, registro de eventos y grabación del CSV con cada decisión.
 */
@Composable
fun UserGuideScreen(scanner: BleScanner, statusBar: @Composable () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val scan by scanner.state.collectAsState()
    val catalog by RouteRepository.catalog.collectAsState()
    val setups by TestSetupRepository.state.collectAsState()
    val recording by BleSessionLogger.state.collectAsState()
    val tts = rememberTtsController()
    val motion = remember { MotionDetector(context.applicationContext) }
    val haptics = remember { Haptics(context.applicationContext) }
    val sonar = remember { SonarPlayer() }

    // Pantalla siempre encendida mientras la guía está abierta (el escaneo sin pantalla necesitará un servicio: pendiente).
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        motion.start()
        onDispose {
            view.keepScreenOn = false; motion.stop(); sonar.release(); tts.stop()
            ActiveGraph.useTemporary(null); ActiveFingerprints.key = null
            // Si se sale a mitad de la guía, se cierra la grabación (el CSV sigue en «Replay → Última grabación»).
            if (BleSessionLogger.state.value.isRecording) BleSessionLogger.stop()
        }
    }

    // ---------- Configuración ----------
    val setup = setups.setups.firstOrNull { it.id == setups.selectedId } ?: setups.setups.first()
    val allBeacons = catalog.beacons.filter { it.enabled }.map { it.name }.sorted()
    val beaconAt = remember(setup.id) {
        mutableStateListOf<String>().apply {
            val remembered = TestSetupRepository.assignmentFor(setup.id)?.takeIf { it.size == setup.spots.size }
            addAll(remembered ?: (allBeacons + List(setup.spots.size) { "NAV_BLE_%02d".format(it + 1) }).distinct().take(setup.spots.size))
        }
    }
    var reverse by remember { mutableStateOf(false) }
    var destIdx by remember(setup.id) { mutableIntStateOf(setup.spots.lastIndex) }
    var voiceOn by remember { mutableStateOf(true) }
    var sonarOn by remember { mutableStateOf(true) }
    var vibrateOn by remember { mutableStateOf(true) }
    var verbose by remember { mutableStateOf(true) }
    var logState by remember { mutableStateOf(true) }

    val ordered = setup.spots.indices.let { if (reverse) it.reversed() else it.toList() }
    val destPos = ordered.indexOf(destIdx).coerceAtLeast(1)
    val routeIdx = ordered.take(destPos + 1)
    val route = routeIdx.map { i -> setup.spots[i].let { GuideSpot(it.id, it.label, beaconAt[i], it.x, it.y) } }

    // ---------- Ejecución (misma sesión que el botón «Iniciar» del producto) ----------
    var phase by remember { mutableStateOf(GuidePhase.SETUP) }
    var session by remember { mutableStateOf<GuideSession?>(null) }
    var runRoute by remember { mutableStateOf(listOf<GuideSpot>()) }
    var truthIdx by remember { mutableIntStateOf(0) }
    var passSummary by remember { mutableStateOf<PassSummary?>(null) }
    var startMs by remember { mutableLongStateOf(0L) }
    var lastStateLog by remember { mutableLongStateOf(0L) }

    fun start() {
        val rot = Rotation("GUIA", "Guía", beaconAt.toList())
        TestSetupRepository.rememberAssignment(setup.id, beaconAt.toList())
        val g = TestLayout.graph(setup, rot)
        ActiveGraph.useTemporary(g, route.map { it.id }); ActiveFingerprints.key = null
        ActiveGraph.resetToken += 1
        runRoute = route; truthIdx = 0; passSummary = null
        startMs = SystemClock.elapsedRealtime()
        BleSessionLogger.start("guia_${setup.id}_${if (reverse) "vuelta" else "ida"}", "Guía de usuario (modo prueba), ${route.size} puntos")
        BleSessionLogger.addLayout(
            "setup=${setup.id};guide=1;dir=${if (reverse) "vuelta" else "ida"};dest=${route.last().id};" +
                route.joinToString(";") { "${it.id}=${it.beacon}" } + ";filtered=${scan.filtered};motion=${motion.available}"
        )
        session = GuideSession(
            route, tts, sonar, haptics, motion, GuideFeedbackOptions(voiceOn, sonarOn, vibrateOn),
            onEvent = { ev -> BleSessionLogger.addNote("GUIA ${ev.kind}: ${ev.text}") },
            onTick = { st, now ->
                if (logState && now - lastStateLog >= 1_000L) {
                    lastStateLog = now
                    BleSessionLogger.addNote(
                        "GUIA_ESTADO tgt=${route.getOrNull(st.target)?.id};prog=${st.progress};at=${st.atSpot};temp=%.2f;mov=${st.moving};rate=%.1f;".format(Locale.US, st.targetTemperature, scanner.state.value.targetRatePerS) +
                            st.tracks.joinToString(";") { "${it.beacon.takeLast(2)}:%.1f/%+.1f/${it.zone.name}".format(Locale.US, it.level, it.slope) }
                    )
                }
            }
        )
        phase = GuidePhase.RUN
    }

    fun finish() {
        session = null
        BleSessionLogger.stop()
        val rows = BleSessionLogger.currentRows()
        passSummary = if (truthIdx > 0) PassAnalyzer.analyze(rows, runRoute.associate { it.id to it.beacon }) else null
        phase = GuidePhase.DONE
    }

    LaunchedEffect(session) { session?.run(scanner) }
    LaunchedEffect(voiceOn, sonarOn, vibrateOn) { session?.options = GuideFeedbackOptions(voiceOn, sonarOn, vibrateOn) }
    val emptyState = remember { MutableStateFlow(GuideState()) }
    val emptyEvents = remember { MutableStateFlow<List<GuideEvent>>(emptyList()) }
    val guideState by (session?.state ?: emptyState).collectAsState()
    val events by (session?.events ?: emptyEvents).collectAsState()
    var keptEvents by remember { mutableStateOf<List<GuideEvent>>(emptyList()) }
    LaunchedEffect(events) { if (events.isNotEmpty()) keptEvents = events }
    val log = if (phase == GuidePhase.DONE) keptEvents else events

    // Vol+ = repetir la última instrucción (como en el producto final).
    val onVol by rememberUpdatedState({ session?.repeatInstruction() ?: Unit })
    DisposableEffect(Unit) {
        val l = { onVol() }
        VolumeUpDispatcher.register(l)
        onDispose { VolumeUpDispatcher.unregister(l) }
    }

    ScreenScaffold(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenTitle("Guía de usuario")
        when (phase) {
            GuidePhase.SETUP -> {
                statusBar()
                Text(
                    "Así la usará una persona ciega: sin tocar la pantalla, con voz corta, pitidos que se aceleran al acercarte, vibración en cada punto y llegada automática. Debajo verás todo el detalle técnico.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AdminCard {
                    SectionHeader("Escenario", 1)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        setups.setups.forEach { s ->
                            val on = s.id == setup.id
                            Box(
                                Modifier.height(44.dp).clip(RoundedCornerShape(14.dp))
                                    .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { TestSetupRepository.select(s.id) }.padding(horizontal = 14.dp),
                                contentAlignment = Alignment.Center
                            ) { Text(s.name, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
                        }
                    }
                    Text("Toca una posición para cambiar su baliza.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    setup.spots.forEachIndexed { i, sp ->
                        val b = beaconAt[i]
                        val seen = scan.targets.any { it.name == b }
                        Row(
                            Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    val pool = (allBeacons + beaconAt).distinct().sorted()
                                    val next = pool[(pool.indexOf(b) + 1) % pool.size]
                                    val other = beaconAt.indexOf(next)
                                    if (other >= 0) beaconAt[other] = b
                                    beaconAt[i] = next
                                }.padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BeaconNumberBadge(b, if (seen) AdminColors.good else MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("${sp.id} · ${sp.label}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text("x %.2f m · y %.2f m".format(sp.x, sp.y), style = NumberStyle.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            StatusPill(if (seen) "detectada" else "no se oye", if (seen) AdminColors.good else AdminColors.bad)
                        }
                    }
                }
                AdminCard {
                    SectionHeader("Recorrido", 2)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        listOf(false to "Ida", true to "Vuelta").forEach { (rv, label) ->
                            if (rv == reverse) Button(onClick = {}, modifier = Modifier.weight(1f).height(48.dp)) { Text(label) }
                            else OutlinedButton(onClick = { reverse = rv; destIdx = if (rv) 0 else setup.spots.lastIndex }, modifier = Modifier.weight(1f).height(48.dp)) { Text(label) }
                        }
                    }
                    Text("Destino", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ordered.drop(1).forEach { i ->
                            val on = i == destIdx
                            Box(
                                Modifier.height(44.dp).clip(RoundedCornerShape(14.dp))
                                    .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { destIdx = i }.padding(horizontal = 14.dp),
                                contentAlignment = Alignment.Center
                            ) { Text(setup.spots[i].label, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, maxLines = 1) }
                        }
                    }
                    Text("Ruta: " + route.joinToString(" → ") { "${it.label} (${it.beacon.takeLast(2)})" }, style = MaterialTheme.typography.bodyMedium)
                    val staleB = route.map { it.beacon }.filter { RouteRepository.isStale(it) }
                    val uncalB = route.map { it.beacon }.filter { !RouteRepository.isCalibrated(it) && it !in staleB }
                    SlotText(
                        buildString {
                            if (staleB.isNotEmpty()) append("Recalibra ${staleB.joinToString { it.takeLast(2) }}: su calibración es de otra potencia y se ignora. ")
                            if (uncalB.isNotEmpty()) append("Sin calibrar: ${uncalB.joinToString { it.takeLast(2) }} (se usa −80 dBm a 1 m). ")
                        }.ifBlank { null },
                        minLines = 2, color = AdminColors.bad, style = MaterialTheme.typography.bodySmall
                    )
                }
                AdminCard {
                    SectionHeader("Qué recibe la persona", 3)
                    GuideToggle("Voz", "Frases cortas: cada punto, correcciones y la llegada. Vol+ repite.", voiceOn) { voiceOn = it }
                    GuideToggle("Pitidos frío / caliente", "Más rápidos y agudos al acercarte al siguiente punto.", sonarOn) { sonarOn = it }
                    GuideToggle("Vibración", "Corta = punto; triple = llegaste; larga = corrige.", vibrateOn) { vibrateOn = it }
                }
                AdminCard {
                    SectionHeader("Para quien prueba", 4)
                    GuideToggle("Panel detallado (verbose)", "Zonas, pendientes, movimiento y registro de eventos en vivo.", verbose) { verbose = it }
                    GuideToggle("Estado del motor en el CSV cada 1 s", "Nivel/pendiente/zona de cada baliza como nota GUIA_ESTADO.", logState) { logState = it }
                    Text(
                        "Sensor de movimiento: " + if (motion.available) "sí (la llegada espera a que te detengas)" else "no hay: la llegada usa solo el tiempo junto al punto",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                PrimaryActionButton("Empezar guía", icon = Icons.Filled.PlayArrow, onClick = { if (route.size >= 1) start() })
                SecondaryActionButton("Volver", icon = Icons.AutoMirrored.Filled.ArrowBack, onClick = onBack)
            }

            GuidePhase.RUN -> {
                val st: GuideState? = guideState
                val nowTick = SystemClock.elapsedRealtime()
                val tgt = st?.let { runRoute.getOrNull(it.target) }
                // ---- Lo que ve/oye la persona usuaria ----
                AdminCard(container = if (st?.arrived == true) AdminColors.goodFill.copy(alpha = .16f) else MaterialTheme.colorScheme.primaryContainer, border = null, padding = 20.dp) {
                    Text(
                        if (st?.arrived == true) "Llegaste" else tgt?.let { "Hacia ${it.label}" } ?: "Buscando balizas…",
                        style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    SlotText(
                        st?.instruction?.takeIf { it.isNotBlank() } ?: "Camina despacio. La app te avisará en cada punto.",
                        minLines = 3, maxLines = 3, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }
                    )
                    TemperatureBar(st?.targetTemperature ?: 0.0, st?.targetSlope ?: 0.0, heard = st?.tracks?.any { it.beacon == tgt?.beacon } == true)
                    RouteProgress(runRoute, st?.progress ?: -1, st?.target ?: 0, st?.atSpot)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { session?.repeatInstruction() }, modifier = Modifier.weight(1f).height(64.dp)) {
                        androidx.compose.material3.Icon(Icons.Filled.Replay, null); Spacer(Modifier.width(8.dp)); Text("Repetir", fontSize = 18.sp)
                    }
                    OutlinedButton(onClick = { finish() }, modifier = Modifier.weight(1f).height(64.dp)) {
                        androidx.compose.material3.Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(8.dp)); Text("Terminar", fontSize = 18.sp)
                    }
                }
                Text("Vol+ también repite la instrucción.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                // ---- Panel detallado ----
                if (verbose && st != null) {
                    AdminCard {
                        SectionHeader("Detalle técnico", subtitle = "Lo que la persona usuaria no ve.")
                        StatRow("Tiempo", "%.1f s".format((nowTick - startMs) / 1000.0))
                        StatRow("Escaneo", (if (scan.filtered) "con filtro" else "SIN filtro (respaldo)") + " · reinicios ${scan.restarts}")
                        StatRow("Muestras/s (todas)", "%.1f".format(scan.targetRatePerS) + if (scan.targetRatePerS < 3.0) "  ⚠ pocas" else "",
                            valueColor = if (scan.targetRatePerS < 3.0) AdminColors.bad else MaterialTheme.colorScheme.onSurface)
                        StatRow("Movimiento", when (st.moving) { true -> "caminando"; false -> "quieto"; null -> "desconocido" } + " · σ %.2f".format(motion.stdDev))
                        StatRow("Progreso / objetivo", "${runRoute.getOrNull(st.progress)?.id ?: "—"} → ${tgt?.id ?: "—"}")
                        StatRow("Junto a", st.atSpot ?: "ninguno")
                        StatRow("Grabando", "${recording.rowCount} filas")
                    }
                    AdminCard {
                        SectionHeader("Balizas", subtitle = "nivel (Kalman) · pendiente · nivel − ref. 1 m · zona")
                        runRoute.forEach { sp ->
                            val t = st.tracks.firstOrNull { it.beacon == sp.beacon }
                            val stale = RouteRepository.isStale(sp.beacon)
                            Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                                BeaconNumberBadge(sp.beacon, t?.let { zoneColor(it.zone) } ?: MaterialTheme.colorScheme.onSurfaceVariant, 32.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(t?.let { "%5.1f".format(it.level) } ?: "  —  ", style = NumberStyle.copy(fontSize = 13.sp), modifier = Modifier.width(52.dp), maxLines = 1, softWrap = false)
                                Text(t?.let { (if (it.slope >= 0) "↑" else "↓") + "%4.1f".format(kotlin.math.abs(it.slope)) } ?: "  —  ", style = NumberStyle.copy(fontSize = 13.sp), color = t?.let { if (it.slope >= 0) AdminColors.good else AdminColors.bad } ?: MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(56.dp), maxLines = 1, softWrap = false)
                                Text(t?.let { "%+5.1f".format(it.relDb) } ?: "  —  ", style = NumberStyle.copy(fontSize = 13.sp), modifier = Modifier.width(52.dp), maxLines = 1, softWrap = false)
                                Spacer(Modifier.weight(1f))
                                Text(t?.let { "%.0f/s".format(it.samplesPerS) } ?: "", style = NumberStyle.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(40.dp), maxLines = 1, softWrap = false)
                                StatusPill(if (stale) "recalibrar" else t?.zone?.label ?: "no se oye", if (stale) AdminColors.bad else t?.let { zoneColor(it.zone) } ?: AdminColors.bad, modifier = Modifier.width(96.dp))
                            }
                        }
                        Text(
                            "Umbrales: CERCA ≥ ref+2 dB (≈1 m); TOCANDO ≥ contacto−8 dB. Ref. = calibración o −80 dBm; contacto = perfil vigente o ref+25.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    AdminCard {
                        SectionHeader("Verdad de terreno", subtitle = "Pulsa al pasar junto a cada baliza, en orden. Sirve para medir el retraso.")
                        val next = runRoute.getOrNull(truthIdx)
                        OutlinedButton(
                            onClick = {
                                next?.let { sp ->
                                    val node = com.example.openview.data.NavNode(sp.id, sp.label, sp.x, sp.y, sp.beacon)
                                    BleSessionLogger.markNode(MarkEvent.ARRIVE, node); BleSessionLogger.markNode(MarkEvent.DEPART, node)
                                    truthIdx += 1
                                }
                            },
                            enabled = next != null, modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) { androidx.compose.material3.Icon(Icons.Filled.Flag, null); Spacer(Modifier.width(8.dp)); Text(next?.let { "Ahora paso junto a ${it.label}" } ?: "Todas marcadas", maxLines = 1) }
                    }
                    AdminCard {
                        SectionHeader("Registro de eventos", subtitle = "Más reciente arriba. También va al CSV como notas «GUIA …».")
                        Column(Modifier.fillMaxWidth().height(240.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            log.reversed().forEach { ev ->
                                Row {
                                    Text("%6.1f".format((ev.tMs - startMs) / 1000.0), style = NumberStyle.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(52.dp))
                                    Text(ev.kind.name, style = NumberStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold), color = eventColor(ev.kind), modifier = Modifier.width(84.dp), maxLines = 1)
                                    Text(ev.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    TextButton(onClick = { BleSessionLogger.exportAndShare(context) }) { androidx.compose.material3.Icon(Icons.Filled.Share, null); Spacer(Modifier.width(6.dp)); Text("Exportar CSV sin parar") }
                }
            }

            GuidePhase.DONE -> {
                AdminCard(container = AdminColors.goodFill.copy(alpha = .12f), border = null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Icon(Icons.Filled.CheckCircle, null, tint = AdminColors.good, modifier = Modifier.size(30.dp)); Spacer(Modifier.width(10.dp))
                        Text("Guía terminada", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    Text("${log.count { it.kind == GuideEventKind.PASSED || it.kind == GuideEventKind.AT_SPOT }} puntos anunciados · " +
                        (if (log.any { it.kind == GuideEventKind.ARRIVED }) "llegada anunciada" else "SIN llegada") +
                        " · ${log.count { it.kind == GuideEventKind.WRONG_WAY }} «vas hacia atrás» · ${log.count { it.kind == GuideEventKind.COLDER }} «te alejas»",
                        style = MaterialTheme.typography.bodyLarge)
                }
                passSummary?.let { ps ->
                    AdminCard {
                        SectionHeader("Contra tus marcas de verdad")
                        StatRow("Detectados al pasar", "${ps.detected} de ${ps.points.size}")
                        StatRow("Orden", if (ps.orderOk) "correcto" else ps.appSequence.joinToString(" → "))
                        ps.points.forEach { p -> StatRow("${p.nodeId} (${p.beaconName?.takeLast(2)})", (p.detectLatencyS?.let { "app %+.1f s".format(it) } ?: "app: no") + (p.peakOffsetS?.let { " · pico %+.1f s".format(it) } ?: "")) }
                    }
                }
                AdminCard {
                    SectionHeader("Eventos")
                    log.forEach { ev -> Text("%6.1f  ${ev.kind.name}  ${ev.text}".format((ev.tMs - startMs) / 1000.0), style = NumberStyle.copy(fontSize = 12.sp)) }
                }
                SecondaryActionButton("Exportar CSV", icon = Icons.Filled.Share, onClick = { BleSessionLogger.exportAndShare(context) })
                PrimaryActionButton("Otra vez", icon = Icons.Filled.PlayArrow, onClick = { phase = GuidePhase.SETUP })
                SecondaryActionButton("Volver", icon = Icons.AutoMirrored.Filled.ArrowBack, onClick = onBack)
            }
        }
    }
}

@Composable
private fun eventColor(k: GuideEventKind) = when (k) {
    GuideEventKind.ARRIVED, GuideEventKind.PASSED, GuideEventKind.AT_SPOT -> AdminColors.good
    GuideEventKind.WRONG_WAY, GuideEventKind.LOST -> AdminColors.bad
    GuideEventKind.COLDER -> AdminColors.warn
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun GuideToggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Barra grande de «temperatura» hacia el siguiente punto, con la palabra y la tendencia. Alto fijo. */
@Composable
private fun TemperatureBar(temp: Double, slope: Double, heard: Boolean) {
    val track = MaterialTheme.colorScheme.surface
    val fill = when { temp >= 0.85 -> AdminColors.good; temp >= 0.6 -> AdminColors.goodFill; temp >= 0.35 -> AdminColors.warn; else -> AdminColors.bad }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (heard) tempWord(temp) else "Sin señal del objetivo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
            Text(if (!heard) "" else if (slope > 0.8) "te acercas ↑" else if (slope < -0.8) "te alejas ↓" else "estable", style = MaterialTheme.typography.titleSmall, maxLines = 1)
        }
        Canvas(Modifier.fillMaxWidth().height(22.dp)) {
            drawRoundRect(track, cornerRadius = CornerRadius(11.dp.toPx()), size = Size(size.width, size.height))
            if (heard) drawRoundRect(fill, cornerRadius = CornerRadius(11.dp.toPx()), size = Size((size.width * temp.toFloat()).coerceAtLeast(size.height), size.height))
        }
    }
}

/** Puntos de la ruta: hechos (verde), el actual (junto a él), el objetivo (resaltado) y los pendientes. */
@Composable
private fun RouteProgress(route: List<GuideSpot>, progress: Int, target: Int, atSpot: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        route.forEachIndexed { i, s ->
            val done = i <= progress
            val isTarget = i == target && !done
            val bg = when { s.id == atSpot -> AdminColors.good; done -> AdminColors.goodFill.copy(alpha = .35f); isTarget -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.surface }
            val fg = when { s.id == atSpot -> androidx.compose.ui.graphics.Color.White; isTarget -> MaterialTheme.colorScheme.onPrimary; else -> MaterialTheme.colorScheme.onSurface }
            Column(
                Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(12.dp)).background(bg),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
            ) {
                Text(s.id, fontWeight = FontWeight.Bold, color = fg, maxLines = 1)
                Text(s.beacon.takeLast(2), style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1, textAlign = TextAlign.Center)
            }
        }
    }
}
