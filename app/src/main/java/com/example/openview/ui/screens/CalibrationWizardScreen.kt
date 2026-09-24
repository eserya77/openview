package com.example.openview.ui.screens

import android.os.Build
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.openview.ble.BleScanner
import com.example.openview.calibration.BeaconProfile
import com.example.openview.calibration.CalibrationScenario
import com.example.openview.calibration.CalibrationScenarios
import com.example.openview.calibration.ProfileExport
import com.example.openview.calibration.ProfileFitter
import com.example.openview.calibration.Quality
import com.example.openview.calibration.ScenarioKind
import com.example.openview.calibration.ScenarioResult
import com.example.openview.calibration.StabilityDetector
import com.example.openview.data.RouteRepository
import com.example.openview.ui.components.PrimaryActionButton
import com.example.openview.ui.components.ScreenScaffold
import com.example.openview.ui.components.ScreenTitle
import com.example.openview.ui.components.SecondaryActionButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class WizardPhase { SETUP, PEAK, POSITION, MEASURING, BEACON_DONE, ALL_DONE }

private val DISTANCE_OPTIONS = listOf(2.0, 3.0, 4.0, 6.0)
private const val AUTO_MIN_STEP_S = 3.0
private const val VISIBLE_WINDOW_MS = 8_000L
private const val NO_SIGNAL_MS = 3_000L

private fun qualityColor(q: Quality) = when (q) {
    Quality.A -> AdminColors.good
    Quality.B -> AdminColors.warn
    Quality.C -> AdminColors.bad
}

private fun shortLabel(sc: CalibrationScenario): String = when (sc.kind) {
    ScenarioKind.CONTACT -> "Toque"
    ScenarioKind.FRONT -> if (sc.distanceM < 1.0) "${(sc.distanceM * 100).toInt()} cm" else "${"%.0f".format(sc.distanceM)} m"
    ScenarioKind.ANGLE -> "${sc.angleDeg}°"
    ScenarioKind.BODY -> "Cuerpo"
}

/** Qué hacer en cada paso, en frases cortas y sin ambigüedad (lo que muestra además el mini mapa). */
private fun howTo(sc: CalibrationScenario): List<String> = when (sc.kind) {
    ScenarioKind.CONTACT -> listOf(
        "Apoya el móvil sobre la baliza, tocándola.",
        "Ponlo en el extremo de la antena: el lado contrario al cable USB.",
        "Siempre el mismo lado del móvil. Quédate quieto mientras mide."
    )
    ScenarioKind.FRONT -> {
        val d = if (sc.distanceM < 1.0) "${(sc.distanceM * 100).toInt()} cm" else "${"%.0f".format(sc.distanceM)} m"
        listOf(
            "Estira la cinta desde la baliza en línea recta, saliendo por el lado de la antena.",
            "Camina hasta la marca de $d, con el móvil en la mano como cuando caminas.",
            "Quédate quieto mientras mide."
        )
    }
    ScenarioKind.ANGLE -> {
        val turn = when (sc.angleDeg) { 90 -> "un cuarto de vuelta"; 180 -> "media vuelta"; else -> "tres cuartos de vuelta" }
        listOf(
            "No te des la vuelta sobre ti: la baliza se queda quieta y eres tú quien camina alrededor de ella.",
            "Mantén la cinta tensa a 1 m y avanza en arco $turn (${sc.angleDeg}°). En el mapa, la marca resaltada.",
            "Lleva el móvil apuntando hacia la baliza y quédate quieto mientras mide."
        )
    }
    ScenarioKind.BODY -> listOf(
        "Ponte a 1 m de la baliza, en línea recta.",
        "Da media vuelta: tu espalda hacia la baliza, tu cuerpo entre ella y el móvil.",
        "Móvil delante del pecho. Quédate quieto mientras mide."
    )
}

