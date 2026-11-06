package com.example.openview.share

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/*
 * Piezas Bluetooth para compartir rutas entre teléfonos (ver [ShareProtocol]). Las orquesta [ShareHub].
 */

/** Un anuncio de OpenView oído (de un guardia o de una persona que pide rutas). */
data class ShareOffer(
    val address: String,
    val type: Int,
    val hash: Int,
    val code: Int,
    val name: String?,
    val rssi: Int,
    val seenMs: Long
)

/** Emite un anuncio de OpenView (uno a la vez). */
@SuppressLint("MissingPermission")
class ShareAdvertiser(context: Context) {
    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private var callback: AdvertiseCallback? = null
    var current: ShareProtocol.Advert? = null; private set

    /** [onResult]: null si arrancó, o el mensaje de error. */
    fun start(advert: ShareProtocol.Advert, name: String?, connectable: Boolean, onResult: (String?) -> Unit = {}) {
        stop()
        val adv = manager?.adapter?.takeIf { it.isEnabled }?.bluetoothLeAdvertiser
        if (adv == null) { onResult("Este teléfono no puede emitir por Bluetooth (o el Bluetooth está apagado)."); return }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(connectable)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
            .addManufacturerData(ShareProtocol.COMPANY_ID, ShareProtocol.encode(advert))
            .build()
        val scanResponse = name?.let {
            AdvertiseData.Builder().setIncludeDeviceName(false)
                .addServiceData(ParcelUuid(ShareProtocol.NAME_UUID), ShareProtocol.nameBytes(it)).build()
        }
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) = onResult(null)
            override fun onStartFailure(errorCode: Int) {
                current = null
                onResult(when (errorCode) {
                    ADVERTISE_FAILED_DATA_TOO_LARGE -> "El anuncio es demasiado grande."
                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "El teléfono ya emite demasiados anuncios."
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Este teléfono no permite emitir anuncios Bluetooth."
                    else -> "No se pudo emitir (código $errorCode)."
                })
            }
        }
        callback = cb
        current = advert
        try {
            if (scanResponse != null) adv.startAdvertising(settings, data, scanResponse, cb) else adv.startAdvertising(settings, data, cb)
        } catch (e: SecurityException) { current = null; onResult("Falta el permiso para emitir por Bluetooth.") }
    }

    fun stop() {
        val cb = callback ?: return
        callback = null; current = null
        runCatching { manager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(cb) }
    }
}

/** Escucha anuncios de OpenView (guardias y peticiones). */
@SuppressLint("MissingPermission")
class ShareScanner(context: Context, private val onOffer: (ShareOffer) -> Unit) {
    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val names = ConcurrentHashMap<String, String>()
    var running = false; private set

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)
        override fun onScanFailed(errorCode: Int) { if (errorCode != SCAN_FAILED_ALREADY_STARTED) running = false }
    }

    private fun handle(r: ScanResult) {
        val rec = r.scanRecord ?: return
        val a = ShareProtocol.decode(rec.getManufacturerSpecificData(ShareProtocol.COMPANY_ID)) ?: return
        val addr = r.device.address
        rec.getServiceData(ParcelUuid(ShareProtocol.NAME_UUID))?.let { b -> names[addr] = String(b, Charsets.UTF_8) }
        onOffer(ShareOffer(addr, a.type, a.hash, a.code, names[addr], r.rssi, SystemClock.elapsedRealtime()))
    }

    /** true si arrancó. */
    fun start(): Boolean {
        if (running) return true
        val sc = manager?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return false
        // Filtro por el primer byte 0xC5 de los datos de fabricante: Android lo mantiene también con la pantalla apagada.
        val filter = ScanFilter.Builder()
            .setManufacturerData(ShareProtocol.COMPANY_ID, byteArrayOf(ShareProtocol.MARKER), byteArrayOf(0xFF.toByte()))
            .build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        return try { sc.startScan(listOf(filter), settings, callback); running = true; true } catch (e: SecurityException) { false }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { manager?.adapter?.bluetoothLeScanner?.stopScan(callback) }
    }
}

