package com.example.openview.ui.screens

import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.openview.ble.BleDeviceInfo
import com.example.openview.ble.BleScanner
import com.example.openview.ble.hasBlePermissions
import com.example.openview.ble.requiredBlePermissions
import com.example.openview.ble.signalBarsForRssi
import com.example.openview.data.ActiveGraph
import com.example.openview.data.BleSignal
import com.example.openview.data.ManagedBeacon
import com.example.openview.data.NavEdge
import com.example.openview.data.NavGraph
import com.example.openview.data.NavNode
import com.example.openview.data.RouteRepository
import com.example.openview.data.SavedRoute
import com.example.openview.logging.BleLogFile
import com.example.openview.logging.BleLogRow
import com.example.openview.logging.BleSessionLogger
import com.example.openview.logging.ReplayEngine
import com.example.openview.logging.ReplayFrame
import com.example.openview.nav.BlePositioner
import com.example.openview.nav.LivePosition
import com.example.openview.nav.TrackerParams
import com.example.openview.ui.components.PrimaryActionButton
import com.example.openview.ui.components.ScreenScaffold
import com.example.openview.ui.components.ScreenTitle
import com.example.openview.ui.components.SecondaryActionButton
import com.example.openview.ui.theme.FailureRed
import com.example.openview.ui.theme.SuccessGreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

private const val PRESENT_WINDOW_MS = 5_000L
private enum class AdminPage { HOME, ROUTES, BEACONS, EDITOR, REPLAY, GUIDED, CALIBRATE, USER_GUIDE }

