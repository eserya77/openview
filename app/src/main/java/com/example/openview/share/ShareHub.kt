package com.example.openview.share

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.openview.data.PlaceJson
import com.example.openview.data.PlaceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Permisos para compartir rutas: escanear, conectar y (guardia / pedir rutas) emitir. */
fun requiredSharePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

fun hasSharePermissions(context: Context): Boolean =
    requiredSharePermissions().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

/**
 * Compartir rutas entre el teléfono del GUARDIA y el de la PERSONA CIEGA.
 *
 * Escenario 1 (automático): el guardia anuncia sus rutas todo el rato. El teléfono de la persona ciega lo oye, avisa
 * («Hay rutas disponibles en Plaza San Miguel») con voz y con una notificación, y descarga si la persona acepta.
 *
 * Escenario 2 (directo): la persona pide las rutas; su teléfono dice un código de 4 cifras y lo emite. El guardia ve la
 * petición con ese código y pulsa «Enviar»; el teléfono que lo pidió descarga solo.
 */
object ShareHub {
    private const val OFFER_TTL_MS = 15_000L
    private const val REQUEST_TTL_MS = 3 * 60_000L
    private const val DIRECT_MS = 90_000L
    const val EXTRA_OPEN_ROUTES = "openview.open_routes"
    private const val CHANNEL = "rutas_cercanas"

    sealed class Download {
        object Idle : Download()
        data class Running(val name: String, val progress: Float, val attempt: Int) : Download()
        data class Done(val name: String) : Download()
        data class Failed(val message: String) : Download()
    }

    data class State(
        /** Guardias que anuncian rutas cerca (escenario 1). */
        val offers: List<ShareOffer> = emptyList(),
        /** Peticiones de personas cercanas (lo ve el guardia, escenario 2). */
        val requests: List<ShareOffer> = emptyList(),
        val download: Download = Download.Idle,
        val listening: Boolean = false,
        /** Mi petición en curso (escenario 2): el código que dije. */
        val myCode: Int? = null,
        // Guardia
        val guardSharing: Boolean = false,
        val guardAnnounce: Boolean = true,
        /** Código al que el guardia está enviando ahora mismo. */
        val guardDirectCode: Int? = null,
        val guardServed: Int = 0,
        val guardLog: List<String> = emptyList(),
        val error: String? = null
    )

    private var ctx: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _announcements = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Frases para decir en voz alta (o anunciar a TalkBack) estés en la pantalla que estés. */
    val announcements: SharedFlow<String> = _announcements.asSharedFlow()

    /** Se pidió abrir la pantalla de rutas (al tocar la notificación). */
    val openRoutesRequest = MutableStateFlow(false)

    private var scanner: ShareScanner? = null
    private var requestAdv: ShareAdvertiser? = null
    private var guardAdv: ShareAdvertiser? = null
    private var server: RouteGattServer? = null
    private var sweeper: Job? = null
    private var requestJob: Job? = null
    private var directJob: Job? = null
    private var downloadJob: Job? = null
    /** Lugares ya ofrecidos (por hash) en esta ejecución: no se repite la notificación. */
    private val notified = mutableSetOf<Int>()
    private val dismissed = mutableSetOf<Int>()

    fun initialize(context: Context) {
        if (ctx != null) return
        ctx = context.applicationContext
        scanner = ShareScanner(ctx!!, ::onAdvert)
        requestAdv = ShareAdvertiser(ctx!!)
        guardAdv = ShareAdvertiser(ctx!!)
        server = RouteGattServer(ctx!!) { addr -> scope.launch { onServed(addr) } }
    }

    private fun say(text: String) { _announcements.tryEmit(text) }

    // ───────────────────────────── Escucha (los dos papeles) ─────────────────────────────

    /** Enciende o apaga la escucha de anuncios. La app la enciende fuera de la guía y del panel de administración. */
    fun listen(on: Boolean) {
        val c = ctx ?: return
        val sc = scanner ?: return
        if (on && hasSharePermissions(c)) {
            val ok = sc.start()
            _state.update { it.copy(listening = ok) }
            if (sweeper?.isActive != true) sweeper = scope.launch {
                while (isActive) {
                    delay(2_000)
                    val now = SystemClock.elapsedRealtime()
                    _state.update { s ->
                        s.copy(offers = s.offers.filter { now - it.seenMs < OFFER_TTL_MS }, requests = s.requests.filter { now - it.seenMs < OFFER_TTL_MS })
                    }
                    if (!sc.running && hasSharePermissions(c)) _state.update { it.copy(listening = sc.start()) }
                }
            }
        } else {
            sc.stop(); sweeper?.cancel(); sweeper = null
            _state.update { it.copy(listening = false) }
        }
    }