/**
 * Asistente de calibración por baliza. Busca las balizas solo (el panel de administración mantiene el escaneo vivo);
 * para cada baliza elegida te lleva por una lista de escenarios (contacto, distancias en línea recta hasta el máximo que
 * tengas, ángulos a 1 m), muestra en un mini mapa animado qué hacer, mide con las muestras reales de esa baliza
 * y al terminar calcula y guarda su perfil.
 */
@Composable
fun CalibrationWizardScreen(scanner: BleScanner, statusBar: @Composable () -> Unit, onBack: () -> Unit) {
    val scan by scanner.state.collectAsState()
    val catalog by RouteRepository.catalog.collectAsState()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var phase by remember { mutableStateOf(WizardPhase.SETUP) }
    val chosen = remember { mutableStateListOf<String>() }
    val seenOnce = remember { mutableSetOf<String>() }
    var maxDistance by remember { mutableStateOf(6.0) }
    var includeAngles by remember { mutableStateOf(true) }
    var includeBody by remember { mutableStateOf(false) }
    var autoStart by remember { mutableStateOf(true) }

    // Estado de la ejecución
    var queue by remember { mutableStateOf(listOf<String>()) }
    var bi by remember { mutableIntStateOf(0) }
    var scenarios by remember { mutableStateOf(listOf<CalibrationScenario>()) }
    var si by remember { mutableIntStateOf(0) }
    val results = remember { mutableStateListOf<ScenarioResult>() }
    val done = remember { mutableStateListOf<BeaconProfile>() }
    var current by remember { mutableStateOf<BeaconProfile?>(null) }
    var recent by remember { mutableStateOf(listOf<Int>()) }
    var stepStartMs by remember { mutableLongStateOf(0L) }
    var secondsLeft by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var peakBeacon by remember { mutableStateOf<String?>(null) }
    var peak by remember { mutableIntStateOf(-127) }
    var lastSampleMs by remember { mutableLongStateOf(0L) }
    var nowMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var sampleCount by remember { mutableIntStateOf(0) }
    val buffer = remember { mutableListOf<Int>() }

    // Reloj de 0,5 s: la pantalla no depende de que lleguen anuncios para refrescar "detectada / sin señal".
    LaunchedEffect(Unit) { while (true) { nowMs = SystemClock.elapsedRealtime(); delay(500) } }

    val byName = scan.targets.filter { it.name != null }.associateBy { it.name!! }
    val enabledBeacons = catalog.beacons.filter { it.enabled }.sortedBy { it.name }
    val visibleNames = enabledBeacons.map { it.name }.filter { n -> byName[n]?.let { nowMs - it.lastSeenElapsedMs <= VISIBLE_WINDOW_MS } == true }

    // Detección automática: cada baliza que aparece por primera vez queda elegida (se puede desmarcar).
    LaunchedEffect(visibleNames) {
        if (phase != WizardPhase.SETUP) return@LaunchedEffect
        visibleNames.forEach { n -> if (seenOnce.add(n) && n !in chosen) chosen.add(n) }
    }

    val targetBeacon: String? = when (phase) {
        WizardPhase.POSITION, WizardPhase.MEASURING -> queue.getOrNull(bi)
        WizardPhase.PEAK -> peakBeacon
        else -> null
    }
    val phaseNow by rememberUpdatedState(phase)

    // Recoge las muestras de la baliza objetivo: ventana reciente (para "estable") y, si se mide, el buffer.
    LaunchedEffect(targetBeacon) {
        val beacon = targetBeacon ?: return@LaunchedEffect
        scanner.samples.collect { s ->
            if (s.name != beacon) return@collect
            lastSampleMs = SystemClock.elapsedRealtime()
            recent = (recent + s.rssi).takeLast(12)
            if (s.rssi > peak) peak = s.rssi
            if (phaseNow == WizardPhase.MEASURING) { buffer.add(s.rssi); sampleCount = buffer.size }
        }
    }

    fun beginBeacon() {
        scenarios = CalibrationScenarios.forMaxDistance(maxDistance, includeAngles, includeBody)
        si = 0; results.clear(); recent = emptyList(); message = null
        stepStartMs = SystemClock.elapsedRealtime()
        phase = WizardPhase.POSITION
    }

    fun finishBeacon() {
        val name = queue[bi]
        current = ProfileFitter.build(name, "${Build.MANUFACTURER} ${Build.MODEL}", System.currentTimeMillis(), results.toList())
        phase = WizardPhase.BEACON_DONE
    }

    fun nextStep() {
        recent = emptyList(); message = null
        if (si >= scenarios.lastIndex) finishBeacon()
        else { si += 1; stepStartMs = SystemClock.elapsedRealtime(); phase = WizardPhase.POSITION }
    }

    fun startMeasuring() {
        val sc = scenarios[si]
        buffer.clear(); sampleCount = 0
        secondsLeft = sc.holdS
        phase = WizardPhase.MEASURING
        scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            val end = t0 + sc.holdS * 1000L
            while (SystemClock.elapsedRealtime() < end && phase == WizardPhase.MEASURING) {
                secondsLeft = ((end - SystemClock.elapsedRealtime() + 999) / 1000).toInt()
                delay(200)
            }
            if (phase != WizardPhase.MEASURING) return@launch
            val r = ProfileFitter.summarize(sc, buffer.toList(), (SystemClock.elapsedRealtime() - t0) / 1000.0)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            if (r == null) {
                message = "Solo llegaron ${buffer.size} muestras (mínimo 6). Acércate, comprueba que la baliza esté encendida y repite este paso."
                stepStartMs = SystemClock.elapsedRealtime()
                phase = WizardPhase.POSITION
            } else {
                results.removeAll { it.scenarioId == sc.id }
                results.add(r)
                nextStep()
            }
        }
    }

    // Arranque automático cuando la señal se estabiliza (y pasaron unos segundos desde que apareció el paso).
    LaunchedEffect(recent, phase, autoStart) {
        if (phase == WizardPhase.POSITION && autoStart) {
            val age = (SystemClock.elapsedRealtime() - stepStartMs) / 1000.0
            if (age >= AUTO_MIN_STEP_S && StabilityDetector.isStable(recent.takeLast(10))) startMeasuring()
        }
    }

    ScreenScaffold(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenTitle("Calibrar balizas")

        when (phase) {
            WizardPhase.SETUP -> {
                statusBar()
                Text(
                    "Para cada baliza te mostraré en un mapa dónde ponerte, medirá sola y al final guardará su perfil. Necesitas una cinta métrica.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                AdminCard {
                    SectionHeader("Balizas", 1, "Se detectan solas: no hace falta pulsar nada.")
                    if (enabledBeacons.isEmpty()) {
                        SlotText("Aún no hay balizas en la lista. Enciende una y espera unos segundos.", minLines = 2)
                    }
                    enabledBeacons.forEach { b ->
                        val dev = byName[b.name]
                        val visible = b.name in visibleNames
                        BeaconChoiceRow(
                            name = b.name, rssi = if (visible) dev?.rssi else null, visible = visible,
                            quality = b.profile?.quality, checked = b.name in chosen,
                            onToggle = { on -> if (on) { if (b.name !in chosen) chosen.add(b.name) } else chosen.remove(b.name) }
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { visibleNames.forEach { if (it !in chosen) chosen.add(it) } }) { Text("Elegir las detectadas") }
                        TextButton(onClick = { chosen.clear() }) { Text("Quitar todas") }
                    }
                }

                AdminCard {
                    SectionHeader("Espacio en línea recta", 2, "¿Hasta cuántos metros puedes alejarte de una baliza?")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        DISTANCE_OPTIONS.forEach { d ->
                            val on = d == maxDistance
                            Box(
                                Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(14.dp))
                                    .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { maxDistance = d },
                                contentAlignment = Alignment.Center
                            ) { Text("${"%.0f".format(d)} m", fontWeight = FontWeight.Bold, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface) }
                        }
                    }
                    DistanceRuler(maxDistance)
                }

                AdminCard {
                    SectionHeader("Opciones", 3)
                    OptionRow("Medir la asimetría de la antena", "3 ángulos a 1 m alrededor de la baliza.", includeAngles) { includeAngles = it }
                    OptionRow("Medir la sombra del cuerpo", "A 1 m, con tu cuerpo entre la baliza y el móvil.", includeBody) { includeBody = it }
                    OptionRow("Medir cuando la señal se estabilice", "Si lo apagas, tú decides cuándo con el botón.", autoStart) { autoStart = it }
                }

                val plan = CalibrationScenarios.forMaxDistance(maxDistance, includeAngles, includeBody)
                val perBeaconMin = (plan.sumOf { it.holdS } + plan.size * 6) / 60 + 1
                AdminCard(container = MaterialTheme.colorScheme.secondaryContainer, border = null) {
                    SectionHeader("Resumen", 4)
                    Text(
                        "${plan.size} pasos por baliza · unos $perBeaconMin min cada una" + if (chosen.size > 1) " · ${perBeaconMin * chosen.size} min en total" else "",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    StepStrip(plan, -1, emptySet())
                }
                PrimaryActionButton(
                    if (chosen.isEmpty()) "Elige al menos una baliza" else "Empezar con ${chosen.size} baliza${if (chosen.size == 1) "" else "s"}",
                    icon = Icons.Filled.Tune,
                    onClick = { if (chosen.isNotEmpty()) { queue = chosen.sorted(); bi = 0; done.clear(); beginBeacon() } }
                )
                SecondaryActionButton("Buscador de máximo (dónde apoyar el móvil)", onClick = {
                    peakBeacon = chosen.firstOrNull() ?: visibleNames.firstOrNull(); peak = -127; recent = emptyList(); phase = WizardPhase.PEAK
                })
                SecondaryActionButton("Volver", icon = Icons.AutoMirrored.Filled.ArrowBack, onClick = onBack)
            }

            WizardPhase.PEAK -> {
                statusBar()
                Text("Mueve el móvil sobre la baliza despacio. Se guarda el pico: ese es el lugar (y el lado del móvil) que conviene marcar con cinta para las pruebas de contacto.", style = MaterialTheme.typography.bodyLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    enabledBeacons.map { it.name }.forEach { nm ->
                        val on = nm == peakBeacon
                        Box(
                            Modifier.width(64.dp).height(44.dp).clip(RoundedCornerShape(14.dp))
                                .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { peakBeacon = nm; peak = -127; recent = emptyList() },
                            contentAlignment = Alignment.Center
                        ) { Text(nm.takeLast(2), fontWeight = FontWeight.Bold, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface) }
                    }
                }
                val smooth = recent.takeLast(5).let { if (it.isEmpty()) null else it.sorted()[it.size / 2] }
                AdminCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RssiReadout(smooth)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Pico", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if (peak > -127) "$peak dBm" else "—", style = NumberStyle.copy(fontSize = 26.sp, fontWeight = FontWeight.Bold), color = AdminColors.bad, maxLines = 1, softWrap = false)
                        }
                    }
                    RssiMeter(smooth, peak = peak.takeIf { it > -127 })
                    SlotText(
                        if (nowMs - lastSampleMs > NO_SIGNAL_MS) "Sin señal de esa baliza: acércate o comprueba que esté encendida." else "El marcador rojo es el pico más alto que llevas.",
                        minLines = 2
                    )
                }
                OutlinedButton(onClick = { peak = -127 }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Restablecer pico") }
                SecondaryActionButton("Terminar", icon = Icons.AutoMirrored.Filled.ArrowBack, onClick = { phase = WizardPhase.SETUP })
            }

            WizardPhase.POSITION, WizardPhase.MEASURING -> {
                val name = queue[bi]
                val sc = scenarios[si]
                val measuring = phase == WizardPhase.MEASURING
                val stable = StabilityDetector.isStable(recent.takeLast(10))
                val noSignal = nowMs - lastSampleMs > NO_SIGNAL_MS
                val smooth = recent.takeLast(5).let { if (it.isEmpty() || noSignal) null else it.sorted()[it.size / 2] }

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    BeaconNumberBadge(name, MaterialTheme.colorScheme.primary, 48.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text("Baliza ${bi + 1} de ${queue.size} · paso ${si + 1} de ${scenarios.size}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                StepStrip(scenarios, si, results.map { it.scenarioId }.toSet())

                CalibrationStageMap(sc, scenarios.getOrNull(si - 1), name, measuring, stable)

                AdminCard {
                    Text(sc.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Column(Modifier.heightIn(min = 156.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        howTo(sc).forEachIndexed { i, line ->
                            Row(verticalAlignment = Alignment.Top) {
                                Box(Modifier.size(22.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .15f), CircleShape), contentAlignment = Alignment.Center) {
                                    Text("${i + 1}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(line, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }

                // Lectura en vivo: todo de tamaño fijo, solo cambian los valores.
                AdminCard {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(84.dp)) {
                        RssiReadout(smooth, color = if (measuring || stable) AdminColors.good else MaterialTheme.colorScheme.onSurface)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SignalBars(smooth)
                                Spacer(Modifier.weight(1f))
                                Text(
                                    if (measuring) "$sampleCount muestras" else "${recent.size} recientes",
                                    style = NumberStyle.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false
                                )
                            }
                            when {
                                noSignal -> StatusPill("Sin señal…", AdminColors.bad, icon = Icons.Filled.Warning)
                                measuring -> StatusPill("Midiendo · no te muevas", AdminColors.good)
                                stable -> StatusPill("Señal estable", AdminColors.good, icon = Icons.Filled.CheckCircle)
                                else -> StatusPill("Esperando estabilidad", AdminColors.warn)
                            }
                            RssiMeter(smooth, color = if (measuring || stable) AdminColors.goodFill else MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                SlotText(message, minLines = 3, color = AdminColors.bad)

                ProgressButton(
                    label = if (measuring) "Midiendo…  $secondsLeft s" else "YA ESTOY · MEDIR",
                    progress = if (measuring) 1f - secondsLeft / sc.holdS.toFloat() else null,
                    onClick = { startMeasuring() }
                )
                SlotText(if (autoStart && !measuring) "También empezará solo cuando la señal se estabilice." else null, minLines = 1, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { if (measuring) { phase = WizardPhase.POSITION; stepStartMs = SystemClock.elapsedRealtime() } else nextStep() },
                        modifier = Modifier.weight(1f).height(52.dp)
                    ) { Text(if (measuring) "Repetir" else "Saltar paso", maxLines = 1) }
                    OutlinedButton(onClick = { phase = WizardPhase.SETUP }, modifier = Modifier.weight(1f).height(52.dp)) { Text("Cancelar", maxLines = 1) }
                }
            }

            WizardPhase.BEACON_DONE -> {
                val p = current
                if (p != null) ProfileSummary(p)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { beginBeacon() }, modifier = Modifier.weight(1f).height(56.dp)) { Text("Repetir", maxLines = 1) }
                    androidx.compose.material3.Button(onClick = {
                        p?.let { RouteRepository.saveProfile(it); done.add(it) }
                        if (bi >= queue.lastIndex) phase = WizardPhase.ALL_DONE else { bi += 1; beginBeacon() }
                    }, modifier = Modifier.weight(1.4f).height(56.dp)) { Text(if (bi >= queue.lastIndex) "Guardar y terminar" else "Guardar y seguir", maxLines = 1) }
                }
                TextButton(onClick = { if (bi >= queue.lastIndex) phase = WizardPhase.ALL_DONE else { bi += 1; beginBeacon() } }, modifier = Modifier.fillMaxWidth()) { Text("Descartar y seguir") }
            }

            WizardPhase.ALL_DONE -> {
                AdminCard(container = AdminColors.goodFill.copy(alpha = .12f), border = null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, null, tint = AdminColors.good, modifier = Modifier.size(32.dp)); Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Calibración terminada", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("${done.size} perfil(es) guardados en este teléfono.", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                done.forEach { p ->
                    val qc = qualityColor(p.quality)
                    AdminCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            QualityBadge(p.quality, 44.dp); Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.beacon, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(p.quality.label, style = MaterialTheme.typography.bodyMedium, color = qc)
                            }
                            Text(p.contactRssi?.let { "%.0f dBm".format(it) } ?: "—", style = NumberStyle.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold), maxLines = 1, softWrap = false)
                        }
                    }
                }
                if (done.isNotEmpty()) SecondaryActionButton("Exportar perfiles (CSV)", icon = Icons.Filled.Share, onClick = { ProfileExport.exportAndShare(context, done.toList()) })
                PrimaryActionButton("Calibrar otras balizas", onClick = { phase = WizardPhase.SETUP })
                SecondaryActionButton("Volver", icon = Icons.AutoMirrored.Filled.ArrowBack, onClick = onBack)
            }
        }
    }
}

