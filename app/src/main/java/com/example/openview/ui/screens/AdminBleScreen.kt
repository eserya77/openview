package com.example.openview.ui.screens

import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.openview.ble.BleDeviceInfo
import com.example.openview.ble.BleScanner
import com.example.openview.ble.TARGET_BEACON_NAME
import com.example.openview.ble.hasBlePermissions
import com.example.openview.ble.requiredBlePermissions
import com.example.openview.ui.components.PrimaryActionButton
import com.example.openview.ui.components.ScreenScaffold
import com.example.openview.ui.components.ScreenTitle
import com.example.openview.ui.components.SecondaryActionButton
import com.example.openview.ui.theme.FailureRed
import com.example.openview.ui.theme.SuccessGreen
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Un dispositivo se considera "presente ahora" si se vio en los últimos 5 s. */
private const val PRESENT_WINDOW_MS = 5_000L

@Composable
fun AdminBleScreen(scanner: BleScanner, onBack: () -> Unit) {
    val state by scanner.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var hasPermission by remember { mutableStateOf(hasBlePermissions(context)) }
    // Por defecto solo mostramos las balizas NAV_BLE_* y ocultamos el resto del ruido.
    var onlyBeacons by remember { mutableStateOf(true) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasPermission = result.values.all { it }
        if (hasPermission) {
            scanner.refreshBluetoothState()
            scanner.startScan()
        }
    }

    // Reloj que avanza cada segundo para refrescar antigüedad y estado "presente".
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }

    ScreenScaffold(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(8.dp))
        ScreenTitle("Diagnóstico BLE")
        Text(
            "Panel de administrador. Busca el beacon «$TARGET_BEACON_NAME».",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        StatusRow(state.bluetoothEnabled, state.isScanning, state.supported)

        state.error?.let { err ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    err,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .padding(16.dp)
                        .semantics { liveRegion = LiveRegionMode.Assertive }
                )
            }
        }

        // Todas las balizas del piloto detectadas (NAV_BLE_01, 02, 03…).
        val targets = state.targets
        if (targets.isEmpty()) {
            TargetBeaconCard(target = null, now = now, isScanning = state.isScanning)
        } else {
            targets.forEach { beacon ->
                TargetBeaconCard(target = beacon, now = now, isScanning = state.isScanning)
            }
        }

        // Filtro de ruido: mostrar solo balizas NAV_BLE o todos los dispositivos.
        FilterToggle(onlyBeacons = onlyBeacons, onToggle = { onlyBeacons = !onlyBeacons })

        // Botón principal: pide permiso si hace falta, luego inicia/detiene.
        if (state.isScanning) {
            PrimaryActionButton(
                text = "Detener escaneo",
                icon = Icons.Filled.Stop,
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
                onClick = { scanner.stopScan() }
            )
        } else {
            PrimaryActionButton(
                text = "Iniciar escaneo",
                icon = Icons.Filled.PlayArrow,
                onClick = {
                    if (hasPermission) {
                        scanner.refreshBluetoothState()
                        scanner.startScan()
                    } else {
                        permissionLauncher.launch(requiredBlePermissions())
                    }
                }
            )
        }

        if (!hasPermission) {
            Text(
                "Se solicitarán permisos de Bluetooth cercano al iniciar el escaneo.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Lista de los demás dispositivos detectados (oculta si el filtro está activo).
        val others = if (onlyBeacons) emptyList() else state.devices.filter { !it.isTarget }
        if (others.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Otros dispositivos (${others.size})",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { heading() }
            )
            others.forEach { DeviceRow(it, now) }
        }

        Spacer(Modifier.height(8.dp))
        SecondaryActionButton(
            text = "Volver",
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            onClick = onBack
        )
    }
}

@Composable
private fun FilterToggle(onlyBeacons: Boolean, onToggle: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .semantics(mergeDescendants = true) {}
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Icon(
                if (onlyBeacons) Icons.Filled.FilterAlt else Icons.Filled.FilterAltOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "Solo balizas NAV_BLE",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Switch(checked = onlyBeacons, onCheckedChange = { onToggle() })
        }
    }
}

@Composable
private fun StatusRow(bluetoothEnabled: Boolean, isScanning: Boolean, supported: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatusChip(
            modifier = Modifier.weight(1f),
            icon = if (bluetoothEnabled) Icons.Filled.BluetoothConnected else Icons.Filled.BluetoothDisabled,
            label = "Bluetooth",
            value = when {
                !supported -> "No soportado"
                bluetoothEnabled -> "Activado"
                else -> "Apagado"
            },
            ok = supported && bluetoothEnabled
        )
        StatusChip(
            modifier = Modifier.weight(1f),
            icon = Icons.Filled.Radar,
            label = "Escaneo",
            value = if (isScanning) "Activo" else "Detenido",
            ok = isScanning
        )
    }
}

@Composable
private fun StatusChip(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    ok: Boolean
) {
    val accent = if (ok) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.semantics(mergeDescendants = true) {}
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 8.dp)
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                color = accent,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun TargetBeaconCard(target: BleDeviceInfo?, now: Long, isScanning: Boolean) {
    val present = target != null && (now - target.lastSeenElapsedMs) <= PRESENT_WINDOW_MS
    val containerColor = if (present) SuccessGreen.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface
    val statusText = when {
        present -> "DETECTADO"
        target != null -> "Señal perdida"
        isScanning -> "Buscando…"
        else -> "Sin detectar"
    }
    val statusColor = if (present) SuccessGreen else FailureRed
    val beaconName = target?.name ?: TARGET_BEACON_NAME

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = if (present && target != null) {
                    "Beacon $beaconName detectado. Señal ${target.signalLabel}, " +
                        "${target.rssi} dBm, distancia aproximada ${target.approxMeters.fmt()} metros."
                } else {
                    "Beacon $beaconName: $statusText."
                }
            }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (present) Icons.Filled.CheckCircle else Icons.Filled.Bluetooth,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    statusText,
                    style = MaterialTheme.typography.headlineMedium,
                    color = statusColor,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                beaconName,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (target != null) {
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Metric("RSSI", "${target.rssi} dBm")
                    Metric("Distancia", "≈ ${target.approxMeters.fmt()} m")
                    Metric("Calidad", target.signalLabel)
                }
                Spacer(Modifier.height(16.dp))
                SignalBars(target.signalBars)
                Spacer(Modifier.height(10.dp))
                val ageSec = ((now - target.lastSeenElapsedMs) / 1000).coerceAtLeast(0)
                Text(
                    if (present) "Visto hace ${ageSec}s" else "Última vez hace ${ageSec}s",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SignalBars(bars: Int) {
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (i in 1..4) {
            val active = i <= bars
            Box(
                modifier = Modifier
                    .width(18.dp)
                    .height((10 + i * 9).dp)
                    .clip(CircleShape)
                    .background(
                        if (active) SuccessGreen else MaterialTheme.colorScheme.surfaceVariant
                    )
            )
        }
    }
}

@Composable
private fun DeviceRow(device: BleDeviceInfo, now: Long) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Icon(
                Icons.Filled.SignalCellularAlt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    device.name ?: "(sin nombre)",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    device.address,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${device.rssi} dBm",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "≈ ${device.approxMeters.fmt()} m",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Formatea metros con un decimal; "—" si no es válido. */
private fun Double.fmt(): String =
    if (this < 0) "—" else ((this * 10).roundToInt() / 10.0).toString()