/** Centro de control local de las rutas y balizas del edificio. */
@Composable
fun AdminBleScreen(scanner: BleScanner, onBack: () -> Unit) {
    val catalog by RouteRepository.catalog.collectAsState()
    val scan by scanner.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var hasPermission by remember { mutableStateOf(hasBlePermissions(context)) }
    var paused by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(AdminPage.HOME) }
    var editingRouteId by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        hasPermission = granted.values.all { it }
    }
    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { scanner.refreshBluetoothState() }
    LaunchedEffect(Unit) { while (true) { now = SystemClock.elapsedRealtime(); delay(1000) } }
    LaunchedEffect(scan.targets) { scan.targets.mapNotNull { it.name }.forEach(RouteRepository::rememberBeacon) }

    // El escaneo arranca solo al entrar al panel y se vigila mientras esté abierto (si Android lo deja mudo, se reinicia).
    // Al salir se apaga: la navegación normal gestiona el suyo.
    LaunchedEffect(hasPermission, paused) {
        when {
            paused -> scanner.stopKeepAlive()
            hasPermission -> scanner.startKeepAlive()
            else -> launcher.launch(requiredBlePermissions())
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { scanner.stopKeepAlive() } }

    val detected = scan.targets.count { now - it.lastSeenElapsedMs <= PRESENT_WINDOW_MS }
    val statusBar: @Composable () -> Unit = {
        ScanStatusBar(
            scan = scan, detected = detected, hasPermission = hasPermission, paused = paused,
            onTogglePause = { paused = !paused },
            onGrant = { launcher.launch(requiredBlePermissions()) },
            onEnableBluetooth = { enableBluetooth.launch(android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
        )
    }

    when (page) {
        AdminPage.HOME -> AdminHome(catalog.routes.firstOrNull { it.id == catalog.activeRouteId }, catalog.beacons.count { it.enabled }, catalog.beacons.size, catalog.beacons.count { it.profile != null }, statusBar, { page = AdminPage.ROUTES }, { page = AdminPage.BEACONS }, { page = AdminPage.REPLAY }, { page = AdminPage.GUIDED }, { page = AdminPage.CALIBRATE }, { page = AdminPage.USER_GUIDE }, onBack)
        AdminPage.ROUTES -> RouteList(catalog.routes, catalog.activeRouteId, { page = AdminPage.HOME }, { id -> editingRouteId = id; page = AdminPage.EDITOR }, { id -> editingRouteId = id; page = AdminPage.EDITOR }, RouteRepository::activate)
        AdminPage.BEACONS -> BeaconInventory(catalog.beacons, scan.targets, now, scanner, statusBar, { page = AdminPage.HOME }, RouteRepository::setBeaconEnabled)
        AdminPage.REPLAY -> ReplayScreen(onBack = { page = AdminPage.HOME })
        AdminPage.GUIDED -> GuidedTestScreen(scanner, statusBar, onBack = { page = AdminPage.HOME })
        AdminPage.CALIBRATE -> CalibrationWizardScreen(scanner, statusBar, onBack = { page = AdminPage.HOME })
        AdminPage.USER_GUIDE -> UserGuideScreen(scanner, statusBar, onBack = { page = AdminPage.HOME })
        AdminPage.EDITOR -> {
            val route = catalog.routes.firstOrNull { it.id == editingRouteId }
            if (route == null) page = AdminPage.ROUTES else RouteEditor(route, catalog.beacons, catalog.activeRouteId, { page = AdminPage.ROUTES }, RouteRepository::activate, RouteRepository::renameRoute, { RouteRepository.deleteRoute(it); page = AdminPage.ROUTES }, RouteRepository::addNode, RouteRepository::updateNode, RouteRepository::removeNode, RouteRepository::connect, RouteRepository::disconnect, RouteRepository.validate(route.id))
        }
    }
}

/** Tarjeta de acceso: icono, título y una línea que explica para qué sirve. Alto fijo. */
@Composable
private fun AdminEntry(step: Int?, title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().height(88.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            IconTile(icon, color, 52.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text((if (step != null) "$step · " else "") + title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, minLines = 2, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun AdminHome(active: SavedRoute?, usable: Int, total: Int, profiled: Int, statusBar: @Composable () -> Unit, onRoutes: () -> Unit, onBeacons: () -> Unit, onReplay: () -> Unit, onGuided: () -> Unit, onCalibrate: () -> Unit, onUserGuide: () -> Unit, onBack: () -> Unit) {
    ScreenScaffold(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenTitle("Administración")
        statusBar()
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            AdminStatTile("Ruta activa", active?.name ?: "Sin ruta", if (active != null) AdminColors.good else AdminColors.warn, Modifier.weight(1f))
            AdminStatTile("Balizas", "$usable de $total", if (usable >= 3) AdminColors.good else AdminColors.bad, Modifier.weight(1f))
            AdminStatTile("Calibradas", "$profiled de $total", if (profiled >= usable && usable > 0) AdminColors.good else AdminColors.warn, Modifier.weight(1f))
        }
        val place = com.example.openview.data.GuidePlaces.current()
        Text(
            "«Iniciar» (persona usuaria) guía por: " + (place?.let { p -> "${p.name} · " + p.spots.joinToString(" · ") { "${it.label} (${it.beacon.takeLast(2)})" } } ?: "nada configurado") +
                ". Se cambia eligiendo escenario y balizas en la Guía de usuario o la Prueba guiada.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth()
        )
        Text("Orden recomendado", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
        AdminEntry(1, "Calibrar balizas", "Un asistente con mapa te guía por distancias y ángulos.", Icons.Filled.Tune, MaterialTheme.colorScheme.primary, onCalibrate)
        AdminEntry(2, "Prueba guiada", "Recorre las posiciones, rota las placas y mide el acierto.", Icons.Filled.FiberManualRecord, AdminColors.bad, onGuided)
        AdminEntry(3, "Guía de usuario (modo prueba)", "Lo que oirá la persona ciega, con registros detallados.", Icons.Filled.RecordVoiceOver, MaterialTheme.colorScheme.tertiary, onUserGuide)
        AdminEntry(4, "Rutas", "Crea el plano de puntos y activa la ruta cuando esté lista.", Icons.Filled.Route, AdminColors.good, onRoutes)
        Text("Herramientas", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
        AdminEntry(null, "Balizas y registro", "Señal de cada baliza en vivo, activarlas y grabar CSV.", Icons.Filled.Radar, AdminColors.beacon, onBeacons)
        AdminEntry(null, "Replay", "Repite una prueba grabada con otro algoritmo o ajuste.", Icons.Filled.History, MaterialTheme.colorScheme.secondary, onReplay)
        SecondaryActionButton("Volver", icon = Icons.AutoMirrored.Filled.ArrowBack, onClick = onBack)
    }
}

/** Dato corto del resumen: etiqueta y valor en una línea cada uno, alto fijo. */
@Composable
private fun AdminStatTile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier.height(72.dp).background(color.copy(alpha = .12f), RoundedCornerShape(16.dp)).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

@Composable
private fun AdminSummary(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: androidx.compose.ui.graphics.Color) {
    Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = .12f)), modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(18.dp)) {
            Icon(icon, null, tint = color, modifier = Modifier.size(30.dp)); Spacer(Modifier.width(14.dp))
            Column { Text(label, style = MaterialTheme.typography.bodyMedium); Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun RouteList(routes: List<SavedRoute>, activeId: String, onBack: () -> Unit, onCreate: (String) -> Unit, onEdit: (String) -> Unit, onActivate: (String) -> List<String>) {
    var create by remember { mutableStateOf(false) }; var problems by remember { mutableStateOf<List<String>>(emptyList()) }
    ScreenScaffold(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenTitle("Rutas")
        Text("Cada ruta es un plano de nodos y pasillos. Puede tener 3, 6 o más balizas.", style = MaterialTheme.typography.bodyLarge)
        PrimaryActionButton("Crear una ruta", icon = Icons.Filled.Add, onClick = { create = true })
        if (problems.isNotEmpty()) ProblemsCard(problems)
        routes.forEach { route ->
            val active = route.id == activeId
            Card(colors = CardDefaults.cardColors(containerColor = if (active) SuccessGreen.copy(alpha = .12f) else MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (active) Icons.Filled.CheckCircle else Icons.Filled.Route, null, tint = if (active) SuccessGreen else MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) { Text(route.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("${route.nodes.size} nodos · ${route.edges.size} pasillos" + if (active) " · ACTIVA" else "", style = MaterialTheme.typography.bodyMedium) }
                    }
                    OutlinedButton(onClick = { onEdit(route.id) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.Edit, null); Spacer(Modifier.width(8.dp)); Text("Editar ruta") }
                    if (!active) Button(onClick = { problems = onActivate(route.id) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Validar y activar") }
                }
            }
        }
        SecondaryActionButton("Volver", icon = Icons.AutoMirrored.Filled.ArrowBack, onClick = onBack)
    }
    if (create) RouteNameDialog("Crear ruta", "Nombre de la ruta", { create = false }) { name -> create = false; onCreate(RouteRepository.createRoute(name)) }
}

@Composable
private fun BeaconInventory(beacons: List<ManagedBeacon>, scanned: List<BleDeviceInfo>, now: Long, scanner: BleScanner, statusBar: @Composable () -> Unit, onBack: () -> Unit, onSetEnabled: (String, Boolean) -> Unit) {
    val found = scanned.mapNotNull { it.name?.let { name -> name to it } }.toMap()
    var calibrating by remember { mutableStateOf<String?>(null) }
    ScreenScaffold(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenTitle("Balizas")
        statusBar()
        Text("Una baliza desactivada no participa en la ubicación ni permite activar rutas que dependan de ella.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        beacons.sortedBy { it.name }.forEach {
            val present = found[it.name]?.let { d -> now - d.lastSeenElapsedMs <= PRESENT_WINDOW_MS } == true
            BeaconCard(it, found[it.name], present, onSetEnabled, onCalibrate = { name -> calibrating = name })
        }
        RecordingPanel()
        SecondaryActionButton("Volver", icon = Icons.AutoMirrored.Filled.ArrowBack, onClick = onBack)
    }
    calibrating?.let { name ->
        CalibrationDialog(
            scanner = scanner,
            beaconName = name,
            currentCalibration = beacons.firstOrNull { it.name == name }?.measuredPowerAt1m,
            onDismiss = { calibrating = null },
            onSave = { value -> RouteRepository.setBeaconCalibration(name, value); calibrating = null }
        )
    }
}

/**
 * Controla una sesión de [BleSessionLogger]: nombrarla, ver cuántas lecturas
 * lleva, anotar contexto en el momento y exportar el CSV al terminar.
 *
 * Grabar no cambia nada del escaneo ni de la navegación: solo observa las
 * lecturas que ya fluyen por `OpenViewApp` (ver Navigation.kt) mientras esta
 * pantalla, u otra, sigue escaneando.
 */
@Composable
private fun RecordingPanel() {
    val session by BleSessionLogger.state.collectAsState()
    var nameField by remember { mutableStateOf("") }
    var conditionsField by remember { mutableStateOf("") }
    var noteField by remember { mutableStateOf("") }
    var tick by remember { mutableLongStateOf(0L) }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(session.isRecording) {
        while (session.isRecording) {
            tick = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.FiberManualRecord, null, tint = if (session.isRecording) FailureRed else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text("Registro de calibración", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            }
            Text(
                "Guarda el RSSI crudo y suavizado de cada baliza mientras caminas, para revisarlo después o compararlo entre pruebas.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!session.isRecording) {
                OutlinedTextField(nameField, { nameField = it }, label = { Text("Nombre de la sesión (opcional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(conditionsField, { conditionsField = it }, label = { Text("Condiciones (opcional), ej. \"hora punta\"") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                PrimaryActionButton("Iniciar registro", icon = Icons.Filled.FiberManualRecord, onClick = { BleSessionLogger.start(nameField, conditionsField) })
            } else {
                // Leer 'tick' aquí hace que Compose recomponga este texto cada
                // segundo mientras graba (lo actualiza el LaunchedEffect de arriba).
                val elapsed = if (tick >= 0L) BleSessionLogger.elapsedSeconds() else 0.0
                SlotText(
                    "Grabando «${session.sessionName}» · ${"%.0f".format(elapsed)} s · ${session.rowCount} lecturas · ${session.trueMarkCount} verdades marcadas",
                    minLines = 3, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold
                )
                CalibrationMap(ActiveGraph.graph)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(noteField, { noteField = it }, label = { Text("Nota, ej. \"puerta abierta\"") }, singleLine = true, modifier = Modifier.weight(1f))
                    IconButton(onClick = { BleSessionLogger.addNote(noteField); noteField = "" }) { Icon(Icons.Filled.Add, "Agregar nota") }
                }
                PrimaryActionButton(
                    "Detener y exportar CSV", icon = Icons.Filled.Stop,
                    containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError,
                    onClick = { BleSessionLogger.stop(); BleSessionLogger.exportAndShare(context) }
                )
            }
        }
    }
}

/** Escala + origen para convertir metros del grafo a píxeles del lienzo, o viceversa. */
private data class MapTransform(val scale: Float, val originX: Float, val originY: Float)

private fun computeMapTransform(canvasW: Float, canvasH: Float, spanX: Double, spanY: Double): MapTransform {
    val padding = 36f
    val w = (canvasW - 2 * padding).coerceAtLeast(1f)
    val h = (canvasH - 2 * padding).coerceAtLeast(1f)
    val scale = minOf(w / spanX.toFloat(), h / spanY.toFloat())
    val originX = (canvasW - spanX.toFloat() * scale) / 2f
    val originY = (canvasH - spanY.toFloat() * scale) / 2f
    return MapTransform(scale, originX, originY)
}

/**
 * Mapa de calibración del grafo activo. Combina dos cosas sobre el mismo
 * lienzo:
 *
 * - **En vivo** (desde [LivePosition], que alimenta `Navigation.kt`): un
 *   círculo de distancia estimada por cada baliza visible, el centroide
 *   ponderado antes del snap, un anillo sobre el nodo detectado (coloreado
 *   según [BleSignal]) y un rastro de las últimas posiciones.
 * - **Verdad de terreno**: tocar el mapa llama a
 *   [BleSessionLogger.markTruePosition], ajustando ("snap") al nodo más
 *   cercano si el toque cae a menos de 0.5 m de una baliza.
 *
 * Ver las dos cosas juntas es justo lo que permite distinguir baliza,
 * teléfono o algoritmo: si el círculo de una baliza nunca llega a cruzar por
 * donde de verdad estás parado, el problema es esa baliza o su calibración,
 * no la fusión de BlePositioner.
 */
@Composable
private fun CalibrationMap(graph: NavGraph) {
    val live by LivePosition.current.collectAsState()
    var lastMarked by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var lastMarkedLabel by remember { mutableStateOf("") }
    val trail = remember { mutableStateListOf<Pair<Double, Double>>() }
    val nodes = graph.nodes
    if (nodes.isEmpty()) return

    LaunchedEffect(live) {
        val x = live?.computedX
        val y = live?.computedY
        if (x != null && y != null) {
            trail.add(x to y)
            if (trail.size > 40) trail.removeAt(0)
        }
    }

    val minX = nodes.minOf { it.x }
    val maxX = nodes.maxOf { it.x }
    val minY = nodes.minOf { it.y }
    val maxY = nodes.maxOf { it.y }
    val spanX = (maxX - minX).coerceAtLeast(1.0)
    val spanY = (maxY - minY).coerceAtLeast(1.0)

    val signalColor = when (live?.signal) {
        BleSignal.STABLE -> Color(0xFF1E8E5A)
        BleSignal.WEAK -> Color(0xFFB8720A)
        BleSignal.LOST -> Color(0xFFB23A2E)
        null -> Color(0xFF8592A8)
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Mapa en vivo", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            SignalPill(live?.signal)
        }
        Text(
            "Los círculos son la distancia que cree ver cada baliza. Toca el mapa cuando estés físicamente parado en un punto, para comparar contra el punto estimado (el anillo de color).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                .clipToBounds()
                .pointerInput(nodes) {
                    detectTapGestures { offset ->
                        val t = computeMapTransform(size.width.toFloat(), size.height.toFloat(), spanX, spanY)
                        val gx = (offset.x - t.originX) / t.scale + minX
                        val gy = maxY - (offset.y - t.originY) / t.scale

                        val nearest = nodes.minByOrNull { hypot(it.x - gx, it.y - gy) }
                        val snapped = nearest != null && hypot(nearest.x - gx, nearest.y - gy) <= 0.5
                        val fx = if (snapped) nearest!!.x else gx
                        val fy = if (snapped) nearest!!.y else gy

                        BleSessionLogger.markTruePosition(fx, fy)
                        lastMarked = fx to fy
                        lastMarkedLabel = if (snapped) nearest!!.label else "punto libre"
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val t = computeMapTransform(size.width, size.height, spanX, spanY)
                fun toScreen(x: Double, y: Double) = Offset(
                    t.originX + (x - minX).toFloat() * t.scale,
                    t.originY + (maxY - y).toFloat() * t.scale
                )

                // Pasillos del grafo.
                graph.edges.forEach { edge ->
                    val a = nodes.firstOrNull { it.id == edge.a }
                    val b = nodes.firstOrNull { it.id == edge.b }
                    if (a != null && b != null) {
                        drawLine(Color.Gray, toScreen(a.x, a.y), toScreen(b.x, b.y), strokeWidth = 4f)
                    }
                }

                // Círculo de distancia estimada por cada baliza visible ahora mismo.
                live?.readings?.forEach { reading ->
                    val node = nodes.firstOrNull { it.id == reading.nodeId } ?: return@forEach
                    val center = toScreen(node.x, node.y)
                    drawCircle(
                        Color(0xFFB8720A).copy(alpha = 0.35f),
                        radius = (reading.estimatedDistanceM * t.scale).toFloat(),
                        center = center,
                        style = Stroke(width = 2f)
                    )
                }

                // Rastro reciente del centroide ponderado (antes del snap), más tenue cuanto más viejo.
                trail.forEachIndexed { index, (x, y) ->
                    val alpha = 0.12f + 0.5f * (index + 1) / trail.size
                    drawCircle(Color(0xFF8592A8).copy(alpha = alpha), radius = 5f, center = toScreen(x, y))
                }

                // Balizas del grafo.
                nodes.forEach { node ->
                    val p = toScreen(node.x, node.y)
                    drawCircle(Color(0xFFB8720A), radius = 14f, center = p)
                    drawContext.canvas.nativeCanvas.drawText(
                        node.label, p.x, p.y - 20f,
                        android.graphics.Paint().apply {
                            textAlign = android.graphics.Paint.Align.CENTER
                            textSize = 30f
                            isAntiAlias = true
                        }
                    )
                }

                // Centroide ponderado en vivo, antes del snap al nodo.
                val cx = live?.computedX
                val cy = live?.computedY
                if (cx != null && cy != null) {
                    val p = toScreen(cx, cy)
                    drawCircle(Color(0xFF8592A8), radius = 8f, center = p)
                }

                // Nodo detectado (snap), coloreado según la calidad de señal.
                live?.nodeId?.let { id ->
                    val node = nodes.firstOrNull { it.id == id }
                    if (node != null) {
                        val p = toScreen(node.x, node.y)
                        drawCircle(signalColor, radius = 20f, center = p, style = Stroke(width = 3f))
                    }
                }

                // Verdad de terreno marcada a mano.
                lastMarked?.let { (x, y) ->
                    val p = toScreen(x, y)
                    drawCircle(Color(0xFF0B5C86), radius = 10f, center = p)
                    drawCircle(Color(0xFF0B5C86), radius = 20f, center = p, style = Stroke(width = 3f))
                }
            }
        }
        SlotText(
            lastMarked?.let { (x, y) -> "Última verdad marcada: (${"%.2f".format(x)}, ${"%.2f".format(y)}) m · $lastMarkedLabel" },
            minLines = 1, style = MaterialTheme.typography.bodySmall
        )
        LiveReadout(live, nodes.mapNotNull { it.beaconName }.distinct().sorted())
    }
}

@Composable
private fun SignalPill(signal: BleSignal?) {
    val (color, label) = when (signal) {
        BleSignal.STABLE -> SuccessGreen to "estable"
        BleSignal.WEAK -> Color(0xFFB8720A) to "débil"
        BleSignal.LOST -> FailureRed to "perdida"
        null -> MaterialTheme.colorScheme.onSurfaceVariant to "sin datos"
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.background(color.copy(alpha = .14f), RoundedCornerShape(100)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = color, fontWeight = FontWeight.Bold)
    }
}

/** Lista compacta de RSSI crudo/suavizado y distancia por baliza visible, para ver de un vistazo cuál tiembla. */
@Composable
private fun LiveReadout(live: BlePositioner.Result?, names: List<String>) {
    val byName = live?.readings.orEmpty().associateBy { it.beaconName }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (names.isEmpty()) {
            Text("El grafo activo no tiene balizas.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        names.forEach { n ->
            val r = byName[n]
            val dist = r?.let { if (it.estimatedDistanceM < 1) "${(it.estimatedDistanceM * 100).toInt()} cm" else "%.1f m".format(it.estimatedDistanceM) } ?: "—"
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(26.dp)) {
                Text(n, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
                Text(r?.let { "${it.rssiRaw} → ${it.rssiSmoothed.toInt()} dBm" } ?: "—", style = NumberStyle.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(116.dp), textAlign = TextAlign.End, maxLines = 1, softWrap = false)
                Text(dist, style = NumberStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold), modifier = Modifier.width(64.dp), textAlign = TextAlign.End, maxLines = 1, softWrap = false)
                Spacer(Modifier.width(8.dp))
                SignalBars(r?.rssiRaw)
            }
        }
    }
}

@Composable
private fun BeaconCard(beacon: ManagedBeacon, device: BleDeviceInfo?, present: Boolean, onSetEnabled: (String, Boolean) -> Unit, onCalibrate: (String) -> Unit) {
    val color = when { !beacon.enabled -> MaterialTheme.colorScheme.onSurfaceVariant; present -> AdminColors.good; else -> AdminColors.bad }
    AdminCard(container = color.copy(alpha = .06f), modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            BeaconNumberBadge(beacon.name, color, 48.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(beacon.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(
                    when { !beacon.enabled -> "No se usará en rutas"; present && device != null -> "Detectada · ${device.signalLabel}"; else -> "No detectada" },
                    style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            RssiReadout(if (present && beacon.enabled) device?.rssi else null, big = false, color = color)
            Switch(checked = beacon.enabled, onCheckedChange = { onSetEnabled(beacon.name, it) })
        }
        val stale = beacon.profile?.isStaleFor(device?.txLevel) == true
        SlotText(
            (if (stale) "⚠ Su potencia cambió desde la calibración: vuelve a calibrarla. " else "") +
            (beacon.measuredPowerAt1m?.let { "Calibrada: $it dBm a 1 m" } ?: "Sin calibrar (usa la referencia global)") +
                (beacon.profile?.let { " · perfil ${it.quality.label}" } ?: "") +
                (device?.fwVersion?.let { " · firmware v$it" } ?: "") +
                (device?.txLevel?.let { " · TX ${-12 + 3 * it} dBm" } ?: ""),
            minLines = 2, style = MaterialTheme.typography.bodySmall, color = if (stale) AdminColors.bad else MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(onClick = { onCalibrate(beacon.name) }, enabled = present, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Icon(Icons.Filled.Tune, null); Spacer(Modifier.width(8.dp))
            Text(if (present) "Calibrar a 1 metro (rápido)" else "Acércate para calibrarla", maxLines = 1)
        }
    }
}

private enum class CalibrationPhase { READY, SAMPLING, DONE, FAILED }
private const val CALIBRATION_SECONDS = 20
private const val CALIBRATION_MIN_SAMPLES = 30
private const val CALIBRATION_MAX_SD_DB = 4.0

/**
 * Asistente "párate a 1 m y toca calibrar": recoge durante [CALIBRATION_SECONDS] s las MUESTRAS
 * reales de una baliza específica (una por anuncio recibido, no una lectura repetida cada 200 ms),
 * toma la MEDIANA y la guarda como su referencia a 1 m ([ManagedBeacon.measuredPowerAt1m]),
 * reemplazando la constante global solo para esa baliza.
 *
 * La mediana no se deja arrastrar por picos aislados de ±10 dB, y si la desviación es alta
 * (> [CALIBRATION_MAX_SD_DB] dB) avisa de que la medición fue inestable.
 * También se puede calibrar todas de una vez con la Prueba guiada (paso "Guardar calibración").
 */
@Composable
private fun CalibrationDialog(scanner: BleScanner, beaconName: String, currentCalibration: Int?, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var phase by remember { mutableStateOf(CalibrationPhase.READY) }
    var samples by remember { mutableStateOf(listOf<Int>()) }
    var collected by remember { mutableIntStateOf(0) }
    var secondsLeft by remember { mutableIntStateOf(CALIBRATION_SECONDS) }

    LaunchedEffect(phase) {
        if (phase == CalibrationPhase.SAMPLING) {
            val readings = mutableListOf<Int>()
            val job = launch {
                scanner.samples.collect { if (it.name == beaconName) { readings.add(it.rssi); collected = readings.size } }
            }
            val endAt = SystemClock.elapsedRealtime() + CALIBRATION_SECONDS * 1000L
            while (SystemClock.elapsedRealtime() < endAt) {
                secondsLeft = ((endAt - SystemClock.elapsedRealtime() + 999) / 1000).toInt()
                delay(200)
            }
            job.cancel()
            samples = readings.toList()
            phase = if (readings.size < CALIBRATION_MIN_SAMPLES) CalibrationPhase.FAILED else CalibrationPhase.DONE
        }
    }

    val sorted = samples.sorted()
    val median = if (sorted.isEmpty()) 0 else sorted[sorted.size / 2]
    val sd = if (samples.size < 2) 0.0 else kotlin.math.sqrt(samples.sumOf { (it - samples.average()) * (it - samples.average()) } / (samples.size - 1))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Calibrar $beaconName") },
        text = {
            Column(Modifier.heightIn(min = 150.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (phase) {
                    CalibrationPhase.READY -> {
                        Text("Párate exactamente a 1 metro de $beaconName, con el móvil en la mano como lo llevarás normalmente, y mantente quieto durante $CALIBRATION_SECONDS segundos.")
                        currentCalibration?.let {
                            Text("Calibración actual: $it dBm a 1 m.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    CalibrationPhase.SAMPLING -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Midiendo… no te muevas ($secondsLeft s · $collected muestras)")
                        }
                    }
                    CalibrationPhase.DONE -> {
                        Text("Nueva referencia: $median dBm a 1 m (mediana de ${samples.size} muestras, desviación ${"%.1f".format(sd)} dB).", fontWeight = FontWeight.Bold)
                        if (sd > CALIBRATION_MAX_SD_DB) {
                            Text("La medición fue inestable (desviación alta). Conviene repetirla sin moverte ni cambiar la orientación del móvil.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        currentCalibration?.let { Text("Antes: $it dBm.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text("Se aplicará solo a $beaconName; el resto de las balizas no cambian.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    CalibrationPhase.FAILED -> {
                        Text("Solo se recibieron ${samples.size} muestras de $beaconName (mínimo $CALIBRATION_MIN_SAMPLES).", color = MaterialTheme.colorScheme.error)
                        Text("Confirma que esté encendida, que estés cerca y que el escaneo esté activo, y vuelve a intentar.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            when (phase) {
                CalibrationPhase.READY -> Button(onClick = { collected = 0; secondsLeft = CALIBRATION_SECONDS; phase = CalibrationPhase.SAMPLING }) { Text("Empezar medición") }
                CalibrationPhase.SAMPLING -> {}
                CalibrationPhase.DONE -> Button(onClick = { onSave(median) }) { Text("Guardar") }
                CalibrationPhase.FAILED -> Button(onClick = { phase = CalibrationPhase.READY }) { Text("Reintentar") }
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, enabled = phase != CalibrationPhase.SAMPLING) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
private fun RouteEditor(route: SavedRoute, beacons: List<ManagedBeacon>, activeId: String, onBack: () -> Unit, onActivate: (String) -> List<String>, onRename: (String, String) -> Unit, onDelete: (String) -> Unit, onAdd: (String, String, String, Double, Double) -> Unit, onUpdate: (String, String, String, String, Double, Double) -> Unit, onRemove: (String, String) -> Unit, onConnect: (String, String, String) -> Unit, onDisconnect: (String, NavEdge) -> Unit, validation: List<String>) {
    var rename by remember { mutableStateOf(false) }; var nodeDialog by remember { mutableStateOf<NavNode?>(null) }; var adding by remember { mutableStateOf(false) }; var linkFrom by remember { mutableStateOf<String?>(null) }; var activationProblems by remember { mutableStateOf<List<String>>(emptyList()) }; var deleting by remember { mutableStateOf(false) }
    val errors = if (activationProblems.isNotEmpty()) activationProblems else validation
    ScreenScaffold(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.Route, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { ScreenTitle(route.name); Text(if (route.id == activeId) "Ruta activa" else "Borrador", style = MaterialTheme.typography.bodyMedium) }; IconButton(onClick = { rename = true }) { Icon(Icons.Filled.Edit, "Cambiar nombre") } }
        Text("Agrega puntos en orden. Se conectan automáticamente; agrega conexiones extra para desvíos o atajos.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (errors.isNotEmpty()) ProblemsCard(errors) else AdminSummary("Lista para activar", "${route.nodes.size} nodos conectados", Icons.Filled.CheckCircle, SuccessGreen)
        PrimaryActionButton("Agregar nodo", icon = Icons.Filled.Add, onClick = { adding = true })
        if (route.id != activeId) SecondaryActionButton("Validar y activar", icon = Icons.Filled.PlayArrow, onClick = { activationProblems = onActivate(route.id) })
        if (linkFrom != null) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) { Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) { Text("Elige otro nodo para conectarlo.", modifier = Modifier.weight(1f)); IconButton(onClick = { linkFrom = null }) { Icon(Icons.Filled.Close, "Cancelar conexión") } } }
        Text("Nodos", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        route.nodes.forEachIndexed { index, node -> NodeCard(index + 1, node, beacons.firstOrNull { it.name == node.beaconName }?.enabled != false, linkFrom, { nodeDialog = node }, { onRemove(route.id, node.id) }) { from -> if (linkFrom == null) linkFrom = from else if (linkFrom != node.id) { onConnect(route.id, linkFrom!!, node.id); linkFrom = null } } }
        if (route.edges.isNotEmpty()) { Text("Pasillos (${route.edges.size})", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }); route.edges.forEach { edge -> val a = route.nodes.firstOrNull { it.id == edge.a }?.label ?: edge.a; val b = route.nodes.firstOrNull { it.id == edge.b }?.label ?: edge.b; Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, CircleShape).padding(start = 14.dp, end = 4.dp, top = 5.dp, bottom = 5.dp)) { Icon(Icons.Filled.Link, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("$a ↔ $b", modifier = Modifier.weight(1f)); IconButton(onClick = { onDisconnect(route.id, edge) }) { Icon(Icons.Filled.Close, "Eliminar pasillo") } } } }
        if (route.id != activeId) OutlinedButton(onClick = { deleting = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.Delete, null); Spacer(Modifier.width(8.dp)); Text("Eliminar ruta") }
        SecondaryActionButton("Volver a rutas", icon = Icons.AutoMirrored.Filled.ArrowBack, onClick = onBack)
    }
    if (rename) RouteNameDialog("Renombrar ruta", route.name, { rename = false }) { name -> rename = false; onRename(route.id, name) }
    if (adding) NodeDialog("Agregar nodo", null, beacons, { adding = false }) { label, beacon, x, y -> adding = false; onAdd(route.id, label, beacon, x, y) }
    nodeDialog?.let { node -> NodeDialog("Editar nodo", node, beacons, { nodeDialog = null }) { label, beacon, x, y -> nodeDialog = null; onUpdate(route.id, node.id, label, beacon, x, y) } }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("¿Eliminar ${route.name}?") }, text = { Text("Se eliminarán sus nodos y pasillos de este teléfono.") }, confirmButton = { Button(onClick = { deleting = false; onDelete(route.id) }) { Text("Eliminar") } }, dismissButton = { OutlinedButton(onClick = { deleting = false }) { Text("Cancelar") } })
}

@Composable
private fun NodeCard(index: Int, node: NavNode, enabled: Boolean, linkingFrom: String?, onEdit: () -> Unit, onRemove: () -> Unit, onLink: (String) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = if (enabled) MaterialTheme.colorScheme.surface else FailureRed.copy(alpha = .10f)), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(30.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) { Text(index.toString(), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold) }; Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(node.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("${node.beaconName ?: "Sin baliza"} · x ${node.x}, y ${node.y}", style = MaterialTheme.typography.bodyMedium, color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else FailureRed) }; IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, "Editar nodo") }; IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, "Eliminar nodo") } }
        val text = when { linkingFrom == null -> "Conectar con…"; linkingFrom == node.id -> "Punto de origen"; else -> "Conectar aquí" }
        OutlinedButton(onClick = { if (linkingFrom != node.id) onLink(node.id) }, enabled = linkingFrom != node.id, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.Link, null); Spacer(Modifier.width(8.dp)); Text(text) }
    } }
}

@Composable
private fun ProblemsCard(problems: List<String>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive }) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(8.dp)); Text("Revisa antes de activar", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }; problems.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) } } }
}

