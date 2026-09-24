package com.example.openview.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.openview.ble.BleDeviceInfo
import com.example.openview.ble.BleScanner
import com.example.openview.data.ActiveFingerprints
import com.example.openview.data.ActiveGraph
import com.example.openview.data.FingerprintRepository
import com.example.openview.data.NavNode
import com.example.openview.data.Rotation
import com.example.openview.data.Rotations
import com.example.openview.data.RouteRepository
import com.example.openview.data.TestLayout
import com.example.openview.data.TestSetup
import com.example.openview.data.TestSetupRepository
import com.example.openview.input.VolumeUpDispatcher
import com.example.openview.logging.BleSessionLogger
import com.example.openview.logging.CENTER_NODE_ID
import com.example.openview.logging.MarkEvent
import com.example.openview.logging.SessionAnalyzer
import com.example.openview.logging.SessionSummary
import com.example.openview.nav.Fingerprints
import com.example.openview.nav.BlePositioner
import com.example.openview.nav.LivePosition
import com.example.openview.ui.components.PrimaryActionButton
import com.example.openview.ui.components.ScreenScaffold
import com.example.openview.ui.components.ScreenTitle
import com.example.openview.ui.components.SecondaryActionButton
import com.example.openview.ui.theme.FailureRed
import com.example.openview.ui.theme.SuccessGreen
import kotlinx.coroutines.delay

private enum class GuidedPhase { SETUP, WALK, DWELL, DONE }

/** Cómo te paras en cada baliza. Se guarda en el log: las huellas de "contacto" y de "a distancia" no se mezclan. */
private enum class StayType(val id: String, val label: String, val instruction: String) {
    CONTACT("contacto", "Contacto (móvil apoyado)", "Apoya el móvil en la baliza, en el extremo de la antena, siempre el mismo lado."),
    CM30("30cm", "A 30 cm", "Ponte a 30 cm de la baliza (mídelo con la cinta), con el móvil en la mano como siempre."),
    M1("1m", "A 1 m", "Ponte a 1 m de la baliza (mídelo con la cinta), con el móvil en la mano como siempre.")
}

private data class Step(val node: NavNode, val isCenter: Boolean)

private val LAG_OPTIONS = listOf(3, 5, 10)
private val DWELL_OPTIONS = listOf(6, 10, 15)
private val POWER_OPTIONS = listOf("PC", "Cargador", "Otro")
private val QUICK_NOTES = listOf("Puerta abierta", "Gente pasando", "Baliza movida", "Apoyé mal el móvil", "Cambié la postura")

/**
 * Prueba guiada v3.
 *
 * Elige el escenario (posiciones físicas en metros), qué balizas participan y una ROTACIÓN
 * (qué baliza va en qué posición): así puedes intercambiar las placas de sitio y comparar si lo que
 * falla es la placa o la posición. Empieza en el centro (donde NO debe detectarse ningún nodo) y
 * recorre las posiciones en orden con el botón LLEGUÉ / Vol+. Todo (escenario, rotación, factores,
 * vuelta) queda en el log en una fila LAYOUT para el análisis.
 */
