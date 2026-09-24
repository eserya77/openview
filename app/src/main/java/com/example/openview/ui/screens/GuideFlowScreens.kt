package com.example.openview.ui.screens

import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Stop
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
import com.example.openview.ui.components.AnnounceOnEnter
import com.example.openview.ui.components.PrimaryActionButton
import com.example.openview.ui.components.ScreenScaffold
import com.example.openview.ui.components.ScreenTitle
import com.example.openview.ui.components.SecondaryActionButton
import com.example.openview.ui.components.rememberScreenReader
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

/*
 * Guía del PRODUCTO (botón «Iniciar»): lo mismo que la guía de prueba del panel de administración, pero sin detalles
 * técnicos. Pantallas grandes, pocas opciones y todo anunciado por voz (o por TalkBack si está activado).
 */

// ─────────────────────────────── ¿A dónde quieres ir? ───────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideDestinationScreen(tts: TtsController, onSelect: (Int) -> Unit, onHelp: () -> Unit, onBack: () -> Unit) {
    val reader = rememberScreenReader(tts)
    val place = remember { GuidePlaces.current() }

    if (place == null) {
        AnnounceOnEnter(reader, "Todavía no hay un lugar preparado. Pide que lo configuren en el panel de administración.")
        ScreenScaffold(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            ScreenTitle("Sin lugar configurado")
            Text("Todavía no hay un lugar preparado. Pide que lo configuren en el panel de administración.", style = MaterialTheme.typography.titleLarge)
            SecondaryActionButton("Volver", icon = Icons.AutoMirrored.Filled.ArrowBack, onClick = reader.click("Volver", onBack))
        }
        return
    }

    AnnounceOnEnter(
        reader,
        "¿A dónde quieres ir? ${place.spots.size} destinos, de arriba abajo:",
        place.spots.mapIndexed { i, s -> "${i + 1}, ${s.label}" }.joinToString(". ") + ".",
        "Toca uno para empezar. Abajo están Ayuda y Volver."
    )

    ScreenScaffold(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.height(8.dp))
        ScreenTitle("¿A dónde quieres ir?")
        Text(place.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
        place.spots.forEachIndexed { i, s ->
            Card(
                onClick = { tts.speak("Vamos a ${s.label}."); onSelect(i) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 104.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "Destino ${i + 1} de ${place.spots.size}: ${s.label}" }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                    Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", color = MaterialTheme.colorScheme.onPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(18.dp))
                    Text(s.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.Place, null, modifier = Modifier.size(32.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        SecondaryActionButton("Ayuda", icon = Icons.AutoMirrored.Filled.HelpOutline, onClick = reader.click("Ayuda", onHelp))
        SecondaryActionButton("Volver", icon = Icons.AutoMirrored.Filled.ArrowBack, onClick = reader.click("Volver", onBack))
    }
}

// ─────────────────────────────── Guía en marcha ───────────────────────────────

private enum class RunPhase { PERMISSION, LOCATING, GUIDING, ARRIVED }

/**
 * Guía hacia el destino [destIdx] del lugar actual. Primero escucha unos segundos para saber junto a qué punto estás,
 * arma la ruta en el sentido que toque y guía con voz, pitidos y vibración. Vol+ repite la instrucción.
 */
@Composable
fun GuideRunScreen(
    scanner: BleScanner,
    tts: TtsController,
    voice: VoiceSettings,
    destIdx: Int,
    onNewDestination: () -> Unit,
    onHome: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val reader = rememberScreenReader(tts)
    val scan by scanner.state.collectAsState()
    val place = remember { GuidePlaces.current() }
    val dest = place?.spots?.getOrNull(destIdx)

    val motion = remember { MotionDetector(context.applicationContext) }
    val haptics = remember { Haptics(context.applicationContext) }
    val sonar = remember { SonarPlayer() }
    var hasPermission by remember { mutableStateOf(hasBlePermissions(context)) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> hasPermission = r.values.all { it } }
    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { scanner.refreshBluetoothState() }

    var phase by remember { mutableStateOf(if (hasPermission) RunPhase.LOCATING else RunPhase.PERMISSION) }
    var session by remember { mutableStateOf<GuideSession?>(null) }
    var route by remember { mutableStateOf<List<GuideSpot>>(emptyList()) }
    var locatingText by remember { mutableStateOf("Buscando dónde estás. Quédate quieto un momento.") }

    DisposableEffect(Unit) {
        view.keepScreenOn = true
        motion.start()
        onDispose { view.keepScreenOn = false; motion.stop(); sonar.release(); scanner.stopKeepAlive() }
    }
    LaunchedEffect(hasPermission) {
        if (hasPermission) { scanner.startKeepAlive(); if (phase == RunPhase.PERMISSION) phase = RunPhase.LOCATING }
        else { tts.speak("Necesito permiso para usar el Bluetooth y encontrar las balizas. Toca el botón Dar permiso."); permLauncher.launch(requiredBlePermissions()) }
    }

    // 1) ¿Dónde estoy? Escucha y elige el punto más cercano.
    LaunchedEffect(phase) {
        if (phase != RunPhase.LOCATING || place == null || dest == null) return@LaunchedEffect
        tts.speak(locatingText)
        val samples = mutableMapOf<String, MutableList<Int>>()
        val beaconSet = place.spots.map { it.beacon }.toSet()
        val found = coroutineScope {
            val collector = launch {
                scanner.samples.collect { s ->
                    if (s.name in beaconSet) synchronized(samples) { samples.getOrPut(s.name) { mutableListOf() }.let { it += s.rssi; if (it.size > 30) it.removeAt(0) } }
                }
            }
            val t0 = SystemClock.elapsedRealtime()
            var lastHint = t0
            var guess: StartLocator.Guess? = null
            while (true) {
                delay(500)
                val elapsed = SystemClock.elapsedRealtime() - t0
                guess = synchronized(samples) { StartLocator.nearest(place.spots, samples.mapValues { it.value.toList() }) }
                if (elapsed >= 3_000L && guess != null) break
                if (SystemClock.elapsedRealtime() - lastHint >= 10_000L) {
                    lastHint = SystemClock.elapsedRealtime()
                    locatingText = if (!scanner.state.value.bluetoothEnabled) "El Bluetooth está apagado. Actívalo para continuar." else "No oigo las balizas. Acércate a la pared guía y quédate quieto."
                    tts.speak(locatingText)
                }
            }
            collector.cancel()
            guess
        }
        val from = place.spots.indexOf(found!!.spot)
        if (from == destIdx) {
            tts.speak("Ya estás junto a ${dest.label}.")
            haptics.arrived()
            phase = RunPhase.ARRIVED
        } else {
            route = GuidePlaces.route(place, from, destIdx)
            session = GuideSession(route, tts, sonar, haptics, motion, GuideFeedbackOptions(true, voice.beeps, voice.vibration))
            phase = RunPhase.GUIDING
        }
    }
    // 2) Guía
    LaunchedEffect(session) { session?.run(scanner) }
    val emptyState = remember { MutableStateFlow(GuideState()) }
    val st by (session?.state ?: emptyState).collectAsState()
    LaunchedEffect(st.arrived) { if (st.arrived) { delay(1_500); phase = RunPhase.ARRIVED; session = null } }

    // Vol+ = repetir la última instrucción
    val onVol by rememberUpdatedState({
        when (phase) {
            RunPhase.GUIDING -> session?.repeatInstruction() ?: Unit
            RunPhase.LOCATING -> tts.speak(locatingText)
            RunPhase.ARRIVED -> tts.speak("Llegaste a ${dest?.label ?: "tu destino"}.")
            RunPhase.PERMISSION -> tts.speak("Necesito permiso de Bluetooth.")
        }
    })
    DisposableEffect(Unit) {
        val l = { onVol() }
        VolumeUpDispatcher.register(l)
        onDispose { VolumeUpDispatcher.unregister(l) }
    }

    ScreenScaffold(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.height(4.dp))
        when (phase) {
            RunPhase.PERMISSION -> {
                BigMessage("Necesito permiso de Bluetooth", "Sin él no puedo encontrar las balizas.")
                PrimaryActionButton("Dar permiso", icon = Icons.Filled.Bluetooth, onClick = { permLauncher.launch(requiredBlePermissions()) })
                SecondaryActionButton("Volver al inicio", icon = Icons.Filled.Home, onClick = reader.click("Volver al inicio", onHome))
            }
            RunPhase.LOCATING -> {
                BigMessage("Buscando dónde estás…", locatingText)
                if (hasPermission && !scan.bluetoothEnabled) {
                    PrimaryActionButton("Encender Bluetooth", icon = Icons.Filled.Bluetooth, onClick = { btLauncher.launch(android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)) })
                }
                Text("Destino: ${dest?.label ?: "—"}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth())
                SecondaryActionButton("Cancelar", icon = Icons.Filled.Stop, onClick = reader.click("Cancelar", onHome))
            }
            RunPhase.GUIDING -> {
                val tgt = route.getOrNull(st.target)
                Text(
                    "Hacia ${dest?.label ?: ""}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().semantics { heading() }
                )
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                    shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        st.instruction.ifBlank { "Camina despacio junto a la pared guía." },
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                        minLines = 3, maxLines = 4,
                        modifier = Modifier.padding(22.dp).fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
                ProximityGauge(st, tgt)
                PrimaryActionButton("Repetir instrucción", icon = Icons.Filled.Replay, modifier = Modifier.heightIn(min = 96.dp), onClick = { session?.repeatInstruction() })
                Text("También con el botón de subir volumen.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                SecondaryActionButton("Terminar guía", icon = Icons.Filled.Stop, onClick = { session = null; tts.speak("Guía terminada."); onHome() })
            }
            RunPhase.ARRIVED -> {
                Icon(Icons.Filled.CheckCircle, null, tint = AdminColors.good, modifier = Modifier.size(120.dp).align(Alignment.CenterHorizontally))
                Text(
                    "Llegaste a ${dest?.label ?: "tu destino"}", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().semantics { heading(); liveRegion = LiveRegionMode.Assertive }
                )
                PrimaryActionButton("Ir a otro destino", icon = Icons.Filled.Place, modifier = Modifier.heightIn(min = 96.dp), onClick = reader.click("Ir a otro destino", onNewDestination))
                SecondaryActionButton("Volver al inicio", icon = Icons.Filled.Home, onClick = reader.click("Volver al inicio", onHome))
            }
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
