Project summary:

El proyecto consiste en desarrollar un prototipo Android de navegación interior accesible para personas con discapacidad visual en Lima Metropolitana. La app busca permitir que el usuario seleccione un destino dentro de un entorno piloto y reciba instrucciones auditivas paso a paso para desplazarse de forma autónoma.

La solución se basa en tres elementos principales: BLE Beacons para estimar la ubicación aproximada del usuario, mapas de grafos para representar pasillos, nodos y rutas, y TTS/audio para entregar instrucciones de navegación en español.

El stack elegido apunta a un MVP offline, accesible y de bajo costo: Kotlin nativo en Android, BLE para localización, A* para cálculo de rutas, TalkBack/WCAG para accesibilidad y una solución híbrida de TTS nativo con audios locales para alertas críticas.

Quiero crear un prototipo visual navegable en Kotlin nativo Android usando Jetpack Compose para una aplicación móvil de navegación interior accesible para personas con discapacidad visual.

Por ahora NO quiero funcionalidad real. No implementar BLE real, A*, base de datos Room, permisos Bluetooth, TTS real ni almacenamiento persistente. Solo quiero interfaz, diseño, navegación entre pantallas y estados simulados con datos falsos.

Contexto del proyecto:
La app debe ayudar a una persona con discapacidad visual a desplazarse dentro de un entorno interior piloto mediante localización BLE, mapas de grafos e instrucciones auditivas. En esta primera etapa solo se diseñará el flujo UX/UI, siguiendo criterios de accesibilidad: botones grandes, textos claros, pocas acciones por pantalla, compatibilidad conceptual con TalkBack, flujo simple y mensajes fáciles de entender.

Stack:

* Kotlin
* Android nativo
* Jetpack Compose
* Navegación entre pantallas con Navigation Compose
* Arquitectura simple tipo MVVM, aunque la lógica puede estar simulada
* Datos mock/hardcoded
1
Pantallas que quiero implementar:

1. Pantalla de onboarding
   Debe explicar brevemente que la app ayuda a navegar dentro del entorno piloto mediante instrucciones auditivas.
   Debe tener botones:

* “Iniciar”
* “Repetir explicación”
* “Configurar voz”

2. Pantalla de configuración de voz
   Debe permitir diseñar visualmente:

* Control de velocidad de voz
* Control de volumen
* Botón “Probar voz”
* Botón “Guardar configuración”
  Por ahora no debe reproducir audio real; puede mostrar un estado o mensaje simulado.

3. Pantalla de selección de destino
   Debe mostrar una lista de destinos disponibles, con tarjetas o botones grandes:

* Aula 302
* Biblioteca
* Baño accesible
* Ascensor
* Entrada principal
* Laboratorio
  Cada destino debe ser claro, grande y fácil de tocar.

4. Pantalla de confirmación de ubicación inicial
   Debe simular que la app detectó una ubicación aproximada.
   Ejemplo:
   “Ubicación aproximada detectada: Entrada principal”
   Botones:

* “Confirmar ubicación”
* “Volver a detectar”
* “Cambiar manualmente”

5. Pantalla de vista previa de ruta
   Debe mostrar:

* Ubicación inicial
* Destino seleccionado
* Tiempo estimado simulado
* Número de indicaciones
* Botón “Iniciar ruta”
  Ejemplo:
  “Ruta: Entrada principal → Aula 302”
  “4 indicaciones”
  “Tiempo estimado: 3 minutos”

6. Pantalla de navegación activa
   Debe ser la pantalla principal del prototipo.
   Debe mostrar una instrucción grande, por ejemplo:
   “Avanza 8 metros y gira a la derecha.”
   También debe mostrar:

* Próximo punto: “Cruce principal”
* Estado BLE: “Señal estable”
* Botón “Repetir instrucción”
* Botón “Pausar”
* Botón “Cancelar ruta”
  Opcional: barra de progreso o indicador visual simple.

7. Pantalla o modal de alerta BLE
   Debe simular un caso de señal baja.
   Texto:
   “Señal BLE insuficiente.”
   “Acércate a un punto de referencia conocido.”
   Botones:

* “Reintentar”
* “Escuchar ayuda”
* “Cancelar navegación”

8. Pantalla de llegada al destino
   Debe mostrar:
   “Has llegado a Aula 302.”
   Botones:

* “Finalizar”
* “Nueva ruta”
* “Repetir confirmación”

9. Pantalla de ayuda contextual
   Debe mostrar instrucciones simples:

* “Para iniciar una ruta, selecciona un destino.”
* “Durante una ruta puedes repetir, pausar o cancelar.”
* “Si la señal es baja, acércate a un punto de referencia.”

10. Pantalla de registro de prueba, opcional
    Esta pantalla será para un evaluador, no para el usuario principal.
    Debe tener campos visuales simulados:

* Ruta probada
* Tiempo
* Llegó correctamente: sí/no
* Observaciones
* Satisfacción del usuario

Prioridad de implementación:
Primero construir el flujo principal:
Selección de destino → Confirmación de ubicación inicial → Vista previa de ruta → Navegación activa → Llegada al destino.

Luego agregar:
Onboarding → Configuración de voz → Ayuda contextual → Alerta BLE → Registro de prueba.

Requisitos de diseño:

* Interfaz limpia y accesible.
* Botones grandes.
* Texto legible.
* Acciones claras.
* No saturar cada pantalla.
* Usar colores con buen contraste.
* Mantener consistencia visual.
* Preparar los elementos para que luego puedan tener contentDescription y soporte TalkBack.
* No usar mapas complejos todavía.
* No usar BLE real todavía.
* No usar TTS real todavía.
* Todo debe funcionar con datos simulados.

Quiero que me generes la estructura inicial del proyecto o los archivos principales en Kotlin/Jetpack Compose para este prototipo visual, incluyendo:

* Modelos simples para destinos y ruta simulada.
* NavHost con las pantallas.
* Composables separados por pantalla.
* Estados mock para destino seleccionado, ubicación inicial, ruta activa y señal BLE.
* Código limpio y fácil de extender después.