@Composable
private fun RouteNameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { OutlinedTextField(value, { value = it }, label = { Text("Nombre") }, singleLine = true) }, confirmButton = { Button(onClick = { onSave(value) }) { Text("Guardar") } }, dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancelar") } })
}

@Composable
private fun NodeDialog(title: String, initial: NavNode?, beacons: List<ManagedBeacon>, onDismiss: () -> Unit, onSave: (String, String, Double, Double) -> Unit) {
    var label by remember { mutableStateOf(initial?.label ?: "") }; var beacon by remember { mutableStateOf(initial?.beaconName ?: beacons.firstOrNull { it.enabled }?.name ?: "NAV_BLE_01") }; var x by remember { mutableStateOf(initial?.x?.toString() ?: "0") }; var y by remember { mutableStateOf(initial?.y?.toString() ?: "0") }; var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Usa el nombre exacto de la baliza. Puedes reasignar un nodo si una baliza falla.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(label, { label = it }, label = { Text("Nombre del punto") }, singleLine = true)
        OutlinedTextField(beacon, { beacon = it.uppercase() }, label = { Text("Baliza, ej. NAV_BLE_04") }, singleLine = true)
        Text("Habilitadas: ${beacons.filter { it.enabled }.joinToString { it.name }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(x, { x = it }, label = { Text("X (m)") }, modifier = Modifier.weight(1f), singleLine = true); OutlinedTextField(y, { y = it }, label = { Text("Y (m)") }, modifier = Modifier.weight(1f), singleLine = true) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    } }, confirmButton = { Button(onClick = { val xx = x.replace(',', '.').toDoubleOrNull(); val yy = y.replace(',', '.').toDoubleOrNull(); if (beacon.isBlank()) error = "Indica la baliza." else if (xx == null || yy == null) error = "X e Y deben ser números." else onSave(label, beacon, xx, yy) }) { Text("Guardar") } }, dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancelar") } })
}

