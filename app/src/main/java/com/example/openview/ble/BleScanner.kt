package com.example.openview.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.pow

/** Datos del beacon ESP32 que estamos buscando (coincide con el firmware Arduino). */
const val TARGET_BEACON_NAME = "NAV_BLE_01"
const val TARGET_SERVICE_UUID = "12345678-1234-1234-1234-123456789001"

/** Tiempo (ms) tras el cual un dispositivo se considera "perdido" si no se vuelve a ver. */
private const val DEVICE_TIMEOUT_MS = 4_000L

/**
 * Vigilancia opcional del escaneo ([BleScanner.startKeepAlive]): Android (y sobre todo las capas de Xiaomi) puede dejar de
 * entregar resultados sin avisar. Si pasa este tiempo sin ningún anuncio, o sin ninguna baliza habiendo visto alguna antes,
 * el escaneo se reinicia. El mínimo entre reinicios queda muy por debajo del límite de Android (5 arranques cada 30 s).
 */
private const val SILENCE_RESTART_MS = 6_000L
private const val TARGET_SILENCE_RESTART_MS = 12_000L
private const val RESTART_MIN_GAP_MS = 8_000L

/** Si con filtro no aparece ninguna baliza en este tiempo, se vuelve al escaneo sin filtro (algunos móviles filtran mal). */
private const val FILTER_FALLBACK_MS = 10_000L

/**
 * Potencia TX que anuncia AHORA cada baliza (nivel 0..7 de los datos de fabricante). La usa el catálogo para saber si
 * una calibración se hizo con otra potencia (p. ej. antes de subir el firmware v3 a 01 y 03) y ya no vale.
 */
object BeaconTxRegistry {
    private val levels = java.util.concurrent.ConcurrentHashMap<String, Int>()
    fun update(name: String, level: Int?) { if (level != null) levels[name.uppercase()] = level }
    fun get(name: String): Int? = levels[name.uppercase()]
}

/**
 * Si llegan menos de estas muestras por segundo (sumando las balizas) con balizas presentes, el escaneo está
 * «estrangulado» (visto el 24-sep con el Redmi: ~1 muestra/s en vez de 8-12): se reinicia, y si iba con filtro, sin él.
 */
private const val MIN_TARGET_RATE_PER_S = 2.5
private const val RATE_WINDOW_MS = 8_000L

/** Company ID de los datos de fabricante del firmware v2 (0xFFFF = uso interno). */
private const val BEACON_COMPANY_ID = 0xFFFF

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

/**
 * Una muestra individual de RSSI: un anuncio recibido de una baliza. El positioner
 * se alimenta de estos eventos (no de la lista de dispositivos), así cada valor
 * entra al filtro una sola vez.
 */
data class BleSample(
    val address: String,
    val name: String,
    val rssi: Int,
    val tMs: Long,
    /** Versión de firmware anunciada en los datos de fabricante (v2+), si existe. */
    val fwVersion: Int? = null,
    /** Nivel de potencia TX anunciado (0..7; 7 = +9 dBm), si existe. */
    val txLevel: Int? = null
)

