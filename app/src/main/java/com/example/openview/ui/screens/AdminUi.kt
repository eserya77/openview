package com.example.openview.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.openview.ble.BleScanState
import com.example.openview.ble.signalBarsForRssi

/*
 * Piezas visuales del panel de administración.
 *
 * Regla de oro: NADA cambia de tamaño mientras llegan datos. Los números van en un ancho fijo con cifras tabulares,
 * los textos que cambian ocupan un espacio reservado (minLines / altura fija) y lo que a veces aparece se dibuja siempre
 * (vacío o atenuado) en lugar de "aparecer y empujar" lo de abajo.
 */

/** Colores de estado legibles sobre fondo claro y oscuro (SuccessGreen puro es demasiado claro para texto). */
internal object AdminColors {
    val good = Color(0xFF1B7F3B)
    val goodFill = Color(0xFF00C853)
    val warn = Color(0xFFB8720A)
    val bad = Color(0xFFC62828)
    val beacon = Color(0xFFB8720A)
    val tape = Color(0xFFE8A200)
}

/** Cifras de ancho fijo para que los números que cambian no muevan el texto que los rodea. */
internal val NumberStyle = TextStyle(fontFamily = FontFamily.Monospace, fontFeatureSettings = "tnum")

@Composable
internal fun AdminCard(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surface,
    border: Color? = MaterialTheme.colorScheme.outlineVariant,
    padding: Dp = 16.dp,
    spacing: Dp = 10.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        shape = RoundedCornerShape(20.dp),
        border = border?.let { BorderStroke(1.dp, it) },
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
    }
}

