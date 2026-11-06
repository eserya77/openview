package com.example.openview.ui

import android.os.SystemClock
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
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import com.example.openview.share.ShareHub
import com.example.openview.share.hasSharePermissions
import com.example.openview.share.requiredSharePermissions
import com.example.openview.ui.components.isTalkBackOn
import com.example.openview.ble.estimateMetersFromRssi
import com.example.openview.ble.hasBlePermissions
import com.example.openview.ble.rememberBleScanner
import com.example.openview.data.BleSignal
import com.example.openview.data.RouteRepository
import com.example.openview.input.VolumeUpDispatcher
import com.example.openview.logging.BleLogRow
import com.example.openview.logging.BleSessionLogger
import com.example.openview.logging.MarkEvent
import com.example.openview.nav.BlePositioner
import com.example.openview.nav.LivePosition
import com.example.openview.tts.rememberTtsController
import com.example.openview.ui.screens.ActiveNavigationScreen
import com.example.openview.ui.screens.AdminBleScreen
import com.example.openview.ui.screens.ArrivalScreen
import com.example.openview.ui.screens.DestinationSelectionScreen
import com.example.openview.ui.screens.FailureScreen
import com.example.openview.ui.screens.GraphShapeScreen
import com.example.openview.ui.screens.GuardScreen
import com.example.openview.ui.screens.GuideRunScreen
import com.example.openview.ui.screens.GuideStartScreen
import com.example.openview.ui.screens.PlaceRoutesScreen
import com.example.openview.ui.screens.HelpScreen
import com.example.openview.ui.screens.OnboardingScreen
import com.example.openview.ui.screens.RoutePreviewScreen
import com.example.openview.ui.screens.SimulatorMenuScreen
import com.example.openview.ui.screens.VoiceConfigScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    /** Guía del producto (botón «Iniciar»). */
    const val GUIDE_START = "guide_start"
    const val GUIDE_RUN = "guide_run/{from}/{to}"
    fun guideRun(from: String, to: String) = "guide_run/$from/$to"
    /** Rutas del lugar (persona ciega: descargar del guardia o pedírselas). */
    const val PLACE_ROUTES = "place_routes"
    /** Modo guardia: compartir el JSON de la empresa. */
    const val GUARD = "guard"
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

    // ── Compartir rutas (guardia ↔ persona ciega) ──
    // Se escucha a los guardias en las pantallas de la persona usuaria; durante la guía y en el panel de
    // administración no (ahí el Bluetooth es para las balizas), salvo que el guardia esté compartiendo o haya una
    // petición en curso.
    val view = LocalView.current
    val backEntry by navController.currentBackStackEntryAsState()
    val hub by ShareHub.state.collectAsState()
    var permTick by remember { mutableIntStateOf(0) }
    val sharePerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permTick++ }
    LaunchedEffect(Unit) {
        if (!hasSharePermissions(context)) {
            val extra = if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(android.Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
            sharePerms.launch(requiredSharePermissions() + extra)
        }
    }
    val listenRoutes = setOf(Routes.ONBOARDING, Routes.GUIDE_START, Routes.PLACE_ROUTES, Routes.GUARD, Routes.HELP, Routes.VOICE_CONFIG)
    LaunchedEffect(backEntry?.destination?.route, hub.guardSharing, hub.myCode, permTick, bleState.bluetoothEnabled) {
        ShareHub.listen(backEntry?.destination?.route in listenRoutes || hub.guardSharing || hub.myCode != null)
    }
    // Lo que avisa el módulo de compartir (rutas disponibles, descarga lista…): voz, o TalkBack si está activado.
    LaunchedEffect(Unit) {
        ShareHub.announcements.collect { msg -> if (isTalkBackOn(context)) view.announceForAccessibility(msg) else tts.speak(msg) }
    }
    // Tocar la notificación «Rutas disponibles en…» abre Rutas del lugar.
    val openRoutes by ShareHub.openRoutesRequest.collectAsState()
    LaunchedEffect(openRoutes) {
        if (openRoutes) { ShareHub.openRoutesRequest.value = false; navController.navigate(Routes.PLACE_ROUTES) { launchSingleTop = true } }
    }

    // El posicionador v3 usa el orden de la ruta activa (solo avanza al siguiente punto).
    LaunchedEffect(state.routePath) { com.example.openview.data.ActiveGraph.routePath = state.routePath }

    // Convierte las señales BLE en posición (nodo del grafo) y la pasa al ViewModel.
    // Se alimenta por MUESTRAS (una por anuncio recibido) y por un reloj de 250 ms que hace
    // caducar balizas y confirmar nodos por tiempo.
    LaunchedEffect(bleScanner, positioner) {
        var lastNode: String? = null
        var lastSignal: BleSignal? = null
        var lastBeacon: String? = null
        var lastNoneLogMs = 0L

        fun publish(pos: BlePositioner.Result, force: Boolean) {
            LivePosition.update(pos)
            val changed = pos.nodeId != lastNode || pos.signal != lastSignal || pos.beaconName != lastBeacon
            if (changed || force) {
                lastNode = pos.nodeId; lastSignal = pos.signal; lastBeacon = pos.beaconName
                viewModel.onPositionUpdate(pos.nodeId, pos.beaconName, pos.signal, pos.stableMs)
            }
        }

        launch {
            bleScanner.samples.collect { sample ->
                val pos = positioner.onSample(sample.name, sample.rssi, sample.tMs)
                publish(pos, force = false)

                // Registro de calibración: solo escribe si hay una sesión grabando (ver
                // BleSessionLogger). No cambia nada del comportamiento de navegación.
                if (BleSessionLogger.state.value.isRecording) {
                    val reading = pos.readings.firstOrNull { it.beaconName == sample.name }
                    BleSessionLogger.log(
                        BleLogRow(
                            timestampS = BleSessionLogger.elapsedSeconds(),
                            beaconName = sample.name,
                            rssiRaw = sample.rssi,
                            rssiSmoothed = reading?.rssiSmoothed,
                            estimatedDistanceM = reading?.estimatedDistanceM
                                ?: estimateMetersFromRssi(sample.rssi, RouteRepository.calibrationFor(sample.name)),
                            computedXM = pos.computedX,
                            computedYM = pos.computedY,
                            detectedNodeId = pos.nodeId,
                            signalState = pos.signal.name.lowercase(),
                            relDb = reading?.relDb,
                            confidence = pos.confidence,
                            stableMs = pos.stableMs,
                            fwVersion = sample.fwVersion,
                            txLevel = sample.txLevel
                        )
                    )
                }
            }
        }
        launch {
            while (true) {
                delay(250)
                val now = SystemClock.elapsedRealtime()
                val pos = positioner.tick(now)
                // El tick refresca el VM aunque nada cambie: así se cumple la espera de confirmación.
                publish(pos, force = pos.nodeId != null)
                if (pos.readings.isEmpty() && BleSessionLogger.state.value.isRecording && now - lastNoneLogMs >= 1_000L) {
                    lastNoneLogMs = now
                    BleSessionLogger.log(
                        BleLogRow(
                            timestampS = BleSessionLogger.elapsedSeconds(), beaconName = "_NONE_", rssiRaw = null,
                            rssiSmoothed = null, estimatedDistanceM = null, computedXM = null, computedYM = null,
                            detectedNodeId = pos.nodeId, signalState = pos.signal.name.lowercase(),
                            event = MarkEvent.NONE
                        )
                    )
                }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.ONBOARDING,
        modifier = modifier
    ) {

        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                tts = tts,
                onStart = { navController.navigate(Routes.GUIDE_START) },
                onRoutes = { navController.navigate(Routes.PLACE_ROUTES) },
                onGuard = { navController.navigate(Routes.GUARD) },
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
                onBeepsChange = viewModel::updateBeeps,
                onVibrationChange = viewModel::updateVibration,
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
            HelpScreen(tts = tts, onBack = { navController.popBackStack() })
        }

        composable(Routes.GUIDE_START) {
            GuideStartScreen(
                scanner = bleScanner,
                tts = tts,
                onSelect = { from, to -> navController.navigate(Routes.guideRun(from, to)) },
                onRoutes = { navController.navigate(Routes.PLACE_ROUTES) },
                onHelp = { navController.navigate(Routes.HELP) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.GUIDE_RUN) { entry ->
            GuideRunScreen(
                scanner = bleScanner,
                tts = tts,
                voice = state.voiceSettings,
                fromBeacon = entry.arguments?.getString("from").orEmpty(),
                toBeacon = entry.arguments?.getString("to").orEmpty(),
                // «Ir a otro destino»: vuelve a detectar dónde estás (una pantalla de inicio nueva).
                onNewDestination = {
                    navController.navigate(Routes.GUIDE_START) { popUpTo(Routes.ONBOARDING) { inclusive = false } }
                },
                onHome = { navController.popBackStack(Routes.ONBOARDING, inclusive = false) }
            )
        }

        composable(Routes.PLACE_ROUTES) {
            PlaceRoutesScreen(
                tts = tts,
                onStart = { navController.navigate(Routes.GUIDE_START) { popUpTo(Routes.ONBOARDING) { inclusive = false } } },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.GUARD) {
            GuardScreen(tts = tts, onBack = { navController.popBackStack() })
        }

        composable(Routes.ADMIN_BLE) {
            AdminBleScreen(
                scanner = bleScanner,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
