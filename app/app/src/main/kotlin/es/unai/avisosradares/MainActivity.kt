package es.unai.avisosradares

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/** Pantalla provisional (fase 1.3): solo comprueba que el APK compila, se firma y se instala. */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        val info = packageManager.getPackageInfo(packageName, 0)
        @Suppress("DEPRECATION")
        val codigo = info.versionCode.toLong()

        val texto = TextView(this).apply {
            text = "Avisos de radares\n\n" +
                etiquetaVersion(info.versionName ?: "?", codigo) + "\n\n" +
                "App vacía de la fase 1"
            textSize = 20f
            gravity = Gravity.CENTER
            fitsSystemWindows = true
        }
        setContentView(texto)
    }
}
