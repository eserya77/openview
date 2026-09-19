package com.example.openview.ui.screens

import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.openview.ble.BleDeviceInfo
import com.example.openview.ble.BleScanner
import com.example.openview.ble.hasBlePermissions
import com.example.openview.ble.requiredBlePermissions
import com.example.openview.data.ManagedBeacon
import com.example.openview.data.NavEdge
import com.example.openview.data.NavNode
import com.example.openview.data.RouteRepository
import com.example.openview.data.SavedRoute
import com.example.openview.ui.components.PrimaryActionButton
import com.example.openview.ui.components.ScreenScaffold
import com.example.openview.ui.components.ScreenTitle
import com.example.openview.ui.components.SecondaryActionButton
import com.example.openview.ui.theme.FailureRed
import com.example.openview.ui.theme.SuccessGreen
import kotlinx.coroutines.delay

private const val PRESENT_WINDOW_MS = 5_000L
private enum class AdminPage { HOME, ROUTES, BEACONS, EDITOR }

/** Centro de control local de las rutas y balizas del edificio. */
@Composable
fun AdminBleScreen(scanner: BleScanner, onBack: () -> Unit) {
    val catalog by RouteRepository.catalog.collectAsState()
    val scan by scanner.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var hasPermission by remember { mutableStateOf(hasBlePermissions(context)) }
    var page by remember { mutableStateOf(AdminPage.HOME) }
    var editingRouteId by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        hasPermission = granted.values.all { it }; if (hasPermission) scanner.startScan()
    }
    LaunchedEffect(Unit) { while (true) { now = SystemClock.elapsedRealtime(); delay(1000) } }
    LaunchedEffect(scan.targets) { scan.targets.mapNotNull { it.name }.forEach(RouteRepository::rememberBeacon) }
    val startScan = { if (hasPermission) { scanner.refreshBluetoothState(); scanner.startScan() } else launcher.launch(requiredBlePermissions()) }

    when (page) {
        AdminPage.HOME -> AdminHome(catalog.routes.firstOrNull { it.id == catalog.activeRouteId }, catalog.beacons.count { it.enabled }, catalog.beacons.size, { page = AdminPage.ROUTES }, { page = AdminPage.BEACONS }, onBack)
        AdminPage.ROUTES -> RouteList(catalog.routes, catalog.activeRouteId, { page = AdminPage.HOME }, { id -> editingRouteId = id; page = AdminPage.EDITOR }, { id -> editingRouteId = id; page = AdminPage.EDITOR }, RouteRepository::activate)
        AdminPage.BEACONS -> BeaconInventory(catalog.beacons, scan.targets, scan.isScanning, scan.error, now, hasPermission, { page = AdminPage.HOME }, startScan, scanner::stopScan, RouteRepository::setBeaconEnabled)
        AdminPage.EDITOR -> {
            val route = catalog.routes.firstOrNull { it.id == editingRouteId }
            if (route == null) page = AdminPage.ROUTES else RouteEditor(route, catalog.beacons, catalog.activeRouteId, { page = AdminPage.ROUTES }, RouteRepository::activate, RouteRepository::renameRoute, { RouteRepository.deleteRoute(it); page = AdminPage.ROUTES }, RouteRepository::addNode, RouteRepository::updateNode, RouteRepository::removeNode, RouteRepository::connect, RouteRepository::disconnect, RouteRepository.validate(route.id))
        }
    }
}

@Composable
private fun AdminHome(active: SavedRoute?, usable: Int, total: Int, onRoutes: () -> Unit, onBeacons: () -> Unit, onBack: () -> Unit) {
    ScreenScaffold(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(8.dp)); ScreenTitle("Administración")
        Text("Configura el espacio antes de guiar a una persona. Las rutas se guardan solo en este teléfono.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AdminSummary("Ruta activa", active?.name ?: "Sin ruta", Icons.Filled.Route, SuccessGreen)
        AdminSummary("Balizas disponibles", "$usable de $total", Icons.Filled.Bluetooth, if (usable >= 3) SuccessGreen else FailureRed)
        PrimaryActionButton("Gestionar rutas", icon = Icons.Filled.Route, onClick = onRoutes)
        SecondaryActionButton("Probar y administrar balizas", icon = Icons.Filled.Radar, onClick = onBeacons)
        Text("Primero prueba las balizas. Luego crea una ruta, agrega sus puntos y actívala cuando no tenga errores.", textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SecondaryActionButton("Volver", icon = Icons.AutoMirrored.Filled.ArrowBack, onClick = onBack)
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
private fun BeaconInventory(beacons: List<ManagedBeacon>, scanned: List<BleDeviceInfo>, scanning: Boolean, error: String?, now: Long, hasPermission: Boolean, onBack: () -> Unit, onStart: () -> Unit, onStop: () -> Unit, onSetEnabled: (String, Boolean) -> Unit) {
    val found = scanned.mapNotNull { it.name?.let { name -> name to it } }.toMap()
    ScreenScaffold(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenTitle("Balizas")
        Text("Una baliza desactivada no participa en la ubicación ni permite activar rutas que dependan de ella. Puedes volver a habilitar NAV_BLE_02 cuando quieras probarla.", style = MaterialTheme.typography.bodyLarge)
        error?.let { ProblemsCard(listOf(it)) }
        if (scanning) PrimaryActionButton("Detener prueba", icon = Icons.Filled.Stop, containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError, onClick = onStop) else PrimaryActionButton("Probar balizas cercanas", icon = Icons.Filled.Radar, onClick = onStart)
        if (!hasPermission) Text("Al iniciar se solicitará permiso de Bluetooth cercano.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        beacons.sortedBy { it.name }.forEach { BeaconCard(it, found[it.name], found[it.name]?.let { now - it.lastSeenElapsedMs <= PRESENT_WINDOW_MS } == true, onSetEnabled) }
        SecondaryActionButton("Volver", icon = Icons.AutoMirrored.Filled.ArrowBack, onClick = onBack)
    }
}

@Composable
private fun BeaconCard(beacon: ManagedBeacon, device: BleDeviceInfo?, present: Boolean, onSetEnabled: (String, Boolean) -> Unit) {
    val color = when { !beacon.enabled -> MaterialTheme.colorScheme.onSurfaceVariant; present -> SuccessGreen; else -> FailureRed }
    Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = .10f)), modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
            Icon(if (present) Icons.Filled.CheckCircle else Icons.Filled.Bluetooth, null, tint = color, modifier = Modifier.size(28.dp)); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) { Text(beacon.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(when { !beacon.enabled -> "No se usará en rutas"; present && device != null -> "Detectada · ${device.rssi} dBm · ${device.signalLabel}"; else -> "No detectada en esta prueba" }, style = MaterialTheme.typography.bodyMedium, color = color) }
            Switch(checked = beacon.enabled, onCheckedChange = { onSetEnabled(beacon.name, it) })
        }
    }
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
