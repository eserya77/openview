package com.example.openview.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.pow

/** Datos del beacon ESP32 que estamos buscando (coincide con el firmware Arduino). */
const val TARGET_BEACON_NAME = "NAV_BLE_01"
const val TARGET_SERVICE_UUID = "12345678-1234-1234-1234-123456789001"

/** Tiempo (ms) tras el cual un dispositivo se considera "perdido" si no se vuelve a ver. */
private const val DEVICE_TIMEOUT_MS = 10_000L

/** RSSI de referencia medido aproximadamente a 1 metro para un ESP32 a P3 dBm. */
const val RSSI_AT_ONE_METER = -59

/**
 * Estima la distancia en metros con el modelo log-distancia (n = 2).
 *
 * Punto único de esta fórmula: la usan [BleDeviceInfo.approxMeters], [com.example.openview.nav.BlePositioner]
 * y el registro de calibración. Si algún día se ajusta [RSSI_AT_ONE_METER] o el exponente
 * de pérdida de trayecto, basta con tocarlo aquí para que toda la app quede en sincronía.
 *
 * [referenceAt1m] permite usar la calibración propia de una baliza (ver
 * `RouteRepository.calibrationFor`) en vez de la constante global; por
 * defecto usa la global, así que el resto de la app no cambia si no la pasa.
 */
fun estimateMetersFromRssi(rssi: Int, referenceAt1m: Int = RSSI_AT_ONE_METER): Double {
    if (rssi == 0) return -1.0
    val ratio = (referenceAt1m - rssi) / (10.0 * 2.0)
    return 10.0.pow(ratio)
}

/** Información de un dispositivo BLE detectado. */
data class BleDeviceInfo(
    val address: String,
    val name: String?,
    val rssi: Int,
    val lastSeenElapsedMs: Long,
    val isTarget: Boolean
) {
    /** Estima la distancia en metros con el modelo log-distancia (n = 2). */
    val approxMeters: Double
        get() = estimateMetersFromRssi(rssi)

    /** Calidad de señal en 5 niveles (0 = nula, 4 = excelente). */
    val signalBars: Int
        get() = signalBarsForRssi(rssi)

    val signalLabel: String
        get() = signalLabelForBars(signalBars)
}

/** Calidad de señal en 5 niveles (0 = nula, 4 = excelente). Punto único: lo usa [BleDeviceInfo] y el mapa en vivo de calibración. */
fun signalBarsForRssi(rssi: Int): Int = when {
    rssi >= -55 -> 4
    rssi >= -67 -> 3
    rssi >= -78 -> 2
    rssi >= -90 -> 1
    else -> 0
}

fun signalLabelForBars(bars: Int): String = when (bars) {
    4 -> "Excelente"
    3 -> "Buena"
    2 -> "Regular"
    1 -> "Débil"
    else -> "Muy débil"
}

/** Estado observable del escaneo BLE. */
data class BleScanState(
    val supported: Boolean = true,
    val bluetoothEnabled: Boolean = false,
    val isScanning: Boolean = false,
    val devices: List<BleDeviceInfo> = emptyList(),
    val error: String? = null
) {
    val target: BleDeviceInfo? get() = devices.firstOrNull { it.isTarget }

    /** Todas las balizas del piloto detectadas (NAV_BLE_*), ordenadas por nombre. */
    val targets: List<BleDeviceInfo> get() = devices.filter { it.isTarget }.sortedBy { it.name ?: "" }
}

/**
 * Escáner BLE real basado en [BluetoothLeScanner]. Detecta todos los dispositivos
 * cercanos y marca el beacon objetivo (NAV_BLE_01 / UUID de servicio). No depende
 * del resto del prototipo: es una herramienta de diagnóstico para el panel admin.
 */
class BleScanner(private val context: Context) {

    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = manager?.adapter
    private var scanner: BluetoothLeScanner? = null

    private val _state = MutableStateFlow(BleScanState(supported = manager?.adapter != null))
    val state: StateFlow<BleScanState> = _state.asStateFlow()

    private val seen = mutableMapOf<String, BleDeviceInfo>()

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handleResult(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handleResult)
        override fun onScanFailed(errorCode: Int) {
            _state.update { it.copy(isScanning = false, error = "El escaneo falló (código $errorCode).") }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleResult(result: ScanResult) {
        val record = result.scanRecord
        val name = record?.deviceName ?: runCatching { result.device.name }.getOrNull()
        val serviceUuids = record?.serviceUuids?.map { it.uuid.toString() } ?: emptyList()
        // Reconoce cualquier baliza del piloto: NAV_BLE_01, NAV_BLE_02, NAV_BLE_03, ...
        val isTarget = (name?.startsWith("NAV_BLE_") == true) ||
            serviceUuids.any { it.equals(TARGET_SERVICE_UUID, ignoreCase = true) }

        val now = SystemClock.elapsedRealtime()
        seen[result.device.address] = BleDeviceInfo(
            address = result.device.address,
            name = name,
            rssi = result.rssi,
            lastSeenElapsedMs = now,
            isTarget = isTarget
        )
        // Elimina dispositivos no vistos recientemente para mantener la lista fresca.
        seen.entries.removeAll { now - it.value.lastSeenElapsedMs > DEVICE_TIMEOUT_MS }

        val list = seen.values.sortedWith(
            compareByDescending<BleDeviceInfo> { it.isTarget }.thenByDescending { it.rssi }
        )
        _state.update { it.copy(devices = list, error = null) }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        val adapter = adapter
        if (adapter == null) {
            _state.update { it.copy(supported = false, error = "Este dispositivo no soporta Bluetooth LE.") }
            return
        }
        if (!adapter.isEnabled) {
            _state.update { it.copy(bluetoothEnabled = false, error = "Activa el Bluetooth para escanear.") }
            return
        }
        scanner = adapter.bluetoothLeScanner
        seen.clear()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner?.startScan(null, settings, callback)
            _state.update {
                it.copy(isScanning = true, bluetoothEnabled = true, devices = emptyList(), error = null)
            }
        } catch (e: SecurityException) {
            _state.update { it.copy(isScanning = false, error = "Faltan permisos de Bluetooth.") }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        runCatching { scanner?.stopScan(callback) }
        _state.update { it.copy(isScanning = false) }
    }

    /** Re-lee si hay adaptador y si el Bluetooth está encendido. */
    fun refreshBluetoothState() {
        _state.update {
            it.copy(supported = adapter != null, bluetoothEnabled = adapter?.isEnabled == true)
        }
    }
}

/** Permisos necesarios según la versión de Android. */
fun requiredBlePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

fun hasBlePermissions(context: Context): Boolean =
    requiredBlePermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

@Composable
fun rememberBleScanner(): BleScanner {
    val context = LocalContext.current
    val scanner = remember { BleScanner(context.applicationContext) }
    DisposableEffect(Unit) {
        scanner.refreshBluetoothState()
        onDispose { scanner.stopScan() }
    }
    return scanner
}