/** Encabezado de sección: círculo numerado (opcional), título y una línea de ayuda. */
@Composable
internal fun SectionHeader(title: String, step: Int? = null, subtitle: String? = null, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        if (step != null) {
            Box(Modifier.size(28.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                Text(step.toString(), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun IconTile(icon: ImageVector, color: Color, size: Dp = 44.dp) {
    Box(Modifier.size(size).background(color.copy(alpha = .14f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = color, modifier = Modifier.size(size * 0.55f))
    }
}

/** Píldora de una línea: su alto es fijo y el texto se recorta con «…» en vez de crecer. */
@Composable
internal fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(28.dp)
            .background(color.copy(alpha = .14f), RoundedCornerShape(100))
            .padding(horizontal = 10.dp)
    ) {
        if (icon != null) { Icon(icon, null, tint = color, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(5.dp)) }
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Texto cuyo hueco se reserva aunque esté vacío o sea corto: [minLines] líneas siempre. */
@Composable
internal fun SlotText(
    text: String?,
    modifier: Modifier = Modifier,
    minLines: Int = 2,
    maxLines: Int = minLines,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null
) {
    Text(
        text ?: " ", modifier = modifier, style = style, color = color, fontWeight = fontWeight, textAlign = textAlign,
        minLines = minLines, maxLines = maxLines, overflow = TextOverflow.Ellipsis
    )
}

/** RSSI grande en un ancho fijo: «-63» y «—» ocupan lo mismo. */
@Composable
internal fun RssiReadout(rssi: Int?, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface, big: Boolean = true) {
    Column(modifier.width(if (big) 128.dp else 84.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            rssi?.toString() ?: "—", style = NumberStyle.copy(fontSize = if (big) 46.sp else 28.sp, fontWeight = FontWeight.Bold),
            color = color, maxLines = 1, softWrap = false, textAlign = TextAlign.Center
        )
        Text("dBm", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SignalBars(rssi: Int?, modifier: Modifier = Modifier, color: Color = AdminColors.beacon) {
    val bars = if (rssi == null) 0 else signalBarsForRssi(rssi)
    Row(modifier.height(18.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        repeat(4) { i ->
            Box(
                Modifier.width(5.dp).height((6 + i * 4).dp)
                    .background(if (i < bars) color else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp))
            )
        }
    }
}

/** Medidor horizontal −100…−30 dBm con marcador de pico opcional. Alto fijo. */
@Composable
internal fun RssiMeter(rssi: Int?, modifier: Modifier = Modifier, peak: Int? = null, color: Color = MaterialTheme.colorScheme.primary) {
    val track = MaterialTheme.colorScheme.outlineVariant
    val peakColor = AdminColors.bad
    Canvas(modifier.fillMaxWidth().height(14.dp)) {
        fun x(v: Int) = ((v.coerceIn(-100, -30) + 100) / 70f) * size.width
        drawRoundRect(track, cornerRadius = CornerRadius(7.dp.toPx()), size = Size(size.width, size.height))
        if (rssi != null) drawRoundRect(color, cornerRadius = CornerRadius(7.dp.toPx()), size = Size(x(rssi).coerceAtLeast(size.height), size.height))
        if (peak != null) drawLine(peakColor, Offset(x(peak), 0f), Offset(x(peak), size.height), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
    }
}

/** Punto que late mientras algo está activo. Tamaño fijo. */
@Composable
internal fun PulseDot(active: Boolean, color: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    val t = rememberInfiniteTransition(label = "pulse")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "p")
    Canvas(modifier.size(size)) {
        val c = center
        val r = this.size.minDimension / 2f
        if (active) drawCircle(color.copy(alpha = (1f - phase) * 0.45f), radius = r * (0.45f + 0.55f * phase))
        drawCircle(color, radius = r * 0.42f, center = c)
    }
}

/** Botón de ancho completo con relleno de progreso (mientras mide) y alto fijo. */
@Composable
internal fun ProgressButton(
    label: String,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    enabled: Boolean = true,
    height: Dp = 76.dp,
    container: Color = MaterialTheme.colorScheme.primary,
    content: Color = MaterialTheme.colorScheme.onPrimary,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier
            .fillMaxWidth().height(height).clip(shape)
            .background(if (progress != null) container.copy(alpha = .28f) else if (enabled) container else container.copy(alpha = .38f))
            .clickable(enabled = enabled && progress == null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (progress != null) {
            Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(container))
        }
        Text(
            label, style = NumberStyle.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold), color = if (progress != null) MaterialTheme.colorScheme.onSurface else content,
            maxLines = 1, softWrap = false, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp)
        )
    }
}

/** Barra fija con el estado del escaneo automático. Siempre 60 dp de alto, diga lo que diga. */
@Composable
internal fun ScanStatusBar(
    scan: BleScanState,
    detected: Int,
    hasPermission: Boolean,
    paused: Boolean,
    onTogglePause: () -> Unit,
    onGrant: () -> Unit,
    onEnableBluetooth: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bluetoothOff = hasPermission && !scan.bluetoothEnabled && scan.error?.contains("Bluetooth", ignoreCase = true) == true
    val (color, title, detail) = when {
        !hasPermission -> Triple(AdminColors.bad, "Falta el permiso de Bluetooth", "Sin él no se pueden buscar las balizas.")
        bluetoothOff -> Triple(AdminColors.bad, "Bluetooth apagado", "Enciéndelo para buscar las balizas.")
        paused -> Triple(AdminColors.warn, "Búsqueda en pausa", "Toca «Reanudar» para volver a buscar.")
        scan.error != null && !scan.isScanning -> Triple(AdminColors.bad, "El escaneo se detuvo", "Se reintenta solo cada pocos segundos.")
        detected == 0 -> Triple(AdminColors.warn, "Buscando balizas…", "Acércate y comprueba que estén encendidas.")
        else -> Triple(AdminColors.good, if (detected == 1) "1 baliza detectada" else "$detected balizas detectadas", "Se actualiza sola." + if (scan.restarts > 0) " · reinicios ${scan.restarts}" else "")
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth().height(60.dp)
            .background(color.copy(alpha = .12f), RoundedCornerShape(16.dp))
            .padding(start = 14.dp, end = 4.dp)
    ) {
        if (!hasPermission) Icon(Icons.Filled.Lock, null, tint = color, modifier = Modifier.size(20.dp))
        else if (bluetoothOff) Icon(Icons.Filled.BluetoothDisabled, null, tint = color, modifier = Modifier.size(20.dp))
        else PulseDot(active = scan.isScanning && !paused, color = color)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            !hasPermission -> TextButton(onClick = onGrant) { Text("Dar permiso") }
            bluetoothOff -> TextButton(onClick = onEnableBluetooth) { Icon(Icons.Filled.Bluetooth, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Encender") }
            else -> TextButton(onClick = onTogglePause) {
                Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                Text(if (paused) "Reanudar" else "Pausar")
            }
        }
    }
}

@Composable
internal fun BeaconNumberBadge(name: String, color: Color, size: Dp = 40.dp) {
    Box(Modifier.size(size).background(color.copy(alpha = .16f), CircleShape), contentAlignment = Alignment.Center) {
        Text(name.takeLast(2), style = NumberStyle.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp), color = color)
    }
}

/** Fila «etiqueta ··· valor» de una línea y alto fijo. */
@Composable
internal fun StatRow(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = NumberStyle.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = valueColor, maxLines = 1, softWrap = false)
    }
}
