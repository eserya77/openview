package com.example.openview.ui

import androidx.lifecycle.ViewModel
import com.example.openview.data.BleSignal
import com.example.openview.data.Destination
import com.example.openview.data.ActiveGraph
import com.example.openview.data.SimulatedRoute
import com.example.openview.data.VoiceSettings
import com.example.openview.nav.RouteEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Tiempo mínimo que el nodo destino debe llevar confirmado para dar la llegada. */
private const val ARRIVAL_HOLD_MS = 1_500L

/** Tiempo mínimo fuera de ruta antes de recalcular con A*. */
private const val OFF_ROUTE_HOLD_MS = 2_000L

/** Estado global de la app de navegación (todo simulado). */
data class NavigationUiState(
    val voiceSettings: VoiceSettings = VoiceSettings(),
    val selectedDestination: Destination? = null,
    val activeRoute: SimulatedRoute? = null,
    val currentStepIndex: Int = 0,
    val bleSignal: BleSignal = BleSignal.STABLE,
    val isPaused: Boolean = false,
    val lastVoiceTestMessage: String? = null,
    // --- Posicionamiento BLE real ---
    val detectedNodeId: String? = null,
    val detectedBeaconName: String? = null,
    val routePath: List<String> = emptyList(),
    val arrivedByBle: Boolean = false,
    val isSimulationMode: Boolean = false,
    val simulatedNodeIndex: Int = 0
) {
    val currentStep get() = activeRoute?.steps?.getOrNull(currentStepIndex)
    val progress: Float
        get() = activeRoute?.let {
            if (it.steps.isEmpty()) 0f
            else (currentStepIndex + 1).toFloat() / it.steps.size
        } ?: 0f
    val isLastStep get() = activeRoute?.let { currentStepIndex >= it.steps.lastIndex } ?: false
}

/**
 * Orquesta el estado de navegación. La lógica es simulada: no hay BLE, A* ni TTS reales.
 * Equivale conceptualmente al NavigationViewModel del diagrama de componentes C4.
 */
class NavigationViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(NavigationUiState(voiceSettings = com.example.openview.data.VoicePrefs.load()))
    val uiState: StateFlow<NavigationUiState> = _uiState.asStateFlow()

    // --- Configuración de voz ---
    fun updateVoiceSpeed(value: Float) = updateVoice { it.copy(speed = value) }

    fun updateVoiceVolume(value: Float) = updateVoice { it.copy(volume = value) }

    fun updateBeeps(on: Boolean) = updateVoice { it.copy(beeps = on) }

    fun updateVibration(on: Boolean) = updateVoice { it.copy(vibration = on) }

    private fun updateVoice(change: (VoiceSettings) -> VoiceSettings) = _uiState.update {
        val v = change(it.voiceSettings)
        com.example.openview.data.VoicePrefs.save(v)
        it.copy(voiceSettings = v)
    }

    fun testVoice() = _uiState.update {
        it.copy(lastVoiceTestMessage = "Reproduciendo: «Avanza 8 metros y gira a la derecha».")
    }

    fun clearVoiceTestMessage() = _uiState.update { it.copy(lastVoiceTestMessage = null) }

    // --- Selección de destino ---
    fun selectDestination(destination: Destination) =
        _uiState.update { it.copy(selectedDestination = destination) }

    // --- Ruta (A* sobre el grafo del piloto) ---
    fun buildRoute() = _uiState.update { state ->
        val dest = state.selectedDestination ?: return@update state
        val origin = state.detectedNodeId ?: ActiveGraph.startNode
        val path = RouteEngine.shortestPath(ActiveGraph.graph, origin, dest.id)
        state.copy(
            activeRoute = RouteEngine.buildRoute(ActiveGraph.graph, path, dest),
            routePath = path,
            currentStepIndex = 0,
            arrivedByBle = path.size <= 1
        )
    }

    fun buildSimulationRoute() = _uiState.update { state ->
        val dest = state.selectedDestination ?: return@update state
        val path = RouteEngine.shortestPath(ActiveGraph.graph, ActiveGraph.startNode, dest.id)
        val startNode = ActiveGraph.graph.node(ActiveGraph.startNode)
        state.copy(
            activeRoute = RouteEngine.buildRoute(ActiveGraph.graph, path, dest),
            routePath = path,
            currentStepIndex = 0,
            arrivedByBle = path.size <= 1,
            isSimulationMode = true,
            simulatedNodeIndex = 0,
            detectedNodeId = startNode.id,
            detectedBeaconName = startNode.beaconName,
            bleSignal = BleSignal.STABLE
        )
    }

    // --- Navegación activa ---
    /** Avance manual (respaldo para pruebas sin todas las balizas). */
    fun nextStep() = _uiState.update {
        val route = it.activeRoute ?: return@update it
        it.copy(currentStepIndex = (it.currentStepIndex + 1).coerceAtMost(route.steps.lastIndex))
    }

    fun togglePause() = _uiState.update { it.copy(isPaused = !it.isPaused) }

    fun advanceSimulationNode() = _uiState.update { st ->
        if (!st.isSimulationMode || st.routePath.isEmpty()) return@update st
        val route = st.activeRoute ?: return@update st
        val nextNodeIndex = (st.simulatedNodeIndex + 1).coerceAtMost(st.routePath.lastIndex)
        val node = ActiveGraph.graph.node(st.routePath[nextNodeIndex])
        val arrived = nextNodeIndex >= st.routePath.lastIndex
        st.copy(
            simulatedNodeIndex = nextNodeIndex,
            detectedNodeId = node.id,
            detectedBeaconName = node.beaconName,
            bleSignal = BleSignal.STABLE,
            currentStepIndex = nextNodeIndex.coerceAtMost(route.steps.lastIndex),
            arrivedByBle = arrived
        )
    }

    fun cancelRoute() = _uiState.update {
        it.copy(
            activeRoute = null, currentStepIndex = 0, isPaused = false,
            bleSignal = BleSignal.STABLE, routePath = emptyList(), arrivedByBle = false,
            isSimulationMode = false, simulatedNodeIndex = 0
        )
    }

    fun finishRoute() = _uiState.update {
        NavigationUiState(voiceSettings = it.voiceSettings)
    }

    /**
     * Actualización de posición proveniente del [com.example.openview.nav.BlePositioner].
     * Avanza el paso según el nodo detectado, recalcula con A* si el usuario aparece
     * fuera de la ruta, y marca la llegada al alcanzar el destino.
     *
     * [stableMs] es cuánto lleva confirmado el nodo actual. Sin esa espera un solo cuadro
     * en el nodo destino bastaba para declarar la llegada (o para recalcular la ruta).
     */
    fun onPositionUpdate(nodeId: String?, beaconName: String?, signal: BleSignal, stableMs: Long = Long.MAX_VALUE) = _uiState.update { st ->
        if (st.isSimulationMode) return@update st
        val base = st.copy(
            bleSignal = signal,
            detectedNodeId = nodeId ?: st.detectedNodeId,
            detectedBeaconName = beaconName ?: st.detectedBeaconName
        )
        val route = st.activeRoute
        if (nodeId == null || route == null || st.routePath.isEmpty()) return@update base

        val idx = st.routePath.indexOf(nodeId)
        if (idx < 0) {
            // Fuera de ruta: recalcular solo si el nodo lleva un rato confirmado.
            if (stableMs < OFF_ROUTE_HOLD_MS) return@update base
            val dest = st.selectedDestination ?: return@update base
            val newPath = RouteEngine.shortestPath(ActiveGraph.graph, nodeId, dest.id)
            return@update base.copy(
                routePath = newPath,
                activeRoute = RouteEngine.buildRoute(ActiveGraph.graph, newPath, dest),
                currentStepIndex = 0,
                arrivedByBle = newPath.size <= 1
            )
        }

        val atDestination = idx >= st.routePath.lastIndex
        base.copy(
            currentStepIndex = idx.coerceAtMost(route.steps.lastIndex),
            arrivedByBle = atDestination && stableMs >= ARRIVAL_HOLD_MS
        )
    }

    /** Override manual de la señal (botón "Reintentar" de la alerta). */
    fun recoverSignal() = _uiState.update { it.copy(bleSignal = BleSignal.STABLE) }
}
