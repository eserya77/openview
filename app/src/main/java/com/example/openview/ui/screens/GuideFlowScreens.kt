package com.example.openview.ui.screens

import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.openview.ble.BleScanner
import com.example.openview.ble.hasBlePermissions
import com.example.openview.ble.requiredBlePermissions
import com.example.openview.data.GuidePlaces
import com.example.openview.data.LastHeading
import com.example.openview.data.PlaceBeacon
import com.example.openview.data.PlacePlanner
import com.example.openview.data.VoiceSettings
import com.example.openview.guide.GuideFeedbackOptions
import com.example.openview.guide.GuideSession
import com.example.openview.guide.Haptics
import com.example.openview.guide.MotionDetector
import com.example.openview.guide.SonarPlayer
import com.example.openview.guide.StartLocator
import com.example.openview.input.VolumeUpDispatcher
import com.example.openview.nav.GuideSpot
import com.example.openview.nav.GuideState
import com.example.openview.tts.TtsController
import com.example.openview.ui.components.PrimaryActionButton
import com.example.openview.ui.components.ScreenScaffold
import com.example.openview.ui.components.ScreenTitle
import com.example.openview.ui.components.SecondaryActionButton
import com.example.openview.ui.components.rememberScreenReader
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/*
 * Guía del PRODUCTO (botón «Iniciar»):
 *  1. ¿Dónde estoy?  Escucha unos segundos y detecta junto a qué baliza estás («Estás en Entrada»).
 *  2. ¿A dónde voy?  Dice los destinos disponibles desde ahí y la persona elige uno.
 *  3. Guía           Indicaciones «camina recto… gira a la derecha…», pitidos, vibración y avisos de desvío.
 *  4. Llegada        No termina sola: sigue vigilando y avisa si te alejas del destino hasta pulsar «Terminar».
 * Pantallas grandes, pocas opciones, todo anunciado por voz (o por TalkBack si está activado).
 */

// ─────────────────────────────── 1 y 2: dónde estás y a dónde vas ───────────────────────────────

private enum class StartPhase { PERMISSION, LOCATING, CHOOSE }

