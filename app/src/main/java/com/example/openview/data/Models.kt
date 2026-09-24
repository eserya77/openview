package com.example.openview.data

/**
 * Modelos simples para el prototipo. Todos los datos son simulados (mock),
 * no hay BLE, A*, Room ni TTS reales en esta etapa.
 */

/** Destino navegable dentro del entorno piloto. */
data class Destination(
    val id: String,
    val name: String,
    val description: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

/** Una indicación individual dentro de una ruta. */
data class RouteStep(
    val instruction: String,
    val nextPoint: String
)

/** Ruta simulada entre un origen y un destino. */
data class SimulatedRoute(
    val origin: String,
    val destination: Destination,
    val steps: List<RouteStep>,
    val estimatedMinutes: Int
) {
    val stepCount: Int get() = steps.size
}

/** Estado simulado de la señal BLE. */
enum class BleSignal(val label: String) {
    STABLE("Señal estable"),
    WEAK("Señal débil"),
    LOST("Sin señal")
}

/** Configuración de voz simulada (no reproduce audio real). */
data class VoiceSettings(
    val speed: Float = 0.5f,  // 0f..1f
    val volume: Float = 0.7f, // 0f..1f
    /** Pitidos «frío/caliente» durante la guía. */
    val beeps: Boolean = true,
    /** Vibraciones (punto, llegada, corrección). */
    val vibration: Boolean = true
)
