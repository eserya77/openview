package com.example.openview.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * Envoltura del motor de Text-To-Speech nativo de Android.
 * Reproduce instrucciones reales en español. La velocidad y el volumen
 * se controlan desde la configuración de voz de la app.
 */
class TtsController(context: Context) {

    private var ready = false
    private var pending: String? = null

    /** Velocidad de habla (1.0 = normal). */
    var rate: Float = 1.0f
    /** Volumen 0..1. */
    var volume: Float = 1.0f

    private val tts: TextToSpeech

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.forLanguageTag("es-ES")
                ready = true
                pending?.let { speak(it); pending = null }
            }
        }
    }

    /** Reproduce el texto, interrumpiendo cualquier locución en curso. */
    fun speak(text: String) {
        if (text.isBlank()) return
        if (!ready) {
            pending = text // se reproducirá cuando el motor esté listo
            return
        }
        tts.setSpeechRate(rate)
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0f, 1f))
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "openview-utt")
    }

    fun stop() {
        if (ready) tts.stop()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}

/** Crea y recuerda un TtsController ligado al ciclo de vida del composable. */
@Composable
fun rememberTtsController(): TtsController {
    val context = LocalContext.current
    val controller = remember { TtsController(context.applicationContext) }
    DisposableEffect(Unit) {
        onDispose { controller.shutdown() }
    }
    return controller
}
