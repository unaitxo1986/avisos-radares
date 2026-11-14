# Avisos de radares

App para Android que avisa con sonido al acercarse a un radar fijo o de tramo, y muestra la velocidad en una ventana flotante. Este repositorio contiene las dos piezas del proyecto:

| Carpeta | Qué es |
|---|---|
| `datos/` | **Proceso de datos.** Se ejecuta en GitHub Actions: baja las fuentes oficiales (DGT, Trafikoa), las unifica, calcula el tramo de aproximación y el límite de cada radar y publica el fichero de radares y el de estado. |
| `app/` | **App Android.** Lee el GPS, descarga el fichero de radares publicado y avisa. GitHub Actions compila el APK. |
| `docs/` | Indica dónde están el plan del proyecto y la referencia técnica (hechos comprobados). |

## Estado

Sin código todavía. El plan completo y la lista de fases están en el proyecto de Claude "Programa radares" (ver [`docs/README.md`](docs/README.md)).

## Cómo se trabaja

- Cada fase del plan se trabaja en una conversación distinta de Claude, que empieza leyendo `CLAUDE.md`, el plan y la referencia técnica, y termina actualizando las casillas del plan.
- Nunca se suben claves ni contraseñas al repositorio. La clave de firma del APK va como secreto de GitHub.

## Datos y licencias

- Radares de la DGT: Creative Commons Attribution, fuente DGT (nap.dgt.es).
- Radares de Euskadi: fuente Trafikoa (Gobierno Vasco); sin términos de reutilización publicados, se cita la fuente.
- Tramo de aproximación y límites calculados con datos de OpenStreetMap © colaboradores de OpenStreetMap, licencia ODbL.

Detalle en [`NOTICE.md`](NOTICE.md). La licencia del código está pendiente de decidir.

## Aviso legal

Solo radares fijos y de tramo, a partir de bases de datos públicas, sin reportes de usuarios ni captación de señal. Uso con el móvil en soporte, configurado antes de arrancar. No es asesoramiento legal.