/**
 * Vuelve a correr un log grabado (de esta sesión o un CSV cargado) con un
 * `alpha` distinto, sin caminar de nuevo el pasillo. Ver [ReplayEngine].
 */
@Composable
private fun ReplayScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var rows by remember { mutableStateOf<List<BleLogRow>>(emptyList()) }
    var sourceLabel by remember { mutableStateOf("") }
    var alpha by remember { mutableFloatStateOf(0.5f) }
    var margin by remember { mutableFloatStateOf(4f) }
    var algorithm by remember { mutableStateOf(com.example.openview.nav.Algorithm.V3) }
    var frameIndex by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = BleLogFile.readText(context, uri)
        val parsed = text?.let(BleLogFile::parse)
        if (parsed.isNullOrEmpty()) {
            loadError = "No se pudo leer ese archivo como un CSV de OpenView."
        } else {
            rows = parsed
            sourceLabel = uri.lastPathSegment?.substringAfterLast('/') ?: "archivo cargado"
            frameIndex = 0
            loadError = null
        }
    }

    // Contexto del replay: si el log trae fila LAYOUT (prueba guiada) se reconstruye ese escenario, esa rotación
    // y sus huellas; si no, se usa la ruta activa.
    val setupsState by com.example.openview.data.TestSetupRepository.state.collectAsState()
    val layoutInfo = remember(rows) { com.example.openview.logging.LayoutInfo.fromRows(rows) }
    val layoutSetup = layoutInfo?.let { li -> setupsState.setups.firstOrNull { it.id == li.setupId } }
    val layoutRotation = layoutInfo?.let { li -> layoutSetup?.let { li.rotationFor(it) } }
    val graph = if (layoutSetup != null && layoutRotation != null) com.example.openview.data.TestLayout.graph(layoutSetup, layoutRotation) else ActiveGraph.graph
    val path: List<String>? = if (layoutRotation != null) graph.nodes.map { it.id } else ActiveGraph.expectedPath ?: graph.nodes.map { it.id }.takeIf { it.size >= 2 }
    val layoutKey = if (layoutSetup != null && layoutInfo != null) layoutInfo.keyFor(layoutSetup) else null
    val storedLaps by com.example.openview.data.FingerprintRepository.laps.collectAsState()
    val fingerprints = layoutKey?.let { com.example.openview.data.FingerprintRepository.setFor(it) }
    val frames = remember(rows, alpha, margin, algorithm, layoutKey, storedLaps) {
        if (rows.isEmpty()) emptyList()
        else ReplayEngine.run(
            rows, TrackerParams(alpha = alpha.toDouble(), marginDb = margin.toDouble()), graph, RouteRepository::calibrationFor,
            algorithm, path, fingerprints, RouteRepository::profileFor
        )
    }
    LaunchedEffect(frames.size) { if (frameIndex >= frames.size) frameIndex = (frames.size - 1).coerceAtLeast(0) }
    LaunchedEffect(playing, frames.size) {
        while (playing && frames.isNotEmpty()) {
            delay(150)
            frameIndex = (frameIndex + 1) % frames.size
        }
    }

    ScreenScaffold(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenTitle("Replay y sandbox")
        Text(
            "Carga una prueba grabada y prueba distintos valores de suavizado sobre los mismos datos, sin volver a caminar.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Cargar CSV")
            }
            OutlinedButton(
                onClick = {
                    val last = BleSessionLogger.currentRows()
                    if (last.isEmpty()) { loadError = "Todavía no hay ninguna grabación en esta sesión de la app." }
                    else { rows = last; sourceLabel = "última grabación de esta sesión"; frameIndex = 0; loadError = null }
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.History, null); Spacer(Modifier.width(8.dp)); Text("Última grabación")
            }
        }
        loadError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

        if (rows.isEmpty()) {
            Text(
                "Carga un CSV que hayas exportado antes, o usa la última grabación si acabas de detener una sesión en Balizas.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text("Fuente: $sourceLabel · ${rows.size} filas · ${frames.size} instantes", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Suavizado (alpha): ${"%.2f".format(alpha)}", style = MaterialTheme.typography.bodyMedium)
                Text("Más bajo = más estable pero más lento para reaccionar. Más alto = más rápido pero más nervioso.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(value = alpha, onValueChange = { alpha = it }, valueRange = 0.05f..0.95f)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Histéresis (margen para cambiar de nodo): ${"%.0f".format(margin)} dB", style = MaterialTheme.typography.bodyMedium)
                Text("Más alto = el nodo cambia menos, pero tarda más en reaccionar. Usa la calibración por baliza guardada en este teléfono.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(value = margin, onValueChange = { margin = it }, valueRange = 0f..12f)
            }

            Text(
                if (layoutInfo != null && layoutRotation != null)
                    "Prueba guiada: ${layoutInfo.setupId} · ${layoutInfo.rotationId} · " + layoutInfo.beaconAt.entries.joinToString(" ") { "${it.key}=${it.value.takeLast(2)}" } +
                        " · parada ${layoutInfo.stay} · " + (if (fingerprints != null) "huellas: ${fingerprints.laps} vuelta(s)" else "sin huellas guardadas para esta disposición")
                else "Log sin datos de escenario: se usa la ruta activa.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                com.example.openview.nav.Algorithm.entries.forEach { a ->
                    val label = if (a == com.example.openview.nav.Algorithm.V3) "v3 (ruta + huella)" else "v2 (más fuerte + histéresis)"
                    if (a == algorithm) Button(onClick = {}, modifier = Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.labelMedium) }
                    else OutlinedButton(onClick = { algorithm = a }, modifier = Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.labelMedium) }
                }
            }
            ReplayAccuracy(rows, graph, frames)
            ErrorSummary(frames)
            ReplayMap(graph, frames, frameIndex)

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = { playing = !playing }, enabled = frames.isNotEmpty()) {
                    Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pausar" else "Reproducir")
                }
                Slider(
                    value = frameIndex.toFloat(),
                    onValueChange = { playing = false; frameIndex = it.roundToInt() },
                    valueRange = 0f..(frames.size - 1).coerceAtLeast(0).toFloat(),
                    modifier = Modifier.weight(1f)
                )
            }
            frames.getOrNull(frameIndex)?.let { f ->
                val errorText = f.errorM?.let { " · error: %.2f m".format(it) } ?: ""
                Text("t = ${"%.1f".format(f.timestampS)} s · señal: ${f.signal.label}$errorText", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        SecondaryActionButton("Volver", icon = Icons.AutoMirrored.Filled.ArrowBack, onClick = onBack)
    }
}

