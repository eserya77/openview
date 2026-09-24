# Guía: probar en la sala (calibración + rotaciones + algoritmo v3)

Rama: `feat/guided-v3-calibration`. Requiere el firmware v2 en las balizas (`openview_beacon_v2.ino`).

## Escenario «Sala (trapecio, 4 posiciones)»

| Posición | X (m) | Y (m) |
|---|---|---|
| P1 | 0,8 | 0,0 |
| P2 | 2,3 | 0,0 |
| P3 | 4,0 | 2,7 |
| P4 | 0,0 | 1,9 |

Recorrido: **P1 → P2 → P3 → P4**. El inicio («centro») es por defecto el centroide (≈ 1,78 ; 1,15); se cambia en
**Prueba guiada → Editar coordenadas / inicio**. Los escenarios se guardan en el teléfono.

**R0 (base)** pone las balizas elegidas de menor a mayor número: **P1 = BLE_03, P2 = BLE_04, P3 = BLE_05, P4 = BLE_06.**

## 1. Calibrar cada baliza (una vez por baliza y teléfono) — Administración → «Calibrar balizas (asistente)»

1. Cinta métrica. Al abrir el panel de administración el escaneo arranca solo (barra verde «N balizas detectadas»; no hace falta «Probar balizas»). Las balizas que se detectan quedan elegidas; ajusta la distancia máxima en línea recta (hasta 6 m en el pasillo).
2. **Buscador de máximo** (opcional): mueve el móvil sobre la placa hasta el pico de RSSI y marca ese lugar/lado con cinta.
3. Cada paso muestra un mini mapa animado visto desde arriba: en los ángulos NO te das la vuelta sobre ti, caminas en arco alrededor de la baliza manteniendo 1 m. Pasos: contacto → 30 cm → 1 m → 2 m → 3 m → 4 m → 6 m → 1 m a 90°/180°/270°. Cada paso arranca solo cuando la señal se estabiliza (o «YA ESTOY · MEDIR»).
4. Al terminar cada baliza: **perfil** (contacto, A a 1 m, n, residuo, asimetría, muestras/s, nota A/B/C con motivos). «Guardar y seguir».
5. «Exportar perfiles (CSV)» al final.

## 2. Vueltas de la prueba guiada — Administración → «Prueba guiada (rotaciones)»

1. Escenario **Sala**, balizas **03, 04, 05, 06**, rotación **R0**. Marca de qué se alimenta cada posición (PC / Cargador).
2. Tipo de parada: **contacto** (recomendado; apoya el móvil siempre igual en el extremo de la antena). Tiempo quieto: 6 s.
3. **Modo ENTRENAR** (por defecto mientras no haya huellas): haz la vuelta — centro → P1 → P2 → P3 → P4 — con LLEGUÉ / Vol+ al detenerte (o «hace 3/5/10 s»).
4. Al terminar mira el resumen y, **si la vuelta salió bien hecha**, toca «Guardar esta vuelta como entrenamiento». Hazlo en **3 vueltas** (la huella es la mediana de las vueltas). No guardes vueltas mal hechas.
5. **Modo VALIDAR**: con huellas guardadas, otra vuelta mide el resultado real sin modificarlas. Exporta el CSV.
6. **Rotar:** elige R1, R2, R3… (o Inversa, Pares, Aleatoria, Personalizada), mueve las placas físicamente (cada una conserva su nombre; la fuente/cable se queda en la posición), y repite entrenar ×3 + validar. **Cada rotación tiene sus propias huellas** (la clave incluye qué baliza va en cada posición y el tipo de parada).

## 3. Cómo decide ahora la app (algoritmo v3)

- Conoce el **orden de la ruta**: solo avanza al **siguiente** punto (margen pequeño); volver atrás exige más; **saltar 2+ puntos** exige 4 dB sobre los intermedios sostenidos 3 s.
- Puntúa cada punto de tres maneras, en este orden:
  1. **Huella** (si hay entrenamiento para esa disposición): distancia entre las lecturas de TODAS las balizas y la huella del punto.
  2. **Perfil** (si las balizas están calibradas y la parada es de contacto): cercanía al nivel de contacto; si ninguna está cerca → **ningún nodo**.
  3. **Relativo** (si no hay nada): RSSI menos la referencia a 1 m de cada baliza.
- Hay dos estados: «progreso» (hasta dónde llegaste) y «dónde estás ahora» (un nodo o **ninguno**, en tránsito o en el centro). Con una huella del centro entrenada, el centro devuelve «ningún nodo».
- La navegación normal da la llegada solo tras 1,5 s estable en el destino.
- En el **Replay** (Administración → Replay) puedes reproducir cualquier log guiado con v2 o v3: reconstruye el escenario/rotación/huellas desde la fila LAYOUT y muestra el acierto por punto.

## 4. Analizar en el PC

```
python tools/analyze_rotation.py "logs de prueba/rot_*.csv" --json rotacion.json
python tools/analyze_log.py <csv>
```

`analyze_rotation.py` separa el efecto de la **placa** del de la **posición** (propio y ajeno), da la repetibilidad entre vueltas y qué baliza gana en el centro.

## Formato del log

- Fila `LAYOUT`: `setup=…;rot=…;P1=NAV_BLE_xx;…;stay=…;dwell=…;lap=…;power=P1:PC|…;center=…;mode=entrenar|validar;fp_laps=N`.
- Marcas `ARRIVE`/`DEPART` con `true_node` = `P1..P4` o `CENTRO`. El resto igual (una fila por muestra nueva).

## Validación con tus datos reales

Con las dos vueltas guiadas del cuarto pequeño (entrenando con una y probando con la otra), v3 da **100 % de acierto y 0 s de falso destino** en ambos sentidos, con la secuencia 06 → 05 → 04 → 03 (test `V3CrossRunTest`). La v2 daba 75 % y hasta 24,5 s de falso destino. Son solo 2 vueltas del mismo cuarto: la validación real es la que harás en la sala.

## Límites

- Las huellas dependen del **teléfono**: se guardan en este teléfono; si cambias de modelo, vuelve a entrenar.
- Una huella es de una disposición y de un tipo de parada: al mover placas o cambiar de contacto a 1 m, entrena de nuevo.
- La navegación normal (fuera de la prueba guiada) usa perfil o RSSI relativo; usar huellas allí requiere entrenar con esa ruta (pendiente).
