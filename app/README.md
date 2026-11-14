# App Android

Proyecto Gradle (Kotlin) con un módulo `:app`. Estado: **app vacía de la fase 1** (una pantalla con la versión instalada), que sirve para comprobar la cadena compilar, firmar e instalar.

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
