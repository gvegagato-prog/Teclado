// Declaraciones de APIs ocultas de Android (solo para compilar: compileOnly).
// En tiempo de ejecución se usan las clases reales del sistema.
plugins {
    id("com.android.library")
}

android {
    namespace = "app.pera.stubs"
    compileSdk = 36
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
