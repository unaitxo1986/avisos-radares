package es.unai.avisosradares

import android.app.Activity
import android.os.Bundle

/**
 * Vía B de la fase 2: pantalla transparente que una rutina de Samsung ("Modos y rutinas" > abrir app)
 * puede abrir. Arranca el servicio de ubicación y se cierra al instante.
 */
class ArranqueRutinaActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Registro.anota(this, "RUTINA", "pantalla de arranque abierta | " + Diagnostico.estado(this))
        ServicioPrueba.arrancar(this, Tipos.UBICACION, "rutina (pantalla de arranque)")
        finish()
    }
}