/** Servidor GATT del guardia: entrega el JSON comprimido por trozos. */
@SuppressLint("MissingPermission")
class RouteGattServer(private val context: Context, private val onServed: (address: String) -> Unit) {
    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private var server: BluetoothGattServer? = null
    @Volatile private var payload = ByteArray(0)
    @Volatile private var info = ByteArray(0)
    private val index = ConcurrentHashMap<String, Int>()
    private val mtu = ConcurrentHashMap<String, Int>()

    val running: Boolean get() = server != null

    /** Publica [json] (sin comprimir). Si ya corría, solo cambia el contenido. Devuelve null o el error. */
    fun start(json: ByteArray, name: String): String? {
        payload = ShareProtocol.gzip(json)
        info = ShareProtocol.info(payload.size, ShareProtocol.hash(json), name)
        if (server != null) return null
        val m = manager ?: return "Sin Bluetooth."
        val s = try { m.openGattServer(context, callback) } catch (e: SecurityException) { return "Falta el permiso de conexión Bluetooth." }
            ?: return "No se pudo abrir el servidor Bluetooth (¿Bluetooth apagado?)."
        val service = BluetoothGattService(ShareProtocol.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(BluetoothGattCharacteristic(ShareProtocol.INFO_UUID, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
        service.addCharacteristic(BluetoothGattCharacteristic(ShareProtocol.INDEX_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE))
        service.addCharacteristic(BluetoothGattCharacteristic(ShareProtocol.DATA_UUID, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
        s.addService(service)
        server = s
        return null
    }

    fun stop() {
        runCatching { server?.close() }
        server = null; index.clear(); mtu.clear()
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) { index.remove(device.address); mtu.remove(device.address) }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) { this@RouteGattServer.mtu[device.address] = mtu }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            val s = server ?: return
            val idx = index[device.address] ?: 0
            val full = when (characteristic.uuid) {
                ShareProtocol.INFO_UUID -> info
                ShareProtocol.DATA_UUID -> ShareProtocol.chunk(payload, idx)
                else -> null
            }
            if (full == null) { s.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null); return }
            val room = (mtu[device.address] ?: 23) - 1
            val part = if (offset >= full.size) ByteArray(0) else full.copyOfRange(offset, (offset + room).coerceAtMost(full.size))
            s.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, part)
            val last = (payload.size + ShareProtocol.CHUNK - 1) / ShareProtocol.CHUNK - 1
            if (characteristic.uuid == ShareProtocol.DATA_UUID && idx == last && offset + part.size >= full.size) onServed(device.address)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            val ok = characteristic.uuid == ShareProtocol.INDEX_UUID && value != null && value.size >= 2
            if (ok) index[device.address] = ((value!![0].toInt() and 0xFF) shl 8) or (value[1].toInt() and 0xFF)
            if (responseNeeded) server?.sendResponse(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, 0, null)
        }
    }
}

/** Descarga el JSON del guardia en [address]. Lanza IOException si algo falla (se reintenta desde [ShareHub]). */
@SuppressLint("MissingPermission")
object RouteDownloader {
    private sealed class Ev {
        data class Conn(val status: Int, val state: Int) : Ev()
        object Mtu : Ev()
        data class Services(val status: Int) : Ev()
        data class Read(val uuid: java.util.UUID, val value: ByteArray, val status: Int) : Ev()
        data class Write(val uuid: java.util.UUID, val status: Int) : Ev()
    }