/** Acierto por punto del replay, según las marcas ARRIVE/DEPART del log (solo si el log las trae). */
@Composable
private fun ReplayAccuracy(rows: List<BleLogRow>, graph: NavGraph, frames: List<ReplayFrame>) {
    val beaconOfNode = graph.nodes.mapNotNull { n -> n.beaconName?.let { n.id to it } }.toMap()
    val summary = remember(rows, frames) {
        com.example.openview.logging.SessionAnalyzer.analyze(rows, beaconOfNode, frames.map { com.example.openview.logging.Detection(it.timestampS, it.nodeId) })
    }
    if (summary.points.isEmpty()) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Acierto por punto (marcas del log)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "Medio: %.0f %% · cambios: %d · %s".format(summary.meanAccuracyPct, summary.totalFlips,
                    if (summary.falseArrival) "falso destino %.1f s".format(summary.wrongDestinationS) else "sin falso destino"),
                style = MaterialTheme.typography.bodyMedium
            )
            summary.points.forEach { p ->
                val label = graph.nodes.firstOrNull { it.id == p.nodeId }?.let { "${it.label} (${it.beaconName?.takeLast(2)})" } ?: p.nodeId
                Text("· $label: %.0f %%".format(p.accuracyPct) + (p.wrongNode?.let { " · confunde con $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ErrorSummary(frames: List<ReplayFrame>) {
    val errors = frames.mapNotNull { it.errorM }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Error contra verdad de terreno", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (errors.isEmpty()) {
                Text("Este log no tiene marcas de verdad de terreno: se puede ver la trayectoria, pero no medir error.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(
                    "Promedio: %.2f m · máximo: %.2f m · con este alpha en %d marcas".format(errors.average(), errors.max(), errors.size),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/** Mismo estilo visual que el mapa en vivo de calibración, pero mostrando la trayectoria completa del replay y el instante actual. */
@Composable
private fun ReplayMap(graph: NavGraph, frames: List<ReplayFrame>, currentIndex: Int) {
    val nodes = graph.nodes
    if (nodes.isEmpty()) return
    val minX = nodes.minOf { it.x }
    val maxX = nodes.maxOf { it.x }
    val minY = nodes.minOf { it.y }
    val maxY = nodes.maxOf { it.y }
    val spanX = (maxX - minX).coerceAtLeast(1.0)
    val spanY = (maxY - minY).coerceAtLeast(1.0)
    val current = frames.getOrNull(currentIndex)
    val signalColor = when (current?.signal) {
        BleSignal.STABLE -> Color(0xFF1E8E5A)
        BleSignal.WEAK -> Color(0xFFB8720A)
        BleSignal.LOST, null -> Color(0xFFB23A2E)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .clipToBounds()
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val t = computeMapTransform(size.width, size.height, spanX, spanY)
            fun toScreen(x: Double, y: Double) = Offset(
                t.originX + (x - minX).toFloat() * t.scale,
                t.originY + (maxY - y).toFloat() * t.scale
            )

            graph.edges.forEach { edge ->
                val a = nodes.firstOrNull { it.id == edge.a }
                val b = nodes.firstOrNull { it.id == edge.b }
                if (a != null && b != null) drawLine(Color.Gray, toScreen(a.x, a.y), toScreen(b.x, b.y), strokeWidth = 4f)
            }
            nodes.forEach { node -> drawCircle(Color(0xFFB8720A), radius = 12f, center = toScreen(node.x, node.y)) }

            // Trayectoria completa del replay, tenue.
            frames.forEach { f ->
                val x = f.computedX; val y = f.computedY
                if (x != null && y != null) drawCircle(Color(0xFF8592A8).copy(alpha = 0.25f), radius = 3f, center = toScreen(x, y))
            }
            // Todas las marcas de verdad de terreno del log, como referencia fija.
            frames.mapNotNull { f -> if (f.trueX != null && f.trueY != null) f.trueX to f.trueY else null }
                .distinct()
                .forEach { (x, y) -> drawCircle(Color(0xFF0B5C86), radius = 6f, center = toScreen(x, y)) }

            // Instante actual del scrub/reproducción.
            val cx = current?.computedX
            val cy = current?.computedY
            if (cx != null && cy != null) {
                val p = toScreen(cx, cy)
                drawCircle(signalColor, radius = 9f, center = p)
                drawCircle(signalColor, radius = 16f, center = p, style = Stroke(width = 2.5f))
                if (current.trueX != null && current.trueY != null) {
                    drawLine(Color(0xFF0B5C86), p, toScreen(current.trueX, current.trueY), strokeWidth = 2f)
                }
            }
        }
    }
}
