package com.example.openview.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.openview.ble.hasBlePermissions
import com.example.openview.ble.rememberBleScanner
import com.example.openview.input.VolumeUpDispatcher
import com.example.openview.nav.BlePositioner
import com.example.openview.tts.rememberTtsController
import com.example.openview.ui.screens.ActiveNavigationScreen
import com.example.openview.ui.screens.AdminBleScreen
import com.example.openview.ui.screens.ArrivalScreen
import com.example.openview.ui.screens.DestinationSelectionScreen
import com.example.openview.ui.screens.FailureScreen
import com.example.openview.ui.screens.GraphShapeScreen
import com.example.openview.ui.screens.HelpScreen
import com.example.openview.ui.screens.OnboardingScreen
import com.example.openview.ui.screens.RoutePreviewScreen
import com.example.openview.ui.screens.SimulatorMenuScreen
import com.example.openview.ui.screens.VoiceConfigScreen

/** Rutas de navegación del prototipo. */
object Routes {
    const val ONBOARDING = "onboarding"
    const val VOICE_CONFIG = "voice_config"
    const val DESTINATION = "destination"
    const val ROUTE_PREVIEW = "route_preview"
    const val ACTIVE_NAV = "active_nav"
    const val ARRIVAL = "arrival"
    const val FAILURE = "failure"
    const val HELP = "help"
    const val ADMIN_BLE = "admin_ble"
    const val GRAPH_SHAPE = "graph_shape"
    const val SIMULATOR_MENU = "simulator_menu"
    const val SIMULATOR_DESTINATION = "simulator_destination"
    const val SIMULATOR_PREVIEW = "simulator_preview"
    const val SIMULATOR_ACTIVE_NAV = "simulator_active_nav"
}

