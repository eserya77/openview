package com.example.openview.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.openview.data.DemoPlaces
import com.example.openview.data.PlaceJson
import com.example.openview.data.PlaceStore
import com.example.openview.data.SharedPlace
import com.example.openview.share.ShareHub
import com.example.openview.share.ShareOffer
import com.example.openview.share.ShareProtocol
import com.example.openview.share.hasSharePermissions
import com.example.openview.share.requiredSharePermissions
import com.example.openview.tts.TtsController
import com.example.openview.ui.components.AnnounceOnEnter
import com.example.openview.ui.components.PrimaryActionButton
import com.example.openview.ui.components.ScreenScaffold
import com.example.openview.ui.components.ScreenTitle
import com.example.openview.ui.components.SecondaryActionButton
import com.example.openview.ui.components.rememberScreenReader

/*
 * Compartir rutas: la pantalla de la PERSONA CIEGA («Rutas del lugar») y la del GUARDIA («Modo guardia»).
 * La lógica está en [ShareHub].
 */

private fun sharePermissionsWithNotifications(): Array<String> =
    requiredSharePermissions() + (if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray())

// ─────────────────────────────── Persona ciega ───────────────────────────────

@Composable
fun PlaceRoutesScreen(tts: TtsController, onStart: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val reader = rememberScreenReader(tts)
    val hub by ShareHub.state.collectAsState()
    val places by PlaceStore.places.collectAsState()
    val activeId by PlaceStore.activeId.collectAsState()
    var granted by remember { mutableStateOf(hasSharePermissions(context)) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasSharePermissions(context); if (granted) ShareHub.listen(true)
    }
    var more by rememberSaveable { mutableStateOf(false) }
    var fileMsg by remember { mutableStateOf<String?>(null) }
    val openJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) fileMsg = runCatching {
            val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            "Cargado: " + PlaceStore.saveAndActivate(text).place.name
        }.getOrElse { "No se pudo cargar: ${it.message}" }
    }

    LaunchedEffect(granted) { if (!granted) permLauncher.launch(sharePermissionsWithNotifications()) else ShareHub.listen(true) }
    val offers = hub.offers
    AnnounceOnEnter(
        reader,
        "Rutas del lugar.",
        if (offers.isNotEmpty()) "Hay rutas disponibles en ${offers.first().name ?: "un establecimiento cercano"}. El botón Descargar está arriba."
        else "Buscando guardias cercanos que compartan rutas.",
        "Si no aparece ninguno, toca Pedir rutas al guardia."
    )

    ScreenScaffold(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScreenTitle("Rutas del lugar")
        if (!granted) {
            Text("Necesito permiso de Bluetooth para encontrar al guardia.", style = MaterialTheme.typography.titleLarge)
            PrimaryActionButton("Dar permiso", onClick = { permLauncher.launch(sharePermissionsWithNotifications()) })
        }

        // Estado de la descarga
        when (val d = hub.download) {
            is ShareHub.Download.Running -> StatusCard("Descargando rutas de ${d.name}…", if (d.attempt > 1) "Intento ${d.attempt} de 3" else "No te alejes del guardia.", progress = d.progress)
            is ShareHub.Download.Done -> {
                StatusCard("Rutas de ${d.name} listas", "Ya puedes elegir a dónde ir.", icon = true)
                PrimaryActionButton("Iniciar", icon = Icons.Filled.PlayArrow, modifier = Modifier.heightIn(min = 104.dp), onClick = reader.click("Iniciar") { ShareHub.clearDownloadStatus(); onStart() })
            }
            is ShareHub.Download.Failed -> StatusCard("No se pudieron descargar", d.message + " Acércate al guardia e inténtalo otra vez.", error = true)
            else -> Unit
        }

        // Escenario 1: guardias que anuncian rutas
        SectionTitle("Cerca de ti")
        if (offers.isEmpty()) {
            Text(
                if (hub.listening) "Buscando guardias que compartan rutas…" else "La búsqueda está parada.",
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
        offers.forEach { o -> OfferCard(o, have = PlaceStore.hasHash(o.hash), busy = hub.download is ShareHub.Download.Running,
            onDownload = reader.click("Descargar") { ShareHub.download(o) }, onUse = reader.click("Usar") { onStart() }) }

        // Escenario 2: pedir las rutas directamente
        SectionTitle("Pedir rutas al guardia")
        val code = hub.myCode
        if (code == null) {
            Text("Si el guardia no las anuncia, pídeselas: tu teléfono dirá un código y el guardia te las enviará.", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth())
            PrimaryActionButton("Pedir rutas al guardia", icon = Icons.Filled.Campaign, containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                onClick = {
                    ShareHub.requestRoutes { c, err ->
                        if (c != null) tts.speak("Dile al guardia este código: ${ShareProtocol.spoken(c)}. Él tocará Enviar en su teléfono. Mantén la app abierta.")
                        else tts.speak(err ?: "No se pudo pedir.")
                    }
                })
        } else {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "Tu código: ${ShareProtocol.spoken(code)}. Díselo al guardia." }
            ) {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Dile al guardia este código", style = MaterialTheme.typography.titleLarge)
                    Text(code.toString().toCharArray().joinToString(" "), fontSize = 64.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
                    Text("Esperando a que te las envíe…", style = MaterialTheme.typography.bodyLarge)
                }
            }
            SecondaryActionButton("Repetir código", onClick = { tts.speak("Tu código es ${ShareProtocol.spoken(code)}.") })
            SecondaryActionButton("Cancelar petición", icon = Icons.Filled.Stop, onClick = { ShareHub.stopRequest() })
        }
        hub.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth()) }

        // Lugares guardados
        if (places.isNotEmpty()) {
            SectionTitle("Mis lugares")
            places.forEach { s ->
                val on = s.place.id == activeId
                Card(
                    colors = CardDefaults.cardColors(containerColor = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(s.place.name + if (on) " · en uso" else "", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(s.place.beacons.joinToString(" · ") { it.name }, style = MaterialTheme.typography.bodyLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (!on) OutlinedButton(onClick = reader.click("Usar ${s.place.name}") { PlaceStore.setActive(s.place.id) }) { Text("Usar este") }
                            OutlinedButton(onClick = reader.click("Borrar ${s.place.name}") { PlaceStore.delete(s.place.id) }) {
                                Icon(Icons.Filled.Delete, null); Spacer(Modifier.width(6.dp)); Text("Borrar")
                            }
                        }
                    }
                }
            }
        }

        // Pruebas con un solo teléfono
        TextButton(onClick = { more = !more }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(if (more) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
            Text(if (more) "Ocultar opciones de prueba" else "Opciones de prueba (sin guardia)")
        }
        if (more) {
            DemoPlaces.ALL.forEach { p ->
                SecondaryActionButton("Ejemplo: ${p.name.substringAfter("(").removeSuffix(")")}", onClick = {
                    fileMsg = "Cargado: " + PlaceStore.saveAndActivate(PlaceJson.write(p)).place.name
                })
            }
            SecondaryActionButton("Cargar JSON desde archivo", icon = Icons.Filled.FileOpen, onClick = { openJson.launch(arrayOf("application/json", "text/plain", "*/*")) })
            fileMsg?.let { Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth()) }
        }
        SecondaryActionButton("Volver", icon = Icons.AutoMirrored.Filled.ArrowBack, onClick = reader.click("Volver", onBack))
    }
}

