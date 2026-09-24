# Prueba en línea recta: 01 · 2,10 m · 02 · 2,60 m · 03

Balizas con **firmware v3** (01 y 03 a −3 dBm, 02 a +9 dBm). Escenario de fábrica **«Sala recta»** (`sala_recta`):

| Posición | Baliza | X (m) |
|---|---|---|
| P1 | NAV_BLE_01 | 0,00 |
| P2 | NAV_BLE_02 | 2,10 |
| P3 | NAV_BLE_03 | 4,70 |

Inicio por defecto: 1 m antes de la 01 (x = −1,0). En «Vuelta», la app lo pone 1 m después de la 03.

## 0. Antes
1. Administración → **Calibrar balizas**: calibra 01, 02 y 03 (si 01/03 salen «Recalibrar», es por el cambio de potencia).
2. Móvil siempre igual (mejor colgado al pecho).

## 1. Prueba guiada (laboratorio, con marcas) — Administración → «Prueba guiada»
Escenario «Sala recta», balizas 01·02·03, rotación R0. Formas de probarla:

| Forma | Sentido | Parada | Qué mide |
|---|---|---|---|
| A | Ida | Contacto | Lo mismo que la prueba de la sala, en recto |
| B | Vuelta | Contacto | Si el sentido cambia algo (antenas, cuerpo) |
| C | Ida y vuelta | Contacto | Volver atrás por los mismos puntos |
| D | Ida | **Pasando sin parar** | Si la app detecta cada baliza al pasar a su lado (hitos) |
| E | Vuelta | Pasando sin parar | Lo mismo en el otro sentido |
| F | Ida | A 1 m | Parada a distancia (más difícil) |

En «Pasando sin parar» se pulsa **PASO JUNTO A ELLA** (o Vol+) en el instante en que la baliza queda a tu lado, sin detenerse.
El resumen dice cuántas detectó la app al pasar, en qué orden y a cuántos segundos de tu marca.

## 2. Guía de usuario (como el producto final) — Administración → «Guía de usuario (modo prueba)»
- Elige «Sala recta», Ida o Vuelta y el destino (P2 o P3).
- Lo que recibe la persona: voz corta en cada punto, **pitidos que se aceleran y suben de tono** al acercarte al siguiente
  punto, vibración (corta = punto, triple = llegaste, larga = corrige), **llegada automática** al detenerte junto al destino,
  aviso «vas hacia atrás» y «te alejas». **Vol+ repite** la instrucción.
- Panel detallado (para quien prueba): nivel Kalman, pendiente (dB/s), nivel − referencia a 1 m y zona de cada baliza;
  movimiento (acelerómetro), si el escaneo va con filtro, eventos en vivo. Botón «Ahora paso junto a …» = marca de verdad
  (se analiza igual que el modo «pasar»).
- El CSV guarda las muestras, cada evento como nota `GUIA …` y el estado del motor cada segundo (`GUIA_ESTADO`).

Pruebas sugeridas con la guía: ida caminando normal; ida con los ojos cerrados y un acompañante; vuelta; pasar la 02 y
volver atrás a propósito (debe decir «vas hacia atrás»); destino P2 (debe decir «llegaste» en la 02 y no en la 03).

## Cambios técnicos
- `nav/GuideEngine.kt`: zonas + Kalman nivel/pendiente + hitos + llegada automática (tests `GuideEngineTest`, incluida la
  vuelta real de la sala del 24-sep).
- `logging/PassAnalyzer.kt`: análisis de «pasar sin parar».
- `ble/BleScanner.kt`: escaneo **con filtro** (nombre NAV_BLE_01..06 o datos de fabricante 0xFFFF). Si en 10 s no aparece
  ninguna baliza, vuelve solo al escaneo sin filtro (lo indica el panel).
- La guía mantiene la pantalla encendida. Escanear con la pantalla apagada necesitará un servicio en primer plano (pendiente).


## 3. Botón «Iniciar» (producto, persona usuaria)
- Inicio → **Iniciar** → «¿A dónde quieres ir?» (los puntos del escenario elegido en el panel, con sus balizas) → la app
  escucha 3 s para saber junto a qué punto estás, arma la ruta en el sentido que toque y guía igual que la Guía de usuario
  (misma `GuideSession`): voz, pitidos (si están activados), vibración, llegada automática. **Vol+ repite.**
- Al llegar: «Ir a otro destino» o «Volver al inicio». Si ya estás en el destino, lo dice.
- **Configurar voz**: velocidad, volumen, pitidos y vibración (se guardan en el teléfono). **Ayuda**: 9 consejos.
- Sin TalkBack, la app hace de lector: anuncia cada pantalla, dice cada botón al pulsarlo y los valores al cambiarlos.
  Con TalkBack activado no habla por encima: todos los botones tienen su descripción para TalkBack.
- Las herramientas técnicas (panel, simulador, forma de prueba) están plegadas en «Opciones de instalación y pruebas».

## Pruebas del 24-sep 13:45 (resumen)
- Guía ida con vuelta atrás a propósito: dijo «vas hacia atrás» junto a la 01 y no dio llegada ✔.
- Pasar sin parar: en la ida la 02 no se distingue porque la 03 (2,6 m más allá) se oye más fuerte al pasar la 02
  (−75 frente a −80…−85 dBm). Revisa la ORIENTACIÓN de la 02 (antena hacia el paso) y calibra 01/02/03.
- Parada a 1 m: la 02 salía como 03 el 100 % por la calibración caducada de la 03 (+8 dB a su favor); con la corrección, ≥ 90 %.
- Motor: «Llegaste» exige ahora estar a ≈ 50 cm (≥ ref + 6 dB); antes de eso dice «te acercas a tu destino».
