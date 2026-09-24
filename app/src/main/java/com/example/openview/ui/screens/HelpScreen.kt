package com.example.openview.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.openview.tts.TtsController
import com.example.openview.ui.components.PrimaryActionButton
import com.example.openview.ui.components.ScreenScaffold
import com.example.openview.ui.components.ScreenTitle
import com.example.openview.ui.components.SecondaryActionButton
import com.example.openview.ui.components.rememberScreenReader

/** Consejos de uso de la guía. Se leen en voz alta al entrar y cada uno al tocarlo. */
val HELP_ITEMS = listOf(
    "Toca Iniciar, elige a dónde quieres ir y camina junto a la pared guía.",
    "Al empezar, la app busca dónde estás. Quédate quieto unos segundos.",
    "Oirás pitidos: más rápidos y más agudos cuanto más cerca estás del siguiente punto.",
    "En cada punto el móvil vibra una vez y te dice su nombre y cuánto falta para el siguiente.",
    "Si vas en sentido contrario, notarás una vibración larga y te dirá que des la vuelta.",
    "Al llegar, vibra tres veces y dice: llegaste. Detente ahí.",
    "Pulsa el botón de subir volumen en cualquier momento para repetir la última instrucción.",
    "Lleva el móvil colgado al pecho, con la pantalla hacia fuera.",
    "En Configurar voz puedes cambiar la velocidad de la voz, el volumen, los pitidos y la vibración."
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(tts: TtsController, onBack: () -> Unit) {
    val reader = rememberScreenReader(tts)
    /** [force] = lo pidió la persona (botón): se lee aunque TalkBack esté activado. */
    fun readAll(force: Boolean = false) {
        val first: (String) -> Unit = if (force) tts::speak else reader::say
        val next: (String) -> Unit = if (force) tts::speakQueued else reader::sayQueued
        first("Ayuda. ${HELP_ITEMS.size} consejos.")
        HELP_ITEMS.forEachIndexed { i, t -> next("${i + 1}. $t") }
        next("Toca un consejo para volver a oírlo. Abajo están los botones Leer todo y Volver.")
    }
    // Al entrar se lee toda la ayuda (si TalkBack está activado, lo hace TalkBack al recorrer la pantalla).
    androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(350); readAll() }

    ScreenScaffold(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.height(8.dp))
        ScreenTitle("Ayuda")
        HELP_ITEMS.forEachIndexed { index, item ->
            Card(
                onClick = { tts.speak("${index + 1}. $item") },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "Consejo ${index + 1}: $item" }
            ) {
                Row(Modifier.padding(18.dp)) {
                    Text("${index + 1}. ", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    Text(item, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        PrimaryActionButton(text = "Leer todo", icon = Icons.Filled.RecordVoiceOver, onClick = { readAll(force = true) })
        SecondaryActionButton(text = "Volver", icon = Icons.AutoMirrored.Filled.ArrowBack, onClick = reader.click("Volver", onBack))
    }
}