@Composable
private fun OfferCard(o: ShareOffer, have: Boolean, busy: Boolean, onDownload: () -> Unit, onUse: () -> Unit) {
    val name = o.name ?: "Establecimiento cercano"
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
        shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {
                contentDescription = "Rutas disponibles en $name. ${signalWords(o.rssi)}."
            }) {
                Icon(Icons.Filled.Storefront, null, modifier = Modifier.size(40.dp))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Rutas disponibles en", style = MaterialTheme.typography.titleMedium)
                    Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(signalWords(o.rssi), style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (have) PrimaryActionButton("Ya las tienes · Usar", icon = Icons.Filled.PlayArrow, modifier = Modifier.heightIn(min = 80.dp), onClick = onUse)
            else PrimaryActionButton(if (busy) "Descargando…" else "Descargar y usar", icon = Icons.Filled.Download, modifier = Modifier.heightIn(min = 88.dp), onClick = { if (!busy) onDownload() })
        }
    }
}

private fun signalWords(rssi: Int) = when {
    rssi >= -60 -> "El guardia está muy cerca"
    rssi >= -75 -> "El guardia está cerca"
    else -> "El guardia está algo lejos"
}

@Composable
private fun StatusCard(title: String, text: String, progress: Float? = null, icon: Boolean = false, error: Boolean = false) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
        ),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon) { Icon(Icons.Filled.CheckCircle, null, tint = AdminColors.good, modifier = Modifier.size(32.dp)); Spacer(Modifier.width(10.dp)) }
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Text(text, style = MaterialTheme.typography.bodyLarge)
            if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).semantics { heading() })
}

