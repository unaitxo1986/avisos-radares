package es.unai.avisosradares

/** Cubos de "espera de apps" de Android (UsageStatsManager.getAppStandbyBucket). */
fun nombreBucket(bucket: Int): String = when (bucket) {
    5 -> "EXEMPTED"
    10 -> "ACTIVE"
    20 -> "WORKING_SET"
    30 -> "FREQUENT"
    40 -> "RARE"
    45 -> "RESTRICTED"
    50 -> "NEVER"
    else -> "desconocido($bucket)"
}

/** Importancia del proceso (RunningAppProcessInfo.importance). */
fun nombreImportancia(importancia: Int): String = when (importancia) {
    100 -> "primer plano"
    125 -> "servicio en primer plano"
    130 -> "servicio en primer plano (otro)"
    150 -> "pantalla superior"
    200 -> "visible"
    230 -> "visible (percibido)"
    300 -> "servicio en segundo plano"
    400 -> "en caché"
    1000 -> "vacío"
    else -> "desconocida($importancia)"
}

/** Motivos de cierre de proceso (ApplicationExitInfo.getReason). */
fun nombreMotivoCierre(motivo: Int): String = when (motivo) {
    1 -> "EXIT_SELF"
    2 -> "SIGNALED"
    3 -> "LOW_MEMORY"
    4 -> "CRASH"
    5 -> "CRASH_NATIVE"
    6 -> "ANR"
    7 -> "INITIALIZATION_FAILURE"
    8 -> "PERMISSION_CHANGE"
    9 -> "EXCESSIVE_RESOURCE_USAGE"
    10 -> "USER_REQUESTED"
    11 -> "USER_STOPPED"
    12 -> "DEPENDENCY_DIED"
    13 -> "OTHER"
    14 -> "FREEZER"
    15 -> "PACKAGE_STATE_CHANGE"
    16 -> "PACKAGE_UPDATED"
    else -> "desconocido($motivo)"
}

/** Tipo de salida de audio (AudioDeviceInfo.getType), solo los que importan para el coche. */
fun nombreSalidaAudio(tipo: Int): String = when (tipo) {
    1 -> "auricular"
    2 -> "altavoz del móvil"
    3 -> "cascos con cable"
    4 -> "auriculares con cable"
    7 -> "Bluetooth SCO"
    8 -> "Bluetooth A2DP"
    11, 22 -> "USB"
    18 -> "telefonía"
    26, 27, 30 -> "Bluetooth LE"
    else -> "tipo $tipo"
}
