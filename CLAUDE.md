# Instrucciones para Claude en este repositorio

Proyecto: app Android de avisos de radares + proceso de datos que publica la lista de radares. El propietario es Unai y trabaja en español: conversación, documentación y mensajes de commit en español.

## Al empezar cualquier tarea

1. Lee el plan (`plan-app-radares.md`: fases, riesgos, decisiones) y la referencia técnica (`referencia-tecnica.md`: hechos comprobados con enlaces). Viven en el proyecto de Claude "Programa radares"; ver `docs/README.md`. Si no tienes acceso, pídeselos a Unai antes de empezar.
2. Trabaja solo la fase o los pendientes que Unai indique. Cada fase va en una conversación independiente.
3. Al terminar, actualiza el plan: marca las casillas (`[x]` con fecha y resultado, `[~]` descartado con motivo), anota las decisiones y añade una línea al registro de avance. Si cambia el esquema de datos, actualiza también `datos/esquema-radares.md`.

## Estructura

- `datos/`: proceso de datos en Python (`datos/pipeline/`), últimas copias buenas de cada fuente (`datos/fuentes/`), fichero manual de añadidos y correcciones (`datos/manual/`) y ficheros publicados (`datos/salida/`).
- `app/`: proyecto Android (Kotlin).
- `.github/workflows/`: compilación del APK y tarea programada del proceso de datos.
- `docs/`: plan y referencia técnica.

## Reglas

- Repositorio público: **nunca** subir claves, contraseñas, ficheros `.jks`/`.keystore` ni `local.properties`.
- La app no interpreta fuentes: lee solo el fichero de radares ya procesado (`datos/salida/radares.json`). El esquema está en `datos/esquema-radares.md`.
- **Ante la duda prevalece la información oficial.** Prioridad de cualquier dato: fuente oficial (DGT, Trafikoa), después lo revisado a mano (`datos/manual/`), después OpenStreetMap. Lo manual y OpenStreetMap solo rellenan lo que la fuente oficial no da; nunca corrigen un dato oficial.
- **El sentido se decide sobre la carretera, no con la brújula.** La app no compara el rumbo del coche con un rumbo del radar. Cada radar lleva su tramo de aproximación (geometría de OpenStreetMap, calzada y sentido elegidos según el sentido oficial) y la app comprueba si el coche está sobre él y avanza hacia el radar.
- Solo radares fijos y de tramo; sin reportes de usuarios; sin hardware ni captación de señal.
- Si una fuente falla o da cifras raras, el proceso sigue con la última copia buena y lo marca en `estado.json`. No publicar con cifras raras.
- Cada ejecución programada del proceso de datos debe dejar un cambio en el repositorio (GitHub desactiva las tareas programadas tras 60 días sin actividad).
- La lógica de avisos (posición sobre el tramo de aproximación, sentido de avance, distancia, límite) es código puro, con pruebas automáticas que se ejecutan en cada compilación.
- Lo no comprobado se marca **[sin verificar]**; no presentar deducciones como hechos.
