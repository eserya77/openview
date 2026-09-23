package com.example.openview.nav

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Último resultado de [BlePositioner], visible desde cualquier pantalla (por
 * ejemplo, el mapa en vivo de calibración del panel de administración) sin
 * tener que pasar la instancia de `BlePositioner` de `OpenViewApp` hacia
 * abajo por todo el árbol de navegación.
 *
 * Es un simple buzón de solo lectura para quien observa: quien calcula la
 * posición ([com.example.openview.ui.Navigation], en el mismo punto donde ya
 * llama a `positioner.update(...)`) es el único que debería escribir aquí.
 */
object LivePosition {
    private val _current = MutableStateFlow<BlePositioner.Result?>(null)
    val current: StateFlow<BlePositioner.Result?> = _current.asStateFlow()

    fun update(result: BlePositioner.Result) {
        _current.value = result
    }
}