// ───────────────────────── piezas del asistente ─────────────────────────

@Composable
private fun BeaconChoiceRow(name: String, rssi: Int?, visible: Boolean, quality: Quality?, checked: Boolean, onToggle: (Boolean) -> Unit) {
    val color = if (visible) AdminColors.good else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth().height(64.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (checked) MaterialTheme.colorScheme.primary.copy(alpha = .08f) else Color.Transparent)
            .clickable(enabled = visible) { onToggle(!checked) }
            .padding(horizontal = 8.dp)
    ) {
        BeaconNumberBadge(name, color)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                if (visible) "Detectada · ${rssi ?: "—"} dBm" else "No detectada",
                style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        if (quality != null) StatusPill("Perfil ${quality.name}", qualityColor(quality)) else StatusPill("Sin perfil", MaterialTheme.colorScheme.onSurfaceVariant)
        Checkbox(checked = checked, onCheckedChange = onToggle, enabled = visible)
    }
}

@Composable
private fun OptionRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Regla de 0 a 6 m con la parte que vas a usar resaltada. */
@Composable
private fun DistanceRuler(maxM: Double) {
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.outlineVariant
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.fillMaxWidth().height(44.dp)) {
        val pad = 14.dp.toPx()
        val w = size.width - 2 * pad
        val y = 14.dp.toPx()
        fun x(m: Double) = pad + (m / 6.0).toFloat() * w
        drawLine(off, Offset(pad, y), Offset(pad + w, y), 6.dp.toPx(), cap = StrokeCap.Round)
        drawLine(on, Offset(pad, y), Offset(x(maxM), y), 6.dp.toPx(), cap = StrokeCap.Round)
        for (m in 0..6) {
            drawLine(if (m <= maxM) on else off, Offset(x(m.toDouble()), y + 6.dp.toPx()), Offset(x(m.toDouble()), y + 12.dp.toPx()), 2.dp.toPx())
            drawContext.canvas.nativeCanvas.drawText(
                "$m", x(m.toDouble()), y + 28.dp.toPx(),
                android.graphics.Paint().apply { isAntiAlias = true; color = ink.toArgb(); textSize = 11.sp.toPx(); textAlign = android.graphics.Paint.Align.CENTER }
            )
        }
    }
}

