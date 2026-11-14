# Proceso de datos

Se ejecuta en GitHub Actions (fase 3 del plan). Esta carpeta:

| Ruta | Contenido |
|---|---|
| `pipeline/` | Código Python: lectura de la DGT, lectura de Trafikoa (con conversión de UTM huso 30 ETRS89 a latitud/longitud), construcción del tramo de aproximación y del límite con OpenStreetMap, unificación y publicación. |
| `fuentes/` | Última copia buena de cada fuente. Si una fuente falla o da cifras raras, se sigue con esta copia y se marca en el estado. |
| `manual/anadidos.json` | Radares que no están en las listas oficiales (municipales) y revisiones hechas a mano. Prioridad de datos: oficial, después manual, después OpenStreetMap; lo manual solo rellena, no corrige lo oficial. |
| `salida/` | Ficheros publicados: `radares.json` y `estado.json`. Los descarga la app. |
| `esquema-radares.md` | Formato de cada radar y del fichero de estado. |

Cada ejecución programada debe dejar un cambio en el repositorio (por ejemplo, la fecha de ejecución en `estado.json`), para que GitHub no desactive la tarea tras 60 días sin actividad.