    /** Devuelve el JSON (descomprimido) y su nombre. [onProgress] va de 0 a 1. */
    suspend fun download(context: Context, address: String, onProgress: (Float) -> Unit = {}): Pair<ByteArray, String> {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: throw IOException("Sin Bluetooth.")
        val device = manager.adapter?.getRemoteDevice(address) ?: throw IOException("Sin Bluetooth.")
        val ch = Channel<Ev>(Channel.UNLIMITED)
        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) { ch.trySend(Ev.Conn(status, newState)) }
            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) { ch.trySend(Ev.Mtu) }
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) { ch.trySend(Ev.Services(status)) }
            override fun onCharacteristicRead(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                ch.trySend(Ev.Read(c.uuid, value, status))
            }
            @Deprecated("Android 12 y anteriores")
            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                ch.trySend(Ev.Read(c.uuid, c.value ?: ByteArray(0), status))
            }
            override fun onCharacteristicWrite(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { ch.trySend(Ev.Write(c.uuid, status)) }
        }

        suspend fun <T> await(ms: Long, what: String, pick: (Ev) -> T?): T = try {
            withTimeout(ms) {
                var got: T? = null
                while (got == null) {
                    val e = ch.receive()
                    if (e is Ev.Conn && e.state == BluetoothProfile.STATE_DISCONNECTED) throw IOException("Se cortó la conexión con el guardia.")
                    got = pick(e)
                }
                got!!
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) { throw IOException("El guardia no respondió ($what).") }

        val gatt = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
            else device.connectGatt(context, false, cb)
        } catch (e: SecurityException) { throw IOException("Falta el permiso de conexión Bluetooth.") } ?: throw IOException("No se pudo conectar.")
        try {
            await(12_000, "conexión") { e -> (e as? Ev.Conn)?.takeIf { it.state == BluetoothProfile.STATE_CONNECTED } }
            if (gatt.requestMtu(517)) runCatching { await(3_000, "MTU") { e -> e as? Ev.Mtu } }
            if (!gatt.discoverServices()) throw IOException("No se pudieron leer los servicios.")
            await(10_000, "servicios") { e -> e as? Ev.Services }
            val svc = gatt.getService(ShareProtocol.SERVICE_UUID) ?: throw IOException("Ese teléfono no comparte rutas de OpenView.")
            val infoC = svc.getCharacteristic(ShareProtocol.INFO_UUID)
            val idxC = svc.getCharacteristic(ShareProtocol.INDEX_UUID)
            val dataC = svc.getCharacteristic(ShareProtocol.DATA_UUID)
            if (infoC == null || idxC == null || dataC == null) throw IOException("Servicio de rutas incompleto.")

            suspend fun read(c: BluetoothGattCharacteristic): ByteArray {
                if (!gatt.readCharacteristic(c)) throw IOException("No se pudo leer.")
                val r = await(8_000, "lectura") { e -> (e as? Ev.Read)?.takeIf { it.uuid == c.uuid } }
                if (r.status != BluetoothGatt.GATT_SUCCESS) throw IOException("Error de lectura (${r.status}).")
                return r.value
            }

            @Suppress("DEPRECATION")
            suspend fun writeIndex(i: Int) {
                val v = byteArrayOf((i shr 8).toByte(), i.toByte())
                val ok = if (Build.VERSION.SDK_INT >= 33) gatt.writeCharacteristic(idxC, v, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0
                else { idxC.value = v; idxC.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; gatt.writeCharacteristic(idxC) }
                if (!ok) throw IOException("No se pudo pedir el trozo $i.")
                val w = await(8_000, "escritura") { e -> (e as? Ev.Write)?.takeIf { it.uuid == idxC.uuid } }
                if (w.status != BluetoothGatt.GATT_SUCCESS) throw IOException("Error al pedir el trozo $i (${w.status}).")
            }

            val info = ShareProtocol.parseInfo(read(infoC)) ?: throw IOException("Datos del guardia no válidos.")
            val out = java.io.ByteArrayOutputStream(info.size)
            for (i in 0 until info.chunks) {
                writeIndex(i)
                val part = read(dataC)
                if (part.isEmpty()) throw IOException("Trozo $i vacío.")
                out.write(part)
                onProgress((i + 1f) / info.chunks)
            }
            val zipped = out.toByteArray()
            if (zipped.size != info.size) throw IOException("Descarga incompleta (${zipped.size} de ${info.size} bytes).")
            val json = ShareProtocol.gunzip(zipped)
            if (ShareProtocol.hash(json) != info.hash) throw IOException("Las rutas llegaron dañadas.")
            return json to info.name
        } finally {
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
            ch.close()
        }
    }
}