// ─────────────────────────────── Guardia ───────────────────────────────

@Composable
fun GuardScreen(tts: TtsController, onBack: () -> Unit) {
    val context = LocalContext.current
    val hub by ShareHub.state.collectAsState()
    val guard by PlaceStore.guard.collectAsState()
    var granted by remember { mutableStateOf(hasSharePermissions(context)) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = hasSharePermissions(context) }
    var msg by remember { mutableStateOf<String?>(null) }
    val openJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) msg = runCatching {
            val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            val s = PlaceStore.setGuard(text)
            if (hub.guardSharing) ShareHub.startGuard(hub.guardAnnounce)
            "JSON cargado: ${s.place.name}"
        }.getOrElse { "No se pudo cargar: ${it.message}" }
    }
    LaunchedEffect(granted) { if (!granted) permLauncher.launch(sharePermissionsWithNotifications()) }

    ScreenScaffold(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenTitle("Modo guardia")
        Text("Comparte las rutas del establecimiento con el teléfono de las personas ciegas, por Bluetooth y sin internet.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
        if (!granted) PrimaryActionButton("Dar permisos de Bluetooth", onClick = { permLauncher.launch(sharePermissionsWithNotifications()) })

        // 1. El JSON de la empresa
        SectionTitle("1 · Rutas de la empresa")
        val g = guard
        if (g == null) Text("Todavía no hay rutas cargadas.", style = MaterialTheme.typography.titleMedium)
        else PlaceSummary(g.place, g.hash)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { openJson.launch(arrayOf("application/json", "text/plain", "*/*")) }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                Icon(Icons.Filled.FileOpen, null); Spacer(Modifier.width(6.dp)); Text("Cargar JSON")
            }
            if (g != null) OutlinedButton(onClick = {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "application/json"; putExtra(Intent.EXTRA_TEXT, g.json) }, "Compartir JSON"))
            }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Icon(Icons.Filled.Share, null); Spacer(Modifier.width(6.dp)); Text("Ver / enviar") }
        }
        Text("Ejemplos con NAV_BLE_01 Entrada · 02 Pasillo A · 03 Baño, a 2 m:", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            DemoPlaces.ALL.forEach { p ->
                OutlinedButton(onClick = {
                    PlaceStore.setGuard(PlaceJson.write(p)); msg = "Ejemplo cargado: ${p.name}"
                    if (hub.guardSharing) ShareHub.startGuard(hub.guardAnnounce)
                }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text(p.name.substringAfter("prueba ").removeSuffix(")"), textAlign = TextAlign.Center) }
            }
        }
        msg?.let { Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth()) }
        if (g != null) TextButton(onClick = { msg = "También en este teléfono: " + PlaceStore.saveAndActivate(g.json).place.name }) { Text("Usar estas rutas también en este teléfono (para probar)") }

        // 2. Compartir
        SectionTitle("2 · Compartir")
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Anunciar a personas cercanas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Escenario 1: su teléfono les avisa solo de que hay rutas.", style = MaterialTheme.typography.bodyMedium)
            }
            Switch(checked = hub.guardAnnounce, onCheckedChange = { ShareHub.setAnnounce(it) })
        }
        if (!hub.guardSharing) {
            PrimaryActionButton("Empezar a compartir", icon = Icons.Filled.Share, modifier = Modifier.heightIn(min = 72.dp), onClick = {
                val err = ShareHub.startGuard(hub.guardAnnounce)
                msg = err; tts.speak(err ?: "Compartiendo rutas.")
            })
        } else {
            StatusCard(
                "Compartiendo «${g?.place?.name}»",
                (if (hub.guardAnnounce) "Anunciando a quien esté cerca." else "Solo a quien lo pida.") + " Entregas: ${hub.guardServed}.",
                icon = true
            )
            SecondaryActionButton("Dejar de compartir", icon = Icons.Filled.Stop, onClick = { ShareHub.stopGuard() })
        }
        hub.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth()) }

        // 3. Peticiones directas
        SectionTitle("3 · Peticiones (escenario 2)")
        if (!hub.guardSharing) Text("Empieza a compartir para ver peticiones.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
        else if (hub.requests.isEmpty()) Text("Nadie está pidiendo rutas ahora. Cuando alguien las pida, verás aquí su código.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
        hub.requests.forEach { r ->
            val sending = hub.guardDirectCode == r.code
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Código", style = MaterialTheme.typography.labelLarge)
                        Text(r.code.toString(), fontSize = 36.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                        Text(signalWords(r.rssi).replace("El guardia", "La persona"), style = MaterialTheme.typography.bodyMedium)
                    }
                    OutlinedButton(onClick = { ShareHub.sendTo(r.code) }, enabled = !sending, modifier = Modifier.heightIn(min = 56.dp)) {
                        Icon(Icons.Filled.Send, null); Spacer(Modifier.width(6.dp)); Text(if (sending) "Enviando…" else "Enviar rutas")
                    }
                }
            }
        }

        if (hub.guardLog.isNotEmpty()) {
            SectionTitle("Registro")
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp)) {
                hub.guardLog.takeLast(12).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        Spacer(Modifier.height(4.dp))
        SecondaryActionButton("Volver", icon = Icons.AutoMirrored.Filled.ArrowBack, onClick = onBack)
    }
}

