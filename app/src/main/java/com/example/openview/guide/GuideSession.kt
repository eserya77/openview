package com.example.openview.guide

import android.os.SystemClock
import com.example.openview.ble.BleScanner
import com.example.openview.data.RouteRepository
import com.example.openview.nav.GuideEngine
import com.example.openview.nav.GuideEvent
import com.example.openview.nav.GuideEventKind
import com.example.openview.nav.GuideSpot
import com.example.openview.nav.GuideState
import com.example.openview.tts.TtsController
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Qué recibe la persona usuaria: se elige en «Configurar voz» (producto) o en la guía de prueba (admin). */
data class GuideFeedbackOptions(val voice: Boolean = true, val beeps: Boolean = true, val vibration: Boolean = true)

/**
 * Una guía en marcha: une el [GuideEngine] con el escáner, la voz, los pitidos «frío/caliente», la vibración y el
 * acelerómetro. La usan la guía del producto (botón «Iniciar») y la guía de prueba del panel de administración, así
 * las dos se comportan exactamente igual.
 */
class GuideSession(
    val route: List<GuideSpot>,
    private val tts: TtsController?,
    private val sonar: SonarPlayer,
    private val haptics: Haptics,
    private val motion: MotionDetector,
    options: GuideFeedbackOptions,
    /** Se llama con cada evento (p. ej. para registrarlo en el CSV). */
    private val onEvent: (GuideEvent) -> Unit = {},
    /** Se llama en cada tic (≈ 5 veces por segundo) con el estado. */
    private val onTick: (GuideState, Long) -> Unit = { _, _ -> }
) {
    @Volatile var options: GuideFeedbackOptions = options

    val engine: GuideEngine = run {
        val liveTx = { b: String -> com.example.openview.ble.BeaconTxRegistry.get(b) }
        GuideEngine(
            route,
            refAt1m = { b -> if (RouteRepository.isCalibrated(b)) RouteRepository.calibrationFor(b).toDouble() else null },
            contactOf = { b -> RouteRepository.profileFor(b)?.takeUnless { it.isStaleFor(liveTx(b)) }?.contactRssi }
        )
    }

    private val _state = MutableStateFlow(GuideState())
    val state: StateFlow<GuideState> = _state.asStateFlow()
    private val _events = MutableStateFlow<List<GuideEvent>>(emptyList())
    val events: StateFlow<List<GuideEvent>> = _events.asStateFlow()

    val startedAtMs: Long = SystemClock.elapsedRealtime()

    private fun speak(text: String) { if (options.voice) tts?.speak(text) }

    /** Repite la última instrucción (botón «Repetir» o Vol+). */
    fun repeatInstruction() {
        val text = _state.value.instruction.ifBlank { "Camina despacio. Te avisaré en cada punto." }
        tts?.speak(text)
    }

    /** Corre la guía hasta que se cancele la corrutina que la llama. */
    suspend fun run(scanner: BleScanner) = coroutineScope {
        launch { scanner.samples.collect { s -> engine.onSample(s.name, s.rssi, s.tMs) } }
        launch {
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val fresh = engine.tick(now, motion.moving)
                fresh.forEach { ev ->
                    _events.value = (_events.value + ev).takeLast(80)
                    onEvent(ev)
                    val o = options
                    when (ev.kind) {
                        GuideEventKind.ARRIVED -> { speak(ev.text); if (o.vibration) haptics.arrived() }
                        GuideEventKind.PASSED, GuideEventKind.AT_SPOT -> { speak(ev.text); if (o.vibration) haptics.milestone() }
                        GuideEventKind.WRONG_WAY, GuideEventKind.COLDER -> {
                            if (o.beeps) sonar.warn(); if (o.vibration) haptics.correct(); speak(ev.text)
                        }
                        GuideEventKind.START, GuideEventKind.LOST, GuideEventKind.FOUND -> speak(ev.text)
                    }
                }
                _state.value = engine.state
                onTick(engine.state, now)
                delay(200)
            }
        }
        launch {
            // Pitidos: más rápidos y agudos al acercarte al siguiente punto. Se callan al llegar o sin señal del objetivo.
            while (isActive) {
                val st = engine.state
                val heard = st.tracks.any { it.beacon == route.getOrNull(st.target)?.beacon }
                if (!options.beeps || st.arrived || !heard) { delay(400); continue }
                sonar.beep(st.targetTemperature)
                delay(SonarPlayer.intervalMs(st.targetTemperature))
            }
        }
    }
}

/**
 * ¿Junto a qué punto de [spots] está la persona? Escucha unos segundos y elige la baliza con mayor nivel relativo a su
 * referencia a 1 m. Null si no se oye ninguna. Lo usa «Iniciar» para saber desde dónde empezar la ruta.
 */
object StartLocator {
    data class Guess(val spot: GuideSpot, val relDb: Double, val marginDb: Double)

    fun nearest(spots: List<GuideSpot>, samples: Map<String, List<Int>>, minSamples: Int = 3): Guess? {
        val rel = spots.mapNotNull { s ->
            val v = samples[s.beacon]?.takeIf { it.size >= minSamples }?.sorted() ?: return@mapNotNull null
            val median = v[v.size / 2].toDouble()
            val ref = if (RouteRepository.isCalibrated(s.beacon)) RouteRepository.calibrationFor(s.beacon).toDouble() else -80.0
            s to median - ref
        }.sortedByDescending { it.second }
        val best = rel.firstOrNull() ?: return null
        return Guess(best.first, best.second, best.second - (rel.getOrNull(1)?.second ?: -99.0))
    }
}