/** Información de un dispositivo BLE detectado. */
data class BleDeviceInfo(
    val address: String,
    val name: String?,
    val rssi: Int,
    val lastSeenElapsedMs: Long,
    val isTarget: Boolean,
    val fwVersion: Int? = null,
    val txLevel: Int? = null
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
    val error: String? = null,
    /** Cuántas veces la vigilancia tuvo que reiniciar el escaneo desde que se activó. */
    val restarts: Int = 0,
    /**
     * true = escaneo con filtro (solo balizas NAV_BLE_*): Android lo mantiene con la pantalla apagada.
     * false = sin filtro (respaldo): con la pantalla apagada Android lo pausa.
     */
    val filtered: Boolean = false,
    /** Muestras por segundo de balizas NAV_BLE (suma de todas) en los últimos segundos. */
    val targetRatePerS: Double = 0.0
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

    // Nombre aprendido por dirección: con el firmware v2 el nombre viaja en el anuncio
    // principal y el UUID en la respuesta de escaneo; según el teléfono, Android puede
    // entregarlos como dos resultados separados y uno de ellos vendría sin nombre.
    private val nameByAddress = mutableMapOf<String, String>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchdog: Job? = null
    private var wantScanning = false
    private var lastResultMs = 0L
    private var lastTargetMs = 0L
    private var lastStartMs = 0L
    private var useFilters = true
    private val targetTimes = ArrayDeque<Long>()

    private val _samples = MutableSharedFlow<BleSample>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Un evento por cada anuncio recibido de una baliza NAV_BLE_*. */
    val samples: SharedFlow<BleSample> = _samples.asSharedFlow()

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handleResult(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handleResult)
        override fun onScanFailed(errorCode: Int) {
            // 1 = ya estaba iniciado: no es un fallo, el escaneo sigue vivo.
            if (errorCode == ScanCallback.SCAN_FAILED_ALREADY_STARTED) return
            _state.update { it.copy(isScanning = false, error = "El escaneo falló (código $errorCode).") }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleResult(result: ScanResult) {
        val record = result.scanRecord
        val address = result.device.address
        val advertisedName = record?.deviceName ?: runCatching { result.device.name }.getOrNull()
        val serviceUuids = record?.serviceUuids?.map { it.uuid.toString() } ?: emptyList()

        // Datos de fabricante del firmware v2: [beaconId, fwVersion, txLevel].
        val mfg = record?.getManufacturerSpecificData(BEACON_COMPANY_ID)
        val beaconId = mfg?.getOrNull(0)?.toInt()?.takeIf { it in 1..99 }
        val fwVersion = mfg?.getOrNull(1)?.toInt()
        val txLevel = mfg?.getOrNull(2)?.toInt()

        var name = advertisedName ?: nameByAddress[address] ?: beaconId?.let { "NAV_BLE_%02d".format(it) }
        // Reconoce cualquier baliza del piloto: NAV_BLE_01, NAV_BLE_02, NAV_BLE_03, ...
        val isTarget = (name?.startsWith("NAV_BLE_") == true) ||
            serviceUuids.any { it.equals(TARGET_SERVICE_UUID, ignoreCase = true) } ||
            (mfg != null && beaconId != null)
        if (isTarget && name != null) nameByAddress[address] = name
        if (name == null && isTarget) name = nameByAddress[address]

        // Instante real en que se oyó el anuncio (Android puede entregar varios juntos: con la hora de entrega se
        // amontonarían y el filtro/Kalman vería saltos falsos).
        val now = SystemClock.elapsedRealtime()
        val heardAt = (result.timestampNanos / 1_000_000L).takeIf { it in (now - 5_000L)..now } ?: now
        lastResultMs = now
        if (isTarget) {
            lastTargetMs = now
            synchronized(targetTimes) { targetTimes.addLast(now); while (targetTimes.isNotEmpty() && now - targetTimes.first() > RATE_WINDOW_MS) targetTimes.removeFirst() }
            if (name != null) BeaconTxRegistry.update(name, txLevel)
        }
        val previous = seen[address]
        seen[address] = BleDeviceInfo(
            address = address,
            name = name,
            rssi = result.rssi,
            lastSeenElapsedMs = now,
            isTarget = isTarget,
            fwVersion = fwVersion ?: previous?.fwVersion,
            txLevel = txLevel ?: previous?.txLevel
        )
        // Elimina dispositivos no vistos recientemente para mantener la lista fresca.
        seen.entries.removeAll { now - it.value.lastSeenElapsedMs > DEVICE_TIMEOUT_MS }

        if (isTarget && name != null) {
            _samples.tryEmit(BleSample(address, name, result.rssi, heardAt, fwVersion ?: previous?.fwVersion, txLevel ?: previous?.txLevel))
        }

        val list = seen.values.sortedWith(
            compareByDescending<BleDeviceInfo> { it.isTarget }.thenByDescending { it.rssi }
        )
        _state.update { it.copy(devices = list, error = null) }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        wantScanning = true
        beginScan(clear = true)
    }

    /** Arranca el escaneo si aún no corre y NO borra lo ya detectado. Es seguro llamarlo varias veces. */
    fun ensureScanning() {
        wantScanning = true
        if (!_state.value.isScanning) beginScan(clear = false)
    }

    @SuppressLint("MissingPermission")
    private fun beginScan(clear: Boolean) {
        val adapter = adapter
        if (adapter == null) {
            _state.update { it.copy(supported = false, error = "Este dispositivo no soporta Bluetooth LE.") }
            return
        }
        if (!adapter.isEnabled) {
            _state.update { it.copy(bluetoothEnabled = false, isScanning = false, error = "Activa el Bluetooth para escanear.") }
            return
        }
        // Si quedó un escaneo previo (reinicio), se detiene antes para no duplicar el callback.
        runCatching { scanner?.stopScan(callback) }
        scanner = adapter.bluetoothLeScanner
        if (clear) { seen.clear(); nameByAddress.clear() }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        // Filtros: por nombre (NAV_BLE_01..06, viaja en el anuncio principal) y por datos de fabricante 0xFFFF (firmware v2+).
        // Se combinan con O. Sin filtro, Android pausa el escaneo con la pantalla apagada y lo degrada con el tiempo.
        val filters: List<ScanFilter>? = if (useFilters)
            (1..6).map { ScanFilter.Builder().setDeviceName("NAV_BLE_%02d".format(it)).build() } +
                ScanFilter.Builder().setManufacturerData(BEACON_COMPANY_ID, ByteArray(0)).build()
        else null
        val now = SystemClock.elapsedRealtime()
        lastStartMs = now
        lastResultMs = now
        lastTargetMs = 0L
        synchronized(targetTimes) { targetTimes.clear() }
        try {
            scanner?.startScan(filters, settings, callback)
            _state.update {
                it.copy(isScanning = true, bluetoothEnabled = true, devices = if (clear) emptyList() else it.devices, error = null, filtered = filters != null)
            }
            // Respaldo también fuera del panel (navegación normal): si con filtro no aparece nada, se quita el filtro.
            if (filters != null) {
                val startedAt = now
                scope.launch {
                    delay(FILTER_FALLBACK_MS)
                    if (wantScanning && useFilters && lastStartMs == startedAt && lastTargetMs == 0L) {
                        useFilters = false
                        beginScan(clear = false)
                    }
                }
            }
        } catch (e: SecurityException) {
            _state.update { it.copy(isScanning = false, error = "Faltan permisos de Bluetooth.") }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        wantScanning = false
        runCatching { scanner?.stopScan(callback) }
        _state.update { it.copy(isScanning = false) }
    }

    /**
     * Escaneo automático y vigilado (lo usa el panel de administración): arranca solo, quita de la lista las balizas
     * que dejan de oírse aunque no llegue ningún anuncio nuevo, y reinicia el escaneo si Android lo deja mudo.
     */
    fun startKeepAlive() {
        ensureScanning()
        if (watchdog?.isActive == true) return
        watchdog = scope.launch {
            while (true) {
                delay(1_000)
                refreshBluetoothState()
                val now = SystemClock.elapsedRealtime()
                pruneStale(now)
                if (!wantScanning) continue
                if (adapter?.isEnabled != true) {
                    if (_state.value.isScanning) _state.update { it.copy(isScanning = false, error = "Activa el Bluetooth para escanear.") }
                    continue
                }
                val sinceStart = now - lastStartMs
                val running = _state.value.isScanning
                val rate = synchronized(targetTimes) {
                    while (targetTimes.isNotEmpty() && now - targetTimes.first() > RATE_WINDOW_MS) targetTimes.removeFirst()
                    targetTimes.size / (RATE_WINDOW_MS / 1000.0)
                }
                if (kotlin.math.abs(rate - _state.value.targetRatePerS) >= 0.1) _state.update { it.copy(targetRatePerS = rate) }
                val starved = running && sinceStart >= RATE_WINDOW_MS + 2_000L && lastTargetMs > 0L && now - lastTargetMs < 3_000L && rate < MIN_TARGET_RATE_PER_S
                val silent = now - lastResultMs >= SILENCE_RESTART_MS
                val targetsLost = lastTargetMs > 0L && now - lastTargetMs >= TARGET_SILENCE_RESTART_MS
                if (useFilters && running && lastTargetMs == 0L && sinceStart >= FILTER_FALLBACK_MS) {
                    // Con filtro no llegó nada: este teléfono no lo respeta bien. Respaldo sin filtro.
                    useFilters = false
                    beginScan(clear = false)
                    _state.update { it.copy(restarts = it.restarts + 1) }
                } else if (starved) {
                    // Balizas presentes pero casi sin muestras: escaneo estrangulado. Con filtro, se prueba sin él.
                    if (useFilters) useFilters = false
                    beginScan(clear = false)
                    _state.update { it.copy(restarts = it.restarts + 1) }
                } else if (sinceStart >= RESTART_MIN_GAP_MS && (!running || silent || targetsLost)) {
                    beginScan(clear = false)
                    _state.update { it.copy(restarts = it.restarts + 1) }
                }
            }
        }
    }

    fun stopKeepAlive() {
        watchdog?.cancel(); watchdog = null
        stopScan()
    }

    /** Quita de la lista lo que lleva más de [DEVICE_TIMEOUT_MS] sin oírse, aunque no haya llegado ningún anuncio nuevo. */
    private fun pruneStale(now: Long) {
        val before = seen.size
        seen.entries.removeAll { now - it.value.lastSeenElapsedMs > DEVICE_TIMEOUT_MS }
        if (seen.size != before) {
            val list = seen.values.sortedWith(compareByDescending<BleDeviceInfo> { it.isTarget }.thenByDescending { it.rssi })
            _state.update { it.copy(devices = list) }
        }
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