@Composable
fun GuideStartScreen(
    scanner: BleScanner,
    tts: TtsController,
    onSelect: (from: String, to: String) -> Unit,
    onRoutes: () -> Unit,
    onHelp: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val reader = rememberScreenReader(tts)
    val scan by scanner.state.collectAsState()
    val current = remember { GuidePlaces.current() }

    if (current == null) {
        LaunchedEffect(Unit) {
            delay(350)
            tts.speak("Todavía no tienes las rutas de este lugar. Toca Rutas del lugar para descargarlas del empleado, o pídeselas.")
        }
        ScreenScaffold(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            ScreenTitle("Sin rutas de este lugar")
            Text("Todavía no tienes las rutas de este lugar. Descárgalas del empleado o pídeselas.", style = MaterialTheme.typography.titleLarge)
            PrimaryActionButton("Rutas del lugar", icon = Icons.Filled.Download, modifier = Modifier.heightIn(min = 96.dp), onClick = reader.click("Rutas del lugar", onRoutes))
            SecondaryActionButton("Volver", icon = Icons.AutoMirrored.Filled.ArrowBack, onClick = reader.click("Volver", onBack))
        }
        return
    }
    val place = current.place
    val spots = current.spots

    var hasPermission by remember { mutableStateOf(hasBlePermissions(context)) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> hasPermission = r.values.all { it } }
    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { scanner.refreshBluetoothState() }
    var phase by remember { mutableStateOf(if (hasPermission) StartPhase.LOCATING else StartPhase.PERMISSION) }
    var here by remember { mutableStateOf<StartLocator.Guess?>(null) }
    var locatingText by remember { mutableStateOf("Buscando dónde estás. Quédate quieto un momento.") }
    var attempt by remember { mutableIntStateOf(0) }
    // Al pasar a la guía el escáner sigue encendido (la guía lo usa); solo se apaga si se sale hacia atrás.
    var toGuide by remember { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { if (!toGuide) scanner.stopKeepAlive() } }
    LaunchedEffect(hasPermission) {
        if (hasPermission) { scanner.startKeepAlive(); if (phase == StartPhase.PERMISSION) phase = StartPhase.LOCATING }
        else { tts.speak("Necesito permiso para usar el Bluetooth y encontrar las balizas."); permLauncher.launch(requiredBlePermissions()) }
    }

    LaunchedEffect(phase, attempt) {
        if (phase != StartPhase.LOCATING) return@LaunchedEffect
        tts.speak("${place.name}. $locatingText")
        val samples = mutableMapOf<String, MutableList<Int>>()
        val beaconSet = spots.map { it.beacon }.toSet()
        val found = coroutineScope {
            val collector = launch {
                scanner.samples.collect { s ->
                    if (s.name in beaconSet) synchronized(samples) { samples.getOrPut(s.name) { mutableListOf() }.let { it += s.rssi; if (it.size > 30) it.removeAt(0) } }
                }
            }
            val t0 = SystemClock.elapsedRealtime()
            var lastHint = t0
            var guess: StartLocator.Guess?
            while (true) {
                delay(500)
                val elapsed = SystemClock.elapsedRealtime() - t0
                guess = synchronized(samples) { StartLocator.nearest(spots, samples.mapValues { it.value.toList() }) }
                if (elapsed >= 3_000L && guess != null) break
                if (SystemClock.elapsedRealtime() - lastHint >= 10_000L) {
                    lastHint = SystemClock.elapsedRealtime()
                    locatingText = if (!scanner.state.value.bluetoothEnabled) "El Bluetooth está apagado. Actívalo para continuar." else "No oigo las balizas. Acércate a una y quédate quieto."
                    tts.speak(locatingText)
                }
            }
            collector.cancel()
            guess
        }
        here = found
        val b = place.beacon(found!!.spot.beacon)!!
        val dests = PlacePlanner.destinationsFrom(place, b.beacon)
        val where = if (found.marginDb >= 3.0) "Estás en ${b.name}." else "Estás cerca de ${b.name}."
        phase = StartPhase.CHOOSE
        tts.speak(where + (b.note?.let { " $it" } ?: ""))
        if (dests.isEmpty()) tts.speakQueued("Desde aquí no hay destinos disponibles.")
        else {
            tts.speakQueued("Destinos disponibles: " + dests.mapIndexed { i, d -> "${i + 1}, ${d.name}" }.joinToString(". ") + ".")
            tts.speakQueued("Toca uno para empezar. Arriba de la lista puedes volver a buscar dónde estás.")
        }
    }

    ScreenScaffold(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.height(4.dp))
        when (phase) {
            StartPhase.PERMISSION -> {
                BigMessage("Necesito permiso de Bluetooth", "Sin él no puedo encontrar las balizas.")
                PrimaryActionButton("Dar permiso", icon = Icons.Filled.Bluetooth, onClick = { permLauncher.launch(requiredBlePermissions()) })
                SecondaryActionButton("Volver", icon = Icons.AutoMirrored.Filled.ArrowBack, onClick = reader.click("Volver", onBack))
            }
            StartPhase.LOCATING -> {
                Text(place.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
                BigMessage("Buscando dónde estás…", locatingText)
                if (hasPermission && !scan.bluetoothEnabled) {
                    PrimaryActionButton("Encender Bluetooth", icon = Icons.Filled.Bluetooth, onClick = { btLauncher.launch(android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)) })
                }
                SecondaryActionButton("Cancelar", icon = Icons.Filled.Stop, onClick = reader.click("Cancelar", onBack))
            }
            StartPhase.CHOOSE -> {
                val g = here!!
                val b = place.beacon(g.spot.beacon)!!
                val dests = PlacePlanner.destinationsFrom(place, b.beacon)
                Text(place.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(20.dp)) {
                        Icon(Icons.Filled.MyLocation, null, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(if (g.marginDb >= 3.0) "Estás en" else "Estás cerca de", style = MaterialTheme.typography.titleMedium)
                            Text(b.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                SecondaryActionButton("Volver a buscar dónde estoy", icon = Icons.Filled.MyLocation, onClick = reader.click("Volver a buscar") { attempt++; phase = StartPhase.LOCATING })
                Text("¿A dónde quieres ir?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().semantics { heading() })
                if (dests.isEmpty()) Text("Desde aquí no hay destinos disponibles.", style = MaterialTheme.typography.titleLarge)
                dests.forEachIndexed { i, d ->
                    val path = PlacePlanner.path(place, b.beacon, d.beacon)
                    val meters = path?.zipWithNext { x, y -> PlacePlanner.distance(place.beacon(x)!!, place.beacon(y)!!) }?.sum() ?: 0.0
                    val via = if (path != null && path.size > 2) ", por ${path.subList(1, path.size - 1).joinToString { place.nameOf(it) }}" else ""
                    DestinationCard(i + 1, dests.size, d, "a ${PlacePlanner.metersText(meters)}$via") {
                        tts.speak("Vamos a ${d.name}.")
                        toGuide = true
                        onSelect(b.beacon, d.beacon)
                    }
                }
                Spacer(Modifier.height(8.dp))
                SecondaryActionButton("Ayuda", icon = Icons.AutoMirrored.Filled.HelpOutline, onClick = reader.click("Ayuda", onHelp))
                SecondaryActionButton("Volver", icon = Icons.AutoMirrored.Filled.ArrowBack, onClick = reader.click("Volver", onBack))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DestinationCard(n: Int, total: Int, d: PlaceBeacon, detail: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 104.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Destino $n de $total: ${d.name}, $detail" }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                Text("$n", color = MaterialTheme.colorScheme.onPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(d.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(detail, style = MaterialTheme.typography.bodyLarge)
            }
            Icon(Icons.Filled.Place, null, modifier = Modifier.size(32.dp))
        }
    }
}

// ─────────────────────────────── 3 y 4: guía y llegada ───────────────────────────────

private enum class RunPhase { PERMISSION, GUIDING, ARRIVED, NO_ROUTE }

/**
 * Guía de [fromBeacon] a [toBeacon] del lugar actual. Al llegar NO termina: sigue vigilando y avisa si te alejas del
 * destino, hasta que se pulsa «Terminar». Vol+ repite la instrucción.
 */
@Composable
fun GuideRunScreen(
    scanner: BleScanner,
    tts: TtsController,
    voice: VoiceSettings,
    fromBeacon: String,
    toBeacon: String,
    onNewDestination: () -> Unit,
    onHome: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val reader = rememberScreenReader(tts)
    val current = remember { GuidePlaces.current() }
    val place = current?.place
    val path = remember { place?.let { PlacePlanner.path(it, fromBeacon, toBeacon) } }
    val legs = remember { if (place != null && path != null) PlacePlanner.legs(place, path, LastHeading.at(fromBeacon, SystemClock.elapsedRealtime())) else emptyList() }
    val route = remember { if (place != null && path != null) PlacePlanner.spots(place, path) else emptyList() }
    val dest = place?.beacon(toBeacon)

    val motion = remember { MotionDetector(context.applicationContext) }
    val haptics = remember { Haptics(context.applicationContext) }
    val sonar = remember { SonarPlayer() }
    var hasPermission by remember { mutableStateOf(hasBlePermissions(context)) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> hasPermission = r.values.all { it } }

    var phase by remember { mutableStateOf(when { route.isEmpty() -> RunPhase.NO_ROUTE; hasPermission -> RunPhase.GUIDING; else -> RunPhase.PERMISSION }) }
    var session by remember { mutableStateOf<GuideSession?>(null) }

    DisposableEffect(Unit) {
        view.keepScreenOn = true
        motion.start()
        onDispose { view.keepScreenOn = false; motion.stop(); sonar.release(); scanner.stopKeepAlive() }
    }
    LaunchedEffect(hasPermission) {
        if (route.isEmpty()) { tts.speak("No encuentro un camino hasta ese destino."); return@LaunchedEffect }
        if (hasPermission) {
            scanner.startKeepAlive()
            if (session == null) {
                val others = current!!.spots.filter { s -> route.none { it.beacon == s.beacon } }
                session = GuideSession(route, tts, sonar, haptics, motion, GuideFeedbackOptions(true, voice.beeps, voice.vibration), others = others, legs = legs.map { it.text })
            }
            if (phase == RunPhase.PERMISSION) phase = RunPhase.GUIDING
        } else { tts.speak("Necesito permiso para usar el Bluetooth y encontrar las balizas."); permLauncher.launch(requiredBlePermissions()) }
    }
    LaunchedEffect(session) { session?.run(scanner) }
    val emptyState = remember { MutableStateFlow(GuideState()) }
    val st by (session?.state ?: emptyState).collectAsState()

    // Llegada: la sesión sigue en marcha (vigila si te alejas). Se recuerda hacia dónde miras para la próxima ruta.
    LaunchedEffect(st.arrived) {
        if (!st.arrived) return@LaunchedEffect
        legs.lastOrNull()?.let { LastHeading.set(toBeacon, PlacePlanner.heading(it.from, it.to), SystemClock.elapsedRealtime()) }
        phase = RunPhase.ARRIVED
        dest?.note?.let { tts.speakQueued(it) }
        tts.speakQueued("Sigo atento: si te alejas, te aviso. Toca Terminar cuando quieras.")
    }

    val onVol by rememberUpdatedState({
        when (phase) {
            RunPhase.GUIDING, RunPhase.ARRIVED -> session?.repeatInstruction() ?: Unit
            RunPhase.PERMISSION -> tts.speak("Necesito permiso de Bluetooth.")
            RunPhase.NO_ROUTE -> tts.speak("No encuentro un camino hasta ese destino.")
        }
    })
    DisposableEffect(Unit) {
        val l = { onVol() }
        VolumeUpDispatcher.register(l)
        onDispose { VolumeUpDispatcher.unregister(l) }
    }
    val finish = { session = null; tts.speak("Guía terminada."); onHome() }

    ScreenScaffold(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(4.dp))
        when (phase) {
            RunPhase.PERMISSION -> {
                BigMessage("Necesito permiso de Bluetooth", "Sin él no puedo encontrar las balizas.")
                PrimaryActionButton("Dar permiso", icon = Icons.Filled.Bluetooth, onClick = { permLauncher.launch(requiredBlePermissions()) })
                SecondaryActionButton("Volver al inicio", icon = Icons.Filled.Home, onClick = reader.click("Volver al inicio", onHome))
            }
            RunPhase.NO_ROUTE -> {
                BigMessage("Sin camino", "No encuentro un camino hasta ese destino en las rutas de este lugar.")
                SecondaryActionButton("Volver al inicio", icon = Icons.Filled.Home, onClick = reader.click("Volver al inicio", onHome))
            }
            RunPhase.GUIDING -> {
                val tgt = route.getOrNull(st.target)
                Text(
                    "Hacia ${dest?.name ?: ""}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().semantics { heading() }
                )
                if (st.offRoute) WarningBanner("Fuera de la ruta", "Vuelve hacia ${tgt?.label ?: dest?.name}.")
                InstructionCard(st.instruction.ifBlank { legs.firstOrNull()?.text ?: "Camina despacio." })
                LegList(legs.map { it.text }, current = (st.target - 1).coerceAtLeast(0))
                ProximityGauge(st, tgt)
                PrimaryActionButton("Repetir instrucción", icon = Icons.Filled.Replay, modifier = Modifier.heightIn(min = 88.dp), onClick = { session?.repeatInstruction() })
                Text("También con el botón de subir volumen.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                SecondaryActionButton("Terminar guía", icon = Icons.Filled.Stop, onClick = finish)
            }
            RunPhase.ARRIVED -> {
                Icon(if (st.away) Icons.Filled.Warning else Icons.Filled.CheckCircle, null, tint = if (st.away) AdminColors.warn else AdminColors.good,
                    modifier = Modifier.size(96.dp).align(Alignment.CenterHorizontally))
                Text(
                    "Llegaste a ${dest?.name ?: "tu destino"}", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().semantics { heading() }
                )
                if (st.away) {
                    WarningBanner("Te estás alejando", "Da la vuelta y vuelve a ${dest?.name}. Los pitidos te guían.")
                    ProximityGauge(st, route.lastOrNull())
                } else {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                        shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Sigo atento: si te alejas, te aviso.", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(20.dp).semantics { liveRegion = LiveRegionMode.Polite }
                        )
                    }
                }
                PrimaryActionButton("Terminar", icon = Icons.Filled.Stop, modifier = Modifier.heightIn(min = 104.dp), onClick = finish)
                SecondaryActionButton("Ir a otro destino", icon = Icons.Filled.Place, onClick = reader.click("Ir a otro destino") { session = null; onNewDestination() })
                SecondaryActionButton("Repetir", icon = Icons.Filled.Replay, onClick = { session?.repeatInstruction() })
            }
        }
    }
}

@Composable
private fun InstructionCard(text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
        shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, minLines = 3, maxLines = 5,
            modifier = Modifier.padding(22.dp).fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }
        )
    }
}

