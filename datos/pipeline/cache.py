"""Caché en disco de las respuestas de servicios externos.

Un fichero JSON por servicio en `datos/cache/`, con una entrada por línea para que
los cambios se vean bien en git. Se sube al repositorio: así las ejecuciones
siguientes solo piden lo nuevo y se puede trabajar sin red con datos reales.
"""
import json
from pathlib import Path


class Cache:
    def __init__(self, ruta):
        self.ruta = Path(ruta)
        self.datos = {}
        self.sucio = False
        if self.ruta.exists():
            self.datos = json.loads(self.ruta.read_text(encoding="utf-8"))

    def __contains__(self, clave):
        return clave in self.datos

    def __len__(self):
        return len(self.datos)

    def get(self, clave):
        return self.datos[clave]

    def set(self, clave, valor):
        self.datos[clave] = valor
        self.sucio = True

    def guardar(self):
        if not self.sucio:
            return
        self.ruta.parent.mkdir(parents=True, exist_ok=True)
        lineas = [f"{json.dumps(k, ensure_ascii=False)}:{json.dumps(v, ensure_ascii=False, separators=(',', ':'))}"
                  for k, v in sorted(self.datos.items())]
        self.ruta.write_text("{\n" + ",\n".join(lineas) + "\n}\n", encoding="utf-8")
        self.sucio = False
