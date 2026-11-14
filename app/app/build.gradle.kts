import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// En GitHub Actions el número de ejecución sube en cada compilación, así que cada
// APK es "más nuevo" que el anterior y Android deja instalarlo encima (fase 1.5).
// En local vale 1.
val numeroCompilacion = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "es.unai.avisosradares"
    compileSdk = 35

    defaultConfig {
        applicationId = "es.unai.avisosradares"
        minSdk = 26
        targetSdk = 35
        versionCode = numeroCompilacion
        versionName = "0.1.$numeroCompilacion"
    }

    // La clave de firma NUNCA está en el repositorio: el workflow la restaura desde
    // secretos de GitHub y pasa su ruta y claves por variables de entorno.
    val rutaClave = System.getenv("KEYSTORE_PATH")
    if (rutaClave != null) {
        signingConfigs {
            create("release") {
                storeFile = file(rutaClave)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (rutaClave != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