    private fun onAdvert(o: ShareOffer) = scope.launch {
        when (o.type) {
            ShareProtocol.TYPE_ANNOUNCE -> {
                val isNew = _state.value.offers.none { it.address == o.address }
                _state.update { s -> s.copy(offers = (s.offers.filter { it.address != o.address } + o).sortedByDescending { it.rssi }) }
                if (isNew && o.hash !in notified && o.hash !in dismissed && !PlaceStore.hasHash(o.hash) && !_state.value.guardSharing) {
                    notified += o.hash
                    val name = o.name ?: "este establecimiento"
                    say("Hay rutas disponibles en $name. Para usarlas, abre Rutas del lugar y toca Descargar.")
                    notifyOffer(name)
                }
            }
            ShareProtocol.TYPE_REQUEST -> if (_state.value.guardSharing) {
                val isNew = _state.value.requests.none { it.code == o.code }
                _state.update { s -> s.copy(requests = (s.requests.filter { it.code != o.code } + o).sortedByDescending { it.rssi }) }
                if (isNew) say("Una persona pide las rutas. Código ${ShareProtocol.spoken(o.code)}.")
            }
            ShareProtocol.TYPE_DIRECT -> {
                val mine = _state.value.myCode
                if (mine != null && o.code == mine && _state.value.download !is Download.Running) {
                    stopRequest(silent = true)
                    download(o, automatic = true)
                }
            }
        }
    }

    // ───────────────────────────── Persona ciega ─────────────────────────────

    /** Escenario 1: descargar las rutas de un guardia oído. */
    fun download(offer: ShareOffer, automatic: Boolean = false) {
        val c = ctx ?: return
        if (downloadJob?.isActive == true) return
        val name = offer.name ?: "el establecimiento"
        downloadJob = scope.launch {
            say(if (automatic) "El guardia te está enviando las rutas de $name. Espera un momento." else "Descargando las rutas de $name.")
            var last: String? = null
            for (attempt in 1..3) {
                _state.update { it.copy(download = Download.Running(name, 0f, attempt)) }
                try {
                    val (json, infoName) = RouteDownloader.download(c, offer.address) { p ->
                        _state.update { it.copy(download = Download.Running(name, p, attempt)) }
                    }
                    val saved = PlaceStore.saveAndActivate(String(json, Charsets.UTF_8))
                    _state.update { it.copy(download = Download.Done(saved.place.name)) }
                    val placeName = saved.place.name.ifBlank { infoName }
                    say("Rutas de $placeName listas: ${saved.place.beacons.size} puntos. Toca Iniciar para elegir a dónde ir.")
                    return@launch
                } catch (e: PlaceJson.InvalidPlace) {
                    last = "Las rutas recibidas no son válidas: ${e.message}"; break
                } catch (e: Exception) {
                    last = e.message ?: "Error de conexión."
                    delay(1_000)
                }
            }
            _state.update { it.copy(download = Download.Failed(last ?: "No se pudo descargar.")) }
            say("No se pudieron descargar las rutas. Acércate al guardia e inténtalo otra vez.")
        }
    }

    fun dismissOffer(offer: ShareOffer) { dismissed += offer.hash; _state.update { s -> s.copy(offers = s.offers.filter { it.address != offer.address }) } }

    fun clearDownloadStatus() { _state.update { it.copy(download = Download.Idle) } }

    /** Escenario 2: pedir las rutas al guardia. Devuelve el código (o null si no se pudo emitir). */
    fun requestRoutes(onResult: (Int?, String?) -> Unit = { _, _ -> }) {
        val adv = requestAdv ?: return
        val code = ShareProtocol.newCode()
        listen(true)
        adv.start(ShareProtocol.Advert(ShareProtocol.TYPE_REQUEST, 0, code), name = null, connectable = false) { err ->
            scope.launch {
                if (err != null) { _state.update { it.copy(myCode = null, error = err) }; onResult(null, err); return@launch }
                _state.update { it.copy(myCode = code, error = null, download = Download.Idle) }
                onResult(code, null)
                requestJob?.cancel()
                requestJob = scope.launch { delay(REQUEST_TTL_MS); if (_state.value.myCode == code) { stopRequest(silent = true); say("La petición de rutas caducó. Puedes pedirlas otra vez.") } }
            }
        }
    }

