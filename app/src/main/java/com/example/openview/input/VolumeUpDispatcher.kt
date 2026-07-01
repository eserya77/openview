package com.example.openview.input

/**
 * Canal simple entre MainActivity y Compose para capturar VOLUME_UP solo cuando
 * una pantalla lo registra explícitamente, como el simulador de ruta.
 */
object VolumeUpDispatcher {
    private var listener: (() -> Unit)? = null

    fun register(onVolumeUp: () -> Unit) {
        listener = onVolumeUp
    }

    fun unregister(onVolumeUp: () -> Unit) {
        if (listener === onVolumeUp) listener = null
    }

    fun dispatch(): Boolean {
        val current = listener ?: return false
        current()
        return true
    }
}
