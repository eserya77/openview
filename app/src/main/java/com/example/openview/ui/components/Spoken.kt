package com.example.openview.ui.components

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.example.openview.tts.TtsController
import kotlinx.coroutines.delay

/*
 * Voz «como TalkBack» sin necesitar TalkBack.
 *
 * - Si TalkBack está activado, la app NO habla por encima: TalkBack ya lee los botones (todos tienen su descripción).
 * - Si no lo está, la app hace de lector: anuncia cada pantalla al entrar, dice el nombre de cada botón al pulsarlo y
 *   los valores al cambiarlos. Así una persona ciega puede usar la app aunque no tenga TalkBack configurado.
 */

fun isTalkBackOn(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
    return am.isEnabled && am.isTouchExplorationEnabled
}

/** Lector integrado de una pantalla. [say] solo habla si TalkBack está apagado. */
class ScreenReader(private val tts: TtsController, private val talkBack: Boolean) {
    fun say(text: String) { if (!talkBack) tts.speak(text) }
    fun sayQueued(text: String) { if (!talkBack) tts.speakQueued(text) }
    /** Envuelve la acción de un botón: primero dice qué es, luego lo hace. */
    fun click(label: String, action: () -> Unit): () -> Unit = { say(label); action() }
    val active: Boolean get() = !talkBack
}

@Composable
fun rememberScreenReader(tts: TtsController): ScreenReader {
    val context = LocalContext.current
    return remember(tts) { ScreenReader(tts, isTalkBackOn(context)) }
}

/** Anuncia la pantalla al entrar (una frase, o varias en cola), si TalkBack está apagado. */
@Composable
fun AnnounceOnEnter(reader: ScreenReader, vararg lines: String) {
    LaunchedEffect(Unit) {
        if (!reader.active || lines.isEmpty()) return@LaunchedEffect
        delay(350)   // deja terminar la frase del botón que trajo aquí
        reader.say(lines.first())
        lines.drop(1).forEach { reader.sayQueued(it) }
    }
}