@Composable
fun OpenViewApp(
    modifier: Modifier = Modifier,
    viewModel: NavigationViewModel = viewModel()
) {
    val navController = rememberNavController()
    val state by viewModel.uiState.collectAsState()
    val tts = rememberTtsController()
    val bleScanner = rememberBleScanner()
    val bleState by bleScanner.state.collectAsState()
    val positioner = remember { BlePositioner() }
    val context = LocalContext.current

    // Sincroniza la configuración de voz con el motor TTS real.
    LaunchedEffect(state.voiceSettings) {
        // velocidad: 0..1 -> 0.5x..2.0x (0.5 ≈ normal)
        tts.rate = 0.5f + state.voiceSettings.speed * 1.5f
        tts.volume = state.voiceSettings.volume
    }

    // Convierte las señales BLE en posición (nodo del grafo) y la pasa al ViewModel.
    LaunchedEffect(bleState.devices) {
        val pos = positioner.update(bleState.devices)
        viewModel.onPositionUpdate(pos.nodeId, pos.beaconName, pos.signal)
    }

    NavHost(
        navController = navController,
        startDestination = Routes.ONBOARDING,
        modifier = modifier
    ) {

        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                tts = tts,
                onStart = { navController.navigate(Routes.DESTINATION) },
                onConfigureVoice = { navController.navigate(Routes.VOICE_CONFIG) },
                onHelp = { navController.navigate(Routes.HELP) },
                onAdmin = { navController.navigate(Routes.ADMIN_BLE) },
                onChooseShape = { navController.navigate(Routes.GRAPH_SHAPE) },
                onSimulator = { navController.navigate(Routes.SIMULATOR_MENU) }
            )
        }

        composable(Routes.GRAPH_SHAPE) {
            GraphShapeScreen(
                onSelected = {
                    // Cambiar de forma descarta cualquier ruta previa.
                    viewModel.cancelRoute()
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.VOICE_CONFIG) {
            VoiceConfigScreen(
                state = state,
                tts = tts,
                onSpeedChange = viewModel::updateVoiceSpeed,
                onVolumeChange = viewModel::updateVoiceVolume,
                onTestVoice = viewModel::testVoice,
                onDismissTestMessage = viewModel::clearVoiceTestMessage,
                onSave = { navController.popBackStack() },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.SIMULATOR_MENU) {
            SimulatorMenuScreen(
                onChooseDestination = { navController.navigate(Routes.SIMULATOR_DESTINATION) },
                onChooseShape = { navController.navigate(Routes.GRAPH_SHAPE) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.DESTINATION) {
            DestinationSelectionScreen(
                onSelect = { destination ->
                    // Al elegir destino calculamos la ruta (A*) desde el nodo detectado.
                    viewModel.selectDestination(destination)
                    viewModel.buildRoute()
                    navController.navigate(Routes.ROUTE_PREVIEW)
                },
                onHelp = { navController.navigate(Routes.HELP) }
            )
        }

        composable(Routes.SIMULATOR_DESTINATION) {
            DestinationSelectionScreen(
                onSelect = { destination ->
                    viewModel.selectDestination(destination)
                    viewModel.buildSimulationRoute()
                    navController.navigate(Routes.SIMULATOR_PREVIEW)
                },
                onHelp = { navController.navigate(Routes.HELP) }
            )
        }

        composable(Routes.ROUTE_PREVIEW) {
            // Escanea ya en el preview para detectar el origen real antes de iniciar.
            DisposableEffect(Unit) {
                if (hasBlePermissions(context)) bleScanner.startScan()
                onDispose { }
            }
            RoutePreviewScreen(
                state = state,
                onStart = { navController.navigate(Routes.ACTIVE_NAV) },
                onRedetect = { viewModel.buildRoute() }, // recalcula desde el nodo detectado
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.SIMULATOR_PREVIEW) {
            RoutePreviewScreen(
                state = state,
                onStart = { navController.navigate(Routes.SIMULATOR_ACTIVE_NAV) },
                onRedetect = { viewModel.buildSimulationRoute() },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.ACTIVE_NAV) {
            // Mantiene el escaneo activo durante la navegación.
            DisposableEffect(Unit) {
                if (hasBlePermissions(context)) bleScanner.startScan()
                onDispose { bleScanner.stopScan() }
            }
            // Llegada automática al detectar el nodo destino por BLE.
            LaunchedEffect(state.arrivedByBle) {
                if (state.arrivedByBle) {
                    navController.navigate(Routes.ARRIVAL) { popUpTo(Routes.DESTINATION) }
                }
            }
            ActiveNavigationScreen(
                state = state,
                tts = tts,
                onNextStep = viewModel::nextStep,
                onTogglePause = viewModel::togglePause,
                onArrived = {
                    navController.navigate(Routes.ARRIVAL) {
                        popUpTo(Routes.DESTINATION)
                    }
                },
                onDeviated = { navController.navigate(Routes.FAILURE) },
                onCancel = {
                    viewModel.cancelRoute()
                    navController.popBackStack(Routes.DESTINATION, inclusive = false)
                }
            )
        }

        composable(Routes.SIMULATOR_ACTIVE_NAV) {
            val onVolumeUp = remember { { viewModel.advanceSimulationNode() } }
            DisposableEffect(onVolumeUp) {
                VolumeUpDispatcher.register(onVolumeUp)
                onDispose { VolumeUpDispatcher.unregister(onVolumeUp) }
            }
            LaunchedEffect(state.arrivedByBle) {
                if (state.arrivedByBle) {
                    navController.navigate(Routes.ARRIVAL) { popUpTo(Routes.SIMULATOR_DESTINATION) }
                }
            }
            ActiveNavigationScreen(
                state = state,
                tts = tts,
                onNextStep = viewModel::advanceSimulationNode,
                onTogglePause = viewModel::togglePause,
                onArrived = {
                    navController.navigate(Routes.ARRIVAL) {
                        popUpTo(Routes.SIMULATOR_DESTINATION)
                    }
                },
                onDeviated = { navController.navigate(Routes.FAILURE) },
                onCancel = {
                    viewModel.cancelRoute()
                    navController.popBackStack(Routes.SIMULATOR_MENU, inclusive = false)
                }
            )
        }

        composable(Routes.ARRIVAL) {
            ArrivalScreen(
                state = state,
                tts = tts,
                onFinish = {
                    viewModel.finishRoute()
                    navController.navigate(Routes.ONBOARDING) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
                onNewRoute = {
                    viewModel.finishRoute()
                    navController.navigate(Routes.DESTINATION) {
                        popUpTo(Routes.DESTINATION) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.FAILURE) {
            FailureScreen(
                tts = tts,
                onRetry = {
                    viewModel.recoverSignal()
                    navController.popBackStack() // vuelve a la navegación activa
                },
                onCancel = {
                    viewModel.cancelRoute()
                    navController.popBackStack(Routes.DESTINATION, inclusive = false)
                }
            )
        }

        composable(Routes.HELP) {
            HelpScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.ADMIN_BLE) {
            AdminBleScreen(
                scanner = bleScanner,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
