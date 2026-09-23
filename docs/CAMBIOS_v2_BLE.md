# Cambios v2 del pipeline BLE (rama `fix/ble-pipeline-v2`)

Origen: análisis de la prueba del 23-sep-2026 (4 balizas, cuarto de ~1,9 × 2,3 m, Redmi Note 14 4G).
La baliza 03 se recibía ~18 dB más fuerte que 04/05/06 y el algoritmo v1 la daba por ganadora desde
cualquier sitio (66 % del tiempo en el nodo 03, 85 cambios de nodo en 130 s, 88 % de los primeros 15 s
en el nodo destino).

## Qué cambió

| Área | Archivo(s) | Cambio |
|---|---|---|
| Firmware | `openview_beacon_v2/openview_beacon_v2.ino` (carpeta del proyecto, no de este repo) | Potencia real leída y mostrada, chip/MAC, anuncio ≤ 31 B con datos de fabricante (id, firmware, nivel TX), no conectable, 50 ms, LED = ID |
| Escáner | `ble/BleScanner.kt` | Emite `BleSample` por cada anuncio (`samples`), aprende el nombre por dirección, lee datos de fabricante, timeout de dispositivos 10 s → 4 s |
| Filtro | `nav/SignalPipeline.kt` | Mediana(3) + EMA **solo con muestras nuevas**; caducidad de 3 s por baliza |
| Decisión de nodo | `nav/NodeTracker.kt` | RSSI relativo a la calibración de cada baliza, baliza más fuerte calibrada (no centroide), histéresis 4 dB + 700 ms, prior de vecinos del grafo (10 dB para saltos) |
| Posicionador | `nav/BlePositioner.kt` | Se alimenta con `onSample`/`tick`; reloj y calibración inyectables (probable en JVM) |
| Navegación | `ui/Navigation.kt`, `ui/NavigationViewModel.kt` | Llegada solo tras 1,5 s estable en el destino; recálculo A* solo tras 2 s fuera de ruta |
| Log | `logging/BleSessionLogger.kt`, `BleLogFile.kt` | Formato v2 (1 fila por muestra + eventos ARRIVE/DEPART/NOTE); el lector entiende v1 y v2 |
| Verdad de terreno | `logging/GroundTruth.kt` | Marcas de llegada/salida → posición real en todo el recorrido (interpolada en tránsito) |
| Métricas | `logging/SessionAnalyzer.kt` | Acierto por punto, latencia, cambios de nodo, riesgo de falsa llegada |
| Replay | `logging/ReplayEngine.kt` | Reproduce el pipeline completo con parámetros distintos (alfa e histéresis en la pantalla de Replay) |
| UI | `ui/screens/GuidedTestScreen.kt` | **Prueba guiada** (Administración → «Prueba guiada»): botón LLEGUÉ / Vol+, «hace 3/5/10 s», notas de un toque, veredicto en vivo, resumen y «Guardar calibración» |
| Calibración | `AdminBleScreen.kt` (`CalibrationDialog`) | 20 s con muestras reales, mediana, aviso si la desviación > 4 dB |
| Herramienta PC | `tools/analyze_log.py` | Resumen de un CSV (v1/v2), consistencia entre balizas, alineación de notas tardías |
| Tests | `app/src/test/...` | `NodeTrackerTest`, `LogRegressionTest` (con el CSV real en `src/test/resources/logs/`) |

## Cómo probar (4 balizas: 03, 04, 05, 06)

1. **Firmware:** abre `openview_beacon_v2.ino`, pon `BEACON_ID` (3, 4, 5, 6), sube a cada una y mira el Monitor Serie (115200):
   debe decir `TX REAL: nivel 7 = +9 dBm`. Anota chip/revisión/MAC de cada unidad. Si alguna no dice nivel 7, ahí está la causa.
   Si con el formato nuevo la app no detecta las balizas, pon `USE_LEGACY_PAYLOAD 1` y vuelve a subir para comparar.
2. **App → Administración → Balizas → «Probar balizas cercanas»:** las 4 deben salir «Detectada», con «firmware v2» y «TX +9 dBm».
   Si aparecen firmware/TX distintos, reflashea todas con el mismo `.ino`.
3. **Ruta activa:** debe tener tus 4 puntos en orden de recorrido (06 → 05 → 04 → 03), cada uno con su baliza.
4. **Administración → Prueba guiada:** primera vuelta (aún sin calibrar). Párate a **1 m** de cada baliza, toca LLEGUÉ (o Vol+), quédate quieto.
   Al terminar toca **«Guardar calibración con esta prueba»**.
5. **Segunda vuelta** (ya calibrada): esta sí valida. Exporta el CSV.
6. **PC:** `python tools/analyze_log.py <csv> --nodes "NAV_BLE_06=<id nodo>,..."`.

Criterios de éxito: acierto ≥ 95 % en cada punto, ≤ 1 cambio de nodo cada 10 s estando quieto, sin falsa llegada, latencia ≤ 2 s.

## Límites conocidos

- La regresión con el CSV del 23-sep usa una calibración ajustada con esa misma prueba (optimista). La validación real es la segunda vuelta guiada.
- `BleSignal` sigue calculándose con el RSSI absoluto de la baliza más fuerte (como en v1): con una baliza «ruidosa» casi siempre dirá «estable».
- Calibración por teléfono: el RSSI absoluto cambia entre modelos; recalibra si cambias de teléfono.
- No se compiló contra un teléfono real: se verificó compilación, tests JVM y `assembleDebug`.
- No se añadió `ScanFilter` por UUID: con el firmware v2 el UUID va en la respuesta de escaneo y algunos chips filtran mal ese caso.