    fun stopRequest(silent: Boolean = false) {
        requestAdv?.stop(); requestJob?.cancel()
        _state.update { it.copy(myCode = null) }
        if (!silent) say("Petición cancelada.")
    }

    // ───────────────────────────── Guardia ─────────────────────────────

    /** Empieza a compartir el lugar del guardia. [announce]: escenario 1 (anunciar siempre). */
    fun startGuard(announce: Boolean): String? {
        val c = ctx ?: return "Sin contexto."
        val g = PlaceStore.guard.value ?: return "Primero carga el JSON de la empresa."
        if (!hasSharePermissions(c)) return "Faltan permisos de Bluetooth."
        server?.start(g.json.toByteArray(Charsets.UTF_8), g.place.name)?.let { err -> _state.update { it.copy(error = err) }; return err }
        _state.update { it.copy(guardSharing = true, guardAnnounce = announce, error = null) }
        listen(true)
        applyGuardAdvert()
        log("Compartiendo «${g.place.name}» (${g.place.beacons.size} balizas, ${g.place.routes.size} rutas).")
        return null
    }

    fun setAnnounce(on: Boolean) { _state.update { it.copy(guardAnnounce = on) }; if (_state.value.guardSharing) applyGuardAdvert() }

    fun stopGuard() {
        guardAdv?.stop(); server?.stop(); directJob?.cancel()
        _state.update { it.copy(guardSharing = false, guardDirectCode = null, requests = emptyList()) }
        log("Dejó de compartir.")
    }

    /** Escenario 2: enviar las rutas a la persona que pidió con [code]. */
    fun sendTo(code: Int) {
        if (!_state.value.guardSharing) return
        _state.update { it.copy(guardDirectCode = code) }
        applyGuardAdvert()
        log("Enviando a la petición $code…")
        directJob?.cancel()
        directJob = scope.launch {
            delay(DIRECT_MS)
            if (_state.value.guardDirectCode == code) { _state.update { it.copy(guardDirectCode = null) }; applyGuardAdvert(); log("Envío a $code terminado (tiempo agotado).") }
        }
    }

    private fun applyGuardAdvert() {
        val adv = guardAdv ?: return
        val s = _state.value
        val g = PlaceStore.guard.value ?: return
        val advert = when {
            s.guardDirectCode != null -> ShareProtocol.Advert(ShareProtocol.TYPE_DIRECT, g.hash, s.guardDirectCode)
            s.guardAnnounce -> ShareProtocol.Advert(ShareProtocol.TYPE_ANNOUNCE, g.hash, 0)
            else -> null
        }
        if (advert == null) { adv.stop(); return }
        adv.start(advert, g.place.name, connectable = true) { err -> if (err != null) scope.launch { _state.update { it.copy(error = err) }; log(err) } }
    }

    private fun onServed(address: String) {
        _state.update { it.copy(guardServed = it.guardServed + 1) }
        val code = _state.value.guardDirectCode
        log("Rutas entregadas a un teléfono (…${address.takeLast(5)}).")
        if (code != null) {
            _state.update { s -> s.copy(guardDirectCode = null, requests = s.requests.filter { it.code != code }) }
            directJob?.cancel(); applyGuardAdvert()
            say("Rutas enviadas a la petición ${ShareProtocol.spoken(code)}.")
        }
    }

    private fun log(line: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        _state.update { it.copy(guardLog = (it.guardLog + "$t  $line").takeLast(30)) }
    }

    // ───────────────────────────── Notificación ─────────────────────────────

    private fun notifyOffer(name: String) {
        val c = ctx ?: return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(NotificationChannel(CHANNEL, "Rutas disponibles cerca", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Avisa cuando un establecimiento cercano comparte sus rutas."
            })
        }
        val intent = c.packageManager.getLaunchIntentForPackage(c.packageName)?.apply {
            putExtra(EXTRA_OPEN_ROUTES, true); addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        } ?: return
        val pi = PendingIntent.getActivity(c, 7, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Rutas disponibles en $name")
            .setContentText("Toca para descargarlas y usarlas con OpenView.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { NotificationManagerCompat.from(c).notify(4721, n) }
    }
}
