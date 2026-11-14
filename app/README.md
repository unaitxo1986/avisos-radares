# App Android

Aquí irá el proyecto Android (Kotlin). Se crea en la **fase 1.3** del plan: una app vacía que compile en GitHub Actions y genere un APK firmado con clave fija.

Requisitos que condicionan el diseño (ver `docs/referencia-tecnica.md`, apartados 6 a 8):

- Servicio de GPS en primer plano con notificación visible.
- Ventana flotante (permiso de superposición; con apps instaladas a mano hay que permitir "ajustes restringidos").
- Arranque automático al conectar el Bluetooth del coche: no garantizado por la documentación, se decide con las pruebas de la fase 2.
- La app lee `radares.json` y `estado.json` publicados por el proceso de datos; no interpreta fuentes originales.
- El aviso se decide por carretera y sentido, no por rumbo de brújula: el coche debe estar sobre el tramo de aproximación del radar y avanzar hacia él (fase 4.3).