@Composable
fun GuidedTestScreen(scanner: BleScanner, statusBar: @Composable () -> Unit, onBack: () -> Unit) {
    val scan by scanner.state.collectAsState()
    val live by LivePosition.current.collectAsState()
    val catalog by RouteRepository.catalog.collectAsState()
    val setups by TestSetupRepository.state.collectAsState()
    val session by BleSessionLogger.state.collectAsState()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    val setup: TestSetup = setups.setups.firstOrNull { it.id == setups.selectedId } ?: setups.setups.first()
    val spotCount = setup.spots.size

    // --- Configuración de la prueba ---
    val picked = remember { mutableStateListOf<String>() }
    var rotationId by remember { mutableStateOf("R0") }
    val custom = remember { mutableStateListOf<String>() }
    var stay by remember { mutableStateOf(StayType.CONTACT) }
    var dwellSeconds by remember { mutableIntStateOf(6) }
    var startAtCenter by remember { mutableStateOf(true) }
    var editingSetup by remember { mutableStateOf(false) }
    var trainChoice by remember { mutableStateOf<Boolean?>(null) }   // null = automático (entrenar si aún no hay huellas)

    val allBeacons = catalog.beacons.map { it.name }.sorted()
    // Al abrir: por defecto las balizas detectadas (o las primeras) hasta completar las posiciones.
    LaunchedEffect(scan.targets.size, allBeacons.size, spotCount) {
        if (picked.isEmpty()) {
            val seen = scan.targets.mapNotNull { it.name }.distinct().sorted()
            (seen + allBeacons.filter { it !in seen }).take(spotCount).forEach { picked.add(it) }
        }
    }
    val baseOrder = picked.sorted()
    // remember: la rotación «Aleatoria» se sortea UNA vez por conjunto de balizas, no en cada recomposición.
    val rotations = remember(baseOrder, spotCount) { if (baseOrder.size == spotCount) Rotations.options(baseOrder) else emptyList() }
    val effectiveRotation: Rotation? = when {
        baseOrder.size != spotCount -> null
        rotationId == "CUS" -> custom.toList().takeIf { Rotations.isValid(it, baseOrder) }?.let { Rotations.custom(it) }
        else -> rotations.firstOrNull { it.id == rotationId } ?: rotations.firstOrNull()
    }
    val graph = effectiveRotation?.let { TestLayout.graph(setup, it) }
    val layoutKey = effectiveRotation?.let { TestLayout.key(setup, it, stay.id) }
    val storedLaps by FingerprintRepository.laps.collectAsState()
    val lapsForKey = layoutKey?.let { storedLaps[it]?.values?.maxOfOrNull { l -> l.size } } ?: 0
    val trainMode = trainChoice ?: (lapsForKey == 0)

    // El grafo temporal (con el orden de los puntos) y las huellas de esta disposición hacen que el posicionador
    // use exactamente estas posiciones/balizas mientras la pantalla está abierta.
    LaunchedEffect(graph, layoutKey) { ActiveGraph.useTemporary(graph); ActiveFingerprints.key = layoutKey }
    DisposableEffect(Unit) { onDispose { ActiveGraph.useTemporary(null); ActiveFingerprints.key = null } }

    // --- Ejecución ---
    var phase by remember { mutableStateOf(GuidedPhase.SETUP) }
    var index by remember { mutableIntStateOf(0) }
    var dwellEndMs by remember { mutableLongStateOf(0L) }
    var nowMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var summary by remember { mutableStateOf<SessionSummary?>(null) }
    var lastNote by remember { mutableStateOf("") }
    var runSteps by remember { mutableStateOf(listOf<Step>()) }
    var runRotation by remember { mutableStateOf<Rotation?>(null) }
    var runSetup by remember { mutableStateOf<TestSetup?>(null) }
    var sessionName by remember { mutableStateOf("") }
    var runKey by remember { mutableStateOf<String?>(null) }
    var runTrain by remember { mutableStateOf(false) }
    var trainedNow by remember { mutableStateOf(false) }

    fun finishTest() {
        BleSessionLogger.stop()
        val rot = runRotation; val st = runSetup
        val beaconOfNode = if (rot != null && st != null) st.spots.mapIndexed { i, s -> s.id to rot.beaconAt[i] }.toMap() else emptyMap()
        summary = SessionAnalyzer.analyze(BleSessionLogger.currentRows(), beaconOfNode)
        if (rot != null && st != null) TestSetupRepository.lapDone(st.id, rot.id)
        phase = GuidedPhase.DONE
    }

    fun finishDwell() {
        val step = runSteps[index]
        BleSessionLogger.markNode(MarkEvent.DEPART, step.node)
        if (index >= runSteps.lastIndex) finishTest() else { index += 1; phase = GuidedPhase.WALK }
    }

    fun arrive(lagS: Int) {
        val step = runSteps[index]
        BleSessionLogger.markNode(MarkEvent.ARRIVE, step.node, lagS.toDouble())
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val remaining = (dwellSeconds - lagS).coerceAtLeast(3)
        dwellEndMs = SystemClock.elapsedRealtime() + remaining * 1000L
        phase = GuidedPhase.DWELL
    }

    fun startRun(rot: Rotation, g: com.example.openview.data.NavGraph) {
        val lap = TestSetupRepository.nextLap(setup.id, rot.id)
        val name = sessionName.ifBlank { "${setup.id}_${rot.id}_v$lap" }
        val steps = buildList {
            if (startAtCenter) {
                val (cx, cy) = setup.center
                add(Step(NavNode(CENTER_NODE_ID, "Centro (inicio)", cx, cy, null), true))
            }
            g.nodes.forEach { add(Step(it, false)) }
        }
        runSteps = steps; runRotation = rot; runSetup = setup
        runKey = layoutKey; runTrain = trainMode; trainedNow = false
        index = 0; summary = null
        ActiveGraph.resetToken += 1        // el posicionador empieza la vuelta sin el progreso de la anterior
        BleSessionLogger.start(name, "Prueba guiada v3, ${g.nodes.size} posiciones, estancia $dwellSeconds s, ${stay.id}")
        val power = setup.spots.joinToString("|") { "${it.id}:${setup.power[it.id] ?: "?"}" }
        BleSessionLogger.addLayout(
            "setup=${setup.id};rot=${rot.id};${TestLayout.describe(setup, rot)};stay=${stay.id};dwell=$dwellSeconds;lap=$lap;power=$power;center=${startAtCenter};mode=${if (trainMode) "entrenar" else "validar"};fp_laps=$lapsForKey"
        )
        phase = GuidedPhase.WALK
    }

    LaunchedEffect(phase, dwellEndMs) {
        while (phase == GuidedPhase.WALK || phase == GuidedPhase.DWELL) {
            nowMs = SystemClock.elapsedRealtime()
            if (phase == GuidedPhase.DWELL && nowMs >= dwellEndMs) { finishDwell(); break }
            delay(200)
        }
    }

    val onVolumeUp by rememberUpdatedState({ if (phase == GuidedPhase.WALK) arrive(0) })
    DisposableEffect(Unit) {
        val listener = { onVolumeUp() }
        VolumeUpDispatcher.register(listener)
        onDispose { VolumeUpDispatcher.unregister(listener) }
    }

    ScreenScaffold(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenTitle("Prueba guiada")

        when (phase) {
            GuidedPhase.SETUP -> {
                statusBar()
                // ---- Escenario ----
                SectionHeader("Escenario", 1, "Posiciones en metros.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    setups.setups.forEach { s ->
                        if (s.id == setup.id) Button(onClick = {}, modifier = Modifier.weight(1f)) { Text(s.name, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center) }
                        else OutlinedButton(onClick = { TestSetupRepository.select(s.id); picked.clear(); rotationId = "R0" }, modifier = Modifier.weight(1f)) { Text(s.name, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center) }
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        setup.spots.forEach { sp ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("${sp.id} · (${"%.1f".format(sp.x)} , ${"%.1f".format(sp.y)}) m", style = MaterialTheme.typography.bodyLarge)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    POWER_OPTIONS.forEach { p ->
                                        val on = setup.power[sp.id] == p
                                        if (on) Button(onClick = {}, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) { Text(p, style = MaterialTheme.typography.labelSmall) }
                                        else OutlinedButton(onClick = { TestSetupRepository.updateSetup(setup.copy(power = setup.power + (sp.id to p))) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) { Text(p, style = MaterialTheme.typography.labelSmall) }
                                    }
                                }
                            }
                        }
                        val (cx, cy) = setup.center
                        Text("Inicio (centro): (${"%.1f".format(cx)} , ${"%.1f".format(cy)}) m · Los botones PC/Cargador registran de qué se alimenta cada posición (se queda con la posición al mover las placas).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { editingSetup = true }, modifier = Modifier.fillMaxWidth()) { Text("Editar coordenadas / inicio") }
                    }
                }

                // ---- Balizas ----
                SectionHeader("Balizas que participan (${picked.size} de $spotCount)", 2, "✓ = detectada ahora, ✗ = no se oye.")
                val byName = scan.targets.filter { it.name != null }.associateBy { it.name!! }
                val now = SystemClock.elapsedRealtime()
                allBeacons.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { name ->
                            val dev = byName[name]
                            val visible = dev != null && now - dev.lastSeenElapsedMs <= 5_000L
                            val label = name.takeLast(2) + if (visible) " ✓" else " ✗"
                            if (name in picked) Button(onClick = { picked.remove(name); rotationId = "R0" }, modifier = Modifier.weight(1f).height(48.dp)) { Text(label, maxLines = 1) }
                            else OutlinedButton(onClick = { if (picked.size < spotCount) { picked.add(name); rotationId = "R0" } }, modifier = Modifier.weight(1f).height(48.dp)) { Text(label, maxLines = 1) }
                        }
                    }
                }
                Text("✓ = detectada ahora. R0 pone las balizas elegidas de menor a mayor número en P1, P2… (la placa más baja en P1).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                // ---- Rotación ----
                SectionHeader("Rotación", 3, "Qué baliza va en cada posición.")
                if (rotations.isEmpty()) {
                    Text("Elige $spotCount balizas para ver las rotaciones.", color = FailureRed, style = MaterialTheme.typography.bodyMedium)
                } else {
                    rotations.forEach { r ->
                        val sel = effectiveRotation?.id == r.id && rotationId != "CUS"
                        Card(
                            colors = CardDefaults.cardColors(containerColor = if (sel) SuccessGreen.copy(alpha = .15f) else MaterialTheme.colorScheme.surface),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(r.name + " · vuelta ${TestSetupRepository.nextLap(setup.id, r.id)}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                    Text(setup.spots.mapIndexed { i, s -> "${s.id}=${r.beaconAt[i].takeLast(2)}" }.joinToString("  "), style = MaterialTheme.typography.bodyMedium)
                                }
                                if (sel) Icon(Icons.Filled.CheckCircle, null, tint = SuccessGreen)
                                else OutlinedButton(onClick = { rotationId = r.id }) { Text("Elegir") }
                            }
                        }
                    }
                    // Personalizada
                    val customSel = rotationId == "CUS"
                    Card(colors = CardDefaults.cardColors(containerColor = if (customSel) SuccessGreen.copy(alpha = .15f) else MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Personalizada (toca una posición para cambiar su baliza)", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            if (customSel && custom.size == spotCount) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                    setup.spots.forEachIndexed { i, s ->
                                        OutlinedButton(onClick = {
                                            // Pasa a la siguiente baliza elegida; si se repite, la otra posición toma la que quedó libre.
                                            val next = baseOrder[(baseOrder.indexOf(custom[i]) + 1) % baseOrder.size]
                                            val other = custom.indexOf(next)
                                            if (other >= 0) custom[other] = custom[i]
                                            custom[i] = next
                                        }, modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) {
                                            Text("${s.id}\n${custom[i].takeLast(2)}", textAlign = TextAlign.Center)
                                        }
                                    }
                                }
                                if (!Rotations.isValid(custom.toList(), baseOrder)) Text("Cada baliza debe estar en una sola posición.", color = FailureRed, style = MaterialTheme.typography.bodySmall)
                            } else {
                                OutlinedButton(onClick = { custom.clear(); custom.addAll(effectiveRotation?.beaconAt ?: baseOrder); rotationId = "CUS" }, modifier = Modifier.fillMaxWidth()) { Text("Usar personalizada") }
                            }
                        }
                    }
                    Text("Cada placa conserva su nombre: solo cámbiala de sitio físicamente según la rotación elegida (con su cable o fuente de esa posición) antes de empezar.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // ---- Modo ----
                SectionHeader("Modo", 4, "Entrenar guarda huellas; validar mide el resultado real.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    if (trainMode) Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("Entrenar") } else OutlinedButton(onClick = { trainChoice = true }, modifier = Modifier.weight(1f)) { Text("Entrenar") }
                    if (!trainMode) Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("Validar") } else OutlinedButton(onClick = { trainChoice = false }, modifier = Modifier.weight(1f)) { Text("Validar") }
                }
                Text(
                    if (lapsForKey == 0) "Esta disposición (baliza en cada posición + tipo de parada) aún no tiene huellas: haz 3 vueltas de ENTRENAMIENTO y guárdalas al final de cada una. Sin huellas la app usa el perfil de calibración o el RSSI relativo."
                    else "Huellas guardadas para esta disposición: $lapsForKey vuelta(s). ENTRENAR suma otra vuelta (al guardarla); VALIDAR usa las guardadas sin modificarlas y mide el resultado real.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (lapsForKey > 0 && layoutKey != null) OutlinedButton(onClick = { FingerprintRepository.clear(layoutKey) }, modifier = Modifier.fillMaxWidth()) { Text("Borrar las huellas de esta disposición") }
                if (!trainMode && lapsForKey == 0) Text("No hay huellas para validar: entrena primero.", color = FailureRed, style = MaterialTheme.typography.bodyMedium)

                // ---- Opciones ----
                SectionHeader("Cómo haces cada parada", 5)
                StayType.entries.forEach { t ->
                    if (t == stay) Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text(t.label) }
                    else OutlinedButton(onClick = { stay = t }, modifier = Modifier.fillMaxWidth()) { Text(t.label) }
                }
                Text("Tiempo quieto en cada punto", style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    DWELL_OPTIONS.forEach { s ->
                        if (s == dwellSeconds) Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("$s s") }
                        else OutlinedButton(onClick = { dwellSeconds = s }, modifier = Modifier.weight(1f)) { Text("$s s") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = startAtCenter, onCheckedChange = { startAtCenter = it }); Spacer(Modifier.width(10.dp))
                    Text("Empezar en el centro (ahí NO debe detectarse ningún nodo)", modifier = Modifier.weight(1f))
                }
                OutlinedTextField(sessionName, { sessionName = it }, label = { Text("Nombre de la sesión (opcional; por defecto escenario_rotación_vuelta)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                val uncal = picked.count { !RouteRepository.isCalibrated(it) }
                if (uncal > 0) Text("$uncal baliza(s) sin calibrar: úsalas igual para medir; para mejorar el resultado usa «Calibrar balizas».", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val fw = picked.mapNotNull { byName[it]?.fwVersion }.toSet(); val tx = picked.mapNotNull { byName[it]?.txLevel }.toSet()
                if (fw.size > 1 || tx.size > 1) Text("Aviso: firmware o potencia TX distintos entre las balizas elegidas.", color = FailureRed, style = MaterialTheme.typography.bodyMedium)

                PrimaryActionButton("Empezar prueba", icon = Icons.Filled.FiberManualRecord, onClick = {
                    if (effectiveRotation != null && graph != null) startRun(effectiveRotation, graph)
                })
                if (effectiveRotation == null) Text("Completa las balizas y una rotación válida.", color = FailureRed, style = MaterialTheme.typography.bodyMedium)
                SecondaryActionButton("Volver", icon = Icons.AutoMirrored.Filled.ArrowBack, onClick = onBack)
            }

            GuidedPhase.WALK, GuidedPhase.DWELL -> {
                val step = runSteps[index]
                val node = step.node
                val elapsed = BleSessionLogger.elapsedSeconds()
                Text("Punto ${index + 1} de ${runSteps.size}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (step.isCenter) "Centro (inicio)" else "${node.label} · ${node.beaconName}",
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
                )
                Text(
                    "Grabando · ${"%.0f".format(elapsed)} s · ${session.rowCount} muestras · ${runRotation?.name ?: ""}",
                    style = NumberStyle.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                RunMap(runSteps.map { it.node }, index, phase == GuidedPhase.DWELL)

                if (phase == GuidedPhase.WALK) {
                    SlotText(
                        if (step.isCenter) "Ponte en el centro del área, sin acercarte a ninguna baliza. Toca «ESTOY EN EL CENTRO» (o Vol+) al detenerte."
                        else "Ve a la posición (${"%.1f".format(node.x)} , ${"%.1f".format(node.y)}) m. ${stay.instruction} Toca LLEGUÉ (o Vol+) apenas te detengas.",
                        minLines = 5, maxLines = 6, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                    )
                    Button(onClick = { arrive(0) }, modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp)) {
                        Text(if (step.isCenter) "ESTOY EN EL CENTRO" else "LLEGUÉ", fontSize = if (step.isCenter) 26.sp else 34.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("¿Tocaste tarde? Llegué hace…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        LAG_OPTIONS.forEach { lag ->
                            OutlinedButton(onClick = { arrive(lag) }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("$lag s") }
                        }
                    }
                } else {
                    val remainingMs = (dwellEndMs - nowMs).coerceAtLeast(0L)
                    Text("Quédate quieto: ${(remainingMs + 999) / 1000} s", style = NumberStyle.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold), maxLines = 1, softWrap = false)
                    LinearProgressIndicator(progress = { 1f - (remainingMs / (dwellSeconds * 1000f)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(10.dp))
                    SlotText("Al terminar, la app marca sola la salida y te dirá cuál es el siguiente punto.", minLines = 4, maxLines = 4, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }

                LiveVerdict(expectedNodeId = if (step.isCenter) null else node.id, expectedLabel = node.label, nodesById = runSteps.associate { it.node.id to it.node }, live = live, beaconNames = runRotation?.beaconAt ?: baseOrder)

                Text("Notas rápidas (un toque)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth())
                QUICK_NOTES.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        pair.forEach { text -> OutlinedButton(onClick = { BleSessionLogger.addNote(text); lastNote = text }, modifier = Modifier.weight(1f)) { Text(text, style = MaterialTheme.typography.labelMedium) } }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                SlotText(if (lastNote.isNotEmpty()) "Anotado: $lastNote" else null, minLines = 1, style = MaterialTheme.typography.bodySmall)

                if (phase == GuidedPhase.WALK) {
                    SecondaryActionButton("Saltar este punto", onClick = { if (index >= runSteps.lastIndex) finishTest() else index += 1 })
                }
                SecondaryActionButton("Terminar ahora", icon = Icons.Filled.Stop, onClick = { finishTest() })
            }

            GuidedPhase.DONE -> DonePhase(
                summary = summary,
                nodeLabels = (runSteps.associate { it.node.id to it.node.label }),
                assignment = runSetup?.let { st -> runRotation?.let { r -> TestLayout.describe(st, r) } } ?: "",
                trainMode = runTrain,
                trainedNow = trainedNow,
                storedLaps = runKey?.let { storedLaps[it]?.values?.maxOfOrNull { l -> l.size } } ?: 0,
                onTrain = {
                    val key = runKey
                    if (key != null) {
                        val vectors = Fingerprints.trainFromRows(BleSessionLogger.currentRows())
                        FingerprintRepository.addLap(key, vectors)
                        trainedNow = true
                    }
                },
                onExport = { BleSessionLogger.exportAndShare(context) },
                onAgain = { phase = GuidedPhase.SETUP; summary = null },
                onBack = onBack
            )
        }
    }

    if (editingSetup) EditSetupDialog(setup, onDismiss = { editingSetup = false }, onReset = { TestSetupRepository.resetSetup(setup.id); editingSetup = false }, onSave = { TestSetupRepository.updateSetup(it); editingSetup = false })
}

@Composable
private fun EditSetupDialog(setup: TestSetup, onDismiss: () -> Unit, onReset: () -> Unit, onSave: (TestSetup) -> Unit) {
    val xs = remember(setup.id) { mutableStateListOf(*setup.spots.map { "%.2f".format(java.util.Locale.US, it.x) }.toTypedArray()) }
    val ys = remember(setup.id) { mutableStateListOf(*setup.spots.map { "%.2f".format(java.util.Locale.US, it.y) }.toTypedArray()) }
    var sx by remember(setup.id) { mutableStateOf(setup.startX?.let { "%.2f".format(java.util.Locale.US, it) } ?: "") }
    var sy by remember(setup.id) { mutableStateOf(setup.startY?.let { "%.2f".format(java.util.Locale.US, it) } ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar ${setup.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                setup.spots.forEachIndexed { i, s ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(s.id, modifier = Modifier.width(32.dp))
                        OutlinedTextField(xs[i], { xs[i] = it }, label = { Text("X (m)") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(ys[i], { ys[i] = it }, label = { Text("Y (m)") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                }
                Text("Inicio (centro): vacío = centro automático", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(sx, { sx = it }, label = { Text("X inicio") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(sy, { sy = it }, label = { Text("Y inicio") }, singleLine = true, modifier = Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val spots = setup.spots.mapIndexed { i, s -> s.copy(x = xs[i].replace(',', '.').toDoubleOrNull() ?: s.x, y = ys[i].replace(',', '.').toDoubleOrNull() ?: s.y) }
                onSave(setup.copy(spots = spots, startX = sx.replace(',', '.').toDoubleOrNull(), startY = sy.replace(',', '.').toDoubleOrNull()))
            }) { Text("Guardar") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onReset) { Text("Restaurar") }
                OutlinedButton(onClick = onDismiss) { Text("Cancelar") }
            }
        }
    )
}

/** Lo que dice la app AHORA frente a lo que debería decir, más el RSSI relativo de cada baliza (una fila fija por baliza). */
@Composable
private fun LiveVerdict(expectedNodeId: String?, expectedLabel: String, nodesById: Map<String, NavNode>, live: BlePositioner.Result?, beaconNames: List<String>) {
    val detected = live?.nodeId
    val ok = detected == expectedNodeId
    val color = if (ok) AdminColors.good else AdminColors.bad
    val byName = live?.readings.orEmpty().associateBy { it.beaconName }
    AdminCard(container = color.copy(alpha = .10f), border = null) {
        SlotText(
            when {
                expectedNodeId == null && detected == null -> "✓ La app no detecta ningún nodo (correcto en el centro)"
                expectedNodeId == null -> "✗ La app dice: ${nodesById[detected]?.label ?: detected} (en el centro no debería detectar ninguno)"
                detected == null -> "La app todavía no detecta ningún punto"
                ok -> "✓ La app dice: $expectedLabel (estable ${"%.1f".format((live?.stableMs ?: 0L) / 1000.0)} s)"
                else -> "✗ La app dice: ${nodesById[detected]?.label ?: detected} (debería ser $expectedLabel)"
            },
            minLines = 2, maxLines = 3, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color
        )
        beaconNames.sortedByDescending { byName[it]?.relDb ?: -999.0 }.forEach { n ->
            val r = byName[n]
            val calibrated = RouteRepository.isCalibrated(n)
            val isExpected = r?.nodeId == expectedNodeId && expectedNodeId != null
            Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(n + if (isExpected) "  ◀" else "", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1)
                Text(
                    r?.let { "${"%.0f".format(it.rssiSmoothed)} dBm · ${"%+.0f".format(it.relDb)} dB" + if (calibrated) "" else " *" } ?: "—",
                    style = NumberStyle.copy(fontSize = 13.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false
                )
            }
        }
        SlotText(if (beaconNames.any { !RouteRepository.isCalibrated(it) }) "* sin calibrar" else null, minLines = 1, style = MaterialTheme.typography.bodySmall)
    }
}

/** Mini mapa de la prueba: posiciones, la que toca ahora (latiendo), las ya hechas y la ruta entre ellas. */
@Composable
private fun RunMap(nodes: List<NavNode>, index: Int, dwelling: Boolean) {
    if (nodes.isEmpty()) return
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "runmap")
    val ph by pulse.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1300, easing = androidx.compose.animation.core.LinearEasing)), label = "p")
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.outline
    val ink = MaterialTheme.colorScheme.onSurface
    val bg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)
    androidx.compose.foundation.Canvas(
        Modifier.fillMaxWidth().height(190.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).background(bg)
    ) {
        val minX = nodes.minOf { it.x }; val maxX = nodes.maxOf { it.x }; val minY = nodes.minOf { it.y }; val maxY = nodes.maxOf { it.y }
        val spanX = (maxX - minX).coerceAtLeast(1.0); val spanY = (maxY - minY).coerceAtLeast(1.0)
        val pad = 34.dp.toPx()
        val scale = minOf((size.width - 2 * pad) / spanX.toFloat(), (size.height - 2 * pad) / spanY.toFloat())
        val ox = (size.width - spanX.toFloat() * scale) / 2f; val oy = (size.height - spanY.toFloat() * scale) / 2f
        fun at(n: NavNode) = androidx.compose.ui.geometry.Offset(ox + (n.x - minX).toFloat() * scale, oy + (maxY - n.y).toFloat() * scale)
        for (i in 0 until nodes.size - 1) {
            val done = i < index
            drawLine(if (done) AdminColors.goodFill else muted.copy(alpha = .5f), at(nodes[i]), at(nodes[i + 1]), strokeWidth = if (done) 5.dp.toPx() else 2.dp.toPx(),
                pathEffect = if (done) null else androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx())))
        }
        nodes.forEachIndexed { i, n ->
            val p = at(n)
            val isCenter = n.id == CENTER_NODE_ID
            val col = when { i < index -> AdminColors.goodFill; i == index -> primary; else -> muted }
            if (i == index) drawCircle(primary.copy(alpha = (1f - ph) * .4f), 14.dp.toPx() + ph * 16.dp.toPx(), p)
            drawCircle(col, if (i == index) 13.dp.toPx() else 10.dp.toPx(), p)
            val label = if (isCenter) "Inicio" else n.label
            drawContext.canvas.nativeCanvas.drawText(
                label, p.x, p.y - 20.dp.toPx(),
                android.graphics.Paint().apply { isAntiAlias = true; color = ink.toArgb(); textSize = 12.sp.toPx(); textAlign = android.graphics.Paint.Align.CENTER; isFakeBoldText = i == index }
            )
        }
    }
}

@Composable
private fun DonePhase(
    summary: SessionSummary?,
    nodeLabels: Map<String, String>,
    assignment: String,
    trainMode: Boolean,
    trainedNow: Boolean,
    storedLaps: Int,
    onTrain: () -> Unit,
    onExport: () -> Unit,
    onAgain: () -> Unit,
    onBack: () -> Unit
) {
    if (summary == null || summary.points.isEmpty()) {
        Text("No hubo llegadas marcadas: no hay nada que evaluar.", style = MaterialTheme.typography.bodyLarge)
    } else {
        val good = summary.meanAccuracyPct >= 95 && !summary.falseArrival
        Card(colors = CardDefaults.cardColors(containerColor = (if (good) SuccessGreen else FailureRed).copy(alpha = .12f)), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Acierto medio: ${"%.0f".format(summary.meanAccuracyPct)} %", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Cambios de nodo estando quieto: ${summary.totalFlips}", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (summary.falseArrival) "⚠ Falsa llegada: la app decía \"destino\" ${"%.1f".format(summary.wrongDestinationS)} s mientras estabas en otro punto."
                    else "Sin falsa llegada al destino.",
                    style = MaterialTheme.typography.bodyLarge
                )
                if (assignment.isNotEmpty()) Text("Balizas por posición: $assignment", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        summary.points.forEach { p ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val isCenter = p.nodeId == CENTER_NODE_ID
                    Text(
                        "${nodeLabels[p.nodeId] ?: p.nodeId}${if (isCenter) "" else " · ${p.beaconName ?: "?"}"}: ${"%.0f".format(p.accuracyPct)} %",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        color = if (p.accuracyPct >= 95) SuccessGreen else FailureRed
                    )
                    Text(
                        if (isCenter) "Debe decir «ningún nodo»." + (p.wrongNode?.let { " Dijo ${nodeLabels[it] ?: it}." } ?: "")
                        else "Tardó ${p.latencyS?.let { "%.1f".format(it) + " s" } ?: "— (nunca)"} en detectarlo · ${p.flips} cambios · RSSI mediano ${p.medianRssi ?: "—"} dBm" +
                            (p.wrongNode?.let { " · lo confundió con ${nodeLabels[it] ?: it}" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Text(
            "Este resultado es de UNA vuelta. Para comparar rotaciones repite 3 vueltas por rotación y exporta cada CSV. La calibración por baliza se hace en «Calibrar balizas».",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (trainMode) {
            Text(
                if (trainedNow) "✓ Vuelta guardada como entrenamiento (ahora hay $storedLaps vuelta(s) para esta disposición)."
                else "Modo ENTRENAR: si la vuelta salió bien hecha (llegadas marcadas al detenerte, sin mover el móvil), guárdala como huella. No guardes vueltas mal hechas.",
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                color = if (trainedNow) SuccessGreen else MaterialTheme.colorScheme.onSurface
            )
            if (!trainedNow) PrimaryActionButton("Guardar esta vuelta como entrenamiento", onClick = onTrain)
        } else {
            Text("Modo VALIDAR: este resultado se calculó con las huellas guardadas (${storedLaps} vuelta(s)), sin modificarlas.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    SecondaryActionButton("Exportar CSV", icon = Icons.Filled.Share, onClick = onExport)
    SecondaryActionButton("Nueva prueba", onClick = onAgain)
    SecondaryActionButton("Volver", icon = Icons.AutoMirrored.Filled.ArrowBack, onClick = onBack)
    Spacer(Modifier.padding(4.dp))
}