/** Resumen visual del lugar: mini plano con las balizas y sus conexiones, y la lista de nombres. */
@Composable
private fun PlaceSummary(p: SharedPlace, hash: Int) {
    val measurer = rememberTextMeasurer()
    val line = MaterialTheme.colorScheme.outline
    val dot = MaterialTheme.colorScheme.primary
    val onDot = MaterialTheme.colorScheme.onPrimary
    val label = MaterialTheme.colorScheme.onSurface
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(p.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${p.beacons.size} balizas · ${p.routes.size} rutas · versión ${p.version} · id ${"%04X".format(hash)}", style = MaterialTheme.typography.bodyMedium)
            val xs = p.beacons.map { it.x }; val ys = p.beacons.map { it.y }
            val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
            Canvas(Modifier.fillMaxWidth().height(150.dp).semantics { contentDescription = "Plano: " + p.beacons.joinToString { it.name } }) {
                val pad = 46.dp.toPx()
                val span = maxOf(maxX - minX, maxY - minY, 0.5)
                val scale = minOf((size.width - 2 * pad) / span, (size.height - 2 * pad) / span).toFloat()
                val cx = size.width / 2 - ((minX + maxX) / 2 * scale).toFloat()
                val cy = size.height / 2 + ((minY + maxY) / 2 * scale).toFloat()
                fun pos(x: Double, y: Double) = Offset(cx + (x * scale).toFloat(), cy - (y * scale).toFloat())
                val links = p.links.ifEmpty { p.beacons.flatMap { a -> p.beacons.filter { it.beacon > a.beacon }.map { a.beacon to it.beacon } } }
                links.forEach { (a, b) ->
                    val pa = p.beacon(a)!!; val pb = p.beacon(b)!!
                    drawLine(line, pos(pa.x, pa.y), pos(pb.x, pb.y), strokeWidth = 4.dp.toPx())
                }
                p.beacons.forEach { b ->
                    val c = pos(b.x, b.y)
                    drawCircle(dot, 16.dp.toPx(), c)
                    if (b.beacon == p.entrance) drawCircle(label, 21.dp.toPx(), c, style = Stroke(2.dp.toPx()))
                    val num = measurer.measure(b.beacon.takeLast(2), TextStyle(color = onDot, fontSize = 13.sp, fontWeight = FontWeight.Bold))
                    drawText(num, topLeft = Offset(c.x - num.size.width / 2f, c.y - num.size.height / 2f))
                    val t = measurer.measure(b.name, TextStyle(color = label, fontSize = 13.sp))
                    drawText(t, topLeft = Offset((c.x - t.size.width / 2f).coerceIn(0f, size.width - t.size.width), c.y + 22.dp.toPx()))
                }
            }
            p.beacons.forEach { b ->
                Text("${b.beacon} → ${b.name}" + if (b.beacon == p.entrance) " (entrada)" else "", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
