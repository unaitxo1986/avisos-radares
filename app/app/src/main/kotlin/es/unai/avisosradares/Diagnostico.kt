package es.unai.avisosradares

import android.Manifest
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/** Resumen en una línea del estado del móvil y de la app; se anota con cada suceso importante. */
object Diagnostico {

    fun concedido(contexto: Context, permiso: String): Boolean =
        contexto.checkSelfPermission(permiso) == PackageManager.PERMISSION_GRANTED

    fun estado(contexto: Context): String {
        val ctx = contexto.applicationContext
        val teclado = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val energia = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val partes = mutableListOf<String>()
        partes += "pantalla=" + (if (energia.isInteractive) "encendida" else "apagada")
        partes += "bloqueado=" + teclado.isKeyguardLocked
        partes += "bateriaSinRestricciones=" + energia.isIgnoringBatteryOptimizations(ctx.packageName)
        partes += "superposicion=" + Settings.canDrawOverlays(ctx)
        partes += "ubicacionPrecisa=" + concedido(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        partes += "ubicacionSiempre=" + concedido(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            partes += "bluetooth=" + concedido(ctx, Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            partes += "notificaciones=" + concedido(ctx, Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                val uso = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                partes += "cubo=" + nombreBucket(uso.appStandbyBucket)
            } catch (e: Throwable) {
                partes += "cubo=?"
            }
        }
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        partes += "importancia=" + nombreImportancia(info.importance)
        return partes.joinToString(" ")
    }
}
