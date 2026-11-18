# App Android

Proyecto Gradle (Kotlin) con un módulo `:app`. Estado: **versión 1 de la fase 4** (18/11/2026).

## Qué hace
- `ServicioRadares`: servicio en primer plano (tipo ubicación) que lee el GPS cada segundo, enseña la velocidad en una ventana flotante (se puede arrastrar), avisa con sonido por el altavoz del móvil y graba cada viaje. Lo arranca y lo para el Bluetooth del coche (`BluetoothReceiver`, exportado; vía A de la fase 2), el botón de la pantalla o el botón "Parar" de la notificación.
- `MotorAvisos` (código puro, con pruebas): aviso **por carretera y sentido** en los radares con tramo de aproximación fiable (el coche va sobre la línea y avanza hacia el radar; distancia medida por la carretera) y **por cercanía** en el resto (acercándose, cualquier sentido; un solo criterio: 500 m si el radar está en una carretera, 150 m si es urbano, sin carretera). El aviso suena siempre al acercarse; si se conoce el límite y se supera, aviso de exceso repetido cada 4 s y ventana roja.
- **Radares de tramo (fase 5)**: si el radar trae la línea del tramo (`tramo`, `tramo_largo`), `MotorAvisos` sigue al coche por ella desde que cruza la cámara de inicio y calcula la **velocidad media** (lo recorrido por la línea entre el tiempo transcurrido; los huecos del GPS en túneles no la estropean). La ventana enseña "media 97" y "fin a 2,3 km" y se pone roja si la media supera el límite; 3 pitidos al pasar la media del límite y luego cada km mientras siga por encima. Inicio y fin, sin sonido. Botón "Simular paso por un radar de tramo".
- `GestorLista`: el APK lleva dentro `datos/salida/radares.json` y `estado.json`; la app baja las versiones nuevas desde GitHub (con `ETag`, cada 6 h como mucho) y avisa si la lista puede estar anticuada.
- `Viajes`: un CSV por viaje (posiciones y avisos), exportable a Descargas/AvisosRadares; se puede reproducir en el móvil. `Simulacion`: recorridos inventados sobre un radar real para probar sin conducir.
- Pruebas automáticas (`src/test`): además de las de la lógica, `DatosRealesTest` simula el paso por **cada** radar de la lista publicada, en su sentido y en el contrario.

## Compilación y firma
- Se compila en GitHub Actions (`.github/workflows/compilar-apk.yml`) en cada cambio en `app/`, o a mano desde la pestaña Actions (Run workflow).
- El workflow ejecuta las pruebas, compila el APK de release y lo firma con una **clave fija** restaurada desde secretos del repositorio: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. La clave no está en el repositorio. **Si se pierde o cambia, hay que desinstalar la app para poder instalar una nueva versión.**
- `versionCode` = número de ejecución del workflow, así que cada compilación es más nueva que la anterior.
- Descarga en el móvil (sin iniciar sesión): https://github.com/unaitxo1986/avisos-radares/releases/download/latest/avisos-radares.apk

## Instalar en el móvil (fases 1.4 y 1.5)
Descargar el APK con el enlace anterior, permitir la instalación de apps desconocidas y abrirlo. En el Samsung hay que desactivar antes Auto Blocker (Ajustes > Seguridad y privacidad). Para actualizar basta instalar la nueva compilación encima: si Android la rechaza, la firma ha cambiado.

## Compilar en local
Hace falta JDK 17 y el SDK de Android. Sin variables de firma, `assembleRelease` genera un APK sin firmar.
`./gradlew testDebugUnitTest assembleDebug`

## Requisitos que condicionan el diseño (ver `referencia-tecnica.md`, apartados 6 a 8)
- Servicio de GPS en primer plano con notificación visible.
- Ventana flotante (permiso de superposición; con apps instaladas a mano hay que permitir "ajustes restringidos").
- Arranque automático al conectar el Bluetooth del coche: no garantizado por la documentación, se decide con las pruebas de la fase 2.
- La app lee `radares.json` y `estado.json` publicados por el proceso de datos; no interpreta fuentes originales.
- El aviso se decide por carretera y sentido, no por rumbo de brújula (fase 4.3).
- Secretos de firma configurados el 14/11/2026 (clave fija, ver arriba).