/** Tira de pasos: hechos ✓, actual resaltado, pendientes atenuados. Se desplaza sola al paso actual. */
@Composable
private fun StepStrip(scenarios: List<CalibrationScenario>, current: Int, doneIds: Set<String>) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    LaunchedEffect(current) {
        if (current >= 0) scroll.animateScrollTo((with(density) { (current * 64).dp.roundToPx() } - with(density) { 90.dp.roundToPx() }).coerceAtLeast(0))
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        scenarios.forEachIndexed { i, sc ->
            val isDone = sc.id in doneIds
            val isCurrent = i == current
            val bg = when { isCurrent -> MaterialTheme.colorScheme.primary; isDone -> AdminColors.goodFill.copy(alpha = .18f); else -> MaterialTheme.colorScheme.surface }
            val fg = when { isCurrent -> MaterialTheme.colorScheme.onPrimary; isDone -> AdminColors.good; else -> MaterialTheme.colorScheme.onSurfaceVariant }
            Column(
                Modifier.width(58.dp).height(48.dp).clip(RoundedCornerShape(12.dp)).background(bg),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
            ) {
                if (isDone) Icon(Icons.Filled.Check, null, tint = fg, modifier = Modifier.size(16.dp))
                else Text("${i + 1}", style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1)
                Text(shortLabel(sc), style = MaterialTheme.typography.labelMedium, color = fg, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

@Composable
private fun QualityBadge(q: Quality, size: androidx.compose.ui.unit.Dp) {
    val c = qualityColor(q)
    Box(Modifier.size(size).background(c.copy(alpha = .16f), CircleShape), contentAlignment = Alignment.Center) {
        Text(q.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = c)
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.height(72.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = NumberStyle.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold), maxLines = 1, softWrap = false)
    }
}

/** Resultado de una baliza: nota, gráfico RSSI–distancia, cifras clave y motivos. */
@Composable
private fun ProfileSummary(p: BeaconProfile) {
    val color = qualityColor(p.quality)
    var showAll by remember { mutableStateOf(false) }
    fun f(v: Double?, unit: String, d: Int = 0) = v?.let { "%.${d}f".format(it) + unit } ?: "—"

    AdminCard(container = color.copy(alpha = .10f), border = null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            QualityBadge(p.quality, 56.dp); Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(p.beacon, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Perfil ${p.quality.label}", style = MaterialTheme.typography.bodyMedium, color = color, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    AdminCard {
        SectionHeader("Señal según la distancia", subtitle = "Puntos: lo medido. Línea: el ajuste. Cuadrados: ángulos a 1 m.")
        ProfileChart(p)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            StatTile("En contacto", f(p.contactRssi, " dBm"), Modifier.weight(1f))
            StatTile("A 1 m (ajuste)", f(p.a1m, " dBm"), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            StatTile("Exponente n", f(p.pathLossN, "", 1), Modifier.weight(1f))
            StatTile("Asimetría", f(p.asymmetryDb, " dB"), Modifier.weight(1f))
        }
        StatRow("Residuo del ajuste", f(p.fitResidualSd, " dB", 1))
        StatRow("Muestras por segundo", f(p.meanRatePerS, "", 1))
    }
    AdminCard {
        SectionHeader("Observaciones")
        p.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
    }
    AdminCard {
        Row(Modifier.fillMaxWidth().clickable { showAll = !showAll }.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Ver cada medición (${p.results.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(if (showAll) "Ocultar" else "Mostrar", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
        if (showAll) p.results.forEach {
            val label = when (it.kind) {
                ScenarioKind.CONTACT -> "Contacto"
                ScenarioKind.ANGLE -> "1 m a ${it.angleDeg}°"
                ScenarioKind.BODY -> "1 m con el cuerpo en medio"
                else -> if (it.distanceM < 1) "${(it.distanceM * 100).toInt()} cm" else "${"%.0f".format(it.distanceM)} m"
            }
            StatRow(label, "${"%.0f".format(it.median)} dBm  σ${"%.1f".format(it.sd)}  n${it.n}")
        }
        Text("Se guarda con el modelo de teléfono: ${p.phoneModel}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