/** Los tramos de la ruta, con el actual resaltado (para quien acompaña o tiene resto visual). */
@Composable
private fun LegList(legs: List<String>, current: Int) {
    if (legs.size < 2) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        legs.forEachIndexed { i, t ->
            val on = i == current
            val done = i < current
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .border(1.dp, if (on) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "Paso ${i + 1} de ${legs.size}${if (done) ", hecho" else if (on) ", ahora" else ""}: $t" }
            ) {
                Text("${i + 1}", fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.width(28.dp),
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                Text(t, style = MaterialTheme.typography.bodyLarge, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun WarningBanner(title: String, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(16.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive }
    ) {
        Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(36.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun BigMessage(title: String, text: String) {
    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().semantics { heading() })
    Text(text, style = MaterialTheme.typography.titleLarge, minLines = 3, modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
}

/** Indicador grande de cercanía al siguiente punto (lo mismo que dicen los pitidos). */
@Composable
private fun ProximityGauge(st: GuideState, target: GuideSpot?) {
    val t = st.targetTemperature
    val word = when { t >= 0.85 -> "Muy cerca"; t >= 0.6 -> "Cerca"; t >= 0.35 -> "Acercándote"; else -> "Lejos" }
    val heard = st.tracks.any { it.beacon == target?.beacon }
    val fill = when { t >= 0.85 -> AdminColors.good; t >= 0.6 -> AdminColors.goodFill; t >= 0.35 -> AdminColors.warn; else -> AdminColors.bad }
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = if (heard) "Siguiente punto: ${target?.label}. $word." else "Buscando la señal del siguiente punto."
        },
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(if (heard) "${target?.label}: $word" else "Buscando señal…", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Canvas(Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(17.dp))) {
            drawRoundRect(track, cornerRadius = CornerRadius(17.dp.toPx()), size = Size(size.width, size.height))
            if (heard) drawRoundRect(fill, cornerRadius = CornerRadius(17.dp.toPx()), size = Size((size.width * t.toFloat()).coerceAtLeast(size.height), size.height))
        }
    }
}
